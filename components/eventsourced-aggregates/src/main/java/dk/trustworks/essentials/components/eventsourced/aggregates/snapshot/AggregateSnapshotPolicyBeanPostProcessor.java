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

import org.slf4j.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.*;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Bean post-processor for registering aggregate snapshot policies. This class processes
 * beans annotated with {@link AggregateSnapshotPolicy} during their initialization and
 * registers the snapshot policies with the {@link AggregateSnapshotPolicyRegistry}.
 * <p>
 * This allows for the centralized management of snapshot policies based on annotations
 * applied to aggregate classes in an event-sourced system.
 */
public class AggregateSnapshotPolicyBeanPostProcessor implements BeanPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(AggregateSnapshotPolicyBeanPostProcessor.class);

    private final AggregateSnapshotPolicyRegistry policyRegistry;
    private final ConfigurableListableBeanFactory beanFactory;

    /**
     * Constructs a new instance of {@code AggregateSnapshotPolicyBeanPostProcessor}.
     * This processor is responsible for scanning and registering aggregate snapshot
     * policies defined by {@link AggregateSnapshotPolicy} annotations during the
     * initialization phase of beans in a Spring application context.
     *
     * @param policyRegistry the {@link AggregateSnapshotPolicyRegistry} used to register
     *                        and manage the aggregate snapshot policies; must not be null
     * @param beanFactory    the {@link ConfigurableListableBeanFactory} provided by
     *                        the Spring application context; used to retrieve bean
     *                        definitions when necessary; must not be null
     */
    public AggregateSnapshotPolicyBeanPostProcessor(AggregateSnapshotPolicyRegistry policyRegistry,
                                                    ConfigurableListableBeanFactory beanFactory) {
        this.policyRegistry = requireNonNull(policyRegistry, "No policyRegistry provided");
        this.beanFactory = requireNonNull(beanFactory, "No beanFactory provided");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        requireNonNull(bean, "No bean provided");
        requireNonNull(beanName, "No beanName provided");

        if (shouldSkipPostProcessing(beanName)) {
            return bean;
        }

        var policy = bean.getClass().getAnnotation(AggregateSnapshotPolicy.class);
        if (policy == null) {
            return bean;
        }

        var aggregateType = policy.aggregateType().isBlank() ? Optional.<String>empty() : Optional.of(policy.aggregateType());
        var descriptor = new AggregateSnapshotPolicyDescriptor(bean.getClass(),
                                                               aggregateType,
                                                               policy);
        policyRegistry.register(descriptor);
        log.debug("Registered aggregate snapshot policy for '{}' from bean '{}'",
                  bean.getClass().getName(),
                  beanName);
        return bean;
    }

    private boolean shouldSkipPostProcessing(String beanName) {
        try {
            var beanDefinition = beanFactory.getBeanDefinition(beanName);
            return beanDefinition.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE;
        } catch (NoSuchBeanDefinitionException e) {
            return false;
        }
    }
}
