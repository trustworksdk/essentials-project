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

package dk.trustworks.essentials.components.foundation.boot;

import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * The startup checks every Essentials Spring Boot starter shares. It lives here, in foundation, so there is one
 * implementation; each starter lists it in its own {@code AutoConfiguration.imports}, and Spring Boot imports it once
 * however many starters list it.
 * <p>
 * Ordered first so its post-processors are registered before any starter bean is created.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class EssentialsStartupChecksAutoConfiguration {

    /**
     * @return the check that every persistence {@code JSONSerializer} bean reproduces the persisted JSON format
     * @see WireFormatCheckBeanPostProcessor
     */
    @Bean
    public static WireFormatCheckBeanPostProcessor essentialsWireFormatCheck() {
        return new WireFormatCheckBeanPostProcessor();
    }

    /**
     * @return the check that logs an ERROR for every removed {@code essentials.*} property that is still set
     * @see RemovedEssentialsPropertiesCheck
     */
    @Bean
    public static RemovedEssentialsPropertiesCheck essentialsRemovedPropertiesCheck() {
        return new RemovedEssentialsPropertiesCheck();
    }
}
