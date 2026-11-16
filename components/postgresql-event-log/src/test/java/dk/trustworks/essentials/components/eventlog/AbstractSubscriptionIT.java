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
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Shared harness of the subscription ITs: an event log with a fast backstop that saves progress after every handled
 * event, writers held open in their own transactions, a recording handler, an observer that counts reads, and a view
 * that holds the tail reader inside its scan (the hold-inside-scan proofs of P0.2).
 */
abstract class AbstractSubscriptionIT extends AbstractEventLogIT {
    static final String   TYPE     = "Orders";
    static final int      SCAN_KEY = 731_901;
    static final String   HELD     = "el_orders_events_held";
    static final Duration WAIT     = Duration.ofSeconds(30);

    static final EventLogSettings FAST = EventLogSettings.defaults()
                                                         .withBackstopInterval(Duration.ofMillis(25))
                                                         .withProbeBackoff(Duration.ofMillis(25), Duration.ofMillis(100))
                                                         .withProgressSaveInterval(Duration.ZERO);

    protected EventLog            eventLog;
    protected AggregateTypeTables tables;
    protected final ReadCounter   reads         = new ReadCounter();
    private final List<Connection>        open          = new CopyOnWriteArrayList<>();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    /** Counts reads and remembers the last one's outstanding state, from the reader's thread */
    static final class ReadCounter implements EventLogObserver {
        final AtomicLong reads = new AtomicLong();
        final AtomicLong holeReads = new AtomicLong();
        final AtomicLong detaches  = new AtomicLong();
        final AtomicLong attaches  = new AtomicLong();
        final AtomicLong rowsRead  = new AtomicLong();
        /** Catch-up, bridge and deferred-bridge statements of every subscription */
        final AtomicLong catchUpReads = new AtomicLong();
        final List<String> detachReasons = new CopyOnWriteArrayList<>();
        final List<Long>   saves         = new CopyOnWriteArrayList<>();
        /** Attaches under an unknown watermark (phase 5 §6.3) */
        final AtomicLong   unknownAttaches = new AtomicLong();
        /** The subscribers that handed gaps off, once per hand-off (phase 5 §6.3) */
        final List<String> handedOffBy   = new CopyOnWriteArrayList<>();
        volatile int lastCandidates;
        volatile int lastHoles;

        @Override
        public void onRead(String aggregateType, boolean combined, long nanos, int rows, int outstandingHoles, int outstandingCandidates, Duration oldestCandidateAge) {
            lastCandidates = outstandingCandidates;
            lastHoles = outstandingHoles;
            rowsRead.addAndGet(rows);
            reads.incrementAndGet();
        }

        @Override
        public void onAttach(String subscriber, String aggregateType, long cursor, long watermark) {
            attaches.incrementAndGet();
            if (watermark == Long.MIN_VALUE) {
                unknownAttaches.incrementAndGet();
            }
        }

        @Override
        public void onGapsHandedOff(String subscriber, String aggregateType, int gaps, boolean toReader) {
            handedOffBy.add(subscriber);
        }

        @Override
        public void onProgressSaved(String subscriber, String aggregateType, long resumeFrom) {
            saves.add(resumeFrom);
        }

        @Override
        public void onCatchUpRead(String subscriber, String aggregateType, boolean bridge, long nanos, int rows) {
            catchUpReads.incrementAndGet();
        }

        @Override
        public void onHoleRead(String aggregateType, int resolvedHoles, int lateRows) {
            holeReads.incrementAndGet();
        }

        @Override
        public void onDetach(String subscriber, String aggregateType, String reason, long resumeFrom) {
            detachReasons.add(reason);
            detaches.incrementAndGet();
        }
    }

    /**
     * Records every delivery, in order; can be made to block on one global order, or on every event of one aggregate,
     * until {@link #release} is counted down
     */
    static final class Recorder implements Subscription.Handler {
        final List<StoredEvent> delivered = new CopyOnWriteArrayList<>();
        final List<StoredEvent> handled   = new CopyOnWriteArrayList<>();
        volatile long           blockAt   = -1;
        volatile String         blockAtAggregate;
        final CountDownLatch    blocked   = new CountDownLatch(1);
        final CountDownLatch    release   = new CountDownLatch(1);

        @Override
        public void handle(StoredEvent event) throws Exception {
            delivered.add(event);
            if (event.globalOrder() == blockAt || event.aggregateId().equals(blockAtAggregate)) {
                blocked.countDown();
                release.await();
            }
            handled.add(event);
        }

        boolean deliveredOrder(long globalOrder) {
            return delivered.stream().anyMatch(e -> e.globalOrder() == globalOrder);
        }

        Set<Long> deliveredOrders() {
            var set = new TreeSet<Long>();
            delivered.forEach(e -> set.add(e.globalOrder()));
            return set;
        }
    }

    @BeforeEach
    void startEventLog() throws SQLException {
        eventLog = EventLog.builder().setDataSource(dataSource).setSettings(FAST).setObserver(reads).build();
        eventLog.start();
        tables = eventLog.registerAggregateType(TYPE);
        execute("""
                CREATE OR REPLACE FUNCTION el_test_scan_hold() RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$
                BEGIN
                    PERFORM pg_advisory_lock_shared(%1$d);
                    PERFORM pg_advisory_unlock_shared(%1$d);
                    RETURN true;
                END $$""".formatted(SCAN_KEY));
        execute("CREATE VIEW " + HELD + " AS SELECT * FROM " + tables.eventTable() + " WHERE el_test_scan_hold()");
    }

    @AfterEach
    void stopEverything() {
        subscriptions.forEach(Subscription::crash);
        eventLog.stop();
        for (var connection : open) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    Subscription subscribe(String name, Recorder recorder, TestSeams seams) throws SQLException {
        return subscribe(name, recorder, seams, 1);
    }

    /** Starts {@code spec} on the current event log; crashed after the test */
    Subscription subscribe(Subscription.SubscriptionSpec spec) throws SQLException {
        return track(eventLog.subscribe(spec));
    }

    /** Crashes {@code subscription} after the test */
    Subscription track(Subscription subscription) {
        subscriptions.add(subscription);
        return subscription;
    }

    Subscription subscribe(String name, Subscription.Handler handler, TestSeams seams, long startFrom) throws SQLException {
        var subscription = eventLog.subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key(name, TYPE, "node-1"), startFrom, handler, seams));
        subscriptions.add(subscription);
        return subscription;
    }

    /** Replaces the event log with one on {@code source} with {@code settings}; the schema and tables stay */
    void restartEventLog(javax.sql.DataSource source, EventLogSettings settings) throws SQLException {
        subscriptions.forEach(Subscription::crash);
        subscriptions.clear();
        eventLog.stop();
        eventLog = EventLog.builder().setDataSource(source).setSettings(settings).setObserver(reads).build();
        eventLog.start();
        tables = eventLog.registerAggregateType(TYPE);
    }

    /**
     * Waits until the shared reader has read {@code globalOrder} (as a new row or a late commit into a hole) and the
     * batch holding it has been offered: one more read has finished after it
     */
    void awaitOffered(long globalOrder) {
        await().atMost(WAIT).until(() -> {
            var reader = eventLog.reader(TYPE);
            var state  = reader == null ? null : reader.state();
            if (state == null || state.cursor() < globalOrder || state.holes().contains(globalOrder)) {
                return false;
            }
            return state.ranges().entrySet().stream().noneMatch(range -> range.getKey() <= globalOrder && range.getValue() >= globalOrder);
        });
        awaitReads(1);
    }

    Subscription subscribe(String name, Recorder recorder, WatermarkMutant... mutants) throws SQLException {
        return subscribe(name, recorder, new TestSeams(Set.of(mutants), TestSeams.Hooks.NONE, null));
    }

    /** Terminates the backend of the shared reader loop's connection, if it has one: the next read fails (§2.4) */
    void terminateReaderBackend() throws SQLException {
        var connection = eventLog.readerLoop().connection();
        if (connection != null) {
            var pid = connection.unwrap(org.postgresql.PGConnection.class).getBackendPID();
            execute("SELECT pg_terminate_backend(" + pid + ")");
        }
    }

    /** A writer whose transaction stays open: it holds an xid and its global_orders until committed or rolled back */
    record OpenWriter(Connection connection, AppendResult result) {
        long globalOrder() {
            return result.events().getFirst().globalOrder();
        }

        void commit() throws SQLException {
            connection.commit();
        }

        void rollback() throws SQLException {
            connection.rollback();
        }
    }

    OpenWriter openAppend(String aggregateId, int events) throws SQLException {
        return openAppend(AppendRequest.withoutExpectedOrder(TYPE, aggregateId, events("E", events)));
    }

    OpenWriter openAppend(AppendRequest request) throws SQLException {
        var connection = connection();
        connection.setAutoCommit(false);
        return new OpenWriter(connection, eventLog.append(connection, request));
    }

    Connection connection() throws SQLException {
        var connection = dataSource.getConnection();
        open.add(connection);
        return connection;
    }

    /** Appends {@code events} events to {@code aggregateId} and commits; returns the first global order */
    long commitAppend(String aggregateId, int events) throws SQLException {
        return appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(TYPE, aggregateId, events("E", events))).getFirst().events().getFirst().globalOrder();
    }

    void awaitDelivered(Recorder recorder, long... globalOrders) {
        await().atMost(WAIT).untilAsserted(() -> {
            for (var g : globalOrders) {
                assertThat(recorder.deliveredOrder(g)).as("global_order %d delivered", g).isTrue();
            }
        });
    }

    /** Waits for {@code count} more reads (each a full probe cycle), then evaluates {@code condition} */
    void awaitReads(int count) {
        var target = reads.reads.get() + count;
        await().atMost(WAIT).until(() -> reads.reads.get() >= target);
    }

    void awaitTrue(BooleanSupplier condition) {
        await().atMost(WAIT).until(condition::getAsBoolean);
    }

    long savedResumeFrom(String subscriber) throws SQLException {
        return queryLong("SELECT resume_from FROM " + EventLogSchema.PROGRESS_TABLE + " WHERE subscriber = '" + subscriber + "'");
    }

    /** Arms the scan hold: the next read that scans a row of {@link #HELD} blocks until {@link #releaseScan} */
    Connection armScanHold() throws SQLException {
        var holder = connection();
        try (var statement = holder.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + SCAN_KEY + ")");
        }
        return holder;
    }

    void awaitScanBlocked() {
        await().atMost(WAIT).until(() -> queryLong("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND objid = " + SCAN_KEY) >= 1);
    }

    static void releaseScan(Connection holder) throws SQLException {
        try (var statement = holder.createStatement()) {
            statement.execute("SELECT pg_advisory_unlock(" + SCAN_KEY + ")");
        }
    }

    static String currentXid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery("SELECT backend_xid::text FROM pg_stat_activity WHERE pid = pg_backend_pid()")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    /**
     * Waits until {@code globalOrder} is delivered, or ten more reads went by without it
     */
    boolean deliveredWithin(Recorder recorder, long globalOrder) {
        var target = reads.reads.get() + 10;
        await().atMost(WAIT).until(() -> recorder.deliveredOrder(globalOrder) || reads.reads.get() >= target);
        return recorder.deliveredOrder(globalOrder);
    }

    /** I2's check: within one incarnation, each aggregate's events are first delivered in event order */
    static List<String> perAggregateOrderViolations(List<StoredEvent> delivered) {
        var firstSeen  = new LinkedHashMap<String, Long>();
        var highest    = new HashMap<String, Long>();
        var violations = new ArrayList<String>();
        for (var event : delivered) {
            var key = event.aggregateId() + "#" + event.eventOrder();
            if (firstSeen.putIfAbsent(key, event.globalOrder()) != null) {
                continue;
            }
            var previous = highest.get(event.aggregateId());
            if (previous != null && event.eventOrder() < previous) {
                violations.add(event.aggregateId() + "#" + event.eventOrder() + " first delivered after #" + previous);
            }
            highest.merge(event.aggregateId(), event.eventOrder(), Math::max);
        }
        return violations;
    }
}
