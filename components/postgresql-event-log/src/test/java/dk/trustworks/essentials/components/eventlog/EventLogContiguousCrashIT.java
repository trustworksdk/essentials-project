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

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * I12: a {@code CONTIGUOUS} subscription never hands on a row above its received
 * watermark, and with in-transaction progress a crash at any point leaves the projection equal to applying each event
 * once. Also the in-transaction compare-and-set on the previous resume point, and a reset that rolls back an open page.
 * <p>
 * The crash loop: per crash point (the page hooks and both reads), a round of writers with random commit order and a
 * rollback below and above a writer that holds a value, a subscription that crashes there ({@link TestSeams.Crash}, the
 * page connection's backend then terminated), restarted with the same key in the next round. A keeper subscription
 * holds the type's reader active, so the restarted one catches up below the held value, attaches, and needs the
 * deferred bridge once the held value commits. At the end every aggregate's view row is compared with a reference fold
 * over the event table in {@code global_order}. The must-fail variants each set up the one situation their rule is
 * for, through hooks and latches.
 */
class EventLogContiguousCrashIT extends AbstractPageIT {
    private static final String PROJECTION = "proj";
    private static final int    PASSES     = Boolean.getBoolean("benchmark.run") ? 5 : 1;

    private final Random random = new Random(4711);

    @BeforeEach
    void pageSettings() throws SQLException {
        restartEventLog(dataSource, FAST.withTailPageSize(10).withCatchUpPageSize(15).withPageSize(7));
    }

    /** Checks every release of one incarnation: strictly increasing, and at or below the watermark received for it */
    private static final class CheckingHooks extends CrashingHooks {
        final List<String> failures;
        long               lastReleased = Long.MIN_VALUE;

        CheckingHooks(CrashPoint point, List<String> failures) {
            super(point);
            this.failures = failures;
        }

        @Override
        public void onRelease(String subscriber, List<StoredEvent> rows, long receivedWatermark) {
            for (var row : rows) {
                if (row.globalOrder() <= lastReleased || row.globalOrder() > receivedWatermark) {
                    failures.add("released " + row.globalOrder() + " after " + lastReleased + " under received watermark " + receivedWatermark);
                }
                lastReleased = row.globalOrder();
            }
            super.onRelease(subscriber, rows, receivedWatermark);
        }
    }

    @Test
    void a_crash_at_any_point_leaves_the_view_equal_to_applying_each_event_once() throws Exception {
        subscribe("keeper", new Recorder());
        var failures = new CopyOnWriteArrayList<String>();
        var fired    = EnumSet.noneOf(CrashPoint.class);
        for (var pass = 0; pass < PASSES; pass++) {
            for (var point : CrashPoint.values()) {
                if (crashRound(point, failures)) {
                    fired.add(point);
                }
            }
        }
        var last = new CheckingHooks(null, failures);
        contiguous(PROJECTION, new TestSeams(Set.of(), last, null));
        commitAppend("tail", 1);
        awaitCaughtUp(PROJECTION);
        assertThat(fired).as("every crash point was reached").containsExactlyInAnyOrder(CrashPoint.values());
        assertThat(failures).as("releases").isEmpty();
        assertThat(foldDifferences()).isEmpty();
    }

    /**
     * @return whether the crash fired
     */
    private boolean crashRound(CrashPoint point, List<String> failures) throws Exception {
        writeShuffled("pre", 4);
        var held = openAppend("held", 1);
        var post = writeShuffled("post", 4);
        awaitOffered(post);
        var hooks        = new CheckingHooks(point, failures);
        var subscription = contiguous(PROJECTION, new TestSeams(Set.of(), hooks, null));
        awaitTrue(() -> hooks.crashed.get() || subscription.isIdle());
        held.commit();
        await().atMost(WAIT).until(hooks.crashed::get);
        cleanUpCrash(subscription);
        return hooks.crashed.get();
    }

    /**
     * {@code writers} transactions, each appending one to three events to its own aggregate, committed in a random
     * order; one of them rolls back
     *
     * @return the highest global order committed (a rolled-back value stays a hole while another writer holds a value)
     */
    private long writeShuffled(String prefix, int writers) throws SQLException {
        var open = new ArrayList<OpenWriter>();
        for (var w = 0; w < writers; w++) {
            open.add(openAppend(prefix + "-" + w, 1 + random.nextInt(3)));
        }
        Collections.shuffle(open, random);
        open.getFirst().rollback();
        var highest = 0L;
        for (var writer : open.subList(1, open.size())) {
            writer.commit();
            highest = Math.max(highest, writer.result().events().getLast().globalOrder());
        }
        return highest;
    }

    // ---- PROGRESS_AFTER_COMMIT ----

    @Test
    void a_crash_after_the_handler_and_before_the_progress_update_applies_nothing_twice() throws Exception {
        assertThat(crashOnceAfterTheHandler()).isEmpty();
    }

    @Test
    void must_fail_progress_in_its_own_transaction_after_the_page_applies_a_page_twice() throws Exception {
        assertThat(crashOnceAfterTheHandler(WatermarkMutant.PROGRESS_AFTER_COMMIT)).isNotEmpty();
    }

    private List<String> crashOnceAfterTheHandler(WatermarkMutant... mutants) throws Exception {
        subscribe("keeper", new Recorder());
        commitAppend("A", 5);
        var hooks        = new CrashingHooks(CrashPoint.AFTER_HANDLER_BEFORE_PROGRESS);
        var subscription = contiguous(PROJECTION, new TestSeams(Set.of(mutants), hooks, null));
        await().atMost(WAIT).until(hooks.crashed::get);
        cleanUpCrash(subscription);
        contiguous(PROJECTION, mutants);
        awaitCaughtUp(PROJECTION);
        return foldDifferences();
    }

    // ---- RELEASE_AT_READER_WATERMARK ----

    @Test
    void held_rows_are_released_only_at_the_received_watermark() throws Exception {
        assertThat(lateCommitBelowAHeldRow()).isEmpty();
    }

    @Test
    void must_fail_releasing_at_the_reader_s_live_watermark_hands_on_a_row_before_the_late_commit_below_it() throws Exception {
        assertThat(lateCommitBelowAHeldRow(WatermarkMutant.RELEASE_AT_READER_WATERMARK)).isNotEmpty();
    }

    /**
     * {@code g0} held open, {@code g1} committed and held back. When the reader has read {@code g0}'s late commit and
     * published its live state, but not yet offered the batch, the hook lets the delivery thread go round twice: only a
     * release at the live watermark hands {@code g1} on then, before {@code g0}
     */
    private List<String> lateCommitBelowAHeldRow(WatermarkMutant... mutants) throws Exception {
        var subscription = new AtomicReference<Subscription>();
        var g0           = new AtomicLong(-1);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void afterReadBeforePublish(String aggregateType, List<StoredEvent> rows) {
                var current = subscription.get();
                if (current != null && rows.stream().anyMatch(row -> row.globalOrder() == g0.get())) {
                    var seen = current.iterations();
                    await().atMost(WAIT).until(() -> current.iterations() >= seen + 2);
                }
            }
        };
        subscription.set(contiguous(PROJECTION, new TestSeams(Set.of(mutants), hooks, null)));
        awaitTrue(subscription.get()::isIdle);
        var writer = openAppend("A", 1);
        g0.set(writer.globalOrder());
        var g1 = commitAppend("B", 1);
        awaitOffered(g1);
        writer.commit();
        awaitCaughtUp(PROJECTION);
        return foldDifferences();
    }

    // ---- NO_HOLD_BACK ----

    @Test
    void a_row_above_a_held_value_waits_for_it() throws Exception {
        assertThat(rowAboveAHeldValue()).isEmpty();
    }

    @Test
    void must_fail_handing_on_every_received_row_applies_the_held_value_after_higher_orders() throws Exception {
        assertThat(rowAboveAHeldValue(WatermarkMutant.NO_HOLD_BACK)).isNotEmpty();
    }

    private List<String> rowAboveAHeldValue(WatermarkMutant... mutants) throws Exception {
        var subscription = contiguous(PROJECTION, mutants);
        awaitTrue(subscription::isIdle);
        var writer = openAppend("A", 1);
        var g1     = commitAppend("B", 1);
        awaitOffered(g1);
        awaitReads(2);
        awaitTrue(subscription::isIdle);
        writer.commit();
        awaitCaughtUp(PROJECTION);
        return foldDifferences();
    }

    // ---- CONTIGUOUS_SKIPS_DEFERRED_BRIDGE ----

    @Test
    void the_deferred_bridge_reads_what_was_committed_between_the_catch_up_and_the_attach() throws Exception {
        assertThat(commitsJustBeforeTheAttach()).isEmpty();
    }

    @Test
    void must_fail_attaching_without_the_deferred_bridge_loses_the_rows_below_the_attach_cursor() throws Exception {
        assertThat(commitsJustBeforeTheAttach(WatermarkMutant.CONTIGUOUS_SKIPS_DEFERRED_BRIDGE)).isNotEmpty();
    }

    /** At the first attach request, three events commit and the reader reads them: they are at or below {@code C} */
    private List<String> commitsJustBeforeTheAttach(WatermarkMutant... mutants) throws Exception {
        subscribe("keeper", new Recorder());
        commitAppend("A", 2);
        var once = new AtomicBoolean(true);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeAttachRequest(String subscriber) throws Exception {
                if (once.getAndSet(false)) {
                    awaitOffered(commitAppend("B", 3) + 2);
                }
            }
        };
        contiguous(PROJECTION, new TestSeams(Set.of(mutants), hooks, null));
        awaitCaughtUp(PROJECTION);
        return foldDifferences();
    }

    // ---- PROGRESS_WITHOUT_EXPECTED_RESUME ----

    @Test
    void two_subscriptions_with_the_same_key_never_apply_a_page_twice() throws Exception {
        assertThat(twoEventLogsSameKey()).isEmpty();
    }

    @Test
    void must_fail_without_the_expected_resume_point_both_apply_the_page() throws Exception {
        assertThat(twoEventLogsSameKey(WatermarkMutant.PROGRESS_WITHOUT_EXPECTED_RESUME)).isNotEmpty();
    }

    /**
     * Two event logs (two JVMs) start the same subscription at 1. The second one's first page waits until the first one
     * committed the same page, then applies it
     */
    private List<String> twoEventLogsSameKey(WatermarkMutant... mutants) throws Exception {
        var firstCommitted = new CountDownLatch(1);
        var firstHooks = new TestSeams.Hooks() {
            @Override
            public void afterCommit(String subscriber, List<StoredEvent> page, long resumeFrom) {
                firstCommitted.countDown();
            }
        };
        var waitOnce = new AtomicBoolean(true);
        var secondHooks = new TestSeams.Hooks() {
            @Override
            public void beforePageHandler(String subscriber, List<StoredEvent> page) throws Exception {
                if (waitOnce.getAndSet(false)) {
                    assertThat(firstCommitted.await(30, TimeUnit.SECONDS)).isTrue();
                }
            }
        };
        var second = EventLog.builder().setDataSource(dataSource).setSettings(eventLog.settings()).build();
        second.start();
        second.registerAggregateType(TYPE);
        try {
            contiguous(PROJECTION, new TestSeams(Set.of(mutants), firstHooks, null));
            track(second.subscribe(contiguousSpec(PROJECTION, new TestSeams(Set.of(mutants), secondHooks, null))));
            commitAppend("A", 5);
            await().atMost(WAIT).until(() -> !waitOnce.get());
            awaitCaughtUp(PROJECTION);
            commitAppend("A", 1);
            awaitCaughtUp(PROJECTION);
            return foldDifferences();
        } finally {
            second.stop();
        }
    }

    // ---- a reset rolls back an open page ----

    @Test
    void a_reset_while_a_page_is_open_rolls_the_page_back_and_restarts_at_the_reset_point() throws Exception {
        var once = new AtomicBoolean(true);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void afterHandlerBeforeProgress(String subscriber, List<StoredEvent> page) throws Exception {
                if (once.getAndSet(false)) {
                    execute("UPDATE " + EventLogSchema.PROGRESS_TABLE + " SET reset_epoch = reset_epoch + 1, resume_from = " + page.getFirst().globalOrder());
                }
            }
        };
        var subscription = contiguous(PROJECTION, new TestSeams(Set.of(), hooks, null));
        awaitTrue(subscription::isIdle);
        var first = commitAppend("A", 3);
        await().atMost(WAIT).until(() -> !once.get());
        awaitCaughtUp(PROJECTION);
        assertThat(foldDifferences()).as("the rolled-back page left no output").isEmpty();
        assertThat(handlerCalls).as("the page ran, rolled back, and ran again from the reset point").containsExactly(first, first + 1, first + 2, first, first + 1, first + 2);
        assertThat(queryLong("SELECT reset_epoch FROM " + EventLogSchema.PROGRESS_TABLE)).isEqualTo(1);
    }
}
