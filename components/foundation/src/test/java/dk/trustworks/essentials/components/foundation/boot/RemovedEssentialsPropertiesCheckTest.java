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

import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemovedEssentialsPropertiesCheckTest {

    @Test
    void nothing_is_reported_when_no_removed_property_is_set() {
        var environment = environmentWith(Map.of("essentials.durable-queues.shared-queue-table-name", "durable_queues"));

        assertThat(RemovedEssentialsPropertiesCheck.findRemovedPropertiesStillSet(environment)).isEmpty();
    }

    @Test
    void every_removed_property_is_reported_with_its_value_and_guidance() {
        var environment = environmentWith(Map.of("essentials.durable-queues.transactional-mode", "fully-transactional",
                                                 "essentials.durable-queues.enable-queue-statistics", "true"));

        var messages = RemovedEssentialsPropertiesCheck.findRemovedPropertiesStillSet(environment);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).contains("'essentials.durable-queues.enable-queue-statistics'", "value 'true'", "QueueStatisticsRegistry");
        assertThat(messages.get(1)).contains("'essentials.durable-queues.transactional-mode'", "value 'fully-transactional'",
                                             "markAsRollbackOnly()", "TransactionalMode is retired");
    }

    /**
     * Spring Boot binds relaxed names, so an application may well have written the key in camelCase in YAML or as an
     * environment variable. Either must be caught.
     */
    @Test
    void a_removed_property_is_found_under_any_name_spring_boot_would_have_bound() {
        var camelCase = environmentWith(Map.of("essentials.durableQueues.transactionalMode", "FullyTransactional"));
        var envVar    = environmentWith(Map.of("ESSENTIALS_DURABLEQUEUES_USEORDEREDUNORDEREDQUERY", "false"));

        assertThat(RemovedEssentialsPropertiesCheck.findRemovedPropertiesStillSet(camelCase))
                .singleElement().asString().contains("'essentials.durable-queues.transactional-mode'");
        assertThat(RemovedEssentialsPropertiesCheck.findRemovedPropertiesStillSet(envVar))
                .singleElement().asString().contains("'essentials.durable-queues.use-ordered-unordered-query'");
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        if (properties.keySet().stream().anyMatch(key -> key.equals(key.toUpperCase()))) {
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, properties));
        } else {
            environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        }
        return environment;
    }
}
