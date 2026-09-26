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

import dk.trustworks.essentials.components.foundation.test.classpath.Jackson3OnlyClassLoader;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * An application whose only Jackson is Jackson 3 must be able to use this starter. Up to 0.50.0 introspecting the
 * auto-configuration failed with {@code NoClassDefFoundError: com/fasterxml/jackson/databind/Module}, because the
 * serializer bean method named Jackson 2's {@code Module}. The scenario runs through {@link Jackson3OnlyClassLoader},
 * as the test classpath can still carry Jackson 2 transitively.
 */
class Jackson3OnlyClasspathTest {
    private static Jackson3OnlyClassLoader jackson3Only;

    @BeforeAll
    static void createClassLoaderWithoutJackson2() {
        jackson3Only = Jackson3OnlyClassLoader.fromTestClasspath();
    }

    @AfterAll
    static void close() throws Exception {
        if (jackson3Only != null) {
            jackson3Only.close();
        }
    }

    @Test
    void the_class_loader_really_hides_jackson_2() {
        assertThat(jackson3Only.hidesJackson2()).isTrue();
    }

    @Test
    void the_auto_configuration_can_be_introspected() {
        assertThatCode(() -> run("introspectTheAutoConfiguration")).doesNotThrowAnyException();
    }

    @Test
    void the_default_serializer_is_jackson_3_and_works() throws Throwable {
        assertThat(run("serializeWithTheDefaultSerializer")).isEqualTo("\"orders\"");
    }

    private static Object run(String step) throws Throwable {
        return jackson3Only.runStatic(Jackson3OnlyScenario.class.getName(), step);
    }
}
