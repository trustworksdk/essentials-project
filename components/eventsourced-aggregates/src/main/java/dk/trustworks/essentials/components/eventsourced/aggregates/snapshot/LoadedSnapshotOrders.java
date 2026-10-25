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


package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;

import java.util.*;
import java.util.function.Supplier;

/**
 * What the newest snapshot of an aggregate was when the aggregate was loaded, so deciding whether to add a snapshot
 * after an update does not have to ask the database again.
 * <p>
 * {@code StatefulAggregateRepository} loads an aggregate by asking for its newest snapshot, and an update always
 * follows a load in the same unit of work, so the answer recorded at load is what {@code findMostRecentLastIncludedEventOrder}
 * would return at the update - one query and round trip per aggregate update, inside the command's transaction, saved.
 * The answer can only be stale towards an older order (another instance added a snapshot in between) or a deleted one
 * (deleted elsewhere): the first adds a snapshot a little early, the second at most the policy's interval late. Deletes
 * through the owning repository forget the aggregate.
 * <p>
 * An answer is used once, and only a bounded number are kept, so loads that are never followed by an update cost a
 * slot until they are evicted; without a recorded answer the caller queries, as before.
 */
final class LoadedSnapshotOrders {
    private static final int MAX_ENTRIES = 10_000;

    private record Key(AggregateType aggregateType, Object aggregateId, Class<?> aggregateImplType) {
    }

    private final Map<Key, Optional<EventOrder>> newestAtLoad = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Optional<EventOrder>> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /**
     * Records what a load found, when it asked for the newest snapshot ({@link EventOrder#MAX_EVENT_ORDER})
     */
    <ID, AGGREGATE_IMPL_TYPE> Optional<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> recordLoaded(AggregateType aggregateType,
                                                                                               ID aggregateId,
                                                                                               EventOrder withLastIncludedEventOrderLessThanOrEqualTo,
                                                                                               Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                                                               Optional<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loaded) {
        if (EventOrder.MAX_EVENT_ORDER.equals(withLastIncludedEventOrderLessThanOrEqualTo)) {
            var newest = loaded.map(snapshot -> snapshot.eventOrderOfLastIncludedEvent);
            synchronized (newestAtLoad) {
                newestAtLoad.put(new Key(aggregateType, aggregateId, aggregateImplType), newest);
            }
        }
        return loaded;
    }

    /**
     * @return the newest snapshot's event order recorded when the aggregate was loaded, else what {@code query} answers
     */
    Optional<EventOrder> newestSnapshotEventOrder(AggregateType aggregateType, Object aggregateId, Class<?> aggregateImplType,
                                                  Supplier<Optional<EventOrder>> query) {
        Optional<EventOrder> recorded;
        synchronized (newestAtLoad) {
            recorded = newestAtLoad.remove(new Key(aggregateType, aggregateId, aggregateImplType));
        }
        return recorded != null ? recorded : query.get();
    }

    void forget(AggregateType aggregateType, Object aggregateId, Class<?> aggregateImplType) {
        synchronized (newestAtLoad) {
            newestAtLoad.remove(new Key(aggregateType, aggregateId, aggregateImplType));
        }
    }

    void forgetAll(Class<?> aggregateImplType) {
        synchronized (newestAtLoad) {
            newestAtLoad.keySet().removeIf(key -> key.aggregateImplType().equals(aggregateImplType));
        }
    }
}
