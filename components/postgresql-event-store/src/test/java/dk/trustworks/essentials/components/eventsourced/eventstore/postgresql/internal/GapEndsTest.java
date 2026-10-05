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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.internal;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.SubscriptionGapHandler.MAX_AWAITED_ORDERS_PER_GAP_END;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gaps a poll reveals: every hole up to the highest event is recorded, but of a hole wider than twice
 * {@link SubscriptionGapHandler#MAX_AWAITED_ORDERS_PER_GAP_END} only that many orders at each end, and its middle is
 * awaited in memory only
 */
class GapEndsTest {
    private static final long END = MAX_AWAITED_ORDERS_PER_GAP_END;

    @Test
    void no_events_and_contiguous_events_open_no_gap() {
        assertThat(GapEnds.below(10, LongStream.of())).isEqualTo(GapEnds.NONE);
        assertThat(GapEnds.below(10, LongStream.of(12, 10, 11, 11))).isEqualTo(GapEnds.NONE);
    }

    @Test
    void every_hole_up_to_the_highest_event_is_recorded_in_full_up_to_twice_the_bound() {
        var gaps = GapEnds.below(10, LongStream.of(20, 13, 13 + 2 * END + 1));

        assertThat(gaps.recorded()).containsExactly(LongRange.between(10, 12),
                                                   LongRange.between(14, 19),
                                                   LongRange.between(21, 13 + 2 * END));
        assertThat(gaps.awaitedInMemoryOnly()).isEmpty();
    }

    @Test
    void of_a_wider_hole_only_the_ends_are_recorded_and_the_middle_is_awaited_in_memory_only() {
        var jump = 1_000_000L;
        var gaps = GapEnds.below(4, LongStream.of(4 + jump + 1, 4 + jump + 2));

        assertThat(gaps.recorded()).containsExactly(LongRange.between(4, 4 + END - 1),
                                                   LongRange.between(4 + jump + 1 - END, 4 + jump));
        assertThat(gaps.awaitedInMemoryOnly()).containsExactly(LongRange.between(4 + END, 4 + jump - END));
        assertThat(gaps.recorded().stream().mapToLong(range -> range.getToInclusive() - range.fromInclusive + 1).sum()).isEqualTo(2 * END);
    }

    @Test
    void a_hole_one_order_wider_than_twice_the_bound_has_a_middle_of_one_order() {
        var gaps = GapEnds.below(1, LongStream.of(2 * END + 2));

        assertThat(gaps.recorded()).containsExactly(LongRange.between(1, END), LongRange.between(END + 2, 2 * END + 1));
        assertThat(gaps.awaitedInMemoryOnly()).containsExactly(LongRange.only(END + 1));
    }

    @Test
    void events_below_the_start_of_the_range_open_no_gap() {
        // Gap fills: they lie below the read position
        var gaps = GapEnds.below(100, LongStream.of(3, 50, 102));

        assertThat(gaps.recorded()).containsExactly(LongRange.between(100, 101));
        assertThat(GapEnds.below(100, LongStream.of(3, 50))).isEqualTo(GapEnds.NONE);
    }
}
