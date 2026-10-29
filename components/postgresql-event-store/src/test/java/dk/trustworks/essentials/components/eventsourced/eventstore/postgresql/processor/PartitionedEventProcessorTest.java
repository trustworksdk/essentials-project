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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link PartitionedEventProcessor}: every instance consumes the {@link Inbox}, forwarding is batched, and it refuses to
 * start on {@link Inboxes} that do not keep key order across instances. An {@link EventProcessor} overriding
 * {@link EventProcessor#getInboxMessageConsumptionMode()} to {@link MessageConsumptionMode#GlobalCompetingConsumers} is
 * held to the same rule; its default stays {@link MessageConsumptionMode#SingleGlobalConsumer}.
 * <p>
 * Mocks throughout, so no database is involved. The shard-owned engine's side - that two instances really keep a key in
 * order - is pinned by the engine's own ordering ITs and measured in the trading demo.
 */
class PartitionedEventProcessorTest {
    private static final AggregateType THINGS = AggregateType.of("Things");

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        // The processors log under their runtime class, so the anonymous overrides below too: every one is nested in this class
        ((Logger) LoggerFactory.getLogger(PartitionedEventProcessorTest.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(PartitionedEventProcessorTest.class)).detachAppender(logAppender);
    }

    @Test
    void a_partitioned_processor_lets_every_instance_consume_its_inbox_where_key_order_is_kept() {
        var inboxes   = inboxes(true);
        var processor = new ThingsPartitionedProcessor(inboxes);

        processor.start();

        assertThat(processor.isStarted()).isTrue();
        assertThat(consumptionModeOf(inboxes)).isEqualTo(MessageConsumptionMode.GlobalCompetingConsumers);
        assertThat(logged(Level.INFO)).anyMatch(message -> message.contains("Inbox consumed with GlobalCompetingConsumers"));
    }

    @Test
    void a_partitioned_processor_forwards_in_batches_by_default() {
        assertThat(new ThingsPartitionedProcessor(inboxes(true)).getForwardBatchSize()).isEqualTo(PartitionedEventProcessor.DEFAULT_FORWARD_BATCH_SIZE);
    }

    @Test
    void a_partitioned_processor_refuses_to_start_where_key_order_is_not_kept_across_instances() {
        var inboxes   = inboxes(false);
        var processor = new ThingsPartitionedProcessor(inboxes);

        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class)
                                            .hasMessageContaining("does not keep an ordered message key in order across instances")
                                            .hasMessageContaining("essentials.shard-owned-queue.durable-queues-enabled");
        assertThat(processor.isStarted()).isFalse();
        verify(inboxes, never()).getOrCreateInbox(any(InboxConfig.class), any(Consumer.class));
    }

    @Test
    void an_event_processor_has_a_single_global_consumer_by_default_even_where_key_order_is_kept() {
        var inboxes = inboxes(true);

        new ThingsEventProcessor(inboxes).start();

        assertThat(consumptionModeOf(inboxes)).isEqualTo(MessageConsumptionMode.SingleGlobalConsumer);
        assertThat(logged(Level.INFO)).noneMatch(message -> message.contains("GlobalCompetingConsumers"));
    }

    @Test
    void an_event_processor_overriding_to_competing_consumers_where_key_order_is_not_kept_refuses_to_start() {
        var inboxes   = inboxes(false);
        var processor = new ThingsEventProcessor(inboxes) {
            @Override
            protected MessageConsumptionMode getInboxMessageConsumptionMode() {
                return MessageConsumptionMode.GlobalCompetingConsumers;
            }
        };

        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class)
                                            .hasMessageContaining("could be handled out of order");
        assertThat(processor.isStarted()).isFalse();
    }

    @Test
    void an_event_processor_overriding_to_competing_consumers_where_key_order_is_kept_starts() {
        var inboxes   = inboxes(true);
        var processor = new ThingsEventProcessor(inboxes) {
            @Override
            protected MessageConsumptionMode getInboxMessageConsumptionMode() {
                return MessageConsumptionMode.GlobalCompetingConsumers;
            }
        };

        processor.start();

        assertThat(processor.isStarted()).isTrue();
        assertThat(consumptionModeOf(inboxes)).isEqualTo(MessageConsumptionMode.GlobalCompetingConsumers);
    }

    // -------------------------------------------------------------------------------------------------------------------

    private List<String> logged(Level level) {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == level)
                               .map(ILoggingEvent::getFormattedMessage)
                               .toList();
    }

    @SuppressWarnings("unchecked")
    private static MessageConsumptionMode consumptionModeOf(Inboxes inboxes) {
        var config = ArgumentCaptor.forClass(InboxConfig.class);
        verify(inboxes).getOrCreateInbox(config.capture(), any(Consumer.class));
        return config.getValue().getMessageConsumptionMode();
    }

    private static EventStoreSubscriptionManager subscriptionManager() {
        // An AwaitedGapInspector, so start() logs no unrelated WARN
        var eventStore = mock(EventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        doReturn(mock(EventStoreUnitOfWorkFactory.class)).when(eventStore).getUnitOfWorkFactory();
        var subscriptionManager = mock(EventStoreSubscriptionManager.class,
                                       // Both subscribe paths: the partitioned processor forwards in batches
                                       invocation -> invocation.getMethod().getName().startsWith("exclusively") ?
                                                     mock(EventStoreSubscription.class) : RETURNS_DEFAULTS.answer(invocation));
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        return subscriptionManager;
    }

    @SuppressWarnings("unchecked")
    private static Inboxes inboxes(boolean keepsKeyOrderAcrossInstances) {
        var inbox = mock(Inbox.class);
        when(inbox.name()).thenReturn(InboxName.of("ThingsEventProcessor"));
        var inboxes = mock(Inboxes.class);
        when(inboxes.getOrCreateInbox(any(InboxConfig.class), any(Consumer.class))).thenReturn(inbox);
        when(inboxes.keepsKeyOrderAcrossInstances()).thenReturn(keepsKeyOrderAcrossInstances);
        return inboxes;
    }

    private static EventProcessorDependencies dependencies(Inboxes inboxes) {
        return EventProcessorDependencies.builder()
                                         .setEventStoreSubscriptionManager(subscriptionManager())
                                         .setInboxes(inboxes)
                                         .setCommandBus(mock(DurableLocalCommandBus.class))
                                         .build();
    }

    static class ThingsEventProcessor extends EventProcessor {
        ThingsEventProcessor(Inboxes inboxes) {
            super(dependencies(inboxes));
        }

        @Override
        public String getProcessorName() {
            return "ThingsEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS);
        }
    }

    static class ThingsPartitionedProcessor extends PartitionedEventProcessor {
        ThingsPartitionedProcessor(Inboxes inboxes) {
            super(dependencies(inboxes));
        }

        @Override
        public String getProcessorName() {
            return "ThingsPartitionedProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS);
        }
    }
}
