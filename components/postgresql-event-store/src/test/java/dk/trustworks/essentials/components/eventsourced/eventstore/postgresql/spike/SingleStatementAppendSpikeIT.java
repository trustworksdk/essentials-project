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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.CurrentEngineBaselineSpikeIT.Stack;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike P0.4 of {@code docs/plans/event-store-subscription-redesign.md}: is the single-statement append of §5.1
 * better than today's JDBI {@code PreparedBatch} append?
 * <p>
 * Three arms write the same events to tables created by {@link SeparateTablePerAggregateTypePersistenceStrategy}
 * (so columns, types and indexes are the real ones), inside the same {@code EventStoreManagedUnitOfWorkFactory}
 * unit of work, with the same event mapper and the configuration's own serializer
 * ({@code EssentialsJSONEventSerializers.create()}):
 * <ul>
 *     <li><b>A</b> - {@code PostgresqlEventStore.appendToStream}, i.e. today's engine: the last-event-order lookup when
 *     no expected order is given, then a JDBI {@code PreparedBatch} of single-row {@code INSERT ... RETURNING}.</li>
 *     <li><b>C</b> - the same two statements through raw pgjdbc on the unit of work's connection, to separate
 *     library and engine cost from statement shape (P0.6 is the dedicated spike for that).</li>
 *     <li><b>B</b> - the §5.1 statement through raw pgjdbc: one {@code WITH xid, head, ok INSERT ... SELECT ... unnest}
 *     per aggregate; several aggregates in one unit of work are sent as one pgjdbc batch (§5.2). Zero rows for an
 *     aggregate is an optimistic-concurrency conflict.</li>
 * </ul>
 * Shapes: {@link #append()} (latency, statements, round trips and WAL at 1, 10 and 100 events, one or five aggregates
 * per unit of work, with and without an expected order) and {@link #contended()} (8 writers on 10 aggregates; burned
 * identity values for A, B and two advisory-lock variants of B, §5.3). {@link #sanity()} checks that B writes rows the
 * engine reads back identically. Arms alternate within each round, every (round, case, arm) gets a fresh table.
 * Results go to {@code target/spike-results/p0-4-append.txt}.
 * <p>
 * Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class SingleStatementAppendSpikeIT {
    private static final String        RESULTS    = "p0-4-append.txt";
    private static final int           RUNS       = Integer.getInteger("spike.append.runs", 2);
    private static final int           ROUNDS     = Integer.getInteger("spike.append.rounds", 3);
    private static final double        TIME_SCALE = Double.parseDouble(System.getProperty("spike.append.timeScale", "1.0"));
    private static final AtomicInteger TABLES     = new AtomicInteger();
    private static final AtomicReference<String> PAYLOAD_ID = new AtomicReference<>("-");
    static void payloadIdForSanity(String id) {
        PAYLOAD_ID.set(id);
    }

    /** Arms of {@link #append()}; default the original three. */
    private static final List<Arm> APPEND_ARMS = Arrays.stream(System.getProperty("spike.append.arms", "A_ENGINE,C_RAW_BATCH,B_SINGLE").split(",")).map(String::trim).map(Arm::valueOf).toList();
    /** Encoding of the {@code B_ENCODED} arm, and the one it falls back to for more than one event when that encoding is {@code SCALAR_1}. */
    /** JSON column types of {@link #append()}'s tables; each arm runs on each. Default JSONB only. */
    static final List<JSONColumnType> APPEND_JSON_TYPES = Arrays.stream(System.getProperty("spike.append.jsonTypes", "JSONB").split(",")).map(String::trim).map(JSONColumnType::valueOf).toList();
    static final Encoding APPEND_ENCODING       = Encoding.valueOf(System.getProperty("spike.append.encoding", "BINARY_ARRAYS"));
    static final Encoding APPEND_ENCODING_MULTI = Encoding.valueOf(System.getProperty("spike.append.encoding.multi", "BINARY_ARRAYS"));

    /**
     * How the B statement's per-event values reach the server (encoding follow-up, {@code event-store-phase0-append.md} §6):
     * <ul>
     *     <li>{@code TEXT_ARRAYS} - the §5.1 statement as P0.4 measured it: eight {@code createArrayOf("text", ...)} arrays,
     *     which pgjdbc sends as text array literals.</li>
     *     <li>{@code BINARY_ARRAYS} - the same statement, the arrays bound with {@code setObject(i, String[])}, which pgjdbc
     *     (42.7, {@code PgPreparedStatement.setObjectArray}) sends in binary as {@code varchar[]}; the elements are still text.</li>
     *     <li>{@code JSONB_ONE} - one {@code jsonb} parameter, an array of event objects, expanded with {@code jsonb_to_recordset}.</li>
     *     <li>{@code SCALAR_1} - one event only: scalar parameters, no {@code unnest}.</li>
     *     <li>{@code NO_HEAD} - diagnostic: text arrays, the expected order bound directly, no {@code head}/{@code ok} CTE,
     *     so a conflict is always a unique violation.</li>
     * </ul>
     */
    enum Encoding {
        TEXT_ARRAYS, BINARY_ARRAYS, JSONB_ONE, SCALAR_1, NO_HEAD
    }

    /** Who writes the events. */
    enum Arm {
        A_ENGINE("A engine (JDBI PreparedBatch)"),
        C_RAW_BATCH("C raw pgjdbc, today's statements"),
        B_SINGLE("B single statement (§5.1)"),
        /** B with the parameter encoding named by {@code -Dspike.append.encoding} (see {@link Encoding}); added by the encoding follow-up. */
        B_ENCODED("B encoded (" + System.getProperty("spike.append.encoding", "BINARY_ARRAYS") + ")");

        final String label;

        Arm(String label) {
            this.label = label;
        }
    }

    /**
     * How the B arm guards against the §5.3 race. {@code ON_CONFLICT_DO_NOTHING} is not a lock: it is plain B with
     * {@code ON CONFLICT (aggregate_id, event_order) DO NOTHING}, so a lost race returns zero rows instead of raising a
     * unique violation (used by {@link ContendedAppendThroughputSpikeIT}).
     */
    enum Lock {
        NONE, CTE, STATEMENT, ON_CONFLICT_DO_NOTHING
    }

    record Case(String label, int aggregates, int eventsPerAggregate, boolean expectedOrder, int iterations) {
    }

    private static final List<Case> CASES = List.of(new Case("1 event, no expected order", 1, 1, false, 1000),
                                                    new Case("1 event, expected order", 1, 1, true, 1000),
                                                    new Case("10 events, no expected order", 1, 10, false, 1000),
                                                    new Case("10 events, expected order", 1, 10, true, 1000),
                                                    new Case("100 events, no expected order", 1, 100, false, 300),
                                                    new Case("100 events, expected order", 1, 100, true, 300),
                                                    new Case("5 aggregates x 10 events, no expected order", 5, 10, false, 500),
                                                    new Case("5 aggregates x 10 events, expected order", 5, 10, true, 500));

    // ------------------------------------------------------------------ sanity

    @Test
    void sanity() throws Exception {
        header("SANITY", "B writes what the engine reads back; zero rows on a stale expected order consumes no identity value; a batch names the right aggregate");
        try (var pg = Pg.start()) {
            var stack = Stack.writer(pg, "writer");
            var w     = new Writer(stack);
            var type  = w.freshType("sanity");
            var cfg   = w.config(type);
            var a     = OrderId.random();
            var b     = OrderId.random();
            PAYLOAD_ID.set(a.toString()); // the same event objects go to every aggregate, so their payload names a
            var added   = new OrderEvent.OrderAdded(a, CustomerId.random(), 42);
            var product = new OrderEvent.ProductAddedToOrder(a, ProductId.random(), 3);

            // Same event objects through the engine (aggregate a) and through B (aggregate b)
            stack.unitOfWorkFactory.usingUnitOfWork(uow -> stack.eventStore.appendToStream(type, a, Optional.empty(), List.of(added, product)));
            var bRows = stack.unitOfWorkFactory.withUnitOfWork(uow -> w.single(uow, cfg, List.of(new Append(b, Optional.empty(), List.of(added, product))), Lock.NONE));
            assertThat(bRows.counts()).containsExactly(2);
            assertThat(bRows.persisted()).extracting(e -> e.eventOrder().longValue()).containsExactly(0L, 1L);

            var engineView = describeRows(pg, cfg, a);
            var singleView = describeRows(pg, cfg, b);
            assertThat(singleView).as("rows written by B equal the engine's, except ids and timestamps").isEqualTo(engineView);

            // Append after, with and without an expected order, then a stale one
            stack.unitOfWorkFactory.usingUnitOfWork(uow -> w.single(uow, cfg, List.of(new Append(b, Optional.empty(), List.of(product))), Lock.NONE));
            stack.unitOfWorkFactory.usingUnitOfWork(uow -> w.single(uow, cfg, List.of(new Append(b, Optional.of(2L), List.of(product))), Lock.NONE));
            long seqBefore = sequenceLastValue(pg, cfg);
            var stale = stack.unitOfWorkFactory.withUnitOfWork(uow -> w.single(uow, cfg, List.of(new Append(b, Optional.of(2L), List.of(product))), Lock.NONE));
            assertThat(stale.counts()).containsExactly(0);
            assertThat(sequenceLastValue(pg, cfg)).as("a stale expected order consumes no identity value").isEqualTo(seqBefore);

            // Engine reads B's stream back
            var stream = stack.unitOfWorkFactory.withUnitOfWork(uow -> stack.eventStore.fetchStream(type, b).orElseThrow().eventList());
            assertThat(stream).extracting(e -> e.eventOrder().longValue()).containsExactly(0L, 1L, 2L, 3L);
            assertThat(stream.get(0).event().getJsonDeserialized().orElseThrow()).isInstanceOf(OrderEvent.OrderAdded.class);
            assertThat(stream.get(3).event().getJsonDeserialized().orElseThrow()).isInstanceOf(OrderEvent.ProductAddedToOrder.class);

            // A batch of three aggregates, the middle one stale: counts name it, the others are written
            var c = OrderId.random();
            var d = OrderId.random();
            var batch = stack.unitOfWorkFactory.withUnitOfWork(uow -> w.single(uow, cfg, List.of(new Append(c, Optional.empty(), List.of(added)),
                                                                                                  new Append(b, Optional.of(0L), List.of(product)),
                                                                                                  new Append(d, Optional.of(-1L), List.of(added, product))), Lock.NONE));
            assertThat(batch.counts()).containsExactly(1, 0, 2);
            assertThat(batch.persisted()).hasSize(3);

            // The advisory-lock variants produce the same rows
            for (var lock : List.of(Lock.CTE, Lock.STATEMENT)) {
                var e = OrderId.random();
                var r = stack.unitOfWorkFactory.withUnitOfWork(uow -> w.single(uow, cfg, List.of(new Append(e, Optional.of(-1L), List.of(added, product))), lock));
                assertThat(r.counts()).containsExactly(2);
                assertThat(describeRows(pg, cfg, e)).isEqualTo(engineView);
            }
            record("SANITY ok | engine row view " + engineView);
            stack.close();
        }
    }

    static List<String> describeRows(Pg pg, SeparateTablePerAggregateEventStreamConfiguration cfg, OrderId id) throws SQLException {
        var c = cfg.eventStreamTableColumnNames;
        var out = new ArrayList<String>();
        try (var s = pg.observer.prepareStatement("SELECT " + String.join(", ", c.eventOrderColumn, c.eventTypeColumn, c.eventRevisionColumn,
                                                                           c.eventPayloadColumn + "::text", c.eventMetaDataColumn + "::text",
                                                                           "(" + c.correlationIdColumn + " IS NOT NULL)::text", "(" + c.causedByEventIdColumn + " IS NOT NULL)::text", c.tenantColumn,
                                                                           "pg_typeof(" + c.eventPayloadColumn + ")::text",
                                                                           "extract(timezone from " + c.timestampColumn + ")::text") +
                                                          " FROM " + cfg.eventStreamTableName + " WHERE " + c.aggregateIdColumn + " = ? ORDER BY " + c.eventOrderColumn)) {
            s.setString(1, id.toString());
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    var row = new StringJoiner(" | ");
                    for (int i = 1; i <= 10; i++) row.add(String.valueOf(rs.getString(i)));
                    out.add(row.toString().replace(id.toString(), "<id>").replace(PAYLOAD_ID.get(), "<id>"));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ append

    @Test
    void append() throws Exception {
        header("APPEND", "single thread, one unit of work per iteration, new aggregates; per run " + ROUNDS + " rounds after one discarded warm-up round; "
                + "arm order rotates per round; fresh table per (round, case, arm); 50 warm-up iterations per table; round trips counted in a separate "
                + "100-iteration pass through a counting TCP proxy");
        for (int run = 1; run <= RUNS; run++) {
            try (var pg = Pg.start(); var proxy = new RoundTripCountingProxy(pg.server.host(), pg.server.port())) {
                var directs  = new LinkedHashMap<JSONColumnType, Writer>();
                var proxieds = new LinkedHashMap<JSONColumnType, Writer>();
                for (var jt : APPEND_JSON_TYPES) {
                    directs.put(jt, new Writer(Stack.writer(pg, "writer", jt)));
                    proxieds.put(jt, new Writer(Stack.writer(pg, "writer", pg.server.viaProxy(proxy.port()), jt)));
                }
                var entries = new ArrayList<Map.Entry<Arm, JSONColumnType>>();
                for (var a : APPEND_ARMS) for (var jt : APPEND_JSON_TYPES) entries.add(Map.entry(a, jt));
                record("APPEND run " + run + " | pgjdbc " + org.postgresql.Driver.getVersion() + ", reWriteBatchedInserts=" + pg.driverProperty("reWriteBatchedInserts")
                               + ", prepareThreshold=" + pg.driverProperty("prepareThreshold") + ", server " + SpikePostgres.serverVersion(pg.observer));
                var samples = new LinkedHashMap<String, List<Long>>();
                var rows    = new LinkedHashMap<String, List<String>>();
                for (int round = 0; round <= ROUNDS; round++) {
                    boolean discard = round == 0;
                    var arms = rotate(entries, round);
                    for (var c : CASES) {
                        for (var entry : arms) {
                            var arm     = entry.getKey();
                            var direct  = directs.get(entry.getValue());
                            var proxied = proxieds.get(entry.getValue());
                            var label   = arm.label + (APPEND_JSON_TYPES.size() > 1 ? " [" + entry.getValue() + "]" : "");
                            var type = direct.freshType("r" + run + "_" + round);
                            proxied.register(type);
                            int iterations = discard ? Math.max(50, c.iterations() / 5) : c.iterations();
                            for (int i = 0; i < 50; i++) direct.unitOfWork(arm, type, c);
                            pg.resetStatistics();
                            var latencies = new ArrayList<Long>(iterations);
                            for (int i = 0; i < iterations; i++) {
                                long t0 = System.nanoTime();
                                direct.unitOfWork(arm, type, c);
                                latencies.add(System.nanoTime() - t0);
                            }
                            if (discard) continue;
                            long events = (long) iterations * c.aggregates() * c.eventsPerAggregate();
                            var  stats  = WindowStats.read(pg, "append run " + run + " round " + round + ": " + label + ": " + c.label());
                            var  cfgT   = direct.config(type);
                            long tableBytes = SpikePostgres.queryLong(pg.observer, "SELECT pg_total_relation_size('" + cfgT.eventStreamTableName + "')");
                            long tableRows  = SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + cfgT.eventStreamTableName);
                            for (int i = 0; i < 20; i++) proxied.unitOfWork(arm, type, c);
                            proxy.reset();
                            for (int i = 0; i < 100; i++) proxied.unitOfWork(arm, type, c);
                            double roundTrips = proxy.flights() / 100.0;
                            var l = Latencies.of(latencies);
                            var key = c.label() + " | " + label;
                            samples.computeIfAbsent(key, k -> new ArrayList<>()).addAll(latencies);
                            var line = String.format("p50 %.3f ms p99 %.3f ms | statements/UoW %.2f (non-utility %.2f) | round trips/UoW %.2f | WAL/event %.0f B | table %.0f B/row",
                                                     l.p50(), l.p99(), (double) stats.total() / iterations, (double) stats.nonUtility() / iterations, roundTrips,
                                                     (double) stats.walBytes() / events, (double) tableBytes / Math.max(1, tableRows));
                            rows.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
                            record(String.format("APPEND run %d round %d | %-44s | %-34s | %s", run, round, c.label(), label, line));
                        }
                    }
                }
                record("APPEND run " + run + " summary (all rounds pooled; per-round p50 in brackets)");
                for (var e : samples.entrySet()) {
                    var l = Latencies.of(e.getValue());
                    var perRound = rows.get(e.getKey()).stream().map(s -> s.substring(4, s.indexOf(" ms"))).toList();
                    var last = rows.get(e.getKey()).getLast();
                    record(String.format("APPEND run %d SUMMARY | %-80s | pooled p50 %.3f ms p99 %.3f ms | per-round p50 %s | last round: %s",
                                         run, e.getKey(), l.p50(), l.p99(), perRound, last.substring(last.indexOf("statements"))));
                }
                for (var x : directs.values()) x.stack.close();
                for (var x : proxieds.values()) x.stack.close();
            }
        }
    }

    private static <T> List<T> rotate(List<T> list, int by) {
        var copy = new ArrayList<>(list);
        Collections.rotate(copy, by);
        return copy;
    }

    // ------------------------------------------------------------------ contended

    enum ContendedArm {
        A_ENGINE(null, "A engine"),
        B_SINGLE(Lock.NONE, "B single statement"),
        B_LOCK_CTE(Lock.CTE, "B + pg_advisory_xact_lock as first CTE (§5.3 as written)"),
        B_LOCK_STATEMENT(Lock.STATEMENT, "B + pg_advisory_xact_lock as a separate statement before it");

        final Lock   lock;
        final String label;

        ContendedArm(Lock lock, String label) {
            this.lock = lock;
            this.label = label;
        }
    }

    /** 8 writers on 10 aggregates: each unit of work reads the aggregate's last event order and appends one event after it. */
    @Test
    void contended() throws Exception {
        var duration = Duration.ofMillis((long) (20_000 * TIME_SCALE));
        header("CONTENDED", "8 writers, 10 pre-created aggregates, " + duration.toSeconds() + " s per arm, fresh table per arm, arm order rotates per run; "
                + "each unit of work: loadLastPersistedEventRelatedTo, then append one event with that expected order");
        for (int run = 1; run <= RUNS; run++) {
            try (var pg = Pg.start()) {
                var w = new Writer(Stack.writer(pg, "writer"));
                for (var arm : rotate(List.of(ContendedArm.values()), run - 1)) {
                    // short warm-up on a throwaway table, then the measured window on a fresh one
                    contendedWindow(pg, w, arm, Duration.ofSeconds(2), run, true);
                    contendedWindow(pg, w, arm, duration, run, false);
                }
                w.stack.close();
            }
        }
    }

    private void contendedWindow(Pg pg, Writer w, ContendedArm arm, Duration duration, int run, boolean discard) throws Exception {
        var type = w.freshType("occ" + run);
        var cfg  = w.config(type);
        var aggregates = new ArrayList<OrderId>();
        for (int i = 0; i < 10; i++) {
            var id = OrderId.random();
            w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> w.stack.eventStore.appendToStream(type, id, Optional.empty(), List.of(new OrderEvent.OrderAdded(id, CustomerId.random(), 1))));
            aggregates.add(id);
        }
        long seqBefore  = sequenceLastValue(pg, cfg);
        long rowsBefore = rowCount(pg, cfg);
        pg.resetStatistics();
        var attempts      = new AtomicLong();
        var zeroRows      = new AtomicLong();
        var uniqueViol    = new AtomicLong();
        var otherErr      = new AtomicLong();
        var firstOther    = new AtomicReference<Throwable>();
        var stopAt        = System.nanoTime() + duration.toNanos();
        var pool          = Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                var random = ThreadLocalRandom.current();
                while (System.nanoTime() < stopAt) {
                    var id = aggregates.get(random.nextInt(aggregates.size()));
                    attempts.incrementAndGet();
                    try {
                        w.stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
                            long last   = w.stack.eventStore.loadLastPersistedEventRelatedTo(type, id).orElseThrow().eventOrder().longValue();
                            var  events = List.<Object>of(new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 1));
                            if (arm == ContendedArm.A_ENGINE) {
                                w.stack.eventStore.appendToStream(type, id, Optional.of(last), events);
                            } else {
                                var result = w.single(uow, cfg, List.of(new Append(id, Optional.of(last), events)), arm.lock);
                                if (result.counts()[0] == 0) throw new ZeroRows();
                            }
                        });
                    } catch (Throwable e) {
                        if (hasCause(e, ZeroRows.class)) zeroRows.incrementAndGet();
                        else if (isUniqueViolation(e)) uniqueViol.incrementAndGet();
                        else {
                            otherErr.incrementAndGet();
                            firstOther.compareAndSet(null, e);
                        }
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(duration.toSeconds() + 60, TimeUnit.SECONDS);
        if (discard) return;
        var  stats     = WindowStats.read(pg, "contended run " + run + ": " + arm.label);
        long successes = rowCount(pg, cfg) - rowsBefore;
        long burned    = sequenceLastValue(pg, cfg) - seqBefore - successes;
        record(String.format("CONTENDED run %d | %-62s | attempts %d, appended %d (%.0f/s), conflicts: zero-row %d, unique-violation %d, other errors %d | identity values burned %d = %.1f per 1 000 attempts, %.1f per 1 000 successful appends | statements per attempt %.2f",
                             run, arm.label, attempts.get(), successes, successes / (duration.toMillis() / 1000.0), zeroRows.get(), uniqueViol.get(), otherErr.get(),
                             burned, burned * 1000.0 / attempts.get(), burned * 1000.0 / Math.max(1, successes), (double) stats.total() / attempts.get()));
        if (firstOther.get() != null) record("  first other error: " + firstOther.get());
    }

    private static final class ZeroRows extends RuntimeException {
        ZeroRows() {
            super("zero rows: optimistic-concurrency conflict", null, false, false);
        }
    }

    private static boolean isUniqueViolation(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof OptimisticAppendToStreamException) return true;
            if (c instanceof SQLException s && "23505".equals(s.getSQLState())) return true;
        }
        return false;
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (var c = t; c != null; c = c.getCause()) if (type.isInstance(c)) return true;
        return false;
    }

    // ------------------------------------------------------------------ the writers

    record Append(OrderId aggregateId, Optional<Long> expectedOrder, List<Object> events) {
    }

    record Result(int[] counts, List<PersistedEvent> persisted) {
    }

    /** One application instance's writer: the engine (arm A) plus raw pgjdbc arms B and C on the same unit of work. */
    static final class Writer {
        final Stack                                                                         stack;
        private final Map<SeparateTablePerAggregateEventStreamConfiguration, Map<Lock, String>> singleSql = new ConcurrentHashMap<>();
        private final Map<SeparateTablePerAggregateEventStreamConfiguration, String[]>          batchSql  = new ConcurrentHashMap<>();
        private final CorrelationId                                                         correlationId   = CorrelationId.random();
        private final EventId                                                               causedByEventId = EventId.random();

        Writer(Stack stack) {
            this.stack = stack;
        }

        AggregateType freshType(String prefix) {
            var type = AggregateType.of("P04_" + prefix + "_" + TABLES.incrementAndGet());
            register(type);
            return type;
        }

        void register(AggregateType type) {
            if (stack.eventStore.findAggregateEventStreamConfiguration(type).isEmpty()) {
                stack.eventStore.addAggregateEventStreamConfiguration(type, OrderId.class);
            }
        }

        SeparateTablePerAggregateEventStreamConfiguration config(AggregateType type) {
            return stack.eventStore.getAggregateEventStreamConfiguration(type);
        }

        void unitOfWork(Arm arm, AggregateType type, Case c) {
            var cfg = config(type);
            stack.unitOfWorkFactory.usingUnitOfWork(uow -> {
                var appends = new ArrayList<Append>(c.aggregates());
                for (int a = 0; a < c.aggregates(); a++) {
                    var id = OrderId.random();
                    appends.add(new Append(id, c.expectedOrder() ? Optional.of(EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED.longValue()) : Optional.empty(),
                                           eventsFor(id, c.eventsPerAggregate())));
                }
                switch (arm) {
                    case A_ENGINE -> {
                        for (var append : appends) stack.eventStore.appendToStream(type, append.aggregateId(), append.expectedOrder(), append.events());
                    }
                    case C_RAW_BATCH -> {
                        for (var append : appends) rawBatch(uow, cfg, append);
                    }
                    case B_ENCODED -> {
                        var encoding = APPEND_ENCODING == Encoding.SCALAR_1 && c.eventsPerAggregate() > 1 ? APPEND_ENCODING_MULTI : APPEND_ENCODING;
                        var result   = encoded(uow, cfg, appends, encoding);
                        for (int i = 0; i < result.counts().length; i++) {
                            if (result.counts()[i] == 0) throw new OptimisticAppendToStreamException("zero rows for " + appends.get(i).aggregateId());
                        }
                    }
                    case B_SINGLE -> {
                        var result = single(uow, cfg, appends, Lock.NONE);
                        for (int i = 0; i < result.counts().length; i++) {
                            if (result.counts()[i] == 0) throw new OptimisticAppendToStreamException("zero rows for " + appends.get(i).aggregateId());
                        }
                    }
                }
            });
        }

        private static List<Object> eventsFor(OrderId id, int count) {
            var events = new ArrayList<Object>(count);
            events.add(new OrderEvent.OrderAdded(id, CustomerId.random(), ThreadLocalRandom.current().nextLong(1_000_000)));
            for (int i = 1; i < count; i++) events.add(new OrderEvent.ProductAddedToOrder(id, ProductId.random(), 2));
            return events;
        }

        /** Same mapping as the P0.0 baseline's (and arm A's) event mapper. */
        private PersistableEvent map(SeparateTablePerAggregateEventStreamConfiguration cfg, Object aggregateId, Object event, long eventOrder) {
            return PersistableEvent.from(EventId.random(), cfg.aggregateType, aggregateId, EventTypeOrName.with(event.getClass()), event, EventOrder.of(eventOrder),
                                         EventRevision.of(1), EventMetaData.of("Key1", "Value1", "Key2", "Value2"), OffsetDateTime.now(), causedByEventId, correlationId, null);
        }

        // -------------------------------------------------------------- arm C: today's statements, raw pgjdbc

        void rawBatch(EventStoreUnitOfWork uow, SeparateTablePerAggregateEventStreamConfiguration cfg, Append append) throws SQLException {
            var sql        = batchSql.computeIfAbsent(cfg, Writer::batchStatements);
            var connection = uow.handle().getConnection();
            var columns    = cfg.eventStreamTableColumnNames;
            var aggregateId = cfg.aggregateIdSerializer.serialize(append.aggregateId());
            long last;
            if (append.expectedOrder().isPresent()) {
                last = append.expectedOrder().get();
            } else {
                try (var lookup = connection.prepareStatement(sql[0])) {
                    lookup.setFetchSize(1);
                    lookup.setString(1, aggregateId);
                    try (var rs = lookup.executeQuery()) {
                        last = rs.next() ? rs.getLong(columns.eventOrderColumn) : EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED.longValue();
                    }
                }
            }
            var mapped = new ArrayList<Serialized>(append.events().size());
            try (var insert = connection.prepareStatement(sql[1], new String[]{columns.globalOrderColumn})) {
                long order = last;
                for (var event : append.events()) {
                    var s = Serialized.of(cfg, map(cfg, append.aggregateId(), event, ++order));
                    mapped.add(s);
                    int p = 1;
                    insert.setString(p++, aggregateId);
                    insert.setLong(p++, s.event().eventOrder().longValue());
                    insert.setString(p++, s.event().eventId().toString());
                    insert.setString(p++, s.causedBy());
                    insert.setString(p++, s.correlation());
                    insert.setString(p++, s.json().getEventTypeOrNamePersistenceValue());
                    insert.setInt(p++, s.event().eventRevision().intValue());
                    insert.setObject(p++, s.timestamp());
                    insert.setString(p++, s.json().getJson());
                    insert.setString(p++, s.metaData().getJson());
                    insert.setString(p, null);
                    insert.addBatch();
                }
                insert.executeBatch();
                var persisted = new ArrayList<PersistedEvent>(mapped.size());
                try (var keys = insert.getGeneratedKeys()) {
                    int i = 0;
                    while (keys.next()) {
                        var s = mapped.get(i++);
                        persisted.add(PersistedEvent.from(s.event(), cfg.aggregateType, GlobalEventOrder.of(keys.getLong(1)), s.json(), s.metaData(), s.timestamp()));
                    }
                }
                uow.registerEventsPersisted(persisted);
            }
        }

        private static String[] batchStatements(SeparateTablePerAggregateEventStreamConfiguration cfg) {
            var c = cfg.eventStreamTableColumnNames;
            var lookup = "SELECT * FROM " + cfg.eventStreamTableName + " WHERE " + c.aggregateIdColumn + " = ? ORDER BY " + c.eventOrderColumn + " DESC LIMIT 1";
            var insert = "INSERT INTO " + cfg.eventStreamTableName + " (" + String.join(", ", c.aggregateIdColumn, c.eventOrderColumn, c.eventIdColumn, c.causedByEventIdColumn,
                                                                                    c.correlationIdColumn, c.eventTypeColumn, c.eventRevisionColumn, c.timestampColumn,
                                                                                    c.eventPayloadColumn, c.eventMetaDataColumn, c.tenantColumn) +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::" + cfg.eventJsonColumnType + ", ?::" + cfg.eventMetadataJsonColumnType + ", ?) RETURNING " + c.globalOrderColumn;
            return new String[]{lookup, insert};
        }

        // -------------------------------------------------------------- arm B: the §5.1 statement

        /**
         * One §5.1 statement per aggregate; more than one aggregate is sent as one pgjdbc batch (§5.2). Returns the rows
         * inserted per aggregate (0 = optimistic-concurrency conflict) and the persisted events built from
         * {@code RETURNING global_order, event_order}.
         */
        Result single(EventStoreUnitOfWork uow, SeparateTablePerAggregateEventStreamConfiguration cfg, List<Append> appends, Lock lock) throws SQLException {
            var sql        = singleSql.computeIfAbsent(cfg, Writer::singleStatements).get(lock);
            var connection = uow.handle().getConnection();
            var mapped     = new ArrayList<List<Serialized>>(appends.size());
            if (lock == Lock.STATEMENT) {
                try (var l = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    for (var append : appends) {
                        l.setString(1, lockKey(cfg, append));
                        l.execute();
                    }
                }
            }
            boolean batch = appends.size() > 1;
            try (var statement = batch ? connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) : connection.prepareStatement(sql)) {
                for (var append : appends) {
                    // event_order is assigned on the server; the mapper gets a provisional one (§5.1 has no client-side order without an expected order)
                    long provisional = append.expectedOrder().orElse(EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED.longValue());
                    var  events      = new ArrayList<Serialized>(append.events().size());
                    for (var event : append.events()) events.add(Serialized.of(cfg, map(cfg, append.aggregateId(), event, ++provisional)));
                    mapped.add(events);
                    int n = events.size();
                    var ids = new String[n];
                    var causes = new String[n];
                    var correlations = new String[n];
                    var types = new String[n];
                    var revisions = new String[n];
                    var timestamps = new String[n];
                    var payloads = new String[n];
                    var metadata = new String[n];
                    for (int i = 0; i < n; i++) {
                        var s = events.get(i);
                        ids[i] = s.event().eventId().toString();
                        causes[i] = s.causedBy();
                        correlations[i] = s.correlation();
                        types[i] = s.json().getEventTypeOrNamePersistenceValue();
                        revisions[i] = String.valueOf(s.event().eventRevision().intValue());
                        timestamps[i] = s.timestamp().toString();
                        payloads[i] = s.json().getJson();
                        metadata[i] = s.metaData().getJson();
                    }
                    int p = 1;
                    if (lock == Lock.CTE) statement.setString(p++, lockKey(cfg, append));
                    var aggregateId = cfg.aggregateIdSerializer.serialize(append.aggregateId());
                    statement.setString(p++, aggregateId);
                    if (append.expectedOrder().isPresent()) {
                        statement.setLong(p++, append.expectedOrder().get());
                        statement.setLong(p++, append.expectedOrder().get());
                    } else {
                        statement.setNull(p++, Types.BIGINT);
                        statement.setNull(p++, Types.BIGINT);
                    }
                    statement.setString(p++, aggregateId);
                    statement.setString(p++, null); // tenant
                    statement.setArray(p++, connection.createArrayOf("text", ids));
                    statement.setArray(p++, connection.createArrayOf("text", causes));
                    statement.setArray(p++, connection.createArrayOf("text", correlations));
                    statement.setArray(p++, connection.createArrayOf("text", types));
                    statement.setArray(p++, connection.createArrayOf("text", revisions));
                    statement.setArray(p++, connection.createArrayOf("text", timestamps));
                    statement.setArray(p++, connection.createArrayOf("text", payloads));
                    statement.setArray(p, connection.createArrayOf("text", metadata));
                    if (batch) statement.addBatch();
                }
                var counts    = new int[appends.size()];
                var persisted = new ArrayList<PersistedEvent>();
                if (batch) {
                    var updateCounts = statement.executeBatch();
                    System.arraycopy(updateCounts, 0, counts, 0, counts.length);
                    try (var rs = statement.getGeneratedKeys()) {
                        for (int a = 0; a < appends.size(); a++) {
                            for (int i = 0; i < counts[a]; i++) {
                                if (!rs.next()) throw new IllegalStateException("fewer returned rows than the update counts say");
                                persisted.add(persisted(cfg, appends.get(a), mapped.get(a).get(i), rs));
                            }
                        }
                    }
                } else {
                    try (var rs = statement.executeQuery()) {
                        int i = 0;
                        while (rs.next()) persisted.add(persisted(cfg, appends.getFirst(), mapped.getFirst().get(i++), rs));
                        counts[0] = i;
                    }
                }
                uow.registerEventsPersisted(persisted);
                return new Result(counts, persisted);
            }
        }

        private final Map<SeparateTablePerAggregateEventStreamConfiguration, Map<Encoding, String>> encodedSql = new ConcurrentHashMap<>();

        /** The B statement with the given parameter {@link Encoding}; same results and batching as {@link #single}. */
        Result encoded(EventStoreUnitOfWork uow, SeparateTablePerAggregateEventStreamConfiguration cfg, List<Append> appends, Encoding encoding) throws SQLException {
            if (encoding == Encoding.TEXT_ARRAYS) return single(uow, cfg, appends, Lock.NONE);
            var sql        = encodedSql.computeIfAbsent(cfg, Writer::encodedStatements).get(encoding);
            var connection = uow.handle().getConnection();
            var mapped     = new ArrayList<List<Serialized>>(appends.size());
            boolean batch  = appends.size() > 1;
            try (var statement = batch ? connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) : connection.prepareStatement(sql)) {
                for (var append : appends) {
                    long provisional = append.expectedOrder().orElse(EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED.longValue());
                    var  events      = new ArrayList<Serialized>(append.events().size());
                    for (var event : append.events()) events.add(Serialized.of(cfg, map(cfg, append.aggregateId(), event, ++provisional)));
                    mapped.add(events);
                    if (encoding == Encoding.SCALAR_1 && events.size() != 1) throw new IllegalArgumentException("SCALAR_1 takes exactly one event");
                    if (encoding == Encoding.NO_HEAD && append.expectedOrder().isEmpty()) throw new IllegalArgumentException("NO_HEAD needs an expected order");
                    var aggregateId = cfg.aggregateIdSerializer.serialize(append.aggregateId());
                    int p = 1;
                    if (encoding != Encoding.NO_HEAD) {
                        statement.setString(p++, aggregateId);
                        if (append.expectedOrder().isPresent()) {
                            statement.setLong(p++, append.expectedOrder().get());
                            statement.setLong(p++, append.expectedOrder().get());
                        } else {
                            statement.setNull(p++, Types.BIGINT);
                            statement.setNull(p++, Types.BIGINT);
                        }
                    }
                    statement.setString(p++, aggregateId);
                    if (encoding == Encoding.NO_HEAD) statement.setLong(p++, append.expectedOrder().get());
                    switch (encoding) {
                        case SCALAR_1 -> {
                            var s = events.getFirst();
                            statement.setString(p++, s.event().eventId().toString());
                            statement.setString(p++, s.causedBy());
                            statement.setString(p++, s.correlation());
                            statement.setString(p++, s.json().getEventTypeOrNamePersistenceValue());
                            statement.setString(p++, String.valueOf(s.event().eventRevision().intValue()));
                            statement.setString(p++, s.timestamp().toString());
                            statement.setString(p++, s.json().getJson());
                            statement.setString(p++, s.metaData().getJson());
                            statement.setString(p, null); // tenant
                        }
                        case JSONB_ONE -> {
                            statement.setString(p++, null); // tenant
                            var json = new StringBuilder(256 * events.size()).append('[');
                            for (int i = 0; i < events.size(); i++) {
                                var s = events.get(i);
                                if (i > 0) json.append(',');
                                json.append("{\"id\":").append(jsonString(s.event().eventId().toString()))
                                    .append(",\"caused_by\":").append(jsonString(s.causedBy()))
                                    .append(",\"correlation\":").append(jsonString(s.correlation()))
                                    .append(",\"type\":").append(jsonString(s.json().getEventTypeOrNamePersistenceValue()))
                                    .append(",\"revision\":").append(jsonString(String.valueOf(s.event().eventRevision().intValue())))
                                    .append(",\"ts\":").append(jsonString(s.timestamp().toString()))
                                    .append(",\"payload\":").append(s.json().getJson())
                                    .append(",\"metadata\":").append(s.metaData().getJson())
                                    .append('}');
                            }
                            statement.setString(p, json.append(']').toString());
                        }
                        case BINARY_ARRAYS, NO_HEAD -> {
                            statement.setString(p++, null); // tenant
                            var columns = columnArrays(events);
                            for (var column : columns) {
                                if (encoding == Encoding.BINARY_ARRAYS) statement.setObject(p++, column); // binary varchar[] (PgPreparedStatement.setObjectArray)
                                else statement.setArray(p++, connection.createArrayOf("text", column));
                            }
                        }
                        default -> throw new IllegalStateException(encoding.name());
                    }
                    if (batch) statement.addBatch();
                }
                var counts    = new int[appends.size()];
                var persisted = new ArrayList<PersistedEvent>();
                if (batch) {
                    var updateCounts = statement.executeBatch();
                    System.arraycopy(updateCounts, 0, counts, 0, counts.length);
                    try (var rs = statement.getGeneratedKeys()) {
                        for (int a = 0; a < appends.size(); a++) {
                            for (int i = 0; i < counts[a]; i++) {
                                if (!rs.next()) throw new IllegalStateException("fewer returned rows than the update counts say");
                                persisted.add(persisted(cfg, appends.get(a), mapped.get(a).get(i), rs));
                            }
                        }
                    }
                } else {
                    try (var rs = statement.executeQuery()) {
                        int i = 0;
                        while (rs.next()) persisted.add(persisted(cfg, appends.getFirst(), mapped.getFirst().get(i++), rs));
                        counts[0] = i;
                    }
                }
                uow.registerEventsPersisted(persisted);
                return new Result(counts, persisted);
            }
        }

        private static String[][] columnArrays(List<Serialized> events) {
            int n = events.size();
            var out = new String[8][n];
            for (int i = 0; i < n; i++) {
                var s = events.get(i);
                out[0][i] = s.event().eventId().toString();
                out[1][i] = s.causedBy();
                out[2][i] = s.correlation();
                out[3][i] = s.json().getEventTypeOrNamePersistenceValue();
                out[4][i] = String.valueOf(s.event().eventRevision().intValue());
                out[5][i] = s.timestamp().toString();
                out[6][i] = s.json().getJson();
                out[7][i] = s.metaData().getJson();
            }
            return out;
        }

        static String jsonString(String value) {
            if (value == null) return "null";
            var sb = new StringBuilder(value.length() + 2).append('"');
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                        else sb.append(ch);
                    }
                }
            }
            return sb.append('"').toString();
        }

        static Map<Encoding, String> encodedStatements(SeparateTablePerAggregateEventStreamConfiguration cfg) {
            var c    = cfg.eventStreamTableColumnNames;
            var t    = cfg.eventStreamTableName;
            var idT  = cfg.aggregateIdColumnType.toString();
            var evT  = cfg.eventIdColumnType.toString();
            var corT = cfg.correlationIdColumnType.toString();
            var head = "WITH xid  AS MATERIALIZED (SELECT pg_current_xact_id() AS x),\n" +
                    "     head AS MATERIALIZED (SELECT coalesce(max(" + c.eventOrderColumn + "), -1) AS v FROM " + t + " WHERE " + c.aggregateIdColumn + " = ?::" + idT + "),\n" +
                    "     ok   AS (SELECT head.v FROM head, xid WHERE ?::bigint IS NULL OR head.v = ?::bigint)\n";
            var insert = "INSERT INTO " + t + " (" + String.join(", ", c.aggregateIdColumn, c.eventOrderColumn, c.eventIdColumn, c.causedByEventIdColumn, c.correlationIdColumn,
                                                                    c.eventTypeColumn, c.eventRevisionColumn, c.timestampColumn, c.eventPayloadColumn, c.eventMetaDataColumn, c.tenantColumn) + ")\n";
            var returning = "RETURNING " + c.globalOrderColumn + ", " + c.eventOrderColumn;
            var unnest = "CROSS JOIN unnest(?::" + evT + "[], ?::" + evT + "[], ?::" + corT + "[], ?::text[], ?::text[], ?::timestamptz[], ?::text[], ?::text[])\n" +
                    "           WITH ORDINALITY AS e(id, caused_by, correlation, type, revision, ts, payload, metadata, n)\n";
            var fromE = "e.id, e.caused_by, e.correlation, e.type, e.revision, e.ts,\n       e.payload::" + cfg.eventJsonColumnType + ", e.metadata::" + cfg.eventMetadataJsonColumnType + ", ?::text\n";
            var binary = head + insert + "SELECT ?::" + idT + ", ok.v + e.n, " + fromE + "FROM ok\n" + unnest + returning;
            var jsonb = head + insert + "SELECT ?::" + idT + ", ok.v + e.n, e.id::" + evT + ", e.caused_by::" + evT + ", e.correlation::" + corT + ", e.type, e.revision, e.ts,\n" +
                    "       e.payload::" + cfg.eventJsonColumnType + ", e.metadata::" + cfg.eventMetadataJsonColumnType + ", ?::text\n" +
                    "FROM ok\n" +
                    "CROSS JOIN ROWS FROM (jsonb_to_recordset(?::jsonb) AS (id text, caused_by text, correlation text, type text, revision text, ts timestamptz, payload jsonb, metadata jsonb))\n" +
                    "           WITH ORDINALITY AS e(id, caused_by, correlation, type, revision, ts, payload, metadata, n)\n" + returning;
            var scalar = head + insert + "SELECT ?::" + idT + ", ok.v + 1, ?::" + evT + ", ?::" + evT + ", ?::" + corT + ", ?::text, ?::text, ?::timestamptz,\n" +
                    "       ?::" + cfg.eventJsonColumnType + ", ?::" + cfg.eventMetadataJsonColumnType + ", ?::text\n" +
                    "FROM ok\n" + returning;
            var noHead = "WITH xid AS MATERIALIZED (SELECT pg_current_xact_id() AS x)\n" + insert + "SELECT ?::" + idT + ", ?::bigint + e.n, " + fromE + "FROM xid\n" + unnest + returning;
            return Map.of(Encoding.BINARY_ARRAYS, binary, Encoding.JSONB_ONE, jsonb, Encoding.SCALAR_1, scalar, Encoding.NO_HEAD, noHead);
        }

        private static String lockKey(SeparateTablePerAggregateEventStreamConfiguration cfg, Append append) {
            return cfg.eventStreamTableName + ":" + cfg.aggregateIdSerializer.serialize(append.aggregateId());
        }

        private static PersistedEvent persisted(SeparateTablePerAggregateEventStreamConfiguration cfg, Append append, Serialized s, ResultSet rs) throws SQLException {
            var e = s.event();
            return PersistedEvent.from(e.eventId(), cfg.aggregateType, append.aggregateId(), s.json(), EventOrder.of(rs.getLong(2)), e.eventRevision(),
                                       GlobalEventOrder.of(rs.getLong(1)), s.metaData(), s.timestamp(), e.causedByEventId(), e.correlationId(), e.tenant());
        }

        private static Map<Lock, String> singleStatements(SeparateTablePerAggregateEventStreamConfiguration cfg) {
            var c    = cfg.eventStreamTableColumnNames;
            var t    = cfg.eventStreamTableName;
            var idT  = cfg.aggregateIdColumnType.toString();
            var evT  = cfg.eventIdColumnType.toString();
            var corT = cfg.correlationIdColumnType.toString();
            var body = "     head AS MATERIALIZED (SELECT coalesce(max(" + c.eventOrderColumn + "), -1) AS v FROM " + t + " WHERE " + c.aggregateIdColumn + " = ?::" + idT + "),\n" +
                    "     ok   AS (SELECT head.v FROM head, xid%s WHERE ?::bigint IS NULL OR head.v = ?::bigint)\n" +
                    "INSERT INTO " + t + " (" + String.join(", ", c.aggregateIdColumn, c.eventOrderColumn, c.eventIdColumn, c.causedByEventIdColumn, c.correlationIdColumn,
                                                            c.eventTypeColumn, c.eventRevisionColumn, c.timestampColumn, c.eventPayloadColumn, c.eventMetaDataColumn, c.tenantColumn) + ")\n" +
                    "SELECT ?::" + idT + ", ok.v + e.n, e.id, e.caused_by, e.correlation, e.type, e.revision, e.ts,\n" +
                    "       e.payload::" + cfg.eventJsonColumnType + ", e.metadata::" + cfg.eventMetadataJsonColumnType + ", ?::text\n" +
                    "FROM ok\n" +
                    "CROSS JOIN unnest(?::" + evT + "[], ?::" + evT + "[], ?::" + corT + "[], ?::text[], ?::text[], ?::timestamptz[], ?::text[], ?::text[])\n" +
                    "           WITH ORDINALITY AS e(id, caused_by, correlation, type, revision, ts, payload, metadata, n)\n" +
                    "RETURNING " + c.globalOrderColumn + ", " + c.eventOrderColumn;
            var plain = "WITH xid  AS MATERIALIZED (SELECT pg_current_xact_id() AS x),\n" + body.formatted("");
            var cte = "WITH lck  AS MATERIALIZED (SELECT pg_advisory_xact_lock(hashtextextended(?, 0)) AS l),\n" +
                    "     xid  AS MATERIALIZED (SELECT pg_current_xact_id() AS x FROM lck),\n" + body.formatted(", lck");
            var onConflict = plain.replace("RETURNING ", "ON CONFLICT (" + c.aggregateIdColumn + ", " + c.eventOrderColumn + ") DO NOTHING\nRETURNING ");
            return Map.of(Lock.NONE, plain, Lock.STATEMENT, plain, Lock.CTE, cte, Lock.ON_CONFLICT_DO_NOTHING, onConflict);
        }
    }

    /** A mapped event and what the configuration's serializer made of it. */
    record Serialized(PersistableEvent event, dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventJSON json,
                      dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventMetaDataJSON metaData, OffsetDateTime timestamp) {
        static Serialized of(SeparateTablePerAggregateEventStreamConfiguration cfg, PersistableEvent event) {
            return new Serialized(event, cfg.jsonSerializer.serializeEvent(event.event()), cfg.jsonSerializer.serializeMetaData(event.metaData()),
                                  event.timestamp().orElseGet(OffsetDateTime::now).withOffsetSameInstant(ZoneOffset.UTC));
        }

        String causedBy() {
            return event.causedByEventId().map(Object::toString).orElse(null);
        }

        String correlation() {
            return event.correlationId().map(Object::toString).orElse(null);
        }
    }

    // ------------------------------------------------------------------ measurement support

    record WindowStats(long total, long nonUtility, long walBytes) {
        static WindowStats read(Pg pg, String label) throws SQLException {
            long wal = SpikePostgres.queryLong(pg.observer, "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '" + pg.lsnAtReset + "'::pg_lsn)::bigint");
            long total = 0, nonUtility = 0;
            var top = new ArrayList<String>();
            try (var s = pg.observer.createStatement();
                 var rs = s.executeQuery("SELECT s.query, s.calls, s.rows, s.wal_bytes::bigint, s.total_exec_time FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid " +
                                                 "WHERE r.rolname = '" + pg.server.role("writer") + "' ORDER BY s.calls DESC")) {
                while (rs.next()) {
                    var q = rs.getString(1).trim().toLowerCase(Locale.ROOT);
                    long calls = rs.getLong(2);
                    total += calls;
                    boolean utility = q.startsWith("begin") || q.startsWith("commit") || q.startsWith("rollback") || q.startsWith("set ") || q.startsWith("show ")
                            || q.startsWith("savepoint") || q.startsWith("release") || q.startsWith("reset") || q.startsWith("start transaction");
                    if (!utility) nonUtility += calls;
                    if (top.size() < 5) {
                        var oneLine = rs.getString(1).replaceAll("\\s+", " ");
                        top.add(String.format("     calls %7d rows %8d wal %10d B exec %8.1f ms | %s", calls, rs.getLong(3), rs.getLong(4), rs.getDouble(5),
                                              oneLine.substring(0, Math.min(150, oneLine.length()))));
                    }
                }
            }
            record("  -- " + label + ": writer statements " + total + " (non-utility " + nonUtility + "), WAL " + wal + " B");
            top.forEach(SingleStatementAppendSpikeIT::record);
            return new WindowStats(total, nonUtility, wal);
        }
    }

    private static long sequenceLastValue(Pg pg, SeparateTablePerAggregateEventStreamConfiguration cfg) throws SQLException {
        return SpikePostgres.queryLong(pg.observer, "SELECT last_value FROM " + SpikePostgres.queryString(pg.observer, "SELECT pg_get_serial_sequence('" + cfg.eventStreamTableName + "', '"
                + cfg.eventStreamTableColumnNames.globalOrderColumn + "')"));
    }

    private static long rowCount(Pg pg, SeparateTablePerAggregateEventStreamConfiguration cfg) throws SQLException {
        return SpikePostgres.queryLong(pg.observer, "SELECT count(*) FROM " + cfg.eventStreamTableName);
    }

    private static void header(String shape, String setup) {
        record("");
        record("=== " + shape + " | " + Instant.now() + " | runs " + RUNS + " | CPUs " + Runtime.getRuntime().availableProcessors()
                       + " | max heap " + (Runtime.getRuntime().maxMemory() >> 20) + " MB | " + setup + (SpikeServer.external() ? " | " + SpikeServer.target("") : ""));
    }

    private static void record(String line) {
        SpikePostgres.record(RESULTS, line);
    }
}
