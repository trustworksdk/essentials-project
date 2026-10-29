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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Which {@link MessageConsumptionMode} an {@link EventProcessor} consumes its {@link Inbox} with, and what it says about it
 * at start: {@link MessageConsumptionMode#SingleGlobalConsumer} unless the opt-in
 * {@link EventProcessorDependencies#competingInboxConsumers} is set AND the {@link Inboxes} keep key order across instances;
 * a processor's own override decides for itself, with a WARN where that is unsafe.
 * <p>
 * Mocks throughout, so no database is involved. The shard-owned engine's side - that two instances really keep a key in
 * order - is pinned by the engine's own ordering ITs.
 */
class EventProcessorCompetingInboxConsumersTest {
    private static final AggregateType THINGS = AggregateType.of("Things");

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        // The processors log under their runtime class, so the anonymous overrides below too: every one is nested in this class
        ((Logger) LoggerFactory.getLogger(EventProcessorCompetingInboxConsumersTest.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(EventProcessorCompetingInboxConsumersTest.class)).detachAppender(logAppender);
    }

    @Test
    void without_the_opt_in_the_inbox_has_a_single_global_consumer_even_where_key_order_is_kept() {
        var inboxes = inboxes(true);

        new ThingsEventProcessor(dependencies(inboxes, false)).start();

        assertThat(consumptionModeOf(inboxes)).isEqualTo(MessageConsumptionMode.SingleGlobalConsumer);
        assertThat(logged(Level.WARN)).isEmpty();
        assertThat(logged(Level.INFO)).noneMatch(message -> message.contains("GlobalCompetingConsumers"));
    }

    @Test
    void with_the_opt_in_and_inboxes_that_keep_key_order_every_instance_consumes_the_inbox() {
        var inboxes = inboxes(true);

        new ThingsEventProcessor(dependencies(inboxes, true)).start();

        assertThat(consumptionModeOf(inboxes)).isEqualTo(MessageConsumptionMode.GlobalCompetingConsumers);
        assertThat(logged(Level.INFO)).anyMatch(message -> message.contains("Inbox consumed with GlobalCompetingConsumers"));
        assertThat(logged(Level.WARN)).isEmpty();
    }

    @Test
    void with_the_opt_in_and_inboxes_that_do_not_keep_key_order_it_stays_on_a_single_global_consumer_and_says_why() {
        var inboxes = inboxes(false);

        new ThingsEventProcessor(dependencies(inboxes, true)).start();

        assertThat(consumptionModeOf(inboxes)).isEqualTo(MessageConsumptionMode.SingleGlobalConsumer);
        assertThat(logged(Level.WARN)).singleElement()
                                      .satisfies(message -> assertThat(message).contains("Competing inbox consumers were enabled")
                                                                               .contains("staying on SingleGlobalConsumer"));
    }

    @Test
    void an_override_choosing_competing_consumers_where_key_order_is_not_kept_is_honoured_with_a_warning() {
        var inboxes = inboxes(false);

        new ThingsEventProcessor(dependencies(inboxes, false)) {
            @Override
            protected MessageConsumptionMode getInboxMessageConsumptionMode() {
                return MessageConsumptionMode.GlobalCompetingConsumers;
            }
        }.start();

        assertThat(consumptionModeOf(inboxes)).isEqualTo(MessageConsumptionMode.GlobalCompetingConsumers);
        assertThat(logged(Level.WARN)).singleElement()
                                      .satisfies(message -> assertThat(message).contains("can be handled out of order"));
    }

    @Test
    void an_override_choosing_a_single_global_consumer_wins_over_the_opt_in() {
        var inboxes = inboxes(true);

        new ThingsEventProcessor(dependencies(inboxes, true)) {
            @Override
            protected MessageConsumptionMode getInboxMessageConsumptionMode() {
                return MessageConsumptionMode.SingleGlobalConsumer;
            }
        }.start();

        assertThat(consumptionModeOf(inboxes)).isEqualTo(MessageConsumptionMode.SingleGlobalConsumer);
        assertThat(logged(Level.WARN)).isEmpty();
    }

    @Test
    void the_dependencies_public_constructor_leaves_the_opt_in_off() {
        var dependencies = new EventProcessorDependencies(subscriptionManager(), inboxes(true), mock(DurableLocalCommandBus.class), List.of());

        assertThat(dependencies.competingInboxConsumers).isFalse();
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

    private static EventProcessorDependencies dependencies(Inboxes inboxes, boolean competingInboxConsumers) {
        return EventProcessorDependencies.builder()
                                         .setEventStoreSubscriptionManager(subscriptionManager())
                                         .setInboxes(inboxes)
                                         .setCommandBus(mock(DurableLocalCommandBus.class))
                                         .setCompetingInboxConsumers(competingInboxConsumers)
                                         .build();
    }

    private static EventStoreSubscriptionManager subscriptionManager() {
        // An AwaitedGapInspector, so start() logs no unrelated WARN
        var eventStore = mock(EventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        doReturn(mock(EventStoreUnitOfWorkFactory.class)).when(eventStore).getUnitOfWorkFactory();
        var subscriptionManager = mock(EventStoreSubscriptionManager.class,
                                       invocation -> invocation.getMethod().getName().equals("exclusivelySubscribeToAggregateEventsAsynchronously") ?
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

    static class ThingsEventProcessor extends EventProcessor {
        ThingsEventProcessor(EventProcessorDependencies dependencies) {
            super(dependencies);
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
}
