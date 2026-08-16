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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Builder for {@link ClosingBooksSetup}. Obtain one via
 * {@link ClosingBooksSetup#builder(AggregateType, Class)}.
 * <table>
 *     <caption>Setters and defaults</caption>
 *     <tr><th>Setter</th><th>Required</th><th>Default</th></tr>
 *     <tr><td>{@code setLogicalAggregateIdType} / {@code setLogicalAggregateIdSerializer}</td><td>one of the two</td><td>-</td></tr>
 *     <tr><td>{@code setStreamIdType} / {@code setStreamIdSerializer}</td><td>one of the two</td><td>-</td></tr>
 *     <tr><td>{@code setUnitOfWorkFactory}</td><td>yes</td><td>-</td></tr>
 *     <tr><td>{@code setGenerationRepository}</td><td>no</td>
 *         <td>{@link PostgresqlClosingBooksGenerationRepository} on the given unit-of-work factory</td></tr>
 *     <tr><td>{@code setGenerationRepositoryTableName}</td><td>no</td><td>the repository's own default</td></tr>
 *     <tr><td>{@code setStreamIdGenerator}</td><td>no</td><td>{@code logicalAggregateId + "#" + generation}</td></tr>
 *     <tr><td>{@code setClock}</td><td>no</td><td>{@link Clock#systemUTC()}</td></tr>
 *     <tr><td>{@code setMeterRegistry}</td><td>no</td><td>{@link Optional#empty()}</td></tr>
 * </table>
 * The two {@code *IdType} setters route through {@link ClosingBooksIdSerializer#forType(Class)}, so an id type it
 * cannot derive fails here rather than at the first generation resolve.
 *
 * @param <LOGICAL_ID> the logical/business aggregate id type
 * @param <STREAM_ID>  the generation stream id type
 */
public final class ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> {
    /**
     * The format the demo and every example use, promoted to a framework default so an application that does not care
     * never has to name it. An application with existing persisted stream ids in another format <b>must</b> keep
     * setting {@link #setStreamIdGenerator(ClosingBooksStreamIdGenerator)} - which is why this is a documented default
     * rather than a silent one.
     */
    public static <ID> ClosingBooksStreamIdGenerator<ID> defaultStreamIdGenerator() {
        return (aggregateType, logicalAggregateId, nextGeneration) -> logicalAggregateId.value() + "#" + nextGeneration;
    }

    private final AggregateType aggregateType;
    private final Class<?>      aggregateImplementationType;

    private ClosingBooksIdSerializer<LOGICAL_ID>                          logicalAggregateIdSerializer;
    private ClosingBooksIdSerializer<STREAM_ID>                           streamIdSerializer;
    private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private ClosingBooksGenerationRepository<LOGICAL_ID>                  generationRepository;
    private Optional<String>                                             generationRepositoryTableName = Optional.empty();
    private ClosingBooksStreamIdGenerator<LOGICAL_ID>                     streamIdGenerator             = defaultStreamIdGenerator();
    private Clock                                                        clock                         = Clock.systemUTC();
    private Optional<MeterRegistry>                                      meterRegistry                 = Optional.empty();

    ClosingBooksSetupBuilder(AggregateType aggregateType,
                             Class<?> aggregateImplementationType) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.aggregateImplementationType = requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
    }

    /**
     * Derives the logical-aggregate-id serializer from the id type.
     *
     * @param logicalAggregateIdType the logical aggregate id type; must not be null
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setLogicalAggregateIdType(Class<LOGICAL_ID> logicalAggregateIdType) {
        return setLogicalAggregateIdSerializer(ClosingBooksIdSerializer.forType(requireNonNull(logicalAggregateIdType, "No logicalAggregateIdType provided")));
    }

    /**
     * @param logicalAggregateIdSerializer the logical-aggregate-id serializer; must not be null
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setLogicalAggregateIdSerializer(ClosingBooksIdSerializer<LOGICAL_ID> logicalAggregateIdSerializer) {
        this.logicalAggregateIdSerializer = requireNonNull(logicalAggregateIdSerializer, "No logicalAggregateIdSerializer provided");
        return this;
    }

    /**
     * Derives the generation-stream-id serializer from the id type.
     *
     * @param streamIdType the generation stream id type; must not be null
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setStreamIdType(Class<STREAM_ID> streamIdType) {
        return setStreamIdSerializer(ClosingBooksIdSerializer.forType(requireNonNull(streamIdType, "No streamIdType provided")));
    }

    /**
     * @param streamIdSerializer the generation-stream-id serializer; must not be null
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setStreamIdSerializer(ClosingBooksIdSerializer<STREAM_ID> streamIdSerializer) {
        this.streamIdSerializer = requireNonNull(streamIdSerializer, "No streamIdSerializer provided");
        return this;
    }

    /**
     * Always required. Supplying your own {@link #setGenerationRepository(ClosingBooksGenerationRepository)} does not
     * remove the need for it: {@link ClosingBooksCoordinator} runs close-and-open-next in a single unit of work.
     *
     * @param unitOfWorkFactory the unit-of-work factory the default generation repository and the coordinator use;
     *                          must not be null
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        return this;
    }

    /**
     * An explicit generation repository, instead of the default
     * {@link PostgresqlClosingBooksGenerationRepository}.
     *
     * @param generationRepository the generation repository; must not be null
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setGenerationRepository(ClosingBooksGenerationRepository<LOGICAL_ID> generationRepository) {
        this.generationRepository = requireNonNull(generationRepository, "No generationRepository provided");
        return this;
    }

    /**
     * @param generationRepositoryTableName the table name for the default generation repository; must not be null, may
     *                                      be {@link Optional#empty()}
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setGenerationRepositoryTableName(Optional<String> generationRepositoryTableName) {
        this.generationRepositoryTableName = requireNonNull(generationRepositoryTableName, "No generationRepositoryTableName Optional provided");
        return this;
    }

    /**
     * @param streamIdGenerator how a generation names its event stream; must not be null
     * @return this builder
     * @see #defaultStreamIdGenerator()
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setStreamIdGenerator(ClosingBooksStreamIdGenerator<LOGICAL_ID> streamIdGenerator) {
        this.streamIdGenerator = requireNonNull(streamIdGenerator, "No streamIdGenerator provided");
        return this;
    }

    /**
     * @param clock the clock used to timestamp policy evaluations and rollovers; must not be null
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setClock(Clock clock) {
        this.clock = requireNonNull(clock, "No clock provided");
        return this;
    }

    /**
     * @param meterRegistry Micrometer registry; must not be null, may be {@link Optional#empty()}, in which case no
     *                      metrics are recorded. Note that an {@code ON_ACCESS} aggregate never runs a scheduled scan,
     *                      so the coordinator is the only place its rollovers can be measured
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setMeterRegistry(Optional<MeterRegistry> meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "No meterRegistry Optional provided");
        return this;
    }

    /**
     * @param meterRegistry Micrometer registry; must not be null
     * @return this builder
     */
    public ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> setMeterRegistry(MeterRegistry meterRegistry) {
        return setMeterRegistry(Optional.of(requireNonNull(meterRegistry, "No meterRegistry provided")));
    }

    /**
     * @return the assembled {@link ClosingBooksSetup}
     * @throws IllegalArgumentException if a required setter was not called
     */
    @SuppressWarnings("removal")
    public ClosingBooksSetup<LOGICAL_ID, STREAM_ID> build() {
        requireNonNull(logicalAggregateIdSerializer,
                       "No logicalAggregateIdSerializer provided - call setLogicalAggregateIdType(...) or setLogicalAggregateIdSerializer(...)");
        requireNonNull(streamIdSerializer,
                       "No streamIdSerializer provided - call setStreamIdType(...) or setStreamIdSerializer(...)");
        // Always required: the ClosingBooksCoordinator runs close-and-open-next in a single unit of work, so supplying
        // your own generation repository does not remove the need for a factory
        requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided - call setUnitOfWorkFactory(...)");

        var resolvedGenerationRepository = generationRepository != null
                                           ? generationRepository
                                           : new PostgresqlClosingBooksGenerationRepository<LOGICAL_ID>(unitOfWorkFactory,
                                                                                                       generationRepositoryTableName,
                                                                                                       logicalAggregateIdSerializer);

        var resolvedCoordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                               resolvedGenerationRepository,
                                                               streamIdGenerator,
                                                               unitOfWorkFactory,
                                                               clock,
                                                               meterRegistry);

        return new ClosingBooksSetup<>(aggregateType,
                                      aggregateImplementationType,
                                      resolvedGenerationRepository,
                                      resolvedCoordinator,
                                      logicalAggregateIdSerializer,
                                      streamIdSerializer);
    }
}
