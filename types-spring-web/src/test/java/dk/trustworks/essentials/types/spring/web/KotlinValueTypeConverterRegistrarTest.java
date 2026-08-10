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

import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code kotlin-reflect} is an <b>optional</b> dependency of {@code types-spring-web}, so a Java-only application
 * does not get it transitively. If {@link KotlinValueTypeConverterRegistrar} instantiated
 * {@code KotlinValueTypeConverter} unconditionally, every such application would fail to start with a
 * {@link NoClassDefFoundError} the moment {@link EssentialsWebMvcConfigurer} was imported - a failure mode that
 * would only show up for consumers, never in this module's own build, where Kotlin is always present.
 */
class KotlinValueTypeConverterRegistrarTest {

    /**
     * Stands in for a Java-only consumer's class loader: everything resolves except Kotlin.
     */
    private static final class KotlinHidingClassLoader extends ClassLoader {
        private KotlinHidingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("kotlin.")) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }

    @Test
    void kotlin_reflect_is_detected_when_it_is_on_the_class_path() {
        assertThat(KotlinValueTypeConverterRegistrar.isKotlinReflectPresent(getClass().getClassLoader())).isTrue();
    }

    @Test
    void kotlin_reflect_is_not_detected_when_it_is_hidden() {
        var javaOnlyClassLoader = new KotlinHidingClassLoader(getClass().getClassLoader());

        assertThat(KotlinValueTypeConverterRegistrar.isKotlinReflectPresent(javaOnlyClassLoader)).isFalse();
    }

    @Test
    void the_kotlin_converter_is_registered_when_kotlin_is_available() {
        var registry = new DefaultFormattingConversionService();

        KotlinValueTypeConverterRegistrar.addTo(registry, getClass().getClassLoader());

        assertThat(registry.canConvert(String.class, KotlinSemanticTypeUnderTest.class)).isTrue();
    }

    @Test
    void nothing_is_registered_and_nothing_is_thrown_when_kotlin_is_absent() {
        var registry = new DefaultFormattingConversionService();

        // The assertion is as much that this call returns at all as that it registers nothing: an unguarded
        // `new KotlinValueTypeConverter()` would raise NoClassDefFoundError here rather than fail an assertion.
        KotlinValueTypeConverterRegistrar.addTo(registry, new KotlinHidingClassLoader(getClass().getClassLoader()));

        assertThat(registry.canConvert(String.class, KotlinSemanticTypeUnderTest.class)).isFalse();
    }

    /**
     * A non-inline Kotlin semantic type over a non-{@code String} value - the one shape that genuinely needs
     * {@code KotlinValueTypeConverter}. See {@code KotlinValueTypeConverterRequiredTest} for why the other shapes
     * do not.
     */
    private interface KotlinSemanticTypeUnderTest extends dk.trustworks.essentials.kotlin.types.LongValueType<KotlinSemanticTypeUnderTest> {
    }
}
