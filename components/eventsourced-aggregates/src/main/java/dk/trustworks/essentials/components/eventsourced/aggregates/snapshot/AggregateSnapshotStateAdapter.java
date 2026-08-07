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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;

/**
 * Defines the contract for serializing and deserializing the state of an aggregate
 * into and from a snapshot representation.
 * <p>
 * Implementations of this interface are responsible for transforming an aggregate's
 * state into a serialized format, and reconstructing the aggregate's state from the
 * serialized representation.
 */
public interface AggregateSnapshotStateAdapter {
    /**
     * Serializes the state of the given aggregate into a snapshot representation.
     *
     * @param <AGGREGATE_IMPL_TYPE> the type of the aggregate being serialized
     * @param aggregate the aggregate instance whose state is to be serialized; must not be null
     * @return a serialized string representation of the aggregate's state
     */
    <AGGREGATE_IMPL_TYPE> String serializeSnapshotState(AGGREGATE_IMPL_TYPE aggregate);

    /**
     * Deserializes a serialized snapshot representation into an aggregate instance of the specified type.
     *
     * @param <ID> the type of the identifier of the aggregate
     * @param <AGGREGATE_IMPL_TYPE> the type of the aggregate implementation
     * @param serializedSnapshot the serialized snapshot representation of the aggregate's state; must not be null
     * @param aggregateImplType the class type of the aggregate implementation; must not be null
     * @param aggregateId the unique identifier of the aggregate being reconstructed; must not be null
     * @param eventOrderOfLastIncludedEvent the event order of the last event included in the snapshot; must not be null
     * @return the aggregate instance reconstructed from the serialized snapshot
     */
    <ID, AGGREGATE_IMPL_TYPE> AGGREGATE_IMPL_TYPE deserializeSnapshotState(String serializedSnapshot,
                                                                           Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                                           ID aggregateId,
                                                                           EventOrder eventOrderOfLastIncludedEvent);
}
