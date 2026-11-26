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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link EventLogInboxProcessor}'s hooks, metadata and inbox consumer without a database; forwarding itself is
 * {@link EventLogInboxProcessorIT}'s.
 */
class EventLogInboxProcessorTest {
    private final EventLog                                eventLog          = EventLog.builder().setDataSource(new PGSimpleDataSource()).build();
    private final EventLogProcessorDependencies           dependencies      = new EventLogProcessorDependencies(eventLog, EssentialsJSONEventSerializers.create(),
                                                                                                                 mock(PageTransactionSource.class));
    private final Inboxes                                 inboxes           = mock(Inboxes.class);
    @SuppressWarnings("unchecked")
    private final UnitOfWorkFactory<UnitOfWork>           unitOfWorkFactory = mock(UnitOfWorkFactory.class);

    class Handlers extends EventLogInboxProcessor {
        final List<String> seen = new CopyOnWriteArrayList<>();
        int                pageSize;

        Handlers() {
            super("handlers", List.of("Orders"), dependencies, inboxes, unitOfWorkFactory);
        }

        @Override
        protected int forwardPageSize() {
            return pageSize > 0 ? pageSize : super.forwardPageSize();
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(Added added) {
            seen.add(added.orderId() + " caused by " + CausationContext.current().map(Object::toString).orElse("nothing"));
        }
    }

    @Test
    void the_constructor_rejects_missing_arguments() {
        assertThatThrownBy(() -> new EventLogInboxProcessor("", List.of("Orders"), dependencies, inboxes, unitOfWorkFactory) {
        }).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new EventLogInboxProcessor("p", List.of(), dependencies, inboxes, unitOfWorkFactory) {
        }).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new EventLogInboxProcessor("p", List.of("Orders"), dependencies, null, unitOfWorkFactory) {
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void the_consumption_mode_follows_whether_the_queues_keep_key_order_across_instances() {
        when(inboxes.keepsKeyOrderAcrossInstances()).thenReturn(false);
        assertThat(new Handlers().inboxMessageConsumptionMode()).isEqualTo(MessageConsumptionMode.SingleGlobalConsumer);
        when(inboxes.keepsKeyOrderAcrossInstances()).thenReturn(true);
        assertThat(new Handlers().inboxMessageConsumptionMode()).isEqualTo(MessageConsumptionMode.GlobalCompetingConsumers);
    }

    @Test
    void the_defaults_are_one_consumer_a_redelivery_policy_and_the_engines_page_size() {
        when(inboxes.maxUnservedAfterInstanceLoss()).thenReturn(Optional.empty());
        var processor = new Handlers();
        assertThat(processor.numberOfParallelInboxMessageConsumers()).isEqualTo(1);
        assertThat(processor.inboxRedeliveryPolicy()).isNotNull();
        assertThat(processor.forwardPageSize()).isEqualTo(Math.min(eventLog.settings().pageSize(), ErrorSettings.MAX_PAGE_SIZE));
        assertThat(processor.inbox()).isEmpty();
        assertThat(processor.projections()).isEmpty();
        assertThat(processor.isStarted()).isFalse();
    }

    @Test
    void an_out_of_range_page_size_fails_the_start_and_stops_the_inbox_consumer() {
        var inbox = mock(Inbox.class);
        when(inbox.hasAMessageConsumer()).thenReturn(true);
        when(inboxes.getOrCreateInbox(any(InboxConfig.class), any(java.util.function.Consumer.class))).thenReturn(inbox);
        when(inboxes.maxUnservedAfterInstanceLoss()).thenReturn(Optional.empty());
        var processor = new Handlers();
        processor.pageSize = ErrorSettings.MAX_PAGE_SIZE + 1;
        assertThatThrownBy(processor::start).isInstanceOf(IllegalArgumentException.class);
        assertThat(processor.isStarted()).isFalse();
        verify(inbox).stopConsuming();
    }

    @Test
    void the_message_metadata_is_the_events_plus_its_event_id() {
        var event    = deliveredEvent(new Added("o1", 1), EventMetaData.of("traceId", "t-1"));
        var metaData = EventLogInboxProcessor.messageMetaData(event);
        assertThat(metaData).containsEntry("traceId", "t-1").containsEntry(EventLogInboxProcessor.EVENT_ID_METADATA_KEY, event.eventId().toString());
    }

    @Test
    void the_inbox_consumer_binds_the_forwarded_event_as_the_cause() {
        var processor = new Handlers();
        var eventId   = EventId.random();
        var consumer  = processor.inboxConsumer();
        consumer.accept(new OrderedMessage(new Added("o1", 1), "o1", 0, new MessageMetaData(Map.of(EventLogInboxProcessor.EVENT_ID_METADATA_KEY, eventId.toString()))));
        consumer.accept(new OrderedMessage(new Added("o2", 2), "o2", 0, new MessageMetaData(Map.of())));
        consumer.accept(new OrderedMessage("no handler takes a String", "o3", 0, new MessageMetaData(Map.of())));
        assertThat(processor.seen).containsExactly("o1 caused by " + eventId, "o2 caused by nothing");
        assertThat(CausationContext.current()).isEmpty();
        assertThat(consumer).isInstanceOf(UnitOfWorkBoundaryOwningMessageConsumer.class);
        assertThat(((UnitOfWorkBoundaryOwningMessageConsumer) consumer).hasNonTransactionalMessageHandlers()).isTrue();
    }

    private static DeliveredEvent deliveredEvent(Object payload, EventMetaData metaData) {
        var eventId = EventId.random();
        var raw = new StoredEvent("Orders", 1, 0, UUID.fromString(eventId.toString()), null, null, "FQCN:" + payload.getClass().getName(), 1,
                                  OffsetDateTime.now(), "o1", "{}", "{}", null);
        return new DeliveredEvent(AggregateType.of("Orders"), "o1", EventOrder.of(0), GlobalEventOrder.of(1), eventId, Optional.empty(), Optional.empty(),
                                  EventTypeOrName.with(payload.getClass()), payload, metaData, OffsetDateTime.now(), Optional.empty(), raw);
    }
}
