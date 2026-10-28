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

import dk.trustworks.essentials.components.foundation.boot.EssentialsStartupChecksAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every property this starter's configuration metadata marks as removed (deprecation level {@code error}) must also be
 * reported at startup: the metadata only reaches an IDE, and Spring Boot ignores an unbound key without a word.
 */
@ExtendWith(OutputCaptureExtension.class)
class RemovedPropertiesCheckTest {

    private static final Path METADATA = Path.of("src", "main", "resources", "META-INF", "additional-spring-configuration-metadata.json");

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(EssentialsStartupChecksAutoConfiguration.class));

    @Test
    void the_metadata_marks_every_property_removed_in_0_60_0() {
        assertThat(removedPropertiesInMetadata()).containsExactlyInAnyOrder("essentials.durable-queues.transactional-mode",
                                                                            "essentials.durable-queues.use-ordered-unordered-query",
                                                                            "essentials.durable-queues.enable-queue-statistics",
                                                                            "essentials.durable-queues.shared-queue-statistics-table-name",
                                                                            "essentials.durable-queues.enable-queue-statistics-ttl",
                                                                            "essentials.durable-queues.queue-statistics-ttl-duration");
    }

    @Test
    void every_removed_property_still_set_is_logged_as_an_error(CapturedOutput output) {
        var removed = removedPropertiesInMetadata();
        contextRunner.withPropertyValues(removed.stream().map(property -> property + "=x").toArray(String[]::new))
                     .run(ctx -> assertThat(ctx).hasNotFailed());

        removed.forEach(property -> assertThat(output).contains("ERROR")
                                                      .contains("Essentials: the property '" + property + "' is set"));
    }

    @Test
    void nothing_is_logged_when_no_removed_property_is_set(CapturedOutput output) {
        contextRunner.withPropertyValues("essentials.durable-queues.shared-queue-table-name=durable_queues")
                     .run(ctx -> assertThat(ctx).hasNotFailed());

        assertThat(output).doesNotContain("Essentials: the property");
    }

    static List<String> removedPropertiesInMetadata() {
        var removed = new ArrayList<String>();
        JsonMapper.builder().build().readTree(METADATA.toFile()).get("properties").forEach(property -> {
            var deprecation = property.get("deprecation");
            if (deprecation != null && "error".equals(deprecation.get("level").asString())) {
                removed.add(property.get("name").asString());
            }
        });
        return removed;
    }
}
