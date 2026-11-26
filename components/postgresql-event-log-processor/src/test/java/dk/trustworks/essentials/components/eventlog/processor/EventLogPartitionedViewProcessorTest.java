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
package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.Added;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.PartitionedViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link EventLogPartitionedViewProcessor} without a database: the queue messages are the old
 * {@link PartitionedViewEventProcessor}'s (key, metadata keys), the consumer hands the handlers the plain aggregate id
 * with the event bound as the cause, an old reference message is refused, and start refuses what the old class refuses.
 */
class EventLogPartitionedViewProcessorTest {
    private final EventLog                      eventLog          = EventLog.builder().setDataSource(new PGSimpleDataSource()).build();
    private final EventLogProcessorDependencies dependencies      = new EventLogProcessorDependencies(eventLog, EssentialsJSONEventSerializers.create(),
                                                                                                       mock(PageTransactionSource.class));
    private final DurableQueues                 durableQueues     = mock(DurableQueues.class);
    @SuppressWarnings("unchecked")
    private final UnitOfWorkFactory<UnitOfWork> unitOfWorkFactory = mock(UnitOfWorkFactory.class);

    class View extends EventLogPartitionedViewProcessor {
        final List<String> seen = new CopyOnWriteArrayList<>();

        View() {
            super("view", List.of("Orders"), dependencies, durableQueues, unitOfWorkFactory);
        }

        @MessageHandler
        void on(Added added, OrderedMessage message) {
            seen.add(message.getKey() + "/" + message.getOrder() + " caused by " + CausationContext.current().map(Object::toString).orElse("nothing"));
        }
    }

    class NonTransactional extends EventLogPartitionedViewProcessor {
        NonTransactional() {
            super("view", List.of("Orders"), dependencies, durableQueues, unitOfWorkFactory);
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(Added added) {
        }
    }

    @Test
    void the_constructor_rejects_missing_arguments() {
        assertThatThrownBy(() -> new EventLogPartitionedViewProcessor("", List.of("Orders"), dependencies, durableQueues, unitOfWorkFactory) {
        }).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new EventLogPartitionedViewProcessor("p", List.of(), dependencies, durableQueues, unitOfWorkFactory) {
        }).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new EventLogPartitionedViewProcessor("p", List.of("Orders"), dependencies, null, unitOfWorkFactory) {
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void the_queue_key_and_metadata_keys_are_the_old_processors() {
        assertThat(EventLogPartitionedViewProcessor.qualifiedKey("Orders", "o1")).isEqualTo(PartitionedViewEventProcessor.qualifiedKey(AggregateType.of("Orders"), "o1"));
        assertThat(EventLogPartitionedViewProcessor.KEY_SEPARATOR).isEqualTo(PartitionedViewEventProcessor.KEY_SEPARATOR);
        assertThat(EventLogPartitionedViewProcessor.AGGREGATE_TYPE_METADATA_KEY).isEqualTo(PartitionedViewEventProcessor.AGGREGATE_TYPE_METADATA_KEY);
        assertThat(EventLogPartitionedViewProcessor.EVENT_ID_METADATA_KEY).isEqualTo(PartitionedViewEventProcessor.EVENT_ID_METADATA_KEY);
        assertThat(new View().queueName().toString()).isEqualTo("view:queue");
    }

    @Test
    void an_event_is_queued_with_its_payload_qualified_key_order_and_metadata() {
        var metaData = new EventMetaData();
        metaData.put("tenant", "t1");
        var event   = deliveredEvent(new Added("o1", 7), metaData);
        var message = EventLogPartitionedViewProcessor.toMessage(event);
        assertThat(message).isInstanceOf(IdentifiedByKeyAndOrder.class);
        assertThat(message.getPayload()).isEqualTo(new Added("o1", 7));
        assertThat(message.getKey()).isEqualTo("Orders|o1");
        assertThat(message.getOrder()).isEqualTo(3);
        assertThat(message.getMetaData()).containsEntry("tenant", "t1")
                                         .containsEntry(EventLogPartitionedViewProcessor.AGGREGATE_TYPE_METADATA_KEY, "Orders")
                                         .containsEntry(EventLogPartitionedViewProcessor.EVENT_ID_METADATA_KEY, event.eventId().toString());
    }

    @Test
    void the_consumer_hands_the_plain_aggregate_id_with_the_event_as_cause() {
        var view    = new View();
        var event   = deliveredEvent(new Added("o1", 7), new EventMetaData());
        view.handle(EventLogPartitionedViewProcessor.toMessage(event));
        assertThat(view.seen).containsExactly("o1/3 caused by " + event.eventId());
    }

    @Test
    void a_reference_message_queued_by_the_old_processor_is_refused() {
        var view = new View();
        assertThatThrownBy(() -> view.handle(OrderedMessage.of(AggregateType.of("Orders"), "Orders|o1", 3, new MessageMetaData())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("drain");
    }

    @Test
    void start_refuses_queues_that_keep_key_order_per_instance_only_and_non_transactional_handlers() {
        when(durableQueues.keepsKeyOrderAcrossInstances()).thenReturn(false);
        var view = new View();
        assertThatThrownBy(view::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("across instances");
        assertThat(view.isStarted()).isFalse();

        when(durableQueues.keepsKeyOrderAcrossInstances()).thenReturn(true);
        var nonTransactional = new NonTransactional();
        assertThatThrownBy(nonTransactional::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("UnitOfWorkMode.NONE");
        verify(durableQueues, never()).consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any());
    }

    private static DeliveredEvent deliveredEvent(Object payload, EventMetaData metaData) {
        var eventId = EventId.random();
        var raw = new StoredEvent("Orders", 1, 3, UUID.fromString(eventId.toString()), null, null, "FQCN:" + payload.getClass().getName(), 1,
                                  OffsetDateTime.now(), "o1", "{}", "{}", null);
        return new DeliveredEvent(AggregateType.of("Orders"), "o1", EventOrder.of(3), GlobalEventOrder.of(1), eventId, Optional.empty(), Optional.empty(),
                                  EventTypeOrName.with(payload.getClass()), payload, metaData, OffsetDateTime.now(), Optional.empty(), raw);
    }
}
