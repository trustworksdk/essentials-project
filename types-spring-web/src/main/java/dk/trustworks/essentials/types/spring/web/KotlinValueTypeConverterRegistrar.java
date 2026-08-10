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

package dk.trustworks.essentials.types.spring.web;

import org.springframework.format.FormatterRegistry;
import org.springframework.util.ClassUtils;

/**
 * Adds {@link KotlinValueTypeConverter} to a {@link FormatterRegistry}, but only on an application that actually has
 * Kotlin on its classpath.
 * <p>
 * {@code kotlin-reflect} and {@code kotlin-stdlib} are <b>optional</b> dependencies of {@code types-spring-web} (as
 * they are of {@code types} and {@code types-jdbi}), so a Java-only application does not get them transitively.
 * {@link KotlinValueTypeConverter} is compiled Kotlin and instantiating it there would fail with
 * {@link NoClassDefFoundError} while the application context is starting. The guard below keeps that class from ever
 * being loaded in that case - the {@code new} expression is the only reference to it, and the JVM resolves it lazily.
 * <p>
 * Not part of the public API: it exists so {@link EssentialsWebMvcConfigurer} and {@link EssentialsWebFluxConfigurer}
 * do not each repeat the check.
 */
final class KotlinValueTypeConverterRegistrar {
    /**
     * Belongs to {@code kotlin-reflect}, which transitively brings {@code kotlin-stdlib} - so one probe covers both.
     * It is also what {@link KotlinValueTypeConverter} actually needs: {@code KClass.primaryConstructor}.
     */
    private static final String KOTLIN_REFLECT_PROBE_CLASS = "kotlin.reflect.full.KClasses";

    private KotlinValueTypeConverterRegistrar() {
    }

    static void addTo(FormatterRegistry registry) {
        addTo(registry, KotlinValueTypeConverterRegistrar.class.getClassLoader());
    }

    /**
     * Package-private overload taking the class loader to probe, so a test can exercise the Kotlin-absent branch
     * without having to construct an isolated classpath.
     */
    static void addTo(FormatterRegistry registry, ClassLoader classLoader) {
        if (isKotlinReflectPresent(classLoader)) {
            registry.addConverter(new KotlinValueTypeConverter());
        }
    }

    static boolean isKotlinReflectPresent(ClassLoader classLoader) {
        return ClassUtils.isPresent(KOTLIN_REFLECT_PROBE_CLASS, classLoader);
    }
}
