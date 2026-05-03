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

import org.slf4j.*;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.*;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A {@link BeanPostProcessor} implementation responsible for processing and registering beans
 * annotated with {@link AggregateClosingBooksPolicy} in the provided {@link AggregateClosingBooksPolicyRegistry}.
 * <p>
 * The processor identifies beans configured with {@link AggregateClosingBooksPolicy}, extracts
 * their metadata, and creates {@link AggregateClosingBooksPolicyDescriptor} instances, which
 * are then registered with the {@link AggregateClosingBooksPolicyRegistry}. The metadata includes
 * information such as the aggregate's implementation type and any explicitly defined aggregate type
 * from the annotation.
 * <p>
 * The post-processor also ensures that only beans that are not infrastructure-related (as determined
 * by their bean definition role) are considered for processing. If a bean's definition role is
 * {@link BeanDefinition#ROLE_INFRASTRUCTURE}, it will be skipped.
 * <p>
 * This class primarily enhances the behavior of aggregate-related processing by facilitating the
 * dynamic registration of closing-books policies in a Spring-based application.
 */
public class AggregateClosingBooksPolicyBeanPostProcessor implements BeanPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(AggregateClosingBooksPolicyBeanPostProcessor.class);

    private final AggregateClosingBooksPolicyRegistry policyRegistry;
    private final ConfigurableListableBeanFactory     beanFactory;

    public AggregateClosingBooksPolicyBeanPostProcessor(AggregateClosingBooksPolicyRegistry policyRegistry,
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

        var targetClass = AopProxyUtils.ultimateTargetClass(bean);
        var policy = AnnotationUtils.findAnnotation(targetClass, AggregateClosingBooksPolicy.class);
        if (policy == null) {
            return bean;
        }

        var aggregateType = policy.aggregateType().isBlank() ? Optional.<String>empty() : Optional.of(policy.aggregateType());
        var descriptor = new AggregateClosingBooksPolicyDescriptor(targetClass, aggregateType, policy);
        policyRegistry.register(descriptor);
        log.debug("Registered aggregate closing-books policy for '{}' from bean '{}'",
                  targetClass.getName(),
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
