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

package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.ShardOwnerSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The properties write their defaults as literals, so the configuration metadata can carry them; these keep each literal
 * equal to the engine's own default.
 */
class ShardOwnedQueuePropertiesTest {

    @Test
    void the_stuck_handler_timeout_default_is_the_engines() {
        var properties = new ShardOwnedQueueProperties();

        assertThat(properties.getStuckHandlerTimeout()).isEqualTo(ShardOwnerSettings.DEFAULT_STUCK_HANDLER_TIMEOUT);
        assertThat(properties.toSettings().stuckHandlerTimeout()).isEqualTo(ShardOwnerSettings.DEFAULT_STUCK_HANDLER_TIMEOUT);
        assertThat(properties.getStuckHandlerTimeouts()).as("no queue has its own unless configured").isEmpty();
    }
}
