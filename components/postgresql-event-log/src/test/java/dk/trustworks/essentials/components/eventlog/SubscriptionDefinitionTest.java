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
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/** The definition's validation, defaults and filter digest */
class SubscriptionDefinitionTest {
    @Test
    void defaults() {
        var definition = SubscriptionDefinition.builder("orders-view", "Orders").build();
        assertThat(definition.ownership()).isSameAs(Ownership.exclusive());
        assertThat(definition.ownership().instanceName()).isEmpty();
        assertThat(definition.startFrom()).isEqualTo(StartFrom.beginning());
        assertThat(definition.consistency()).isEqualTo(ConsistencyMode.FRESH);
        assertThat(definition.eventTypes().matchesAll()).isTrue();
        assertThat(definition.tenant()).isEmpty();
        assertThat(definition.errors()).isEmpty();
        assertThat(definition.filterDigest()).isNull();
    }

    @Test
    void invalid_definitions_are_refused() {
        assertThatThrownBy(() -> Ownership.perInstance(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionDefinition.builder("has space", "Orders").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionDefinition.builder("x; drop", "Orders").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionDefinition.builder("ok", " ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionDefinition.builder("ok", "Orders").tenant("").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StartFrom.globalOrder(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorSettings(-1, Duration.ofMillis(1), Duration.ofMillis(1), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorSettings(0, Duration.ofMillis(2), Duration.ofMillis(1), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorSettings(0, Duration.ofMillis(1), Duration.ofMillis(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorSettings(0, Duration.ofMillis(1), Duration.ofMillis(1), 10_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void error_defaults_come_from_the_event_log_settings() {
        var defaults = ErrorSettings.defaults(EventLogSettings.defaults());
        assertThat(defaults).isEqualTo(new ErrorSettings(3, Duration.ofMillis(10), Duration.ofSeconds(10), 100));
        assertThat(defaults.backoff(0)).isEqualTo(Duration.ofMillis(10));
        assertThat(defaults.backoff(3)).isEqualTo(Duration.ofMillis(80));
        assertThat(defaults.backoff(30)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void the_digest_is_stable_and_order_insensitive() {
        var ab = SubscriptionDefinition.filterDigest(EventTypeFilter.of("A", "B"), Optional.empty());
        assertThat(SubscriptionDefinition.filterDigest(EventTypeFilter.of("B", "A"), Optional.empty())).isEqualTo(ab);
        assertThat(SubscriptionDefinition.filterDigest(EventTypeFilter.of("A", "B"), Optional.of("t1"))).isNotEqualTo(ab);
        assertThat(SubscriptionDefinition.filterDigest(EventTypeFilter.all(), Optional.of("t1"))).hasSize(64);
        assertThat(SubscriptionDefinition.filterDigest(EventTypeFilter.of("AB"), Optional.empty())).isNotEqualTo(SubscriptionDefinition.filterDigest(EventTypeFilter.of("A", "B"), Optional.empty()));
    }
}
