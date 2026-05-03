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

    @Bean
    @ConditionalOnMissingBean
    public AggregateClosingBooksPolicyRegistry aggregateClosingBooksPolicyRegistry() {
        return new InMemoryAggregateClosingBooksPolicyRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public static AggregateClosingBooksPolicyBeanPostProcessor aggregateClosingBooksPolicyBeanPostProcessor(AggregateClosingBooksPolicyRegistry registry,
                                                                                                             ConfigurableListableBeanFactory beanFactory) {
        return new AggregateClosingBooksPolicyBeanPostProcessor(registry, beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregateClosingBooksConfigurationResolver aggregateClosingBooksConfigurationResolver(EssentialsEventStoreProperties properties,
                                                                                                 AggregateClosingBooksPolicyRegistry registry) {
        return new DefaultAggregateClosingBooksConfigurationResolver(properties, registry);
    }

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
