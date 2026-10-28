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

import dk.trustworks.essentials.components.foundation.boot.MissingDependencyFailureAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot only consults a {@link FailureAnalyzer} listed in a {@code META-INF/spring.factories}, so the shared
 * analyzer in foundation reaches an application only through the starters' registration.
 */
class MissingDependencyFailureAnalyzerRegistrationTest {

    @Test
    void the_starter_registers_the_missing_dependency_failure_analyzer() {
        var analyzers = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                                             // Spring Boot's own analyzers need constructor arguments this test does not supply
                                             .load(FailureAnalyzer.class, null, (factoryType, implementationName, notInstantiable) -> {
                                             });

        var analyzer = analyzers.stream().filter(MissingDependencyFailureAnalyzer.class::isInstance).findFirst();
        assertThat(analyzer).isPresent();
        assertThat(analyzer.get().analyze(new BeanCreationException("reactiveHandlersBeanPostProcessor", "Failed to deduce bean type",
                                                                    new NoClassDefFoundError("org/springframework/data/mongodb/core/convert/MongoCustomConversions$MongoConverterConfigurationAdapter")))
                                 .getAction())
                .contains("org.springframework.boot:spring-boot-starter-data-mongodb");
    }
}
