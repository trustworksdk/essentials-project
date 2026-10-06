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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.ResolveTransientGapsToIncludeInQueryStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler.RotationKey;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResolveTransientGapsToIncludeInQueryStrategy#defaultSelection()}: the public way for a custom strategy to reuse the default selection
 */
class DefaultSelectionStrategyTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    @Test
    void asks_for_every_gap_up_to_the_maximum() {
        var strategy = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();

        assertThat(strategy.resolveTransientGaps(ORDERS, LongRange.from(1), gaps(LongStream.of(9, 4, 6))))
                .containsExactly(GlobalEventOrder.of(4), GlobalEventOrder.of(6), GlobalEventOrder.of(9));
    }

    @Test
    void is_bounded_and_rotates_beyond_the_maximum_like_the_selection_it_wraps() {
        var strategy = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();
        var reference = new TransientGapsQuerySelection();
        var all = gaps(LongStream.rangeClosed(1, 500));

        var asked = new HashSet<GlobalEventOrder>();
        for (var poll = 0; poll < 30; poll++) {
            var selected = strategy.resolveTransientGaps(ORDERS, LongRange.from(1), all);
            assertThat(selected).hasSize(TransientGapsQuerySelection.MAX_GAPS_PER_QUERY)
                                .isEqualTo(reference.select(all));
            asked.addAll(selected);
        }
        // 10 lowest + 20 highest + 30 polls * 20 rotating covers every one of the 500
        assertThat(asked).hasSize(500);
    }

    @Test
    void every_call_returns_its_own_rotation() {
        var all = gaps(LongStream.rangeClosed(1, 500));
        var first = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();
        var second = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();

        var firstPoll = first.resolveTransientGaps(ORDERS, LongRange.from(1), all);
        first.resolveTransientGaps(ORDERS, LongRange.from(1), all);

        assertThat(second.resolveTransientGaps(ORDERS, LongRange.from(1), all)).isEqualTo(firstPoll);
    }

    @Test
    void can_be_composed_in_a_custom_strategy() {
        var defaultSelection = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();
        ResolveTransientGapsToIncludeInQueryStrategy mine = (type, range, gaps) -> {
            var selected = new TreeSet<>(defaultSelection.resolveTransientGaps(type, range, gaps));
            selected.add(GlobalEventOrder.of(250));
            return List.copyOf(selected);
        };

        assertThat(mine.resolveTransientGaps(ORDERS, LongRange.from(1), gaps(LongStream.rangeClosed(1, 500))))
                .hasSize(TransientGapsQuerySelection.MAX_GAPS_PER_QUERY + 1)
                .contains(GlobalEventOrder.of(250));
    }

    /**
     * Asked by the gap handler, a default selection rotates with the asking subscription's rotation - also when it is
     * wrapped by, or called from, a custom strategy, which is not told which subscription asks
     */
    @Test
    void wrapped_in_a_custom_strategy_it_still_rotates_per_subscription_when_the_gap_handler_asks() {
        var defaultSelection = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();
        ResolveTransientGapsToIncludeInQueryStrategy wrapping = (type, range, gaps) -> defaultSelection.resolveTransientGaps(type, range, gaps);
        var all                = gaps(LongStream.rangeClosed(1, 500));
        var firstSubscription  = new ConcurrentHashMap<RotationKey, TransientGapsQuerySelection>();
        var secondSubscription = new ConcurrentHashMap<RotationKey, TransientGapsQuerySelection>();
        var firstReference     = new TransientGapsQuerySelection();
        var secondReference    = new TransientGapsQuerySelection();

        for (var poll = 0; poll < 5; poll++) {
            // The second subscription polls twice as often: shared, the rotation would advance for both
            assertThat(PostgresqlEventStreamGapHandler.withRotationsOf(firstSubscription, () -> wrapping.resolveTransientGaps(ORDERS, LongRange.from(1), all)))
                    .isEqualTo(firstReference.select(all));
            assertThat(PostgresqlEventStreamGapHandler.withRotationsOf(secondSubscription, () -> wrapping.resolveTransientGaps(ORDERS, LongRange.from(1), all)))
                    .isEqualTo(secondReference.select(all));
            assertThat(PostgresqlEventStreamGapHandler.withRotationsOf(secondSubscription, () -> wrapping.resolveTransientGaps(ORDERS, LongRange.from(1), all)))
                    .isEqualTo(secondReference.select(all));
        }
        // ... and called directly, the instance rotates with its own, untouched by the subscriptions
        assertThat(wrapping.resolveTransientGaps(ORDERS, LongRange.from(1), all)).isEqualTo(new TransientGapsQuerySelection().select(all));
    }

    /**
     * A custom strategy may compose more than one default selection - here one per half of the gaps. Asked by the gap
     * handler, each keeps a rotation of its own within the subscription's: shared, one cursor would be advanced by both
     */
    @Test
    void two_default_selections_composed_in_one_strategy_keep_a_rotation_each_when_the_gap_handler_asks() {
        var lowerHalfSelection = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();
        var upperHalfSelection = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();
        var lowerHalf          = gaps(LongStream.rangeClosed(1, 250));
        var upperHalf          = gaps(LongStream.rangeClosed(251, 500));
        var selectedByHalf     = new ArrayList<List<GlobalEventOrder>>();
        ResolveTransientGapsToIncludeInQueryStrategy byHalf = (type, range, gaps) -> {
            selectedByHalf.clear();
            selectedByHalf.add(lowerHalfSelection.resolveTransientGaps(type, range, lowerHalf));
            selectedByHalf.add(upperHalfSelection.resolveTransientGaps(type, range, upperHalf));
            return selectedByHalf.stream().flatMap(List::stream).toList();
        };
        var subscription       = new ConcurrentHashMap<RotationKey, TransientGapsQuerySelection>();
        var lowerHalfReference = new TransientGapsQuerySelection();
        var upperHalfReference = new TransientGapsQuerySelection();
        var all                = gaps(LongStream.rangeClosed(1, 500));

        for (var poll = 0; poll < 5; poll++) {
            PostgresqlEventStreamGapHandler.withRotationsOf(subscription, () -> byHalf.resolveTransientGaps(ORDERS, LongRange.from(1), all));

            assertThat(selectedByHalf.get(0)).isEqualTo(lowerHalfReference.select(lowerHalf));
            assertThat(selectedByHalf.get(1)).isEqualTo(upperHalfReference.select(upperHalf));
        }
    }

    private static List<Pair<GlobalEventOrder, OffsetDateTime>> gaps(LongStream orders) {
        var discovered = OffsetDateTime.now();
        return orders.mapToObj(order -> Pair.of(GlobalEventOrder.of(order), discovered)).collect(Collectors.toList());
    }
}
