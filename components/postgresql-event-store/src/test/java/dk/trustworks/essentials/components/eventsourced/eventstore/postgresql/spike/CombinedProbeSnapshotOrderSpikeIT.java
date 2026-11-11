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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.Protocol;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike P0.2 of {@code docs/plans/event-store-subscription-redesign.md}: does the combined probe of §4.10 - a tail read
 * with a scalar sub-select over {@code pg_stat_activity} - read the running-writer set {@code X} after the statement's
 * MVCC snapshot, so that a writer holding a {@code global_order <= S} that the snapshot cannot see is always in
 * {@code X}?
 * <p>
 * Writers here take their xid first ({@code SELECT pg_current_xact_id()}) and then insert, which is the §4.3
 * prerequisite; the spike is about the reader, not the writer. Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class CombinedProbeSnapshotOrderSpikeIT {
    private static final String RESULTS = "p0-2-combined-probe-snapshot-order.txt";

    private static final String RUNNING_SUBSELECT = "(SELECT array_agg(backend_xid::text) FROM pg_stat_activity WHERE backend_xid IS NOT NULL)";
    /** §4.10 as written. */
    static final         String COMBINED          = "SELECT e.global_order, " + RUNNING_SUBSELECT + " AS running FROM spike_events e " +
            "WHERE e.global_order > ? ORDER BY e.global_order LIMIT ?";
    /** §4.10 with a hold in the scan: after the snapshot is taken, before any row is projected. */
    static final         String COMBINED_HELD     = "SELECT e.global_order, " + RUNNING_SUBSELECT + " AS running FROM spike_events e " +
            "WHERE e.global_order > ? AND spike_hold() ORDER BY e.global_order LIMIT ?";
    static final         String TAIL              = "SELECT e.global_order, NULL::text[] AS running FROM spike_events e WHERE e.global_order > ? ORDER BY e.global_order LIMIT ?";
    static final         String RUNNING           = "SELECT array_agg(backend_xid::text) FROM pg_stat_activity WHERE backend_xid IS NOT NULL";

    static Stream<Arguments> cases() {
        return images().stream().flatMap(image -> Arrays.stream(Protocol.values()).map(protocol -> Arguments.of(image, protocol)));
    }

    @AfterAll
    static void stopContainers() {
        stopAll();
    }

    record Probe(List<Long> rows, Set<String> running) {
        long highest() {
            return rows.isEmpty() ? -1 : rows.getLast();
        }
    }

    /** A writer that took its xid, inserted one row and holds its transaction open. */
    record OpenWriter(Connection connection, String xid, long globalOrder) implements AutoCloseable {
        static OpenWriter begin(SpikeServer postgres) throws SQLException {
            var connection = connect(postgres);
            connection.setAutoCommit(false);
            var xid = queryString(connection, "SELECT pg_current_xact_id()::xid::text");
            var globalOrder = insertOne(connection, "open-" + UUID.randomUUID());
            return new OpenWriter(connection, xid, globalOrder);
        }

        void commit() throws SQLException {
            connection.commit();
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Deterministic scenarios
    // ------------------------------------------------------------------------------------------------------------------

    /** The basic gate: a held writer below a committed one is not read and is in {@code X}. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void a_running_writer_below_S_is_in_the_running_set(String image, Protocol protocol) throws Exception {
        var postgres = container(image);
        try (var setup = connect(postgres);
             var reader = connect(postgres, protocol);
             var probe = reader.prepareStatement(COMBINED)) {
            var version = serverVersion(setup);
            var cursor  = recreateEventTable(setup);
            warmUp(probe, protocol, cursor);

            try (var a = OpenWriter.begin(postgres)) {
                var b      = insertOne(setup, "B");
                var result = run(probe, cursor);
                var plans  = protocol.serverPrepared ? preparedPlanCounts(reader) : "n/a";
                record(RESULTS, "%s (%s) | %s | basic | A=%d xid %s open, B=%d committed | rows=%s running=%s | prepared plans=%s"
                        .formatted(image, version, protocol, a.globalOrder, a.xid, b, result.rows, result.running, plans));
                assertThat(a.globalOrder).isLessThan(b);
                assertThat(result.rows).containsExactly(b);
                assertThat(result.running).contains(a.xid);
                assertThat(violatesRule(result, a)).isFalse();
                if (protocol == Protocol.PREPARED_GENERIC) {
                    assertThat(plans).doesNotStartWith("generic=0 ");
                }
            }
        }
    }

    /**
     * The ordering itself. The probe is held inside its scan - after its snapshot was taken, before {@code X} is read.
     * Meanwhile A (running at the snapshot) commits and C takes an xid. If the snapshot precedes {@code X}, A's row is
     * invisible AND A is absent from {@code X} (it finished before {@code X} was read), and C is in {@code X}.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void the_running_set_is_read_after_the_snapshot(String image, Protocol protocol) throws Exception {
        var postgres = container(image);
        try (var setup = connect(postgres);
             var observer = connect(postgres);
             var reader = connect(postgres, protocol);
             var probe = reader.prepareStatement(COMBINED_HELD)) {
            var version = serverVersion(setup);
            var cursor  = recreateEventTable(setup);
            SpikeHold.install(setup);
            warmUp(probe, protocol, cursor);

            try (var a = OpenWriter.begin(postgres);
                 var c = connect(postgres)) {
                var b = insertOne(setup, "B");
                Probe result;
                try (var hold = SpikeHold.arm(postgres)) {
                    var finalCursor = cursor;
                    var held = CompletableFuture.supplyAsync(() -> {
                        try {
                            return run(probe, finalCursor);
                        } catch (SQLException e) {
                            throw new CompletionException(e);
                        }
                    });
                    Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> SpikeHold.held(observer) == 1);
                    a.commit();
                    c.setAutoCommit(false);
                    var cXid = queryString(c, "SELECT pg_current_xact_id()::xid::text");
                    hold.release();
                    result = held.get(30, TimeUnit.SECONDS);
                    var plans = protocol.serverPrepared ? preparedPlanCounts(reader) : "n/a";
                    record(RESULTS, "%s (%s) | %s | held-after-snapshot | A=%d xid %s committed during hold, B=%d committed before, C xid %s began during hold | rows=%s running=%s | prepared plans=%s"
                            .formatted(image, version, protocol, a.globalOrder, a.xid, b, cXid, result.rows, result.running, plans));
                    assertThat(result.rows).as("A committed after the snapshot, so its row is invisible").containsExactly(b);
                    assertThat(result.running).as("A finished before X was read").doesNotContain(a.xid);
                    assertThat(result.running).as("C began before X was read").contains(cXid);
                    c.rollback();
                }
            }
        }
    }

    /** The control the gate asks for: two statements, X read BEFORE the tail. Must be seen to break the rule. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void control_two_statements_with_the_running_set_read_first_breaks_the_rule(String image, Protocol protocol) throws Exception {
        var postgres = container(image);
        try (var setup = connect(postgres);
             var reader = connect(postgres, protocol);
             var running = reader.prepareStatement(RUNNING);
             var tail = reader.prepareStatement(TAIL)) {
            var version = serverVersion(setup);
            var cursor  = recreateEventTable(setup);
            warmUp(tail, protocol, cursor);

            var x = readRunning(running);
            try (var a = OpenWriter.begin(postgres)) {
                var b      = insertOne(setup, "B");
                var rows   = run(tail, cursor).rows;
                var result = new Probe(rows, x);
                var broken = violatesRule(result, a);
                record(RESULTS, "%s (%s) | %s | control X-then-S | A=%d xid %s open, B=%d | rows=%s running=%s | rule broken=%s"
                        .formatted(image, version, protocol, a.globalOrder, a.xid, b, rows, x, broken));
                assertThat(broken).as("the reversed two-statement probe misses A").isTrue();
            }
        }
    }

    /** The correct two-statement form (S then X), the documented fallback, for completeness. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void two_statements_with_the_tail_read_first_keep_the_rule(String image, Protocol protocol) throws Exception {
        var postgres = container(image);
        try (var setup = connect(postgres);
             var reader = connect(postgres, protocol);
             var running = reader.prepareStatement(RUNNING);
             var tail = reader.prepareStatement(TAIL)) {
            var cursor = recreateEventTable(setup);
            warmUp(tail, protocol, cursor);
            try (var a = OpenWriter.begin(postgres)) {
                insertOne(setup, "B");
                var rows   = run(tail, cursor).rows;
                var result = new Probe(rows, readRunning(running));
                assertThat(violatesRule(result, a)).isFalse();
            }
        }
    }

    /**
     * Breaking it on purpose: inside an explicit transaction, {@code pg_stat_activity} is a snapshot taken at its first
     * access in that transaction and kept until the transaction ends. A probe after an earlier access in the same
     * transaction reads a fresh MVCC snapshot (READ COMMITTED) but a stale X.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void inside_a_transaction_that_already_read_pg_stat_activity_the_combined_probe_is_unsafe(String image, Protocol protocol) throws Exception {
        var postgres = container(image);
        try (var setup = connect(postgres);
             var reader = connect(postgres, protocol);
             var probe = reader.prepareStatement(COMBINED)) {
            var version = serverVersion(setup);
            var cursor  = recreateEventTable(setup);
            warmUp(probe, protocol, cursor);

            reader.setAutoCommit(false);
            queryLong(reader, "SELECT count(*) FROM pg_stat_activity");
            try (var a = OpenWriter.begin(postgres)) {
                var b      = insertOne(setup, "B");
                var result = run(probe, cursor);
                var broken = violatesRule(result, a);
                record(RESULTS, "%s (%s) | %s | explicit txn, pg_stat_activity read earlier | A=%d xid %s open, B=%d | rows=%s running=%s | rule broken=%s"
                        .formatted(image, version, protocol, a.globalOrder, a.xid, b, result.rows, result.running, broken));
                assertThat(result.rows).as("READ COMMITTED: the probe's own snapshot sees B").containsExactly(b);
                assertThat(broken).as("the cached backend status misses A").isTrue();

                // pg_stat_clear_snapshot() discards the cached status; the next probe in the same transaction is safe.
                queryString(reader, "SELECT pg_stat_clear_snapshot()::text");
                var cleared = run(probe, cursor);
                record(RESULTS, "%s (%s) | %s | same txn after pg_stat_clear_snapshot() | rows=%s running=%s | rule broken=%s"
                        .formatted(image, version, protocol, cleared.rows, cleared.running, violatesRule(cleared, a)));
                assertThat(violatesRule(cleared, a)).isFalse();
            }
            reader.rollback();
        }
    }

    /** An explicit transaction that did not touch pg_stat_activity before: its first access is inside the probe. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void inside_a_transaction_whose_first_access_is_the_probe_the_combined_probe_is_safe(String image, Protocol protocol) throws Exception {
        var postgres = container(image);
        try (var setup = connect(postgres);
             var reader = connect(postgres, protocol);
             var probe = reader.prepareStatement(COMBINED)) {
            var cursor = recreateEventTable(setup);
            warmUp(probe, protocol, cursor);
            reader.setAutoCommit(false);
            queryLong(reader, "SELECT count(*) FROM spike_events");
            try (var a = OpenWriter.begin(postgres)) {
                insertOne(setup, "B");
                var result = run(probe, cursor);
                assertThat(violatesRule(result, a)).isFalse();
            }
            reader.rollback();
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Race-window hunt
    // ------------------------------------------------------------------------------------------------------------------

    enum ProbeForm {COMBINED_ONE_STATEMENT, CONTROL_X_THEN_S}

    static Stream<Arguments> stressCases() {
        return images().stream().flatMap(image -> Arrays.stream(Protocol.values())
                                                        .flatMap(protocol -> Arrays.stream(ProbeForm.values()).map(form -> Arguments.of(image, protocol, form))));
    }

    /**
     * Concurrent writers (xid first, then insert, then commit after a random pause) and one reader running the
     * watermark rule for real: probe, record the holes at or below S, wait until no member of X runs, read the holes
     * once, and call whatever is still missing permanent. Writers never roll back, so every value is eventually
     * committed: a hole declared permanent that later turns up is a violation. The control form shows whether the
     * harness can see one at all - and in the phase-0 runs it could not (zero violations for the control too, also with one
     * writer in ten pausing 20-60 ms): the X-to-S window is one round trip wide, so this hunt is not evidence on its own.
     * The deterministic tests above are.
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("stressCases")
    void race_window_hunt(String image, Protocol protocol, ProbeForm form) throws Exception {
        var postgres = container(image);
        var seconds  = Long.parseLong(System.getProperty("spike.stress.seconds", "4"));
        var writers  = Integer.parseInt(System.getProperty("spike.stress.writers", "6"));
        try (var setup = connect(postgres);
             var reader = connect(postgres, protocol);
             var observer = connect(postgres);
             var combined = reader.prepareStatement(COMBINED);
             var tail = reader.prepareStatement(TAIL);
             var running = reader.prepareStatement(RUNNING)) {
            var version = serverVersion(setup);
            var cursor  = recreateEventTable(setup);

            var stop        = new AtomicBoolean();
            var writerError = new AtomicReference<Throwable>();
            var committed   = new AtomicLong();
            var pool        = Executors.newFixedThreadPool(writers);
            for (var w = 0; w < writers; w++) {
                pool.submit(() -> {
                    try (var connection = connect(postgres)) {
                        connection.setAutoCommit(false);
                        var random = ThreadLocalRandom.current();
                        while (!stop.get()) {
                            queryString(connection, "SELECT pg_current_xact_id()::text");
                            insertOne(connection, "w-" + UUID.randomUUID());
                            LockSupport.parkNanos(random.nextLong(0, 2_000_000));
                            connection.commit();
                            committed.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        writerError.compareAndSet(null, t);
                    }
                    return null;
                });
            }

            var probes             = 0;
            var probesWithHoles    = 0;
            var holesSeen          = 0L;
            var declaredPermanent  = new TreeSet<Long>();
            var deadline           = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
            while (System.nanoTime() < deadline) {
                Probe result;
                if (form == ProbeForm.COMBINED_ONE_STATEMENT) {
                    result = run(combined, cursor, 100_000);
                } else {
                    var x = readRunning(running);
                    result = new Probe(run(tail, cursor, 100_000).rows, x);
                }
                probes++;
                if (result.rows.isEmpty()) {
                    continue;
                }
                var s     = result.highest();
                var read  = new HashSet<>(result.rows);
                var holes = new ArrayList<Long>();
                for (var v = cursor + 1; v < s; v++) {
                    if (!read.contains(v)) {
                        holes.add(v);
                    }
                }
                if (!holes.isEmpty()) {
                    probesWithHoles++;
                    holesSeen += holes.size();
                    awaitRetired(observer, result.running);
                    var late = readHoles(observer, holes);
                    for (var hole : holes) {
                        if (!late.contains(hole)) {
                            declaredPermanent.add(hole);
                        }
                    }
                }
                cursor = s;
            }
            stop.set(true);
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(writerError.get()).as("writer failure").isNull();

            // Every value was committed in the end; any declared permanent that exists now arrived after it was given up.
            var violations = declaredPermanent.isEmpty() ? Set.<Long>of() : readHoles(observer, new ArrayList<>(declaredPermanent));
            record(RESULTS, "%s (%s) | %s | race hunt %s | %d s, %d writers, %d commits | probes=%d with holes=%d holes=%d | declared permanent=%d, of which later committed (violations)=%d"
                    .formatted(image, version, protocol, form, seconds, writers, committed.get(), probes, probesWithHoles, holesSeen,
                               declaredPermanent.size(), violations.size()));
            if (form == ProbeForm.COMBINED_ONE_STATEMENT) {
                assertThat(violations).as("the combined probe never gives up a value that later commits").isEmpty();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    /**
     * The §4.2 rule broken for writer A: A holds a value at or below S that was not read, A was still running when X
     * was read (here: it still runs, uncommitted), and A's xid is not in X.
     */
    private static boolean violatesRule(Probe probe, OpenWriter a) {
        return a.globalOrder <= probe.highest() && !probe.rows.contains(a.globalOrder) && !probe.running.contains(a.xid);
    }

    /** Six executions, so a server-side prepared statement is past its five custom plans. */
    private static void warmUp(PreparedStatement statement, Protocol protocol, long cursor) throws SQLException {
        var runs = protocol.serverPrepared ? 6 : 1;
        for (var i = 0; i < runs; i++) {
            run(statement, cursor);
        }
    }

    private static Probe run(PreparedStatement statement, long cursor) throws SQLException {
        return run(statement, cursor, 100);
    }

    private static Probe run(PreparedStatement statement, long cursor, int limit) throws SQLException {
        statement.setLong(1, cursor);
        statement.setInt(2, limit);
        var rows    = new ArrayList<Long>();
        var running = new HashSet<String>();
        try (var rs = statement.executeQuery()) {
            while (rs.next()) {
                rows.add(rs.getLong(1));
                var array = rs.getArray(2);
                if (array != null) {
                    running.addAll(Arrays.asList((String[]) array.getArray()));
                }
            }
        }
        return new Probe(rows, running);
    }

    private static Set<String> readRunning(PreparedStatement running) throws SQLException {
        try (var rs = running.executeQuery()) {
            rs.next();
            var array = rs.getArray(1);
            return array == null ? new HashSet<>() : new HashSet<>(Arrays.asList((String[]) array.getArray()));
        }
    }

    /** Waits until no member of X is still running (set disjointness, as §4.2 says). */
    private static void awaitRetired(Connection observer, Set<String> x) {
        if (x.isEmpty()) {
            return;
        }
        Awaitility.await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(20)).until(() -> {
            try (var statement = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE backend_xid::text = ANY(?)")) {
                statement.setArray(1, observer.createArrayOf("text", x.toArray(String[]::new)));
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1) == 0;
                }
            }
        });
    }

    private static Set<Long> readHoles(Connection connection, List<Long> holes) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT global_order FROM spike_events WHERE global_order = ANY(?)")) {
            statement.setArray(1, connection.createArrayOf("bigint", holes.toArray(Long[]::new)));
            var found = new HashSet<Long>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getLong(1));
                }
            }
            return found;
        }
    }

    private static String preparedPlanCounts(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT generic_plans, custom_plans FROM pg_prepared_statements WHERE statement LIKE '%pg_stat_activity%spike_events%' AND statement NOT LIKE '%pg_prepared_statements%'")) {
            var counts = new StringJoiner("; ");
            while (rs.next()) {
                counts.add("generic=" + rs.getLong(1) + " custom=" + rs.getLong(2));
            }
            return counts.length() == 0 ? "none" : counts.toString();
        }
    }
}
