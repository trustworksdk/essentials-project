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
import dk.trustworks.essentials.components.eventlog.processor.EventLogEventStore;
import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.NoEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring.SpringTransactionAwareEventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.spring.UnitOfWorkTransactionIsolation;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.Order;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.*;

import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;

/**
 * Phase 8, unit 8b-D of {@code docs/archive/event-store-engine-phase8.md} (§10.3), suite {@code p8b-gate}: the
 * {@link EventLogEventStore} adapter against {@link PostgresqlEventStore}, both wired as the Spring starter wires the
 * old engine ({@code Jdbi.create(new TransactionAwareDataSourceProxy(dataSource))} with the {@link PostgresPlugin} and
 * {@link SqlExecutionTimeLogger}, a {@link SpringTransactionAwareEventStoreUnitOfWorkFactory} on a
 * {@link DataSourceTransactionManager} with {@code READ_COMMITTED}; the old engine on
 * {@link SeparateTablePerAggregateTypePersistenceStrategy} with {@code TEXT} ids and {@code JSONB}; the adapter on an
 * {@link EventLog} with {@link EventLogSettings#defaults()}). Containers run with {@code fsync=on}.
 * <ul>
 *     <li>{@link #append()}: P0.4's shapes (1, 10, 100 events in one new aggregate; 5 new aggregates x 10 events) through
 *     {@link StatefulAggregateRepository#save}. Latency, statements by role, round trips (a counting TCP proxy, P0.4's),
 *     WAL per event, units of work per second.</li>
 *     <li>{@link #contended()}: P0.4's contended shape on the {@code EventStore} directly: 8 writers, 10 aggregates,
 *     {@code loadLastPersistedEventRelatedTo} then {@code appendToStream} with that expected order. Burned
 *     {@code global_order} values (identity sequence advance minus appended rows).</li>
 *     <li>{@link #fetch()}: {@code fetchStream} and {@code StatefulAggregateRepository.load} of a 1 000-event aggregate.</li>
 * </ul>
 * Fresh container per (run, case, arm), arm order alternating. {@code writer} = the application pool (every unit of
 * work), {@code reader} = the event log's own {@code DataSource} (adapter arm only). Results
 * {@code target/spike-results/p8-d-*.txt}. Knobs: {@code -Dspike.gate.runs} (2), {@code -Dspike.gate.timeScale} (1.0).
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class EventLogAdapterGateSpikeIT {
    private static final String APPEND     = "p8-d-append.txt";
    private static final String CONTENDED  = "p8-d-contended.txt";
    private static final String FETCH      = "p8-d-fetch.txt";
    private static final int    RUNS       = Integer.getInteger("spike.gate.runs", 2);
    private static final double TIME_SCALE = Double.parseDouble(System.getProperty("spike.gate.timeScale", "1.0"));

    enum Arm {OLD, ADAPTER}

    record Case(String label, int aggregates, int events, int iterations) {
    }

    private static final List<Case> CASES = List.of(new Case("1 event", 1, 1, 1000),
                                                    new Case("10 events", 1, 10, 1000),
                                                    new Case("100 events", 1, 100, 300),
                                                    new Case("5 aggregates x 10 events", 5, 10, 500));

    // ------------------------------------------------------------------ appends

    @Test
    void append() throws Exception {
        header(APPEND, "APPEND", "single thread, one unit of work per iteration, new aggregates through StatefulAggregateRepository.save; 50 warm-up units of work; "
                + "round trips over a separate 100-unit pass through a counting TCP proxy (20 warm-up)");
        for (int run = 1; run <= RUNS; run++) {
            for (int c = 0; c < CASES.size(); c++) {
                var arms = (run + c) % 2 == 1 ? List.of(Arm.OLD, Arm.ADAPTER) : List.of(Arm.ADAPTER, Arm.OLD);
                for (var arm : arms) append(run, c, CASES.get(c), arm);
            }
        }
    }

    private void append(int run, int caseIndex, Case c, Arm arm) throws Exception {
        try (var db = SpikeDb.start("p8d_a_r" + run + "_c" + caseIndex + "_" + arm.name().toLowerCase(Locale.ROOT), 50, true);
             var proxy = proxyFor(db);
             var app = new App(db, arm, 2, proxy.port())) {
            var type = AggregateType.of("Orders");
            for (int i = 0; i < 50; i++) app.direct.saveNew(c);
            db.resetStatistics();
            var latencies = new ArrayList<Long>(c.iterations());
            long started = System.nanoTime();
            for (int i = 0; i < c.iterations(); i++) {
                long t0 = System.nanoTime();
                app.direct.saveNew(c);
                latencies.add(System.nanoTime() - t0);
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            var    s       = summarise(db, APPEND, "append run " + run + ": " + c.label() + ": " + arm);
            long   events  = (long) c.iterations() * c.aggregates() * c.events();
            for (int i = 0; i < 20; i++) app.proxied.saveNew(c);
            proxy.reset();
            for (int i = 0; i < 100; i++) app.proxied.saveNew(c);
            double roundTrips = proxy.flights() / 100.0;
            var    l          = Latencies.of(latencies);
            SpikeDb.record(APPEND, String.format("APPEND run %d | %-26s | %-7s | p50 %.3f ms p99 %.3f ms | ROUND TRIPS/UoW %.2f | writer statements/UoW %.2f (non-utility %.2f, COMMIT %.2f) "
                                                         + "| reader statements/UoW %.3f | WAL/event %.0f B | %.0f UoW/s, %.0f events/s",
                                                 run, c.label(), arm, l.p50(), l.p99(), roundTrips, s.calls("writer") / (double) c.iterations(),
                                                 s.nonUtility("writer") / (double) c.iterations(), s.commits("writer") / (double) c.iterations(),
                                                 s.calls("reader") / (double) c.iterations(), s.wal() / (double) events, c.iterations() / seconds, events / seconds));
        }
    }

    // ------------------------------------------------------------------ contended

    @Test
    void contended() throws Exception {
        var window = Duration.ofMillis(Math.round(20_000 * TIME_SCALE));
        header(CONTENDED, "CONTENDED", "8 writers, 10 pre-created aggregates, " + window.toSeconds() + " s window after a 2 s warm-up on a throwaway type; each unit of work: "
                + "loadLastPersistedEventRelatedTo, then appendToStream of one event with that expected order (EventStore directly, P0.4's shape)");
        for (int run = 1; run <= RUNS; run++) {
            var arms = run % 2 == 1 ? List.of(Arm.OLD, Arm.ADAPTER) : List.of(Arm.ADAPTER, Arm.OLD);
            for (var arm : arms) {
                try (var db = SpikeDb.start("p8d_c_r" + run + "_" + arm.name().toLowerCase(Locale.ROOT), 50, true);
                     var proxy = proxyFor(db);
                     var app = new App(db, arm, 10, proxy.port())) {
                    contendedWindow(db, app, arm, run, AggregateType.of("Warmup"), Duration.ofSeconds(2), true);
                    contendedWindow(db, app, arm, run, AggregateType.of("Orders"), window, false);
                }
            }
        }
    }

    private void contendedWindow(SpikeDb db, App app, Arm arm, int run, AggregateType type, Duration window, boolean discard) throws Exception {
        var stack      = app.direct;
        if (stack.eventStore.findAggregateEventStreamConfiguration(type).isEmpty()) stack.eventStore.addAggregateEventStreamConfiguration(type, OrderId.class);
        var aggregates = new ArrayList<OrderId>();
        for (int i = 0; i < 10; i++) {
            var id = OrderId.random();
            stack.unitOfWorkFactory.usingUnitOfWork(uow -> stack.eventStore.appendToStream(type, id, Optional.empty(), List.of(new OrderEvent.OrderAdded(id, CustomerId.random(), 1))));
            aggregates.add(id);
        }
        var  sequence   = app.globalOrderSequence(type);
        long seqBefore  = SpikeDb.queryLong(db.observer, "SELECT last_value FROM " + sequence);
        long rowsBefore = SpikeDb.queryLong(db.observer, "SELECT count(*) FROM " + app.eventTable(type));
        db.resetStatistics();
        var attempts   = new AtomicLong();
        var conflicts  = new AtomicLong();
        var otherErr   = new AtomicLong();
        var firstOther = new AtomicReference<Throwable>();
        var latencies  = new ConcurrentLinkedQueue<Long>();
        long stopAt    = System.nanoTime() + window.toNanos();
        var  pool      = Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                var random = ThreadLocalRandom.current();
                while (System.nanoTime() < stopAt) {
                    var id = aggregates.get(random.nextInt(aggregates.size()));
                    attempts.incrementAndGet();
                    long t0 = System.nanoTime();
                    try {
                        stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
                            long last = stack.eventStore.loadLastPersistedEventRelatedTo(type, id).orElseThrow().eventOrder().longValue();
                            stack.eventStore.appendToStream(type, id, Optional.of(last), List.of(new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 1)));
                        });
                        latencies.add(System.nanoTime() - t0);
                    } catch (Throwable e) {
                        if (hasCause(e, OptimisticAppendToStreamException.class)) conflicts.incrementAndGet();
                        else {
                            otherErr.incrementAndGet();
                            firstOther.compareAndSet(null, e);
                        }
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(window.toSeconds() + 60, TimeUnit.SECONDS);
        if (discard) return;
        var  s         = summarise(db, CONTENDED, "contended run " + run + ": " + arm);
        long successes = SpikeDb.queryLong(db.observer, "SELECT count(*) FROM " + app.eventTable(type)) - rowsBefore;
        long burned    = SpikeDb.queryLong(db.observer, "SELECT last_value FROM " + sequence) - seqBefore - successes;
        var  l         = Latencies.of(latencies);
        SpikeDb.record(CONTENDED, String.format("CONTENDED run %d | %-7s | attempts %d, appended %d (%.0f appends/s), conflicts %d (%.1f %%), other errors %d | global_order values BURNED %d "
                                                        + "= %.2f per 1 000 appends, %.2f per 1 000 conflicts | successful UoW p50 %.3f ms p99 %.3f ms | writer statements/attempt %.2f "
                                                        + "(non-utility %.2f) | reader statements/attempt %.3f | WAL/appended event %.0f B",
                                                run, arm, attempts.get(), successes, successes / (window.toMillis() / 1000.0), conflicts.get(), 100.0 * conflicts.get() / Math.max(1, attempts.get()),
                                                otherErr.get(), burned, burned * 1000.0 / Math.max(1, successes), burned * 1000.0 / Math.max(1, conflicts.get()), l.p50(), l.p99(),
                                                s.calls("writer") / (double) attempts.get(), s.nonUtility("writer") / (double) attempts.get(), s.calls("reader") / (double) attempts.get(),
                                                s.wal() / (double) Math.max(1, successes)));
        if (firstOther.get() != null) SpikeDb.record(CONTENDED, "  first other error: " + firstOther.get());
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (var t = e; t != null; t = t.getCause()) if (type.isInstance(t)) return true;
        return false;
    }

    // ------------------------------------------------------------------ fetchStream

    @Test
    void fetch() throws Exception {
        header(FETCH, "FETCH", "one aggregate of 1 000 events (OrderAdded + 999 ProductAddedToOrder); FETCH = unit of work { fetchStream(type, id).eventList() }, "
                + "LOAD = unit of work { StatefulAggregateRepository.load(id) } (rehydrates); 50 warm-up, 300 measured each; round trips over 50 through the proxy");
        for (int run = 1; run <= RUNS; run++) {
            var arms = run % 2 == 1 ? List.of(Arm.OLD, Arm.ADAPTER) : List.of(Arm.ADAPTER, Arm.OLD);
            for (var arm : arms) {
                try (var db = SpikeDb.start("p8d_f_r" + run + "_" + arm.name().toLowerCase(Locale.ROOT), 50, true);
                     var proxy = proxyFor(db);
                     var app = new App(db, arm, 2, proxy.port())) {
                    var type = AggregateType.of("Orders");
                    var id   = OrderId.random();
                    app.direct.unitOfWorkFactory.usingUnitOfWork(uow -> {
                        var order = new Order(id, CustomerId.random(), 1);
                        for (int i = 1; i < 1000; i++) order.addProduct(ProductId.random(), i);
                        app.direct.orders.save(order);
                    });
                    for (var mode : List.of("FETCH", "LOAD")) {
                        Runnable direct = mode.equals("FETCH") ? () -> app.direct.fetch(type, id) : () -> app.direct.load(id);
                        Runnable proxied = mode.equals("FETCH") ? () -> app.proxied.fetch(type, id) : () -> app.proxied.load(id);
                        for (int i = 0; i < 50; i++) direct.run();
                        db.resetStatistics();
                        var latencies = new ArrayList<Long>();
                        for (int i = 0; i < 300; i++) {
                            long t0 = System.nanoTime();
                            direct.run();
                            latencies.add(System.nanoTime() - t0);
                        }
                        var s = summarise(db, FETCH, "fetch run " + run + ": " + mode + " " + arm);
                        for (int i = 0; i < 10; i++) proxied.run();
                        proxy.reset();
                        for (int i = 0; i < 50; i++) proxied.run();
                        var l = Latencies.of(latencies);
                        SpikeDb.record(FETCH, String.format("FETCH run %d | %-5s | %-7s | p50 %.3f ms p99 %.3f ms | ROUND TRIPS/UoW %.2f | writer statements/UoW %.2f (non-utility %.2f) | reader statements/UoW %.3f",
                                                            run, mode, arm, l.p50(), l.p99(), proxy.flights() / 50.0, s.calls("writer") / 300.0, s.nonUtility("writer") / 300.0,
                                                            s.calls("reader") / 300.0));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ wiring

    private static RoundTripCountingProxy proxyFor(SpikeDb db) throws Exception {
        var hostAndPort = db.hostAndPort().split(":");
        return new RoundTripCountingProxy(hostAndPort[0], Integer.parseInt(hostAndPort[1]));
    }

    /** One arm's database: the application stack direct and through the proxy, sharing one event log (adapter arm) */
    private static final class App implements AutoCloseable {
        final Arm              arm;
        final HikariDataSource readerPool;
        final EventLog         eventLog;
        final Stack            direct;
        final Stack            proxied;

        App(SpikeDb db, Arm arm, int poolSize, int proxyPort) throws SQLException {
            this.arm = arm;
            if (arm == Arm.ADAPTER) {
                readerPool = db.pool("reader", 4);
                eventLog = EventLog.builder().setDataSource(readerPool).setSettings(EventLogSettings.defaults()).build();
                eventLog.start();
            } else {
                readerPool = null;
                eventLog = null;
            }
            direct = new Stack(db.pool("writer", poolSize), eventLog);
            proxied = new Stack(db.poolVia("writer", 2, proxyPort), eventLog);
        }

        String eventTable(AggregateType type) throws SQLException {
            return arm == Arm.ADAPTER ? eventLog.registerAggregateType(type.toString()).eventTable()
                                      : ((PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration>) (Object) direct.eventStore).getAggregateEventStreamConfiguration(type).eventStreamTableName;
        }

        @SuppressWarnings("unchecked")
        String globalOrderSequence(AggregateType type) throws SQLException {
            var column = arm == Arm.ADAPTER ? "global_order"
                                            : ((PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration>) (Object) direct.eventStore).getAggregateEventStreamConfiguration(type)
                                                                                                                                                  .eventStreamTableColumnNames.globalOrderColumn;
            try (var c = direct.pool.getConnection()) {
                return SpikeDb.queryString(c, "SELECT pg_get_serial_sequence('" + eventTable(type) + "', '" + column + "')");
            }
        }

        @Override
        public void close() {
            if (eventLog != null) eventLog.stop();
            direct.pool.close();
            proxied.pool.close();
            if (readerPool != null) readerPool.close();
        }
    }

    /** The application side, as the Spring starter wires it */
    private static final class Stack {
        final HikariDataSource                                          pool;
        final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
        final ConfigurableEventStore<?>                                 eventStore;
        final StatefulAggregateRepository<OrderId, OrderEvent, Order>   orders;

        Stack(HikariDataSource pool, EventLog eventLog) {
            this.pool = pool;
            var jdbi = Jdbi.create(new TransactionAwareDataSourceProxy(pool));
            jdbi.installPlugin(new PostgresPlugin());
            jdbi.setSqlLogger(new SqlExecutionTimeLogger());
            var factory = new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, new DataSourceTransactionManager(pool));
            factory.setTransactionIsolation(UnitOfWorkTransactionIsolation.READ_COMMITTED);
            unitOfWorkFactory = factory;
            var serializer = EssentialsJSONEventSerializers.create();
            var mapper     = new TestPersistableEventMapper();
            if (eventLog != null) {
                eventStore = new EventLogEventStore(eventLog, factory, serializer, mapper);
            } else {
                var strategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                               .setJdbi(jdbi)
                                                                               .setUnitOfWorkFactory(factory)
                                                                               .setEventMapper(mapper)
                                                                               .setAggregateEventStreamConfigurationFactory(SeparateTablePerAggregateTypeEventStreamConfigurationFactory
                                                                                                                                    .standardSingleTenantConfiguration(serializer, IdentifierColumnType.TEXT, JSONColumnType.JSONB))
                                                                               .build();
                eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                                 .setUnitOfWorkFactory(factory)
                                                 .setPersistenceStrategy(strategy)
                                                 .setEventStreamGapHandlerFactory(es -> new NoEventStreamGapHandler<>())
                                                 .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver())
                                                 .build();
            }
            orders = repository(eventStore);
        }

        private static <C extends AggregateEventStreamConfiguration> StatefulAggregateRepository<OrderId, OrderEvent, Order> repository(ConfigurableEventStore<C> eventStore) {
            return StatefulAggregateRepository.from(eventStore, AggregateType.of("Orders"), reflectionBasedAggregateRootFactory(), Order.class);
        }

        void saveNew(Case c) {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                for (int a = 0; a < c.aggregates(); a++) {
                    var order = new Order(OrderId.random(), CustomerId.random(), a);
                    for (int i = 1; i < c.events(); i++) order.addProduct(ProductId.random(), i);
                    orders.save(order);
                }
            });
        }

        void fetch(AggregateType type, OrderId id) {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                if (eventStore.fetchStream(type, id).orElseThrow().eventList().size() != 1000) throw new IllegalStateException("not 1 000 events");
            });
        }

        void load(OrderId id) {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                if (orders.load(id).productAndQuantity.size() != 999) throw new IllegalStateException("not 999 products");
            });
        }
    }

    // ------------------------------------------------------------------ helpers

    record Summary(Map<String, Long> calls, Map<String, Long> nonUtility, Map<String, Long> commits, long wal) {
        long calls(String role) {
            return calls.getOrDefault(role, 0L);
        }

        long nonUtility(String role) {
            return nonUtility.getOrDefault(role, 0L);
        }

        long commits(String role) {
            return commits.getOrDefault(role, 0L);
        }
    }

    private static Summary summarise(SpikeDb db, String file, String label) throws SQLException {
        long wal        = db.walSinceReset();
        var  statements = db.statements();
        var  calls      = new TreeMap<String, Long>();
        var  nonUtility = new TreeMap<String, Long>();
        var  commits    = new TreeMap<String, Long>();
        for (var s : statements) {
            calls.merge(s.role(), s.calls(), Long::sum);
            if (!s.utility()) nonUtility.merge(s.role(), s.calls(), Long::sum);
            if (s.query().trim().equalsIgnoreCase("commit")) commits.merge(s.role(), s.calls(), Long::sum);
        }
        SpikeDb.record(file, "  -- " + label + ": statements by role " + calls + " (non-utility " + nonUtility + ", COMMIT " + commits + "), WAL " + wal + " B");
        statements.stream().limit(8).forEach(s -> {
            var q = s.query().replaceAll("\\s+", " ");
            SpikeDb.record(file, String.format("     %-6s calls %7d rows %8d exec %9.1f ms wal %10d B | %s", s.role(), s.calls(), s.rows(), s.totalExecMs(), s.walBytes(),
                                               q.substring(0, Math.min(140, q.length()))));
        });
        return new Summary(calls, nonUtility, commits, wal);
    }

    record Latencies(double p50, double p99) {
        static Latencies of(Collection<Long> nanos) {
            if (nanos.isEmpty()) return new Latencies(Double.NaN, Double.NaN);
            var sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            return new Latencies(sorted[(int) Math.floor(0.50 * (sorted.length - 1))] / 1e6, sorted[(int) Math.floor(0.99 * (sorted.length - 1))] / 1e6);
        }
    }

    private static void header(String file, String shape, String setup) {
        SpikeDb.record(file, "");
        SpikeDb.record(file, "=== " + shape + " | " + Instant.now() + " | " + (SpikeDb.external() ? "external server" : "container " + SpikeDb.IMAGE + " (fsync=on)")
                + " | CPUs " + Runtime.getRuntime().availableProcessors() + " | pgjdbc " + org.postgresql.Driver.getVersion() + " | " + setup);
    }
}
