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
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
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

    /**
     * Creates a builder for a {@link DefaultAggregateSnapshotRepositoryFactory}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DefaultAggregateSnapshotRepositoryFactory}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload.
     */
    public static final class Builder {
        private ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
        private EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
        private JSONEventSerializer jsonSerializer;
        private AggregateSnapshotStore snapshotStore;
        private AggregateSnapshotConfigurationResolver resolver;
        private DurableAsyncSnapshotSettings durableSettings;
        private EssentialsEventStoreProperties properties;
        private AggregateSnapshotJobRepository jobRepository;
        private MeterRegistry meterRegistry;

        /**
         * @param eventStore required
         * @return this builder
         */
        public Builder setEventStore(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
            this.eventStore = eventStore;
            return this;
        }

        /**
         * @param unitOfWorkFactory required
         * @return this builder
         */
        public Builder setUnitOfWorkFactory(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            return this;
        }

        /**
         * @param jsonSerializer required
         * @return this builder
         */
        public Builder setJsonSerializer(JSONEventSerializer jsonSerializer) {
            this.jsonSerializer = jsonSerializer;
            return this;
        }

        /**
         * @param snapshotStore required
         * @return this builder
         */
        public Builder setSnapshotStore(AggregateSnapshotStore snapshotStore) {
            this.snapshotStore = snapshotStore;
            return this;
        }

        /**
         * @param resolver required
         * @return this builder
         */
        public Builder setResolver(AggregateSnapshotConfigurationResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        /**
         * @param durableSettings required
         * @return this builder
         */
        public Builder setDurableSettings(DurableAsyncSnapshotSettings durableSettings) {
            this.durableSettings = durableSettings;
            return this;
        }

        /**
         * @param properties required
         * @return this builder
         */
        public Builder setProperties(EssentialsEventStoreProperties properties) {
            this.properties = properties;
            return this;
        }

        /**
         * @param jobRepository optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setJobRepository(AggregateSnapshotJobRepository jobRepository) {
            this.jobRepository = jobRepository;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setJobRepository}.
         *
         * @param jobRepository the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setJobRepository(Optional<AggregateSnapshotJobRepository> jobRepository) {
            requireNonNull(jobRepository, "No jobRepository provided");
            return setJobRepository(jobRepository.orElse(null));
        }

        /**
         * @param meterRegistry optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setMeterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setMeterRegistry}.
         *
         * @param meterRegistry the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setMeterRegistry(Optional<MeterRegistry> meterRegistry) {
            requireNonNull(meterRegistry, "No meterRegistry provided");
            return setMeterRegistry(meterRegistry.orElse(null));
        }

        /**
         * @return the new {@link DefaultAggregateSnapshotRepositoryFactory}
         */
        @SuppressWarnings("removal")
        public DefaultAggregateSnapshotRepositoryFactory build() {
            return new DefaultAggregateSnapshotRepositoryFactory(eventStore,
                                                                 unitOfWorkFactory,
                                                                 jsonSerializer,
                                                                 snapshotStore,
                                                                 resolver,
                                                                 durableSettings,
                                                                 properties,
                                                                 Optional.ofNullable(jobRepository),
                                                                 Optional.ofNullable(meterRegistry));
        }
    }

}
