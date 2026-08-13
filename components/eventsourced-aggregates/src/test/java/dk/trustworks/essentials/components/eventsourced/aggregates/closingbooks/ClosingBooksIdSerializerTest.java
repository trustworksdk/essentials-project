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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ClosingBooksIdSerializerTest {

    private static final ClosingBooksIdSerializer<Integer> INTEGER_BASED = new ClosingBooksIdSerializer<>() {
        @Override
        public String serialize(Integer id) {
            return id.toString();
        }

        @Override
        public Integer deserialize(String persistedId) {
            return Integer.parseInt(persistedId);
        }
    };

    @Test
    void test_the_string_based_serializer_round_trips_an_id() {
        var serializer = ClosingBooksIdSerializer.stringBased();

        assertThat(serializer.serialize("Account-123")).isEqualTo("Account-123");
        assertThat(serializer.deserialize("Account-123")).isEqualTo("Account-123");
    }

    @Test
    void test_the_string_based_serializer_round_trips_a_logical_aggregate_id() {
        var serializer = ClosingBooksIdSerializer.stringBased();

        assertThat(serializer.serializeLogicalAggregateId(new LogicalAggregateId<>("Account-123"))).isEqualTo("Account-123");
        assertThat(serializer.deserializeLogicalAggregateId("Account-123")).isEqualTo(new LogicalAggregateId<>("Account-123"));
    }

    @Test
    void test_a_logical_aggregate_id_serializes_to_the_same_string_as_the_id_it_wraps() {
        // The persisted form of a logical aggregate id has to stay the value's own serialized form - it is what the
        // logical_aggregate_id column already holds
        assertThat(INTEGER_BASED.serializeLogicalAggregateId(new LogicalAggregateId<>(123)))
                .isEqualTo(INTEGER_BASED.serialize(123))
                .isEqualTo("123");
    }

    @Test
    void test_a_logical_aggregate_id_round_trips_through_a_typed_serializer() {
        var logicalAggregateId = new LogicalAggregateId<>(123);

        var persisted = INTEGER_BASED.serializeLogicalAggregateId(logicalAggregateId);

        assertThat(INTEGER_BASED.deserializeLogicalAggregateId(persisted)).isEqualTo(logicalAggregateId);
        assertThat(INTEGER_BASED.deserializeLogicalAggregateId(persisted).value()).isEqualTo(123);
    }

    @Test
    void test_the_serializer_rejects_null_arguments() {
        var serializer = ClosingBooksIdSerializer.stringBased();

        assertThatThrownBy(() -> serializer.serialize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serializer.deserialize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serializer.serializeLogicalAggregateId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serializer.deserializeLogicalAggregateId(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
