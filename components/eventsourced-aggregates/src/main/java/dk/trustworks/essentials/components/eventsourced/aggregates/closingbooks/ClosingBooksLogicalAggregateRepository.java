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

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Aggregate repository facade that keeps application code on logical aggregate ids while
 * internally persisting generation-specific stream ids.
 * <p>
 * This is intended to be the main ergonomic seam for closing-books-aware application services:
 * user-facing code stays on business ids, while the repository resolves and manages internal
 * generation stream ids on its behalf.
 *
 * @param <LOGICAL_ID>        the logical/business aggregate id type
 * @param <EVENT_TYPE>        the event type
 * @param <AGGREGATE_IMPL_TYPE> the aggregate implementation type, which persists against string stream ids
 */
public class ClosingBooksLogicalAggregateRepository<LOGICAL_ID,
                                                    STREAM_ID,
                                                    EVENT_TYPE,
                                                    AGGREGATE_IMPL_TYPE extends StatefulAggregate<STREAM_ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE>> {
    private final AggregateType                                                           aggregateType;
    private final StatefulAggregateRepository<STREAM_ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE> delegate;
    private final ClosingBooksCoordinator<LOGICAL_ID>                                     coordinator;
    private final ClosingBooksStreamIdSerializer<STREAM_ID>                               streamIdSerializer;

    /**
     * Constructs an instance of ClosingBooksLogicalAggregateRepository.
     *
     * @param aggregateType        The type of the aggregate this repository manages.
     * @param delegate             The delegate repository responsible for stateful management of aggregates.
     * @param coordinator          The coordinator used to manage the lifecycle and coordination of logical aggregates.
     * @param streamIdSerializer   The serializer for converting stream IDs to and from their storage representation.
     */
    public ClosingBooksLogicalAggregateRepository(AggregateType aggregateType,
                                                  StatefulAggregateRepository<STREAM_ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE> delegate,
                                                  ClosingBooksCoordinator<LOGICAL_ID> coordinator,
                                                  ClosingBooksStreamIdSerializer<STREAM_ID> streamIdSerializer) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.delegate = requireNonNull(delegate, "No delegate provided");
        this.coordinator = requireNonNull(coordinator, "No coordinator provided");
        this.streamIdSerializer = requireNonNull(streamIdSerializer, "No streamIdSerializer provided");
    }

    public AggregateType aggregateType() {
        return aggregateType;
    }

    /**
     * Resolves the current generation for a given logical aggregate identifier.
     * If no generation exists, it resolves or opens the initial generation.
     *
     * @param logicalAggregateId the identifier of the logical business aggregate whose current generation is to be resolved
     * @return an {@code Optional} containing the current generation, or an empty {@code Optional} if no generation is found
     */
    public Optional<AggregateGeneration<LOGICAL_ID>> resolveCurrentGeneration(LogicalAggregateId<LOGICAL_ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return coordinator.resolveCurrentGeneration(logicalAggregateId);
    }

    /**
     * Resolves the current open generation for the specified logical aggregate identifier
     * or opens the first generation if no generation exists.
     *
     * @param logicalAggregateId the identifier of the logical aggregate for which the current generation is to be resolved or opened
     * @return the resolved or newly opened current generation for the specified logical aggregate
     */
    public AggregateGeneration<LOGICAL_ID> resolveOrOpenCurrentGeneration(LogicalAggregateId<LOGICAL_ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
    }

    /**
     * Attempts to load the aggregate instance associated with the specified logical aggregate identifier.
     * The method resolves the current generation of the logical aggregate and attempts to load the corresponding
     * aggregate instance from the delegate repository.
     *
     * @param logicalAggregateId the identifier of the logical business aggregate whose instance is to be loaded
     * @return an {@code Optional} containing the loaded aggregate instance if it exists, or an empty {@code Optional}
     *         if the instance cannot be found
     * @throws IllegalArgumentException if the provided {@code logicalAggregateId} is {@code null}
     */
    public Optional<AGGREGATE_IMPL_TYPE> tryLoad(LogicalAggregateId<LOGICAL_ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return coordinator.resolveCurrentGeneration(logicalAggregateId)
                          .flatMap(generation -> delegate.tryLoad(deserializeStreamId(generation.streamAggregateId())));
    }

    /**
     * Loads an aggregate instance corresponding to the specified logical aggregate identifier.
     * The method resolves the current generation of the logical aggregate and fetches
     * the associated aggregate instance from the delegate repository.
     *
     * @param logicalAggregateId the identifier of the logical business aggregate to be loaded; must not be null
     * @return the loaded aggregate instance corresponding to the given logical aggregate identifier
     * @throws IllegalArgumentException if the provided {@code logicalAggregateId} is {@code null}
     * @throws IllegalStateException if no open generation exists for the specified {@code logicalAggregateId}
     */
    public AGGREGATE_IMPL_TYPE load(LogicalAggregateId<LOGICAL_ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        var generation = coordinator.resolveCurrentGeneration(logicalAggregateId)
                                    .orElseThrow(() -> new IllegalStateException("No open generation exists for logicalAggregateId '" + logicalAggregateId + "'"));
        return delegate.load(deserializeStreamId(generation.streamAggregateId()));
    }

    /**
     * Opens a new aggregate generation for the specified logical aggregate identifier.
     * If an existing generation is already open for the given identifier, an exception is thrown.
     * The new aggregate instance is created using the provided factory and is persisted.
     *
     * @param logicalAggregateId the identifier of the logical aggregate for which a new generation should be opened; must not be null
     * @param aggregateFactory   the factory used to create the aggregate instance for the new generation; must not be null
     * @return the created aggregate instance persisted for the new generation
     * @throws NullPointerException     if either {@code logicalAggregateId} or {@code aggregateFactory} is null
     * @throws IllegalStateException    if an open generation already exists for the specified {@code logicalAggregateId}
     */
    public AGGREGATE_IMPL_TYPE open(LogicalAggregateId<LOGICAL_ID> logicalAggregateId,
                                    ClosingBooksAggregateFactory<LOGICAL_ID, STREAM_ID, AGGREGATE_IMPL_TYPE> aggregateFactory) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(aggregateFactory, "No aggregateFactory provided");

        if (coordinator.resolveCurrentGeneration(logicalAggregateId).isPresent()) {
            throw new IllegalStateException("An open generation already exists for logicalAggregateId '" + logicalAggregateId + "'");
        }

        var generation = coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
        var aggregate = aggregateFactory.create(new ClosingBooksAggregateInstantiationContext<>(logicalAggregateId,
                                                                                               deserializeStreamId(generation.streamAggregateId()),
                                                                                               generation.generation()));
        return delegate.save(aggregate);
    }

    /**
     * Resolves the current aggregate generation for the provided logical aggregate identifier.
     * If the aggregate instance is found in the current generation, it is loaded and returned.
     * If no instance is found, a new aggregate is opened and returned using the provided factory.
     *
     * @param logicalAggregateId the identifier of the logical aggregate whose state needs to be loaded
     *                           or for which a new aggregate should be opened; must not be null
     * @param aggregateFactory   the factory used to create a new aggregate instance if no instance
     *                           is found in the current generation; must not be null
     * @return the loaded aggregate instance if it exists, or a newly created and opened instance
     *         if no aggregate is found in the current generation
     * @throws IllegalArgumentException if either {@code logicalAggregateId} or {@code aggregateFactory}
     *                              is null
     */
    public AGGREGATE_IMPL_TYPE loadOrOpen(LogicalAggregateId<LOGICAL_ID> logicalAggregateId,
                                          ClosingBooksAggregateFactory<LOGICAL_ID, STREAM_ID, AGGREGATE_IMPL_TYPE> aggregateFactory) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(aggregateFactory, "No aggregateFactory provided");

        return coordinator.resolveCurrentGeneration(logicalAggregateId)
                                               .flatMap(generation -> delegate.tryLoad(deserializeStreamId(generation.streamAggregateId())))
                                               .orElseGet(() -> open(logicalAggregateId, aggregateFactory));
    }

    /**
     * Closes the current generation of the specified logical aggregate and opens the next generation.
     * The new aggregate instance for the next generation is created using the provided factory and is persisted.
     *
     * @param logicalAggregateId the identifier of the logical aggregate for which the current generation
     *                           should be closed and the next generation should be opened; must not be null
     * @param aggregateFactory   the factory used to create the aggregate instance for the next generation;
     *                           must not be null
     * @return the created and persisted aggregate instance for the next generation
     * @throws IllegalArgumentException if either {@code logicalAggregateId} or {@code aggregateFactory} is null
     */
    public AGGREGATE_IMPL_TYPE closeAndOpenNextGeneration(LogicalAggregateId<LOGICAL_ID> logicalAggregateId,
                                                          ClosingBooksAggregateFactory<LOGICAL_ID, STREAM_ID, AGGREGATE_IMPL_TYPE> aggregateFactory) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(aggregateFactory, "No aggregateFactory provided");

        var generation = coordinator.closeAndOpenNextGeneration(logicalAggregateId);
        var nextAggregate = aggregateFactory.create(new ClosingBooksAggregateInstantiationContext<>(logicalAggregateId,
                                                                                                   deserializeStreamId(generation.streamAggregateId()),
                                                                                                   generation.generation()));
        return delegate.save(nextAggregate);
    }

    /**
     * Closes the current aggregate generation and opens the next generation, returning
     * the newly created next-generation aggregate.
     *
     * @param logicalAggregateId the identifier of the logical aggregate to be transitioned to the next generation
     * @param currentAggregate the instance of the current aggregate that is being closed
     * @param hint additional context or data that may assist in creating the next-generation aggregate
     * @param nextGenerationFactory a factory responsible for creating the next-generation aggregate instance
     * @return the newly created next-generation aggregate
     * @throws IllegalArgumentException if any of the parameters are null
     */
    public <HINT> AGGREGATE_IMPL_TYPE closeAndOpenNextGeneration(LogicalAggregateId<LOGICAL_ID> logicalAggregateId,
                                                                 AGGREGATE_IMPL_TYPE currentAggregate,
                                                                 HINT hint,
                                                                 ClosingBooksNextGenerationFactory<LOGICAL_ID, STREAM_ID, AGGREGATE_IMPL_TYPE, HINT> nextGenerationFactory) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(currentAggregate, "No currentAggregate provided");
        requireNonNull(nextGenerationFactory, "No nextGenerationFactory provided");

        var generation = coordinator.closeAndOpenNextGeneration(logicalAggregateId);
        var nextAggregate = nextGenerationFactory.createNextGeneration(currentAggregate,
                                                                       new ClosingBooksAggregateInstantiationContext<>(logicalAggregateId,
                                                                                                                      deserializeStreamId(generation.streamAggregateId()),
                                                                                                                      generation.generation()),
                                                                       hint);
        return delegate.save(nextAggregate);
    }

    public AGGREGATE_IMPL_TYPE save(AGGREGATE_IMPL_TYPE aggregate) {
        requireNonNull(aggregate, "No aggregate provided");
        return delegate.save(aggregate);
    }

    private STREAM_ID deserializeStreamId(String persistedStreamId) {
        return streamIdSerializer.deserialize(persistedStreamId);
    }
}
