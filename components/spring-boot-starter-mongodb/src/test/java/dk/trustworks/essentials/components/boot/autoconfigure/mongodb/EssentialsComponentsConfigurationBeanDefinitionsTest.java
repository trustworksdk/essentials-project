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

package dk.trustworks.essentials.components.boot.autoconfigure.mongodb;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code @Bean} method in {@link EssentialsComponentsConfiguration} must produce a distinct type. The
 * configuration once declared {@code EssentialTypesJacksonModule} twice under two names, both
 * {@code @ConditionalOnMissingBean}: the second was silently skipped at runtime, so nothing failed, but overriding or
 * reasoning about "the" bean depended on method order. This starter has no mutually exclusive alternatives for one
 * type, so any repeat is a mistake.
 */
class EssentialsComponentsConfigurationBeanDefinitionsTest {

    @Test
    void no_two_bean_methods_produce_the_same_type() {
        Map<Class<?>, List<String>> beanMethodsByType = Arrays.stream(EssentialsComponentsConfiguration.class.getDeclaredMethods())
                                                              .filter(method -> method.isAnnotationPresent(Bean.class))
                                                              .collect(Collectors.groupingBy(Method::getReturnType,
                                                                                             Collectors.mapping(Method::getName, Collectors.toList())));

        assertThat(beanMethodsByType).isNotEmpty();
        assertThat(beanMethodsByType.entrySet().stream()
                                    .filter(entry -> entry.getValue().size() > 1)
                                    .map(entry -> entry.getKey().getName() + " <- " + entry.getValue()))
                .as("@Bean methods producing the same type")
                .isEmpty();
    }
}
