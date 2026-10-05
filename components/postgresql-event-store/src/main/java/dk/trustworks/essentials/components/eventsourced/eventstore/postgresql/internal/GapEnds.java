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

import java.util.*;
import java.util.stream.LongStream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.SubscriptionGapHandler.MAX_AWAITED_ORDERS_PER_GAP_END;

/**
 * <b>Internal - not part of the public API</b>, and may change in any release: public only so the polling event store
 * and the {@link PostgresqlEventStreamGapHandler}, in two packages, split a gap the same way.
 * <p>
 * The gaps a poll reveals: every hole from where its query range starts up to the highest event it read - before the
 * lowest event at or above that start, and between two consecutive events. A hole no wider than twice
 * {@link SubscriptionGapHandler#MAX_AWAITED_ORDERS_PER_GAP_END} is {@link #recorded} in full; of a wider one only that many
 * orders at each end are, and the middle is {@link #awaitedInMemoryOnly}. Found by walking the events, so finding them
 * costs as much as the events, whatever the width of the holes.
 *
 * @param recorded            closed ranges of the orders to record as transient gaps, ascending and disjoint
 * @param awaitedInMemoryOnly closed ranges of the middles of the holes too wide to record in full, ascending and disjoint
 */
public record GapEnds(List<LongRange> recorded, List<LongRange> awaitedInMemoryOnly) {
    public static final GapEnds NONE = new GapEnds(List.of(), List.of());

    /**
     * @param fromInclusive where the query range starts: what the subscription has already seen lies below
     * @param eventOrders   the global orders of the events read, in any order and with any duplicates; those below
     *                      {@code fromInclusive} - gap fills - open no gap
     * @return the gaps below the highest of {@code eventOrders}
     */
    public static GapEnds below(long fromInclusive, LongStream eventOrders) {
        var recorded            = new ArrayList<LongRange>();
        var awaitedInMemoryOnly = new ArrayList<LongRange>();
        var nextMissing         = fromInclusive;
        for (var order : eventOrders.filter(order -> order >= fromInclusive).sorted().distinct().toArray()) {
            if (order > nextMissing) {
                var lastMissing = order - 1;
                if (lastMissing - nextMissing + 1 > 2L * MAX_AWAITED_ORDERS_PER_GAP_END) {
                    recorded.add(LongRange.between(nextMissing, nextMissing + MAX_AWAITED_ORDERS_PER_GAP_END - 1));
                    awaitedInMemoryOnly.add(LongRange.between(nextMissing + MAX_AWAITED_ORDERS_PER_GAP_END, lastMissing - MAX_AWAITED_ORDERS_PER_GAP_END));
                    recorded.add(LongRange.between(lastMissing - MAX_AWAITED_ORDERS_PER_GAP_END + 1, lastMissing));
                } else {
                    recorded.add(LongRange.between(nextMissing, lastMissing));
                }
            }
            nextMissing = order + 1;
        }
        return recorded.isEmpty() ? NONE : new GapEnds(List.copyOf(recorded), List.copyOf(awaitedInMemoryOnly));
    }
}
