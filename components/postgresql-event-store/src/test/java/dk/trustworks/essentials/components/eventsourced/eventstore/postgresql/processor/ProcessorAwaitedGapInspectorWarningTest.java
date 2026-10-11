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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * An {@link EventProcessor} whose event store is no {@link AwaitedGapInspector} WARNs once, when it starts, that
 * {@link AbstractEventProcessor#awaitProcessed} cannot ask whether an event is still awaited as a gap - worded so that an
 * application that never calls {@code awaitProcessed} can see it does not apply. One whose event store is an
 * {@link AwaitedGapInspector} does not WARN.
 * <p>
 * Mocks throughout, so no database is involved. The {@link ViewEventProcessor} start is pinned by {@code ViewEventProcessorIT}.
 */
class ProcessorAwaitedGapInspectorWarningTest {
    private static final AggregateType THINGS = AggregateType.of("Things");

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        processorLogger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        processorLogger().detachAppender(logAppender);
    }

    @Test
    void an_EventProcessor_whose_event_store_is_no_AwaitedGapInspector_WARNs_once_at_start() {
        var processor = new ThingsEventProcessor(subscriptionManager(mock(EventStore.class)));

        processor.start();
        processor.start();

        assertThat(awaitedGapInspectorWarnings()).singleElement()
                                                 .satisfies(warning -> assertThat(warning.getFormattedMessage()).contains("If you call awaitProcessed on this processor")
                                                                                                                .contains("ignore this"));
    }

    @Test
    void an_EventProcessor_whose_event_store_is_an_AwaitedGapInspector_does_not_WARN() {
        var processor = new ThingsEventProcessor(subscriptionManager(mock(EventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class))));

        processor.start();

        assertThat(awaitedGapInspectorWarnings()).isEmpty();
    }

    private List<ILoggingEvent> awaitedGapInspectorWarnings() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.WARN)
                               .filter(event -> event.getFormattedMessage().contains(AwaitedGapInspector.class.getSimpleName()))
                               .toList();
    }

    private static Logger processorLogger() {
        return (Logger) LoggerFactory.getLogger(ThingsEventProcessor.class);
    }

    private static EventStoreSubscriptionManager subscriptionManager(EventStore eventStore) {
        doReturn(mock(EventStoreUnitOfWorkFactory.class)).when(eventStore).getUnitOfWorkFactory();
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        return subscriptionManager;
    }

    @SuppressWarnings("unchecked")
    private static Inboxes inboxes() {
        var inbox = mock(Inbox.class);
        when(inbox.name()).thenReturn(InboxName.of("ThingsEventProcessor"));
        var inboxes = mock(Inboxes.class);
        when(inboxes.getOrCreateInbox(any(InboxConfig.class), any(Consumer.class))).thenReturn(inbox);
        return inboxes;
    }

    // -------------------------------------------------------------------------------------------------------------------

    static class ThingsEventProcessor extends EventProcessor {
        ThingsEventProcessor(EventStoreSubscriptionManager subscriptionManager) {
            super(subscriptionManager, inboxes(), mock(DurableLocalCommandBus.class));
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
