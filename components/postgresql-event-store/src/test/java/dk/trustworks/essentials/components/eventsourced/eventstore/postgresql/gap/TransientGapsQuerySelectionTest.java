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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.TransientGapsQuerySelection.*;
import static org.assertj.core.api.Assertions.assertThat;

class TransientGapsQuerySelectionTest {
    private static final OffsetDateTime DISCOVERED = OffsetDateTime.now();

    @Test
    void every_gap_is_included_up_to_the_maximum() {
        var selection = new TransientGapsQuerySelection();

        assertThat(selection.select(gaps(LongStream.of()))).isEmpty();
        assertThat(selection.select(gaps(LongStream.of(7, 3, 5)))).containsExactly(order(3), order(5), order(7));
        assertThat(selection.select(gaps(LongStream.rangeClosed(1, MAX_GAPS_PER_QUERY)))).hasSize(MAX_GAPS_PER_QUERY);
    }

    @Test
    void duplicates_are_included_once() {
        var selection = new TransientGapsQuerySelection();

        assertThat(selection.select(gaps(LongStream.of(4, 4, 2, 2)))).containsExactly(order(2), order(4));
    }

    @Test
    void beyond_the_maximum_the_newest_and_the_lowest_gaps_are_always_included() {
        var selection = new TransientGapsQuerySelection();
        var allGaps   = gaps(LongStream.rangeClosed(1, 1_000));

        for (var poll = 0; poll < 10; poll++) {
            var selected = selection.select(allGaps);

            assertThat(selected).hasSize(MAX_GAPS_PER_QUERY)
                                .doesNotHaveDuplicates()
                                .isSorted();
            // A late commit fills a gap opened recently: it is asked for on every poll
            assertThat(selected).containsAll(orders(LongStream.rangeClosed(1_000 - NEWEST_GAPS + 1, 1_000)));
            // The next to be promoted get a last look on every poll
            assertThat(selected).containsAll(orders(LongStream.rangeClosed(1, LOWEST_GAPS)));
        }
    }

    @Test
    void the_gaps_in_between_are_all_asked_for_by_the_rotation() {
        var selection     = new TransientGapsQuerySelection();
        var allGaps       = gaps(LongStream.rangeClosed(1, 1_000));
        var inBetween     = orders(LongStream.rangeClosed(LOWEST_GAPS + 1, 1_000 - NEWEST_GAPS));
        var pollsPerRound = (inBetween.size() + ROTATING_GAPS - 1) / ROTATING_GAPS;

        var askedFor = new HashSet<GlobalEventOrder>();
        for (var poll = 0; poll < pollsPerRound; poll++) {
            askedFor.addAll(selection.select(allGaps));
        }

        assertThat(askedFor).containsAll(inBetween);
    }

    @Test
    void the_rotation_wraps_around_and_continues_from_where_it_got_to_when_gaps_change() {
        var selection = new TransientGapsQuerySelection();
        // 10 lowest + 25 in between + 20 newest
        var allGaps   = gaps(LongStream.rangeClosed(1, LOWEST_GAPS + 25 + NEWEST_GAPS));

        var first = inBetween(selection.select(allGaps));
        assertThat(first).isEqualTo(orders(LongStream.rangeClosed(LOWEST_GAPS + 1, LOWEST_GAPS + ROTATING_GAPS)));

        // The rest of the gaps in between, then from the start again
        var second = inBetween(selection.select(allGaps));
        assertThat(second).containsExactlyInAnyOrderElementsOf(Stream.concat(orders(LongStream.rangeClosed(LOWEST_GAPS + ROTATING_GAPS + 1, LOWEST_GAPS + 25)).stream(),
                                                                             orders(LongStream.rangeClosed(LOWEST_GAPS + 1, LOWEST_GAPS + ROTATING_GAPS - 5)).stream())
                                                                     .toList());

        // Gaps resolved since: the rotation continues above the last gap it asked for, rather than at a position
        var remaining = allGaps.stream()
                               .filter(gap -> gap._1.longValue() % 2 == 0 || gap._1.longValue() > LOWEST_GAPS + 25)
                               .collect(Collectors.toCollection(ArrayList::new));
        remaining.addAll(gaps(LongStream.rangeClosed(1_000, 1_030)));
        var third = selection.select(remaining);
        assertThat(third).hasSize(MAX_GAPS_PER_QUERY);
        var lastAskedFor = LOWEST_GAPS + ROTATING_GAPS - 5;
        assertThat(inBetween(third, remaining).getFirst().longValue()).isGreaterThan(lastAskedFor);
    }

    private static List<GlobalEventOrder> inBetween(List<GlobalEventOrder> selected) {
        return selected.subList(LOWEST_GAPS, LOWEST_GAPS + ROTATING_GAPS).stream().sorted().toList();
    }

    /**
     * The rotating part of {@code selected}, in the order it was asked for: lowest-first from where the rotation was
     */
    private static List<GlobalEventOrder> inBetween(List<GlobalEventOrder> selected, List<Pair<GlobalEventOrder, OffsetDateTime>> allGaps) {
        var sortedGaps = allGaps.stream().map(Pair::_1).sorted().distinct().toList();
        var lowest     = sortedGaps.subList(0, LOWEST_GAPS);
        var newest     = sortedGaps.subList(sortedGaps.size() - NEWEST_GAPS, sortedGaps.size());
        return selected.stream().filter(gap -> !lowest.contains(gap) && !newest.contains(gap)).toList();
    }

    private static List<Pair<GlobalEventOrder, OffsetDateTime>> gaps(LongStream globalOrders) {
        return globalOrders.mapToObj(globalOrder -> Pair.of(order(globalOrder), DISCOVERED)).toList();
    }

    private static List<GlobalEventOrder> orders(LongStream globalOrders) {
        return globalOrders.mapToObj(TransientGapsQuerySelectionTest::order).toList();
    }

    private static GlobalEventOrder order(long globalOrder) {
        return GlobalEventOrder.of(globalOrder);
    }
}
