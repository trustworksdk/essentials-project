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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 §7.1 (I13; see docs/design/event-log.md §11) and §7.3: filtered catch-up. A long writer holds a matching
 * event below the reader's cursor while a
 * filtered subscription catches up past it, with the reader running, starting under the writer (F4's wait) and
 * restarted (F4); a type first appended while the catch-up runs; a short page advances to the ceiling; a cancelled read
 * retries with a quarter of the window. Data sizes scale up with {@code -Dbenchmark.run=true}.
 */
class EventLogFilteredCatchUpIT extends AbstractSubscriptionIT {
    private static final int              SCALE    = Boolean.getBoolean("benchmark.run") ? 10 : 1;
    private static final EventTypeFilter  ONLY_X   = EventTypeFilter.of("X");
    private static final EventLogSettings FILTERED = FAST.withCatchUpPageSize(100).withCatchUpScanWindow(1_000, 1_000_000);

    enum Shape {
        /** The reader runs (a warm subscription) before the writer opens: the watermark stays below the held value */
        READER_RUNNING,
        /** The tested subscription is the type's first, started while the writer holds: the watermark is unknown (F4) */
        READER_STARTS_HELD,
        /** The reader is restarted while the writer holds (F4) */
        READER_RESTARTED
    }

    record Outcome(long held, Set<Long> missed, List<String> duplicatesOrForeign, List<String> orderViolations, long resumeWhileHeld) {
    }

    // ---- I13: a held matching event below the cursor ----

    @ParameterizedTest
    @CsvSource({"FRESH, false", "FRESH, true", "CONTIGUOUS, false", "CONTIGUOUS, true"})
    void a_held_matching_event_below_the_cursor_is_delivered_once(ConsistencyMode mode, boolean indexed) throws Exception {
        assertDeliveredOnce(heldMatchingEvent(mode, indexed, Shape.READER_RUNNING, Set.of(), Set.of()));
    }

    @ParameterizedTest
    @CsvSource({"FRESH", "CONTIGUOUS"})
    void must_fail_a_short_page_advancing_to_the_cursor_loses_the_held_event(ConsistencyMode mode) throws Exception {
        assertThat(heldMatchingEvent(mode, false, Shape.READER_RUNNING, Set.of(), Set.of(WatermarkMutant.FILTERED_CATCH_UP_TO_CURSOR)).missed()).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"FRESH", "CONTIGUOUS"})
    void a_filtered_subscription_started_under_a_long_writer_waits_for_the_watermark(ConsistencyMode mode) throws Exception {
        assertDeliveredOnce(heldMatchingEvent(mode, false, Shape.READER_STARTS_HELD, Set.of(), Set.of()));
    }

    @Test
    void must_fail_catching_up_above_the_unknown_watermark_loses_the_held_event() throws Exception {
        assertThat(heldMatchingEvent(ConsistencyMode.FRESH, false, Shape.READER_STARTS_HELD, Set.of(WatermarkMutant.CATCH_UP_ABOVE_WATERMARK), Set.of())
                           .missed()).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"FRESH", "CONTIGUOUS"})
    void a_filtered_subscription_behind_a_restarted_reader_gets_the_held_event_once(ConsistencyMode mode) throws Exception {
        assertDeliveredOnce(heldMatchingEvent(mode, false, Shape.READER_RESTARTED, Set.of(), Set.of()));
        // FRESH attaches under the unknown watermark and hands its gaps off before the held event commits;
        // CONTIGUOUS keeps F4's wait
        if (mode == ConsistencyMode.FRESH) {
            assertThat(reads.handedOffBy).contains("tested");
        } else {
            assertThat(reads.handedOffBy).doesNotContain("tested");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = WatermarkMutant.class, names = {"GAPS_NOT_HANDED_OFF", "HANDED_GAPS_RESOLVED_AT_ONCE"})
    void must_fail_a_filtered_subscription_behind_a_restarted_reader_loses_the_held_event_handed_off(WatermarkMutant mutant) throws Exception {
        // On both: the warm subscription hands off too, and it carries the reader's seams
        assertThat(heldMatchingEvent(ConsistencyMode.FRESH, false, Shape.READER_RESTARTED, Set.of(mutant), Set.of(mutant)).missed()).isNotEmpty();
    }

    @Test
    void must_fail_a_restarted_reader_at_the_head_loses_the_held_event() throws Exception {
        assertThat(heldMatchingEvent(ConsistencyMode.FRESH, false, Shape.READER_RESTARTED, Set.of(WatermarkMutant.RESTARTED_READER_AT_HEAD), Set.of())
                           .missed()).isNotEmpty();
    }

    private static void assertDeliveredOnce(Outcome outcome) {
        assertThat(outcome.missed()).as("matching events not delivered").isEmpty();
        assertThat(outcome.duplicatesOrForeign()).as("duplicates or non-matching events").isEmpty();
        assertThat(outcome.orderViolations()).isEmpty();
        assertThat(outcome.resumeWhileHeld()).as("the resume point while %d is held", outcome.held()).isLessThanOrEqualTo(outcome.held());
    }

    /**
     * A transaction holds {@code X} at {@code g0}; 20 rounds of 99 {@code Y} and one {@code X} commit above it (times
     * {@link #SCALE}); a filtered subscription starts at 1 and catches up; then {@code g0} commits
     */
    private Outcome heldMatchingEvent(ConsistencyMode mode, boolean indexed, Shape shape, Set<WatermarkMutant> readerMutants,
                                      Set<WatermarkMutant> mutants) throws Exception {
        var settings = indexed ? FILTERED.withEventTypeIndexed(Set.of(TYPE)) : FILTERED;
        restartEventLog(dataSource, settings);
        if (indexed) {
            assertThat(eventLog.eventTypeIndex().build(TYPE).get()).as("the index is built and valid").isTrue();
        }
        if (shape != Shape.READER_STARTS_HELD) {
            subscribe("warm", event -> {
            }, new TestSeams(readerMutants, TestSeams.Hooks.NONE, null), 1);
        }
        var committedX = new TreeSet<Long>();
        committedX.add(appendTyped("x-0", "X", 1));
        appendTyped("y-0", "Y", 50);
        var held = openAppend(AppendRequest.withoutExpectedOrder(TYPE, "x-held", events("X", 1)));
        var last = 0L;
        for (var round = 0; round < 20 * SCALE; round++) {
            appendTyped("y-" + round % 7, "Y", 99);
            last = appendTyped("x-" + round % 3, "X", 1);
            committedX.add(last);
        }
        if (shape != Shape.READER_STARTS_HELD) {
            var head = last;
            awaitTrue(() -> eventLog.reader(TYPE) != null && eventLog.reader(TYPE).state().cursor() >= head);
        }
        if (shape == Shape.READER_RESTARTED) {
            terminateReaderBackend();
            awaitTrue(() -> reads.detachReasons.contains("READER_RESTART") && eventLog.reader(TYPE) != null);
        }
        var recorder = new Recorder();
        var seams    = new TestSeams(shape == Shape.READER_STARTS_HELD ? union(readerMutants, mutants) : mutants, TestSeams.Hooks.NONE, null);
        var key      = new ProgressStore.Key("tested", TYPE, "node-1");
        var spec     = mode == ConsistencyMode.FRESH ? Subscription.SubscriptionSpec.periodic(key, 1, mode, recorder, seams)
                                                                  : Subscription.SubscriptionSpec.inTransaction(key, 1, mode, PageHandler.perEvent((e, tx) -> recorder.handle(e)), seams);
        var tested   = subscribe(spec.withFilter(ONLY_X));
        if (mode == ConsistencyMode.FRESH && shape == Shape.READER_RESTARTED && !readerMutants.contains(WatermarkMutant.RESTARTED_READER_AT_HEAD)) {
            // The held event commits only once the subscription attached under the unknown watermark and handed off
            var handing = !mutants.contains(WatermarkMutant.GAPS_NOT_HANDED_OFF);
            awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED && (!handing || reads.handedOffBy.contains("tested")));
            assertThat(eventLog.reader(TYPE).state().watermark()).as("the restart's watermark is still unknown").isNull();
        }
        if (mode == ConsistencyMode.FRESH && shape == Shape.READER_RUNNING && mutants.isEmpty()) {
            awaitDelivered(recorder, committedX.stream().mapToLong(Long::longValue).toArray());
        }
        awaitTrue(tested::isIdle);
        awaitReads(3);
        awaitTrue(tested::isIdle);
        var resumeWhileHeld = savedResumeFrom("tested");
        var g0              = held.globalOrder();
        held.commit();
        committedX.add(g0);
        var tail = appendTyped("x-tail", "X", 1);
        committedX.add(tail);
        var target = reads.reads.get() + 40;
        await().atMost(WAIT).until(() -> recorder.deliveredOrder(tail) && recorder.deliveredOrder(g0) || reads.reads.get() >= target);
        awaitReads(2);
        var missed = new TreeSet<>(committedX);
        missed.removeAll(recorder.deliveredOrders());
        var wrong = new ArrayList<String>();
        var seen  = new HashSet<Long>();
        for (var event : recorder.delivered) {
            if (!event.eventType().equals("X") || !seen.add(event.globalOrder())) {
                wrong.add(event.globalOrder() + ":" + event.eventType());
            }
        }
        return new Outcome(g0, missed, wrong, perAggregateOrderViolations(recorder.delivered), resumeWhileHeld);
    }

    // ---- I13: a type first appended while the catch-up runs ----

    @Test
    void a_type_first_appended_while_the_catch_up_runs_is_not_skipped() throws Exception {
        assertThat(firstAppendedTypeDelivered()).isTrue();
    }

    @Test
    void must_fail_resolving_type_ids_before_the_ceiling_skips_the_new_type() throws Exception {
        assertThat(firstAppendedTypeDelivered(WatermarkMutant.TYPE_IDS_BEFORE_CEILING)).isFalse();
    }

    /**
     * The filter names {@code X} and {@code Z}; {@code Z} has no id yet. Held at {@code afterCeilingBeforeTypeIds}, a
     * writer interns {@code Z}, appends a {@code Z} event and commits, and the reader publishes a watermark above it
     */
    private boolean firstAppendedTypeDelivered(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FILTERED);
        subscribe("warm", event -> {
        }, TestSeams.NONE, 1);
        appendTyped("y-0", "Y", 300);
        var x    = appendTyped("x-0", "X", 1);
        awaitTrue(() -> eventLog.reader(TYPE) != null && eventLog.reader(TYPE).state().watermark() != null && eventLog.reader(TYPE).state().watermark() >= x);
        var z    = new AtomicLong(-1);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void afterCeilingBeforeTypeIds(String subscriber, long ceiling) throws Exception {
                if (z.get() < 0) {
                    z.set(appendTyped("z-0", "Z", 1));
                    awaitTrue(() -> {
                        var state = eventLog.reader(TYPE).state();
                        return state.watermark() != null && state.watermark() >= z.get();
                    });
                }
            }
        };
        var recorder = new Recorder();
        var tested   = subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("tested", TYPE, "node-1"), 1, recorder, new TestSeams(Set.of(mutants), hooks, null))
                                         .withFilter(EventTypeFilter.of("X", "Z")));
        awaitDelivered(recorder, x);
        awaitTrue(() -> tested.phase() == Subscription.Phase.ATTACHED && tested.isIdle());
        return deliveredWithin(recorder, z.get());
    }

    // ---- a short page advances to the ceiling ----

    @Test
    void a_short_filtered_page_advances_to_the_ceiling() throws Exception {
        var statements = sparseCatchUpStatements();
        // 40 rounds of 500 orders from a window of 1 000 doubling: log2(20) + 1 windows, plus one
        assertThat(statements).isLessThanOrEqualTo((int) Math.ceil(Math.log(40.0 * SCALE * 500 / 1_000) / Math.log(2)) + 2);
    }

    @Test
    void must_fail_a_short_page_advancing_to_its_last_row_crawls() throws Exception {
        var statements = sparseCatchUpStatements(WatermarkMutant.FILTERED_ADVANCE_TO_LAST_ROW);
        assertThat(statements).isGreaterThan((int) Math.ceil(Math.log(40.0 * SCALE * 500 / 1_000) / Math.log(2)) + 2);
    }

    /** One {@code X} every 500 orders; @return the filtered catch-up statements until everything is delivered */
    private int sparseCatchUpStatements(WatermarkMutant... mutants) throws Exception {
        restartEventLog(dataSource, FILTERED);
        subscribe("warm", event -> {
        }, TestSeams.NONE, 1);
        var xs = new ArrayList<Long>();
        for (var round = 0; round < 40 * SCALE; round++) {
            appendTyped("y-" + round % 5, "Y", 499);
            xs.add(appendTyped("x-" + round % 3, "X", 1));
        }
        var head = xs.getLast();
        awaitTrue(() -> {
            var state = eventLog.reader(TYPE).state();
            return state.watermark() != null && state.watermark() >= head;
        });
        var statements = new AtomicInteger();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeFilteredRead(String subscriber, long lower, long ceiling, Set<Short> typeIds) {
                statements.incrementAndGet();
            }
        };
        var recorder = new Recorder();
        subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("tested", TYPE, "node-1"), 1, recorder, new TestSeams(Set.of(mutants), hooks, null))
                          .withFilter(ONLY_X));
        awaitDelivered(recorder, xs.stream().mapToLong(Long::longValue).toArray());
        assertThat(recorder.delivered).hasSize(xs.size());
        return statements.get();
    }

    // ---- a cancelled read quarters the window ----

    @Test
    void a_cancelled_filtered_read_retries_with_a_quarter_of_the_window_and_loses_nothing() throws Exception {
        restartEventLog(dataSource, FILTERED);
        subscribe("warm", event -> {
        }, TestSeams.NONE, 1);
        var xs = new ArrayList<Long>();
        for (var round = 0; round < 10; round++) {
            appendTyped("y-" + round % 5, "Y", 199);
            xs.add(appendTyped("x-" + round % 3, "X", 1));
        }
        var head = xs.getLast();
        awaitTrue(() -> {
            var state = eventLog.reader(TYPE).state();
            return state.watermark() != null && state.watermark() >= head;
        });
        var spans = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeFilteredRead(String subscriber, long lower, long ceiling, Set<Short> typeIds) {
                spans.add(ceiling - lower);
            }
        };
        var holder   = armScanHold();
        var recorder = new Recorder();
        subscribe(new Subscription.SubscriptionSpec(new ProgressStore.Key("tested", TYPE, "node-1"), 1, recorder, new TestSeams(Set.of(), hooks, HELD))
                          .withFilter(ONLY_X));
        awaitScanBlocked();
        execute("SELECT pg_cancel_backend(pid) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND objid = " + SCAN_KEY);
        releaseScan(holder);
        awaitDelivered(recorder, xs.stream().mapToLong(Long::longValue).toArray());
        assertThat(recorder.delivered).hasSize(xs.size());
        assertThat(spans.get(0)).isEqualTo(1_000L);
        assertThat(spans.get(1)).as("the same lower bound with a quarter of the window").isEqualTo(250L);
    }

    // ---- helpers ----

    private long appendTyped(String aggregateId, String eventType, int count) throws SQLException {
        return appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(TYPE, aggregateId, events(eventType, count))).getFirst().events().getFirst().globalOrder();
    }

    private static Set<WatermarkMutant> union(Set<WatermarkMutant> a, Set<WatermarkMutant> b) {
        var all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }

    private static org.awaitility.core.ConditionFactory await() {
        return org.awaitility.Awaitility.await();
    }
}
