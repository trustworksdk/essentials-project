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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A factory implementation for creating instances of {@code AggregateSnapshotRepository}.
 * This factory provides support for creating repositories in various modes such as
 * synchronous snapshots, asynchronous in-memory snapshots, and asynchronous durable snapshots.
 */
public class DefaultAggregateSnapshotRepositoryFactory implements AggregateSnapshotRepositoryFactory {
    private final ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork>               unitOfWorkFactory;
    private final JSONEventSerializer                                                       jsonSerializer;
    private final AggregateSnapshotStore                                                    snapshotStore;
    private final AggregateSnapshotConfigurationResolver                                    resolver;
    private final DurableAsyncSnapshotSettings                                              durableSettings;
    private final EssentialsEventStoreProperties                                            properties;
    private final Optional<AggregateSnapshotJobRepository>                                  jobRepository;
    private final Optional<MeterRegistry>                                                   meterRegistry;

    /**
     * Constructs a {@code DefaultAggregateSnapshotRepositoryFactory} with the specified dependencies.
     *
     * @param eventStore the configurable event store used for event streaming
     * @param unitOfWorkFactory the factory to create instances of unit of work for the event store
     * @param jsonSerializer the serializer used for serializing and deserializing events
     * @param snapshotStore the store used for managing aggregate snapshots
     * @param resolver the resolver for aggregate snapshot configurations
     * @param durableSettings the settings for managing durable asynchronous snapshots
     * @param properties the essential event store properties
     * @param jobRepository the optional repository for managing aggregate snapshot jobs
     * @param meterRegistry the optional meter registry for monitoring and metrics
     * @throws IllegalArgumentException if any of the provided parameters is null
     */
    public DefaultAggregateSnapshotRepositoryFactory(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                                                     EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                     JSONEventSerializer jsonSerializer,
                                                     AggregateSnapshotStore snapshotStore,
                                                     AggregateSnapshotConfigurationResolver resolver,
                                                     DurableAsyncSnapshotSettings durableSettings,
                                                     EssentialsEventStoreProperties properties,
                                                     Optional<AggregateSnapshotJobRepository> jobRepository,
                                                     Optional<MeterRegistry> meterRegistry) {
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
        this.snapshotStore = requireNonNull(snapshotStore, "No snapshotStore provided");
        this.resolver = requireNonNull(resolver, "No resolver provided");
        this.durableSettings = requireNonNull(durableSettings, "No durableSettings provided");
        this.properties = requireNonNull(properties, "No properties provided");
        this.jobRepository = requireNonNull(jobRepository, "No jobRepository provided");
        this.meterRegistry = requireNonNull(meterRegistry, "No meterRegistry provided");
    }

    @Override
    public Optional<AggregateSnapshotRepository> create(AggregateType aggregateType,
                                                        Class<?> aggregateImplementationType) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");

        var resolvedConfiguration = resolver.resolve(aggregateType, aggregateImplementationType);
        if (!resolvedConfiguration.enabled()) {
            return Optional.empty();
        }

        var triggerStrategy = AddNewAggregateSnapshotStrategy.updateWhenBehindByNumberOfEvents(resolvedConfiguration.everyNEvents());
        var deletionStrategy = resolvedConfiguration.deletionMode().toDeletionStrategy(resolvedConfiguration.keepLastSnapshots());

        AggregateSnapshotRepository repository = switch (resolvedConfiguration.mode()) {
            case SYNC -> new PostgresqlAggregateSnapshotRepository(eventStore,
                                                                  unitOfWorkFactory,
                                                                  Optional.ofNullable(properties.getSnapshots().getSnapshotTableName()),
                                                                  jsonSerializer,
                                                                  triggerStrategy,
                                                                  deletionStrategy,
                                                                  meterRegistry);
            case ASYNC_IN_MEMORY -> new AsyncAggregateSnapshotRepository(snapshotStore,
                                                                         jsonSerializer,
                                                                         triggerStrategy,
                                                                         deletionStrategy,
                                                                         new AsyncAggregateSnapshotSettings(SnapshotExecutionMode.ASYNC_IN_MEMORY,
                                                                                                            properties.getSnapshots().getWorkerThreads()),
                                                                         unitOfWorkFactory);
            case ASYNC_DURABLE -> new DurableAsyncAggregateSnapshotRepository(eventStore,
                                                                             snapshotStore,
                                                                             jobRepository.orElseThrow(() -> new IllegalStateException("AggregateSnapshotJobRepository bean is required for ASYNC_DURABLE snapshot mode")),
                                                                             jsonSerializer,
                                                                             triggerStrategy,
                                                                             deletionStrategy,
                                                                             meterRegistry);
        };
        return Optional.of(repository);
    }
}
