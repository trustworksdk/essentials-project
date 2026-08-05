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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import org.springframework.beans.factory.config.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Optional;

/**
 * Configuration class for setting up the infrastructure and beans required for managing
 * closing books within an event sourcing and aggregate lifecycle configuration context.
 * <p>
 * This configuration is applied after the {@link SnapshotConfiguration} and is
 * enabled only if the {@link AggregateClosingBooksPolicy} class is available on the classpath.
 * <p>
 * Leverages Spring's {@code @EnableConfigurationProperties} to bind external configuration
 * properties defined in {@link EssentialsEventStoreProperties}.
 * <p>
 * The following beans are defined in this configuration:
 * - A {@code BeanFactoryPostProcessor} for marking specific beans as infrastructure components.
 * - {@code AggregateClosingBooksPolicyRegistry}, a registry for managing closing books policies.
 * - {@code AggregateClosingBooksPolicyBeanPostProcessor}, responsible for applying post-processing
 *   to beans associated with {@link AggregateClosingBooksPolicy}.
 * - {@code AggregateClosingBooksConfigurationResolver}, which resolves closing book configurations
 *   based on the provided properties and registered policies.
 * - {@code AggregateLifecycleConfigurationValidator}, responsible for validating the aggregate lifecycle
 *   configuration, including snapshot and closing books policies.
 */
@AutoConfiguration(after = SnapshotConfiguration.class)
@ConditionalOnClass(AggregateClosingBooksPolicy.class)
@EnableConfigurationProperties(EssentialsEventStoreProperties.class)
public class ClosingBooksConfiguration {
    @Bean
    public static BeanFactoryPostProcessor closingBooksInfrastructureBeanRolePostProcessor() {
        return beanFactory -> markAsInfrastructure(beanFactory,
                                                   "closingBooksConfiguration",
                                                   "aggregateClosingBooksPolicyRegistry",
                                                   "aggregateClosingBooksPolicyBeanPostProcessor");
    }

    /**
     * Provides a bean definition for an {@link AggregateClosingBooksPolicyRegistry}.
     * This method creates and returns an instance of {@link InMemoryAggregateClosingBooksPolicyRegistry}
     * if no other {@link AggregateClosingBooksPolicyRegistry} bean is configured in the application context.
     *
     * The registry serves as a component to manage and retrieve aggregate closing books policies,
     * facilitating policy registration, retrieval, and application for different aggregate types.
     *
     * @return an instance of {@link AggregateClosingBooksPolicyRegistry}, specifically an {@link InMemoryAggregateClosingBooksPolicyRegistry},
     *         which offers an in-memory storage mechanism for managing policy descriptors.
     */
    @Bean
    @ConditionalOnMissingBean
    public AggregateClosingBooksPolicyRegistry aggregateClosingBooksPolicyRegistry() {
        return new InMemoryAggregateClosingBooksPolicyRegistry();
    }

    /**
     * Provides a bean definition for an {@link AggregateClosingBooksPolicyBeanPostProcessor}.
     *
     * This method creates and returns an instance of {@link AggregateClosingBooksPolicyBeanPostProcessor}
     * if no other bean of the same type is configured in the application context. The processor is used
     * to process and register beans annotated with {@link AggregateClosingBooksPolicy} in the
     * {@link AggregateClosingBooksPolicyRegistry}. It ensures appropriate management of aggregate
     * closing-books policies within a Spring application.
     *
     * @param registry       the {@link AggregateClosingBooksPolicyRegistry} responsible for storing
     *                       and managing policy descriptors related to aggregate types.
     * @param beanFactory    the {@link ConfigurableListableBeanFactory} that provides access to bean
     *                       definitions registered within the application context.
     * @return an instance of {@link AggregateClosingBooksPolicyBeanPostProcessor}, responsible for
     *         dynamically processing and registering aggregate closing-books policies.
     */
    @Bean
    @ConditionalOnMissingBean
    public static AggregateClosingBooksPolicyBeanPostProcessor aggregateClosingBooksPolicyBeanPostProcessor(AggregateClosingBooksPolicyRegistry registry,
                                                                                                             ConfigurableListableBeanFactory beanFactory) {
        return new AggregateClosingBooksPolicyBeanPostProcessor(registry, beanFactory);
    }

    /**
     * Defines a bean for {@link AggregateClosingBooksConfigurationResolver}.
     * <p>
     * This method configures and returns an instance of {@link DefaultAggregateClosingBooksConfigurationResolver},
     * which is responsible for resolving aggregate closing books configuration based on the given properties
     * and policy registry. It ensures that the resolver bean is created only if no other bean of the
     * same type is present in the application context.
     *
     * @param properties the {@link EssentialsEventStoreProperties} containing configuration properties
     *                   related to the event store and aggregate lifecycle.
     * @param registry   the {@link AggregateClosingBooksPolicyRegistry} responsible for registering
     *                   and managing aggregate closing books policies.
     * @return an instance of {@link AggregateClosingBooksConfigurationResolver} for resolving
     *         aggregate-specific closing books configurations.
     */
    @Bean
    @ConditionalOnMissingBean
    public AggregateClosingBooksConfigurationResolver aggregateClosingBooksConfigurationResolver(EssentialsEventStoreProperties properties,
                                                                                                 AggregateClosingBooksPolicyRegistry registry) {
        return new DefaultAggregateClosingBooksConfigurationResolver(properties, registry);
    }

    /**
     * Provides a bean definition for {@link AggregateLifecycleConfigurationValidator}.
     * This method creates and returns an instance of {@link DefaultAggregateLifecycleConfigurationValidator}
     * if no other {@link AggregateLifecycleConfigurationValidator} bean is configured in the application context.
     * It is responsible for validating the lifecycle configuration of aggregates based on snapshot and
     * closing books policies and configurations defined within the application.
     *
     * @param snapshotPolicyRegistry the {@link AggregateSnapshotPolicyRegistry} responsible for managing
     *                               and retrieving aggregate snapshot policies.
     * @param closingBooksPolicyRegistry the {@link AggregateClosingBooksPolicyRegistry} responsible for
     *                                   managing and retrieving aggregate closing books policies.
     * @param snapshotConfigurationResolver the {@link AggregateSnapshotConfigurationResolver} used to resolve
     *                                       configuration settings related to snapshot policies for aggregates.
     * @param closingBooksConfigurationResolver the {@link AggregateClosingBooksConfigurationResolver} used to resolve
     *                                          configuration settings related to closing books policies for aggregates.
     * @param properties the {@link EssentialsEventStoreProperties} containing configuration properties
     *                   related to the event store and aggregate lifecycle.
     * @param fencedLockManagerOptional an {@link Optional} containing the {@link FencedLockManager}, if available,
     *                                  to manage distributed locks for aggregate lifecycle operations.
     * @param nextGenerationFactories an {@link org.springframework.beans.factory.ObjectProvider} for obtaining
     *                                factories of type {@link TypedClosingBooksNextGenerationFactory} to support
     *                                next-generation closing books functionality.
     * @return an instance of {@link AggregateLifecycleConfigurationValidator}, specifically a
     *         {@link DefaultAggregateLifecycleConfigurationValidator}, used for validating the configuration
     *         of aggregate lifecycles within the application.
     */
    @Bean
    @ConditionalOnMissingBean
    public AggregateLifecycleConfigurationValidator aggregateLifecycleConfigurationValidator(AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                                                                            AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry,
                                                                                            AggregateSnapshotConfigurationResolver snapshotConfigurationResolver,
                                                                                            AggregateClosingBooksConfigurationResolver closingBooksConfigurationResolver,
                                                                                            EssentialsEventStoreProperties properties,
                                                                                            Optional<FencedLockManager> fencedLockManagerOptional,
                                                                                            org.springframework.beans.factory.ObjectProvider<TypedClosingBooksNextGenerationFactory<?, ?, ?, ?>> nextGenerationFactories) {
        return new DefaultAggregateLifecycleConfigurationValidator(snapshotPolicyRegistry,
                                                                  closingBooksPolicyRegistry,
                                                                  snapshotConfigurationResolver,
                                                                  closingBooksConfigurationResolver,
                                                                  properties,
                                                                  fencedLockManagerOptional,
                                                                  nextGenerationFactories.orderedStream().toList());
    }

    private static void markAsInfrastructure(ConfigurableListableBeanFactory beanFactory, String... beanNames) {
        for (var beanName : beanNames) {
            if (beanFactory.containsBeanDefinition(beanName)) {
                beanFactory.getBeanDefinition(beanName).setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            }
        }
    }
}
