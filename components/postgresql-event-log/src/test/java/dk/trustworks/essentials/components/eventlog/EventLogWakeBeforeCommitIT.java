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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A wake that reads before the commit loses nothing, and I11 holds inside a flush of
 * several types - one running set, read after every type's snapshot.
 * <p>
 * The flush scenario: types {@code WakeA}, {@code WakeH} and {@code WakeB} are activated in that order, so a flush reads
 * them in that order; {@code WakeH} is read through a view that holds the scan (P0.2's hold-inside-scan). A long writer
 * L holds a value of {@code WakeH}, so {@code WakeH} wants a running set. {@code afterCommit} for all three types is
 * called before any of the writers below commits (repeated until one flush reads all three, as the wakes are set one
 * by one; the hold is armed and {@code WakeH}'s row committed from that flush's hook); the flush blocks inside {@code WakeH}'s scan, and meanwhile a writer
 * W takes a value of {@code WakeB} and a value above it commits. So W started after every statement before
 * {@code WakeH}'s and before {@code WakeB}'s snapshot. L then rolls back, {@code WakeB} is probed, and only then W
 * commits. Must-fail variants: {@link WatermarkMutant#RUNNING_SET_FIRST_IN_FLUSH} (the set read before W started),
 * {@link WatermarkMutant#RUNNING_SET_PER_TYPE_IN_FLUSH} ({@code WakeB}'s set is the first one, cached for the flush's
 * implicit transaction) and {@link WatermarkMutant#WAKE_ADVANCES_WATERMARK}: each retires or drops W's hole before W
 * commits, and W's event is lost.
 */
class EventLogWakeBeforeCommitIT extends AbstractSubscriptionIT {
    static final String A = "WakeA";
    static final String H = "WakeH";
    static final String B = "WakeB";

    static final EventLogSettings SETTINGS = FAST.withBackstopInterval(Duration.ofSeconds(30))
                                                 .withProbeBackoff(Duration.ofMillis(10), Duration.ofMillis(20))
                                                 .withWokenProbeMin(Duration.ofMillis(10))
                                                 // only wakes may make H probe: uncapped, its backstop-floored wait is the 30 s backstop
                                                 .withHoleProbeWaitMax(Duration.ofSeconds(30));

    @Test
    void a_writer_starting_inside_a_flush_is_delivered_in_both_modes() throws Exception {
        assertThat(writerInsideFlush(null)).containsExactly(true, true);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = WatermarkMutant.class, names = {"RUNNING_SET_FIRST_IN_FLUSH", "RUNNING_SET_PER_TYPE_IN_FLUSH", "WAKE_ADVANCES_WATERMARK"})
    void must_fail_the_writer_is_lost(WatermarkMutant mutant) throws Exception {
        assertThat(writerInsideFlush(mutant)).containsExactly(false, false);
    }

    /**
     * @return whether W's event reached the {@code FRESH} and the {@code CONTIGUOUS} subscription of {@code WakeB}
     */
    private List<Boolean> writerInsideFlush(WatermarkMutant mutant) throws Exception {
        restartEventLog(dataSource, SETTINGS);
        var tablesH = eventLog.registerAggregateType(H);
        eventLog.registerAggregateType(A);
        eventLog.registerAggregateType(B);
        var heldView = "el_wakeh_events_held";
        execute("CREATE VIEW " + heldView + " AS SELECT * FROM " + tablesH.eventTable() + " WHERE el_test_scan_hold()");

        var flushes = new CopyOnWriteArrayList<Boolean>();
        var bProbes = new AtomicLong();
        // Run on the loop thread just before the first 3-type flush with a running set sends its statements
        var onFlush = new AtomicReference<Callable<?>>();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeFlush(List<String> aggregateTypes, boolean wantsRunningSet) throws Exception {
                if (aggregateTypes.size() == 3) {
                    flushes.add(wantsRunningSet);
                    var action = wantsRunningSet ? onFlush.getAndSet(null) : null;
                    if (action != null) {
                        action.call();
                    }
                } else if (aggregateTypes.equals(List.of(B)) && wantsRunningSet) {
                    bProbes.incrementAndGet();
                }
            }
        };
        var mutants    = mutant == null ? Set.<WatermarkMutant>of() : Set.of(mutant);
        var recorderA  = new Recorder();
        var recorderH  = new Recorder();
        var recorderB  = new Recorder();
        var contiguous = new ConcurrentSkipListSet<Long>();
        subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("a", A, "node-1"), 1, recorderA, new TestSeams(mutants, hooks, null)));
        awaitTrue(() -> reads.attaches.get() >= 1);
        subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("h", H, "node-1"), 1, recorderH, new TestSeams(mutants, hooks, heldView)));
        awaitTrue(() -> reads.attaches.get() >= 2);
        subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("b", B, "node-1"), 1, recorderB, new TestSeams(mutants, hooks, null)));
        subscribe(Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key("b-contiguous", B, "node-1"), 1, ConsistencyMode.CONTIGUOUS,
                                                              (page, tx) -> page.forEach(event -> contiguous.add(event.globalOrder())), TestSeams.NONE));
        awaitTrue(() -> reads.attaches.get() >= 4);

        // L holds a value of WakeH: a hole with a candidate, so WakeH wants a running set
        var longWriter = openAppend(AppendRequest.withoutExpectedOrder(H, "long", events("E", 1)));
        var lXid       = currentXid(longWriter.connection());
        appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(H, "h1", events("E", 1)));
        eventLog.afterCommit(List.of(H));
        awaitTrue(() -> eventLog.reader(H) != null && eventLog.reader(H).state().candidates().stream().anyMatch(c -> c.runningXids().contains(lXid)));
        // Past the woken probe wait (at most probeBackoffMax, 20 ms), so the flush may read a running set
        await().pollDelay(Duration.ofMillis(100)).atMost(WAIT).until(() -> true);

        // afterCommit wakes the types one by one, and each wake unparks the loop: the loop may take A's flag before H's
        // and B's are set and read them in two flushes. So the hold is armed and h2 committed from the hook, on the loop
        // thread, by the first flush that does read all three types and a running set; until one does, wake them again.
        // h2 is then the row the flush's H statement scans, and no earlier read of H can have taken it
        var holder = connection();
        onFlush.set(() -> {
            try (var statement = holder.createStatement()) {
                statement.execute("SELECT pg_advisory_lock(" + SCAN_KEY + ")");
            }
            return appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(H, "h2", events("E", 1)));
        });
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            if (onFlush.get() != null) {
                eventLog.afterCommit(List.of(A, H, B));
            }
            return onFlush.get() == null;
        });
        awaitScanBlocked();
        assertThat(flushes).as("the blocked flush reads all three types and a running set").last().isEqualTo(true);
        var w      = openAppend(AppendRequest.withoutExpectedOrder(B, "w", events("E", 1)));
        var gW     = w.globalOrder();
        var bAbove = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(B, "b-above", events("E", 1))).getFirst().events().getFirst().globalOrder();
        releaseScan(holder);
        awaitDelivered(recorderB, bAbove);
        assertThat(recorderB.deliveredOrder(gW)).isFalse();

        // L ends while W runs; WakeB is probed twice (a candidate without W retires, and its hole read misses W's value),
        // or has no hole left to probe for
        longWriter.rollback();
        var probed = bProbes.get();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(15)).until(() -> {
            eventLog.afterCommit(List.of(B));
            var state = eventLog.reader(B).state();
            return bProbes.get() >= probed + 2 || state.holes().isEmpty() && state.candidates().isEmpty();
        });
        w.commit();
        var marker = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(B, "marker", events("E", 1))).getFirst().events().getFirst().globalOrder();
        eventLog.afterCommit(List.of(B));
        awaitDelivered(recorderB, marker);
        await().atMost(WAIT).until(() -> contiguous.contains(marker));
        return List.of(recorderB.deliveredOrder(gW), contiguous.contains(gW));
    }

    @Test
    void a_wake_before_the_commit_loses_nothing() throws Exception {
        restartEventLog(dataSource, SETTINGS);
        var recorder = new Recorder();
        subscribe("sub", recorder, TestSeams.NONE);
        awaitTrue(() -> reads.attaches.get() >= 1);
        var w     = openAppend("w", 1);
        var above = commitAppend("above", 1);
        // Misplaced: before W's commit. The woken read finds the row above and a hole at W's value
        eventLog.afterCommit(List.of(TYPE));
        awaitDelivered(recorder, above);
        assertThat(eventLog.reader(TYPE).state().holes()).contains(w.globalOrder());
        w.commit();
        eventLog.afterCommit(List.of(TYPE));
        awaitDelivered(recorder, w.globalOrder());
        assertThat(recorder.delivered.stream().filter(e -> e.globalOrder() == w.globalOrder())).hasSize(1);
    }

    @Test
    void a_rollback_after_a_wake_retires_its_hole_and_holds_nothing_up() throws Exception {
        restartEventLog(dataSource, SETTINGS);
        var recorder = new Recorder();
        subscribe("sub", recorder, TestSeams.NONE);
        awaitTrue(() -> reads.attaches.get() >= 1);
        var w     = openAppend("w", 1);
        var above = commitAppend("above", 1);
        eventLog.afterCommit(List.of(TYPE));
        awaitDelivered(recorder, above);
        w.rollback();
        var marker = commitAppend("marker", 1);
        await().atMost(WAIT).pollInterval(Duration.ofMillis(15)).until(() -> {
            eventLog.afterCommit(List.of(TYPE));
            var state = eventLog.reader(TYPE).state();
            return state.holes().isEmpty() && state.candidates().isEmpty() && recorder.deliveredOrder(marker);
        });
        assertThat(eventLog.reader(TYPE).state().watermark()).isGreaterThanOrEqualTo(marker);
        assertThat(recorder.deliveredOrder(w.globalOrder())).isFalse();
    }
}
