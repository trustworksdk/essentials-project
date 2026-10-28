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
 * saturated driver shows in the report instead of quietly lowering the rate.
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
    private final Map<LocalDate, Instant>  cutoffs   = new HashMap<>();
    private ZoneId                         calendarZone;
    private Instant                        clockReading;
    private long                           clockReadAt;
    private double                         clockSpeed;

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
        }
        scheduler.scheduleAtFixedRate(this::refreshQuotes, 0, 1, TimeUnit.SECONDS);
        chooseActiveCustomers();

        var started     = System.nanoTime();
        var closes      = 0;
        var dayStarted  = -1L;
        var lastTick    = System.nanoTime();
        var lastPoll    = 0L;
        var wasOpen     = tradingOpen();
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
        if (!settings.fault().equals("none")) {
            faults.clearDeliveryFaults();
            faultEvidence = faults.evidence();
        }
        return report(report, openCases, missing, faultEvidence, loadSeconds);
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
        var clock = http.json(http.plainGet("EXCHANGE_CLOCK", settings.exchangeUrl() + "/api/clock"));
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
            stats.count("skipped:driver-saturated");
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
        return customer.session().post(step, "/api/accounts/" + customer.accountId() + "/orders", body).ok() ? orderId : null;
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

    private boolean report(JsonNode server, JsonNode openCases, JsonNode missing, Map<String, Object> faultEvidence, double loadSeconds) throws IOException {
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
        checks.put("no open reconciliation case", openCases.isArray() && openCases.isEmpty());
        checks.put("no missing statement", missing.isArray() && missing.isEmpty());
        checks.put("driver never saturated", stats.counter("skipped:driver-saturated") == 0);
        if (faultEvidence != null) {
            // A fault run proves nothing unless the faults happened
            for (var kind : List.of("duplicate", "missing", "null", "held")) {
                checks.put("fault " + kind + " injected", SimulatorFaults.total(faultEvidence, kind) > 0);
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
            faultEvidence.forEach((subscription, evidence) -> log("fault %s %-24s %s", settings.fault(), subscription, evidence));
        }
        checks.forEach((check, passed) -> log("%s %s", passed ? "PASS" : "FAIL", check));
        log("report: %s", file.toAbsolutePath());
        return !checks.containsValue(false);
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
