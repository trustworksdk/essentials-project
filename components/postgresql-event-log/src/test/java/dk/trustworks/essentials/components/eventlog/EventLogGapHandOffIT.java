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
import org.junit.jupiter.params.provider.EnumSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After a reader restart behind a writer that holds a value
 * below the head, a {@code FRESH} subscription that has waited one backstop interval attaches under the unknown
 * watermark, bridges, and hands the gaps it found to the reader, which re-reads them until its initial candidate
 * retires. The held value commits only once both subscriptions are attached and have handed off (asserted with the
 * reader's watermark still unknown), so it can reach them only through the hand-off. Must-fail variants:
 * {@link WatermarkMutant#GAPS_NOT_HANDED_OFF} and {@link WatermarkMutant#HANDED_GAPS_RESOLVED_AT_ONCE}; for the re-read
 * after the hand-off (I2: a predecessor in a gap ahead of its successor already in the inbox),
 * {@link WatermarkMutant#NO_REREAD_AFTER_HAND_OFF}. Above
 * {@link EventLogSettings#holeRangeThreshold()} gaps the subscription falls back to F4's wait.
 */
class EventLogGapHandOffIT extends AbstractSubscriptionIT {
    @Test
    void a_held_value_below_the_head_that_commits_after_the_hand_off_is_delivered_once() throws Exception {
        var outcome = heldAfterARestart();
        assertThat(outcome.heldDeliveries()).as("deliveries of the held value").isEqualTo(1);
        assertThat(outcome.duplicates()).as("events delivered twice").isEmpty();
        assertThat(outcome.handedOff()).as("hand-offs before the commit").contains("tested", "warm");
        assertThat(outcome.resumeWhileHeld()).as("the resume point while %d is held", outcome.held()).isLessThanOrEqualTo(outcome.held());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = WatermarkMutant.class, names = {"GAPS_NOT_HANDED_OFF", "HANDED_GAPS_RESOLVED_AT_ONCE"})
    void must_fail_the_held_value_is_lost(WatermarkMutant mutant) throws Exception {
        assertThat(heldAfterARestart(mutant).heldDeliveries()).isZero();
    }

    private record Outcome(long held, int heldDeliveries, List<Long> duplicates, List<String> handedOff, long resumeWhileHeld) {
    }

    /**
     * {@code warm} (the type's first subscription, carrying the reader's seams) is attached; a writer holds a value
     * below the head; the reader restarts; {@code tested} starts at 1. Both attach under the unknown watermark and hand
     * off; then the held value commits
     */
    private Outcome heldAfterARestart(WatermarkMutant... mutants) throws Exception {
        var seams = new TestSeams(Set.of(mutants), TestSeams.Hooks.NONE, null);
        var warm  = new Recorder();
        var w     = subscribe("warm", warm, seams);
        awaitDelivered(warm, commitAppend("pre", 1));
        var held  = openAppend("held", 1);
        var above = commitAppend("above", 2);
        awaitDelivered(warm, above, above + 1);
        terminateReaderBackend();
        awaitTrue(() -> reads.detachReasons.contains(Inbox.CloseReason.READER_RESTART.name()) && eventLog.reader(TYPE) != null);

        var recorder = new Recorder();
        var tested   = subscribe("tested", recorder, seams);
        var handing  = !Set.of(mutants).contains(WatermarkMutant.GAPS_NOT_HANDED_OFF);
        awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED && w.phase() == Subscription.Phase.ATTACHED
                && (!handing || reads.handedOffBy.containsAll(List.of("tested", "warm"))));
        awaitDelivered(recorder, above, above + 1);
        awaitTrue(tested::isIdle);
        assertThat(eventLog.reader(TYPE).state().watermark()).as("the restart's watermark is still unknown").isNull();
        assertThat(recorder.deliveredOrder(held.globalOrder())).isFalse();
        var handedOff       = List.copyOf(reads.handedOffBy);
        var resumeWhileHeld = savedResumeFrom("tested");

        held.commit();
        var tail = commitAppend("tail", 1);
        awaitDelivered(recorder, tail);
        awaitDelivered(warm, tail);
        awaitReads(3);
        var counts = new TreeMap<Long, Integer>();
        recorder.delivered.forEach(e -> counts.merge(e.globalOrder(), 1, Integer::sum));
        var duplicates = counts.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        return new Outcome(held.globalOrder(), counts.getOrDefault(held.globalOrder(), 0), duplicates, handedOff, resumeWhileHeld);
    }

    // ---- I2: the re-read after the hand-off takes a committed predecessor ahead of the inbox ----

    @Test
    void a_predecessor_that_commits_into_a_gap_before_the_hand_off_is_delivered_before_its_successor_in_the_inbox() throws Exception {
        var outcome = predecessorCommittedBeforeTheHandOff();
        assertThat(outcome.violations()).as("per-aggregate order violations").isEmpty();
        assertThat(outcome.predecessorDeliveries()).as("deliveries of the predecessor").isEqualTo(1);
        assertThat(outcome.successorDeliveries()).as("deliveries of the successor").isEqualTo(1);
    }

    @Test
    void must_fail_without_the_re_read_after_the_hand_off_the_successor_overtakes_its_predecessor() throws Exception {
        assertThat(predecessorCommittedBeforeTheHandOff(WatermarkMutant.NO_REREAD_AFTER_HAND_OFF).violations()).isNotEmpty();
    }

    private record OrderOutcome(List<String> violations, long predecessorDeliveries, long successorDeliveries) {
    }

    /**
     * {@code tested} is the type's only subscription. Two writers hold values below the head: {@code other} (it keeps
     * the restarted reader's initial candidate alive, so the reader keeps the handed-off gaps as holes) and the
     * predecessor {@code g0} of aggregate {@code agg}. The reader restarts; {@code tested} attaches under the unknown
     * watermark and bridges. Held at {@code beforeHandOff}, {@code g0} commits, its successor {@code g1} commits, and
     * the reader reads {@code g1} into the inbox. Only then does the hand-off reach the loop
     */
    private OrderOutcome predecessorCommittedBeforeTheHandOff(WatermarkMutant... mutants) throws Exception {
        var atHandOff = new CountDownLatch(1);
        var proceed   = new CountDownLatch(1);
        var first     = new AtomicBoolean(true);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeHandOff(String subscriber, List<HandledSet.Gap> gaps) throws Exception {
                if (first.compareAndSet(true, false)) {
                    atHandOff.countDown();
                    if (!proceed.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("not released");
                    }
                }
            }
        };
        var recorder = new Recorder();
        var tested   = subscribe("tested", recorder, new TestSeams(Set.of(mutants), hooks, null));
        awaitDelivered(recorder, commitAppend("pre", 1));
        var other = openAppend("other", 1);
        var g0    = openAppend("agg", 1);
        var above = commitAppend("above", 1);
        awaitDelivered(recorder, above);
        terminateReaderBackend();
        awaitTrue(() -> reads.detachReasons.contains(Inbox.CloseReason.READER_RESTART.name()) && eventLog.reader(TYPE) != null);

        assertThat(atHandOff.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("the hand-off is reached").isTrue();
        assertThat(tested.phase()).isEqualTo(Subscription.Phase.BRIDGING);
        g0.commit();
        var g1 = commitAppend("agg", 1);
        awaitOffered(g1);
        assertThat(recorder.deliveredOrder(g0.globalOrder()) || recorder.deliveredOrder(g1)).as("nothing delivered while held").isFalse();
        assertThat(eventLog.reader(TYPE).state().watermark()).as("the initial candidate is alive").isNull();
        proceed.countDown();

        awaitDelivered(recorder, g1);
        other.commit();
        var tail = commitAppend("tail", 1);
        awaitDelivered(recorder, g0.globalOrder(), tail);
        var delivered = List.copyOf(recorder.delivered);
        return new OrderOutcome(perAggregateOrderViolations(delivered),
                                delivered.stream().filter(e -> e.globalOrder() == g0.globalOrder()).count(),
                                delivered.stream().filter(e -> e.globalOrder() == g1).count());
    }

    // ---- fallback: more gaps than the threshold keep F4's wait ----

    @Test
    void more_gaps_than_the_threshold_fall_back_to_waiting_for_the_watermark() throws Exception {
        restartEventLog(dataSource, FAST.withHoleRangeThreshold(2));
        var warm = new Recorder();
        subscribe("warm", warm, TestSeams.NONE);
        awaitDelivered(warm, commitAppend("pre", 1));
        // Three rolled-back values and the held one: four gaps in (0, C] for a subscription starting at 1
        for (var i = 0; i < 3; i++) {
            openAppend("rolled-back-" + i, 1).rollback();
            commitAppend("between-" + i, 1);
        }
        var held  = openAppend("held", 1);
        var above = commitAppend("above", 1);
        awaitDelivered(warm, above);
        terminateReaderBackend();
        awaitTrue(() -> reads.detachReasons.contains(Inbox.CloseReason.READER_RESTART.name()) && eventLog.reader(TYPE) != null);

        var recorder = new Recorder();
        var tested   = subscribe("tested", recorder, TestSeams.NONE);
        awaitTrue(() -> reads.detachReasons.contains(Inbox.CloseReason.TOO_MANY_GAPS.name()) && tested.phase() == Subscription.Phase.WAITING_FOR_WATERMARK);
        awaitReads(5);
        assertThat(tested.phase()).as("it keeps waiting instead of attaching again").isEqualTo(Subscription.Phase.WAITING_FOR_WATERMARK);
        assertThat(reads.handedOffBy).doesNotContain("tested");
        assertThat(eventLog.reader(TYPE).state().watermark()).isNull();
        assertThat(savedResumeFrom("tested")).isEqualTo(1);

        held.commit();
        var tail = commitAppend("tail", 1);
        awaitDelivered(recorder, held.globalOrder(), tail);
        assertThat(recorder.delivered.stream().filter(e -> e.globalOrder() == held.globalOrder()).count()).isEqualTo(1);
    }
}
