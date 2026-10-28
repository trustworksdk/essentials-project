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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

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

    /**
     * The per-queue stuck-handler timeouts are copied in and read-only out, so neither the caller of the setter nor the
     * reader of the getter can change what the properties hold afterwards.
     */
    @Test
    void the_stuck_handler_timeouts_are_copied_in_and_read_only_out() {
        var properties = new ShardOwnedQueueProperties();
        var timeouts   = new LinkedHashMap<String, Duration>();
        timeouts.put("reports", Duration.ofHours(3));

        properties.setStuckHandlerTimeouts(timeouts);
        timeouts.put("orders", Duration.ofHours(1));

        assertThat(properties.getStuckHandlerTimeouts()).containsExactly(Map.entry("reports", Duration.ofHours(3)));
        assertThatThrownBy(() -> properties.getStuckHandlerTimeouts().put("orders", Duration.ofHours(1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Spring Boot binds a map by merging into the one the getter returns, and through the setter when that one refuses -
     * so the read-only getter must still bind correctly from more than one property source.
     */
    @Test
    void the_stuck_handler_timeouts_still_bind() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("essentials.shard-owned-queue.stuck-handler-timeouts.reports", "3h")),
                                new MapConfigurationPropertySource(Map.of("essentials.shard-owned-queue.stuck-handler-timeouts.[Inbox:orders]", "2h")));

        var properties = binder.bind("essentials.shard-owned-queue", ShardOwnedQueueProperties.class).get();

        assertThat(properties.getStuckHandlerTimeouts()).containsOnly(Map.entry("reports", Duration.ofHours(3)),
                                                                      Map.entry("Inbox:orders", Duration.ofHours(2)));
    }
}
