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

package dk.trustworks.essentials.components.eventlog.processor.spike;

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.Added;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Suite {@code p8-gate}. The processor arms
 * need this module, so the whole suite lives here (the engine-only admin arm too, through the engine's public API), on a copy
 * of the engine's {@link SpikeDb}: a fresh container and database name per arm, every arm {@code RUNS} times,
 * {@code pg_stat_statements} by role ({@code reader} = the engine's {@code DataSource}, {@code writer} = the
 * application's pool: appends, and the JDBI and Spring page sources).
 * <ul>
 *     <li>{@link #processorSteady()}: the exclusive steady shape, 100 ev/s, 10 subscriptions: 5
 *     {@link ViewProjection} + 5 {@link EventReactor} against 5 raw {@code CONTIGUOUS} page handlers + 5 raw {@code FRESH}
 *     periodic handlers. Commit-to-handler latency, reader statements per delivery, client CPU and allocation.</li>
 *     <li>{@link #burst()}: {@code BURST_EVENTS} seeded events into one set-based projection: raw
 *     {@link PageHandler}, {@link ViewProjection#builder} {@code onPage} on {@code engine()}, on the JDBI unit-of-work
 *     source and on the Spring source. Events/s, statements per page, borrow time, CPU and allocation.</li>
 *     <li>{@link #retryBackoff()}: row by row page 100 with every global order
 *     divisible by 997 always failing, at retry backoffs {@code BACKOFFS} ms against no failures.</li>
 *     <li>{@link #adminAndParked()}: {@code subscriptions()} over 1 000 progress rows (100 with parked heads); the
 *     parked check of 10 subscriptions holding aggregates and 10 holding none; retry from another node.</li>
 * </ul>
 * Results {@code target/spike-results/p8-c-*.txt}. Knobs: {@code -Dspike.gate.runs} (2), {@code -Dspike.gate.timeScale}
 * (1.0), {@code -Dspike.gate.p8BurstEvents} (100000), {@code -Dspike.gate.p8Backoffs} ("100,25,5,1"),
 * {@code -Dspike.eventlog.image}; external server as {@link SpikeDb}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class EventLogProcessorGateSpikeIT {
    private static final String        STEADY       = "p8-c-steady.txt";
    private static final String        BURST        = "p8-c-burst.txt";
    private static final String        BACKOFF      = "p8-c-backoff.txt";
    private static final String        ADMIN        = "p8-c-admin.txt";
    private static final String        TYPE         = "Orders";
    private static final String        EVENT_TYPE   = "FQCN:" + Added.class.getName();
    private static final int           RUNS         = Integer.getInteger("spike.gate.runs", 2);
    private static final double        TIME_SCALE   = Double.parseDouble(System.getProperty("spike.gate.timeScale", "1.0"));
    private static final int           BURST_EVENTS = Integer.getInteger("spike.gate.p8BurstEvents", 100_000);
    private static final int           AGGREGATES   = Math.max(1, BURST_EVENTS / 10);
    private static final List<Integer> BACKOFFS     = Arrays.stream(System.getProperty("spike.gate.p8Backoffs", "100,25,5,1").split(","))
                                                            .map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf).toList();
    private static final int           FAILING_EVERY = 997;
    private static final int           WRITER_POOL   = 10;
    private static final JSONEventSerializer SERIALIZER = EssentialsJSONEventSerializers.create();
    private static final String        METADATA      = SERIALIZER.serialize(EventMetaData.empty());

    private static final com.sun.management.OperatingSystemMXBean OS      = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final com.sun.management.ThreadMXBean          THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    // ------------------------------------------------------------------ steady

    enum SteadyArm {RAW, PROCESSOR}

    @Test
    void processorSteady() throws Exception {
        var warmUp = scaled(Duration.ofSeconds(10));
        var window = scaled(Duration.ofSeconds(60));
        header(STEADY, "PROCESSOR STEADY", "100 ev/s, one event per unit of work to a new aggregate; 10 exclusive subscriptions: RAW = 5 CONTIGUOUS page + 5 FRESH periodic "
                + "handlers, PROCESSOR = 5 ViewProjection + 5 EventReactor; warm-up " + warmUp.toSeconds() + " s, window " + window.toSeconds() + " s");
        for (int run = 1; run <= RUNS; run++) {
            var arms = run % 2 == 1 ? List.of(SteadyArm.RAW, SteadyArm.PROCESSOR) : List.of(SteadyArm.PROCESSOR, SteadyArm.RAW);
            for (var arm : arms) steady(run, arm, warmUp, window);
        }
    }

    private void steady(int run, SteadyArm arm, Duration warmUp, Duration window) throws Exception {
        var settings = EventLogSettings.defaults().withCrossJvmWakeUp(false);
        try (var db = SpikeDb.start("p8s_r" + run + "_" + arm.name().toLowerCase(Locale.ROOT), 100); var app = new App(db, settings, 12)) {
            var committed = new ConcurrentHashMap<String, Long>();
            var sinks     = new Sinks();
            for (int i = 0; i < 5; i++) {
                if (arm == SteadyArm.RAW) {
                    app.eventLog.subscribe(definition("fresh-" + i, ConsistencyMode.FRESH, null), (PeriodicHandler) e -> sinks.sample("FRESH", committed.get(e.aggregateId())));
                    app.eventLog.subscribe(definition("contiguous-" + i, ConsistencyMode.CONTIGUOUS, null), (PageHandler) (page, tx) -> {
                        for (var e : page) sinks.sample("CONTIGUOUS", committed.get(e.aggregateId()));
                    });
                } else {
                    EventReactor.builder("fresh-" + i, TYPE).startFrom(StartFrom.globalOrder(1))
                                .on(Added.class, e -> sinks.sample("FRESH", committed.get(e.payloadAs(Added.class).orderId())))
                                .build(app.eventLog, SERIALIZER).start();
                    ViewProjection.builder("contiguous-" + i, TYPE).startFrom(StartFrom.globalOrder(1))
                                  .on(Added.class, (e, context) -> sinks.sample("CONTIGUOUS", committed.get(e.payloadAs(Added.class).orderId())))
                                  .build(app.eventLog, SERIALIZER).start();
                }
            }
            var measuring = new AtomicBoolean();
            var appended  = new AtomicLong();
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                try (var c = app.writerPool.getConnection()) {
                    var id      = UUID.randomUUID().toString();
                    var measure = measuring.get();
                    c.setAutoCommit(false);
                    append(app.eventLog, c, TYPE, id, 1);
                    if (measure) committed.put(id, System.nanoTime());
                    c.commit();
                    if (measure) {
                        committed.put(id, System.nanoTime());
                        appended.incrementAndGet();
                    }
                    app.eventLog.afterCommit(List.of(TYPE));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }, 0, 10_000, TimeUnit.MICROSECONDS);
            Thread.sleep(warmUp.toMillis());
            db.resetStatistics();
            var client = Client.now();
            measuring.set(true);
            Thread.sleep(window.toMillis());
            measuring.set(false);
            var used = client.since();
            var s    = summarise(db, STEADY, "steady run " + run + ": " + arm);
            scheduler.shutdown();
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
            long events     = appended.get();
            long deliveries = events * 10;
            try {
                await(Duration.ofSeconds(30), () -> sinks.total.get() >= deliveries);
            } catch (IllegalStateException e) {
                SpikeDb.record(STEADY, "  !! " + arm + ": " + sinks.total.get() + " of " + deliveries + " samples after 30 s");
            }
            var line = new StringBuilder(String.format("STEADY run %d | %-9s | 100 ev/s | appended %d |", run, arm, events));
            var all  = new ArrayList<Long>();
            for (var key : new TreeSet<>(sinks.byKey.keySet())) {
                var l = Latencies.of(sinks.byKey.get(key));
                all.addAll(sinks.byKey.get(key));
                line.append(String.format(" %s p50 %.2f p99 %.2f max %.1f ms (%d) |", key, l.p50(), l.p99(), l.max(), sinks.byKey.get(key).size()));
            }
            var l = Latencies.of(all);
            line.append(String.format(" ALL p50 %.2f p99 %.2f ms | READER STATEMENTS PER DELIVERY %.3f (non-utility %.3f) | writer statements per event %.2f | "
                                              + "CLIENT CPU %.1f us per delivery (%.1f %% of one core) | ALLOCATION %.1f MB/s, %.0f B per delivery",
                                      l.p50(), l.p99(), s.calls("reader") / (double) Math.max(1, deliveries), s.nonUtility("reader") / (double) Math.max(1, deliveries),
                                      s.calls("writer") / (double) Math.max(1, events), used.cpuNanos / 1e3 / Math.max(1, deliveries),
                                      100.0 * used.cpuNanos / used.wallNanos, used.allocated / 1e6 / (used.wallNanos / 1e9), used.allocated / (double) Math.max(1, deliveries)));
            SpikeDb.record(STEADY, line.toString());
        }
    }

    /** Commit-to-handler samples by key; only events committed inside the window have a commit time */
    private static final class Sinks {
        final Map<String, ConcurrentLinkedQueue<Long>> byKey = new ConcurrentHashMap<>();
        final AtomicLong                               total = new AtomicLong();

        void sample(String key, Long committedAt) {
            if (committedAt == null) return;
            byKey.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(System.nanoTime() - committedAt);
            total.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ burst, page sources

    enum BurstArm {RAW, VIEW_ENGINE, VIEW_JDBI, VIEW_SPRING}

    private static final String VIEW          = "p8_view";
    private static final String VIEW_CONFLICT = " ON CONFLICT (aggregate_id) DO UPDATE SET event_count = v.event_count + excluded.event_count, "
            + "last_global_order = excluded.last_global_order, last_event_type = excluded.last_event_type";
    private static final String SET_UPSERT    = "INSERT INTO " + VIEW + " AS v (aggregate_id, event_count, last_global_order, last_event_type)"
            + " SELECT DISTINCT ON (a) a, count(*) OVER (PARTITION BY a), g, t FROM unnest(?::text[], ?::bigint[], ?::text[]) AS u(a, g, t) ORDER BY a, g DESC" + VIEW_CONFLICT;
    private static final String ROW_UPSERT    = "INSERT INTO " + VIEW + " AS v (aggregate_id, event_count, last_global_order, last_event_type) VALUES (?, 1, ?, ?)" + VIEW_CONFLICT;

    private static void setUpsert(Connection connection, String[] ids, Long[] orders, String[] types) throws SQLException {
        try (var statement = connection.prepareStatement(SET_UPSERT)) {
            statement.setArray(1, connection.createArrayOf("text", ids));
            statement.setArray(2, connection.createArrayOf("bigint", orders));
            statement.setArray(3, connection.createArrayOf("text", types));
            statement.executeUpdate();
        }
    }

    @Test
    void burst() throws Exception {
        header(BURST, "BURST", BURST_EVENTS + " seeded events over " + AGGREGATES + " aggregates into one set-based projection (unnest upsert, page 100 = the default); arms "
                + Arrays.toString(BurstArm.values()) + "; reader pool 4, application (writer) pool " + WRITER_POOL);
        for (int run = 1; run <= RUNS; run++) {
            var arms = new ArrayList<>(List.of(BurstArm.values()));
            if (run % 2 == 0) Collections.reverse(arms);
            for (var arm : arms) burst(run, arm);
        }
    }

    private void burst(int run, BurstArm arm) throws Exception {
        try (var db = SpikeDb.start("p8b_r" + run + "_" + arm.name().toLowerCase(Locale.ROOT), 100); var app = new App(db, EventLogSettings.defaults().withCrossJvmWakeUp(false), 4)) {
            seed(app);
            db.checkpoint();
            db.resetStatistics();
            app.readerWait.reset();
            app.writerWait.reset();
            var subscriber = "burst";
            var client     = Client.now();
            long started   = System.nanoTime();
            switch (arm) {
                case RAW -> app.eventLog.subscribe(definition(subscriber, ConsistencyMode.CONTIGUOUS, null), (PageHandler) (page, tx) ->
                        setUpsert(tx.connection(), page.stream().map(StoredEvent::aggregateId).toArray(String[]::new), page.stream().map(StoredEvent::globalOrder).toArray(Long[]::new),
                                  page.stream().map(StoredEvent::eventType).toArray(String[]::new)));
                default -> {
                    var builder = ViewProjection.builder(subscriber, TYPE).startFrom(StartFrom.globalOrder(1)).onPage((events, context) ->
                            setUpsert(context.connection(), events.stream().map(e -> e.payloadAs(Added.class).orderId()).toArray(String[]::new),
                                      events.stream().map(e -> e.globalOrder().longValue()).toArray(Long[]::new), events.stream().map(e -> e.raw().eventType()).toArray(String[]::new)));
                    if (arm == BurstArm.VIEW_JDBI) builder.transactions(new UnitOfWorkPageTransactions(new JdbiUnitOfWorkFactory(Jdbi.create(app.writerWait))));
                    if (arm == BurstArm.VIEW_SPRING) builder.transactions(new SpringPageTransactions(new DataSourceTransactionManager(app.writerWait), app.writerWait));
                    builder.build(app.eventLog, SERIALIZER).start();
                }
            }
            awaitResumePast(db, subscriber, BURST_EVENTS, Duration.ofMinutes(20));
            double seconds = (System.nanoTime() - started) / 1e9;
            var    used    = client.since();
            var    s       = summarise(db, BURST, "burst run " + run + ": " + arm);
            long   pages   = 0;
            for (var st : db.statements()) {
                if (st.query().contains(EventLogSchema.PROGRESS_TABLE) && st.query().trim().toUpperCase(Locale.ROOT).startsWith("UPDATE")) pages += st.calls();
            }
            long counted;
            try (var c = db.connect("writer")) {
                counted = SpikeDb.queryLong(c, "SELECT coalesce(sum(event_count), 0) FROM " + VIEW);
            }
            var pool = arm == BurstArm.VIEW_JDBI || arm == BurstArm.VIEW_SPRING ? app.writerWait : app.readerWait;
            SpikeDb.record(BURST, String.format("BURST run %d | %-11s | %.1f s | EVENTS/S %.0f | pages (progress updates) %d | STATEMENTS PER PAGE %.2f (reader %.2f, writer %.2f; "
                                                        + "non-utility %.2f) | page-pool BORROW mean %.3f ms, max %.1f ms over %d borrows | CLIENT CPU %.2f us per event | "
                                                        + "ALLOCATION %.1f MB/s, %.0f B per event | fold %s",
                                                run, arm, seconds, BURST_EVENTS / seconds, pages, (s.calls("reader") + s.calls("writer")) / (double) Math.max(1, pages),
                                                s.calls("reader") / (double) Math.max(1, pages), s.calls("writer") / (double) Math.max(1, pages),
                                                (s.nonUtility("reader") + s.nonUtility("writer")) / (double) Math.max(1, pages), pool.meanMillis(), pool.maxMillis(), pool.count.get(),
                                                used.cpuNanos / 1e3 / BURST_EVENTS, used.allocated / 1e6 / (used.wallNanos / 1e9), used.allocated / (double) BURST_EVENTS,
                                                counted == BURST_EVENTS ? "OK" : "MISMATCH " + counted));
        }
    }

    /** The view, pre-populated (every write an update), and {@code BURST_EVENTS} events over {@code AGGREGATES} interleaved aggregates */
    private static void seed(App app) throws SQLException {
        try (var c = app.writerPool.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute("CREATE TABLE " + VIEW + " (aggregate_id text PRIMARY KEY, event_count bigint NOT NULL, last_global_order bigint NOT NULL, last_event_type text)");
                s.execute("INSERT INTO " + VIEW + " SELECT 'agg-' || a, 0, 0, NULL FROM generate_series(0, " + (AGGREGATES - 1) + ") a");
            }
            c.commit();
            var requests = new ArrayList<AppendRequest>(1000);
            for (int k = 0; k < BURST_EVENTS; k++) {
                var id = "agg-" + (k % AGGREGATES);
                requests.add(AppendRequest.withoutExpectedOrder(TYPE, id, List.of(event(id, k))));
                if (requests.size() == 1000 || k == BURST_EVENTS - 1) {
                    app.eventLog.append(c, requests);
                    c.commit();
                    requests.clear();
                }
            }
            try (var s = c.createStatement()) {
                s.execute("ANALYZE");
            }
            c.commit();
        }
    }

    // ------------------------------------------------------------------ retry backoff

    @Test
    void retryBackoff() throws Exception {
        header(BACKOFF, "RETRY BACKOFF", BURST_EVENTS + " seeded events over " + AGGREGATES + " aggregates; raw CONTIGUOUS row-by-row upsert, page 100, 3 retries, backoff max 10 s; "
                + "every global order divisible by " + FAILING_EVERY + " always throws; backoffs " + BACKOFFS + " ms against no failures (0)");
        var arms = new ArrayList<Integer>();
        arms.add(0);
        arms.addAll(BACKOFFS);
        for (int run = 1; run <= RUNS; run++) {
            Double baseline = null;
            for (int backoff : arms) {
                try (var db = SpikeDb.start("p8r_r" + run + "_b" + backoff, 100); var app = new App(db, EventLogSettings.defaults().withCrossJvmWakeUp(false), 4)) {
                    seed(app);
                    db.checkpoint();
                    db.resetStatistics();
                    var subscriber = "fallback";
                    var errors     = backoff == 0 ? null : new ErrorSettings(3, Duration.ofMillis(backoff), Duration.ofSeconds(10), 100);
                    long started   = System.nanoTime();
                    app.eventLog.subscribe(definition(subscriber, ConsistencyMode.CONTIGUOUS, errors), PageHandler.perEvent((event, tx) -> {
                        if (backoff != 0 && event.globalOrder() % FAILING_EVERY == 0) throw new IllegalStateException("always fails: " + event.globalOrder());
                        try (var statement = tx.connection().prepareStatement(ROW_UPSERT)) {
                            statement.setString(1, event.aggregateId());
                            statement.setLong(2, event.globalOrder());
                            statement.setString(3, event.eventType());
                            statement.executeUpdate();
                        }
                    }));
                    awaitResumePast(db, subscriber, BURST_EVENTS, Duration.ofMinutes(30));
                    double seconds = (System.nanoTime() - started) / 1e9;
                    summarise(db, BACKOFF, "backoff run " + run + ": " + backoff + " ms");
                    long counted, heads, parked;
                    try (var c = db.connect("writer")) {
                        counted = SpikeDb.queryLong(c, "SELECT coalesce(sum(event_count), 0) FROM " + VIEW);
                        heads = SpikeDb.queryLong(c, "SELECT count(*) FROM " + EventLogSchema.PARKED_EVENTS_TABLE + " WHERE blocked_by IS NULL");
                        parked = SpikeDb.queryLong(c, "SELECT count(*) FROM " + EventLogSchema.PARKED_EVENTS_TABLE);
                    }
                    double rate = BURST_EVENTS / seconds;
                    if (backoff == 0) baseline = rate;
                    SpikeDb.record(BACKOFF, String.format("BACKOFF run %d | %3d ms | %.1f s | EVENTS/S %.0f | vs no failures %s | parked heads %d, rows %d | fold %s",
                                                          run, backoff, seconds, rate, baseline == null ? "-" : String.format("%.2fx", rate / baseline), heads, parked,
                                                          counted + parked == BURST_EVENTS ? "OK" : "MISMATCH " + counted + " + " + parked));
                }
            }
        }
    }

    // ------------------------------------------------------------------ admin and parked check

    private static final int LIST_TYPES = 10, LIST_SUBSCRIBERS = 100, LIST_CALLS = 200;

    @Test
    void adminAndParked() throws Exception {
        var window = scaled(Duration.ofSeconds(60));
        header(ADMIN, "ADMIN AND PARKED", LIST_TYPES * LIST_SUBSCRIBERS + " progress rows over " + LIST_TYPES + " aggregate types (1 000 events each), 100 with a parked head and 9 "
                + "rows behind it; " + LIST_CALLS + " subscriptions() calls from a second EventLog (writer role); parked check: 10 subscriptions holding 20 aggregates and 10 holding "
                + "none, idle window " + window.toSeconds() + " s, parked-check interval = backstop 1 s; then 20 retries from a second EventLog");
        for (int run = 1; run <= RUNS; run++) {
            listing(run);
            parkedCheck(run, window);
        }
    }

    private void listing(int run) throws Exception {
        try (var db = SpikeDb.start("p8a_r" + run + "_list", 100); var app = new App(db, EventLogSettings.defaults().withCrossJvmWakeUp(false), 4)) {
            try (var c = app.writerPool.getConnection()) {
                c.setAutoCommit(false);
                for (int t = 0; t < LIST_TYPES; t++) {
                    var type = "T" + t;
                    app.eventLog.registerAggregateType(type);
                    var requests = new ArrayList<AppendRequest>();
                    for (int k = 0; k < 1000; k++) requests.add(AppendRequest.withoutExpectedOrder(type, "a" + (k % 100), List.of(event("a" + (k % 100), k))));
                    app.eventLog.append(c, requests);
                }
                try (var s = c.createStatement()) {
                    s.execute("INSERT INTO " + EventLogSchema.PROGRESS_TABLE + " (subscriber, aggregate_type, instance_name, exclusive, consistency_mode, progress_model, resume_from) "
                                      + "SELECT 'sub-' || s, 'T' || t, '', true, 'CONTIGUOUS', 'IN_HANDLER_TRANSACTION', 500 FROM generate_series(0, " + (LIST_TYPES - 1) + ") t, "
                                      + "generate_series(0, " + (LIST_SUBSCRIBERS - 1) + ") s");
                    s.execute("INSERT INTO " + EventLogSchema.PARKED_EVENTS_TABLE + " (subscriber, aggregate_type, instance_name, global_order, aggregate_id, blocked_by, attempts, "
                                      + "error_type, error_message) SELECT 'sub-' || s, 'T' || t, '', 100 + 10 * k, 'a' || s, CASE WHEN k = 0 THEN NULL ELSE 100 END, 4, "
                                      + "'java.lang.IllegalStateException', 'boom' FROM generate_series(0, " + (LIST_TYPES - 1) + ") t, generate_series(0, 9) s, generate_series(0, 9) k");
                    s.execute("ANALYZE");
                }
                c.commit();
            }
            var admin = EventLog.builder().setDataSource(app.writerWait).setSettings(EventLogSettings.defaults().withCrossJvmWakeUp(false)).build();
            admin.start();
            try {
                for (int i = 0; i < 20; i++) admin.admin().subscriptions(Optional.empty());
                db.resetStatistics();
                var  nanos = new ArrayList<Long>();
                int  rows  = 0;
                long heads = 0;
                for (int i = 0; i < LIST_CALLS; i++) {
                    long start = System.nanoTime();
                    var  list  = admin.admin().subscriptions(Optional.empty());
                    nanos.add(System.nanoTime() - start);
                    rows = list.size();
                    heads = list.stream().filter(r -> r.parkedHeads() > 0).count();
                }
                var s = summarise(db, ADMIN, "listing run " + run);
                var l = Latencies.of(nanos);
                SpikeDb.record(ADMIN, String.format("LISTING run %d | rows %d, with parked heads %d | %d calls | p50 %.2f p99 %.2f max %.2f ms | STATEMENTS PER CALL %.2f (non-utility %.2f)",
                                                    run, rows, heads, LIST_CALLS, l.p50(), l.p99(), l.max(), s.calls("writer") / (double) LIST_CALLS,
                                                    s.nonUtility("writer") / (double) LIST_CALLS));
            } finally {
                admin.stop();
            }
        }
    }

    private void parkedCheck(int run, Duration window) throws Exception {
        try (var db = SpikeDb.start("p8a_r" + run + "_parked", 100); var app = new App(db, EventLogSettings.defaults().withCrossJvmWakeUp(false), 24)) {
            var errors   = new ErrorSettings(0, Duration.ofMillis(1), Duration.ofMillis(1), 100);
            var failing  = new ConcurrentHashMap<String, Set<Long>>();
            var handled  = new ConcurrentHashMap<Long, Long>(); // held-0: global order -> handled at
            var handles  = new LinkedHashMap<String, SubscriptionHandle>();
            for (int i = 0; i < 10; i++) {
                for (var name : List.of("held-" + i, "clean-" + i)) {
                    var set = ConcurrentHashMap.<Long>newKeySet();
                    if (name.startsWith("held")) for (long g = 1; g <= 20; g++) set.add(g);
                    failing.put(name, set);
                    var definition = SubscriptionDefinition.builder(name, TYPE).perInstance("node1").startFrom(StartFrom.globalOrder(1)).consistency(ConsistencyMode.CONTIGUOUS)
                                                           .errors(errors).build();
                    handles.put(name, app.eventLog.subscribe(definition, PageHandler.perEvent((event, tx) -> {
                        if (set.contains(event.globalOrder())) throw new IllegalStateException("fails: " + event.globalOrder());
                        if (name.equals("held-0")) handled.put(event.globalOrder(), System.nanoTime());
                    })));
                }
            }
            for (int k = 0; k < 20; k++) app.appendNewStream("p" + k);
            await(Duration.ofSeconds(60), () -> {
                try (var c = db.connect("writer")) {
                    return SpikeDb.queryLong(c, "SELECT count(*) FROM " + EventLogSchema.PARKED_EVENTS_TABLE + " WHERE blocked_by IS NULL") == 200
                            && SpikeDb.queryLong(c, "SELECT count(*) FROM " + EventLogSchema.PROGRESS_TABLE + " WHERE resume_from > 20") == 20;
                } catch (SQLException e) {
                    return false;
                }
            });
            Thread.sleep(2000);
            var before = new HashMap<String, Long>();
            handles.forEach((name, h) -> before.put(name, ProcessorTestBridge.parkedChecks(h)));
            db.resetStatistics();
            Thread.sleep(window.toMillis());
            var s = summarise(db, ADMIN, "parked check run " + run);
            long requestReads = 0;
            for (var st : db.statements()) if (st.query().contains("requested IS NOT NULL AND blocked_by IS NULL")) requestReads += st.calls();
            long heldChecks = 0, heldMax = 0, cleanChecks = 0;
            for (var e : handles.entrySet()) {
                long checks = ProcessorTestBridge.parkedChecks(e.getValue()) - before.get(e.getKey());
                if (e.getKey().startsWith("held")) {
                    heldChecks += checks;
                    heldMax = Math.max(heldMax, checks);
                } else {
                    cleanChecks += checks;
                }
            }
            double intervals = window.toMillis() / 1000.0;
            SpikeDb.record(ADMIN, String.format("PARKED CHECK run %d | idle %.0f s, interval 1 s | parked-request statements %d | held (10 subs): checks %d, max %d per sub = %.2f per "
                                                        + "interval | CLEAN (10 subs): checks %d | reader statements/s %.2f",
                                                run, intervals, requestReads, heldChecks, heldMax, heldMax / intervals, cleanChecks, s.calls("reader") / intervals));
            // retry from another node: a second EventLog that runs nothing
            var other = EventLog.builder().setDataSource(app.writerWait).setSettings(EventLogSettings.defaults().withCrossJvmWakeUp(false)).build();
            other.start();
            try {
                var key       = new ProgressKey("held-0", TYPE, "node1");
                var latencies = new ArrayList<Long>();
                var results   = new TreeMap<String, Integer>();
                for (long g = 1; g <= 20; g++) {
                    failing.get("held-0").remove(g);
                    long requested = System.nanoTime();
                    var  result    = other.admin().retryParked(key, g);
                    results.merge(result.name(), 1, Integer::sum);
                    long head = g;
                    await(Duration.ofSeconds(30), () -> handled.containsKey(head));
                    latencies.add(handled.get(head) - requested);
                    Thread.sleep(ThreadLocalRandom.current().nextInt(50, 1050)); // spread the requests over the check interval
                }
                var l = Latencies.of(latencies);
                SpikeDb.record(ADMIN, String.format("RETRY FROM ANOTHER NODE run %d | 20 heads | results %s | request-to-handler p50 %.1f ms, p100 %.1f ms", run, results, l.p50(), l.max()));
            } finally {
                other.stop();
            }
        }
    }

    // ------------------------------------------------------------------ harness

    private static SubscriptionDefinition definition(String subscriber, ConsistencyMode mode, ErrorSettings errors) {
        return SubscriptionDefinition.builder(subscriber, TYPE).exclusive().startFrom(StartFrom.globalOrder(1)).consistency(mode).errors(errors).build();
    }

    private static EventToAppend event(String id, long n) {
        return new EventToAppend(UUID.randomUUID(), null, null, EVENT_TYPE, 1, Instant.now(), SERIALIZER.serialize(new Added(id, (int) n)), METADATA);
    }

    private static void append(EventLog log, Connection connection, String type, String id, int count) throws SQLException {
        var events = new ArrayList<EventToAppend>(count);
        for (int i = 0; i < count; i++) events.add(event(id, ThreadLocalRandom.current().nextInt(1_000_000)));
        log.append(connection, AppendRequest.withoutExpectedOrder(type, id, events));
    }

    /** Polls the progress row on the observer connection (not counted): resume point past {@code events} */
    private static void awaitResumePast(SpikeDb db, String subscriber, long events, Duration timeout) throws Exception {
        var sql = "SELECT coalesce(max(resume_from), 0) FROM " + EventLogSchema.PROGRESS_TABLE + " WHERE subscriber = '" + subscriber + "'";
        await(timeout, () -> {
            try {
                return SpikeDb.queryLong(db.observer, sql) > events;
            } catch (SQLException e) {
                return false;
            }
        });
    }

    /** Client CPU (process) and allocation (every thread) since a point */
    private record Client(long cpu, long allocated, long wall) {
        static Client now() {
            return new Client(OS.getProcessCpuTime(), THREADS.getTotalThreadAllocatedBytes(), System.nanoTime());
        }

        Used since() {
            return new Used(OS.getProcessCpuTime() - cpu, THREADS.getTotalThreadAllocatedBytes() - allocated, System.nanoTime() - wall);
        }
    }

    private record Used(long cpuNanos, long allocated, long wallNanos) {
    }

    private static final class App implements AutoCloseable {
        final HikariDataSource writerPool;
        final HikariDataSource readerPool;
        final TimedDataSource  readerWait;
        final TimedDataSource  writerWait;
        final EventLog         eventLog;

        App(SpikeDb db, EventLogSettings settings, int readerPoolSize) throws SQLException {
            writerPool = db.pool("writer", WRITER_POOL);
            readerPool = db.pool("reader", readerPoolSize);
            readerWait = new TimedDataSource(readerPool);
            writerWait = new TimedDataSource(writerPool);
            eventLog = EventLog.builder().setDataSource(readerWait).setSettings(settings).build();
            eventLog.start();
            eventLog.registerAggregateType(TYPE);
            eventLog.eventTypes().idOf(EVENT_TYPE);
        }

        void appendNewStream(String id) throws SQLException {
            try (var c = writerPool.getConnection()) {
                c.setAutoCommit(false);
                append(eventLog, c, TYPE, id, 1);
                c.commit();
            }
            eventLog.afterCommit(List.of(TYPE));
        }

        @Override
        public void close() {
            eventLog.stop();
            readerPool.close();
            writerPool.close();
        }
    }

    /** Times every {@code getConnection()}: the borrow time */
    private static final class TimedDataSource implements DataSource {
        private final DataSource delegate;
        final AtomicLong count = new AtomicLong(), sumNanos = new AtomicLong(), maxNanos = new AtomicLong();

        TimedDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void reset() {
            count.set(0);
            sumNanos.set(0);
            maxNanos.set(0);
        }

        double meanMillis() {
            return sumNanos.get() / 1e6 / Math.max(1, count.get());
        }

        double maxMillis() {
            return maxNanos.get() / 1e6;
        }

        private Connection timed(Callable<Connection> borrow) throws SQLException {
            long start = System.nanoTime();
            try {
                return borrow.call();
            } catch (SQLException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new SQLException(e);
            } finally {
                long nanos = System.nanoTime() - start;
                count.incrementAndGet();
                sumNanos.addAndGet(nanos);
                maxNanos.accumulateAndGet(nanos, Math::max);
            }
        }

        @Override
        public Connection getConnection() throws SQLException {
            return timed(delegate::getConnection);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return timed(() -> delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    record Summary(Map<String, Long> calls, Map<String, Long> nonUtility, long wal) {
        long calls(String role) {
            return calls.getOrDefault(role, 0L);
        }

        long nonUtility(String role) {
            return nonUtility.getOrDefault(role, 0L);
        }
    }

    private static Summary summarise(SpikeDb db, String file, String label) throws SQLException {
        long wal        = db.walSinceReset();
        var  statements = db.statements();
        var  calls      = new TreeMap<String, Long>();
        var  nonUtility = new TreeMap<String, Long>();
        for (var s : statements) {
            calls.merge(s.role(), s.calls(), Long::sum);
            if (!s.utility()) nonUtility.merge(s.role(), s.calls(), Long::sum);
        }
        SpikeDb.record(file, "  -- " + label + ": statements by role " + calls + " (non-utility " + nonUtility + "), WAL " + wal + " B");
        statements.stream().limit(10).forEach(s -> {
            var q = s.query().replaceAll("\\s+", " ");
            SpikeDb.record(file, String.format("     %-6s calls %7d rows %8d exec %9.1f ms | %s", s.role(), s.calls(), s.rows(), s.totalExecMs(), q.substring(0, Math.min(150, q.length()))));
        });
        return new Summary(calls, nonUtility, wal);
    }

    record Latencies(double p50, double p99, double max) {
        static Latencies of(Collection<Long> nanos) {
            if (nanos.isEmpty()) return new Latencies(Double.NaN, Double.NaN, Double.NaN);
            var sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            return new Latencies(sorted[(int) Math.floor(0.50 * (sorted.length - 1))] / 1e6, sorted[(int) Math.floor(0.99 * (sorted.length - 1))] / 1e6,
                                 sorted[sorted.length - 1] / 1e6);
        }
    }

    private static void header(String file, String shape, String setup) {
        SpikeDb.record(file, "");
        SpikeDb.record(file, "=== " + shape + " | " + Instant.now() + " | " + (SpikeDb.external() ? "external server" : "container " + SpikeDb.IMAGE + " (fsync=off)")
                + " | CPUs " + Runtime.getRuntime().availableProcessors() + " | " + setup);
    }

    private static Duration scaled(Duration duration) {
        return Duration.ofMillis(Math.round(duration.toMillis() * TIME_SCALE));
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("condition not met within " + timeout);
            Thread.sleep(5);
        }
    }
}
