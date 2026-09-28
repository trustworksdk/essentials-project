/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.AppendToStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Every {@link EventStore#appendToStream} convenience overload must hand the events it was given - and nothing else -
 * to {@link EventStore#appendToStream(AppendToStream)}.
 * <p>
 * The overloads all end in {@code new AppendToStream<>(...)}, and {@link AppendToStream} has an
 * {@code (AggregateType, ID, Object...)} constructor. An argument list that stops matching a specific constructor still
 * compiles against that one, with the remaining arguments - an {@code Optional}, an array - appended as "events". The
 * {@code (Optional<Long>, Object...)} overload did exactly that for as long as it existed, and the others started doing
 * it once 0.60 removed the {@code Optional} constructor they had bound to.
 */
class EventStoreAppendToStreamDefaultsTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");
    private static final String        ID     = "order-1";

    @Test
    void list_without_an_event_order() {
        assertAppends(store -> store.appendToStream(ORDERS, ID, List.of("OrderAdded", "OrderAccepted")), null);
    }

    @Test
    void varargs_without_an_event_order() {
        assertAppends(store -> store.appendToStream(ORDERS, ID, "OrderAdded", "OrderAccepted"), null);
    }

    @Test
    void list_after_an_EventOrder() {
        assertAppends(store -> store.appendToStream(ORDERS, ID, EventOrder.of(3), List.of("OrderAdded", "OrderAccepted")), 3L);
    }

    @Test
    void varargs_after_an_EventOrder() {
        assertAppends(store -> store.appendToStream(ORDERS, ID, EventOrder.of(3), "OrderAdded", "OrderAccepted"), 3L);
    }

    @Test
    void list_after_a_Long() {
        assertAppends(store -> store.appendToStream(ORDERS, ID, 3L, List.of("OrderAdded", "OrderAccepted")), 3L);
    }

    @Test
    void list_after_an_Optional() {
        assertAppends(store -> store.appendToStream(ORDERS, ID, Optional.of(3L), List.of("OrderAdded", "OrderAccepted")), 3L);
    }

    @Test
    void varargs_after_an_Optional() {
        assertAppends(store -> store.appendToStream(ORDERS, ID, Optional.of(3L), "OrderAdded", "OrderAccepted"), 3L);
    }

    @Test
    void varargs_after_an_empty_Optional() {
        assertAppends(store -> store.appendToStream(ORDERS, ID, Optional.empty(), "OrderAdded", "OrderAccepted"), null);
    }

    @Test
    void the_builder_with_and_without_an_event_order() {
        var withOrder = AppendToStream.<String>builder()
                                      .setAggregateType(ORDERS)
                                      .setAggregateId(ID)
                                      .setAppendEventsAfterEventOrder(3L)
                                      .setEventsToAppend(List.of("OrderAdded", "OrderAccepted"))
                                      .build();
        assertThat(List.<Object>copyOf(withOrder.getEventsToAppend())).containsExactly("OrderAdded", "OrderAccepted");
        assertThat(withOrder.getAppendEventsAfterEventOrder()).contains(3L);

        var withoutOrder = AppendToStream.<String>builder()
                                         .setAggregateType(ORDERS)
                                         .setAggregateId(ID)
                                         .setEventsToAppend(List.of("OrderAdded"))
                                         .build();
        assertThat(List.<Object>copyOf(withoutOrder.getEventsToAppend())).containsExactly("OrderAdded");
        assertThat(withoutOrder.getAppendEventsAfterEventOrder()).isEmpty();
    }

    /**
     * A call written for the removed {@code (AggregateType, ID, Optional<Long>, List<?>)} constructor still compiles against
     * the varargs one; it must fail instead of persisting the {@code Optional} and the list as events.
     */
    @Test
    void the_varargs_constructor_rejects_what_cannot_be_an_event() {
        assertThatThrownBy(() -> new AppendToStream<>(ORDERS, ID, Optional.of(3L), List.of("OrderAdded")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Optional")
                .hasMessageContaining("AppendToStream(AggregateType, ID, Long, List)");
        // a single List binds to the (AggregateType, ID, List) constructor, so this one is fine
        assertThatNoException().isThrownBy(() -> new AppendToStream<>(ORDERS, ID, List.of("OrderAdded")));
        assertThatThrownBy(() -> new AppendToStream<>(ORDERS, ID, "OrderAdded", List.of("OrderAccepted")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collection");
        assertThatThrownBy(() -> new AppendToStream<>(ORDERS, ID, "OrderAdded", new Object[]{"OrderAccepted"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("array");
    }

    @SuppressWarnings("unchecked")
    private static void assertAppends(Consumer<EventStore> call, Long expectedAppendAfter) {
        var store = mock(EventStore.class, CALLS_REAL_METHODS);
        doReturn(null).when(store).appendToStream(any(AppendToStream.class));

        call.accept(store);

        ArgumentCaptor<AppendToStream<String>> captor = ArgumentCaptor.forClass((Class<AppendToStream<String>>) (Class<?>) AppendToStream.class);
        verify(store).appendToStream((AppendToStream<String>) captor.capture());
        var operation = captor.getValue();
        assertThat((Object) operation.getAggregateType()).isEqualTo(ORDERS);
        assertThat(operation.getAggregateId()).isEqualTo(ID);
        assertThat(List.<Object>copyOf(operation.getEventsToAppend())).containsExactly("OrderAdded", "OrderAccepted");
        assertThat(operation.getAppendEventsAfterEventOrder()).isEqualTo(Optional.ofNullable(expectedAppendAfter));
    }
}
