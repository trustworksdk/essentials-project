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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.*;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateClosingBooksPolicyBeanPostProcessorTest {
    @Test
    void registers_policy_for_annotated_aggregate_bean() {
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("annotatedAggregate", new RootBeanDefinition(AnnotatedAggregate.class));
        var registry = new InMemoryAggregateClosingBooksPolicyRegistry();
        var postProcessor = new AggregateClosingBooksPolicyBeanPostProcessor(registry, beanFactory);

        postProcessor.postProcessAfterInitialization(new AnnotatedAggregate(), "annotatedAggregate");

        assertThat(registry.findByAggregateImplementationType(AnnotatedAggregate.class))
                .isPresent()
                .get()
                .satisfies(descriptor -> {
                    assertThat(descriptor.aggregateType()).contains("Accounts");
                    assertThat(descriptor.policy().triggerMode()).isEqualTo(ClosingBooksTriggerMode.SCHEDULED_SCAN);
                    assertThat(descriptor.policy().enabled()).isTrue();
                    assertThat(descriptor.policy().defaultPolicy()).isEqualTo(ClosingBooksDefaultPolicyType.EVENT_COUNT);
                    assertThat(descriptor.policy().eventThreshold()).isEqualTo(50);
                    assertThat(descriptor.policy().timeBoundary()).isEqualTo(ClosingBooksTimeBoundary.END_OF_MONTH);
                    assertThat(descriptor.policy().zoneId()).isEqualTo("Europe/Copenhagen");
                });
    }

    @Test
    void skips_infrastructure_beans() {
        var beanFactory = new DefaultListableBeanFactory();
        var beanDefinition = new RootBeanDefinition(AnnotatedAggregate.class);
        beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        beanFactory.registerBeanDefinition("annotatedAggregate", beanDefinition);
        var registry = new InMemoryAggregateClosingBooksPolicyRegistry();
        var postProcessor = new AggregateClosingBooksPolicyBeanPostProcessor(registry, beanFactory);

        postProcessor.postProcessAfterInitialization(new AnnotatedAggregate(), "annotatedAggregate");

        assertThat(registry.findByAggregateImplementationType(AnnotatedAggregate.class)).isEmpty();
    }

    @Test
    void ignores_non_annotated_beans() {
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("plainAggregate", new RootBeanDefinition(PlainAggregate.class));
        var registry = new InMemoryAggregateClosingBooksPolicyRegistry();
        var postProcessor = new AggregateClosingBooksPolicyBeanPostProcessor(registry, beanFactory);

        postProcessor.postProcessAfterInitialization(new PlainAggregate(), "plainAggregate");

        assertThat(registry.getRegisteredPolicies()).isEmpty();
    }

    @AggregateClosingBooksPolicy(aggregateType = "Accounts",
                                 triggerMode = ClosingBooksTriggerMode.SCHEDULED_SCAN,
                                 defaultPolicy = ClosingBooksDefaultPolicyType.EVENT_COUNT,
                                 eventThreshold = 50,
                                 timeBoundary = ClosingBooksTimeBoundary.END_OF_MONTH,
                                 zoneId = "Europe/Copenhagen")
    private static final class AnnotatedAggregate {
    }

    private static final class PlainAggregate {
    }
}
