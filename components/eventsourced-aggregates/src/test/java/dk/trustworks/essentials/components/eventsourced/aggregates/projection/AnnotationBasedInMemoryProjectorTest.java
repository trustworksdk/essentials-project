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

package dk.trustworks.essentials.components.eventsourced.aggregates.projection;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnotationBasedInMemoryProjectorTest {

    private static final AggregateType ORDERS = AggregateType.of("Orders");

    private final AnnotationBasedInMemoryProjector projector = new AnnotationBasedInMemoryProjector();

    // ================================= Test Events =================================

    public record OrderCreated(String orderId, String customerId) {}
    public record ProductAdded(String productId, int quantity) {}
    public record OrderAccepted() {}
    public record UnhandledEvent(String data) {}

    // ================================= Test Projections =================================

    public static class OrderSummaryProjection {
        private String orderId;
        private String customerId;
        private final List<String> products = new ArrayList<>();
        private boolean accepted;

        public OrderSummaryProjection() {}

        @EventHandler
        private void on(OrderCreated event) {
            this.orderId = event.orderId();
            this.customerId = event.customerId();
        }

        @EventHandler
        private void on(ProductAdded event) {
            products.add(event.productId());
        }

        @EventHandler
        private void on(OrderAccepted event) {
            this.accepted = true;
        }

        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public List<String> getProducts() { return products; }
        public boolean isAccepted() { return accepted; }
    }

    public static class ProjectionWithoutEventHandlers {
        private String value;

        public ProjectionWithoutEventHandlers() {}

        public void setValue(String value) {
            this.value = value;
        }
    }

    public static class ProjectionWithoutNoArgConstructor {
        private final String value;

        public ProjectionWithoutNoArgConstructor(String value) {
            this.value = value;
        }

        @EventHandler
        private void on(OrderCreated event) {}
    }

    // ================================= supports() Tests =================================

    @Test
    void supports_returns_true_for_class_with_EventHandler_methods() {
        assertThat(projector.supports(OrderSummaryProjection.class)).isTrue();
    }

    @Test
    void supports_returns_false_for_class_without_EventHandler_methods() {
        assertThat(projector.supports(ProjectionWithoutEventHandlers.class)).isFalse();
    }

    @Test
    void supports_returns_false_for_String_class() {
        assertThat(projector.supports(String.class)).isFalse();
    }

    @Test
    void supports_throws_when_projectionType_is_null() {
        assertThatThrownBy(() -> projector.supports(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectionType");
    }

    // ================================= projectEvents() Tests =================================

    @Test
    void projectEvents_returns_empty_when_no_events_exist() {
        // Given
        var eventStore = mock(EventStore.class);
        var aggregateId = "order-123";
        when(eventStore.fetchStream(ORDERS, aggregateId)).thenReturn(Optional.empty());

        // When
        var result = projector.projectEvents(ORDERS, aggregateId, OrderSummaryProjection.class, eventStore);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void projectEvents_applies_events_to_projection_in_order() {
        // Given
        var eventStore = mock(EventStore.class);
        var aggregateId = "order-123";

        var events = List.of(
                createPersistedEvent(aggregateId, 0, new OrderCreated("order-123", "customer-456")),
                createPersistedEvent(aggregateId, 1, new ProductAdded("product-1", 2)),
                createPersistedEvent(aggregateId, 2, new ProductAdded("product-2", 1)),
                createPersistedEvent(aggregateId, 3, new OrderAccepted())
        );
        var eventStream = createEventStream(aggregateId, events);
        when(eventStore.fetchStream(ORDERS, aggregateId)).thenReturn(Optional.of(eventStream));

        // When
        var result = projector.projectEvents(ORDERS, aggregateId, OrderSummaryProjection.class, eventStore);

        // Then
        assertThat(result).isPresent();
        var projection = result.get();
        assertThat(projection.getOrderId()).isEqualTo("order-123");
        assertThat(projection.getCustomerId()).isEqualTo("customer-456");
        assertThat(projection.getProducts()).containsExactly("product-1", "product-2");
        assertThat(projection.isAccepted()).isTrue();
    }

    @Test
    void projectEvents_ignores_events_without_matching_handlers() {
        // Given
        var eventStore = mock(EventStore.class);
        var aggregateId = "order-123";

        var events = List.of(
                createPersistedEvent(aggregateId, 0, new OrderCreated("order-123", "customer-456")),
                createPersistedEvent(aggregateId, 1, new UnhandledEvent("some-data")),
                createPersistedEvent(aggregateId, 2, new OrderAccepted())
        );
        var eventStream = createEventStream(aggregateId, events);
        when(eventStore.fetchStream(ORDERS, aggregateId)).thenReturn(Optional.of(eventStream));

        // When
        var result = projector.projectEvents(ORDERS, aggregateId, OrderSummaryProjection.class, eventStore);

        // Then
        assertThat(result).isPresent();
        var projection = result.get();
        assertThat(projection.getOrderId()).isEqualTo("order-123");
        assertThat(projection.isAccepted()).isTrue();
    }

    @Test
    void projectEvents_throws_when_projection_type_not_supported() {
        // Given
        var eventStore = mock(EventStore.class);
        var aggregateId = "order-123";

        // When/Then
        assertThatThrownBy(() -> projector.projectEvents(ORDERS, aggregateId, ProjectionWithoutEventHandlers.class, eventStore))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isn't supported")
                .hasMessageContaining("@EventHandler");
    }

    @Test
    void projectEvents_throws_when_projection_has_no_noarg_constructor() {
        // Given
        var eventStore = mock(EventStore.class);
        var aggregateId = "order-123";

        var events = List.of(
                createPersistedEvent(aggregateId, 0, new OrderCreated("order-123", "customer-456"))
        );
        var eventStream = createEventStream(aggregateId, events);
        when(eventStore.fetchStream(ORDERS, aggregateId)).thenReturn(Optional.of(eventStream));

        // When/Then
        assertThatThrownBy(() -> projector.projectEvents(ORDERS, aggregateId, ProjectionWithoutNoArgConstructor.class, eventStore))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-argument constructor");
    }

    @Test
    void projectEvents_throws_when_aggregateType_is_null() {
        var eventStore = mock(EventStore.class);
        assertThatThrownBy(() -> projector.projectEvents(null, "id", OrderSummaryProjection.class, eventStore))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateType");
    }

    @Test
    void projectEvents_throws_when_aggregateId_is_null() {
        var eventStore = mock(EventStore.class);
        assertThatThrownBy(() -> projector.projectEvents(ORDERS, null, OrderSummaryProjection.class, eventStore))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    void projectEvents_throws_when_projectionType_is_null() {
        var eventStore = mock(EventStore.class);
        assertThatThrownBy(() -> projector.projectEvents(ORDERS, "id", null, eventStore))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectionType");
    }

    @Test
    void projectEvents_throws_when_eventStore_is_null() {
        assertThatThrownBy(() -> projector.projectEvents(ORDERS, "id", OrderSummaryProjection.class, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventStore");
    }

    // ================================= Helper Methods =================================

    private PersistedEvent createPersistedEvent(String aggregateId, long eventOrder, Object event) {
        var eventJSON = mock(EventJSON.class);
        when(eventJSON.deserialize()).thenReturn(event);

        return PersistedEvent.from(
                EventId.random(),
                ORDERS,
                aggregateId,
                eventJSON,
                EventOrder.of(eventOrder),
                EventRevision.of(1),
                GlobalEventOrder.of(eventOrder + 1),
                mock(EventMetaDataJSON.class),
                OffsetDateTime.now(),
                Optional.empty(),
                Optional.of(CorrelationId.random()),
                Optional.empty()
        );
    }

    @SuppressWarnings("unchecked")
    private AggregateEventStream<String> createEventStream(String aggregateId, List<PersistedEvent> events) {
        // Create a mock AggregateEventStream that returns the events
        var stream = mock(AggregateEventStream.class);
        when(stream.eventList()).thenReturn(events);
        when(stream.aggregateId()).thenReturn(aggregateId);
        when(stream.aggregateType()).thenReturn(ORDERS);
        return stream;
    }
}
