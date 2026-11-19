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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attach first, then bridge {@code (P, C]}, then drain; each bridge page re-reads the attach holes at or
 * below its lower bound (I2; see docs/design/event-log.md §11); a row that both the bridge and the reader return is
 * handled once; no attach while the
 * type's watermark is unknown (F4).
 * <p>
 * The common shape: a keeper subscription makes the type active, a long writer holds a hole so the reader's cursor
 * {@code C} is ahead of its watermark {@code W}, and the subscription under test (catch-up and bridge page of 3)
 * starts afterwards, catches up to {@code W} and attaches. Hooks on the delivery thread put each race at a fixed
 * point. Must-fail variants: {@link WatermarkMutant#ATTACH_WITHOUT_BRIDGE}, {@link WatermarkMutant#BRIDGE_BEFORE_ATTACH},
 * {@link WatermarkMutant#BRIDGE_WITHOUT_HOLE_REREAD}, {@link WatermarkMutant#DRAIN_BEFORE_BRIDGE},
 * {@link WatermarkMutant#NO_DEDUPE}, {@link WatermarkMutant#ATTACH_WITH_UNKNOWN_WATERMARK} and
 * {@link WatermarkMutant#CATCH_UP_ABOVE_WATERMARK}.
 */
class EventLogAttachIT extends AbstractSubscriptionIT {
    private static final int PAGE = 3;

    @BeforeEach
    void smallPages() throws SQLException {
        restartEventLog(dataSource, FAST.withCatchUpPageSize(PAGE));
    }

    // ---- (W, C] is bridged ----

    @Test
    void rows_between_the_watermark_and_the_cursor_are_bridged() throws Exception {
        assertThat(missedBetweenWatermarkAndCursor()).isEmpty();
    }

    @Test
    void must_fail_attach_without_bridge_loses_the_rows_between_the_watermark_and_the_cursor() throws Exception {
        assertThat(missedBetweenWatermarkAndCursor(WatermarkMutant.ATTACH_WITHOUT_BRIDGE)).isNotEmpty();
    }

    private Set<Long> missedBetweenWatermarkAndCursor(WatermarkMutant... mutants) throws Exception {
        var shape    = longWriterShape();
        var recorder = new Recorder();
        var tested   = subscribe("tested", recorder, new TestSeams(Set.of(mutants), TestSeams.Hooks.NONE, null));
        awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED);
        awaitDelivered(recorder, commitAppend("tail", 1));
        var missed = new TreeSet<>(shape.committedAboveWatermark);
        missed.removeAll(recorder.deliveredOrders());
        shape.longWriter.rollback();
        return missed;
    }

    // ---- attach first, then bridge ----

    @Test
    void a_read_between_the_end_of_catch_up_and_the_attach_is_bridged() throws Exception {
        assertThat(readBeforeAttachDelivered()).isTrue();
    }

    @Test
    void must_fail_bridge_before_attach_loses_the_read_in_between() throws Exception {
        assertThat(readBeforeAttachDelivered(WatermarkMutant.BRIDGE_BEFORE_ATTACH)).isFalse();
    }

    /**
     * At {@code beforeAttachRequest} a writer commits and the reader reads it, so the reader's cursor moves after the
     * catch-up ended and before the attach
     */
    private boolean readBeforeAttachDelivered(WatermarkMutant... mutants) throws Exception {
        var shape    = longWriterShape();
        var recorder = new Recorder();
        var between  = new AtomicLong(-1);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeAttachRequest(String subscriber) throws Exception {
                if (between.get() < 0) {
                    between.set(commitAppend("between", 1));
                    await(() -> eventLog.reader(TYPE) != null && eventLog.reader(TYPE).state().cursor() >= between.get());
                }
            }
        };
        var tested = subscribe("tested", recorder, new TestSeams(Set.of(mutants), hooks, null));
        awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED);
        awaitDelivered(recorder, commitAppend("tail", 1));
        shape.longWriter.rollback();
        assertThat(between.get()).as("the hook ran").isPositive();
        return recorder.deliveredOrder(between.get());
    }

    // ---- per-aggregate order across bridge pages (I2) ----

    @ParameterizedTest(name = "{0} events per transaction")
    @ValueSource(ints = {1, 3})
    void a_late_commit_into_an_attach_hole_is_delivered_before_its_successor_in_a_later_page(int eventsPerTransaction) throws Exception {
        assertThat(lateCommitIntoAnAttachHole(eventsPerTransaction)).isEmpty();
    }

    @ParameterizedTest(name = "{0} events per transaction")
    @ValueSource(ints = {1, 3})
    void must_fail_bridge_pages_without_the_hole_reread_let_the_successor_overtake(int eventsPerTransaction) throws Exception {
        assertThat(lateCommitIntoAnAttachHole(eventsPerTransaction, WatermarkMutant.BRIDGE_WITHOUT_HOLE_REREAD)).isNotEmpty();
    }

    /**
     * A#0.. is held open in hole {@code h}, a second writer holds the values above three committed rows. At the first
     * {@code betweenBridgePages} (after page 1 missed {@code h}) the second writer rolls back, the sequence is set back
     * onto its values, A#0.. commits and A's successors are appended into those values: at or below {@code C}, in
     * page 2's range. Only page 2's re-read of {@code h} hands A#0.. on before them.
     *
     * @return I2 violations
     */
    private List<String> lateCommitIntoAnAttachHole(int eventsPerTransaction, WatermarkMutant... mutants) throws Exception {
        keeper();
        awaitOffered(commitAppend("pre", 1));
        var aFirst = openAppend(AppendRequest.afterEventOrder(TYPE, "A", AppendRequest.NEW_STREAM, events("E", eventsPerTransaction)));
        commitAppend("x", PAGE);
        var reserved = openAppend("reserved", eventsPerTransaction);
        var lastY    = commitAppend("y", PAGE) + PAGE - 1;
        awaitOffered(lastY);
        var recorder  = new Recorder();
        var successor = new AtomicReference<AppendResult>();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void betweenBridgePages(String subscriber, long lowerBound) throws Exception {
                if (successor.get() == null) {
                    reserved.rollback();
                    execute("SELECT setval(pg_get_serial_sequence('" + tables.eventTable() + "', 'global_order'), " + (reserved.globalOrder() - 1) + ")");
                    aFirst.commit();
                    successor.set(appendCommitted(eventLog, AppendRequest.afterEventOrder(TYPE, "A", eventsPerTransaction - 1, events("E", eventsPerTransaction))).getFirst());
                }
            }
        };
        subscribe("tested", recorder, new TestSeams(Set.of(mutants), hooks, null));
        await(() -> successor.get() != null);
        assertThat(successor.get().events().getFirst().globalOrder()).as("the successor lands at or below C").isLessThanOrEqualTo(lastY);
        awaitDelivered(recorder, aFirst.result().events().stream().mapToLong(AppendedEvent::globalOrder).toArray());
        awaitDelivered(recorder, successor.get().events().stream().mapToLong(AppendedEvent::globalOrder).toArray());
        return perAggregateOrderViolations(recorder.delivered);
    }

    @Test
    void the_bridge_is_read_before_the_inbox_is_drained() throws Exception {
        assertThat(successorReadIntoTheInboxDuringTheBridge()).isEmpty();
    }

    @Test
    void must_fail_draining_before_the_bridge_lets_the_successor_overtake() throws Exception {
        assertThat(successorReadIntoTheInboxDuringTheBridge(WatermarkMutant.DRAIN_BEFORE_BRIDGE)).isNotEmpty();
    }

    /**
     * A#0 lies in page 2 of the bridge; at the first {@code betweenBridgePages} A#1 is appended above {@code C} and
     * the reader offers it to the inbox
     *
     * @return I2 violations
     */
    private List<String> successorReadIntoTheInboxDuringTheBridge(WatermarkMutant... mutants) throws Exception {
        var shape    = longWriterShape();
        var recorder = new Recorder();
        var aNext    = new AtomicLong(-1);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void betweenBridgePages(String subscriber, long lowerBound) throws Exception {
                if (aNext.get() < 0) {
                    aNext.set(appendCommitted(eventLog, AppendRequest.afterEventOrder(TYPE, "A", 0, events("E", 1))).getFirst().events().getFirst().globalOrder());
                    awaitOffered(aNext.get());
                }
            }
        };
        subscribe("tested", recorder, new TestSeams(Set.of(mutants), hooks, null));
        await(() -> aNext.get() > 0);
        assertThat(aNext.get()).isGreaterThan(shape.cursor);
        awaitDelivered(recorder, shape.aFirst, aNext.get());
        shape.longWriter.rollback();
        return perAggregateOrderViolations(recorder.delivered);
    }

    // ---- no duplicate at the overlap ----

    @Test
    void a_late_commit_returned_by_both_the_bridge_and_the_reader_is_handled_once() throws Exception {
        assertThat(deliveriesOfALateCommitAtTheOverlap()).isEqualTo(1);
    }

    @Test
    void must_fail_without_dedupe_the_late_commit_is_handled_twice() throws Exception {
        assertThat(deliveriesOfALateCommitAtTheOverlap(WatermarkMutant.NO_DEDUPE)).isEqualTo(2);
    }

    /**
     * At the first {@code betweenBridgePages} the long writer commits into its attach hole and the reader's re-read
     * offers it to the inbox; page 2 re-reads it too
     */
    private long deliveriesOfALateCommitAtTheOverlap(WatermarkMutant... mutants) throws Exception {
        var shape     = longWriterShape();
        var recorder  = new Recorder();
        var committed = new AtomicBoolean();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void betweenBridgePages(String subscriber, long lowerBound) throws Exception {
                if (!committed.getAndSet(true)) {
                    shape.longWriter.commit();
                    awaitOffered(shape.longWriter.globalOrder());
                }
            }
        };
        var tested = subscribe("tested", recorder, new TestSeams(Set.of(mutants), hooks, null));
        awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED);
        awaitDelivered(recorder, commitAppend("tail", 1));
        awaitTrue(tested::isIdle);
        assertThat(committed).isTrue();
        return recorder.delivered.stream().filter(e -> e.globalOrder() == shape.longWriter.globalOrder()).count();
    }

    // ---- attach only with a known watermark (F4) ----

    @Test
    void no_attach_until_the_initial_candidate_retires_but_by_hand_off_and_the_held_value_is_delivered() throws Exception {
        var result = startWhileAWriterHoldsAValueBelowTheHead();
        // FRESH may attach under the unknown watermark only to hand its gaps off, never as an ordinary attach
        assertThat(result.attachesWhileHeld).isEqualTo(result.unknownAttachesWhileHeld);
        assertThat(result.heldDelivered).isTrue();
    }

    @Test
    void must_fail_attach_with_an_unknown_watermark_loses_the_held_value() throws Exception {
        assertThat(startWhileAWriterHoldsAValueBelowTheHead(WatermarkMutant.ATTACH_WITH_UNKNOWN_WATERMARK).heldDelivered).isFalse();
    }

    @Test
    void must_fail_catch_up_taken_as_final_above_the_unknown_watermark_loses_the_held_value() throws Exception {
        assertThat(startWhileAWriterHoldsAValueBelowTheHead(WatermarkMutant.CATCH_UP_ABOVE_WATERMARK).heldDelivered).isFalse();
    }

    private record HeldResult(long attachesWhileHeld, long unknownAttachesWhileHeld, boolean heldDelivered) {
    }

    /**
     * The type starts (its first subscription) while a writer holds a value below the head, so the reader's initial
     * candidate leaves the watermark unknown
     */
    private HeldResult startWhileAWriterHoldsAValueBelowTheHead(WatermarkMutant... mutants) throws Exception {
        commitAppend("pre", 1);
        var held = openAppend("held", 1);
        commitAppend("above", 2);
        var recorder = new Recorder();
        var tested   = subscribe("tested", recorder, mutants);
        awaitTrue(() -> tested.phase() == Subscription.Phase.WAITING_FOR_WATERMARK || tested.phase() == Subscription.Phase.ATTACHED);
        awaitReads(3);
        var attaches        = reads.attaches.get();
        var unknownAttaches = reads.unknownAttaches.get();
        held.commit();
        awaitDelivered(recorder, commitAppend("tail", 1));
        return new HeldResult(attaches, unknownAttaches, recorder.deliveredOrder(held.globalOrder()));
    }

    // ---- shared shape ----

    /**
     * @param longWriter              holds {@code l}, the lowest hole: the watermark is {@code l - 1}
     * @param committedAboveWatermark the rows in {@code (W, C]}: three {@code x}, then A#0 and two {@code y}
     * @param aFirst                  A#0, in bridge page 2
     * @param cursor                  {@code C} once the reader has read them
     */
    private record LongWriterShape(OpenWriter longWriter, Set<Long> committedAboveWatermark, long aFirst, long cursor) {
    }

    private LongWriterShape longWriterShape() throws Exception {
        keeper();
        awaitOffered(commitAppend("pre", 1));
        var longWriter = openAppend("long", 1);
        var x          = commitAppend("x", PAGE);
        var aFirst     = appendCommitted(eventLog, AppendRequest.afterEventOrder(TYPE, "A", AppendRequest.NEW_STREAM, events("E", 1))).getFirst().events().getFirst().globalOrder();
        var y          = commitAppend("y", 2);
        awaitOffered(y + 1);
        var above = new TreeSet<>(List.of(x, x + 1, x + 2, aFirst, y, y + 1));
        assertThat(eventLog.reader(TYPE).state().holes()).contains(longWriter.globalOrder());
        return new LongWriterShape(longWriter, above, aFirst, y + 1);
    }

    /** The type's first subscription: its reader runs with no seams */
    private void keeper() throws SQLException {
        subscribe("keeper", new Recorder());
    }

    private void await(java.util.function.BooleanSupplier condition) {
        awaitTrue(condition);
    }
}
