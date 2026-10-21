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

package dk.trustworks.essentials.components.queue.shardowned.api;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link ApiShardOwnedKeptUnitRelease#from} carries every field of the engine's {@link KeptUnitRelease} into the admin
 * shape, for each {@link KeptUnitRelease.Outcome}: the outcome by its name, the time in a handler in milliseconds.
 */
class ApiShardOwnedKeptUnitReleaseTest {

    @ParameterizedTest
    @EnumSource(KeptUnitRelease.Outcome.class)
    void every_outcome_is_carried_into_the_admin_shape(KeptUnitRelease.Outcome outcome) {
        var queueName = QueueName.of("orders");
        var release   = new KeptUnitRelease(17, "node-1-2", outcome, List.of("ACC-1", "ACC-2"), Duration.ofMillis(1_234).plusNanos(999_999));

        var api = ApiShardOwnedKeptUnitRelease.from(queueName, release);

        assertThat(api.queueName()).isEqualTo(queueName);
        assertThat(api.unit()).isEqualTo(17);
        assertThat(api.instanceId()).isEqualTo("node-1-2");
        assertThat(api.outcome()).isEqualTo(outcome.name());
        assertThat(api.keysInHandler()).containsExactly("ACC-1", "ACC-2");
        assertThat(api.longestInHandlerMillis()).as("whole milliseconds").isEqualTo(1_234);
    }

    @ParameterizedTest
    @EnumSource(KeptUnitRelease.Outcome.class)
    void no_key_in_a_handler_is_an_empty_list_and_zero_milliseconds(KeptUnitRelease.Outcome outcome) {
        var api = ApiShardOwnedKeptUnitRelease.from(QueueName.of("orders"),
                                                    new KeptUnitRelease(0, "node-1", outcome, List.of(), Duration.ZERO));

        assertThat(api.keysInHandler()).isEmpty();
        assertThat(api.longestInHandlerMillis()).isZero();
    }

    @Test
    void the_record_keeps_its_own_unmodifiable_copy_of_the_keys_in_a_handler() {
        var keys = new ArrayList<>(List.of("ACC-1", "ACC-2"));

        var api = new ApiShardOwnedKeptUnitRelease(QueueName.of("orders"), 3, "node-1", "BUSY", keys, 5);
        keys.add("ACC-3");

        assertThat(api.keysInHandler()).as("a later change to the given list").containsExactly("ACC-1", "ACC-2");
        assertThatThrownBy(() -> api.keysInHandler().add("ACC-4")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(api.keysInHandler()).containsExactly("ACC-1", "ACC-2");
    }

    @Test
    void no_keys_in_a_handler_given_is_an_empty_list() {
        var api = new ApiShardOwnedKeptUnitRelease(QueueName.of("orders"), 3, "node-1", "BUSY", null, 0);

        assertThat(api.keysInHandler()).isEmpty();
    }
}
