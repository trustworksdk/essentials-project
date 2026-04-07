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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.*;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateSnapshotPolicyBeanPostProcessorTest {
    @Test
    void registers_policy_for_annotated_aggregate_bean() {
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("annotatedAggregate", new RootBeanDefinition(AnnotatedAggregate.class));
        var registry = new InMemoryAggregateSnapshotPolicyRegistry();
        var postProcessor = new AggregateSnapshotPolicyBeanPostProcessor(registry, beanFactory);

        postProcessor.postProcessAfterInitialization(new AnnotatedAggregate(), "annotatedAggregate");

        assertThat(registry.findByAggregateImplementationType(AnnotatedAggregate.class))
                .isPresent()
                .get()
                .satisfies(descriptor -> {
                    assertThat(descriptor.aggregateType()).contains("Orders");
                    assertThat(descriptor.policy().mode()).isEqualTo(SnapshotExecutionMode.ASYNC_DURABLE);
                    assertThat(descriptor.policy().everyNEvents()).isEqualTo(25);
                    assertThat(descriptor.policy().deletionMode()).isEqualTo(SnapshotDeletionMode.KEEP_LAST_N);
                    assertThat(descriptor.policy().keepLastSnapshots()).isEqualTo(2);
                });
    }

    @Test
    void skips_infrastructure_beans() {
        var beanFactory = new DefaultListableBeanFactory();
        var beanDefinition = new RootBeanDefinition(AnnotatedAggregate.class);
        beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        beanFactory.registerBeanDefinition("annotatedAggregate", beanDefinition);
        var registry = new InMemoryAggregateSnapshotPolicyRegistry();
        var postProcessor = new AggregateSnapshotPolicyBeanPostProcessor(registry, beanFactory);

        postProcessor.postProcessAfterInitialization(new AnnotatedAggregate(), "annotatedAggregate");

        assertThat(registry.findByAggregateImplementationType(AnnotatedAggregate.class)).isEmpty();
    }

    @Test
    void ignores_non_annotated_beans() {
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("plainAggregate", new RootBeanDefinition(PlainAggregate.class));
        var registry = new InMemoryAggregateSnapshotPolicyRegistry();
        var postProcessor = new AggregateSnapshotPolicyBeanPostProcessor(registry, beanFactory);

        postProcessor.postProcessAfterInitialization(new PlainAggregate(), "plainAggregate");

        assertThat(registry.getRegisteredPolicies()).isEmpty();
    }

    @Test
    void registers_annotated_aggregate_even_if_no_bean_definition_is_available() {
        var beanFactory = new DefaultListableBeanFactory();
        var registry = new InMemoryAggregateSnapshotPolicyRegistry();
        var postProcessor = new AggregateSnapshotPolicyBeanPostProcessor(registry, beanFactory);

        postProcessor.postProcessAfterInitialization(new AnnotatedAggregate(), "missingBeanDefinition");

        assertThat(registry.findByAggregateImplementationType(AnnotatedAggregate.class)).isPresent();
    }

    @Test
    void latest_registration_overwrites_previous_descriptor_for_same_aggregate_type() {
        var registry = new InMemoryAggregateSnapshotPolicyRegistry();
        registry.register(new AggregateSnapshotPolicyDescriptor(AnnotatedAggregate.class,
                                                               java.util.Optional.of("Orders"),
                                                               AnnotatedAggregate.class.getAnnotation(AggregateSnapshotPolicy.class)));
        registry.register(new AggregateSnapshotPolicyDescriptor(AnnotatedAggregate.class,
                                                               java.util.Optional.of("UpdatedOrders"),
                                                               AnnotatedAggregate.class.getAnnotation(AggregateSnapshotPolicy.class)));

        assertThat(registry.findByAggregateImplementationType(AnnotatedAggregate.class))
                .isPresent()
                .get()
                .satisfies(descriptor -> assertThat(descriptor.aggregateType()).contains("UpdatedOrders"));
    }

    @AggregateSnapshotPolicy(
            aggregateType = "Orders",
            mode = SnapshotExecutionMode.ASYNC_DURABLE,
            everyNEvents = 25,
            deletionMode = SnapshotDeletionMode.KEEP_LAST_N,
            keepLastSnapshots = 2
    )
    private static final class AnnotatedAggregate {
    }

    private static final class PlainAggregate {
    }
}
