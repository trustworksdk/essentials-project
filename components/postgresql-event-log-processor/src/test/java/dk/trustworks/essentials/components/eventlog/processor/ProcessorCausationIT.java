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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.CausationPersistableEventEnricher;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EventLogReactor} and {@link EventLogViewProcessor} bind the delivered event as the cause around each handler
 * call, as {@link EventLogInboxProcessor} and {@link EventLogPartitionedViewProcessor} do: an event a handler appends
 * through the adapter (with the {@link CausationPersistableEventEnricher} the starter wires) records the delivered
 * event's id as its {@code caused_by_event_id}.
 */
class ProcessorCausationIT extends AbstractProcessorIT {
    private static final String NOTES = "Notes";

    private EventLogEventStore                                 store;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> storeUnitOfWork;

    @BeforeEach
    void setUpStore() {
        storeUnitOfWork = new EventStoreManagedUnitOfWorkFactory(jdbi);
        PersistableEventMapper mapper = (aggregateId, configuration, event, eventOrder) ->
                PersistableEvent.builder()
                                .setEvent(event)
                                .setAggregateType(configuration.aggregateType)
                                .setAggregateId(aggregateId)
                                .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                                .setEventOrder(eventOrder)
                                .build();
        store = new EventLogEventStore(eventLog, storeUnitOfWork, serializer, mapper, new TenantSerializer.NoSupportForMultiTenancySerializer(),
                                       List.of(new CausationPersistableEventEnricher()), Optional.empty());
        store.addAggregateEventStreamConfiguration(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType.of(NOTES), CorrelationId.class);
    }

    /** Appends one note per delivered event to its own stream, named after the delivered event's id */
    private void noteFor(DeliveredEvent event) {
        storeUnitOfWork.usingUnitOfWork(uow -> store.appendToStream(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType.of(NOTES),
                                                                    CorrelationId.of(event.eventId().toString()),
                                                                    List.of(new Added(event.eventId().toString(), 1))));
    }

    class Notifier extends EventLogReactor {
        final Set<EventId> delivered = ConcurrentHashMap.newKeySet();

        Notifier() {
            super("causation-notifier", List.of(ORDERS), new EventLogProcessorDependencies(eventLog, serializer, PageTransactionSource.engine()));
        }

        @MessageHandler
        void on(Added added, DeliveredEvent event) {
            noteFor(event);
            delivered.add(event.eventId());
        }
    }

    class NotingView extends EventLogViewProcessor {
        final Set<EventId> delivered = ConcurrentHashMap.newKeySet();

        NotingView() {
            super("causation-view", List.of(ORDERS), new EventLogProcessorDependencies(eventLog, serializer, source(SourceKind.JDBI, Set.of())));
        }

        @MessageHandler
        void on(Added added, DeliveredEvent event) {
            noteFor(event);
            delivered.add(event.eventId());
        }
    }

    private void assertEachNoteIsCausedByItsDeliveredEvent(Set<EventId> delivered) throws Exception {
        for (var eventId : delivered) {
            var notes = eventLog.loadStream(NOTES, eventId.toString());
            assertThat(notes).hasSize(1);
            assertThat(notes.getFirst().causedByEventId()).isEqualTo(UUID.fromString(eventId.toString()));
        }
    }

    @Test
    void an_event_appended_from_a_reactor_handler_records_the_delivered_event_as_its_cause() throws Exception {
        appendOne(ORDERS, "o1", new Added("o1", 1));
        appendOne(ORDERS, "o2", new Added("o2", 2));
        var notifier = new Notifier();
        notifier.start();
        try {
            awaitTrue("both delivered", Duration.ofSeconds(20), () -> notifier.delivered.size() == 2);
            assertEachNoteIsCausedByItsDeliveredEvent(notifier.delivered);
        } finally {
            notifier.stop();
        }
    }

    @Test
    void an_event_appended_from_a_view_handler_records_the_delivered_event_as_its_cause() throws Exception {
        appendOne(ORDERS, "o1", new Added("o1", 1));
        appendOne(ORDERS, "o2", new Added("o2", 2));
        var view = new NotingView();
        view.start();
        try {
            awaitTrue("both delivered", Duration.ofSeconds(20), () -> view.delivered.size() == 2);
            assertEachNoteIsCausedByItsDeliveredEvent(view.delivered);
        } finally {
            view.stop();
        }
    }
}
