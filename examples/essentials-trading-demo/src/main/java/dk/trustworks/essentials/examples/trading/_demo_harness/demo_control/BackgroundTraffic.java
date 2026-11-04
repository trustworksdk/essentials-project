/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.trading._demo_harness.demo_control;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.clear_deposit.ClearDeposit;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.credit_deposit.CreditDeposit;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account.OpenAccount;
import dk.trustworks.essentials.examples.trading.accounts.views.account_overview.AccountOverviewQuery;
import dk.trustworks.essentials.examples.trading.accounts.views.positions.PositionsQuery;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.examples.trading.funding.use_cases.link_bank_account.LinkBankAccount;
import dk.trustworks.essentials.examples.trading.funding.use_cases.request_deposit.RequestDeposit;
import dk.trustworks.essentials.examples.trading.funding.use_cases.request_withdrawal.RequestWithdrawal;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.examples.trading.market_data.types.*;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.examples.trading.orders.use_cases.cancel_order.CancelOrder;
import dk.trustworks.essentials.examples.trading.orders.use_cases.place_order.PlaceOrder;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Simulated customers trading and moving money in the background while the demo runs (phase 8 §4, D31): the operator
 * starts it, changes its rate and stops it from {@code /ops}; it never ends on its own and keeps no report.
 * <p>
 * The customers use the same commands as real ones, sent on the command bus: market and marketable limit buys, sells of
 * what they hold, resting limit buys of which half are cancelled, deposits and withdrawals - the load driver's mix
 * (phase 6 §2). The rate is set in orders per <em>exchange</em> minute, so a faster clock means faster traffic and a
 * day looks like a day at any speed, capped at {@link #MAX_ORDERS_PER_SECOND} real. Orders go in only while the market
 * is open, with a burst for the first {@link #BURST} after the open; deposits and withdrawals run through the whole
 * business day. The customers are this instance's own ({@code BG-<instance>-<n>}), opened and funded on first use and
 * kept across restarts, so Alice's screens show only her account.
 */
@Component
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class BackgroundTraffic {
    private static final Logger      log                   = LoggerFactory.getLogger(BackgroundTraffic.class);
    static final         int         MAX_ORDERS_PER_SECOND = 100;
    static final         Duration    BURST                 = Duration.ofMinutes(15);
    private static final int         BURST_FACTOR          = 4;
    private static final int         CUSTOMERS             = 20;
    private static final Money       SEED_CASH             = Money.of("250000", CurrencyCode.USD);
    private static final UserId      OWNER                 = UserId.of("USR-BACKGROUND");
    private static final BankAccount BANK_ACCOUNT          = new BankAccount("GB82WEST12345698765432", "Background Customer");
    private static final List<InstrumentId> INSTRUMENTS    = List.of(InstrumentId.of("ACME"), InstrumentId.of("AAPL"), InstrumentId.of("MSFT"),
                                                                     InstrumentId.of("NVDA"), InstrumentId.of("AMZN"));
    private static final Duration    WINDOW                = Duration.ofMinutes(1);
    /**
     * Below this buying power a customer sells what it holds, or deposits, instead of buying: the mix buys more than it
     * sells, and without it the customers run dry.
     */
    private static final BigDecimal  LOW_BUYING_POWER      = new BigDecimal("20000");

    /**
     * What the page shows.
     *
     * @param running                 whether it runs
     * @param ordersPerExchangeMinute the rate
     * @param customers               the simulated customers
     * @param lastMinute              what it sent in the last real minute, by kind
     * @param outcomesLastMinute      what became of its orders and payments in the last real minute, by status
     * @param message                 what it is doing, in words
     */
    public record Status(boolean running, double ordersPerExchangeMinute, int customers, Map<String, Long> lastMinute,
                         Map<String, Long> outcomesLastMinute, String message) {
    }

    private final CommandBus           commandBus;
    private final MarketQuotes         quotes;
    private final PositionsQuery       positions;
    private final AccountOverviewQuery overviews;
    private final ExchangeClockControl clock;
    private final JdbcTemplate         jdbcTemplate;
    private final String               prefix;
    private final ScheduledExecutorService ticker  = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("background-traffic").daemon().factory());
    private final ExecutorService          actions = Executors.newFixedThreadPool(4, Thread.ofPlatform().name("background-traffic-action-", 0).daemon().factory());
    private final Semaphore                inFlight = new Semaphore(64);
    private final Map<String, ConcurrentLinkedDeque<Long>> sent = new ConcurrentHashMap<>();
    private final AtomicLong               sequence = new AtomicLong(System.currentTimeMillis() % 1_000_000_000L);
    private final Random                   random   = new Random();
    private final List<AccountId>          customers = new CopyOnWriteArrayList<>();

    private volatile ScheduledFuture<?>          running;
    private volatile double                      ordersPerExchangeMinute = 30;
    private volatile String                      message                 = "Stopped";
    private volatile ExchangeClockControl.ClockState clockState;
    private volatile long                        clockReadAt;
    private volatile Instant                     burstUntil              = Instant.MIN;
    private volatile String                      lastPhase;
    private double orderCredit;
    private double paymentCredit;
    private long   lastTick;

    public BackgroundTraffic(CommandBus commandBus,
                             MarketQuotes quotes,
                             PositionsQuery positions,
                             AccountOverviewQuery overviews,
                             ExchangeClockControl clock,
                             JdbcTemplate jdbcTemplate,
                             @Value("${essentials.shard-owned-queue.instance-id:local}") String instanceId) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.quotes = requireNonNull(quotes, "No quotes provided");
        this.positions = requireNonNull(positions, "No positions provided");
        this.overviews = requireNonNull(overviews, "No overviews provided");
        this.clock = requireNonNull(clock, "No clock provided");
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.prefix = "BG-" + requireNonNull(instanceId, "No instanceId provided").replaceAll("[^A-Za-z0-9]", "") + "-";
    }

    public synchronized Status start(double ordersPerExchangeMinute) {
        rate(ordersPerExchangeMinute);
        if (running == null) {
            message = "Opening and funding " + CUSTOMERS + " customers";
            orderCredit = 0;
            paymentCredit = 0;
            lastTick = System.nanoTime();
            lastPhase = null;
            running = ticker.scheduleWithFixedDelay(this::tickQuietly, 0, 250, TimeUnit.MILLISECONDS);
        }
        return status();
    }

    public synchronized Status stop() {
        if (running != null) {
            running.cancel(false);
            running = null;
        }
        message = "Stopped";
        return status();
    }

    public Status rate(double ordersPerExchangeMinute) {
        if (ordersPerExchangeMinute < 0 || ordersPerExchangeMinute > 600) {
            throw new IllegalArgumentException("ordersPerExchangeMinute must be 0 to 600, was " + ordersPerExchangeMinute);
        }
        this.ordersPerExchangeMinute = ordersPerExchangeMinute;
        return status();
    }

    /**
     * A burst of orders now, as at the open: {@link #BURST_FACTOR} times the rate for the next {@link #BURST} of exchange
     * time. Orders still go in only while the market is open.
     */
    public Status burst() {
        var state = clockState;
        burstUntil = (state != null ? state.now() : Instant.now()).plus(BURST);
        return status();
    }

    public Status status() {
        var cutoff = System.currentTimeMillis() - WINDOW.toMillis();
        var counts = new TreeMap<String, Long>();
        sent.forEach((kind, times) -> {
            times.removeIf(time -> time < cutoff);
            counts.put(kind, (long) times.size());
        });
        return new Status(running != null, ordersPerExchangeMinute, customers.size(), counts, outcomes(), message);
    }

    private Map<String, Long> outcomes() {
        var outcomes = new TreeMap<String, Long>();
        jdbcTemplate.query("SELECT status, count(*) AS n FROM projection_orders WHERE order_id LIKE ? AND updated_at > now() - interval '1 minute' GROUP BY status",
                           rs -> { outcomes.put("order " + rs.getString("status"), rs.getLong("n")); },
                           prefix + "%");
        jdbcTemplate.query("SELECT kind, status, count(*) AS n FROM projection_funding_payment WHERE reference LIKE ? AND updated_at > now() - interval '1 minute' GROUP BY kind, status",
                           rs -> { outcomes.put(rs.getString("kind").toLowerCase() + " " + rs.getString("status"), rs.getLong("n")); },
                           prefix + "%");
        return outcomes;
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException e) {
            message = "Waiting: " + e.getMessage();
            log.debug("Background traffic tick failed", e);
        }
    }

    private void tick() {
        if (customers.size() < CUSTOMERS) {
            openCustomers();
            message = "Running";
        }
        var now = System.nanoTime();
        if (clockState == null || now - clockReadAt > 2_000_000_000L) {
            clockState = clock.state();
            clockReadAt = now;
        }
        var state            = clockState;
        var realSeconds      = (now - lastTick) / 1e9;
        lastTick = now;
        if (state.job() != null && state.job().state().equals("RUNNING")) {
            message = "Waiting while the clock jumps";
            return;
        }
        if (!state.running()) {
            message = "Waiting: the exchange's clock is paused";
            return;
        }
        var exchangeMinutes = realSeconds * state.speed().doubleValue() / 60;
        var exchangeNow     = state.now().plusNanos((long) ((now - clockReadAt) * state.speed().doubleValue()));
        var phase           = state.phase();
        if ("OPEN".equals(phase) && !"OPEN".equals(lastPhase) && lastPhase != null) {
            burstUntil = exchangeNow.plus(BURST); // the open's rush
        }
        lastPhase = phase;
        if ("NO_TRADING_DAY".equals(phase)) {
            message = "No trading today: the customers wait for the next business day";
            return;
        }
        paymentCredit += ordersPerExchangeMinute / 10 * exchangeMinutes;
        if ("OPEN".equals(phase)) {
            var burst = exchangeNow.isBefore(burstUntil);
            orderCredit += ordersPerExchangeMinute * (burst ? BURST_FACTOR : 1) * exchangeMinutes;
            message = burst ? "Market open - the open's burst" : "Market open - trading";
        } else {
            orderCredit = 0;
            message = "Market closed - the customers only move money";
        }
        var maxPerTick = MAX_ORDERS_PER_SECOND * realSeconds;
        orderCredit = Math.min(orderCredit, maxPerTick + 1);
        paymentCredit = Math.min(paymentCredit, maxPerTick + 1);
        for (; orderCredit >= 1; orderCredit--) {
            submit(this::orderAction);
        }
        for (; paymentCredit >= 1; paymentCredit--) {
            submit(this::paymentAction);
        }
    }

    private void submit(Runnable action) {
        if (!inFlight.tryAcquire()) {
            count("skipped (busy)");
            return;
        }
        actions.execute(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                count("refused: " + reason(e));
                log.debug("Background traffic action refused: {}", e.toString());
            } finally {
                inFlight.release();
            }
        });
    }

    private void openCustomers() {
        var businessDate = clock.state().openBusinessDate();
        for (var n = customers.size() + 1; n <= CUSTOMERS; n++) {
            var accountId = AccountId.of(prefix + n);
            if (overviews.findAccount(accountId).isEmpty()) {
                commandBus.send(new OpenAccount(accountId, OWNER, CurrencyCode.USD));
                commandBus.send(new LinkBankAccount(accountId, BANK_ACCOUNT));
                var seed = DepositId.of("SEED-" + accountId);
                commandBus.send(new CreditDeposit(accountId, seed, SEED_CASH, businessDate != null ? businessDate : LocalDate.of(2030, 1, 7)));
                commandBus.send(new ClearDeposit(accountId, seed));
            }
            customers.add(accountId);
        }
    }

    private void orderAction() {
        var customer   = customers.get(random.nextInt(customers.size()));
        var instrument = INSTRUMENTS.get(random.nextInt(INSTRUMENTS.size()));
        var quantity   = Quantity.of(random.nextLong(1, 11));
        var orderId    = OrderId.of(prefix + "O" + sequence.incrementAndGet());
        var roll       = random.nextInt(100);
        if (roll < 65 && lowOnMoney(customer)) {
            roll = 70; // sell instead, or deposit when there is nothing to sell
        }
        if (roll < 45) {
            send("market buy", new PlaceOrder(orderId, customer, instrument, TradeSide.BUY, OrderType.MARKET, quantity, null, TimeInForce.DAY));
        } else if (roll < 65) {
            var ask = quotes.bestAsk(instrument).orElseThrow(() -> new IllegalStateException("No quote for " + instrument));
            send("limit buy", new PlaceOrder(orderId, customer, instrument, TradeSide.BUY, OrderType.LIMIT, quantity, scaled(ask, "1.01", RoundingMode.UP), TimeInForce.DAY));
        } else if (roll < 85) {
            var held = positions.positions(customer).stream()
                                .filter(position -> position.instrumentId().equals(instrument) && position.sellable().value() > 0)
                                .findFirst();
            if (held.isEmpty() && lowOnMoney(customer)) {
                send("deposit", new RequestDeposit(DepositId.of(prefix + "D" + sequence.incrementAndGet()), customer,
                                                   Money.of(BigDecimal.valueOf(random.nextInt(20_000, 50_001)), CurrencyCode.USD)));
            } else if (held.isEmpty()) {
                send("market buy", new PlaceOrder(orderId, customer, instrument, TradeSide.BUY, OrderType.MARKET, quantity, null, TimeInForce.DAY));
            } else {
                var sellable = held.get().sellable().value();
                send("sell", new PlaceOrder(orderId, customer, instrument, TradeSide.SELL, OrderType.MARKET, Quantity.of(Math.min(sellable, quantity.value())), null, TimeInForce.DAY));
            }
        } else {
            var bid = quotes.bestBid(instrument).orElseThrow(() -> new IllegalStateException("No quote for " + instrument));
            send("resting limit", new PlaceOrder(orderId, customer, instrument, TradeSide.BUY, OrderType.LIMIT, quantity, scaled(bid, "0.97", RoundingMode.DOWN), TimeInForce.DAY));
            if (random.nextBoolean()) {
                ticker.schedule(() -> submit(() -> send("cancel", new CancelOrder(customer, orderId))), 3, TimeUnit.SECONDS);
            }
        }
    }

    private void paymentAction() {
        var customer = customers.get(random.nextInt(customers.size()));
        if (random.nextInt(100) < 70) {
            send("deposit", new RequestDeposit(DepositId.of(prefix + "D" + sequence.incrementAndGet()), customer,
                                               Money.of(BigDecimal.valueOf(random.nextInt(500, 5_001)), CurrencyCode.USD)));
        } else {
            // Only what the account can withdraw now: cash that is held or not yet cleared cannot leave it
            var amount       = BigDecimal.valueOf(random.nextInt(100, 1_001));
            var withdrawable = overviews.findAccount(customer).map(overview -> overview.withdrawable().getAmount().value()).orElse(BigDecimal.ZERO);
            if (withdrawable.compareTo(amount) >= 0) {
                send("withdrawal", new RequestWithdrawal(WithdrawalId.of(prefix + "W" + sequence.incrementAndGet()), customer, Money.of(amount, CurrencyCode.USD)));
            }
        }
    }

    private boolean lowOnMoney(AccountId customer) {
        return overviews.findAccount(customer)
                        .map(overview -> overview.buyingPower().getAmount().value().compareTo(LOW_BUYING_POWER) < 0)
                        .orElse(true);
    }

    /**
     * Sends a command as the customer API does: a conflict on the account's stream - two of the customer's actions at
     * once - is decided again, up to 5 times.
     */
    private void send(String kind, Object command) {
        for (var attempt = 1; ; attempt++) {
            try {
                commandBus.send(command);
                count(kind);
                return;
            } catch (RuntimeException e) {
                if (!isConflict(e) || attempt == 5) {
                    throw e;
                }
            }
        }
    }

    private static boolean isConflict(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticAppendToStreamException) {
                return true;
            }
        }
        return false;
    }

    private void count(String kind) {
        sent.computeIfAbsent(kind, ignored -> new ConcurrentLinkedDeque<>()).add(System.currentTimeMillis());
    }

    /**
     * Why the brokerage refused an action, in a few words, from the rule the innermost cause names.
     */
    private static String reason(Throwable e) {
        var cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        var text = String.valueOf(cause.getMessage());
        if (isConflict(e)) {
            return "account busy, gave up after 5 tries";
        }
        if (text.contains("for WITHDRAWAL")) {
            return "not enough withdrawable cash";
        }
        if (text.contains("for ORDER")) {
            return "not enough buying power";
        }
        if (text.contains("No current price") || text.contains("No quote")) {
            return "no current price";
        }
        if (text.contains("sellable") || text.contains("shares")) {
            return "nothing to sell";
        }
        return cause.getClass().getSimpleName();
    }

    private static Money scaled(Money price, String factor, RoundingMode rounding) {
        return Money.of(price.getAmount().value().multiply(new BigDecimal(factor)).setScale(2, rounding), price.getCurrency());
    }

    @PreDestroy
    public void shutdown() {
        ticker.shutdownNow();
        actions.shutdownNow();
    }
}
