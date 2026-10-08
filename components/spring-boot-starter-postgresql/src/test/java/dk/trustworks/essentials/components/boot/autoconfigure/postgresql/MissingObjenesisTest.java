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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;

import dk.trustworks.essentials.components.foundation.boot.MissingDependencyFailureAnalyzer;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.util.Arrays;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.*;

/**
 * This starter brings {@code immutable-jackson3}, which needs Objenesis, and Objenesis stays {@code provided}
 * everywhere by design. An application that does not declare it used to start and write normally and then fail the
 * first read of a persisted object with {@code NoClassDefFoundError: org/objenesis/...}. The mapper now refuses to be
 * created instead, naming the dependency.
 * <p>
 * The test classpath always has Objenesis (Mockito brings it), so the scenario runs through a class loader over the
 * test classpath minus the Objenesis jar.
 */
class MissingObjenesisTest {

    @Test
    void the_class_loader_really_hides_objenesis() throws Exception {
        try (var withoutObjenesis = testClasspathWithout(MissingObjenesisTest::isObjenesis)) {
            assertThatThrownBy(() -> Class.forName("org.objenesis.ObjenesisStd", false, withoutObjenesis))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void the_persistence_mapper_is_created_when_objenesis_is_present() throws Exception {
        try (var everything = testClasspathWithout(entry -> false)) {
            assertThat(createMapperThrough(everything)).isNotNull();
        }
    }

    @Test
    void the_persistence_mapper_refuses_to_be_created_without_objenesis_and_names_the_dependency() throws Exception {
        try (var withoutObjenesis = testClasspathWithout(MissingObjenesisTest::isObjenesis)) {
            assertThatThrownBy(() -> createMapperThrough(withoutObjenesis))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause()
                    .satisfies(cause -> assertThat(cause.getClass().getName()).isEqualTo(IllegalStateException.class.getName()))
                    .hasMessageContaining("org.objenesis:objenesis")
                    .hasMessageContaining("provided scope by design");
        }
    }

    /** The way out for an application that does not want the module: essentials.immutable-jackson-module-enabled=false. */
    @Test
    void a_mapper_without_the_immutable_module_needs_no_objenesis() throws Exception {
        try (var withoutObjenesis = testClasspathWithout(MissingObjenesisTest::isObjenesis)) {
            var mappers      = Class.forName(EssentialsObjectMappers.class.getName(), true, withoutObjenesis);
            var modulesArray = Class.forName("[Ltools.jackson.databind.JacksonModule;", false, withoutObjenesis);
            var mapper = mappers.getMethod("createJackson3ObjectMapper", boolean.class, modulesArray)
                                .invoke(null, false, Array.newInstance(modulesArray.getComponentType(), 0));

            assertThat(mapper).isNotNull();
        }
    }

    @Test
    void spring_boot_reports_the_missing_objenesis_with_its_coordinates() throws Exception {
        try (var withoutObjenesis = testClasspathWithout(MissingObjenesisTest::isObjenesis)) {
            var failure = catchThrowable(() -> createMapperThrough(withoutObjenesis));

            var analyzers = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                                                 // Spring Boot's own analyzers need constructor arguments this test does not supply
                                                 .load(FailureAnalyzer.class, null, (factoryType, implementationName, notInstantiable) -> {
                                                 });
            assertThat(analyzers).as("registered through this starter's META-INF/spring.factories")
                                 .hasAtLeastOneElementOfType(MissingDependencyFailureAnalyzer.class);
            var analysis = analyzers.stream()
                                    .filter(MissingDependencyFailureAnalyzer.class::isInstance)
                                    .findFirst()
                                    .orElseThrow()
                                    .analyze(new BeanCreationException("jsonSerializer", "Failed to instantiate", failure.getCause()));
            assertThat(analysis).isNotNull();
            assertThat(analysis.getAction()).contains("org.objenesis:objenesis");
        }
    }

    private static Object createMapperThrough(ClassLoader classLoader) throws Exception {
        var mappers      = Class.forName(EssentialsObjectMappers.class.getName(), true, classLoader);
        var modulesArray = Class.forName("[Ltools.jackson.databind.JacksonModule;", false, classLoader);
        assertThat(mappers.getClassLoader()).as("EssentialsObjectMappers must be loaded in isolation").isSameAs(classLoader);
        return mappers.getMethod("createJackson3ObjectMapper", modulesArray)
                      .invoke(null, Array.newInstance(modulesArray.getComponentType(), 0));
    }

    private static boolean isObjenesis(String classpathEntry) {
        return classpathEntry.replace('\\', '/').contains("/org/objenesis/");
    }

    private static URLClassLoader testClasspathWithout(Predicate<String> excluded) {
        // Surefire runs tests through a manifest-only jar, so java.class.path does not list the real test classpath
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var urls = Arrays.stream(classpath.split(File.pathSeparator))
                         .filter(entry -> !entry.isBlank() && !excluded.test(entry))
                         .map(MissingObjenesisTest::toUrl)
                         .toArray(URL[]::new);
        return new URLClassLoader("test-classpath-subset", urls, ClassLoader.getPlatformClassLoader());
    }

    private static URL toUrl(String classpathEntry) {
        try {
            return new File(classpathEntry).toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(classpathEntry, e);
        }
    }
}
