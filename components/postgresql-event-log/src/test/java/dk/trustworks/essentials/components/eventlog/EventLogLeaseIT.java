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

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Gate row I16 (design phase 7 §9.3): an owner that cannot renew stops delivering before its lease expires by server
 * time; a renewal pass never extends a lease whose fence changed; one renewal statement renews every owned lease.
 * <p>
 * The server's expiry is mapped onto the JVM's monotonic clock after every renewal (in
 * {@code afterRenewalBeforeConfirm}, before the delivery threads can rely on it): {@code nanoTime} taken before reading
 * {@code lease_until - clock_timestamp()}, so the mapped expiry is never later than the real one. Every periodic handler
 * call and every page start at or after it is a violation.
 */
class EventLogLeaseIT extends AbstractLeaseIT {
    static final String PERIODIC = "periodic";
    static final String PAGES    = "pages";
    static final String VIEW     = "el_test_view";

    /** Saves rarely, so a periodic owner does not stop at a blocked save before its deadline */
    static final EventLogSettings SETTINGS = LEASE.withProgressSaveInterval(Duration.ofSeconds(10));

    enum Block {
        /** {@code beforeRenewal} holds the keeper */
        HOOK,
        /** A side transaction holds {@code LOCK TABLE el_progress IN ACCESS EXCLUSIVE MODE}: renewals time out */
        LOCK_TABLE,
        /** One renewal is held 1.5 s in {@code afterRenewalBeforeConfirm}, and every later one in {@code beforeRenewal} */
        SLOW_CONFIRM
    }

    private final AtomicLong violations  = new AtomicLong();
    private volatile long    expiryNanos = Long.MAX_VALUE;

    private void recordExpiry() throws SQLException {
        var before = System.nanoTime();
        var remaining = queryLong("SELECT (extract(epoch FROM min(lease_until) - clock_timestamp()) * 1e9)::bigint FROM " + EventLogSchema.PROGRESS_TABLE +
                                          " WHERE instance_name = '' AND lease_until IS NOT NULL");
        expiryNanos = before + remaining;
    }

    private void check() {
        if (System.nanoTime() - expiryNanos >= 0) {
            violations.incrementAndGet();
        }
    }

    /**
     * A periodic and an in-transaction owner deliver a steady stream; renewals are blocked by {@code block}
     *
     * @return handler calls and page starts at or after the server's expiry
     */
    private long ownerThatCannotRenew(Block block, Set<WatermarkMutant> subscriptionMutants, Set<WatermarkMutant> keeperMutants) throws Exception {
        var log = eventLog("node-a", SETTINGS);
        createView(VIEW);
        var renewGate   = new Gate(false);
        var slowConfirm = new AtomicBoolean(false);
        log.leaseSeams(seams(new TestSeams.Hooks() {
            @Override
            public void beforeRenewal(List<ProgressStore.Key> keys) throws Exception {
                renewGate.pass();
            }

            @Override
            public void afterRenewalBeforeConfirm(List<ProgressStore.Key> keys) throws Exception {
                recordExpiry();
                if (slowConfirm.getAndSet(false)) {
                    renewGate.arm();
                    Thread.sleep(1_500);
                }
            }
        }, keeperMutants));
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeHandler(String subscriber, StoredEvent event) {
                check();
            }

            @Override
            public void beforePageHandler(String subscriber, List<StoredEvent> page) {
                check();
            }
        };
        var delivered = new CopyOnWriteArrayList<Long>();
        var periodic  = periodic(log, PERIODIC, event -> delivered.add(event.globalOrder()), seams(hooks, subscriptionMutants));
        var pages     = inTransaction(log, PAGES, Subscription.ConsistencyMode.CONTIGUOUS, viewWriter(VIEW, "A"), seams(hooks, subscriptionMutants));
        awaitOwned(periodic);
        awaitOwned(pages);

        var writing = new AtomicBoolean(true);
        var written = new AtomicLong();
        var writer = Thread.ofVirtual().start(() -> {
            while (writing.get()) {
                try {
                    append(log, "order-" + written.incrementAndGet(), 1);
                    Thread.sleep(20);
                } catch (Exception e) {
                    return;
                }
            }
        });
        try {
            awaitTrue(() -> delivered.size() >= 20 && expiryNanos != Long.MAX_VALUE);
            var fence = row(PERIODIC).fence();
            Connection locker = null;
            switch (block) {
                case HOOK -> renewGate.arm().awaitReached();
                case SLOW_CONFIRM -> {
                    slowConfirm.set(true);
                    // The slow confirmation arms the gate (new latches) after its read; the keeper's next pass reaches it
                    awaitTrue(renewGate::armed);
                    renewGate.awaitReached();
                }
                case LOCK_TABLE -> {
                    locker = dataSource.getConnection();
                    locker.setAutoCommit(false);
                    try (var statement = locker.createStatement()) {
                        statement.execute("LOCK TABLE " + EventLogSchema.PROGRESS_TABLE + " IN ACCESS EXCLUSIVE MODE");
                    }
                }
            }
            // Well past the server's expiry: an owner without a working deadline has delivered after it by now
            await().atMost(WAIT).until(() -> System.nanoTime() - expiryNanos > Duration.ofMillis(700).toNanos());
            var result = violations.get();
            if (subscriptionMutants.isEmpty() && keeperMutants.isEmpty()) {
                assertThat(result).as("deliveries at or after the server's expiry").isZero();
                assertThat(periodic.phase()).isEqualTo(Subscription.Phase.SUSPENDED);
                if (block != Block.LOCK_TABLE) {
                    // Under the table lock the page waits on its progress update, which it sent before its deadline
                    assertThat(pages.phase()).isEqualTo(Subscription.Phase.SUSPENDED);
                }
                var before = delivered.size();

                // Renewals work again; nobody claimed, so the fence is unchanged and delivery resumes, nothing replayed
                if (locker != null) {
                    locker.commit();
                    locker.close();
                }
                renewGate.open();
                awaitTrue(() -> periodic.lease().state() == Lease.State.OWNED && pages.lease().state() == Lease.State.OWNED);
                awaitTrue(() -> delivered.size() > before + 10);
                assertThat(delivered).doesNotHaveDuplicates();
                assertThat(row(PERIODIC).fence()).as("no one claimed it").isEqualTo(fence);
                writing.set(false);
                writer.join();
                var last = written.get();
                awaitTrue(() -> {
                    try {
                        return viewDefects(VIEW, last) == 0;
                    } catch (SQLException e) {
                        return false;
                    }
                });
            } else if (locker != null) {
                locker.rollback();
                locker.close();
            }
            return result;
        } finally {
            writing.set(false);
            writer.join();
        }
    }

    @Test
    void an_owner_whose_renewals_are_held_stops_before_its_lease_expires_and_resumes() throws Exception {
        assertThat(ownerThatCannotRenew(Block.HOOK, Set.of(), Set.of())).isZero();
    }

    @Test
    void an_owner_whose_renewals_time_out_on_a_table_lock_stops_before_its_lease_expires_and_resumes() throws Exception {
        assertThat(ownerThatCannotRenew(Block.LOCK_TABLE, Set.of(), Set.of())).isZero();
    }

    @Test
    void a_slow_renewal_reply_does_not_move_the_deadline_past_the_server_expiry() throws Exception {
        assertThat(ownerThatCannotRenew(Block.SLOW_CONFIRM, Set.of(), Set.of())).isZero();
    }

    @Test
    void must_fail_NO_LOCAL_DEADLINE() throws Exception {
        assertThat(ownerThatCannotRenew(Block.HOOK, Set.of(NO_LOCAL_DEADLINE), Set.of())).as("deliveries after the server's expiry").isPositive();
    }

    @Test
    void must_fail_DEADLINE_FROM_RENEWAL_END() throws Exception {
        assertThat(ownerThatCannotRenew(Block.SLOW_CONFIRM, Set.of(), Set.of(DEADLINE_FROM_RENEWAL_END))).as("deliveries after the server's expiry").isPositive();
    }

    // ---- a pass never extends a changed fence; a lost lease leaves the reader ----

    /**
     * Event log A owns 20 exclusive subscriptions; one lease is made to lapse while A's keeper is held, and event log B
     * claims it; A's next pass runs
     *
     * @return whether A's subscription of that lease lost it and went back to standby, out of the reader
     */
    private boolean passAfterOneLeaseMoved(WatermarkMutant... keeperMutants) throws Exception {
        var logA      = eventLog("node-a");
        var logB      = eventLog("node-b");
        var renewGate = new Gate(false);
        var renewals  = new CopyOnWriteArrayList<Integer>();
        logA.leaseSeams(seams(new TestSeams.Hooks() {
            @Override
            public void beforeRenewal(List<ProgressStore.Key> keys) throws Exception {
                renewGate.pass();
            }
        }, keeperMutants));
        var owned = new ArrayList<Subscription>();
        for (var i = 0; i < 20; i++) {
            owned.add(periodic(logA, "s" + i, event -> {
            }, TestSeams.NONE));
        }
        owned.forEach(AbstractLeaseIT::awaitOwned);
        awaitTrue(() -> owned.stream().allMatch(s -> s.inbox() != null));
        var moved  = owned.get(7);
        var claimB = periodic(logB, "s7", event -> {
        }, TestSeams.NONE);

        renewGate.arm().awaitReached();
        lapse("s7");
        awaitOwned(claimB);
        var fenceB = row("s7").fence();
        var passes = logA.leaseKeeper().passes;
        renewGate.open();
        await().atMost(WAIT).until(() -> logA.leaseKeeper().passes >= passes + 2);
        var lost = moved.lease().state() == Lease.State.STANDBY;
        if (lost) {
            awaitStandbyAfterLoss(moved);
            assertThat(moved.inbox()).as("a lost subscription holds no inbox").isNull();
        }
        assertThat(owned.stream().filter(s -> s != moved)).as("the other 19 stay owned").allMatch(s -> s.lease().state() == Lease.State.OWNED);
        assertThat(row("s7").fence()).isEqualTo(fenceB);
        assertThat(row("s7").owner()).isEqualTo(logB.leaseOwner());
        return lost && claimB.lease().state() == Lease.State.OWNED;
    }

    @Test
    void a_pass_never_extends_a_lease_whose_fence_changed() throws Exception {
        assertThat(passAfterOneLeaseMoved()).isTrue();
    }

    @Test
    void must_fail_RENEW_WITHOUT_FENCE() throws Exception {
        assertThat(passAfterOneLeaseMoved(RENEW_WITHOUT_FENCE)).as("A's pass renewed the lease B holds").isFalse();
    }

    // ---- one renewal statement for every owned lease ----

    /** @return renewal statements per pass with 200 owned leases */
    private double renewalStatementsPerPass(WatermarkMutant... keeperMutants) throws Exception {
        var log = eventLog("node-a");
        log.leaseSeams(seams(TestSeams.Hooks.NONE, keeperMutants));
        var owned = new ArrayList<Subscription>();
        for (var i = 0; i < 200; i++) {
            owned.add(periodic(log, "m" + i, event -> {
            }, TestSeams.NONE));
        }
        owned.forEach(AbstractLeaseIT::awaitOwned);
        var keeper     = log.leaseKeeper();
        var passes     = keeper.passes;
        var statements = keeper.renewStatements;
        await().atMost(WAIT).until(() -> keeper.passes >= passes + 3);
        // Read both at once, between passes
        var passesAfter     = keeper.passes;
        var statementsAfter = keeper.renewStatements;
        return (double) (statementsAfter - statements) / (passesAfter - passes);
    }

    @Test
    void one_statement_renews_every_owned_lease() throws Exception {
        assertThat(renewalStatementsPerPass()).isBetween(0.5, 1.5);
    }

    @Test
    void must_fail_RENEW_PER_LEASE() throws Exception {
        assertThat(renewalStatementsPerPass(RENEW_PER_LEASE)).isGreaterThan(100);
    }
}
