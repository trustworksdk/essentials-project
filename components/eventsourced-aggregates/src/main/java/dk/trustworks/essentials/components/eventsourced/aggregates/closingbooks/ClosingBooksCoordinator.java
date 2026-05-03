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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;

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
    private final AggregateType                                                       aggregateType;
    private final ClosingBooksGenerationRepository<ID>                                generationRepository;
    private final ClosingBooksStreamIdGenerator<ID>                                   streamIdGenerator;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork>       unitOfWorkFactory;
    private final Clock                                                               clock;

    public ClosingBooksCoordinator(AggregateType aggregateType,
                                   ClosingBooksGenerationRepository<ID> generationRepository,
                                   ClosingBooksStreamIdGenerator<ID> streamIdGenerator,
                                   HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(aggregateType,
             generationRepository,
             streamIdGenerator,
             unitOfWorkFactory,
             Clock.systemUTC());
    }

    public ClosingBooksCoordinator(AggregateType aggregateType,
                                   ClosingBooksGenerationRepository<ID> generationRepository,
                                   ClosingBooksStreamIdGenerator<ID> streamIdGenerator,
                                   HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                   Clock clock) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.generationRepository = requireNonNull(generationRepository, "No generationRepository provided");
        this.streamIdGenerator = requireNonNull(streamIdGenerator, "No streamIdGenerator provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
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
     * Close the current generation and immediately open the next generation, atomically.
     * <p>
     * The close + open pair runs inside a single {@link HandleAwareUnitOfWork}: a crash or
     * exception between the two repository calls rolls back the close, leaving the previous
     * generation {@code OPEN} for safe retry rather than stranding the aggregate with no open
     * generation.
     *
     * @throws IllegalStateException if no open generation exists
     */
    public AggregateGeneration<ID> closeAndOpenNextGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return unitOfWorkFactory.withUnitOfWork(uow -> {
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
        });
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
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            // Compute the actual next generation number from any existing closed rows so the
            // stream-id matches the row inserted by the repository (which also uses MAX+1).
            var existing = generationRepository.loadGenerations(aggregateType, logicalAggregateId);
            var nextGeneration = existing.stream()
                                          .mapToLong(AggregateGeneration::generation)
                                          .max()
                                          .orElse(0L) + 1L;
            var streamAggregateId = streamIdGenerator.generate(aggregateType,
                                                               logicalAggregateId,
                                                               nextGeneration);
            return generationRepository.openNextGeneration(aggregateType,
                                                           logicalAggregateId,
                                                           streamAggregateId);
        });
    }
}
