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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Gate row I6 (design phase 7 §9.2): a reset wins over every write it races - a periodic save, a page commit, a periodic
 * park and a release. Every arm runs once with a non-exclusive and once with an exclusive subscription.
 */
class EventLogResetRaceIT extends AbstractLeaseIT {
    static final String SUB      = "projection";
    static final String INSTANCE = "node-1";
    static final String VIEW     = "el_test_view";

    private EventLog eventLog;
    /** The periodic park arm's deliveries */
    private final List<Long> delivered = new CopyOnWriteArrayList<>();

    private String instance(boolean exclusive) {
        return exclusive ? "" : INSTANCE;
    }

    private Subscription periodic(boolean exclusive, PeriodicHandler handler, TestSeams seams) throws SQLException {
        return track(eventLog.subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key(SUB, TYPE, instance(exclusive)), 1, ConsistencyMode.FRESH,
                                                                           ProgressModel.PERIODIC, handler, null, seams, EventTypeFilter.all(), exclusive)));
    }

    private Subscription inTransaction(boolean exclusive, PageHandler handler, TestSeams seams) throws SQLException {
        return track(eventLog.subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key(SUB, TYPE, instance(exclusive)), 1, ConsistencyMode.CONTIGUOUS,
                                                                           ProgressModel.IN_HANDLER_TRANSACTION, null, handler, seams, EventTypeFilter.all(),
                                                                           exclusive)));
    }

    private void reset(boolean exclusive, long resumeFrom) throws SQLException {
        assertThat(eventLog.reset(SUB, TYPE, instance(exclusive), resumeFrom)).isTrue();
    }

    private long resumeFrom(boolean exclusive) throws SQLException {
        return queryLong("SELECT resume_from FROM " + EventLogSchema.PROGRESS_TABLE + " WHERE subscriber = '" + SUB + "' AND instance_name = '" + instance(exclusive) + "'");
    }

    // ---- a reset racing a periodic save ----

    /**
     * The save of resume point 2 (after event 1) is held; a reset to 6 commits; the save is released
     *
     * @return the events delivered after the save was released
     */
    private List<Long> resetRacingAPeriodicSave(boolean exclusive, WatermarkMutant... mutants) throws Exception {
        eventLog = eventLog("node-a");
        append(eventLog, "order-1", 10);
        var saveGate  = new Gate(true);
        var delivered = new CopyOnWriteArrayList<Long>();
        periodic(exclusive, event -> delivered.add(event.globalOrder()), seams(new TestSeams.Hooks() {
            @Override
            public void beforePeriodicSave(String subscriber, long resumeFrom) throws Exception {
                saveGate.pass();
            }
        }, mutants));
        saveGate.awaitReached();
        assertThat(delivered).containsExactly(1L);
        reset(exclusive, 6);
        saveGate.open();
        awaitTrue(() -> delivered.contains(10L));
        return delivered.subList(1, delivered.size());
    }

    @ParameterizedTest(name = "exclusive={0}")
    @ValueSource(booleans = {false, true})
    void a_reset_racing_a_periodic_save_wins(boolean exclusive) throws Exception {
        assertThat(resetRacingAPeriodicSave(exclusive)).as("delivery restarts at the reset point").containsExactly(6L, 7L, 8L, 9L, 10L);
        await().atMost(WAIT).until(() -> resumeFrom(exclusive) == 11);
    }

    @ParameterizedTest(name = "exclusive={0}")
    @ValueSource(booleans = {false, true})
    void must_fail_SAVE_WITHOUT_EPOCH_on_a_periodic_save(boolean exclusive) throws Exception {
        assertThat(resetRacingAPeriodicSave(exclusive, SAVE_WITHOUT_EPOCH)).as("the stale save undid the reset").contains(2L);
    }

    // ---- a reset racing a page commit ----

    /**
     * The first page (1..10) commits; the second page (11..20) is held before its progress update; a reset to 11 - the
     * resume point the page's expected-value guard expects, so only the epoch tells the page apart - commits; the page
     * is released
     *
     * @return how many pages the handler applied that hold event 11
     */
    private long resetRacingAPageBeforeItsUpdate(boolean exclusive, WatermarkMutant... mutants) throws Exception {
        eventLog = eventLog("node-a");
        createView(VIEW);
        append(eventLog, "order-1", 10);
        var pageGate = new Gate(false);
        var pagesWith11 = new AtomicLong();
        inTransaction(exclusive, viewWriter(VIEW, "A"), seams(new TestSeams.Hooks() {
            @Override
            public void beforePageHandler(String subscriber, List<StoredEvent> page) {
                if (page.stream().anyMatch(event -> event.globalOrder() == 11)) {
                    pagesWith11.incrementAndGet();
                }
            }

            @Override
            public void afterHandlerBeforeProgress(String subscriber, List<StoredEvent> page) throws Exception {
                if (page.stream().anyMatch(event -> event.globalOrder() == 11)) {
                    pageGate.pass();
                }
            }
        }, mutants));
        await().atMost(WAIT).until(() -> resumeFrom(exclusive) == 11);
        pageGate.arm();
        append(eventLog, "order-2", 10);
        pageGate.awaitReached();
        reset(exclusive, 11);
        pageGate.open();
        await().atMost(WAIT).until(() -> resumeFrom(exclusive) == 21);
        assertThat(viewDefects(VIEW, 20)).as("each event once in the view").isZero();
        return pagesWith11.get();
    }

    @ParameterizedTest(name = "exclusive={0}")
    @ValueSource(booleans = {false, true})
    void a_reset_racing_a_page_rolls_the_page_back(boolean exclusive) throws Exception {
        assertThat(resetRacingAPageBeforeItsUpdate(exclusive)).as("the page rolled back and was applied again from the reset point").isEqualTo(2);
    }

    @ParameterizedTest(name = "exclusive={0}")
    @ValueSource(booleans = {false, true})
    void must_fail_SAVE_WITHOUT_EPOCH_on_a_page(boolean exclusive) throws Exception {
        assertThat(resetRacingAPageBeforeItsUpdate(exclusive, SAVE_WITHOUT_EPOCH)).as("the stale page committed over the reset").isEqualTo(1);
    }

    /**
     * The page that ends at event 10 has run its progress update and is held before its commit, so it holds the row lock;
     * a reset to that page's first event waits on it; the page commits first, the reset after it, and the subscription repositions at 3
     */
    @ParameterizedTest(name = "exclusive={0}")
    @ValueSource(booleans = {false, true})
    void a_reset_waiting_on_a_pages_row_lock_applies_after_the_page(boolean exclusive) throws Exception {
        eventLog = eventLog("node-a");
        createView(VIEW);
        append(eventLog, "order-1", 10);
        var commitGate = new Gate(true);
        var heldFirst  = new AtomicLong();
        inTransaction(exclusive, viewWriter(VIEW, "A"), seams(new TestSeams.Hooks() {
            @Override
            public void afterProgressBeforeCommit(String subscriber, List<StoredEvent> page, long resumeFrom) throws Exception {
                if (page.getLast().globalOrder() == 10 && heldFirst.compareAndSet(0, page.getFirst().globalOrder())) {
                    commitGate.pass();
                }
            }
        }));
        commitGate.awaitReached();
        var reset = CompletableFuture.supplyAsync(() -> {
            try {
                return eventLog.reset(SUB, TYPE, instance(exclusive), heldFirst.get());
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
        awaitTrue(() -> {
            try {
                return queryLong("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()") >= 1;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        commitGate.open();
        assertThat(reset.get(30, TimeUnit.SECONDS)).isTrue();
        // The reset applied after the page; the next page (event 11) updates nothing and repositions: the held page's events are
        // applied a second time, from the reset point
        append(eventLog, "order-2", 1);
        await().atMost(WAIT).until(() -> queryLong("SELECT count(*) FROM " + VIEW + " WHERE global_order = 10") == 2);
        var first = heldFirst.get();
        assertThat(queryLong("SELECT count(*) FROM " + VIEW + " WHERE global_order < " + first)).isEqualTo(first - 1);
        assertThat(queryLong("SELECT count(*) FROM " + VIEW + " WHERE global_order = " + first)).isEqualTo(2);
        await().atMost(WAIT).until(() -> resumeFrom(exclusive) == 12);
        assertThat(queryLong("SELECT count(*) FROM " + VIEW + " WHERE global_order = 11")).isEqualTo(1);
    }

    // ---- a reset racing a periodic park (phase-4 §11.5's gap) ----

    /** @return the park rows left after event 5's held park insert was released behind a reset to 8 */
    private long resetRacingAPeriodicPark(boolean exclusive, WatermarkMutant... mutants) throws Exception {
        eventLog = eventLog("node-a");
        append(eventLog, "order-1", 10);
        var parkGate  = new Gate(true);
        periodic(exclusive, event -> {
            delivered.add(event.globalOrder());
            if (event.globalOrder() == 5) {
                throw new ParkEventException("park 5");
            }
        }, seams(new TestSeams.Hooks() {
            @Override
            public void beforePeriodicPark(String subscriber, long globalOrder) throws Exception {
                parkGate.pass();
            }
        }, mutants));
        parkGate.awaitReached();
        reset(exclusive, 8);
        parkGate.open();
        // Under the mutant the surviving park row holds order-1's later events behind it, so they are parked, not delivered
        await().atMost(WAIT).until(() -> resumeFrom(exclusive) == 11);
        return queryLong("SELECT count(*) FROM " + EventLogSchema.PARKED_EVENTS_TABLE);
    }

    @ParameterizedTest(name = "exclusive={0}")
    @ValueSource(booleans = {false, true})
    void a_reset_racing_a_periodic_park_leaves_no_park_row(boolean exclusive) throws Exception {
        assertThat(resetRacingAPeriodicPark(exclusive)).isZero();
        assertThat(delivered).as("delivery restarted at the reset point").contains(8L, 9L, 10L);
    }

    @ParameterizedTest(name = "exclusive={0}")
    @ValueSource(booleans = {false, true})
    void must_fail_UNGUARDED_PERIODIC_PARK(boolean exclusive) throws Exception {
        assertThat(resetRacingAPeriodicPark(exclusive, UNGUARDED_PERIODIC_PARK)).as("a park row outlived the reset").isPositive();
    }

    // ---- a reset racing a release ----

    /** Events 1..10 are delivered and saved; a reset to 3 commits; the subscription stops before it writes again */
    private long resetRacingAStop(boolean exclusive, WatermarkMutant... mutants) throws Exception {
        eventLog = eventLog("node-a");
        append(eventLog, "order-1", 10);
        var subscription = periodic(exclusive, event -> {
        }, seams(TestSeams.Hooks.NONE, mutants));
        await().atMost(WAIT).until(() -> resumeFrom(exclusive) == 11);
        reset(exclusive, 3);
        subscription.stop();
        if (exclusive) {
            assertThat(row(SUB).owner()).as("the lease is released either way").isNull();
        }
        return resumeFrom(exclusive);
    }

    @ParameterizedTest(name = "exclusive={0}")
    @ValueSource(booleans = {false, true})
    void a_release_leaves_the_resets_resume_point(boolean exclusive) throws Exception {
        assertThat(resetRacingAStop(exclusive)).isEqualTo(3);
    }

    @Test
    void must_fail_RELEASE_WRITES_ANY_EPOCH() throws Exception {
        assertThat(resetRacingAStop(true, RELEASE_WRITES_ANY_EPOCH)).as("the release undid the reset").isEqualTo(11);
    }

    // ---- a reset while owned ----

    @Test
    void a_reset_while_owned_keeps_the_lease_and_repositions_the_owner() throws Exception {
        eventLog = eventLog("node-a");
        var standbyLog = eventLog("node-b");
        append(eventLog, "order-1", 10);
        var delivered = new CopyOnWriteArrayList<Long>();
        var owner     = periodic(true, event -> delivered.add(event.globalOrder()), TestSeams.NONE);
        await().atMost(WAIT).until(() -> resumeFrom(true) == 11);
        var standby = periodic(standbyLog, SUB, event -> {
        }, TestSeams.NONE);
        var before = row(SUB);

        reset(true, 1);
        var after = row(SUB);
        assertThat(after.fence()).isEqualTo(before.fence());
        assertThat(after.owner()).isEqualTo(before.owner());
        assertThat(after.leased()).isTrue();
        append(eventLog, "order-2", 1);
        awaitTrue(() -> delivered.stream().filter(order -> order == 1L).count() == 2 && delivered.contains(11L));
        assertThat(owner.lease().state()).isEqualTo(Lease.State.OWNED);
        assertThat(standby.lease().state()).isEqualTo(Lease.State.STANDBY);
        assertThat(row(SUB).fence()).as("no standby claimed").isEqualTo(before.fence());
    }
}
