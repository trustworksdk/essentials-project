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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.credit_deposit.CreditDeposit;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account.OpenAccount;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.examples.trading.market_data.types.*;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.examples.trading.orders.use_cases.cancel_order.CancelOrder;
import dk.trustworks.essentials.examples.trading.orders.use_cases.place_order.PlaceOrder;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.*;
import org.slf4j.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Phase 3's load scenario (design {@code phase-3-orders-and-execution.md} §8): simulated customers placing orders
 * through the same command types the HTTP API sends, against the stub exchange, and the time from an order's
 * placement to its booking on the account.
 * <p>
 * Each customer is one account and places its orders one after another, as a person at an order ticket does; the
 * {@code threads} customers trade at once. What contends is everything after placement: the exchange's reports on the
 * order's stream, and the booking and release on the account's stream while the customer's next order holds funds on
 * it. A mix of three kinds of buy:
 * <ul>
 *     <li><b>market</b> — held at ask x collar, filled at the ask;</li>
 *     <li><b>marketable limit</b> — limit 1 % above the ask, filled at the ask, the rest of the hold released;</li>
 *     <li><b>resting limit</b> ({@code restingSharePercent}) — limit 10 % below the bid, never filled, cancelled by the
 *     customer straight after placing it, which exercises the cancel round trip and the release.</li>
 * </ul>
 * The run is complete when every placed order has released its hold ({@code FundsReleased}) and every fillable one is
 * booked ({@code TradeBooked}), counted in the {@code Accounts} event table. End-to-end latency is per filled order:
 * the {@code TradeBooked} event's timestamp minus the moment {@code PlaceOrder} returned.
 * <p>
 * Fresh account ids per run ({@code OF-<run>-<n>}). Buys only: a sell needs settled shares. Not a slice: writes go
 * through command types, the price through {@code market_data}'s published seam (see this package's {@code CLAUDE.md}).
 */
@Component
public class OrderFlowLoadGenerator implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(OrderFlowLoadGenerator.class);

    private static final UserId   LOAD_OWNER    = UserId.of("USR-LOAD");
    private static final Money    DEPOSIT       = Money.of("100000000", CurrencyCode.USD);
    private static final int      MAX_ATTEMPTS  = 20;
    private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(15);

    public record Settings(int customers, int ordersPerCustomer, int threads, int marketSharePercent, int restingSharePercent, String instrument) {
        public Settings {
            requireNonNull(instrument, "No instrument provided");
            if (customers < 1 || ordersPerCustomer < 1 || threads < 1 || marketSharePercent < 0 || restingSharePercent < 0
                || marketSharePercent + restingSharePercent > 100) {
                throw new IllegalArgumentException("Invalid settings: " + customers + " customers, " + ordersPerCustomer + " orders each, " + threads
                                                   + " threads, " + marketSharePercent + "% market, " + restingSharePercent + "% resting");
            }
        }
    }

    private final CommandBus          commandBus;
    private final MarketQuotes        marketQuotes;
    private final AccountEventCounter eventCounter;
    private final ExecutorService     runner = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("order-flow-load").daemon().factory());

    private final    AtomicReference<OrderFlowResultView> lastResult = new AtomicReference<>();
    private volatile Run                                  current;

    public OrderFlowLoadGenerator(CommandBus commandBus, MarketQuotes marketQuotes, AccountEventCounter eventCounter) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.marketQuotes = requireNonNull(marketQuotes, "No marketQuotes provided");
        this.eventCounter = requireNonNull(eventCounter, "No eventCounter provided");
    }

    public synchronized OrderFlowResultView start(Settings settings) {
        requireNonNull(settings, "No settings provided");
        if (current != null) {
            throw new IllegalStateException("An order flow run is in progress: " + current.runId);
        }
        if (marketQuotes.bestAsk(InstrumentId.of(settings.instrument())).isEmpty()) {
            throw new IllegalStateException("No current quote for '" + settings.instrument() + "'; is it in trading-demo.market-data.synthetic.instruments?");
        }
        var run = new Run(Long.toString(System.currentTimeMillis(), 36), settings);
        current = run;
        lastResult.set(run.view("RUNNING", null));
        runner.submit(() -> execute(run));
        return lastResult.get();
    }

    public OrderFlowResultView status() {
        var run = current;
        return run != null ? run.view("RUNNING", null) : lastResult.get();
    }

    @Override
    public void destroy() {
        runner.shutdownNow();
    }

    private void execute(Run run) {
        try {
            var settings = run.settings;
            log.info("Order flow {}: {} customers x {} orders, {} at once, {}% market, {}% resting", run.runId, settings.customers(),
                     settings.ordersPerCustomer(), settings.threads(), settings.marketSharePercent(), settings.restingSharePercent());
            var setupStarted = System.nanoTime();
            for (var i = 0; i < settings.customers(); i++) {
                var accountId = run.account(i);
                send(new OpenAccount(accountId, LOAD_OWNER, CurrencyCode.USD), run);
                send(new CreditDeposit(accountId, DepositId.of("DEP-" + accountId), DEPOSIT, LocalDate.now()), run);
            }
            run.setupMs = millisSince(setupStarted);

            var pool = Executors.newFixedThreadPool(settings.threads(), Thread.ofPlatform().name("order-flow-customer-", 0).daemon().factory());
            run.placeStarted = System.nanoTime();
            try {
                var customers = new ArrayList<Future<?>>();
                for (var i = 0; i < settings.customers(); i++) {
                    var customer = i;
                    customers.add(pool.submit(() -> trade(run, customer)));
                }
                for (var customer : customers) {
                    customer.get();
                }
            } finally {
                pool.shutdownNow();
            }
            run.placeMs = millisSince(run.placeStarted);

            awaitDrained(run);
            run.e2eNanos = endToEndNanos(run);
            lastResult.set(run.view("COMPLETED", null));
            log.info("Order flow {} completed: {}", run.runId, lastResult.get());
        } catch (Throwable e) {
            log.error("Order flow {} failed", run.runId, e);
            lastResult.set(run.view("FAILED", e.toString()));
        } finally {
            current = null;
        }
    }

    private void trade(Run run, int customer) {
        var accountId  = run.account(customer);
        var instrument = InstrumentId.of(run.settings.instrument());
        var random     = ThreadLocalRandom.current();
        for (var n = 0; n < run.settings.ordersPerCustomer(); n++) {
            var orderId  = OrderId.of(accountId + "-" + n);
            var quantity = Quantity.of(random.nextLong(1, 11));
            var roll     = random.nextInt(100);
            var resting  = roll < run.settings.restingSharePercent();
            var market   = !resting && roll < run.settings.restingSharePercent() + run.settings.marketSharePercent();
            var order = market
                        ? new PlaceOrder(orderId, accountId, instrument, TradeSide.BUY, OrderType.MARKET, quantity, null, TimeInForce.DAY)
                        : new PlaceOrder(orderId, accountId, instrument, TradeSide.BUY, OrderType.LIMIT, quantity, limitFor(instrument, resting), TimeInForce.DAY);
            var started = System.nanoTime();
            if (!send(order, run)) {
                continue;
            }
            run.placeLatencies.add(System.nanoTime() - started);
            run.placed.incrementAndGet();
            if (resting) {
                run.resting.incrementAndGet();
                send(new CancelOrder(accountId, orderId), run);
            } else {
                run.placedAt.put(orderId.toString(), Instant.now());
            }
        }
    }

    private Money limitFor(InstrumentId instrument, boolean resting) {
        if (resting) {
            var bid = marketQuotes.bestBid(instrument).orElseThrow(() -> new IllegalStateException("Quote for '" + instrument + "' went stale"));
            return Money.of(bid.getAmount().value().multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.DOWN), bid.getCurrency());
        }
        var ask = marketQuotes.bestAsk(instrument).orElseThrow(() -> new IllegalStateException("Quote for '" + instrument + "' went stale"));
        return Money.of(ask.getAmount().value().multiply(new BigDecimal("1.01")).setScale(2, RoundingMode.UP), ask.getCurrency());
    }

    /**
     * @return false when the command was refused (counted), true when it was handled
     */
    private boolean send(Object command, Run run) {
        for (var attempt = 1; ; attempt++) {
            try {
                commandBus.send(command);
                return true;
            } catch (RuntimeException e) {
                if (isConflict(e) && attempt < MAX_ATTEMPTS) {
                    run.conflicts.incrementAndGet();
                    continue;
                }
                run.refused.incrementAndGet();
                log.debug("Order flow command refused: {}", e.toString());
                return false;
            }
        }
    }

    private void awaitDrained(Run run) throws InterruptedException {
        var prefix   = run.accountPrefix();
        var deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        while (true) {
            run.bookings = eventCounter.countEventsOfTypeForAccountsStartingWith(prefix, "TradeBooked");
            run.releases = eventCounter.countEventsOfTypeForAccountsStartingWith(prefix, "FundsReleased");
            if (run.bookings >= run.placed.get() - run.resting.get() && run.releases >= run.placed.get()) {
                run.drainMs = millisSince(run.placeStarted);
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Not drained after " + DRAIN_TIMEOUT + ": " + run.bookings + " bookings, " + run.releases + " releases of "
                                                + run.placed.get() + " placed orders (" + run.resting.get() + " resting)");
            }
            Thread.sleep(100);
        }
    }

    /**
     * The booking's own timestamp, from the event table, against the moment the placement returned.
     */
    private long[] endToEndNanos(Run run) {
        var bookedAt = eventCounter.tradeBookingTimesForAccountsStartingWith(run.accountPrefix());
        return run.placedAt.entrySet().stream()
                           .filter(placed -> bookedAt.containsKey("EXE-" + placed.getKey()))
                           .mapToLong(placed -> Math.max(0, Duration.between(placed.getValue(), bookedAt.get("EXE-" + placed.getKey())).toNanos()))
                           .sorted()
                           .toArray();
    }

    private static boolean isConflict(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticAppendToStreamException) {
                return true;
            }
        }
        return false;
    }

    private static long millisSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static double percentileMs(long[] sortedNanos, double percentile) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        var index = (int) Math.min(sortedNanos.length - 1, Math.ceil(percentile * sortedNanos.length) - 1);
        return sortedNanos[Math.max(0, index)] / 1_000_000.0;
    }

    private static final class Run {
        final String                         runId;
        final Settings                       settings;
        final AtomicLong                     placed         = new AtomicLong();
        final AtomicLong                     resting        = new AtomicLong();
        final AtomicLong                     refused        = new AtomicLong();
        final AtomicLong                     conflicts      = new AtomicLong();
        final ConcurrentLinkedQueue<Long>    placeLatencies = new ConcurrentLinkedQueue<>();
        final Map<String, Instant>           placedAt       = new ConcurrentHashMap<>();
        volatile long                        placeStarted;
        volatile long                        setupMs;
        volatile long                        placeMs;
        volatile long                        drainMs;
        volatile long                        bookings;
        volatile long                        releases;
        volatile long[]                      e2eNanos       = new long[0];

        Run(String runId, Settings settings) {
            this.runId = runId;
            this.settings = settings;
        }

        String accountPrefix() {
            return "OF-" + runId + "-";
        }

        AccountId account(int index) {
            return AccountId.of(accountPrefix() + index);
        }

        OrderFlowResultView view(String state, String error) {
            var latencies = placeLatencies.stream().mapToLong(Long::longValue).sorted().toArray();
            var seconds   = placeMs > 0 ? placeMs / 1000.0 : 0;
            return new OrderFlowResultView(runId,
                                           state,
                                           settings.customers(),
                                           settings.ordersPerCustomer(),
                                           settings.threads(),
                                           placed.get(),
                                           resting.get(),
                                           refused.get(),
                                           conflicts.get(),
                                           setupMs,
                                           placeMs,
                                           seconds > 0 ? placed.get() / seconds : 0,
                                           percentileMs(latencies, 0.50),
                                           percentileMs(latencies, 0.99),
                                           drainMs,
                                           drainMs > 0 ? placed.get() / (drainMs / 1000.0) : 0,
                                           bookings,
                                           releases,
                                           percentileMs(e2eNanos, 0.50),
                                           percentileMs(e2eNanos, 0.99),
                                           e2eNanos.length == 0 ? 0 : e2eNanos[e2eNanos.length - 1] / 1_000_000.0,
                                           error);
        }
    }
}
