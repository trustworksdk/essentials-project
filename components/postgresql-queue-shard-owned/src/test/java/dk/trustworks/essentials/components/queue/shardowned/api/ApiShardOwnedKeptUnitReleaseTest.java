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

    @Test
    void a_null_key_in_a_handler_is_refused_with_a_message_naming_the_field() {
        var keys = Arrays.asList("ACC-1", null);

        assertThatThrownBy(() -> new ApiShardOwnedKeptUnitRelease(QueueName.of("orders"), 3, "node-1", "BUSY", keys, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keysInHandler");
    }

    /**
     * {@link ApiShardOwnedKeptUnitReleases#from} carries the instance ids that answered, in order, and each release, so an
     * answer that released nothing still names the process that gave it.
     */
    @Test
    void the_answer_names_the_instance_ids_that_answered_beside_what_was_released() {
        var queueName = QueueName.of("orders");
        var answer = new KeptUnitReleases(List.of("node-1", "node-1-1"),
                                          List.of(new KeptUnitRelease(17, "node-1-1", KeptUnitRelease.Outcome.RELEASED, List.of("ACC-1"), Duration.ofSeconds(90))));

        var api = ApiShardOwnedKeptUnitReleases.from(queueName, answer);

        assertThat(api.answeredBy()).containsExactly("node-1", "node-1-1");
        assertThat(api.releases()).singleElement().satisfies(release -> {
            assertThat(release.queueName()).isEqualTo(queueName);
            assertThat(release.instanceId()).isEqualTo("node-1-1");
            assertThat(release.outcome()).isEqualTo("RELEASED");
        });
    }

    @Test
    void an_answer_that_released_nothing_still_names_who_answered() {
        var api = ApiShardOwnedKeptUnitReleases.from(QueueName.of("orders"), new KeptUnitReleases(List.of("node-2"), List.of()));

        assertThat(api.answeredBy()).containsExactly("node-2");
        assertThat(api.releases()).isEmpty();
    }

    @Test
    void the_answer_keeps_its_own_unmodifiable_copies() {
        var answeredBy = new ArrayList<>(List.of("node-1"));
        var releases   = new ArrayList<ApiShardOwnedKeptUnitRelease>();

        var api = new ApiShardOwnedKeptUnitReleases(answeredBy, releases);
        answeredBy.add("node-2");
        releases.add(new ApiShardOwnedKeptUnitRelease(QueueName.of("orders"), 3, "node-1", "BUSY", List.of(), 0));

        assertThat(api.answeredBy()).containsExactly("node-1");
        assertThat(api.releases()).isEmpty();
        assertThatThrownBy(() -> api.answeredBy().add("node-3")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ApiShardOwnedKeptUnitReleases(null, null)).isEqualTo(ApiShardOwnedKeptUnitReleases.NONE);
    }
}
