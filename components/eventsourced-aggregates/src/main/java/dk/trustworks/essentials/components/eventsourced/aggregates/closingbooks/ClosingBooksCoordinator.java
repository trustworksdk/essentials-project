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

import java.time.Clock;
import java.time.OffsetDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Coordinates generation lifecycle transitions for one {@link AggregateType}.
 * <p>
 * The coordinator encapsulates the common closing-books flow of resolving the current generation,
 * opening the first generation on demand, and rolling from one generation to the next.
 */
public class ClosingBooksCoordinator<ID> {
    private final AggregateType                          aggregateType;
    private final ClosingBooksGenerationRepository<ID>   generationRepository;
    private final ClosingBooksStreamIdGenerator<ID>      streamIdGenerator;
    private final Clock                                  clock;

    /**
     * Initializes an instance of {@code ClosingBooksCoordinator} with the specified configurations.
     *
     * @param aggregateType the type of the aggregate to be managed
     * @param generationRepository the repository responsible for handling closing book generation data
     * @param streamIdGenerator the generator used to create unique stream IDs for closing book operations
     */
    public ClosingBooksCoordinator(AggregateType aggregateType,
                                   ClosingBooksGenerationRepository<ID> generationRepository,
                                   ClosingBooksStreamIdGenerator<ID> streamIdGenerator) {
        this(aggregateType,
             generationRepository,
             streamIdGenerator,
             Clock.systemUTC());
    }

    /**
     * Constructs a new instance of {@code ClosingBooksCoordinator}, initializing the required
     * dependencies for managing the lifecycle and operations related to closing book generations.
     *
     * @param aggregateType the type of the aggregate to be managed
     * @param generationRepository the repository responsible for handling closing book generation data
     * @param streamIdGenerator the generator used to create unique stream IDs for closing book operations
     * @param clock the clock instance used to provide the current time for generation-related operations
     */
    public ClosingBooksCoordinator(AggregateType aggregateType,
                                   ClosingBooksGenerationRepository<ID> generationRepository,
                                   ClosingBooksStreamIdGenerator<ID> streamIdGenerator,
                                   Clock clock) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.generationRepository = requireNonNull(generationRepository, "No generationRepository provided");
        this.streamIdGenerator = requireNonNull(streamIdGenerator, "No streamIdGenerator provided");
        this.clock = requireNonNull(clock, "No clock provided");
    }

    /**
     * Resolve the current open generation or open generation {@code 1} if no generation exists yet.
     */
    public java.util.Optional<AggregateGeneration<ID>> resolveCurrentGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId);
    }

    /**
     * Resolve all known generations for the provided logical aggregate id.
     */
    public java.util.List<AggregateGeneration<ID>> loadGenerations(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return generationRepository.loadGenerations(aggregateType, logicalAggregateId);
    }

    /**
     * Resolve the current open generation or open generation {@code 1} if no generation exists yet.
     */
    public AggregateGeneration<ID> resolveOrOpenCurrentGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId)
                                   .orElseGet(() -> openFirstGeneration(logicalAggregateId));
    }

    /**
     * Close the current generation and immediately open the next generation.
     *
     * @throws IllegalStateException if no open generation exists
     */
    public AggregateGeneration<ID> closeAndOpenNextGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");

        var currentGeneration = generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId)
                                                   .orElseThrow(() -> new IllegalStateException("No open generation exists for logicalAggregateId '" + logicalAggregateId + "'"));
        generationRepository.closeCurrentGeneration(aggregateType, logicalAggregateId);
        var nextGenerationNumber = currentGeneration.generation() + 1;
        var nextStreamAggregateId = streamIdGenerator.generate(aggregateType,
                                                               logicalAggregateId,
                                                               nextGenerationNumber);
        return generationRepository.openNextGeneration(aggregateType,
                                                       logicalAggregateId,
                                                       nextStreamAggregateId);
    }

    /**
     * Evaluate a closing-books policy against the current generation and aggregate instance.
     */
    public <AGGREGATE> AggregateGeneration<ID> evaluatePolicy(LogicalAggregateId<ID> logicalAggregateId,
                                                              AGGREGATE aggregate,
                                                              ClosingBooksTriggerMode triggerMode,
                                                              ClosingBooksDecisionPolicy<ID, AGGREGATE> policy) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(aggregate, "No aggregate provided");
        requireNonNull(triggerMode, "No triggerMode provided");
        requireNonNull(policy, "No policy provided");

        var currentGeneration = resolveOrOpenCurrentGeneration(logicalAggregateId);
        var decision = policy.decide(new ClosingBooksEvaluationContext<>(aggregateType,
                                                                         logicalAggregateId,
                                                                         currentGeneration,
                                                                         aggregate,
                                                                         triggerMode,
                                                                         OffsetDateTime.now(clock)));

        return switch (decision) {
            case KEEP_OPEN -> currentGeneration;
            case CLOSE_ONLY -> generationRepository.closeCurrentGeneration(aggregateType, logicalAggregateId);
            case CLOSE_AND_OPEN_NEXT -> closeAndOpenNextGeneration(logicalAggregateId);
        };
    }

    private AggregateGeneration<ID> openFirstGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        var streamAggregateId = streamIdGenerator.generate(aggregateType,
                                                           logicalAggregateId,
                                                           1L);
        return generationRepository.openNextGeneration(aggregateType,
                                                       logicalAggregateId,
                                                       streamAggregateId);
    }
}
