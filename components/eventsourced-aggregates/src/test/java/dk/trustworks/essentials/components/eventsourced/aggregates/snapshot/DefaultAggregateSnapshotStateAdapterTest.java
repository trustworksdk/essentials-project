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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAggregateSnapshotStateAdapterTest {
    private final AggregateSnapshotStateAdapter adapter = new DefaultAggregateSnapshotStateAdapter(new JacksonJSONEventSerializer(createObjectMapper()));

    @Test
    void serializes_domain_state_without_framework_runtime_fields_for_plain_modern_aggregate() {
        var orderId = OrderId.random();
        var productId = ProductId.random();
        var order = new dk.trustworks.essentials.components.eventsourced.aggregates.modern.Order(orderId, CustomerId.random(), 1234);
        order.addProduct(productId, 10);
        order.accept();

        var snapshot = adapter.serializeSnapshotState(order);

        assertThat(snapshot).contains("productAndQuantity");
        assertThat(snapshot).contains(productId.toString());
        assertThat(snapshot).doesNotContain("invoker");
        assertThat(snapshot).doesNotContain("uncommittedEvents");
        assertThat(snapshot).doesNotContain("eventOrderOfLastAppliedEvent");
        assertThat(snapshot).doesNotContain("eventOrderOfLastRehydratedEvent");
        assertThat(snapshot).doesNotContain("hasBeenRehydrated");
    }

    @Test
    void round_trips_modern_with_state_aggregate_and_restores_runtime_state_from_snapshot_metadata() {
        var orderId = OrderId.random();
        var productId = ProductId.random();
        var order = new dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order(orderId, CustomerId.random(), 1234);
        order.addProduct(productId, 10);
        order.accept();

        var snapshot = adapter.serializeSnapshotState(order);
        var restored = adapter.deserializeSnapshotState(snapshot,
                                                        dk.trustworks.essentials.components.eventsourced.aggregates.modern.with_state.Order.class,
                                                        orderId,
                                                        EventOrder.of(2));

        assertThat(snapshot).contains("\"state\"");
        assertThat((Object) restored.aggregateId()).isEqualTo(orderId);
        assertThat(restored.eventOrderOfLastAppliedEvent()).isEqualTo(EventOrder.of(2));
        assertThat(restored.eventOrderOfLastRehydratedEvent()).isEqualTo(EventOrder.of(2));
        assertThat(restored.hasBeenRehydrated()).isTrue();
        assertThat(restored.getUncommittedChanges().events).isEmpty();
        assertThat(restored.state().accepted).isTrue();
        assertThat(restored.state().productAndQuantity).isEqualTo(Map.of(productId, 10));
    }

    static ObjectMapper createObjectMapper() {
        var objectMapper = JsonMapper.builder()
                                     .disable(MapperFeature.AUTO_DETECT_GETTERS)
                                     .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                                     .disable(MapperFeature.AUTO_DETECT_SETTERS)
                                     .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                                     .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                     .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                     .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                                     .enable(MapperFeature.AUTO_DETECT_CREATORS)
                                     .enable(MapperFeature.AUTO_DETECT_FIELDS)
                                     .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
                                     .addModule(new Jdk8Module())
                                     .addModule(new JavaTimeModule())
                                     .addModule(new EssentialTypesJacksonModule())
                                     .addModule(new EssentialsImmutableJacksonModule())
                                     .build();

        objectMapper.setVisibility(objectMapper.getSerializationConfig().getDefaultVisibilityChecker()
                                               .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                                               .withCreatorVisibility(JsonAutoDetect.Visibility.ANY));
        return objectMapper;
    }
}
