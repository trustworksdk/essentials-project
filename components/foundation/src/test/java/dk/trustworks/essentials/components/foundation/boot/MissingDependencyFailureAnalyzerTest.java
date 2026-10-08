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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;

import static org.assertj.core.api.Assertions.assertThat;

class MissingDependencyFailureAnalyzerTest {

    private final MissingDependencyFailureAnalyzer analyzer = new MissingDependencyFailureAnalyzer();

    /**
     * The symptoms the plugin's stack contract (S2.1) lists, each as the JVM reports it: a {@code NoClassDefFoundError}
     * names the class with slashes, a {@code ClassNotFoundException} with dots.
     */
    @ParameterizedTest
    @CsvSource({
            "org/jdbi/v3/postgres/PostgresPlugin, org.jdbi:jdbi3-postgres",
            "org/jdbi/v3/core/Jdbi, org.jdbi:jdbi3-core",
            "kotlin/jvm/internal/Intrinsics, org.jetbrains.kotlin:kotlin-stdlib",
            "kotlin/reflect/full/KClasses, org.jetbrains.kotlin:kotlin-reflect",
            "org/springframework/data/mongodb/core/convert/MongoCustomConversions$MongoConverterConfigurationAdapter, org.springframework.boot:spring-boot-starter-data-mongodb",
            "tools/jackson/databind/JacksonModule, tools.jackson.core:jackson-databind",
            "org/objenesis/ObjenesisStd, org.objenesis:objenesis",
            "org/reactivestreams/Publisher, io.projectreactor:reactor-core",
            "org/springframework/jdbc/datasource/TransactionAwareDataSourceProxy, org.springframework.boot:spring-boot-starter-jdbc"
    })
    void a_missing_class_deep_in_bean_creation_is_mapped_to_the_dependency_to_add(String missingClass, String coordinates) {
        var failure = new BeanCreationException("essentialsEventBus", "Failed to instantiate",
                                                new IllegalStateException("wrapped", new NoClassDefFoundError(missingClass)));

        var analysis = analyzer.analyze(failure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains(missingClass.replace('/', '.'));
        assertThat(analysis.getAction()).contains(coordinates).contains("provided scope, by design");
        assertThat(analysis.getCause()).isSameAs(failure);
    }

    @Test
    void a_class_not_found_exception_names_the_class_with_dots() {
        var analysis = analyzer.analyze(new IllegalStateException(new ClassNotFoundException("org.objenesis.ObjenesisStd")));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getAction()).contains("org.objenesis:objenesis");
    }

    @Test
    void a_missing_postgresql_driver_is_mapped_although_the_pool_reports_it_without_a_class_not_found_exception() {
        var analysis = analyzer.analyze(new BeanCreationException("dataSource", "Failed",
                                                                  new RuntimeException("Failed to load driver class org.postgresql.Driver in either of HikariConfig class loader or Thread context classloader")));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getAction()).contains("org.postgresql:postgresql");
    }

    @Test
    void a_missing_class_from_a_library_essentials_does_not_need_is_left_to_spring_boot() {
        assertThat(analyzer.analyze(new BeanCreationException("x", "Failed", new NoClassDefFoundError("com/example/Unknown")))).isNull();
        assertThat(analyzer.analyze(new IllegalStateException("no missing class at all"))).isNull();
    }

    @Test
    void a_cyclic_cause_chain_terminates() {
        var first  = new IllegalStateException("first");
        var second = new IllegalStateException("second", first);
        first.initCause(second);

        assertThat(analyzer.analyze(first)).isNull();
    }
}
