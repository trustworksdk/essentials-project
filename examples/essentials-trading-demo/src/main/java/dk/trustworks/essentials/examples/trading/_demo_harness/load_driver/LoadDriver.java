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
package dk.trustworks.essentials.examples.trading._demo_harness.load_driver;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The phase 6 load driver (design {@code docs/design/phase-6-realistic-load.md}): simulated customers trading and
 * moving money through the brokerage's public HTTP API, in logged-in sessions, from a JVM of its own (Q-1, Q-2). Not
 * part of the application: a plain {@code main}, no Spring, started by {@code run-load.sh}.
 * <p>
 * A run registers and funds the customer population it needs (once - later runs reuse it), then for
 * {@code business-days} closes of the exchange's calendar places orders at a steady rate and at a burst rate in the
 * first part of each business day (§3), with account reads before each order and a stream of deposits and withdrawals.
 * Customers act only inside a business day of the exchange's calendar ({@code GET /api/calendar}), from its local
 * midnight to its cutoff, so every trade is dated the day it is placed: no weekend or holiday trading, which the exchange
 * (run without trading sessions) would otherwise accept and date the next business day - one settlement date would then
 * carry three days of trading.
 * The customers active in a business day are drawn afresh at each close. Then it waits for every order and payment to
 * reach its outcome, for one more close so reconciliation has run, and reports: its own request latencies and outcomes,
 * and what the brokerage recorded ({@code GET /api/admin/load-report}), reconciliation cases and missing statements.
 * <p>
 * The load is open-loop: actions start at the configured rates whatever the brokerage's latency, as customers do; an
 * action that would exceed {@code max-in-flight} is skipped and counted ({@code skipped:driver-saturated}), so a
 * saturated driver shows in the report instead of quietly lowering the rate. Inside a fault window and for 30 s after
 * it, skips are counted apart ({@code skipped:driver-saturated-in-fault-window}): actions waiting out the fault are its
 * cost, not a driver too small for the load.
 */
public final class LoadDriver {
    static final String PASSWORD = "load-driver-password";
    static final String IBAN     = "GB82WEST12345698765432";

    private record Customer(int n, String accountId, BrokerageHttp.Session session) {
    }

    private final LoadDriverSettings       settings;
    private final StepStats                stats     = new StepStats();
    private final BrokerageHttp            http;
    private final SimulatorFaults          faults;
    private final List<Customer>           customers = new ArrayList<>();
    private final ConcurrentMap<String, double[]> quotes = new ConcurrentHashMap<>();
    private final AtomicLong               orderSequence   = new AtomicLong();
    private final AtomicLong               paymentSequence = new AtomicLong();
    private final Semaphore                inFlight;
    private final ExecutorService          actions   = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("load-driver-", 0).factory());
    private volatile List<Customer>        activeToday = List.of();
    private long                           closeCursor;
    private volatile LocalDate             lastClosed;
    private final Map<LocalDate, Instant>  cutoffs   = new HashMap<>();
    private ZoneId                         calendarZone;
    private Instant                        clockReading;
    private long                           clockReadAt;
    private double                         clockSpeed;
    private volatile Instant               faultOpened; // a fault window (F-2, F-4), on the driver's clock
    private volatile Instant               faultClosed;
    private volatile Instant               targetDown;  // F-4: when the restarted simulator stopped answering
    private volatile Instant               quotesBack;  // F-4 on the exchange: every instrument quoted again after it

    LoadDriver(LoadDriverSettings settings) {
        this.settings = settings;
        this.http = new BrokerageHttp(stats, settings.operatorUser(), settings.operatorPassword());
        this.inFlight = new Semaphore(settings.maxInFlight());
        this.faults = new SimulatorFaults(http, List.of(settings.exchangeUrl(), settings.gatewayUrl()));
    }

    public static void main(String[] args) throws Exception {
        var settings = LoadDriverSettings.fromArguments(args);
        var passed   = new LoadDriver(settings).run();
        System.exit(passed ? 0 : 1);
    }

    boolean run() throws Exception {
        log("run %s: %s", settings.runToken(), settings);
        var dayMillis = realBusinessDayMillis();
        log("the exchange's business day lasts %.1f real seconds", dayMillis / 1000.0);
        loadCalendar();
        setUpPopulation();
        skipToTheLatestClose();
        if (settings.fault().equals("F-1")) {
            // From the first order on, so every flow's deliveries are damaged; cleared only after the drain
            faults.applyDeliveryFaults(SimulatorFaults.deliveryFaultPolicy(Math.abs((long) settings.runToken().hashCode())));
            log("fault F-1: delivery faults on every webhook subscription of both simulators");
        } else if (settings.fault().equals("F-2")) {
            faults.findSubscriptions(); // before the window, so a failure to reach the simulators stops the run early
        }
        scheduler.scheduleAtFixedRate(this::refreshQuotes, 0, 1, TimeUnit.SECONDS);
        chooseActiveCustomers();

        var started     = System.nanoTime();
        var closes      = 0;
        var dayStarted  = -1L;
        var lastTick    = System.nanoTime();
        var lastPoll    = 0L;
        var wasOpen     = tradingOpen();
        var faultLoggedClosed = false;
        var orderCredit = 0.0;
        var payCredit   = 0.0;
        while (closes < settings.businessDays()) {
            Thread.sleep(50);
            var now = System.nanoTime();
            if (now - lastPoll > 1_000_000_000L) {
                lastPoll = now;
                var newCloses = pollCloses();
                if (newCloses > 0) {
                    closes += newCloses;
                    chooseActiveCustomers();
                    log("close %d of %d; %s", closes, settings.businessDays(), progress());
                }
                if (now - clockReadAt > 5_000_000_000L) {
                    readClock();
                }
            }
            var open = tradingOpen();
            if (open && !wasOpen) {
                dayStarted = now; // the open: a business day's window begins
                if (faultOpened == null && closes == settings.faultAtDay() - 1) {
                    if (settings.fault().equals("F-2")) {
                        openWebhookOutage();
                    } else if (settings.fault().equals("F-4")) {
                        openSimulatorRestart();
                    } else if (settings.fault().equals("F-3")) {
                        faultOpened = Instant.now();
                        log("fault window open (F-3): kill the instance now; %s", progress());
                        watchOutage("instance", base() + "/actuator/health");
                    }
                }
            }
            if (faultClosed != null && !faultLoggedClosed && Instant.now().isAfter(faultClosed)) {
                faultLoggedClosed = true;
                log("fault window closed (%s); %s", settings.fault(), progress());
            }
            wasOpen = open;
            var inBurst = dayStarted >= 0 && (now - dayStarted) / 1e6 < settings.burstFraction() * dayMillis;
            var seconds = (now - lastTick) / 1e9;
            lastTick = now;
            if (!open) {
                continue;
            }
            orderCredit += (inBurst ? settings.burstOrdersPerSecond() : settings.ordersPerSecond()) * seconds;
            payCredit += settings.paymentsPerSecond() * seconds;
            for (; orderCredit >= 1; orderCredit--) {
                start(this::orderAction);
            }
            for (; payCredit >= 1; payCredit--) {
                start(this::paymentAction);
            }
        }
        var loadSeconds = (System.nanoTime() - started) / 1e9;
        log("load done after %.0f s; waiting for in-flight actions", loadSeconds);
        if (!inFlight.tryAcquire(settings.maxInFlight(), 60, TimeUnit.SECONDS)) {
            log("in-flight actions did not finish within 60 s");
        }
        scheduler.shutdownNow();

        var report = drain();
        log("waiting for one more close, so reconciliation has run");
        pollCloses(); // closes during the drain do not count: the next one must come after every outcome
        waitForCloses(1, Duration.ofMillis(dayMillis * 3));
        Thread.sleep(10_000);
        report = serverReport();
        var openCases = http.json(http.operatorGet("REPORT", base() + "/api/admin/reconciliation/cases?state=OPEN"));
        var missing   = http.json(http.operatorGet("REPORT", base() + "/api/admin/reconciliation/missing-statements"));
        Map<String, Object> faultEvidence = null;
        ReconciliationBreaks.Result breaks = null;
        if (settings.fault().equals("F-1")) {
            faults.clearDeliveryFaults();
            faultEvidence = faults.evidence();
        } else if (settings.fault().equals("F-2")) {
            faultEvidence = webhookOutageEvidence(report);
        } else if (settings.fault().equals("F-4") || settings.fault().equals("F-3")) {
            faultEvidence = simulatorRestartEvidence(report);
        } else if (settings.fault().equals("F-5")) {
            breaks = new ReconciliationBreaks(http, base(), settings.exchangeUrl(), settings.gatewayUrl()).compare(lastClosed);
            faultEvidence = breaks.evidence();
        }
        return report(report, openCases, missing, faultEvidence, breaks, loadSeconds);
    }

    // ---- the exchange's calendar and clock

    private void loadCalendar() {
        readClock();
        var today    = clockReading.atZone(ZoneOffset.UTC).toLocalDate();
        var calendar = http.plainGet("SETUP", settings.exchangeUrl() + "/api/calendar?from=" + today.minusDays(7) + "&to=" + today.plusDays(400));
        if (!calendar.ok()) {
            throw new IllegalStateException("Cannot read the exchange's calendar: " + calendar);
        }
        var json = http.json(calendar);
        calendarZone = ZoneId.of(json.path("timeZone").asString());
        for (var day : json.path("businessDays")) {
            cutoffs.put(LocalDate.parse(day.path("date").asString()), Instant.parse(day.path("cutoffAt").asString()));
        }
        log("calendar: %d business days, cutoff %s %s", cutoffs.size(), json.path("cutoff").asString(), calendarZone);
    }

    private void readClock() {
        var answer = http.plainGet("EXCHANGE_CLOCK", settings.exchangeUrl() + "/api/clock");
        if (!answer.ok()) {
            return; // the exchange is restarting (F-4): keep extrapolating the last reading
        }
        var clock = http.json(answer);
        clockReading = Instant.parse(clock.path("now").asString());
        clockSpeed = clock.path("speed").asDouble(1);
        clockReadAt = System.nanoTime();
    }

    /**
     * Whether the exchange's clock is inside a business day, between its local midnight and its cutoff.
     */
    private boolean tradingOpen() {
        var now    = clockReading.plusNanos((long) ((System.nanoTime() - clockReadAt) * clockSpeed));
        var cutoff = cutoffs.get(now.atZone(calendarZone).toLocalDate());
        return cutoff != null && now.isBefore(cutoff);
    }

    // ---- setup

    private long realBusinessDayMillis() {
        var clock = http.plainGet("SETUP", settings.exchangeUrl() + "/api/clock");
        if (!clock.ok()) {
            throw new IllegalStateException("Cannot read the exchange's clock: " + clock);
        }
        var json = http.json(clock);
        if (!json.path("running").asBoolean(false)) {
            throw new IllegalStateException("The exchange's clock is not running: " + clock.body());
        }
        return (long) (86_400_000 / json.path("speed").asDouble(1));
    }

    private void setUpPopulation() throws InterruptedException {
        var pool     = Executors.newFixedThreadPool(settings.setupThreads());
        var created  = new AtomicInteger();
        var failures = new ConcurrentLinkedQueue<String>();
        for (var n = 0; n < settings.population(); n++) {
            var customer = new Customer(n, "LDA-" + n, http.new Session(settings.baseUrls().get(n % settings.baseUrls().size()), "lda-" + n + "@load.local", PASSWORD));
            customers.add(customer);
            pool.execute(() -> {
                try {
                    if (setUp(customer)) {
                        created.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    failures.add(customer.accountId() + ": " + e.getMessage());
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.MINUTES);
        if (!failures.isEmpty()) {
            throw new IllegalStateException(failures.size() + " customers could not be set up, e.g. " + failures.peek());
        }
        log("population of %d ready (%d new)", settings.population(), created.get());
    }

    /**
     * @return whether the customer was new
     */
    private boolean setUp(Customer customer) {
        var userId = "USR-LDA-" + customer.n();
        var registered = http.operatorPost("SETUP", base() + "/api/admin/users",
                                           Map.of("userId", userId, "email", "lda-" + customer.n() + "@load.local", "displayName", "Load customer " + customer.n(),
                                                  "roles", List.of("CUSTOMER"), "password", PASSWORD));
        if (!registered.ok() && registered.status() != 409) {
            throw new IllegalStateException("register: " + registered);
        }
        if (customer.session().get("SETUP", "/api/accounts/" + customer.accountId()).ok()) {
            return false;
        }
        var opened = customer.session().post("SETUP", "/api/accounts", Map.of("accountId", customer.accountId(), "ownerId", userId, "currency", "USD"));
        if (!opened.ok()) {
            throw new IllegalStateException("open account: " + opened);
        }
        // The account's owner is a projection, so the first calls on a new account may be refused for a moment
        retry("link bank account", () -> customer.session().put("SETUP", "/api/accounts/" + customer.accountId() + "/bank-account",
                                                                 Map.of("accountId", customer.accountId(), "bankAccount", Map.of("iban", IBAN, "holderName", "Load customer " + customer.n()))));
        var seed = "SEED-" + customer.accountId();
        retry("seed deposit", () -> http.operatorPost("SETUP", base() + "/api/admin/accounts/" + customer.accountId() + "/deposits",
                                                     Map.of("accountId", customer.accountId(), "depositId", seed, "amount", usd(BigDecimal.valueOf(settings.seedCash())),
                                                            "businessDate", "2030-01-07")));
        retry("seed clearance", () -> http.operatorPost("SETUP", base() + "/api/admin/accounts/" + customer.accountId() + "/deposit-clearances",
                                                       Map.of("accountId", customer.accountId(), "depositId", seed)));
        retry("account readable", () -> customer.session().get("SETUP", "/api/accounts/" + customer.accountId()));
        return true;
    }

    private static void retry(String what, java.util.function.Supplier<BrokerageHttp.Response> call) {
        BrokerageHttp.Response last = null;
        for (var attempt = 0; attempt < 60; attempt++) {
            last = call.get();
            if (last.ok()) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(what + ": interrupted");
            }
        }
        throw new IllegalStateException(what + ": " + last);
    }

    // ---- the exchange's business days

    private void skipToTheLatestClose() {
        while (pollCloses() > 0) {
            // read the log to its end; only closes from now on count
        }
    }

    /**
     * @return the closes the exchange has logged since the last call
     */
    private int pollCloses() {
        var closes = 0;
        while (true) {
            var page = http.plainGet("EXCHANGE_LOG", settings.exchangeUrl() + "/api/events?eventType=BusinessDayClosed&limit=1000&after=" + closeCursor);
            if (!page.ok()) {
                return closes;
            }
            var events = http.json(page);
            if (!events.isArray() || events.isEmpty()) {
                return closes;
            }
            for (var event : events) {
                closeCursor = Math.max(closeCursor, event.path("sequence").asLong());
                var date = event.path("payload").path("businessDate").asString(null);
                if (date != null && (lastClosed == null || LocalDate.parse(date).isAfter(lastClosed))) {
                    lastClosed = LocalDate.parse(date);
                }
                closes++;
            }
        }
    }

    private void waitForCloses(int count, Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        var seen     = 0;
        while (seen < count && System.nanoTime() < deadline) {
            Thread.sleep(1000);
            seen += pollCloses();
        }
    }

    private void chooseActiveCustomers() {
        var shuffled = new ArrayList<>(customers);
        Collections.shuffle(shuffled);
        activeToday = List.copyOf(shuffled.subList(0, settings.activePerDay()));
    }

    // ---- customer actions

    private void start(Runnable action) {
        if (!inFlight.tryAcquire()) {
            // Inside a fault window, and for 30 s after it, actions waiting on the fault are the fault's cost (F-3's
            // placements retry until the instance answers), not a driver too small for the load
            var window = faultOpened != null && (faultClosed == null || Instant.now().isBefore(faultClosed.plusSeconds(30)));
            stats.count(window ? "skipped:driver-saturated-in-fault-window" : "skipped:driver-saturated");
            return;
        }
        actions.execute(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                stats.count("action-failed:" + e.getClass().getSimpleName());
            } finally {
                inFlight.release();
            }
        });
    }

    private void orderAction() {
        var random   = ThreadLocalRandom.current();
        var customer = activeToday.get(random.nextInt(activeToday.size()));
        var session  = customer.session();
        var account  = "/api/accounts/" + customer.accountId();
        for (var i = 0; i < settings.readsPerOrder(); i++) {
            switch (i % 3) {
                case 0 -> session.get("READ_ACCOUNT", account);
                case 1 -> session.get("READ_POSITIONS", account + "/positions");
                default -> session.get("READ_ORDERS", account + "/orders");
            }
        }
        var instrument = settings.instruments().get(random.nextInt(settings.instruments().size()));
        var quantity   = 1 + random.nextInt(settings.maxQuantity());
        var pick       = random.nextInt(100);
        if (pick < settings.marketBuyPercent()) {
            place(customer, "PLACE_MARKET_BUY", instrument, "BUY", "MARKET", quantity, null);
        } else if (pick < settings.marketBuyPercent() + settings.limitBuyPercent()) {
            var quote = quotes.get(instrument);
            if (quote == null) {
                place(customer, "PLACE_MARKET_BUY", instrument, "BUY", "MARKET", quantity, null);
            } else {
                place(customer, "PLACE_LIMIT_BUY", instrument, "BUY", "LIMIT", quantity, cents(quote[1] * 1.05));
            }
        } else if (pick < settings.marketBuyPercent() + settings.limitBuyPercent() + settings.sellPercent()) {
            sell(customer, quantity);
        } else {
            var quote = quotes.get(instrument);
            if (quote == null) {
                stats.count("resting-skipped:no-quote");
                return;
            }
            var orderId = place(customer, "PLACE_RESTING", instrument, "BUY", "LIMIT", quantity, cents(quote[0] * 0.95));
            if (orderId != null && random.nextInt(100) < settings.cancelRestingPercent()) {
                // Cancelled a few seconds later, or left to expire at the close (DAY)
                try {
                    scheduler.schedule(() -> start(() -> session.post("CANCEL", account + "/orders/" + orderId + "/cancellation", null)),
                                       2 + random.nextInt(9), TimeUnit.SECONDS);
                } catch (RejectedExecutionException e) {
                    stats.count("cancel-skipped:run-ended");
                }
            }
        }
    }

    private void sell(Customer customer, int quantity) {
        var positions = customer.session().get("READ_POSITIONS", "/api/accounts/" + customer.accountId() + "/positions");
        if (positions.ok()) {
            var sellable = new ArrayList<JsonNode>();
            for (var position : http.json(positions)) {
                if (number(position.path("sellable")) > 0) {
                    sellable.add(position);
                }
            }
            if (!sellable.isEmpty()) {
                var position = sellable.get(ThreadLocalRandom.current().nextInt(sellable.size()));
                place(customer, "PLACE_MARKET_SELL", position.path("instrumentId").asString(), "SELL", "MARKET",
                      (int) Math.min(quantity, number(position.path("sellable"))), null);
                return;
            }
        }
        stats.count("sell-fell-back-to-buy:nothing-sellable");
        place(customer, "PLACE_MARKET_BUY", settings.instruments().getFirst(), "BUY", "MARKET", quantity, null);
    }

    /**
     * @return the order id if the brokerage took the order
     */
    private String place(Customer customer, String step, String instrument, String side, String type, int quantity, BigDecimal limitPrice) {
        var orderId = settings.runPrefix() + "O" + orderSequence.incrementAndGet();
        var body    = new LinkedHashMap<String, Object>();
        body.put("orderId", orderId);
        body.put("accountId", customer.accountId());
        body.put("instrumentId", instrument);
        body.put("side", side);
        body.put("type", type);
        body.put("quantity", quantity);
        body.put("limitPrice", limitPrice == null ? null : usd(limitPrice));
        body.put("timeInForce", "DAY");
        // Q-7: a placement that got no answer at all (the instance gone, F-3) is sent again with the same orderId, as a
        // client would; PlaceOrder ignores an orderId it already has, so a placement that did land is not placed twice
        var deadline = System.nanoTime() + settings.drainTimeout().toNanos();
        var answer   = customer.session().post(step, "/api/accounts/" + customer.accountId() + "/orders", body);
        while (answer.status() == -1 && System.nanoTime() < deadline) {
            stats.count("placement-retried:no-answer");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            answer = customer.session().post(step, "/api/accounts/" + customer.accountId() + "/orders", body);
        }
        return answer.ok() ? orderId : null;
    }

    private void paymentAction() {
        var random   = ThreadLocalRandom.current();
        var customer = activeToday.get(random.nextInt(activeToday.size()));
        var account  = "/api/accounts/" + customer.accountId();
        var reference = paymentSequence.incrementAndGet();
        if (random.nextInt(100) < settings.depositPercent()) {
            customer.session().post("DEPOSIT", account + "/deposits",
                                    Map.of("depositId", settings.runPrefix() + "D" + reference, "accountId", customer.accountId(),
                                           "amount", usd(BigDecimal.valueOf(100 + random.nextInt(1_900)))));
            return;
        }
        var overview = customer.session().get("READ_ACCOUNT", account);
        var withdrawable = overview.ok() ? http.json(overview).path("withdrawable").path("amount").asDouble(0) : 0;
        if (withdrawable < 100) {
            stats.count("withdrawal-skipped:not-enough-withdrawable");
            return;
        }
        customer.session().post("WITHDRAW", account + "/withdrawals",
                                Map.of("withdrawalId", settings.runPrefix() + "W" + reference, "accountId", customer.accountId(),
                                       "amount", usd(cents(Math.min(500, withdrawable / 2)))));
    }

    private void refreshQuotes() {
        try {
            var session = customers.getFirst().session();
            for (var instrument : settings.instruments()) {
                var quote = session.get("QUOTE_REFRESH", "/api/market-data/quotes/" + instrument);
                var json = quote.ok() ? http.json(quote) : null;
                if (json != null && !json.path("stale").asBoolean(false)) {
                    quotes.put(instrument, new double[]{json.path("bid").path("amount").asDouble(), json.path("ask").path("amount").asDouble()});
                } else {
                    // No quote, or a stale one: limits priced off an old quote would cross our own resting orders
                    quotes.remove(instrument);
                }
            }
            if (targetDown != null && faultClosed != null && quotesBack == null && quotes.size() == settings.instruments().size()) {
                quotesBack = Instant.now();
            }
        } catch (RuntimeException e) {
            stats.count("quote-refresh-failed");
        }
    }

    // ---- draining and reporting

    private JsonNode drain() throws InterruptedException {
        var deadline = System.nanoTime() + settings.drainTimeout().toNanos();
        JsonNode report;
        while (true) {
            try {
                report = serverReport();
            } catch (IllegalStateException e) {
                // The report reads the run's event tables; at the end of a long run, with the settlement wave still
                // landing, it outlasted the 30 s request timeout in 2 of 18 runs and took the whole run's result with
                // it. The data is still there - ask again until the drain deadline.
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                stats.count("report-retried");
                log("load report failed (%s); asking again", e.getMessage());
                Thread.sleep(5_000);
                continue;
            }
            var orders  = report.path("orders");
            var pending = orders.path("nonTerminal").asLong() + orders.path("fillsNotBooked").asLong() + pendingPayments(report);
            if (pending == 0) {
                log("drained: every order and payment has its outcome");
                return report;
            }
            if (System.nanoTime() > deadline) {
                log("not drained after %s: %d still open", settings.drainTimeout(), pending);
                return report;
            }
            log("draining: %s", progress(report));
            Thread.sleep(5_000);
        }
    }

    private static long pendingPayments(JsonNode report) {
        var pending = 0L;
        for (var entry : report.path("payments").properties()) {
            if (entry.getKey().endsWith(":REQUESTED") || entry.getKey().endsWith(":INITIATED")) {
                pending += entry.getValue().asLong();
            }
        }
        return pending;
    }

    private JsonNode serverReport() {
        var response = http.operatorGet("REPORT", base() + "/api/admin/load-report?runPrefix=" + settings.runPrefix() + "&accountPrefix=LDA-&bucketSeconds=10");
        if (!response.ok()) {
            throw new IllegalStateException("load report: " + response);
        }
        return http.json(response);
    }

    private String progress() {
        try {
            return progress(serverReport());
        } catch (RuntimeException e) {
            return "report unavailable: " + e.getMessage();
        }
    }

    private String progress(JsonNode report) {
        var orders = report.path("orders");
        return String.format("orders placed %d, open %d, fills not booked %d, booked p50/p99 %.0f/%.0f ms; payments pending %d; skipped (driver saturated) %d",
                             orders.path("placed").asLong(), orders.path("nonTerminal").asLong(), orders.path("fillsNotBooked").asLong(),
                             orders.path("placedToBooked").path("p50Ms").asDouble(), orders.path("placedToBooked").path("p99Ms").asDouble(),
                             pendingPayments(report), stats.counter("skipped:driver-saturated"));
    }

    private boolean report(JsonNode server, JsonNode openCases, JsonNode missing, Map<String, Object> faultEvidence, ReconciliationBreaks.Result breaks,
                           double loadSeconds) throws IOException {
        var deadLetters = 0L;
        for (var queue : server.path("queues").properties()) {
            deadLetters += queue.getValue().path("deadLetters").asLong();
        }
        var orders = server.path("orders");
        var checks = new LinkedHashMap<String, Boolean>();
        checks.put("every order reached its outcome", orders.path("nonTerminal").asLong() == 0);
        checks.put("every fill booked", orders.path("fillsNotBooked").asLong() == 0);
        checks.put("every payment reached its outcome", pendingPayments(server) == 0);
        checks.put("no dead letters", deadLetters == 0);
        if (breaks == null) {
            checks.put("no open reconciliation case", openCases.isArray() && openCases.isEmpty());
        } else {
            // Seeded breaks must become cases; the comparison says which, and that there are no others
            checks.putAll(breaks.checks());
        }
        checks.put("no missing statement", missing.isArray() && missing.isEmpty());
        checks.put("driver never saturated", stats.counter("skipped:driver-saturated") == 0);
        if (settings.fault().equals("F-1")) {
            // A fault run proves nothing unless the faults happened
            for (var kind : List.of("duplicate", "missing", "null", "held")) {
                checks.put("fault " + kind + " injected", SimulatorFaults.total(faultEvidence, kind) > 0);
            }
        } else if (settings.fault().equals("F-2")) {
            checks.put("webhook outage opened", faultOpened != null);
            checks.put("webhooks refused during the outage", ((Number) faultEvidence.get("refused")).longValue() > 0);
            checks.put("simulators saw the refusals", ((Number) faultEvidence.get("answered503")).longValue() > 0);
            // Deliveries the simulators gave up on can only arrive through catch-up: needs simulators/compose.outage.yml
            checks.put("simulators dead-lettered deliveries", ((Number) faultEvidence.get("dead")).longValue() > 0);
            checks.put("booked p99 under 10 s from 30 s to 2 min after the outage", (Boolean) faultEvidence.get("recovered"));
        } else if (settings.fault().equals("F-3")) {
            checks.put("instance went down", targetDown != null);
            checks.put("instance came back", faultClosed != null);
            checks.put("booked p99 under 10 s from 30 s to 2 min after it came back", (Boolean) faultEvidence.get("recovered"));
        } else if (settings.fault().equals("F-4")) {
            checks.put("simulator went down", targetDown != null);
            checks.put("simulator came back", faultClosed != null);
            checks.put("booked p99 under 10 s from 30 s to 2 min after it came back", (Boolean) faultEvidence.get("recovered"));
            if (settings.faultTarget().equals("exchange")) {
                // Without quotes market buys are refused and limits are not placed: the run goes on at a fraction of its rate
                checks.put("every instrument quoted again within 30 s of the exchange answering",
                           quotesBack != null && Duration.between(faultClosed, quotesBack).toSeconds() <= 30);
            }
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("settings", settings);
        result.put("loadSeconds", loadSeconds);
        result.put("checks", checks);
        result.put("requests", stats.summaries());
        result.put("counters", stats.counters());
        result.put("brokerage", server);
        result.put("openCases", openCases);
        result.put("missingStatements", missing);
        if (faultEvidence != null) {
            result.put("faults", Map.of("fault", settings.fault(), "evidence", faultEvidence));
        }
        var directory = Path.of(settings.reportDirectory());
        Files.createDirectories(directory);
        var file = directory.resolve("load-" + settings.runToken() + ".json");
        Files.writeString(file, http.write(result));

        log("---- run %s, %.0f s of load", settings.runToken(), loadSeconds);
        stats.summaries().forEach((step, s) -> log("%-18s %7d  p50 %8.1f ms  p99 %8.1f ms  max %8.1f ms  %s", step, s.count(), s.p50Ms(), s.p99Ms(), s.maxMs(), s.outcomes()));
        log("counters: %s", stats.counters());
        log("brokerage: %s; orders by status %s; placed to filled p50/p99 %.0f/%.0f ms", progress(server), orders.path("byStatus"),
            orders.path("placedToFilled").path("p50Ms").asDouble(), orders.path("placedToFilled").path("p99Ms").asDouble());
        for (var stage : orders.path("stages").properties()) {
            log("stage %-18s p50 %8.0f ms  p99 %8.0f ms  max %8.0f ms", stage.getKey(), stage.getValue().path("p50Ms").asDouble(),
                stage.getValue().path("p99Ms").asDouble(), stage.getValue().path("maxMs").asDouble());
        }
        if (faultEvidence != null) {
            faultEvidence.forEach((key, evidence) -> log("fault %s %-24s %s", settings.fault(), key, evidence));
        }
        checks.forEach((check, passed) -> log("%s %s", passed ? "PASS" : "FAIL", check));
        log("report: %s", file.toAbsolutePath());
        return !checks.containsValue(false);
    }

    // ---- F-2, the webhook outage

    private void openWebhookOutage() {
        faultOpened = Instant.now();
        faultClosed = faultOpened.plus(settings.faultDuration());
        for (var baseUrl : settings.baseUrls()) {
            var opened = http.operatorPost("FAULTS", baseUrl + "/api/admin/webhook-outage?seconds=" + settings.faultDuration().toSeconds(), Map.of());
            if (!opened.ok()) {
                log("could not open the webhook outage on %s: %s", baseUrl, opened);
            }
        }
        log("fault window open (F-2): every instance answers its webhooks 503 for %d s; %s", settings.faultDuration().toSeconds(), progress());
    }

    /**
     * The window, what the instances refused, the simulators' side of it per subscription (attempts by the status we
     * answered, deliveries delivered / dead / pending), and the recovery: the placement buckets' booked p99 from the
     * window's opening, and whether every bucket placed from 30 s to 2 minutes after it closed booked its p99 under 10 s
     * (design §10.2: back under 10 s within 30 s of the window closing).
     */
    private Map<String, Object> webhookOutageEvidence(JsonNode server) {
        var subscriptions = faults.evidence();
        var evidence      = new LinkedHashMap<String, Object>();
        evidence.put("window", faultOpened == null ? "never opened" : faultOpened + " - " + faultClosed);
        var refused = 0L;
        for (var baseUrl : settings.baseUrls()) {
            refused += http.json(http.operatorGet("FAULTS", baseUrl + "/api/admin/webhook-outage")).path("refused").asLong();
        }
        evidence.put("refused", refused);
        evidence.put("answered503", SimulatorFaults.totalAnswered(subscriptions, "503"));
        evidence.put("dead", SimulatorFaults.totalDead(subscriptions));
        recovery(server, evidence);
        evidence.put("subscriptions", subscriptions);
        return evidence;
    }

    // ---- F-4, a simulator restarted

    /**
     * Only announces the window: {@code run-faults.sh} restarts the container on this line (design §10.4, Q-9). A
     * watcher times the outage from the simulator's {@code /health} and closes the window once it answers again.
     */
    private void openSimulatorRestart() {
        faultOpened = Instant.now();
        var health = (settings.faultTarget().equals("exchange") ? settings.exchangeUrl() : settings.gatewayUrl()) + "/health";
        log("fault window open (F-4 %s): restart the %s now; %s", settings.faultTarget(), settings.faultTarget(), progress());
        watchOutage(settings.faultTarget(), health);
    }

    /**
     * Times an outage the run script causes (F-3, F-4) from a health endpoint, polled every 200 ms: {@code targetDown}
     * at the first failed answer, {@code faultClosed} - the window's end - at the first good one after it.
     */
    private void watchOutage(String what, String health) {
        Thread.ofPlatform().daemon().name("load-driver-outage-watch").start(() -> {
            var deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            while (System.nanoTime() < deadline) {
                var up = http.plainGet("FAULT_HEALTH", health).ok();
                if (!up && targetDown == null) {
                    targetDown = Instant.now();
                    log("fault %s: the %s stopped answering", settings.fault(), what);
                } else if (up && targetDown != null) {
                    faultClosed = Instant.now();
                    log("fault %s: the %s answers again after %.1f s", settings.fault(), what, Duration.between(targetDown, faultClosed).toMillis() / 1000.0);
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
            log("fault %s: the %s did not %s within 5 minutes", settings.fault(), what, targetDown == null ? "go down" : "come back");
        });
    }

    /**
     * The restart's times, and the recovery from the moment the simulator answered again.
     */
    private Map<String, Object> simulatorRestartEvidence(JsonNode server) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("target", settings.fault().equals("F-3") ? "instance" : settings.faultTarget());
        evidence.put("placementsRetried", stats.counter("placement-retried:no-answer"));
        evidence.put("skippedInWindow", stats.counter("skipped:driver-saturated-in-fault-window"));
        evidence.put("windowOpened", String.valueOf(faultOpened));
        evidence.put("down", String.valueOf(targetDown));
        evidence.put("up", String.valueOf(faultClosed));
        evidence.put("downSeconds", targetDown == null || faultClosed == null ? -1 : Duration.between(targetDown, faultClosed).toMillis() / 1000.0);
        evidence.put("quotesBack", String.valueOf(quotesBack));
        recovery(server, evidence);
        return evidence;
    }

    /**
     * Design §10.2's recovery: the placement buckets' booked p99 from the window's opening to 2 minutes after it closed,
     * and {@code recovered} - every bucket placed from 30 s to 2 minutes after the close booked its p99 under 10 s.
     */
    private void recovery(JsonNode server, Map<String, Object> evidence) {
        var recovered = faultOpened != null && faultClosed != null;
        var curve     = new ArrayList<String>();
        if (recovered) {
            var recoveredFrom = faultClosed.plusSeconds(30);
            for (var bucket : server.path("buckets")) {
                var start = Instant.parse(bucket.path("start").asString());
                var p99   = bucket.path("p99BookedMs").asDouble();
                if (start.isBefore(faultOpened.minusSeconds(10)) || start.isAfter(faultClosed.plusSeconds(120))) {
                    continue;
                }
                curve.add(String.format(Locale.ROOT, "%s %.0f ms", start, p99));
                if (!start.isBefore(recoveredFrom) && p99 >= 10_000) {
                    recovered = false;
                }
            }
        }
        evidence.put("recovered", recovered);
        evidence.put("bookedP99Curve", curve);
    }

    // ---- helpers

    private String base() {
        return settings.baseUrls().getFirst();
    }

    private static Map<String, Object> usd(BigDecimal amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private static BigDecimal cents(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }

    private static long number(JsonNode node) {
        return node.isNumber() ? node.asLong() : node.path("value").asLong(0);
    }

    private static void log(String format, Object... args) {
        System.out.printf("%s %s%n", LocalTime.now().withNano(0), String.format(format, args));
    }
}
