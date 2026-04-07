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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import java.util.List;
import java.util.Optional;

/**
 * Resolves and mutates aggregate generation metadata used for closing-books rollovers.
 */
public interface ClosingBooksGenerationResolver<ID> {
    /**
     * Resolve the currently open generation for the logical aggregate, if one exists.
     */
    Optional<AggregateGeneration<ID>> resolveCurrentGeneration(AggregateType aggregateType,
                                                               LogicalAggregateId<ID> logicalAggregateId);

    /**
     * Load all known generations for the logical aggregate in repository-defined order.
     */
    List<AggregateGeneration<ID>> loadGenerations(AggregateType aggregateType,
                                                  LogicalAggregateId<ID> logicalAggregateId);

    /**
     * Open the next generation for the logical aggregate.
     *
     * @throws IllegalStateException if an open generation already exists or the generation cannot be persisted
     */
    AggregateGeneration<ID> openNextGeneration(AggregateType aggregateType,
                                               LogicalAggregateId<ID> logicalAggregateId,
                                               String streamAggregateId);

    /**
     * Close the currently open generation for the logical aggregate.
     *
     * @throws IllegalStateException if no open generation exists or the generation cannot be updated
     */
    AggregateGeneration<ID> closeCurrentGeneration(AggregateType aggregateType,
                                                   LogicalAggregateId<ID> logicalAggregateId);
}
