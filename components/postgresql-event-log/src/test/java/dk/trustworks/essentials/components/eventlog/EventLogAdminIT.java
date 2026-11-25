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

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * The admin facade and parked requests, with
 * two {@link EventLog}s on their own {@code DataSource}s as two nodes. Every arm with a must-fail mutant has a
 * {@code must_fail_*} test that runs it against the mutant and asserts the arm's defect shows.
 */
class EventLogAdminIT extends AbstractLeaseIT {
    private static final String SUB = "admin-sub";

    /** A periodic handler that parks every event of {@code order-1} while {@link #broken}, and records the rest */
    private final List<Long>    delivered = new CopyOnWriteArrayList<>();
    private volatile boolean    broken    = true;
    private volatile Gate       headGate;
    private volatile long       gatedHead = -1;
    /** Each event of {@code order-1} takes this long once {@link #broken} is cleared: a slow retry */
    private volatile long       slowMillis = 0;

    private PeriodicHandler recorder() {
        return event -> {
            if (broken && event.aggregateId().equals("order-1")) {
                throw new ParkEventException("broken");
            }
            if (event.globalOrder() == gatedHead && headGate != null) {
                headGate.pass();
            }
            if (slowMillis > 0 && event.aggregateId().equals("order-1")) {
                Thread.sleep(slowMillis);
            }
            delivered.add(event.globalOrder());
        };
    }

    private static ProgressKey adminKey(String subscriber) {
        return ProgressKey.exclusive(subscriber, TYPE);
    }

    private long parkedRows(String subscriber) throws SQLException {
        return queryLong("SELECT count(*) FROM " + EventLogSchema.PARKED_EVENTS_TABLE + " WHERE subscriber = '" + subscriber + "'");
    }

    /**
     * Node A runs {@link #SUB} (exclusive, periodic); {@code order-1}'s events 1-3 are parked (1 the head, 2 and 3 held
     * behind it), {@code order-2}'s 4 and 5 delivered; then the handler is fixed
     */
    private Subscription parkedOnA(EventLog logA, WatermarkMutant... mutants) throws Exception {
        return parkedOnA(logA, false, mutants);
    }

    private Subscription parkedOnA(EventLog logA, boolean inTransaction, WatermarkMutant... mutants) throws Exception {
        return parkedOnA(logA, inTransaction, TestSeams.Hooks.NONE, mutants);
    }

    private Subscription parkedOnA(EventLog logA, boolean inTransaction, TestSeams.Hooks hooks, WatermarkMutant... mutants) throws Exception {
        var recorder = recorder();
        var a = inTransaction
                ? inTransaction(logA, SUB, ConsistencyMode.FRESH, PageHandler.perEvent((event, tx) -> recorder.handle(event)), seams(hooks, mutants))
                : periodic(logA, SUB, recorder, seams(hooks, mutants));
        awaitOwned(a);
        append(logA, "order-1", 3);
        append(logA, "order-2", 2);
        awaitTrue(() -> delivered.equals(List.of(4L, 5L)));
        await().atMost(WAIT).until(() -> parkedRows(SUB) == 3);
        awaitTrue(() -> a.heldAggregates() == 1);
        broken = false;
        return a;
    }

    /** Waits up to {@code within} for {@code condition}; @return whether it held */
    private static boolean within(Duration within, Callable<Boolean> condition) {
        try {
            await().atMost(within).pollInterval(Duration.ofMillis(10)).until(condition);
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    // ---- listing ----

    @Test
    void the_listing_shows_every_row_of_both_nodes() throws Exception {
        var logA = eventLog("A");
        var logB = eventLog("B");
        parkedOnA(logA);
        execute("CREATE TABLE listing_view (global_order bigint NOT NULL)");
        var views = logB.subscribe(Definitions.definition("views", TYPE, "b1", 1, ConsistencyMode.CONTIGUOUS, EventTypeFilter.of("OrderPlaced")),
                                   (PageHandler) (page, tx) -> {
                                   });
        await().atMost(WAIT).until(() -> logA.admin().subscription(new ProgressKey("views", TYPE, "b1")).map(r -> r.resumePoint() == 6).orElse(false));
        await().atMost(WAIT).until(() -> logB.admin().subscription(adminKey(SUB)).map(r -> r.resumePoint() == 6).orElse(false));

        var rows = logB.admin().subscriptions(Optional.empty());
        assertThat(rows).extracting(r -> r.key().subscriber()).containsExactly(SUB, "views");
        var sub = rows.get(0);
        assertThat(sub.key()).isEqualTo(adminKey(SUB));
        assertThat(sub.exclusive()).isTrue();
        assertThat(sub.consistencyMode()).isEqualTo("FRESH");
        assertThat(sub.progressModel()).isEqualTo("PERIODIC");
        assertThat(sub.resumePoint()).isEqualTo(6);
        assertThat(sub.leaseOwner()).hasValueSatisfying(owner -> assertThat(owner).startsWith("A/"));
        assertThat(sub.leaseUntil()).isPresent();
        assertThat(sub.fence()).isPresent();
        assertThat(sub.filterDigest()).isEmpty();
        assertThat(sub.parkedHeads()).isEqualTo(1);
        assertThat(sub.parkedTotal()).isEqualTo(3);
        assertThat(sub.lag()).hasValue(0);
        var view = rows.get(1);
        assertThat(view.key()).isEqualTo(new ProgressKey("views", TYPE, "b1"));
        assertThat(view.exclusive()).isFalse();
        assertThat(view.consistencyMode()).isEqualTo("CONTIGUOUS");
        assertThat(view.progressModel()).isEqualTo("IN_HANDLER_TRANSACTION");
        assertThat(view.leaseOwner()).isEmpty();
        assertThat(view.fence()).isEmpty();
        assertThat(view.filterDigest()).isPresent();
        assertThat(view.parkedTotal()).isZero();
        assertThat(logA.admin().subscriptions(Optional.of("Other"))).isEmpty();
        assertThat(logA.admin().subscriptions(Optional.of(TYPE))).hasSize(2);
        assertThat(logA.admin().subscriptions(Optional.empty())).extracting(SubscriptionRow::key).as("either node lists the same rows")
                                                                .containsExactly(rows.get(0).key(), rows.get(1).key());

        // The lag of a stopped subscription grows with the type's head
        views.stop();
        append(logA, "order-3", 4);
        assertThat(logB.admin().subscription(new ProgressKey("views", TYPE, "b1")).orElseThrow().lag()).hasValue(4);

        // Parked rows, paged
        var first = logB.admin().parked(adminKey(SUB), 0, 2);
        assertThat(first.rows()).extracting(ParkedRow::globalOrder).containsExactly(1L, 2L);
        assertThat(first.rows().get(0).head()).isTrue();
        assertThat(first.rows().get(0).errorType()).hasValue(ParkEventException.class.getName());
        assertThat(first.rows().get(1).blockedBy()).hasValue(1);
        assertThat(first.nextAfter()).hasValue(2);
        var second = logB.admin().parked(adminKey(SUB), first.nextAfter().getAsLong(), 2);
        assertThat(second.rows()).extracting(ParkedRow::globalOrder).containsExactly(3L);
        assertThat(second.nextAfter()).isEmpty();

        // Refusals
        assertThat(logB.admin().retryParked(adminKey("nobody"), 1)).isEqualTo(ParkedRequestResult.NO_SUCH_SUBSCRIPTION);
        assertThat(logB.admin().retryParked(adminKey(SUB), 2)).as("2 is held behind 1").isEqualTo(ParkedRequestResult.NOT_A_HEAD);
        assertThat(logB.admin().reset(adminKey("nobody"), 1)).isFalse();
        assertThat(logB.admin().subscription(adminKey("nobody"))).isEmpty();
        assertThatThrownBy(() -> logB.admin().deleteRetiredInstance(adminKey(SUB))).isInstanceOf(IllegalArgumentException.class);

        // A retired instance
        assertThat(logB.admin().deleteRetiredInstance(new ProgressKey("views", TYPE, "b1"))).isTrue();
        assertThat(logB.admin().subscriptions(Optional.empty())).extracting(r -> r.key().subscriber()).containsExactly(SUB);
    }

    @Test
    void deleting_the_row_of_an_instance_still_running_on_another_node_does_not_strand_its_held_aggregate() throws Exception {
        // Per-instance rows have no lease, so the admin cannot tell a retired instance from one running on another node
        var logA = eventLog("A");
        var logB = eventLog("B");
        var key  = new ProgressKey("instance-sub", TYPE, "a1");
        logA.subscribe(Definitions.definition("instance-sub", TYPE, "a1", 1, ConsistencyMode.FRESH, EventTypeFilter.all()), recorder());
        append(logA, "order-1", 2);
        append(logA, "order-2", 1);
        awaitTrue(() -> delivered.equals(List.of(3L)));
        await().atMost(WAIT).until(() -> parkedRows("instance-sub") == 2);
        broken = false;

        assertThat(logB.admin().deleteRetiredInstance(key)).isTrue();
        assertThat(parkedRows("instance-sub")).isZero();
        append(logA, "order-2", 1);
        await().atMost(WAIT).until(() -> logB.admin().subscription(key).isPresent());
        var later = append(logA, "order-1", 1).getFirst();

        await().atMost(WAIT).until(() -> delivered.contains(later));
        assertThat(parkedRows("instance-sub")).isZero();
    }

    // ---- retry and discard from the other node ----

    /** Node B (which runs nothing) asks; @return whether node A served the request within {@code within} */
    private boolean requestFromOtherNode(boolean discard, Duration within, WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("A");
        var logB = eventLog("B");
        parkedOnA(logA, mutants);
        var result = discard ? logB.admin().discardParked(adminKey(SUB), 1) : logB.admin().retryParked(adminKey(SUB), 1);
        assertThat(result).isEqualTo(ParkedRequestResult.ACCEPTED);
        assertThat(logB.admin().parked(adminKey(SUB), 0, 10).rows().getFirst().requested()).hasValue(discard ? "DISCARD" : "RETRY");
        return within(within, () -> parkedRows(SUB) == 0 && delivered.size() == (discard ? 4 : 5));
    }

    @Test
    void a_retry_from_the_other_node_is_served_by_the_owner() throws Exception {
        assertThat(requestFromOtherNode(false, WAIT)).isTrue();
        assertThat(delivered).as("the head, then the events held behind it, in order").containsExactly(4L, 5L, 1L, 2L, 3L);
    }

    @Test
    void must_fail_retry_PARKED_CHECK_SKIPPED() throws Exception {
        assertThat(requestFromOtherNode(false, Duration.ofSeconds(3), PARKED_CHECK_SKIPPED)).as("the request was never served").isFalse();
    }

    @Test
    void a_discard_from_the_other_node_is_served_by_the_owner() throws Exception {
        assertThat(requestFromOtherNode(true, WAIT)).isTrue();
        assertThat(delivered).as("the head unhandled, the held events in order").containsExactly(4L, 5L, 2L, 3L);
    }

    @Test
    void must_fail_discard_PARKED_CHECK_SKIPPED() throws Exception {
        assertThat(requestFromOtherNode(true, Duration.ofSeconds(3), PARKED_CHECK_SKIPPED)).as("the request was never served").isFalse();
    }

    @Test
    void a_request_on_the_node_that_runs_the_subscription_is_served_at_once() throws Exception {
        var logA = eventLog("A", LEASE.withParkedCheckInterval(Duration.ofSeconds(5)));
        parkedOnA(logA, PARKED_CHECK_SKIPPED);
        assertThat(logA.admin().retryParked(adminKey(SUB), 1)).isEqualTo(ParkedRequestResult.DONE);
        assertThat(delivered).containsExactly(4L, 5L, 1L, 2L, 3L);
        assertThat(parkedRows(SUB)).isZero();
    }

    // ---- a request racing a reset ----

    /**
     * A serves B's retry and is held in the handler on the head; B resets meanwhile. @return the reset's outcome (or its
     * failure)
     */
    /**
     * In-transaction: the serve is held after its late locks (progress row, then parked rows), so the reset waits for it
     * and is ordered after it. Periodic: the serve is held in the handler, with nothing locked, so the reset goes ahead at
     * once; the serve's second step then sees the new epoch and refuses, and the events are delivered again (at least
     * once). An in-transaction serve held in its handler behaves as the periodic one (late locking).
     */
    private Object requestRacingReset(boolean inTransaction, WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("A");
        var logB = eventLog("B");
        var serveGate = new Gate(inTransaction);
        headGate = new Gate(!inTransaction);
        gatedHead = 1;
        var a = parkedOnA(logA, inTransaction, new TestSeams.Hooks() {
            @Override
            public void afterServeLocked(String subscriber, long head) throws Exception {
                serveGate.pass();
            }
        }, mutants);
        var epoch = row(SUB).resetEpoch();
        assertThat(logB.admin().retryParked(adminKey(SUB), 1)).isEqualTo(ParkedRequestResult.ACCEPTED);
        (inTransaction ? serveGate : headGate).awaitReached();
        var reset = CompletableFuture.supplyAsync(() -> {
            try {
                return logB.admin().reset(adminKey(SUB), 1);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
        if (inTransaction) {
            await().atMost(WAIT).until(() -> queryLong("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND " +
                                                               "(query LIKE '%reset_epoch = reset_epoch + 1%' OR query LIKE 'DELETE FROM " + EventLogSchema.PARKED_EVENTS_TABLE + "%')") == 1);
        } else {
            await().atMost(WAIT).until(reset::isDone);
        }
        headGate.open();
        serveGate.open();
        Object outcome;
        try {
            outcome = reset.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        }
        assertThat(row(SUB).resetEpoch()).isEqualTo(epoch + 1);
        // A notices the reset at its next save (an idle subscription writes nothing) and replays from 1 under the new epoch
        append(logA, "order-3", 1);
        awaitTrue(() -> delivered.stream().filter(order -> order == 1L).count() == 2 && delivered.contains(6L));
        assertThat(parkedRows(SUB)).as("no parked row survives the reset").isZero();
        assertThat(a.heldAggregates()).isZero();
        return outcome;
    }

    @Test
    void a_request_racing_a_reset_is_ordered_before_it() throws Exception {
        assertThat(requestRacingReset(true)).isEqualTo(true);
    }

    @Test
    void must_fail_reset_race_SERVE_WITHOUT_EPOCH() throws Exception {
        assertThat(requestRacingReset(true, SERVE_WITHOUT_EPOCH)).as("the reset deadlocked with the serve").isInstanceOf(SQLException.class);
    }

    @Test
    void a_periodic_request_racing_a_reset_lets_the_reset_go_first_and_is_replayed() throws Exception {
        assertThat(requestRacingReset(false)).isEqualTo(true);
    }

    @Test
    void a_slow_periodic_retry_of_an_exclusive_subscription_commits() throws Exception {
        // Each held event takes longer than lease-local-stop (1 s here). Holding the progress row across them blocked the
        // lease renewal, so the deadline check rolled the retry back every time and the aggregate stayed parked
        var logA = eventLog("A");
        parkedOnA(logA);
        slowMillis = 1_200;

        assertThat(logA.admin().retryParked(adminKey(SUB), 1)).isIn(ParkedRequestResult.DONE, ParkedRequestResult.ACCEPTED);

        await().atMost(WAIT).until(() -> parkedRows(SUB) == 0);
        assertThat(delivered).contains(1L, 2L, 3L);
    }

    // ---- a request racing a takeover ----

    /**
     * A owns {@link #SUB} with no local deadline and its keeper held; B takes over and holds the head. A request stored
     * from a third node is read by A first (B checks only every 3 s). @return the orders A's handler got after the
     * takeover, and whether B served the head
     */
    private record Takeover(List<Long> servedByA, boolean servedByB) {
    }

    private Takeover requestRacingTakeover(WatermarkMutant... mutants) throws Exception {
        var renewGate = new Gate(false);
        var logA = eventLog("A");
        logA.leaseSeams(seams(new TestSeams.Hooks() {
            @Override
            public void beforeRenewal(List<ProgressStore.Key> keys) throws Exception {
                renewGate.pass();
            }
        }));
        var logB = eventLog("B", LEASE.withParkedCheckInterval(Duration.ofSeconds(3)));
        var logC = eventLog("C");
        var all = new ArrayList<WatermarkMutant>(List.of(mutants));
        all.add(NO_LOCAL_DEADLINE);
        parkedOnA(logA, all.toArray(WatermarkMutant[]::new));
        var deliveredB = new CopyOnWriteArrayList<Long>();
        var b = periodic(logB, SUB, event -> deliveredB.add(event.globalOrder()), TestSeams.NONE);
        renewGate.arm().awaitReached();
        lapse(SUB);
        awaitOwned(b);
        awaitTrue(() -> b.heldAggregates() == 1);
        var before = delivered.size();
        assertThat(logC.admin().retryParked(adminKey(SUB), 1)).isEqualTo(ParkedRequestResult.ACCEPTED);
        var servedByB = within(Duration.ofSeconds(15), () -> deliveredB.containsAll(List.of(1L, 2L, 3L)));
        renewGate.open();
        return new Takeover(List.copyOf(delivered.subList(before, delivered.size())), servedByB);
    }

    @Test
    void a_request_racing_a_takeover_is_served_by_the_new_owner_only() throws Exception {
        var result = requestRacingTakeover();
        assertThat(result.servedByA()).as("the old owner serves nothing").isEmpty();
        assertThat(result.servedByB()).as("the new owner serves it").isTrue();
        // A periodic retry commits in a short transaction after its handler ran: wait for it rather than race it
        await().atMost(WAIT).until(() -> parkedRows(SUB) == 0);
    }

    @Test
    void must_fail_takeover_race_SERVE_WITHOUT_FENCE() throws Exception {
        var result = requestRacingTakeover(SERVE_WITHOUT_FENCE);
        assertThat(result.servedByA()).as("the old owner served the head").isNotEmpty();
    }

    // ---- rebuild: truncate and reset in one transaction ----

    private static final String VIEW = "rebuild_view";

    /** The view's state and the resume point in one snapshot: the view must be exactly the fold of {@code [1, resume)} */
    private boolean viewMatchesResumePoint() throws SQLException {
        return queryLong("WITH r AS (SELECT resume_from FROM " + EventLogSchema.PROGRESS_TABLE + " WHERE subscriber = 'rebuild')\n" +
                                 "SELECT CASE WHEN (SELECT count(*) FROM " + VIEW + ") = (SELECT resume_from - 1 FROM r)\n" +
                                 "             AND NOT EXISTS (SELECT 1 FROM " + VIEW + " WHERE applied <> 1 OR global_order >= (SELECT resume_from FROM r)) THEN 1 ELSE 0 END") == 1;
    }

    /**
     * A {@code CONTIGUOUS} in-transaction view of 20 events; a rebuild that rolls back (a crash), then one that commits,
     * sampled until it caught up. @return whether every sample matched the resume point
     */
    private boolean rebuild(WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("A");
        logA.adminSeams(seams(TestSeams.Hooks.NONE, mutants));
        execute("CREATE TABLE " + VIEW + " (global_order bigint PRIMARY KEY, applied int NOT NULL)");
        inTransaction(logA, "rebuild", ConsistencyMode.CONTIGUOUS, PageHandler.perEvent((event, tx) -> {
            try (var statement = tx.connection().prepareStatement("INSERT INTO " + VIEW + " VALUES (?, 1) ON CONFLICT (global_order) DO UPDATE SET applied = " + VIEW + ".applied + 1")) {
                statement.setLong(1, event.globalOrder());
                statement.executeUpdate();
            }
        }), TestSeams.NONE);
        append(logA, "order-1", 10);
        append(logA, "order-2", 10);
        await().atMost(WAIT).until(() -> queryLong("SELECT count(*) FROM " + VIEW) == 20 && row("rebuild").resumeFrom() == 21);
        var key = adminKey("rebuild");
        var consistent = true;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("TRUNCATE " + VIEW);
            }
            assertThat(logA.admin().reset(key, 1, connection)).isTrue();
            connection.rollback();
            // The crash: nothing of the rebuild may show
            for (var i = 0; i < 20; i++) {
                consistent &= viewMatchesResumePoint();
                Thread.sleep(10);
            }
            try (var statement = connection.createStatement()) {
                statement.execute("TRUNCATE " + VIEW);
            }
            assertThat(logA.admin().reset(key, 1, connection)).isTrue();
            connection.commit();
        }
        // An idle subscription notices the reset at its next page
        append(logA, "order-3", 1);
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            consistent &= viewMatchesResumePoint();
            if (queryLong("SELECT count(*) FROM " + VIEW) == 21 && row("rebuild").resumeFrom() == 22) {
                break;
            }
            Thread.sleep(5);
        }
        return consistent && viewMatchesResumePoint() && row("rebuild").resumeFrom() == 22;
    }

    @Test
    void a_rebuild_matches_its_resume_point_at_every_observed_point() throws Exception {
        assertThat(rebuild()).isTrue();
    }

    @Test
    void must_fail_rebuild_RESET_OUTSIDE_CALLER_TRANSACTION() throws Exception {
        assertThat(rebuild(RESET_OUTSIDE_CALLER_TRANSACTION)).as("the rolled-back rebuild left a reset resume point under a full view").isFalse();
    }

    // ---- the parked check costs nothing without a held aggregate ----

    private long parkedChecksOverTenIntervals(boolean held, WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("A");
        Subscription a;
        if (held) {
            a = parkedOnA(logA, mutants);
        } else {
            a = periodic(logA, SUB, recorder(), seams(TestSeams.Hooks.NONE, mutants));
            awaitOwned(a);
            append(logA, "order-2", 2);
            awaitTrue(() -> delivered.size() == 2);
        }
        var before = a.parkedChecks();
        Thread.sleep(LEASE.backstopInterval().multipliedBy(10).toMillis());
        return a.parkedChecks() - before;
    }

    @Test
    void no_held_aggregate_reads_no_requests() throws Exception {
        assertThat(parkedChecksOverTenIntervals(false)).isZero();
    }

    @Test
    void must_fail_no_held_PARKED_CHECK_ALWAYS() throws Exception {
        assertThat(parkedChecksOverTenIntervals(false, PARKED_CHECK_ALWAYS)).isPositive();
    }

    @Test
    void a_held_aggregate_reads_requests_at_most_once_per_interval() throws Exception {
        assertThat(parkedChecksOverTenIntervals(true)).isBetween(1L, 11L);
    }

    // ---- an operator's retry racing an automatic one ----

    @Test
    void an_operator_retry_racing_an_automatic_retry_applies_the_aggregate_once_in_order() throws Exception {
        var logA     = eventLog("A", LEASE.withParkedRetry(Duration.ofMillis(100), Duration.ofMillis(100)));
        var failing  = new java.util.concurrent.atomic.AtomicBoolean(true);
        var autoGate = new Gate(false);
        var a = periodic(logA, SUB, event -> {
            if (failing.get() && event.aggregateId().equals("order-1")) {
                throw new IllegalStateException("order-1 fails for now");
            }
            delivered.add(event.globalOrder());
        }, seams(new TestSeams.Hooks() {
            @Override
            public void beforeParkedRetry(String subscriber, long head, boolean auto) throws Exception {
                if (auto && head == 1) {
                    autoGate.pass();
                }
            }
        }));
        awaitOwned(a);
        append(logA, "order-1", 3);
        append(logA, "order-2", 2);
        await().atMost(WAIT).until(() -> parkedRows(SUB) == 3);
        awaitTrue(() -> delivered.equals(List.of(4L, 5L)));
        autoGate.arm();
        autoGate.awaitReached();
        failing.set(false);
        var operator = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return logA.admin().retryParked(adminKey(SUB), 1);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(operator.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isIn(ParkedRequestResult.ACCEPTED, ParkedRequestResult.DONE, ParkedRequestResult.FAILED);
        autoGate.open();
        await().atMost(WAIT).until(() -> parkedRows(SUB) == 0);
        awaitTrue(() -> delivered.size() >= 5);
        assertThat(delivered).as("each event once, the aggregate in order").containsExactly(4L, 5L, 1L, 2L, 3L);
        assertThat(a.isRunning()).isTrue();
    }
}

