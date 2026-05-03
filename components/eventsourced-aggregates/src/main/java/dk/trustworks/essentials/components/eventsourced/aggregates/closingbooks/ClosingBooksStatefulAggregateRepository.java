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

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregate;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * This repository provides functionality to manage stateful aggregates with support for multiple generations.
 * It acts as a wrapper around a delegate {@code StatefulAggregateRepository} and integrates additional
 * behavior for handling logical aggregate IDs and generation resolution in the context of closing books.
 *
 * @param <LOGICAL_ID>          The type of the logical aggregate identifier.
 * @param <EVENT_TYPE>          The type of events applicable to aggregates managed by this repository.
 * @param <AGGREGATE_IMPL_TYPE> The specific implementation type of the {@code StatefulAggregate}.
 *                              This type must extend {@code StatefulAggregate<String, EVENT_TYPE, AGGREGATE_IMPL_TYPE>}.
 */
public class ClosingBooksStatefulAggregateRepository<LOGICAL_ID,
        EVENT_TYPE,
        AGGREGATE_IMPL_TYPE extends StatefulAggregate<String, EVENT_TYPE, AGGREGATE_IMPL_TYPE>> {
    private final AggregateType                                                        aggregateType;
    private final StatefulAggregateRepository<String, EVENT_TYPE, AGGREGATE_IMPL_TYPE> delegate;
    private final ClosingBooksGenerationResolver<LOGICAL_ID>                           generationResolver;

    /**
     * Constructs an instance of ClosingBooksStatefulAggregateRepository.
     *
     * @param aggregateType      The type of the aggregate that this repository supports.
     *                           Must not be null.
     * @param delegate           The delegate repository for handling stateful aggregates.
     *                           Must not be null.
     * @param generationResolver The generation resolver used to manage aggregate generations.
     *                           Must not be null.
     */
    public ClosingBooksStatefulAggregateRepository(AggregateType aggregateType,
                                                   StatefulAggregateRepository<String, EVENT_TYPE, AGGREGATE_IMPL_TYPE> delegate,
                                                   ClosingBooksGenerationResolver<LOGICAL_ID> generationResolver) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.delegate = requireNonNull(delegate, "No delegate provided");
        this.generationResolver = requireNonNull(generationResolver, "No generationResolver provided");
    }

    public AggregateType aggregateType() {
        return aggregateType;
    }

    public Optional<AggregateGeneration<LOGICAL_ID>> resolveCurrentGeneration(LogicalAggregateId<LOGICAL_ID> logicalAggregateId) {
        return generationResolver.resolveCurrentGeneration(aggregateType, logicalAggregateId);
    }

    public List<AggregateGeneration<LOGICAL_ID>> loadGenerations(LogicalAggregateId<LOGICAL_ID> logicalAggregateId) {
        return generationResolver.loadGenerations(aggregateType, logicalAggregateId);
    }

    public AggregateGeneration<LOGICAL_ID> openNextGeneration(LogicalAggregateId<LOGICAL_ID> logicalAggregateId,
                                                              String streamAggregateId) {
        return generationResolver.openNextGeneration(aggregateType,
                                                     logicalAggregateId,
                                                     streamAggregateId);
    }

    public AggregateGeneration<LOGICAL_ID> closeCurrentGeneration(LogicalAggregateId<LOGICAL_ID> logicalAggregateId) {
        return generationResolver.closeCurrentGeneration(aggregateType, logicalAggregateId);
    }

    public Optional<AGGREGATE_IMPL_TYPE> tryLoad(LogicalAggregateId<LOGICAL_ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return resolveCurrentGeneration(logicalAggregateId).flatMap(generation -> delegate.tryLoad(generation.streamAggregateId()));
    }

    public AGGREGATE_IMPL_TYPE load(LogicalAggregateId<LOGICAL_ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return resolveCurrentGeneration(logicalAggregateId)
                .map(generation -> delegate.load(generation.streamAggregateId()))
                .orElseThrow(() -> new IllegalStateException("No open generation exists for logicalAggregateId '" + logicalAggregateId + "'"));
    }

    public AGGREGATE_IMPL_TYPE save(AGGREGATE_IMPL_TYPE aggregate) {
        requireNonNull(aggregate, "No aggregate provided");
        return delegate.save(aggregate);
    }
}
