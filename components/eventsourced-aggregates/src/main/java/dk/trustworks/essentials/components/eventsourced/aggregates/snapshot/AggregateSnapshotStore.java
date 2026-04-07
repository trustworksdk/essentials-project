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

/**
 * Storage abstraction for loading, persisting, and deleting aggregate snapshots.
 */
public interface AggregateSnapshotStore {
    /**
     * Loads a snapshot for the specified aggregate type and identifier, where the last included event order in the
     * snapshot is less than or equal to the specified event order.
     *
     * @param <ID>                                the type of the aggregate identifier
     * @param <AGGREGATE_IMPL_TYPE>               the type of the aggregate implementation
     * @param aggregateType                       the type of the aggregate whose snapshot is to be loaded
     * @param aggregateId                         the identifier for the aggregate instance
     * @param withLastIncludedEventOrderLessThanOrEqualTo
     *                                            the maximum event order of the last included event in the snapshot
     * @param aggregateImplType                   the class type of the aggregate implementation
     * @return                                    an {@link Optional} containing the loaded snapshot if found;
     *                                            otherwise, an empty {@link Optional}
     */
    <ID, AGGREGATE_IMPL_TYPE> Optional<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadSnapshot(AggregateType aggregateType,
                                                                                                ID aggregateId,
                                                                                                EventOrder withLastIncludedEventOrderLessThanOrEqualTo,
                                                                                                Class<AGGREGATE_IMPL_TYPE> aggregateImplType);

    /**
     * Loads all snapshots associated with the specified aggregate type and identifier.
     *
     * @param <ID>                    the type of the aggregate identifier
     * @param <AGGREGATE_IMPL_TYPE>   the type of the aggregate implementation
     * @param aggregateType           the type of the aggregate whose snapshots are to be loaded
     * @param aggregateId             the identifier for the aggregate instance
     * @param aggregateImplType       the class type of the aggregate implementation
     * @param includeSnapshotPayload  a flag indicating whether the snapshot payload should be included in the result
     * @return                        a list of {@link AggregateSnapshot} instances matching the specified criteria
     */
    <ID, AGGREGATE_IMPL_TYPE> List<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadAllSnapshots(AggregateType aggregateType,
                                                                                                ID aggregateId,
                                                                                                Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                                                                boolean includeSnapshotPayload);

    /**
     * Finds the most recent {@link EventOrder} of the last included event in a snapshot
     * for the specified aggregate type and identifier.
     *
     * @param <ID>                  the type of the aggregate identifier
     * @param <AGGREGATE_IMPL_TYPE> the type of the aggregate implementation
     * @param aggregateType         the type of the aggregate whose snapshot information is to be queried
     * @param aggregateId           the identifier for the aggregate instance
     * @param aggregateImplType     the class type of the aggregate implementation
     * @return                      an {@link Optional} containing the most recent {@link EventOrder}
     *                              of the last included event in a snapshot if found; otherwise, an empty {@link Optional}
     */
    <ID, AGGREGATE_IMPL_TYPE> Optional<EventOrder> findMostRecentLastIncludedEventOrder(AggregateType aggregateType,
                                                                                        ID aggregateId,
                                                                                        Class<AGGREGATE_IMPL_TYPE> aggregateImplType);

    /**
     * Persists a snapshot for the specified aggregate type and identifier with the
     * provided details.
     *
     * @param <ID>                     the type of the aggregate identifier
     * @param <AGGREGATE_IMPL_TYPE>    the type of the aggregate implementation
     * @param aggregateType            the type of the aggregate for which the snapshot is saved
     * @param aggregateId              the identifier of the aggregate instance
     * @param aggregateImplType        the class type of the aggregate implementation
     * @param lastIncludedEventOrder   the order of the last event included in the snapshot
     * @param serializedSnapshot       the serialized representation of the snapshot
     */
    <ID, AGGREGATE_IMPL_TYPE> void saveSnapshot(AggregateType aggregateType,
                                                ID aggregateId,
                                                Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                EventOrder lastIncludedEventOrder,
                                                String serializedSnapshot);

    /**
     * Deletes all snapshots associated with the specified aggregate implementation type.
     *
     * @param <AGGREGATE_IMPL_TYPE> the type of the aggregate implementation
     * @param ofAggregateImplementationType the class type of the aggregate implementation whose snapshots
     *                                       are to be deleted
     */
    <AGGREGATE_IMPL_TYPE> void deleteAllSnapshots(Class<AGGREGATE_IMPL_TYPE> ofAggregateImplementationType);

    /**
     * Deletes snapshots associated with the specified aggregate type, identifier,
     * and aggregate implementation type.
     *
     * @param <ID>                           the type of the aggregate identifier
     * @param <AGGREGATE_IMPL_TYPE>          the type of the aggregate implementation
     * @param aggregateType                  the type of the aggregate whose snapshots are to be deleted
     * @param aggregateId                    the identifier of the aggregate instance
     * @param withAggregateImplementationType the class type of the aggregate implementation
     *                                        whose snapshots are to be deleted
     */
    <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshots(AggregateType aggregateType,
                                                   ID aggregateId,
                                                   Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType);

    /**
     * Deletes specific snapshots associated with the given aggregate type, identifier,
     * aggregate implementation type, and event orders.
     *
     * @param <ID>                           the type of the aggregate identifier
     * @param <AGGREGATE_IMPL_TYPE>          the type of the aggregate implementation
     * @param aggregateType                  the type of the aggregate whose snapshots are to be deleted
     * @param aggregateId                    the identifier of the aggregate instance
     * @param withAggregateImplementationType the class type of the aggregate implementation
     *                                        whose snapshots are to be deleted
     * @param snapshotEventOrdersToDelete    the list of event orders corresponding to the snapshots
     *                                        that need to be deleted
     */
    <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshots(AggregateType aggregateType,
                                                   ID aggregateId,
                                                   Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType,
                                                   List<EventOrder> snapshotEventOrdersToDelete);
}
