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

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Gate row I5: a stale owner's write never lands once another event log claimed the lease. Owner
 * A runs on event log 1, standby B on event log 2. A is held in a hook with its write still to come, its keeper is held
 * so its lease can be made to lapse, B claims, and A is released. Each arm has a must-fail variant.
 * <p>
 * The in-transaction arms run with a local deadline of 3 s (lease 4 s), so A's page is not stopped by the deadline before
 * its commit: the deadline only avoids wasted work, and the fence alone must stop the page.
 */
class EventLogLeaseFencingIT extends AbstractLeaseIT {
    static final String SUB  = "projection";
    static final String VIEW = "el_test_view";

    static final EventLogSettings ROOMY = LEASE.withLease(Duration.ofSeconds(4), Duration.ofMillis(500), Duration.ofSeconds(3));

    // ---- (a) and (f): a periodic save after a takeover ----

    /** What became of A's held periodic save */
    record StaleSave(boolean aLost, long resumeFrom, List<LoggedVersion> regressions) {
    }

    private StaleSave periodicSaveAfterTakeover(boolean rewindFence, WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("node-a");
        var logB = eventLog("node-b");
        installProgressLog();
        append(logA, "order-1", 10);

        var saveGate  = new Gate(true);
        var renewGate = new Gate(false);
        logA.leaseSeams(seams(new TestSeams.Hooks() {
            @Override
            public void beforeRenewal(List<ProgressStore.Key> keys) throws Exception {
                renewGate.pass();
            }
        }));
        var a = periodic(logA, SUB, event -> {
        }, seams(new TestSeams.Hooks() {
            @Override
            public void beforePeriodicSave(String subscriber, long resumeFrom) throws Exception {
                saveGate.pass();
            }
        }, mutants));
        saveGate.awaitReached();
        var fenceA    = row(SUB).fence();
        var deliveredB = new CopyOnWriteArrayList<Long>();
        var b         = periodic(logB, SUB, event -> deliveredB.add(event.globalOrder()), TestSeams.NONE);

        renewGate.arm().awaitReached();
        if (rewindFence) {
            // Failover: the standby hands out A's fence again
            execute("SELECT setval('el_lease_fence_seq', " + fenceA + ", false)");
        }
        lapse(SUB);
        awaitOwned(b);
        if (rewindFence) {
            assertThat(row(SUB).fence()).as("B drew A's fence again").isEqualTo(fenceA);
        } else {
            assertThat(row(SUB).fence()).isGreaterThan(fenceA);
        }
        append(logB, "order-2", 50);
        awaitTrue(() -> deliveredB.size() >= 60);
        await().atMost(WAIT).until(() -> row(SUB).resumeFrom() == 61);

        saveGate.open();
        await().atMost(WAIT).until(() -> a.lease().state() == Lease.State.STANDBY || row(SUB).resumeFrom() != 61);
        renewGate.open();
        var aLost = a.lease().state() == Lease.State.STANDBY;
        if (aLost) {
            awaitStandbyAfterLoss(a);
            assertThat(a.inbox()).as("a lost owner holds no inbox").isNull();
        }
        return new StaleSave(aLost, row(SUB).resumeFrom(), resumeRegressions(SUB));
    }

    /** Versions that moved the resume point backwards under the same epoch */
    private List<LoggedVersion> resumeRegressions(String subscriber) throws SQLException {
        var regressions = new ArrayList<LoggedVersion>();
        LoggedVersion previous = null;
        for (var version : progressLog(subscriber)) {
            if (previous != null && previous.resetEpoch() == version.resetEpoch() && version.resumeFrom() < previous.resumeFrom()) {
                regressions.add(version);
            }
            previous = version;
        }
        return regressions;
    }

    @Test
    void a_a_periodic_save_after_a_takeover_updates_nothing() throws Exception {
        var result = periodicSaveAfterTakeover(false);
        assertThat(result.aLost()).as("A lost its lease and is standby").isTrue();
        assertThat(result.resumeFrom()).isEqualTo(61);
        assertThat(result.regressions()).as("no committed write moved the resume point back").isEmpty();
    }

    @Test
    void must_fail_a_UNFENCED_SAVE() throws Exception {
        var result = periodicSaveAfterTakeover(false, UNFENCED_SAVE);
        assertThat(result.regressions()).as("A's stale save landed").isNotEmpty();
    }

    @Test
    void f_a_duplicated_fence_does_not_let_the_old_owner_write() throws Exception {
        var result = periodicSaveAfterTakeover(true);
        assertThat(result.aLost()).isTrue();
        assertThat(result.resumeFrom()).isEqualTo(61);
        assertThat(result.regressions()).isEmpty();
    }

    @Test
    void must_fail_f_FENCE_WITHOUT_OWNER() throws Exception {
        var result = periodicSaveAfterTakeover(true, FENCE_WITHOUT_OWNER);
        assertThat(result.regressions()).as("A's save under the duplicated fence landed").isNotEmpty();
    }

    // ---- (b): a page after a takeover ----

    /**
     * A's page of events 1..10 is written and held before its progress update; B claims; A is released, then B commits
     * the same page (B's reader waits for A's open page transaction, so B cannot commit first).
     * A runs without the expected-value guard ({@link WatermarkMutant#PROGRESS_WITHOUT_EXPECTED_RESUME}), which
     * would also refuse A's page here: the fence alone must stop it.
     *
     * @return rows A committed to the view
     */
    private long pageAfterTakeover(WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("node-a", ROOMY);
        var logB = eventLog("node-b", ROOMY);
        createView(VIEW);
        var pageGate  = new Gate(true);
        var renewGate = new Gate(false);
        logA.leaseSeams(seams(new TestSeams.Hooks() {
            @Override
            public void beforeRenewal(List<ProgressStore.Key> keys) throws Exception {
                renewGate.pass();
            }
        }));
        var aMutants = new HashSet<>(List.of(mutants));
        aMutants.add(PROGRESS_WITHOUT_EXPECTED_RESUME);
        var a = inTransaction(logA, SUB, ConsistencyMode.CONTIGUOUS, viewWriter(VIEW, "A"), seams(new TestSeams.Hooks() {
            @Override
            public void afterHandlerBeforeProgress(String subscriber, List<StoredEvent> page) throws Exception {
                pageGate.pass();
            }
        }, aMutants));
        awaitOwned(a);
        var b = inTransaction(logB, SUB, ConsistencyMode.CONTIGUOUS, viewWriter(VIEW, "B"), TestSeams.NONE);
        append(logA, "order-1", 10);
        pageGate.awaitReached();

        renewGate.arm().awaitReached();
        lapse(SUB);
        awaitOwned(b);
        // B cannot release CONTIGUOUS rows yet: A's open page holds an xid its reader must wait for
        pageGate.open();
        await().atMost(WAIT).until(() -> a.lease().state() == Lease.State.STANDBY || queryLong("SELECT count(*) FROM " + VIEW + " WHERE writer = 'A'") > 0);
        renewGate.open();
        var stale = queryLong("SELECT count(*) FROM " + VIEW + " WHERE writer = 'A'");
        if (stale == 0) {
            await().atMost(WAIT).until(() -> row(SUB).resumeFrom() == 11);
        }
        return stale;
    }

    @Test
    void b_a_page_after_a_takeover_rolls_back() throws Exception {
        assertThat(pageAfterTakeover()).as("rows of A's stale page").isZero();
        assertThat(viewDefects(VIEW, 10)).as("each event once").isZero();
    }

    @Test
    void must_fail_b_UNFENCED_SAVE() throws Exception {
        assertThat(pageAfterTakeover(UNFENCED_SAVE)).as("A's stale page committed").isPositive();
    }

    // ---- (c): a stale write waiting on the new owner's row lock ----

    /**
     * A's page is held before its progress update. The lease is made to lapse and a side transaction keeps the row locked; B's
     * claim queues on that lock, then A is released and its update queues behind B's claim. The side transaction
     * commits: B claims, and A's update runs against B's row version. So A's fence was valid when A was released and is
     * not when its update runs - the window a separate fence check cannot close.
     *
     * @return rows A committed to the view
     */
    private long staleWriteBehindTheClaim(WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("node-a", ROOMY);
        var logB = eventLog("node-b", ROOMY);
        createView(VIEW);
        var pageGate  = new Gate(true);
        var renewGate = new Gate(false);
        var claimGate = new Gate(true);
        logA.leaseSeams(seams(new TestSeams.Hooks() {
            @Override
            public void beforeRenewal(List<ProgressStore.Key> keys) throws Exception {
                renewGate.pass();
            }
        }));
        logB.leaseSeams(seams(new TestSeams.Hooks() {
            @Override
            public void beforeClaim(List<ProgressStore.Key> keys) throws Exception {
                claimGate.pass();
            }
        }));
        var a = inTransaction(logA, SUB, ConsistencyMode.CONTIGUOUS, viewWriter(VIEW, "A"), seams(new TestSeams.Hooks() {
            @Override
            public void afterHandlerBeforeProgress(String subscriber, List<StoredEvent> page) throws Exception {
                pageGate.pass();
            }
        }, mutants));
        awaitOwned(a);
        var b = inTransaction(logB, SUB, ConsistencyMode.CONTIGUOUS, viewWriter(VIEW, "B"), TestSeams.NONE);
        claimGate.awaitReached();
        append(logA, "order-1", 10);
        pageGate.awaitReached();
        renewGate.arm().awaitReached();

        lapse(SUB);
        try (var side = dataSource.getConnection()) {
            side.setAutoCommit(false);
            // A claim and an update queue on a row lock only if the committed version matches their WHERE: so the lapse is
            // committed first, and the side transaction only locks the row
            try (var statement = side.createStatement()) {
                statement.execute("SELECT 1 FROM " + EventLogSchema.PROGRESS_TABLE + " WHERE subscriber = '" + SUB + "' FOR UPDATE");
            }
            claimGate.open();
            awaitTrue(() -> lockWaiters() >= 1);
            pageGate.open();
            awaitTrue(() -> lockWaiters() >= 2);
            side.commit();
        }
        awaitOwned(b);
        await().atMost(WAIT).until(() -> a.lease().state() == Lease.State.STANDBY || queryLong("SELECT count(*) FROM " + VIEW + " WHERE writer = 'A'") > 0);
        renewGate.open();
        return queryLong("SELECT count(*) FROM " + VIEW + " WHERE writer = 'A'");
    }

    private long lockWaiters() {
        try {
            return queryLong("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void c_a_stale_write_behind_the_new_owners_lock_updates_nothing() throws Exception {
        assertThat(staleWriteBehindTheClaim()).isZero();
    }

    @Test
    void must_fail_c_FENCE_READ_BEFORE_WRITE() throws Exception {
        assertThat(staleWriteBehindTheClaim(FENCE_READ_BEFORE_WRITE)).as("A's page passed its fence check before B's claim and committed after it").isPositive();
    }

    // ---- (d): a periodic park after a takeover ----

    /** @return the error messages of the park rows of global order 5 */
    private List<String> parkAfterTakeover(WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("node-a");
        var logB = eventLog("node-b");
        var parkA     = new Gate(true);
        var parkB     = new Gate(true);
        var renewGate = new Gate(false);
        logA.leaseSeams(seams(new TestSeams.Hooks() {
            @Override
            public void beforeRenewal(List<ProgressStore.Key> keys) throws Exception {
                renewGate.pass();
            }
        }));
        append(logA, "order-1", 10);
        var a = periodic(logA, SUB, event -> {
            if (event.globalOrder() == 5) {
                throw new ParkEventException("parked by A");
            }
        }, seams(new TestSeams.Hooks() {
            @Override
            public void beforePeriodicPark(String subscriber, long globalOrder) throws Exception {
                parkA.pass();
            }
        }, mutants));
        parkA.awaitReached();
        periodic(logB, SUB, event -> {
            if (event.globalOrder() == 5) {
                throw new ParkEventException("parked by B");
            }
        }, seams(new TestSeams.Hooks() {
            @Override
            public void beforePeriodicPark(String subscriber, long globalOrder) throws Exception {
                parkB.pass();
            }
        }));
        renewGate.arm().awaitReached();
        lapse(SUB);
        parkB.awaitReached();

        parkA.open();
        await().atMost(WAIT).until(() -> a.lease().state() == Lease.State.STANDBY || queryLong("SELECT count(*) FROM el_parked_events") > 0);
        parkB.open();
        await().atMost(WAIT).until(() -> queryLong("SELECT count(*) FROM el_parked_events") > 0 && row(SUB).resumeFrom() == 11);
        renewGate.open();
        var messages = new ArrayList<String>();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT error_message FROM el_parked_events WHERE global_order = 5")) {
            while (resultSet.next()) {
                messages.add(resultSet.getString(1));
            }
        }
        return messages;
    }

    @Test
    void d_a_periodic_park_after_a_takeover_lands_nothing() throws Exception {
        var messages = parkAfterTakeover();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).contains("parked by B");
    }

    @Test
    void must_fail_d_UNGUARDED_PERIODIC_PARK() throws Exception {
        assertThat(parkAfterTakeover(UNGUARDED_PERIODIC_PARK)).anyMatch(message -> message.contains("parked by A"));
    }

    // ---- (e): a claim delayed past a newer owner's ----

    /** @return the row after A's delayed claim ran, and whether A stayed standby */
    private Map.Entry<Row, Boolean> claimDelayed(WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("node-a");
        var logB = eventLog("node-b");
        // The row exists and is free: a first owner on B claims it and releases it on stop
        var first = periodic(logB, SUB, event -> {
        }, TestSeams.NONE);
        awaitOwned(first);
        first.stop();

        var claimGate = new Gate(true);
        var claimsSent = new java.util.concurrent.atomic.AtomicLong();
        logA.leaseSeams(seams(new TestSeams.Hooks() {
            @Override
            public void beforeClaim(List<ProgressStore.Key> keys) throws Exception {
                claimGate.pass();
                claimsSent.incrementAndGet();
            }
        }, mutants));
        var a = periodic(logA, SUB, event -> {
        }, TestSeams.NONE);
        claimGate.awaitReached();
        var b = periodic(logB, SUB, event -> {
        }, TestSeams.NONE);
        awaitOwned(b);
        var owned = row(SUB);

        claimGate.open();
        awaitTrue(() -> claimsSent.get() >= 3 || a.lease().state() != Lease.State.STANDBY);
        var after = row(SUB);
        assertThat(after.fence()).as("fence before: %s", owned.fence()).isNotNull();
        return Map.entry(after, a.lease().state() == Lease.State.STANDBY && after.owner().equals(owned.owner()) && after.fence().equals(owned.fence()));
    }

    @Test
    void e_a_claim_delayed_past_a_newer_owners_matches_nothing() throws Exception {
        var result = claimDelayed();
        assertThat(result.getValue()).as("A stayed standby and B's fence and owner are unchanged: %s", result.getKey()).isTrue();
    }

    @Test
    void must_fail_e_CLAIM_IGNORES_LIVE_LEASE() throws Exception {
        assertThat(claimDelayed(CLAIM_IGNORES_LIVE_LEASE).getValue()).as("A stole the live lease").isFalse();
    }
}
