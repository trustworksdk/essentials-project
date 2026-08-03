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
import java.util.function.Supplier;

/**
 * Resolves and mutates aggregate generation metadata used for closing-books rollovers.
 */
public interface ClosingBooksGenerationResolver<ID> {
    /**
     * Run {@code rollover} with exclusive access to the generation state of one logical aggregate, so a
     * resolve-then-act sequence cannot interleave with another rollover of the same logical aggregate.
     * <p>
     * Rollovers are read-then-write: resolve the open generation, decide, close it, open the next. Without
     * serialization two callers can resolve the same open generation and both act on it, leaving only the storage
     * constraint to catch them — and that surfaces as an opaque failure in the middle of whatever business operation
     * triggered the rollover, rather than the outcome
     * {@link #openNextGeneration(AggregateType, LogicalAggregateId, String)} and
     * {@link #closeCurrentGeneration(AggregateType, LogicalAggregateId)} document.
     * <p>
     * The default implementation gives no isolation and simply runs {@code rollover}, which is correct for
     * implementations that are single-threaded or hold no shared state. Implementations serving concurrent callers
     * should override it.
     *
     * @param rollover the critical section; run exactly once
     * @return whatever {@code rollover} returns
     */
    default <R> R withGenerationLock(AggregateType aggregateType,
                                     LogicalAggregateId<ID> logicalAggregateId,
                                     Supplier<R> rollover) {
        return rollover.get();
    }

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
