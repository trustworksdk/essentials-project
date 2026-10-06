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

import java.time.OffsetDateTime;
import java.util.*;

/**
 * The default choice of which of one subscriber's transient gaps a poll asks for again - the default
 * {@link PostgresqlEventStreamGapHandler.ResolveTransientGapsToIncludeInQueryStrategy}. One instance per subscription
 * and aggregate type: it remembers where its rotation got to.
 * <p>
 * Every gap a poll includes becomes one more value in the {@code global_order IN (...)} clause of a query that runs
 * on every poll of every subscription, so the number is bounded: at most {@link #MAX_GAPS_PER_QUERY}. Up to that many
 * open gaps, all of them are included. Beyond it, a poll includes
 * <ul>
 *     <li>the {@link #NEWEST_GAPS} highest gaps - a transaction that commits late (a lower global order committing
 *     after a higher one) fills a gap that was opened recently, so it is picked up by the next poll after it commits;</li>
 *     <li>the {@link #LOWEST_GAPS} lowest gaps - the oldest, i.e. the next to be promoted to permanent gaps, which get a
 *     last look before they are;</li>
 *     <li>a window of {@link #ROTATING_GAPS} from the gaps in between, rotating on every poll, so each of those is
 *     asked for again at least once every {@code ceil(gapsInBetween / ROTATING_GAPS)} polls.</li>
 * </ul>
 * The previous default included only the two lowest gaps. Rolled-back transactions - holes that are never filled - sit
 * lowest, so with more than two open a late commit above them was not asked for until the holes were promoted
 * (120 seconds), and was given up on unread whenever it was promoted together with them.
 * <p>
 * Cost: in the common case - a handful of open gaps - the query asks for each of them on every poll, a few primary-key
 * lookups. However many gaps are open, it never asks for more than {@link #MAX_GAPS_PER_QUERY}. The Change Data Capture
 * catch-up asks for up to 1 000 gaps at once, but only on the first page of a catch-up; a poll repeats every polling
 * interval, so it trades a larger per-query list for the rotation.
 */
final class TransientGapsQuerySelection {
    /**
     * The most transient gaps one poll includes
     */
    static final int MAX_GAPS_PER_QUERY = 50;
    /**
     * Beyond {@link #MAX_GAPS_PER_QUERY} open gaps: how many of the highest a poll always includes
     */
    static final int NEWEST_GAPS        = 20;
    /**
     * Beyond {@link #MAX_GAPS_PER_QUERY} open gaps: how many of the lowest a poll always includes
     */
    static final int LOWEST_GAPS        = 10;
    /**
     * Beyond {@link #MAX_GAPS_PER_QUERY} open gaps: how many of the gaps in between a poll includes, rotating
     */
    static final int ROTATING_GAPS      = MAX_GAPS_PER_QUERY - NEWEST_GAPS - LOWEST_GAPS;

    /**
     * The highest gap the previous rotating window included - the next window starts above it
     */
    private long rotatedUpToInclusive = Long.MIN_VALUE;

    /**
     * @param allTransientGaps all the subscriber's transient gaps for one aggregate type, in any order, duplicates allowed
     * @return the gaps to include in the next query, lowest first - at most {@link #MAX_GAPS_PER_QUERY}
     */
    synchronized List<GlobalEventOrder> select(List<Pair<GlobalEventOrder, OffsetDateTime>> allTransientGaps) {
        var gaps = allTransientGaps.stream()
                                   .mapToLong(gap -> gap._1.longValue())
                                   .sorted()
                                   .distinct()
                                   .toArray();
        if (gaps.length <= MAX_GAPS_PER_QUERY) {
            return toGlobalEventOrders(gaps, 0, gaps.length);
        }

        var selected = new ArrayList<GlobalEventOrder>(MAX_GAPS_PER_QUERY);
        selected.addAll(toGlobalEventOrders(gaps, 0, LOWEST_GAPS));

        // The gaps in between: [LOWEST_GAPS, gaps.length - NEWEST_GAPS) - more than ROTATING_GAPS of them, as there are
        // more than MAX_GAPS_PER_QUERY gaps in all. The window starts right above where the previous one ended, so a
        // gap that was resolved or promoted in the meantime does not shift the rotation, and wraps around at the top
        var inBetweenFrom    = LOWEST_GAPS;
        var inBetweenTo      = gaps.length - NEWEST_GAPS;
        var numberInBetween  = inBetweenTo - inBetweenFrom;
        var firstAboveCursor = Arrays.binarySearch(gaps, inBetweenFrom, inBetweenTo, rotatedUpToInclusive);
        var windowStart      = firstAboveCursor >= 0 ? firstAboveCursor + 1 : -firstAboveCursor - 1;
        if (windowStart >= inBetweenTo) {
            windowStart = inBetweenFrom;
        }
        var rotating = new long[ROTATING_GAPS];
        for (var i = 0; i < ROTATING_GAPS; i++) {
            rotating[i] = gaps[inBetweenFrom + (windowStart - inBetweenFrom + i) % numberInBetween];
        }
        rotatedUpToInclusive = rotating[ROTATING_GAPS - 1];
        Arrays.sort(rotating);
        selected.addAll(toGlobalEventOrders(rotating, 0, rotating.length));

        selected.addAll(toGlobalEventOrders(gaps, inBetweenTo, gaps.length));
        return selected;
    }

    private static List<GlobalEventOrder> toGlobalEventOrders(long[] globalOrders, int fromInclusive, int toExclusive) {
        var result = new ArrayList<GlobalEventOrder>(toExclusive - fromInclusive);
        for (var i = fromInclusive; i < toExclusive; i++) {
            result.add(GlobalEventOrder.of(globalOrders[i]));
        }
        return result;
    }
}
