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

package dk.trustworks.essentials.examples.trading;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TradingDemoComposeProfileTest {

    @Test
    void compose_profile_contains_local_postgresql_and_essentials_runtime_settings() throws IOException {
        var composeProfile = new ClassPathResource("application-compose.yml").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(composeProfile).contains("compose:");
        assertThat(composeProfile).contains("enabled: true");
        assertThat(composeProfile).contains("file: classpath:compose.yml");
        assertThat(composeProfile).contains("url: jdbc:postgresql://localhost:5432/essentials-trading-demo");
        assertThat(composeProfile).contains("username: essentials");
        assertThat(composeProfile).contains("password: password");
        assertThat(composeProfile).contains("immutable-jackson-module-enabled: true");
        assertThat(composeProfile).contains("scheduler:");
        assertThat(composeProfile).contains("event-store-polling-batch-size: 5");
        assertThat(composeProfile).contains("snapshots:");
        assertThat(composeProfile).contains("closing-books:");
    }

    @Test
    void compose_file_exists_and_defines_postgresql_service() throws IOException {
        var composeText = new ClassPathResource("compose.yml").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(composeText).contains("services:");
        assertThat(composeText).contains("postgresql:");
        assertThat(composeText).contains("POSTGRES_DB: essentials-trading-demo");
    }
}
