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

import org.junit.jupiter.api.BeforeEach;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;

/**
 * Shared harness of the phase-4 page ITs (design phase 4 §8): a view table {@code (aggregate_id, count,
 * last_global_order, digest)} where each event sets {@code digest = md5(digest || global_order)}, a one-row high-water
 * mark that records a violation (in the page's own transaction) whenever an event is applied at or below the highest
 * order applied anywhere, a reference fold over the event table to compare with, and crash helpers.
 */
abstract class AbstractPageIT extends AbstractSubscriptionIT {
    static final String VIEW       = "el_test_view";
    static final String HIGH       = "el_test_high";
    static final String VIOLATIONS = "el_test_violations";

    /** Every handler call, rolled-back pages included */
    final List<Long> handlerCalls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void createView() throws SQLException {
        execute("CREATE TABLE " + VIEW + " (aggregate_id text PRIMARY KEY, count int NOT NULL, last_global_order bigint NOT NULL, digest text NOT NULL)");
        execute("CREATE TABLE " + HIGH + " (id int PRIMARY KEY, g bigint NOT NULL)");
        execute("INSERT INTO " + HIGH + " VALUES (1, 0)");
        execute("CREATE TABLE " + VIOLATIONS + " (g bigint NOT NULL, subscriber text)");
    }

    /** The view projection, row by row on the page's connection */
    PageHandler view() {
        return PageHandler.perEvent((event, tx) -> apply(event, tx.connection()));
    }

    void apply(StoredEvent event, Connection connection) throws SQLException {
        handlerCalls.add(event.globalOrder());
        try (var statement = connection.prepareStatement("UPDATE " + HIGH + " SET g = ? WHERE id = 1 AND g < ?")) {
            statement.setLong(1, event.globalOrder());
            statement.setLong(2, event.globalOrder());
            if (statement.executeUpdate() == 0) {
                try (var violation = connection.prepareStatement("INSERT INTO " + VIOLATIONS + " VALUES (?, ?)")) {
                    violation.setLong(1, event.globalOrder());
                    violation.setString(2, Thread.currentThread().getName());
                    violation.executeUpdate();
                }
            }
        }
        try (var statement = connection.prepareStatement("INSERT INTO " + VIEW + " AS v VALUES (?, 1, ?, md5(?::text)) ON CONFLICT (aggregate_id) DO UPDATE " +
                                                                 "SET count = v.count + 1, last_global_order = EXCLUDED.last_global_order, digest = md5(v.digest || ?::text)")) {
            statement.setString(1, event.aggregateId());
            statement.setLong(2, event.globalOrder());
            statement.setLong(3, event.globalOrder());
            statement.setLong(4, event.globalOrder());
            statement.executeUpdate();
        }
    }

    Subscription.SubscriptionSpec contiguousSpec(String name, TestSeams seams) {
        return Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key(name, TYPE, "node-1"), 1, ConsistencyMode.CONTIGUOUS, view(), seams);
    }

    Subscription contiguous(String name, TestSeams seams) throws SQLException {
        return subscribe(contiguousSpec(name, seams));
    }

    Subscription contiguous(String name, WatermarkMutant... mutants) throws SQLException {
        return contiguous(name, new TestSeams(Set.of(mutants), TestSeams.Hooks.NONE, null));
    }

    long maxGlobalOrder() throws SQLException {
        return queryLong("SELECT coalesce(max(global_order), 0) FROM " + tables.eventTable());
    }

    /** Waits until {@code subscriber}'s stored resume point passes every committed event */
    void awaitCaughtUp(String subscriber) throws SQLException {
        var target = maxGlobalOrder() + 1;
        await().atMost(WAIT).until(() -> savedResumeFrom(subscriber) >= target);
    }

    /**
     * @return how the view differs from applying every committed event once, in {@code global_order} order, plus every
     * recorded violation of strictly increasing application; empty when exactly once and in order
     */
    List<String> foldDifferences() throws Exception {
        return foldDifferences(Set.of(), true);
    }

    /**
     * As {@link #foldDifferences()}, for a reference fold that leaves out {@code skipped} (parked events)
     *
     * @param checkIncreasing whether a recorded violation of strictly increasing application counts (a retried parked
     *                        aggregate is applied below the highest order on purpose)
     */
    List<String> foldDifferences(Set<Long> skipped, boolean checkIncreasing) throws Exception {
        var expected = new TreeMap<String, String>();
        var counts   = new HashMap<String, Integer>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT aggregate_id, global_order FROM " + tables.eventTable() + " ORDER BY global_order")) {
            while (rows.next()) {
                var id = rows.getString(1);
                var g  = rows.getLong(2);
                if (skipped.contains(g)) {
                    continue;
                }
                expected.put(id, md5(expected.getOrDefault(id, "") + g));
                counts.merge(id, 1, Integer::sum);
            }
        }
        var actual = new TreeMap<String, String>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT aggregate_id, count, digest FROM " + VIEW)) {
            while (rows.next()) {
                actual.put(rows.getString(1), rows.getInt(2) + "/" + rows.getString(3));
            }
        }
        var differences = new ArrayList<String>();
        var ids         = new TreeSet<>(expected.keySet());
        ids.addAll(actual.keySet());
        for (var id : ids) {
            var want = expected.containsKey(id) ? counts.get(id) + "/" + expected.get(id) : null;
            if (!Objects.equals(want, actual.get(id))) {
                differences.add(id + ": expected " + want + ", view " + actual.get(id));
            }
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT g FROM " + VIOLATIONS + " ORDER BY g")) {
            while (checkIncreasing && rows.next()) {
                differences.add("global_order " + rows.getLong(1) + " applied at or below the highest order applied before it");
            }
        }
        return differences;
    }

    static String md5(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * After a {@link TestSeams.Crash}: waits for the delivery thread to end, then terminates the open page connection's
     * backend, so its transaction dies as with a lost JVM
     */
    void cleanUpCrash(Subscription subscription) throws SQLException {
        awaitTrue(() -> !subscription.isRunning());
        var connection = subscription.openPageConnection();
        if (connection != null) {
            var pid = connection.unwrap(org.postgresql.PGConnection.class).getBackendPID();
            execute("SELECT pg_terminate_backend(" + pid + ")");
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    /** The crash points of §8.1: every delivery-thread hook of the page transaction, the release, and both reads */
    enum CrashPoint {
        CATCH_UP_READ,
        DEFERRED_BRIDGE_READ,
        ON_RELEASE,
        BEFORE_PAGE_HANDLER,
        AFTER_HANDLER_BEFORE_PROGRESS,
        AFTER_PROGRESS_BEFORE_COMMIT,
        AFTER_COMMIT
    }

    /**
     * Hooks that throw {@link TestSeams.Crash} the first time {@code point} is reached with rows (once {@link #armed});
     * {@link #crashed} tells whether it fired
     */
    static class CrashingHooks implements TestSeams.Hooks {
        final CrashPoint    point;
        final AtomicBoolean armed   = new AtomicBoolean(true);
        final AtomicBoolean crashed = new AtomicBoolean();

        CrashingHooks(CrashPoint point) {
            this.point = point;
        }

        void maybeCrash(CrashPoint at) {
            if (at == point && armed.compareAndSet(true, false)) {
                crashed.set(true);
                throw new TestSeams.Crash(at.name());
            }
        }

        @Override
        public void beforeDeliveryRead(String subscriber, boolean deferred) {
            maybeCrash(deferred ? CrashPoint.DEFERRED_BRIDGE_READ : CrashPoint.CATCH_UP_READ);
        }

        @Override
        public void onRelease(String subscriber, List<StoredEvent> rows, long receivedWatermark) {
            maybeCrash(CrashPoint.ON_RELEASE);
        }

        @Override
        public void beforePageHandler(String subscriber, List<StoredEvent> page) throws Exception {
            maybeCrash(CrashPoint.BEFORE_PAGE_HANDLER);
        }

        @Override
        public void afterHandlerBeforeProgress(String subscriber, List<StoredEvent> page) throws Exception {
            maybeCrash(CrashPoint.AFTER_HANDLER_BEFORE_PROGRESS);
        }

        @Override
        public void afterProgressBeforeCommit(String subscriber, List<StoredEvent> page, long resumeFrom) throws Exception {
            maybeCrash(CrashPoint.AFTER_PROGRESS_BEFORE_COMMIT);
        }

        @Override
        public void afterCommit(String subscriber, List<StoredEvent> page, long resumeFrom) throws Exception {
            maybeCrash(CrashPoint.AFTER_COMMIT);
        }
    }
}
