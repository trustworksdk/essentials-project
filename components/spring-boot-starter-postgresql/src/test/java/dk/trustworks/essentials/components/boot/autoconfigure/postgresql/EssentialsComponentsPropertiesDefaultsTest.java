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

import dk.trustworks.essentials.components.queue.postgresql.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter restates some component defaults as literals, because the Spring configuration processor only records a
 * property's default in the configuration metadata when it can read it from a literal. This test keeps each literal
 * equal to the component constant it restates, so the component stays the one source of the value.
 */
class EssentialsComponentsPropertiesDefaultsTest {
    @Test
    void durable_queues_max_polling_interval_matches_the_component_default() {
        assertThat(new EssentialsComponentsProperties().getDurableQueues().getMaxPollingInterval())
                .isEqualTo(PostgresqlDurableQueues.DEFAULT_MAX_POLLING_INTERVAL);
    }

    @Test
    void multi_table_change_listener_polling_interval_matches_the_component_default() {
        assertThat(new EssentialsComponentsProperties().getMultiTableChangeListener().getPollingInterval())
                .isEqualTo(PostgresqlDurableQueuesBuilder.DEFAULT_CHANGE_LISTENER_POLLING_INTERVAL);
    }
}
