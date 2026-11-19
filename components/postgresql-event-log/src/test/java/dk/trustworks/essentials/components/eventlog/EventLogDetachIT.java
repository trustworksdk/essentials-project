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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A subscription detached by an overflow resumes at the F5 position, {@code min(received watermark,
 * lowest unhandled received - 1) + 1}, so the unhandled rows of the batch it was working through and of its discarded
 * inbox are read again; and a superseding reset while attached detaches it and restarts delivery at the reset point.
 * Must-fail variant: {@link WatermarkMutant#DETACH_RESUMES_AT_WATERMARK}.
 */
class EventLogDetachIT extends AbstractSubscriptionIT {
    private static final int BURST = 16;

    @Test
    void a_detach_after_overflow_resumes_at_the_lowest_unhandled_event() throws Exception {
        var result = overflowWhileTheHandlerBlocks();
        assertThat(result.lost).isEmpty();
        assertThat(result.reasons).contains(Inbox.CloseReason.OVERFLOW.name());
        assertThat(result.savedBeforeSecondHandled).isNotEmpty().as("the progress row never passes g2 while it is unhandled").allMatch(saved -> saved <= result.g1 + 1);
        assertThat(result.firstDeliveries).as("every event first delivered in order").isSorted();
    }

    @Test
    void must_fail_a_detach_that_resumes_at_the_received_watermark_loses_the_unhandled_rows_of_its_batch() throws Exception {
        assertThat(overflowWhileTheHandlerBlocks(WatermarkMutant.DETACH_RESUMES_AT_WATERMARK).lost).isNotEmpty();
    }

    private record OverflowResult(long g1, Set<Long> lost, List<String> reasons, List<Long> savedBeforeSecondHandled, List<Long> firstDeliveries) {
    }

    /**
     * Buffer 10, tail page 5. Sixteen events commit together: the reader offers {@code g1..g5}, the handler blocks on
     * {@code g1}, and only then (the loop held at {@code afterReadBeforePublish}) {@code g6..g10} and {@code g11..g15}
     * fill the inbox and {@code g16} overflows it. Released, the subscription detaches.
     */
    private OverflowResult overflowWhileTheHandlerBlocks(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FAST.withTailPageSize(5).withSubscriptionBufferSize(10));
        var recorder = new Recorder();
        recorder.blockAtAggregate = "burst";
        var held = new AtomicBoolean();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void afterReadBeforePublish(String aggregateType, List<StoredEvent> rows) throws Exception {
                if (!rows.isEmpty() && rows.getFirst().aggregateId().equals("burst") && rows.getFirst().eventOrder() > 0 && !held.getAndSet(true)) {
                    assertThat(recorder.blocked.await(30, TimeUnit.SECONDS)).isTrue();
                }
            }
        };
        var savedBeforeSecond = new java.util.concurrent.atomic.AtomicReference<List<Long>>(List.of());
        PeriodicHandler handler = event -> {
            // Saves run on this (the delivery) thread: every save made before g2 is handled is in the list now
            if (event.aggregateId().equals("burst") && event.eventOrder() == 1 && !recorder.deliveredOrder(event.globalOrder())) {
                savedBeforeSecond.set(List.copyOf(reads.saves));
            }
            recorder.handle(event);
        };
        var subscription = subscribe("sub", handler, new TestSeams(Set.of(mutants), hooks, null), 1);
        awaitTrue(subscription::isIdle);
        var g1 = commitAppend("burst", BURST);
        awaitOffered(g1 + BURST - 1);
        assertThat(subscription.inbox().closeReason()).isEqualTo(Inbox.CloseReason.OVERFLOW);
        assertThat(recorder.delivered).as("only g1 is in the handler").hasSize(1);
        recorder.blockAtAggregate = null;
        recorder.release.countDown();
        awaitDelivered(recorder, commitAppend("tail", 1));
        var lost = new TreeSet<Long>();
        for (var g = g1; g < g1 + BURST; g++) {
            lost.add(g);
        }
        lost.removeAll(recorder.deliveredOrders());
        var first = new ArrayList<Long>();
        var seen  = new HashSet<Long>();
        recorder.delivered.forEach(e -> {
            if (seen.add(e.globalOrder())) {
                first.add(e.globalOrder());
            }
        });
        return new OverflowResult(g1, lost, List.copyOf(reads.detachReasons), savedBeforeSecond.get(), first);
    }

    /**
     * The reset test of ProgressStoreIT, on an attached subscription: the reset lands while the subscription is attached
     */
    @Test
    void a_reset_while_attached_detaches_and_restarts_delivery_at_the_reset_point() throws Exception {
        var recorder     = new Recorder();
        var subscription = subscribe("sub", recorder);
        var first        = commitAppend("A", 3);
        awaitDelivered(recorder, first, first + 1, first + 2);
        await().atMost(WAIT).until(() -> savedResumeFrom("sub") == first + 3);
        awaitTrue(() -> subscription.phase() == Subscription.Phase.ATTACHED);
        execute("UPDATE " + EventLogSchema.PROGRESS_TABLE + " SET reset_epoch = reset_epoch + 1, resume_from = " + first);
        var next = commitAppend("A", 1);
        await().atMost(Duration.ofSeconds(30)).until(() -> recorder.delivered.stream().filter(e -> e.globalOrder() == first).count() == 2);
        awaitDelivered(recorder, next);
        var orders     = recorder.delivered.stream().map(StoredEvent::globalOrder).toList();
        // The save that meets the reset follows the handling of the next event, so that one may come first
        var afterReset = orders.subList(orders.lastIndexOf(first), orders.size());
        assertThat(afterReset).as("delivery restarts at the reset point").startsWith(first, first + 1, first + 2);
        assertThat(reads.detachReasons).contains(Inbox.CloseReason.RESET.name());
        assertThat(queryLong("SELECT reset_epoch FROM " + EventLogSchema.PROGRESS_TABLE)).isEqualTo(1);
        await().atMost(WAIT).until(() -> savedResumeFrom("sub") == next + 1);
    }

    // ---- the FRESH bridge starts above the highest handled order ----

    @Test
    void after_an_overflow_the_bridge_starts_above_the_highest_handled_order_and_re_reads_the_gaps() throws Exception {
        var result = lateCommitDiscardedByAnOverflow();
        assertThat(result.lateDeliveries).as("the late commit, discarded with the inbox, delivered once").isEqualTo(1);
        assertThat(result.droppedAsHandled).as("bridge rows dropped as already handled").isZero();
    }

    @Test
    void must_fail_a_bridge_from_the_highest_handled_order_without_the_gaps_loses_the_late_commit() throws Exception {
        assertThat(lateCommitDiscardedByAnOverflow(WatermarkMutant.BRIDGE_FROM_HIGHEST_WITHOUT_GAPS).lateDeliveries).isZero();
    }

    private record LateCommitResult(long lateDeliveries, long droppedAsHandled) {
    }

    /**
     * Buffer 10, tail page 5. {@code g0} and {@code g1} are held open; five events above them are handled. The handler
     * blocks on the next event; {@code g1} commits, and its late row sits in the inbox until fifteen more events overflow
     * it. Released, the subscription detaches with {@code g1} unhandled below the highest handled order, and {@code g0}
     * still holding the watermark below both.
     */
    private LateCommitResult lateCommitDiscardedByAnOverflow(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FAST.withTailPageSize(5).withSubscriptionBufferSize(10));
        var recorder     = new Recorder();
        var subscription = subscribe("sub", recorder, mutants);
        awaitTrue(subscription::isIdle);
        openAppend("A", 1);
        var late = openAppend("B", 1);
        var g2   = commitAppend("C", 5);
        awaitDelivered(recorder, g2, g2 + 4);
        recorder.blockAtAggregate = "D";
        commitAppend("D", 1);
        assertThat(recorder.blocked.await(30, TimeUnit.SECONDS)).isTrue();
        late.commit();
        awaitOffered(late.globalOrder());
        var gE = commitAppend("E", 15);
        awaitOffered(gE + 14);
        assertThat(subscription.inbox().closeReason()).isEqualTo(Inbox.CloseReason.OVERFLOW);
        recorder.blockAtAggregate = null;
        recorder.release.countDown();
        awaitDelivered(recorder, gE + 14);
        deliveredWithin(recorder, late.globalOrder());
        var lateDeliveries = recorder.delivered.stream().filter(e -> e.globalOrder() == late.globalOrder()).count();
        return new LateCommitResult(lateDeliveries, subscription.bridgeRowsDroppedAsHandled());
    }
}
