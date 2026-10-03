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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcDeliveryTracker.Kind;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class CdcDeliveryTrackerTest {
    private static final Duration GAP_TIMEOUT = Duration.ofSeconds(120);

    private final AtomicLong nanoTime = new AtomicLong(1_000);

    private CdcDeliveryTracker tracker(long watermark, int maxTrackedGaps) {
        return new CdcDeliveryTracker("test", watermark, GAP_TIMEOUT, maxTrackedGaps, nanoTime::get);
    }

    private void advanceClock(Duration duration) {
        nanoTime.addAndGet(duration.toNanos());
    }

    @Test
    void in_order_deliveries_advance_the_watermark_contiguously() {
        var tracker = tracker(0, 100);

        assertThat(tracker.markDelivered(1).kind()).isEqualTo(Kind.IN_ORDER);
        assertThat(tracker.markDelivered(2).kind()).isEqualTo(Kind.IN_ORDER);
        assertThat(tracker.markDelivered(3).kind()).isEqualTo(Kind.IN_ORDER);

        assertThat(tracker.watermark()).isEqualTo(3);
        assertThat(tracker.resumeFromInclusive()).isEqualTo(4);
        assertThat(tracker.highestDeliveredExclusive()).isEqualTo(4);
        assertThat(tracker.awaitedGaps(100)).isEmpty();
    }

    @Test
    void nothing_at_or_below_the_starting_point_is_delivered() {
        var tracker = tracker(10, 100);

        assertThat(tracker.markDelivered(10).isNew()).isFalse();
        assertThat(tracker.markDelivered(3).isNew()).isFalse();
        assertThat(tracker.isAtOrBelowWatermarkAndNotAwaited(10)).isTrue();
        assertThat(tracker.markDelivered(11).kind()).isEqualTo(Kind.IN_ORDER);
    }

    @Test
    void a_lower_order_committing_after_a_higher_one_is_delivered_when_it_arrives() {
        var tracker = tracker(0, 100);
        tracker.markDelivered(1);

        var opened = tracker.markDelivered(3);
        assertThat(opened.kind()).isEqualTo(Kind.OPENED_GAP);
        assertThat(opened.gapFromInclusive()).isEqualTo(2);
        assertThat(tracker.watermark()).isEqualTo(1);
        assertThat(tracker.isDelivered(2)).isFalse();
        assertThat(tracker.isDelivered(3)).isTrue();
        assertThat(tracker.isAtOrBelowWatermarkAndNotAwaited(2)).isFalse();
        assertThat(tracker.awaitedGaps(100)).containsExactly(GlobalEventOrder.of(2));
        assertThat(tracker.resumeFromInclusive()).as("polling reads the gap again").isEqualTo(2);
        assertThat(tracker.highestDeliveredExclusive()).isEqualTo(4);

        var filled = tracker.markDelivered(2);
        assertThat(filled.kind()).isEqualTo(Kind.FILLED_GAP);
        assertThat(filled.gapFromInclusive()).isEqualTo(2);
        assertThat(tracker.watermark()).as("contiguous again, across the run above the gap").isEqualTo(3);
        assertThat(tracker.awaitedGaps(100)).isEmpty();
    }

    @Test
    void duplicates_are_dropped_wherever_they_fall() {
        var tracker = tracker(0, 100);
        tracker.markDelivered(1);
        tracker.markDelivered(4);
        tracker.markDelivered(5);
        tracker.markDelivered(9);

        // Below the watermark, inside a run, at the end of a run, the highest delivered
        for (long duplicate : new long[]{1, 4, 5, 9}) {
            assertThat(tracker.markDelivered(duplicate).kind()).as("#%d", duplicate).isEqualTo(Kind.DUPLICATE);
            assertThat(tracker.isDelivered(duplicate)).isTrue();
        }
        assertThat(tracker.awaitedGaps(100)).extracting(GlobalEventOrder::longValue).containsExactly(2L, 3L, 6L, 7L, 8L);
    }

    @Test
    void gaps_filled_in_any_order_end_in_one_contiguous_watermark() {
        var tracker = tracker(0, 100);
        tracker.markDelivered(10);
        // Inside the gap 1..9, none adjacent to anything: splits it
        assertThat(tracker.markDelivered(5).kind()).isEqualTo(Kind.FILLED_GAP);
        // Adjacent to the run above only, below only, and to both
        assertThat(tracker.markDelivered(9).kind()).isEqualTo(Kind.FILLED_GAP);
        assertThat(tracker.markDelivered(6).kind()).isEqualTo(Kind.FILLED_GAP);
        assertThat(tracker.markDelivered(8).kind()).isEqualTo(Kind.FILLED_GAP);
        assertThat(tracker.markDelivered(7).kind()).isEqualTo(Kind.FILLED_GAP);
        assertThat(tracker.awaitedGaps(100)).extracting(GlobalEventOrder::longValue).containsExactly(1L, 2L, 3L, 4L);
        assertThat(tracker.watermark()).isZero();

        for (long order : new long[]{2, 4, 1, 3}) {
            assertThat(tracker.markDelivered(order).kind()).isEqualTo(Kind.FILLED_GAP);
        }
        assertThat(tracker.watermark()).isEqualTo(10);
        assertThat(tracker.awaitedGaps(100)).isEmpty();
        assertThat(tracker.markDelivered(11).kind()).isEqualTo(Kind.IN_ORDER);
    }

    @Test
    void a_random_delivery_order_delivers_everything_exactly_once() {
        var random = new Random(42);
        for (int round = 0; round < 50; round++) {
            var tracker = tracker(0, 1_000);
            var orders  = new ArrayList<>(LongStream.rangeClosed(1, 200).boxed().toList());
            // Every order shows up twice, in any order
            orders.addAll(LongStream.rangeClosed(1, 200).boxed().toList());
            Collections.shuffle(orders, random);

            var delivered = new ArrayList<Long>();
            for (long order : orders) {
                if (tracker.markDelivered(order).isNew()) {
                    delivered.add(order);
                }
            }

            assertThat(delivered).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 200).boxed().toList());
            assertThat(tracker.watermark()).isEqualTo(200);
            assertThat(tracker.awaitedGaps(1_000)).isEmpty();
        }
    }

    @Test
    void a_gap_older_than_the_timeout_is_given_up_and_its_late_event_dropped() {
        var tracker = tracker(0, 100);
        tracker.markDelivered(1);
        tracker.markDelivered(3);
        advanceClock(Duration.ofSeconds(60));
        // A second gap, revealed later
        tracker.markDelivered(6);

        advanceClock(Duration.ofSeconds(59));
        assertThat(tracker.resumeFromInclusive()).as("still waiting for 2").isEqualTo(2);

        advanceClock(Duration.ofSeconds(1));
        assertThat(tracker.resumeFromInclusive()).as("2 given up, 4..5 still waited for").isEqualTo(4);
        assertThat(tracker.markDelivered(2).kind()).isEqualTo(Kind.DUPLICATE);
        assertThat(tracker.awaitedGaps(100)).extracting(GlobalEventOrder::longValue).containsExactly(4L, 5L);

        advanceClock(Duration.ofSeconds(60));
        assertThat(tracker.resumeFromInclusive()).isEqualTo(7);
        assertThat(tracker.awaitedGaps(100)).isEmpty();
        assertThat(tracker.markDelivered(4).isNew()).isFalse();
    }

    @Test
    void a_gap_split_by_a_late_event_keeps_the_age_of_the_gap_it_came_from() {
        var tracker = tracker(0, 100);
        tracker.markDelivered(10);
        advanceClock(Duration.ofSeconds(100));
        tracker.markDelivered(5);

        advanceClock(Duration.ofSeconds(20));
        // Both halves were revealed by 10, 120 s ago
        assertThat(tracker.resumeFromInclusive()).isEqualTo(11);
    }

    @Test
    void more_gaps_than_the_cap_gives_up_the_oldest_at_once() {
        var tracker = tracker(0, 3);
        // Gaps below 2, 4, 6, 8 - one more than the cap
        tracker.markDelivered(2);
        tracker.markDelivered(4);
        tracker.markDelivered(6);
        assertThat(tracker.awaitedGaps(100)).extracting(GlobalEventOrder::longValue).containsExactly(1L, 3L, 5L);

        tracker.markDelivered(8);

        assertThat(tracker.watermark()).as("1 given up").isEqualTo(2);
        assertThat(tracker.awaitedGaps(100)).extracting(GlobalEventOrder::longValue).containsExactly(3L, 5L, 7L);
        assertThat(tracker.markDelivered(1).isNew()).isFalse();
        assertThat(tracker.markDelivered(7).isNew()).isTrue();
    }

    @Test
    void awaited_gaps_are_bounded_by_the_limit_lowest_first() {
        var tracker = tracker(0, 100);
        tracker.markDelivered(1_000);

        assertThat(tracker.awaitedGaps(3)).extracting(GlobalEventOrder::longValue).containsExactly(1L, 2L, 3L);
    }

    @Test
    void transient_gaps_recorded_before_the_subscription_started_are_waited_for_below_the_watermark() {
        var tracker = tracker(10, 100);
        tracker.seedEarlierGaps(List.of(GlobalEventOrder.of(4), GlobalEventOrder.of(7), GlobalEventOrder.of(12)));

        assertThat(tracker.isAtOrBelowWatermarkAndNotAwaited(4)).isFalse();
        assertThat(tracker.isAtOrBelowWatermarkAndNotAwaited(5)).isTrue();
        assertThat(tracker.isDelivered(7)).isFalse();
        assertThat(tracker.awaitedGaps(100)).extracting(GlobalEventOrder::longValue).containsExactly(4L, 7L);
        // Above the watermark it is delivered anyway, so it is not an earlier gap
        assertThat(tracker.resumeFromInclusive()).isEqualTo(11);

        assertThat(tracker.markDelivered(7).kind()).isEqualTo(Kind.FILLED_GAP);
        assertThat(tracker.markDelivered(7).kind()).isEqualTo(Kind.DUPLICATE);
        assertThat(tracker.markDelivered(5).kind()).isEqualTo(Kind.DUPLICATE);

        advanceClock(GAP_TIMEOUT);
        assertThat(tracker.awaitedGaps(100)).isEmpty();
        assertThat(tracker.markDelivered(4).kind()).isEqualTo(Kind.DUPLICATE);
    }

    @Test
    void earlier_gaps_beyond_the_cap_keep_the_highest() {
        var tracker = tracker(100, 2);
        tracker.seedEarlierGaps(List.of(GlobalEventOrder.of(50), GlobalEventOrder.of(60), GlobalEventOrder.of(70)));

        assertThat(tracker.awaitedGaps(100)).extracting(GlobalEventOrder::longValue).containsExactly(60L, 70L);
    }

    @Test
    void the_gap_timeout_follows_the_threshold_of_the_gap_handlers_promotion_strategy() {
        var strategy   = PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(600);
        var gapHandler = new StubGapHandler(strategy.permanentGapThreshold());

        assertThat(strategy.permanentGapThreshold()).contains(Duration.ofSeconds(600));
        assertThat(CdcDeliveryTracker.gapTimeoutFor(Optional.of(gapHandler))).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void the_gap_timeout_is_the_default_when_the_gap_handler_states_none() {
        PostgresqlEventStreamGapHandler.ResolveTransientGapsToPermanentGapsPromotionStrategy custom = (type, gaps) -> List.of();

        assertThat(custom.permanentGapThreshold()).isEmpty();
        assertThat(CdcDeliveryTracker.gapTimeoutFor(Optional.empty())).isEqualTo(CdcDeliveryTracker.DEFAULT_GAP_TIMEOUT);
        assertThat(CdcDeliveryTracker.gapTimeoutFor(Optional.of(new StubGapHandler(custom.permanentGapThreshold())))).isEqualTo(CdcDeliveryTracker.DEFAULT_GAP_TIMEOUT);
        // A no-op gap handler states none: the tracker's own timeout is then the only rule
        assertThat(CdcDeliveryTracker.gapTimeoutFor(Optional.of(new NoEventStreamGapHandler<>().gapHandlerFor(SubscriberId.of("s")))))
                .isEqualTo(CdcDeliveryTracker.DEFAULT_GAP_TIMEOUT);
        assertThat(CdcDeliveryTracker.gapTimeoutFor(Optional.of(new StubGapHandler(Optional.of(Duration.ZERO))))).isEqualTo(CdcDeliveryTracker.DEFAULT_GAP_TIMEOUT);
    }

    @Test
    void a_tracker_gives_up_a_gap_after_the_timeout_it_is_given_not_after_120_seconds() {
        var tracker = new CdcDeliveryTracker("test", 0, Duration.ofSeconds(10), 100, nanoTime::get);
        tracker.markDelivered(1);
        assertThat(tracker.markDelivered(3).kind()).isEqualTo(Kind.OPENED_GAP);

        advanceClock(Duration.ofSeconds(9));
        assertThat(tracker.markDelivered(2).kind()).isEqualTo(Kind.FILLED_GAP);

        assertThat(tracker.markDelivered(5).kind()).isEqualTo(Kind.OPENED_GAP);
        advanceClock(Duration.ofSeconds(11));
        assertThat(tracker.markDelivered(4).kind()).isEqualTo(Kind.DUPLICATE);
        assertThat(tracker.watermark()).isEqualTo(5);
    }

    @Test
    void the_orders_given_up_on_are_collected_for_the_gap_handler_once_asked_to() {
        var tracker = tracker(10, 2);
        tracker.collectGivenUpGaps();
        tracker.seedEarlierGaps(List.of(GlobalEventOrder.of(4)));
        tracker.markDelivered(11);
        // Gaps 12..13 and 15
        tracker.markDelivered(14);
        tracker.markDelivered(16);
        assertThat(tracker.drainGivenUpGaps()).isEmpty();

        // A third gap is one more than the cap: the oldest, 12..13, is given up at once
        tracker.markDelivered(18);
        assertThat(tracker.drainGivenUpGaps()).extracting(GlobalEventOrder::longValue).containsExactly(12L, 13L);
        assertThat(tracker.drainGivenUpGaps()).as("drained").isEmpty();

        // Too old: the earlier gap, and the gaps 15 and 17
        advanceClock(GAP_TIMEOUT);
        assertThat(tracker.resumeFromInclusive()).isEqualTo(19);
        assertThat(tracker.drainGivenUpGaps()).extracting(GlobalEventOrder::longValue).containsExactlyInAnyOrder(4L, 15L, 17L);
    }

    @Test
    void the_orders_given_up_on_are_not_kept_unless_asked_to() {
        var tracker = tracker(0, 100);
        tracker.markDelivered(1);
        tracker.markDelivered(3);
        advanceClock(GAP_TIMEOUT);

        assertThat(tracker.resumeFromInclusive()).as("2 given up").isEqualTo(4);
        assertThat(tracker.drainGivenUpGaps()).isEmpty();
    }

    @Test
    void a_threshold_too_large_for_nanoseconds_does_not_overflow_into_giving_every_gap_up() {
        var tracker = new CdcDeliveryTracker("test", 0, Duration.ofDays(365L * 1000), 100, nanoTime::get);
        tracker.markDelivered(2);
        advanceClock(Duration.ofDays(365));
        assertThat(tracker.markDelivered(1).kind()).isEqualTo(Kind.FILLED_GAP);
    }

    private record StubGapHandler(Optional<Duration> threshold) implements SubscriptionGapHandler {
        @Override
        public Optional<Duration> transientGapGiveUpThreshold() {
            return threshold;
        }

        @Override
        public SubscriberId subscriberId() {
            return SubscriberId.of("stub");
        }

        @Override
        public List<GlobalEventOrder> findTransientGapsToIncludeInQuery(AggregateType aggregateType, LongRange range) {
            return List.of();
        }

        @Override
        public void reconcileGaps(AggregateType aggregateType, LongRange range, List<PersistedEvent> events, List<GlobalEventOrder> gaps) {
        }

        @Override
        public List<GlobalEventOrder> resetTransientGapsFor(AggregateType aggregateType) {
            return List.of();
        }

        @Override
        public List<GlobalEventOrder> getTransientGapsFor(AggregateType aggregateType) {
            return List.of();
        }

        @Override
        public java.util.stream.Stream<GlobalEventOrder> getPermanentGapsFor(AggregateType aggregateType) {
            return java.util.stream.Stream.empty();
        }
    }
}
