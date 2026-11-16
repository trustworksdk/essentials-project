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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design phase 4 §2.3, §2.5, §7.1, §8.2: a {@code CONTIGUOUS} subscription detaches only between pages, so an overflow
 * while a page is open keeps that page's rows pending if it rolls back and drops them if it commits; held-back rows
 * count against the inbox's bound, so behind a long writer the subscription overflows and re-attaches - with no
 * statement while the writer runs - instead of growing without bound; and the reader loop never waits for it (I9).
 * Must-fail variants: {@link WatermarkMutant#REREAD_WITHOUT_APPLIED_DROP}, {@link WatermarkMutant#DETACH_POSITION_PAST_OPEN_PAGE},
 * {@link WatermarkMutant#HELD_NOT_COUNTED}, {@link WatermarkMutant#BACK_PRESSURE}.
 */
class EventLogContiguousDetachIT extends AbstractPageIT {
    private static final int      BURST    = 16;
    private static final String   NAME     = "proj";
    /** How long the must-fail variant of I9 gives the other subscription; it receives in about 100 ms when correct */
    private static final Duration STARVING = Duration.ofSeconds(3);

    // ---- detach with a page open ----

    @Test
    void a_page_open_during_an_overflow_that_commits_is_not_applied_again() throws Exception {
        assertThat(pageOpenDuringAnOverflow(false)).isEmpty();
    }

    @Test
    void must_fail_catching_up_from_before_the_committed_page_applies_it_twice() throws Exception {
        assertThat(pageOpenDuringAnOverflow(false, WatermarkMutant.REREAD_WITHOUT_APPLIED_DROP)).isNotEmpty();
    }

    @Test
    void a_page_open_during_an_overflow_that_rolls_back_is_applied_after_the_detach() throws Exception {
        assertThat(pageOpenDuringAnOverflow(true)).isEmpty();
    }

    @Test
    void must_fail_a_detach_position_past_the_open_page_loses_it_when_it_rolls_back() throws Exception {
        assertThat(pageOpenDuringAnOverflow(true, WatermarkMutant.DETACH_POSITION_PAST_OPEN_PAGE)).isNotEmpty();
    }

    /**
     * Buffer 10, tail page 5. Sixteen events commit together: the first batch's five are released and their page blocks
     * in {@code beforePageHandler}, still counted in the inbox; only then (the loop held at {@code afterReadBeforePublish})
     * do the next two batches arrive and overflow it. Released, the page
     * commits or (once) throws, and the subscription detaches.
     */
    private List<String> pageOpenDuringAnOverflow(boolean throwOnce, WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FAST.withTailPageSize(5).withSubscriptionBufferSize(10));
        var blockOnce = new AtomicBoolean(true);
        var throwFlag = new AtomicBoolean(throwOnce);
        var blocked   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var heldReader = new AtomicBoolean();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void afterReadBeforePublish(String aggregateType, List<StoredEvent> rows) throws Exception {
                // Reader loop (this is the type's first subscription): the second burst batch waits until the page is open
                if (!rows.isEmpty() && rows.getFirst().aggregateId().equals("burst") && rows.getFirst().eventOrder() > 0 && !heldReader.getAndSet(true)) {
                    assertThat(blocked.await(30, TimeUnit.SECONDS)).isTrue();
                }
            }

            @Override
            public void beforePageHandler(String subscriber, List<StoredEvent> page) throws Exception {
                if (page.getFirst().aggregateId().equals("burst") && blockOnce.getAndSet(false)) {
                    blocked.countDown();
                    assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                }
            }
        };
        var view = view();
        Subscription.PageHandler handler = (page, connection) -> {
            if (page.getFirst().aggregateId().equals("burst") && throwFlag.getAndSet(false)) {
                throw new IllegalStateException("fails once");
            }
            view.handle(page, connection);
        };
        var subscription = subscribe(Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key(NAME, TYPE, "node-1"), 1, Subscription.ConsistencyMode.CONTIGUOUS,
                                                                                 handler, new TestSeams(Set.of(mutants), hooks, null)));
        awaitTrue(subscription::isIdle);
        var g1 = commitAppend("burst", BURST);
        assertThat(blocked.await(30, TimeUnit.SECONDS)).isTrue();
        awaitOffered(g1 + BURST - 1);
        assertThat(subscription.inbox().closeReason()).isEqualTo(Inbox.CloseReason.OVERFLOW);
        release.countDown();
        commitAppend("tail", 1);
        awaitCaughtUp(NAME);
        return foldDifferences();
    }

    // ---- held-back rows count against the bound ----

    @Test
    void behind_a_long_writer_held_rows_overflow_and_re_attach_without_a_statement() throws Exception {
        var result = behindALongWriter();
        assertThat(result.overflows).as("overflows while the writer holds").isGreaterThanOrEqualTo(2);
        assertThat(result.readsWhileHeld).as("catch-up and bridge statements while the writer holds").isZero();
        assertThat(result.highWater).as("held-back rows").isLessThanOrEqualTo(10);
        assertThat(result.differences).isEmpty();
    }

    @Test
    void must_fail_held_back_rows_not_counted_grow_past_the_bound() throws Exception {
        assertThat(behindALongWriter(WatermarkMutant.HELD_NOT_COUNTED).highWater).isGreaterThan(10);
    }

    private record LongWriterResult(long overflows, long readsWhileHeld, int highWater, List<String> differences) {
    }

    /** Buffer 10, tail page 5; a writer holds a value while three transactions of 15 events commit above it */
    private LongWriterResult behindALongWriter(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FAST.withTailPageSize(5).withSubscriptionBufferSize(10));
        var subscription = contiguous(NAME, mutants);
        awaitTrue(subscription::isIdle);
        var writer      = openAppend("held", 1);
        var readsBefore = reads.catchUpReads.get();
        for (var round = 0; round < 3; round++) {
            var first = commitAppend("x" + round, 15);
            awaitOffered(first + 14);
            awaitTrue(subscription::isIdle);
        }
        var readsWhileHeld = reads.catchUpReads.get() - readsBefore;
        var overflows      = reads.detachReasons.stream().filter(Inbox.CloseReason.OVERFLOW.name()::equals).count();
        writer.commit();
        awaitCaughtUp(NAME);
        return new LongWriterResult(overflows, readsWhileHeld, subscription.heldBackHighWater(), foldDifferences());
    }

    // ---- I9 with a CONTIGUOUS subscription ----

    @Test
    void a_contiguous_subscription_behind_a_long_writer_never_delays_another() throws Exception {
        assertThat(otherReceivesBehindAFullContiguousInbox()).isTrue();
    }

    @Test
    void must_fail_a_loop_that_waits_on_a_full_contiguous_inbox_stops_the_others() throws Exception {
        assertThat(otherReceivesBehindAFullContiguousInbox(WatermarkMutant.BACK_PRESSURE)).isFalse();
    }

    /**
     * The {@code CONTIGUOUS} subscription is the type's first, so the reader takes its mutants. A writer holds a value;
     * fifteen events commit above it: they fill its inbox, held back, and only it can never release them
     *
     * @return whether a {@code FRESH} subscription on the same type received the fifteen, and five more committed once the
     * contiguous inbox was full, meanwhile
     */
    private boolean otherReceivesBehindAFullContiguousInbox(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FAST.withTailPageSize(5).withSubscriptionBufferSize(10));
        var contiguous = contiguous(NAME, mutants);
        var other      = new Recorder();
        var fresh      = subscribe("other", other);
        awaitTrue(() -> contiguous.isIdle() && fresh.isIdle());
        var writer   = openAppend("held", 1);
        var first    = commitAppend("x", 15);
        var firstFifteen = java.util.stream.LongStream.range(first, first + 15).boxed().toList();
        // Either the other has the fifteen (correct, or the waiting loop offered it the last batch first), or the
        // contiguous inbox is full and the waiting loop is stuck on it
        awaitTrue(() -> other.deliveredOrders().containsAll(firstFifteen) || fullInbox(contiguous));
        // Committed after that: a loop that waits on the full inbox never reads these, whatever the attach order
        var later    = commitAppend("y", 5);
        var expected = java.util.stream.LongStream.concat(java.util.stream.LongStream.range(first, first + 15), java.util.stream.LongStream.range(later, later + 5))
                                                  .boxed().toList();
        var deadline = System.nanoTime() + STARVING.toNanos();
        var received = false;
        while (!received && System.nanoTime() < deadline) {
            received = other.deliveredOrders().containsAll(expected);
            Thread.sleep(10);
        }
        writer.commit();
        return received;
    }

    private static boolean fullInbox(Subscription subscription) {
        var inbox = subscription.inbox();
        return inbox != null && inbox.closeReason() == null && inbox.events() >= 10;
    }
}
