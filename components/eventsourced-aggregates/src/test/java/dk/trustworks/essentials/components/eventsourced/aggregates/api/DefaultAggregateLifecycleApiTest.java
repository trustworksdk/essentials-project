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

package dk.trustworks.essentials.components.eventsourced.aggregates.api;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.shared.functional.CheckedFunction;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.IdentifierColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.JSONColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.types.LongRange;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultAggregateLifecycleApiTest {
    @Test
    void it_exposes_registered_policies_and_snapshots() {
        var snapshotRegistry = new InMemoryAggregateSnapshotPolicyRegistry();
        snapshotRegistry.register(new AggregateSnapshotPolicyDescriptor(TestAggregate.class,
                                                                       Optional.of("Orders"),
                                                                       TestAggregate.class.getAnnotation(AggregateSnapshotPolicy.class)));
        var closingBooksRegistry = new InMemoryAggregateClosingBooksPolicyRegistry();
        closingBooksRegistry.register(new AggregateClosingBooksPolicyDescriptor(TestAggregate.class,
                                                                               Optional.of("Orders"),
                                                                               TestAggregate.class.getAnnotation(dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicy.class)));

        var eventStore = mock(ConfigurableEventStore.class);
        var eventStreamConfiguration = new AggregateEventStreamConfiguration(AggregateType.of("Orders"),
                                                                            100,
                                                                            mock(JSONEventSerializer.class),
                                                                            new AggregateIdSerializer.StringIdSerializer(),
                                                                            IdentifierColumnType.TEXT,
                                                                            IdentifierColumnType.TEXT,
                                                                            IdentifierColumnType.TEXT,
                                                                            JSONColumnType.JSONB,
                                                                            JSONColumnType.JSONB,
                                                                            new TenantSerializer.NoSupportForMultiTenancySerializer());
        when(eventStore.getAggregateEventStreamConfiguration(AggregateType.of("Orders"))).thenReturn(eventStreamConfiguration);

        var snapshotStore = mock(AggregateSnapshotStore.class);
        when(snapshotStore.loadAllSnapshots(eq(AggregateType.of("Orders")), eq("order-1"), eq(TestAggregate.class), eq(true)))
                .thenReturn(Collections.singletonList(new AggregateSnapshot<>(AggregateType.of("Orders"),
                                                                             "order-1",
                                                                             TestAggregate.class,
                                                                             new TestAggregate(),
                                                                             new dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder(12L))));
        var persistedEvent = mock(PersistedEvent.class);
        when(persistedEvent.eventId()).thenReturn(EventId.random());
        when(persistedEvent.aggregateType()).thenReturn(AggregateType.of("Orders"));
        when(persistedEvent.aggregateId()).thenReturn("order-1#1");
        when(persistedEvent.eventOrder()).thenReturn(new EventOrder(0L));
        when(persistedEvent.globalEventOrder()).thenReturn(new GlobalEventOrder(1L));
        when(persistedEvent.eventRevision()).thenReturn(new dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventRevision(1));
        when(persistedEvent.event()).thenReturn(new dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventJSON(mock(JSONEventSerializer.class),
                                                                                                                                        dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventName.of("Created"),
                                                                                                                                        "{\"type\":\"Created\"}"));
        when(persistedEvent.metaData()).thenReturn(new dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventMetaDataJSON(mock(JSONEventSerializer.class),
                                                                                                                                              (String) null,
                                                                                                                                              "{\"source\":\"test\"}"));
        when(persistedEvent.timestamp()).thenReturn(java.time.OffsetDateTime.parse("2026-04-07T12:00:00Z"));
        when(persistedEvent.causedByEventId()).thenReturn(Optional.empty());
        when(persistedEvent.correlationId()).thenReturn(Optional.empty());
        when(persistedEvent.tenant()).thenReturn(Optional.empty());
        /* fetchGenerationEventStream reads the event store inside a UnitOfWork, since the stream is materialised while
           mapping it. Run the action inline so the mocked event store answers without a database. */
        var eventStoreUnitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        when(eventStoreUnitOfWorkFactory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(invocation -> {
            CheckedFunction<EventStoreUnitOfWork, ?> action = invocation.getArgument(0);
            return action.apply(mock(EventStoreUnitOfWork.class));
        });
        when(eventStore.getUnitOfWorkFactory()).thenReturn(eventStoreUnitOfWorkFactory);
        when(eventStore.fetchStream(eq(AggregateType.of("Orders")), eq("order-1#1"), any(LongRange.class)))
                .thenReturn(Optional.of(AggregateEventStream.of(eventStreamConfiguration,
                                                                "order-1#1",
                                                                LongRange.between(0L, 0L),
                                                                java.util.stream.Stream.of(persistedEvent))));

        var jsonSerializer = mock(JSONEventSerializer.class);
        when(jsonSerializer.serializePrettyPrint(any())).thenReturn("{\"state\":\"ok\"}");

        var api = new DefaultAggregateLifecycleApi(new EssentialsSecurityProvider.AllAccessSecurityProvider(),
                                                   snapshotRegistry,
                                                   closingBooksRegistry,
                                                   Optional.of((aggregateType, aggregateImplementationType) -> {
                                                       var access = new TestClosingBooksGenerationAccess();
                                                       if (access.aggregateType().equals(aggregateType) && access.aggregateImplementationType().equals(aggregateImplementationType)) {
                                                           return Optional.of(access);
                                                       }
                                                       return Optional.empty();
                                                   }),
                                                   Optional.of(snapshotStore),
                                                   eventStore,
                                                   jsonSerializer);

        assertThat(api.findAllAggregateSnapshotPolicies("principal")).hasSize(1);
        assertThat(api.findAllAggregateClosingBooksPolicies("principal")).hasSize(1);
        assertThat(api.findCurrentClosingBooksGeneration("principal", AggregateType.of("Orders"), "order-1"))
                .hasValueSatisfying(generation -> assertThat(generation.generation()).isEqualTo(2L));
        assertThat(api.findClosingBooksGenerations("principal", AggregateType.of("Orders"), "order-1")).hasSize(2);
        assertThat(api.findClosingBooksGenerationEventStream("principal", AggregateType.of("Orders"), "order-1", 1L))
                .hasValueSatisfying(generationEventStream -> {
                    assertThat(generationEventStream.streamAggregateId()).isEqualTo("order-1#1");
                    assertThat(generationEventStream.events()).singleElement().satisfies(event -> {
                        assertThat(event.aggregateId()).isEqualTo("order-1#1");
                        assertThat(event.eventOrder()).isEqualTo(0L);
                        assertThat(event.eventPayload()).contains("Created");
                    });
                });
        assertThat(api.findSnapshots("principal", AggregateType.of("Orders"), "order-1", true))
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.aggregateType()).isEqualTo("Orders");
                    assertThat(snapshot.aggregateId()).isEqualTo("order-1");
                    assertThat(snapshot.snapshotPayload()).contains("ok");
                });
    }

    @AggregateSnapshotPolicy(aggregateType = "Orders")
    @dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicy(aggregateType = "Orders")
    private static class TestAggregate {
    }

    private static class TestClosingBooksGenerationAccess implements TypedAggregateClosingBooksGenerationAccess<String> {
        private final InMemoryClosingBooksGenerationResolver<String> repository;

        private TestClosingBooksGenerationAccess() {
            repository = new InMemoryClosingBooksGenerationResolver<>();
            var logicalAggregateId = new LogicalAggregateId<>("order-1");
            repository.openNextGeneration(aggregateType(), logicalAggregateId, (type, id, generation) -> "order-1#" + generation);
            repository.closeCurrentGeneration(aggregateType(), logicalAggregateId);
            repository.openNextGeneration(aggregateType(), logicalAggregateId, (type, id, generation) -> "order-1#" + generation);
        }

        @Override
        public AggregateType aggregateType() {
            return AggregateType.of("Orders");
        }

        @Override
        public Class<?> aggregateImplementationType() {
            return TestAggregate.class;
        }

        @Override
        public ClosingBooksGenerationRepository<String> generationRepository() {
            return repository;
        }

        @Override
        public ClosingBooksIdSerializer<String> logicalAggregateIdSerializer() {
            return ClosingBooksIdSerializer.stringBased();
        }
    }
}
