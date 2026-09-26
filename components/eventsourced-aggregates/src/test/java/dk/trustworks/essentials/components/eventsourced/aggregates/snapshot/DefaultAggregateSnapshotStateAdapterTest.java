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

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAggregateSnapshotStateAdapterTest {
    private final AggregateSnapshotStateAdapter adapter = new DefaultAggregateSnapshotStateAdapter(EssentialsJSONEventSerializers.create());

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

    @Test
    void deserializes_aggregate_with_required_arg_constructor_without_essentials_immutable_jackson_module() {
        // Critical regression: aggregates with a required-arg constructor and no @JsonCreator
        // must deserialize even if the user has NOT registered EssentialsImmutableJacksonModule
        // (and therefore Jackson alone cannot instantiate the type from "{}"). Objenesis is used
        // directly by DefaultAggregateSnapshotStateAdapter to bypass the constructor.
        //
        // Deliberately a bare mapper rather than EssentialsJSONEventSerializers: the scenario needs a mapper that
        // CANNOT instantiate the type. Jackson 3 would otherwise read the lone single-argument constructor as a
        // creator (parameter names are compiled in), construct the aggregate itself and never reach the Objenesis
        // fallback this test covers, so creator detection is switched off. No Essentials Jackson module is
        // registered.
        var jsonMapper = JsonMapper.builder()
                                   .changeDefaultVisibility(visibility -> tools.jackson.databind.introspect.VisibilityChecker
                                           .defaultInstance()
                                           .withGetterVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                                           .withSetterVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                                           .withFieldVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
                                           .withCreatorVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE))
                                   .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                   .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                                   .build();
        var serializer = new Jackson3JSONEventSerializer(jsonMapper);
        var adapter = new DefaultAggregateSnapshotStateAdapter(serializer);

        var snapshot = "{\"orderNumber\":42}";
        var restored = adapter.deserializeSnapshotState(snapshot,
                                                        RequiredArgConstructorAggregate.class,
                                                        "order-1",
                                                        EventOrder.of(1));

        assertThat(restored.orderNumber).isEqualTo(42);
    }

    /** Aggregate that has only a required-arg constructor, no no-arg constructor, no @JsonCreator —
     *  the case the original review flagged as silently failing. */
    public static class RequiredArgConstructorAggregate {
        public int orderNumber;

        public RequiredArgConstructorAggregate(int orderNumber) {
            if (orderNumber < 0) {
                throw new IllegalArgumentException("orderNumber must be >= 0");
            }
            this.orderNumber = orderNumber;
        }
    }

}
