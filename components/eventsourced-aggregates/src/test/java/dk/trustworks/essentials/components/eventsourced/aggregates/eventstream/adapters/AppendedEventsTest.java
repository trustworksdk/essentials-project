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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfiguratorTest.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.reactive.command.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ES-4: {@link EventStreamDeciderCommandHandlerAdapter} logged the {@link GlobalEventOrder} its append returned and
 * discarded it, so a caller had no N to await a processor for. {@link AppendedEvents} hands it to the caller of
 * {@code commandBus.send(...)}
 */
class AppendedEventsTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    private CommandBus commandBus;
    private AtomicLong nextGlobalEventOrder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        nextGlobalEventOrder = new AtomicLong(41);
        ConfigurableEventStore<?> eventStore = mock(ConfigurableEventStore.class);
        when(eventStore.fetchStream(any(AggregateType.class), any())).thenReturn(Optional.empty());
        when(eventStore.appendToStream(any(AggregateType.class), any(), anyList())).thenAnswer(invocation -> {
            var persistedEvent = mock(PersistedEvent.class);
            when(persistedEvent.aggregateType()).thenReturn(ORDERS);
            when(persistedEvent.aggregateId()).thenReturn(invocation.getArgument(1));
            when(persistedEvent.globalEventOrder()).thenReturn(GlobalEventOrder.of(nextGlobalEventOrder.incrementAndGet()));
            var stream = mock(AggregateEventStream.class);
            when(stream.firstEvent()).thenReturn(persistedEvent);
            return stream;
        });
        commandBus = new LocalCommandBus();
        var configuration = new EventStreamAggregateTypeConfiguration(ORDERS,
                                                                      OrderId.class,
                                                                      AggregateIdSerializer.serializerFor(OrderId.class),
                                                                      new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
                                                                      command -> ((OrderCommand) command).orderId(),
                                                                      event -> ((OrderEvent) event).orderId());
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(configuration), List.of(new CreateOrderDecider()));
    }

    @Test
    void the_events_a_decider_appends_during_a_send_are_handed_to_the_caller() {
        var orderId = OrderId.of("o-1");

        var recorded = AppendedEvents.recordDuring(() -> commandBus.send(new CreateOrder(orderId, CustomerId.of("c-1"))));

        // send still returns what the adapter returned before - the event
        assertThat(recorded.result()).isInstanceOf(OrderCreated.class);
        assertThat(recorded.appendedEvents()).hasSize(1);
        assertThat(recorded.lastAppendedEvent()).hasValueSatisfying(event -> assertThat(event.globalEventOrder().longValue()).isEqualTo(42));
        assertThat(recorded.highestGlobalEventOrder(ORDERS)).hasValueSatisfying(order -> assertThat(order.longValue()).isEqualTo(42));
        assertThat(recorded.highestGlobalEventOrder(AggregateType.of("Other"))).isEmpty();
    }

    @Test
    void recordings_nest_and_nothing_is_recorded_outside_one() {
        commandBus.send(new CreateOrder(OrderId.of("o-0"), CustomerId.of("c-1")));

        var outer = AppendedEvents.recordDuring(() -> {
            commandBus.send(new CreateOrder(OrderId.of("o-1"), CustomerId.of("c-1")));
            var inner = AppendedEvents.recordDuring(() -> commandBus.send(new CreateOrder(OrderId.of("o-2"), CustomerId.of("c-1"))));
            assertThat(inner.appendedEvents()).extracting(PersistedEvent::aggregateId).containsExactly(OrderId.of("o-2"));
            return inner;
        });

        assertThat(outer.appendedEvents()).extracting(PersistedEvent::aggregateId).containsExactly(OrderId.of("o-1"), OrderId.of("o-2"));
    }
}
