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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Optional;

@AutoConfiguration(after = EventStoreConfiguration.class)
@ConditionalOnClass(AggregateSnapshotRepository.class)
@EnableConfigurationProperties(EssentialsEventStoreProperties.class)
public class SnapshotConfiguration {

    /**
     * Creates a {@link BeanFactoryPostProcessor} that marks specific beans as infrastructure beans
     * within the provided {@link ConfigurableListableBeanFactory}.
     * <p>
     * The method targets the following beans:
     * - "snapshotConfiguration"
     * - "aggregateSnapshotPolicyRegistry"
     * - "aggregateSnapshotPolicyBeanPostProcessor"
     * <p>
     * These beans will be assigned the {@link BeanDefinition#ROLE_INFRASTRUCTURE}
     * to indicate their role in supporting the application's infrastructure.
     *
     * @return a {@link BeanFactoryPostProcessor} that processes the specified beans and marks them
     * as infrastructure components.
     */
    @Bean
    public static BeanFactoryPostProcessor snapshotInfrastructureBeanRolePostProcessor() {
        return beanFactory -> markAsInfrastructure(beanFactory,
                                                   "snapshotConfiguration",
                                                   "aggregateSnapshotPolicyRegistry",
                                                   "aggregateSnapshotPolicyBeanPostProcessor");
    }

    /**
     * Provides an {@link AggregateSnapshotPolicyRegistry} bean for managing aggregate snapshot policies.
     * If a custom implementation of {@link AggregateSnapshotPolicyRegistry} is not defined, this method
     * supplies a default in-memory implementation.
     *
     * @return an instance of {@link AggregateSnapshotPolicyRegistry}, specifically an
     *         {@link InMemoryAggregateSnapshotPolicyRegistry}.
     */
    @Bean
    @ConditionalOnMissingBean
    public AggregateSnapshotPolicyRegistry aggregateSnapshotPolicyRegistry() {
        return new InMemoryAggregateSnapshotPolicyRegistry();
    }

    /**
     * Creates and returns a new instance of {@link AggregateSnapshotPolicyBeanPostProcessor}.
     * The method is annotated with {@code @Bean} to indicate that it produces a Spring bean,
     * and {@code @ConditionalOnMissingBean} ensures the bean is only created if no other
     * bean of the same type is present in the application context.
     *
     * @param registry the {@link AggregateSnapshotPolicyRegistry} used to register snapshot policies.
     * @param beanFactory the {@link ConfigurableListableBeanFactory} for resolving bean dependencies.
     * @return an instance of {@link AggregateSnapshotPolicyBeanPostProcessor}.
     */
    @Bean
    @ConditionalOnMissingBean
    public static AggregateSnapshotPolicyBeanPostProcessor aggregateSnapshotPolicyBeanPostProcessor(AggregateSnapshotPolicyRegistry registry,
                                                                                                    ConfigurableListableBeanFactory beanFactory) {
        return new AggregateSnapshotPolicyBeanPostProcessor(registry, beanFactory);
    }

    /**
     * Configures and returns an instance of {@link AggregateSnapshotConfigurationResolver}.
     *
     * @param properties the {@link EssentialsEventStoreProperties} providing configuration properties
     *                   for the event store and snapshot behavior.
     * @param registry   the {@link AggregateSnapshotPolicyRegistry} managing the snapshot policies
     *                   for the aggregates.
     * @return an instance of {@link AggregateSnapshotConfigurationResolver} for resolving snapshot
     *         configurations based on the provided properties and policies.
     */
    @Bean
    @ConditionalOnMissingBean
    public AggregateSnapshotConfigurationResolver aggregateSnapshotConfigurationResolver(EssentialsEventStoreProperties properties,
                                                                                        AggregateSnapshotPolicyRegistry registry) {
        return new DefaultAggregateSnapshotConfigurationResolver(properties, registry);
    }

    /**
     * Creates and configures an {@code AggregateSnapshotStore} bean for handling aggregate snapshots.
     * This method is only enabled when the application is configured with snapshot support and
     * all required dependencies are available in the Spring context.
     *
     * @param eventStore The event store used for event sourcing operations, configured with
     *                   {@code SeparateTablePerAggregateEventStreamConfiguration}.
     * @param unitOfWorkFactory The factory for creating instances of {@code EventStoreUnitOfWork},
     *                          which are used to manage transactional operations on the event store.
     * @param jsonSerializer The serializer responsible for converting events to and from JSON format.
     * @param properties The properties object containing configuration related to the event store,
     *                   including settings for snapshot storage such as the snapshot table name.
     * @param meterRegistry An optional {@code MeterRegistry} for collecting metrics and monitoring
     *                      performance; can be absent if not required.
     * @return An instance of {@code AggregateSnapshotStore} configured to use PostgreSQL for
     *         storing aggregate snapshots.
     */
    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnBean({
            ConfigurableEventStore.class,
            EventStoreUnitOfWorkFactory.class,
            JSONEventSerializer.class
    })
    @ConditionalOnMissingBean
    public AggregateSnapshotStore aggregateSnapshotStore(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                                                         EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                         JSONEventSerializer jsonSerializer,
                                                         EssentialsEventStoreProperties properties,
                                                         Optional<MeterRegistry> meterRegistry) {
        return new PostgresqlAggregateSnapshotStore(eventStore,
                                                    unitOfWorkFactory,
                                                    Optional.ofNullable(properties.getSnapshots().getSnapshotTableName()),
                                                    jsonSerializer,
                                                    meterRegistry);
    }

    /**
     * Creates and provides a bean of type {@link AddNewAggregateSnapshotStrategy}
     * that determines the strategy for triggering the creation of aggregate snapshots
     * in the event store. This bean is only created if the snapshot feature is enabled
     * and no other bean of the same type is already defined.
     *
     * @param properties the configuration properties for the Essentials Event Store,
     *                   including snapshot-specific settings such as the default number of events
     *                   after which a snapshot will be triggered.
     * @return an instance of {@link AddNewAggregateSnapshotStrategy} configured to trigger
     *         snapshot creation based on the defined number of events.
     */
    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public AddNewAggregateSnapshotStrategy aggregateSnapshotTriggerStrategy(EssentialsEventStoreProperties properties) {
        return AddNewAggregateSnapshotStrategy.updateWhenBehindByNumberOfEvents(properties.getSnapshots().getDefaultEveryNEvents());
    }

    /**
     * Creates and configures an {@code AggregateSnapshotDeletionStrategy} bean based on the
     * application properties and default deletion mode settings.
     *
     * @param properties the {@code EssentialsEventStoreProperties} containing configuration
     *                   for snapshot management, including default deletion mode and retention
     *                   settings.
     * @return an instance of {@code AggregateSnapshotDeletionStrategy} configured with the
     *         specified deletion mode and retention settings.
     */
    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public AggregateSnapshotDeletionStrategy aggregateSnapshotDeletionStrategy(EssentialsEventStoreProperties properties) {
        return properties.getSnapshots()
                         .getDefaultDeletionMode()
                         .toDeletionStrategy(properties.getSnapshots().getDefaultKeepLastSnapshots());
    }

    /**
     * Creates and configures an instance of {@code AsyncAggregateSnapshotSettings} based on the provided
     * {@code EssentialsEventStoreProperties}. This bean is only created if the snapshot feature is enabled
     * via the configuration properties and no other bean of the same type is already defined.
     *
     * @param properties the event store properties that include configuration for snapshots, such as the default mode and worker thread settings.
     * @return an instance of {@code AsyncAggregateSnapshotSettings} configured with the defined snapshot settings.
     */
    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public AsyncAggregateSnapshotSettings asyncAggregateSnapshotSettings(EssentialsEventStoreProperties properties) {
        return new AsyncAggregateSnapshotSettings(properties.getSnapshots().getDefaultMode(),
                                                  properties.getSnapshots().getWorkerThreads());
    }

    /**
     * Creates and returns a {@link DurableAsyncSnapshotSettings} bean configured based on the provided
     * {@link EssentialsEventStoreProperties}.
     * This method is conditioned on the "essentials.eventstore.snapshots.enabled" property being set to "true"
     * and no other bean of the same type being defined.
     *
     * @param properties The properties object containing configuration details for the event store snapshots,
     *                   including settings for durable snapshots such as poll interval, batch size, worker threads,
     *                   maximum retries, retry delay, and processing timeout.
     * @return A configured instance of {@link DurableAsyncSnapshotSettings}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public DurableAsyncSnapshotSettings durableAsyncSnapshotSettings(EssentialsEventStoreProperties properties) {
        var durable = properties.getSnapshots().getDurable();
        return new DurableAsyncSnapshotSettings(durable.getPollInterval(),
                                                durable.getBatchSize(),
                                                durable.getWorkerThreads(),
                                                durable.getMaxRetries(),
                                                durable.getRetryDelay(),
                                                durable.getProcessingTimeout());
    }

    /**
     * Creates and returns a bean of type {@code AggregateSnapshotJobRepository}. This repository
     * facilitates managing snapshot jobs in the context of the Event Store when certain conditions
     * are satisfied:
     * - Snapshots are enabled as per the configuration.
     * - Durable snapshots are not disabled.
     * - A bean of type {@code EventStoreUnitOfWorkFactory} exists in the application context.
     * - No other bean of type {@code AggregateSnapshotJobRepository} is defined.
     *
     * @param unitOfWorkFactory        the factory for creating {@code EventStoreUnitOfWork} instances,
     *                                 used for transactional interactions with the Event Store.
     * @param properties               the configuration properties for the Event Store,
     *                                 which include snapshot-related settings.
     * @param meterRegistry            an optional {@code MeterRegistry} for publishing metrics,
     *                                 if a metrics system is enabled and available.
     * @return an instance of {@code PostgresqlAggregateSnapshotJobRepository}, configured according
     *         to the provided factory, properties, and metrics registry.
     */
    @Bean
    @ConditionalOnExpression(
            "'${essentials.eventstore.snapshots.enabled:false}'.equalsIgnoreCase('true') && " +
            "!'${essentials.eventstore.snapshots.durable.enabled:true}'.equalsIgnoreCase('false')"
    )
    @ConditionalOnBean(EventStoreUnitOfWorkFactory.class)
    @ConditionalOnMissingBean
    public AggregateSnapshotJobRepository aggregateSnapshotJobRepository(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                                        EssentialsEventStoreProperties properties,
                                                                        Optional<MeterRegistry> meterRegistry) {
        return new PostgresqlAggregateSnapshotJobRepository(unitOfWorkFactory,
                                                            Optional.ofNullable(properties.getSnapshots().getDurable().getJobTableName()),
                                                            meterRegistry);
    }

    /**
     * Creates and configures a {@link PostgresqlAggregateSnapshotJobProcessor} bean for processing aggregate snapshot jobs.
     *
     * @param eventStore The event store configured to work with a separate table per aggregate event stream.
     * @param snapshotStore The store responsible for managing aggregate snapshots.
     * @param jobRepository The repository for managing snapshot job records.
     * @param unitOfWorkFactory The factory for creating units of work for the event store.
     * @param settings The durable async snapshot processing settings.
     * @param meterRegistry An optional meter registry for collecting metrics related to snapshot job processing.
     * @return A configured {@link PostgresqlAggregateSnapshotJobProcessor} bean.
     */
    @Bean
    @ConditionalOnBean(AggregateSnapshotJobRepository.class)
    @ConditionalOnMissingBean
    public PostgresqlAggregateSnapshotJobProcessor aggregateSnapshotJobProcessor(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                                                                                 AggregateSnapshotStore snapshotStore,
                                                                                 AggregateSnapshotJobRepository jobRepository,
                                                                                 EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                                                 DurableAsyncSnapshotSettings settings,
                                                                                 Optional<MeterRegistry> meterRegistry) {
        return new PostgresqlAggregateSnapshotJobProcessor(eventStore,
                                                           snapshotStore,
                                                           jobRepository,
                                                           unitOfWorkFactory,
                                                           settings,
                                                           meterRegistry);
    }

    /**
     * Creates and provides an instance of {@link DurableAsyncSnapshotManager}.
     * This method is conditional on the presence of {@link PostgresqlAggregateSnapshotJobProcessor}
     * and the absence of a pre-existing {@link DurableAsyncSnapshotManager} bean.
     *
     * @param processor the {@link PostgresqlAggregateSnapshotJobProcessor} required for snapshot management.
     * @param settings  the {@link DurableAsyncSnapshotSettings} containing configuration for durable snapshot operations.
     * @return an instance of {@link DurableAsyncSnapshotManager} configured with the given processor and settings.
     */
    @Bean
    @ConditionalOnBean(PostgresqlAggregateSnapshotJobProcessor.class)
    @ConditionalOnMissingBean
    public DurableAsyncSnapshotManager durableAsyncSnapshotManager(PostgresqlAggregateSnapshotJobProcessor processor,
                                                                   DurableAsyncSnapshotSettings settings) {
        return new DurableAsyncSnapshotManager(processor, settings);
    }

    /**
     * Creates and configures an {@link AggregateSnapshotRepositoryFactory} bean.
     *
     * @param eventStore the configurable event store for managing aggregate event streams.
     * @param unitOfWorkFactory the factory responsible for creating instances of {@link EventStoreUnitOfWork}.
     * @param jsonSerializer the serializer for handling event data in JSON format.
     * @param snapshotStore the store for managing aggregate snapshots.
     * @param resolver the configuration resolver for aggregate snapshot settings.
     * @param durableSettings the settings for handling durable asynchronous snapshots.
     * @param properties the properties for the essentials event store.
     * @param jobRepository an optional repository for aggregate snapshot jobs.
     * @param meterRegistry an optional meter registry for recording metrics.
     * @return an instance of {@link DefaultAggregateSnapshotRepositoryFactory}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnBean({
            ConfigurableEventStore.class,
            EventStoreUnitOfWorkFactory.class,
            JSONEventSerializer.class,
            AggregateSnapshotStore.class
    })
    @ConditionalOnMissingBean
    public AggregateSnapshotRepositoryFactory aggregateSnapshotRepositoryFactory(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                                                                                 EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                                                 JSONEventSerializer jsonSerializer,
                                                                                 AggregateSnapshotStore snapshotStore,
                                                                                 AggregateSnapshotConfigurationResolver resolver,
                                                                                 DurableAsyncSnapshotSettings durableSettings,
                                                                                 EssentialsEventStoreProperties properties,
                                                                                 Optional<AggregateSnapshotJobRepository> jobRepository,
                                                                                 Optional<MeterRegistry> meterRegistry) {
        return new DefaultAggregateSnapshotRepositoryFactory(eventStore,
                                                             unitOfWorkFactory,
                                                             jsonSerializer,
                                                             snapshotStore,
                                                             resolver,
                                                             durableSettings,
                                                             properties,
                                                             jobRepository,
                                                             meterRegistry);
    }

    /**
     * Provides an instance of {@link AggregateSnapshotRepositoryProvider} if snapshot support is enabled
     * and a corresponding {@link AggregateSnapshotRepositoryFactory} is available in the application context.
     *
     * @param factory the factory used to create the aggregate snapshot repository
     * @return an instance of {@link AggregateSnapshotRepositoryProvider}
     */
    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnBean(AggregateSnapshotRepositoryFactory.class)
    @ConditionalOnMissingBean
    public AggregateSnapshotRepositoryProvider aggregateSnapshotRepositoryProvider(AggregateSnapshotRepositoryFactory factory) {
        return new CachingAggregateSnapshotRepositoryProvider(factory);
    }

    private static void markAsInfrastructure(ConfigurableListableBeanFactory beanFactory, String... beanNames) {
        for (var beanName : beanNames) {
            if (beanFactory.containsBeanDefinition(beanName)) {
                beanFactory.getBeanDefinition(beanName).setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            }
        }
    }
}
