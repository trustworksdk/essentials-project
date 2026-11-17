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

import org.junit.jupiter.api.AfterEach;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.awaitility.Awaitility.await;

/**
 * Shared harness of the phase-7 lease ITs (design {@code docs/plans/event-store-engine-phase7.md} §9): "another JVM" is
 * a second {@link EventLog} on its own {@code DataSource}; the test lease timings of §6 (2 s lease, 500 ms renewal, 1 s
 * local deadline, 1 s statement timeout); {@link Gate}s that hold a keeper or delivery thread in a hook; and the
 * test-only progress log, a trigger on {@code el_progress} that records every row version a transaction writes, so an IT
 * asserts on what <b>committed</b> (a rolled-back write leaves no log row), in write order.
 */
abstract class AbstractLeaseIT extends AbstractEventLogIT {
    static final String   TYPE = "Orders";
    static final Duration WAIT = Duration.ofSeconds(30);
    /** The progress log's table (§9): never created by the engine */
    static final String   LOG  = "el_test_progress_log";

    /** §6's test timings */
    static final EventLogSettings LEASE = EventLogSettings.defaults()
                                                          .withBackstopInterval(Duration.ofMillis(25))
                                                          .withProbeBackoff(Duration.ofMillis(25), Duration.ofMillis(100))
                                                          .withProgressSaveInterval(Duration.ZERO)
                                                          .withLease(Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(1))
                                                          .withLeaseStatementTimeout(Duration.ofSeconds(1));

    private final List<EventLog>     eventLogs     = new CopyOnWriteArrayList<>();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final List<Gate>         gates         = new CopyOnWriteArrayList<>();

    /**
     * Holds the thread that {@link #pass()}es while armed, until {@link #open()}; counts the threads that reached it
     */
    final class Gate {
        private volatile boolean     armed;
        private volatile CountDownLatch reached = new CountDownLatch(1);
        private volatile CountDownLatch opened  = new CountDownLatch(1);

        Gate(boolean armed) {
            this.armed = armed;
            gates.add(this);
        }

        Gate arm() {
            reached = new CountDownLatch(1);
            opened = new CountDownLatch(1);
            armed = true;
            return this;
        }

        boolean armed() {
            return armed;
        }

        void pass() throws InterruptedException {
            if (!armed) {
                return;
            }
            var opened = this.opened;
            reached.countDown();
            opened.await();
        }

        void awaitReached() throws InterruptedException {
            if (!reached.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for a thread to reach the gate");
            }
        }

        boolean reached() {
            return reached.getCount() == 0;
        }

        void open() {
            armed = false;
            opened.countDown();
        }
    }

    @AfterEach
    void stopLeaseEventLogs() {
        gates.forEach(Gate::open);
        subscriptions.forEach(Subscription::crash);
        eventLogs.forEach(EventLog::stop);
        // A connection a failed test left open (a held lock, an open page) would block the next test's schema drop
        try {
            execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND datname = current_database()");
        } catch (SQLException ignored) {
        }
    }

    EventLog eventLog(String nodeName) throws SQLException {
        return eventLog(nodeName, LEASE);
    }

    EventLog eventLog(String nodeName, EventLogSettings settings) throws SQLException {
        var eventLog = EventLog.builder().setDataSource(dataSource("")).setSettings(settings.withNodeName(nodeName)).build();
        eventLog.start();
        eventLog.registerAggregateType(TYPE);
        eventLogs.add(eventLog);
        return eventLog;
    }

    static TestSeams seams(TestSeams.Hooks hooks, WatermarkMutant... mutants) {
        return new TestSeams(Set.of(mutants), hooks, null);
    }

    static TestSeams seams(TestSeams.Hooks hooks, Collection<WatermarkMutant> mutants) {
        return new TestSeams(Set.copyOf(mutants), hooks, null);
    }

    static ProgressStore.Key key(String subscriber) {
        return new ProgressStore.Key(subscriber, TYPE, "");
    }

    /** An exclusive {@code FRESH} periodic subscription on {@code eventLog}; crashed after the test */
    Subscription periodic(EventLog eventLog, String subscriber, PeriodicHandler handler, TestSeams seams) throws SQLException {
        return track(eventLog.subscribe(new Subscription.SubscriptionSpec(key(subscriber), 1, ConsistencyMode.FRESH, Subscription.ProgressModel.PERIODIC,
                                                                           handler, null, seams, EventTypeFilter.all(), true)));
    }

    /** An exclusive in-transaction subscription on {@code eventLog}; crashed after the test */
    Subscription inTransaction(EventLog eventLog, String subscriber, ConsistencyMode mode, PageHandler handler, TestSeams seams)
            throws SQLException {
        return track(eventLog.subscribe(new Subscription.SubscriptionSpec(key(subscriber), 1, mode, Subscription.ProgressModel.IN_HANDLER_TRANSACTION, null, handler,
                                                                           seams, EventTypeFilter.all(), true)));
    }

    Subscription track(Subscription subscription) {
        subscriptions.add(subscription);
        return subscription;
    }

    /** Appends {@code count} events to {@code aggregateId}, commits, and wakes the event log's reader; returns their global orders */
    List<Long> append(EventLog eventLog, String aggregateId, int count) throws SQLException {
        var result = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(TYPE, aggregateId, events("OrderPlaced", count)));
        eventLog.afterCommit(List.of(TYPE));
        return result.getFirst().events().stream().map(AppendedEvent::globalOrder).toList();
    }

    /** A view table with no key: every row an in-transaction handler commits shows, duplicates included */
    void createView(String table) throws SQLException {
        execute("CREATE TABLE " + table + " (global_order bigint NOT NULL, writer text NOT NULL)");
    }

    /** A page handler that writes each event of the page to {@code table}, tagged {@code writer} */
    static PageHandler viewWriter(String table, String writer) {
        return PageHandler.perEvent((event, tx) -> {
            var connection = tx.connection();
            try (var statement = connection.prepareStatement("INSERT INTO " + table + " (global_order, writer) VALUES (?, ?)")) {
                statement.setLong(1, event.globalOrder());
                statement.setString(2, writer);
                statement.executeUpdate();
            }
        });
    }

    /** Rows of {@code table} whose global order appears more than once, plus orders in {@code 1..upTo} that are missing */
    long viewDefects(String table, long upTo) throws SQLException {
        return queryLong("SELECT (SELECT count(*) - count(DISTINCT global_order) FROM " + table + ") + " +
                                 "(SELECT count(*) FROM generate_series(1, " + upTo + ") g WHERE NOT EXISTS (SELECT 1 FROM " + table + " v WHERE v.global_order = g))");
    }

    /**
     * Installs the progress log (§9): every row version written to {@code el_progress} is recorded with its
     * transaction id and the clock time, in write order ({@code id})
     */
    void installProgressLog() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS " + LOG + " (id bigserial PRIMARY KEY, subscriber text, resume_from bigint, reset_epoch bigint, lease_fence bigint, " +
                        "lease_owner text, lease_until timestamptz, xid bigint, at timestamptz)");
        execute("""
                CREATE OR REPLACE FUNCTION el_test_progress_log_row() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO %s (subscriber, resume_from, reset_epoch, lease_fence, lease_owner, lease_until, xid, at)
                    VALUES (NEW.subscriber, NEW.resume_from, NEW.reset_epoch, NEW.lease_fence, NEW.lease_owner, NEW.lease_until, txid_current(), clock_timestamp());
                    RETURN NEW;
                END $$""".formatted(LOG));
        execute("CREATE OR REPLACE TRIGGER el_test_progress_log AFTER INSERT OR UPDATE ON " + EventLogSchema.PROGRESS_TABLE +
                        " FOR EACH ROW EXECUTE FUNCTION el_test_progress_log_row()");
    }

    /** One committed row version of the progress log */
    record LoggedVersion(long id, long resumeFrom, long resetEpoch, Long fence, String owner) {
    }

    List<LoggedVersion> progressLog(String subscriber) throws SQLException {
        var versions = new ArrayList<LoggedVersion>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT id, resume_from, reset_epoch, lease_fence, lease_owner FROM " + LOG + " WHERE subscriber = ? ORDER BY id")) {
            statement.setString(1, subscriber);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    var fence = resultSet.getLong(4);
                    versions.add(new LoggedVersion(resultSet.getLong(1), resultSet.getLong(2), resultSet.getLong(3), resultSet.wasNull() ? null : fence,
                                                   resultSet.getString(5)));
                }
            }
        }
        return versions;
    }

    /**
     * Writes that changed the resume point under {@code fence} and committed after the first version written under
     * {@code laterFence} (I5: none may)
     */
    List<LoggedVersion> resumeWritesAfter(String subscriber, long fence, long laterFence) throws SQLException {
        var log   = progressLog(subscriber);
        var first = log.stream().filter(v -> Objects.equals(v.fence(), laterFence)).mapToLong(LoggedVersion::id).min().orElse(Long.MAX_VALUE);
        var late  = new ArrayList<LoggedVersion>();
        LoggedVersion previous = null;
        for (var version : log) {
            if (version.id() > first && Objects.equals(version.fence(), fence) && previous != null && previous.resumeFrom() != version.resumeFrom()) {
                late.add(version);
            }
            previous = version;
        }
        return late;
    }

    /** The lease and progress columns of an exclusive row */
    record Row(Long fence, String owner, boolean leased, long resumeFrom, long resetEpoch) {
    }

    Row row(String subscriber) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT lease_fence, lease_owner, lease_until IS NOT NULL, resume_from, reset_epoch FROM " +
                                                                 EventLogSchema.PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ''")) {
            statement.setString(1, subscriber);
            statement.setString(2, TYPE);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                var fence = resultSet.getLong(1);
                var noFence = resultSet.wasNull();
                return new Row(noFence ? null : fence, resultSet.getString(2), resultSet.getBoolean(3), resultSet.getLong(4), resultSet.getLong(5));
            }
        }
    }

    /** Makes the lease of {@code subscriber} lapse by server time; its owner's keeper must be held, or it renews it again */
    void lapse(String subscriber) throws SQLException {
        execute("UPDATE " + EventLogSchema.PROGRESS_TABLE + " SET lease_until = now() - interval '1 second' WHERE subscriber = '" + subscriber + "' AND instance_name = ''");
    }

    static void awaitTrue(BooleanSupplier condition) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(10)).until(condition::getAsBoolean);
    }

    static void awaitOwned(Subscription subscription) {
        awaitTrue(() -> subscription.lease().state() == Lease.State.OWNED && subscription.phase() != Subscription.Phase.STANDBY &&
                subscription.phase() != Subscription.Phase.STARTING);
    }

    /** Waits until the subscription lost its lease and went back to standby, out of the reader */
    static void awaitStandbyAfterLoss(Subscription subscription) {
        awaitTrue(() -> subscription.lease().state() == Lease.State.STANDBY && subscription.phase() == Subscription.Phase.STANDBY);
    }
}
