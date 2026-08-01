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

package dk.trustworks.essentials.components.boot.autoconfigure.admin.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the starter is actually discoverable as a starter.
 * <p>
 * {@link EssentialsAdminUiAutoConfigurationTest} cannot catch this: it registers the configuration class
 * explicitly via {@code AutoConfigurations.of(...)}, which bypasses the imports file entirely. So the
 * wiring can be perfect and green while a real application loads none of it — which is exactly what
 * happened when this file was first written one directory too high, at {@code spring/…} instead of
 * {@code META-INF/spring/…}.
 */
class AutoConfigurationRegistrationTest {

    private static final String IMPORTS_RESOURCE =
            "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void the_auto_configuration_is_registered_at_the_location_spring_boot_reads() throws Exception {
        try (InputStream imports = getClass().getResourceAsStream(IMPORTS_RESOURCE)) {
            assertThat(imports)
                    .as("""
                        %s is missing from the classpath. Spring Boot discovers auto-configurations only from \
                        that exact path — anywhere else and the starter silently does nothing in a real \
                        application.""", IMPORTS_RESOURCE)
                    .isNotNull();

            var declared = new String(imports.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(declared.lines().map(String::trim).filter(line -> !line.isBlank()))
                    .contains(EssentialsAdminUiAutoConfiguration.class.getName());
        }
    }

    @Test
    void every_declared_auto_configuration_class_exists() throws Exception {
        try (InputStream imports = getClass().getResourceAsStream(IMPORTS_RESOURCE)) {
            assertThat(imports).isNotNull();
            var declared = new String(imports.readAllBytes(), StandardCharsets.UTF_8);

            for (String className : declared.lines().map(String::trim).filter(line -> !line.isBlank()).toList()) {
                assertThat(Class.forName(className))
                        .as("declared auto-configuration %s must be loadable", className)
                        .isNotNull();
            }
        }
    }
}
