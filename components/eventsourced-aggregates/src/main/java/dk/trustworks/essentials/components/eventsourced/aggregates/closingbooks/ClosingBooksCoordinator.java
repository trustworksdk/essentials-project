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
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

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
    private final ClosingBooksManagementMeasurementSupport                            measurementSupport;

    /**
     * Constructs a new instance of {@code ClosingBooksCoordinator}.
     *
     * @param aggregateType The type of the aggregate being coordinated.
     * @param generationRepository The repository for managing closing-book generations.
     * @param streamIdGenerator The generator for creating unique stream IDs for aggregate instances.
     * @param unitOfWorkFactory The factory for creating instances of {@code HandleAwareUnitOfWork}.
     */
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
        this(aggregateType,
             generationRepository,
             streamIdGenerator,
             unitOfWorkFactory,
             clock,
             Optional.empty());
    }

    /**
     * Constructs a new instance of {@code ClosingBooksCoordinator} that reports rollover metrics.
     *
     * @param aggregateType         The type of the aggregate being coordinated.
     * @param generationRepository  The repository for managing closing-book generations.
     * @param streamIdGenerator     The generator for creating unique stream IDs for aggregate instances.
     * @param unitOfWorkFactory     The factory for creating instances of {@code HandleAwareUnitOfWork}.
     * @param clock                 The clock used to timestamp policy evaluations and rollovers.
     * @param meterRegistryOptional Optional Micrometer registry. When empty, no metrics are recorded.
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Deprecated(forRemoval = true, since = "0.40.x")
    public ClosingBooksCoordinator(AggregateType aggregateType,
                                   ClosingBooksGenerationRepository<ID> generationRepository,
                                   ClosingBooksStreamIdGenerator<ID> streamIdGenerator,
                                   HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                   Clock clock,
                                   Optional<MeterRegistry> meterRegistryOptional) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.generationRepository = requireNonNull(generationRepository, "No generationRepository provided");
        this.streamIdGenerator = requireNonNull(streamIdGenerator, "No streamIdGenerator provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.clock = requireNonNull(clock, "No clock provided");
        this.measurementSupport = new ClosingBooksManagementMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
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
                                   .orElseGet(() -> generationRepository.withGenerationLock(aggregateType,
                                                                                            logicalAggregateId,
                                                                                            // Re-resolved under the lock: another caller may have opened the
                                                                                            // first generation between the resolve above and this point.
                                                                                            () -> generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId)
                                                                                                                      .orElseGet(() -> openFirstGeneration(logicalAggregateId))));
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
        // The counters are incremented after the UnitOfWork returns, never inside it: a rollback must not leave a
        // count claiming a generation was closed that the database then rolled back.
        try {
            var nextGeneration = measurementSupport.recordRollover(aggregateType,
                                                                    () -> generationRepository.withGenerationLock(aggregateType, logicalAggregateId, () -> unitOfWorkFactory.withUnitOfWork(uow -> {
                                                                        generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId)
                                                                                            .orElseThrow(() -> new IllegalStateException("No open generation exists for logicalAggregateId '" + logicalAggregateId + "'"));
                                                                        generationRepository.closeCurrentGeneration(aggregateType, logicalAggregateId);
                                                                        // The repository decides the next generation number and feeds it to the generator, so the stream id it
                                                                        // persists always names the generation on the row it is stored with.
                                                                        return generationRepository.openNextGeneration(aggregateType,
                                                                                                                       logicalAggregateId,
                                                                                                                       streamIdGenerator);
                                                                    })));
            measurementSupport.incrementGenerationsClosed(aggregateType);
            measurementSupport.incrementGenerationsOpened(aggregateType);
            measurementSupport.incrementRolloverOutcome(aggregateType, "succeeded");
            measurementSupport.recordRolloverTimestamp(aggregateType, clock.millis());
            return nextGeneration;
        } catch (RuntimeException e) {
            measurementSupport.incrementRolloverOutcome(aggregateType, "failed");
            throw e;
        }
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

        // The whole evaluation is one critical section: the policy decides against the generation resolved here, and
        // acting on a generation another caller has meanwhile closed or rolled would either fail outright or close a
        // generation the policy never saw. Both lock implementations are reentrant, so the nested acquisition in
        // closeAndOpenNextGeneration is a no-op.
        return generationRepository.withGenerationLock(aggregateType, logicalAggregateId, () -> {
            var currentGeneration = resolveOrOpenCurrentGeneration(logicalAggregateId);
            var decision = policy.decide(new ClosingBooksEvaluationContext<>(aggregateType,
                                                                             logicalAggregateId,
                                                                             currentGeneration,
                                                                             aggregate,
                                                                             triggerMode,
                                                                             OffsetDateTime.now(clock)));

            measurementSupport.incrementPolicyDecision(aggregateType, decision, triggerMode);

            return switch (decision) {
                case KEEP_OPEN -> currentGeneration;
                case CLOSE_ONLY -> {
                    var closed = generationRepository.closeCurrentGeneration(aggregateType, logicalAggregateId);
                    measurementSupport.incrementGenerationsClosed(aggregateType);
                    yield closed;
                }
                // Counts its own generations_closed / generations_opened, so nothing is added here.
                case CLOSE_AND_OPEN_NEXT -> closeAndOpenNextGeneration(logicalAggregateId);
            };
        });
    }

    private AggregateGeneration<ID> openFirstGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        // "First" only in the sense of the first one currently open: closed generations may already exist, and the
        // repository numbers past them. It used to load every generation here to work that number out for the stream
        // id, duplicating the repository's own rule; the repository now supplies it to the generator instead.
        var generation = unitOfWorkFactory.withUnitOfWork(uow -> generationRepository.openNextGeneration(aggregateType,
                                                                                                        logicalAggregateId,
                                                                                                        streamIdGenerator));
        measurementSupport.incrementGenerationsOpened(aggregateType);
        return generation;
    }

    /**
     * Creates a builder for a {@link ClosingBooksCoordinator}.
     *
     * @param <ID> the aggregate id type
     * @return a new builder
     */
    public static <ID> Builder<ID> builder() {
        return new Builder<>();
    }

    /**
     * Builder for {@link ClosingBooksCoordinator}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload.
     */
    public static final class Builder<ID> {
        private AggregateType aggregateType;
        private ClosingBooksGenerationRepository<ID> generationRepository;
        private ClosingBooksStreamIdGenerator<ID> streamIdGenerator;
        private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
        private Clock clock;
        private MeterRegistry meterRegistryOptional;

        /**
         * @param aggregateType required
         * @return this builder
         */
        public Builder<ID> setAggregateType(AggregateType aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        /**
         * @param generationRepository required
         * @return this builder
         */
        public Builder<ID> setGenerationRepository(ClosingBooksGenerationRepository<ID> generationRepository) {
            this.generationRepository = generationRepository;
            return this;
        }

        /**
         * @param streamIdGenerator required
         * @return this builder
         */
        public Builder<ID> setStreamIdGenerator(ClosingBooksStreamIdGenerator<ID> streamIdGenerator) {
            this.streamIdGenerator = streamIdGenerator;
            return this;
        }

        /**
         * @param unitOfWorkFactory required
         * @return this builder
         */
        public Builder<ID> setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            return this;
        }

        /**
         * @param clock required
         * @return this builder
         */
        public Builder<ID> setClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * @param meterRegistryOptional optional — {@code null} selects the default
         * @return this builder
         */
        public Builder<ID> setMeterRegistry(MeterRegistry meterRegistryOptional) {
            this.meterRegistryOptional = meterRegistryOptional;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setMeterRegistry}.
         *
         * @param meterRegistryOptional the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder<ID> setMeterRegistry(Optional<MeterRegistry> meterRegistryOptional) {
            requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided");
            return setMeterRegistry(meterRegistryOptional.orElse(null));
        }

        /**
         * @return the new {@link ClosingBooksCoordinator}
         */
        @SuppressWarnings("removal")
        public ClosingBooksCoordinator<ID> build() {
            return new ClosingBooksCoordinator<>(aggregateType,
                                                   generationRepository,
                                                   streamIdGenerator,
                                                   unitOfWorkFactory,
                                                   clock,
                                                   Optional.ofNullable(meterRegistryOptional));
        }
    }

}
