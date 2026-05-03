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
    @Bean
    public static BeanFactoryPostProcessor snapshotInfrastructureBeanRolePostProcessor() {
        return beanFactory -> markAsInfrastructure(beanFactory,
                                                   "snapshotConfiguration",
                                                   "aggregateSnapshotPolicyRegistry",
                                                   "aggregateSnapshotPolicyBeanPostProcessor");
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregateSnapshotPolicyRegistry aggregateSnapshotPolicyRegistry() {
        return new InMemoryAggregateSnapshotPolicyRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public static AggregateSnapshotPolicyBeanPostProcessor aggregateSnapshotPolicyBeanPostProcessor(AggregateSnapshotPolicyRegistry registry,
                                                                                                    ConfigurableListableBeanFactory beanFactory) {
        return new AggregateSnapshotPolicyBeanPostProcessor(registry, beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregateSnapshotConfigurationResolver aggregateSnapshotConfigurationResolver(EssentialsEventStoreProperties properties,
                                                                                        AggregateSnapshotPolicyRegistry registry) {
        return new DefaultAggregateSnapshotConfigurationResolver(properties, registry);
    }

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

    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public AddNewAggregateSnapshotStrategy aggregateSnapshotTriggerStrategy(EssentialsEventStoreProperties properties) {
        return AddNewAggregateSnapshotStrategy.updateWhenBehindByNumberOfEvents(properties.getSnapshots().getDefaultEveryNEvents());
    }

    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public AggregateSnapshotDeletionStrategy aggregateSnapshotDeletionStrategy(EssentialsEventStoreProperties properties) {
        return properties.getSnapshots()
                         .getDefaultDeletionMode()
                         .toDeletionStrategy(properties.getSnapshots().getDefaultKeepLastSnapshots());
    }

    @Bean
    @ConditionalOnProperty(prefix = "essentials.eventstore.snapshots", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public AsyncAggregateSnapshotSettings asyncAggregateSnapshotSettings(EssentialsEventStoreProperties properties) {
        return new AsyncAggregateSnapshotSettings(properties.getSnapshots().getDefaultMode());
    }

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

    @Bean
    @ConditionalOnBean(PostgresqlAggregateSnapshotJobProcessor.class)
    @ConditionalOnMissingBean
    public DurableAsyncSnapshotManager durableAsyncSnapshotManager(PostgresqlAggregateSnapshotJobProcessor processor,
                                                                   DurableAsyncSnapshotSettings settings) {
        return new DurableAsyncSnapshotManager(processor, settings);
    }

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
