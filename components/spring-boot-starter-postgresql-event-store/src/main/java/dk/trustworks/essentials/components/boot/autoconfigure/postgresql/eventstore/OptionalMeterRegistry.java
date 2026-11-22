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

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.util.ClassUtils;

import java.util.Optional;

/**
 * Looks up the optional Micrometer {@link MeterRegistry} bean for a {@code @Bean} method that must also work when
 * micrometer-core is not on the classpath (it is an optional, {@code provided} dependency of this starter).
 * <p>
 * An {@code Optional<MeterRegistry>} parameter cannot be used for that: Spring resolves the generic parameter type of a
 * {@code @Bean} method to inject it, and resolving {@code Optional<MeterRegistry>} without micrometer-core throws
 * {@code TypeNotPresentException}, so the application fails to start. Such a method takes the {@link BeanFactory}
 * instead and calls {@link #from(BeanFactory)}, which never touches the {@code MeterRegistry} type unless it is present.
 * The result is the same as the {@code Optional} injection point: empty when there is no registry bean, the primary
 * one when there are several.
 */
final class OptionalMeterRegistry {
    private static final String METER_REGISTRY_CLASS_NAME = "io.micrometer.core.instrument.MeterRegistry";

    private OptionalMeterRegistry() {
    }

    /**
     * @param beanFactory the bean factory to look the registry up in
     * @return the {@link MeterRegistry} bean, or {@link Optional#empty()} when micrometer-core is absent or no registry
     * bean is defined
     */
    static Optional<MeterRegistry> from(BeanFactory beanFactory) {
        if (!ClassUtils.isPresent(METER_REGISTRY_CLASS_NAME, OptionalMeterRegistry.class.getClassLoader())) {
            return Optional.empty();
        }
        return Optional.ofNullable(beanFactory.getBeanProvider(MeterRegistry.class).getIfAvailable());
    }
}
