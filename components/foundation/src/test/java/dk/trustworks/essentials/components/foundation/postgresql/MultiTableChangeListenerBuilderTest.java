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

package dk.trustworks.essentials.components.foundation.postgresql;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.reactive.EventBus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MultiTableChangeListenerBuilderTest {

    @Test
    void jdbi_jsonSerializer_and_eventBus_are_required() {
        assertThatThrownBy(() -> MultiTableChangeListener.builder()
                                                         .setJsonSerializer(mock(JSONSerializer.class))
                                                         .setEventBus(mock(EventBus.class))
                                                         .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setJdbi");
        assertThatThrownBy(() -> MultiTableChangeListener.builder()
                                                         .setJdbi(mock(Jdbi.class))
                                                         .setEventBus(mock(EventBus.class))
                                                         .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setJsonSerializer");
        assertThatThrownBy(() -> MultiTableChangeListener.builder()
                                                         .setJdbi(mock(Jdbi.class))
                                                         .setJsonSerializer(mock(JSONSerializer.class))
                                                         .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setEventBus");
    }

    @Test
    void the_defaults_match_the_starter() {
        var eventBus = mock(EventBus.class);
        try (var listener = MultiTableChangeListener.builder()
                                                    .setJdbi(mock(Jdbi.class))
                                                    .setJsonSerializer(mock(JSONSerializer.class))
                                                    .setEventBus(eventBus)
                                                    .build()) {
            assertThat(listener.isFilterDuplicateNotifications()).isTrue();
            assertThat(listener.getEventBus()).isSameAs(eventBus);
            assertThat(listener.isStarted()).isFalse();
        }
        assertThat(MultiTableChangeListenerBuilder.DEFAULT_POLLING_INTERVAL).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void a_listener_jdbi_and_a_listener_data_source_are_mutually_exclusive() {
        assertThatThrownBy(() -> MultiTableChangeListener.builder()
                                                         .setJdbi(mock(Jdbi.class))
                                                         .setJsonSerializer(mock(JSONSerializer.class))
                                                         .setEventBus(mock(EventBus.class))
                                                         .setListenerJdbi(mock(Jdbi.class))
                                                         .setListenerDataSource(mock(DataSource.class))
                                                         .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void intervals_are_validated() {
        var builder = MultiTableChangeListener.builder();
        assertThatThrownBy(() -> builder.setPollingInterval(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.setLivenessCheckInterval(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.setConnectionLossLogInterval(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> builder.setLivenessCheckInterval(Duration.ZERO)).doesNotThrowAnyException();
    }

    @Test
    void the_channel_deduplication_policy_matches_tables_case_insensitively() {
        var policy = ChannelDeduplicationPolicy.onlyTables("Orders", "durable_queues");
        assertThat(policy.isDeduplicated("orders")).isTrue();
        assertThat(policy.isDeduplicated("durable_queues")).isTrue();
        assertThat(policy.isDeduplicated("customers")).isFalse();
        assertThat(ChannelDeduplicationPolicy.allChannels().isDeduplicated("anything")).isTrue();
    }
}
