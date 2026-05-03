/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.IdentifierColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.JSONColumnType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.shared.functional.CheckedFunction;
import dk.trustworks.essentials.types.LongRange;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class DefaultAggregateGenerationArchiverTest {
    @Test
    void it_archives_a_closed_generation_and_saves_registry_entry() throws Exception {
        var fixture = setupFixture(GenerationState.CLOSED);

        when(fixture.registry.tryClaim(any(), anyString(), anyLong(), anyString(), any())).thenReturn(true);
        when(fixture.exporter.format()).thenReturn(AggregateArchiveFormat.JSONL);
        when(fixture.exporter.fileExtension()).thenReturn("jsonl");
        when(fixture.destination.write(any(), any())).thenReturn(new AggregateArchiveWriteResult(
                "file:///tmp/orders/order-1/generation-1.jsonl",
                5L,
                1L,
                "sha256:abc"));

        var result = new DefaultAggregateGenerationArchiver(fixture.registry,
                                                            fixture.accessProvider,
                                                            fixture.eventStore,
                                                            fixture.unitOfWorkFactory,
                                                            fixture.exporter,
                                                            fixture.destination)
                .archiveGeneration(AggregateType.of("Orders"), "order-1", 1L);

        assertThat(result.aggregateType().toString()).isEqualTo("Orders");
        assertThat(result.logicalAggregateId()).isEqualTo("order-1");
        assertThat(result.generation()).isEqualTo(1L);
        assertThat(result.streamAggregateId()).isEqualTo("order-1#1");
        assertThat(result.archiveLocation()).isEqualTo("file:///tmp/orders/order-1/generation-1.jsonl");
        assertThat(result.format()).isEqualTo(AggregateArchiveFormat.JSONL);
        verify(fixture.registry).save(any(AggregateArchiveEntry.class));
        verify(fixture.registry).tryClaim(eq(AggregateType.of("Orders")), eq("order-1"), eq(1L), eq("order-1#1"), any());
    }

    @Test
    void it_records_archive_metrics() throws Exception {
        var fixture = setupFixture(GenerationState.CLOSED);
        var meterRegistry = new SimpleMeterRegistry();

        when(fixture.registry.tryClaim(any(), anyString(), anyLong(), anyString(), any())).thenReturn(true);
        when(fixture.exporter.format()).thenReturn(AggregateArchiveFormat.JSONL);
        when(fixture.exporter.fileExtension()).thenReturn("jsonl");
        when(fixture.destination.write(any(), any())).thenReturn(new AggregateArchiveWriteResult(
                "file:///tmp/orders/order-1/generation-1.jsonl",
                5L,
                1L,
                "sha256:abc"));

        new DefaultAggregateGenerationArchiver(fixture.registry,
                                               fixture.accessProvider,
                                               fixture.eventStore,
                                               fixture.unitOfWorkFactory,
                                               fixture.exporter,
                                               fixture.destination,
                                               Optional.of(meterRegistry))
                .archiveGeneration(AggregateType.of("Orders"), "order-1", 1L);

        assertThat(meterRegistry.get("essentials.aggregate_archive.archive_generation.outcome")
                                .tag("aggregate_type", "Orders")
                                .tag("outcome", "archived")
                                .counter()
                                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("essentials.aggregate_archive.archived_event_count")
                                .tag("aggregate_type", "Orders")
                                .summary()
                                .count()).isEqualTo(1L);
        assertThat(meterRegistry.get("essentials.aggregate_archive.archived_bytes")
                                .tag("aggregate_type", "Orders")
                                .summary()
                                .totalAmount()).isEqualTo(5.0d);
        assertThat(meterRegistry.get("essentials.aggregate_archive.archive_generation")
                                .tag("aggregate_type", "Orders")
                                .timer()
                                .count()).isEqualTo(1L);
    }

    @Test
    void it_rejects_archiving_an_open_generation() {
        var fixture = setupFixture(GenerationState.OPEN);

        assertThatThrownBy(() -> new DefaultAggregateGenerationArchiver(fixture.registry,
                                                                         fixture.accessProvider,
                                                                         fixture.eventStore,
                                                                         fixture.unitOfWorkFactory,
                                                                         fixture.exporter,
                                                                         fixture.destination)
                .archiveGeneration(AggregateType.of("Orders"), "order-1", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still open");
    }

    private static Fixture setupFixture(GenerationState state) {
        var fixture = new Fixture();
        fixture.registry = mock(AggregateArchiveRegistry.class);
        fixture.accessProvider = mock(AggregateClosingBooksGenerationAccessProvider.class);
        var access = mock(AggregateClosingBooksGenerationAccess.class);
        fixture.eventStore = mock(ConfigurableEventStore.class);
        fixture.exporter = mock(AggregateArchiveExporter.class);
        fixture.destination = mock(AggregateArchiveDestination.class);
        fixture.unitOfWorkFactory = inlineHandleAwareUnitOfWorkFactory();
        var aggregateIdSerializer = mock(AggregateIdSerializer.class);
        var generation = new AggregateGeneration<>(AggregateType.of("Orders"),
                                                   new LogicalAggregateId<>("order-1"),
                                                   1L,
                                                   "order-1#1",
                                                   state,
                                                   OffsetDateTime.parse("2026-04-01T00:00:00Z"),
                                                   state == GenerationState.CLOSED
                                                           ? Optional.of(OffsetDateTime.parse("2026-04-10T00:00:00Z"))
                                                           : Optional.empty());
        var aggregateConfiguration = new AggregateEventStreamConfiguration(AggregateType.of("Orders"),
                                                                            100,
                                                                            mock(JSONEventSerializer.class),
                                                                            aggregateIdSerializer,
                                                                            IdentifierColumnType.TEXT,
                                                                            IdentifierColumnType.TEXT,
                                                                            IdentifierColumnType.TEXT,
                                                                            JSONColumnType.JSONB,
                                                                            JSONColumnType.JSONB,
                                                                            new TenantSerializer.NoSupportForMultiTenancySerializer());
        var aggregateEventStream = mock(AggregateEventStream.class);
        var persistedEvent = mock(PersistedEvent.class);

        when(fixture.registry.findArchivedGeneration(AggregateType.of("Orders"), "order-1", 1L)).thenReturn(Optional.empty());
        when(fixture.accessProvider.resolve(AggregateType.of("Orders"))).thenReturn(Optional.of(access));
        when(access.loadGenerations("order-1")).thenReturn(List.of(generation));
        when(fixture.eventStore.getAggregateEventStreamConfiguration(AggregateType.of("Orders"))).thenReturn(aggregateConfiguration);
        when(aggregateIdSerializer.deserialize("order-1#1")).thenReturn("order-1#1");
        when(fixture.eventStore.fetchStream(AggregateType.of("Orders"), "order-1#1", LongRange.from(0L))).thenReturn(Optional.of(aggregateEventStream));
        when(aggregateEventStream.events()).thenReturn(Stream.of(persistedEvent));
        return fixture;
    }

    private static HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> inlineHandleAwareUnitOfWorkFactory() {
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> factory = mock(HandleAwareUnitOfWorkFactory.class);
        var uow = mock(HandleAwareUnitOfWork.class);
        try {
            when(factory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(invocation -> {
                CheckedFunction<HandleAwareUnitOfWork, ?> function = invocation.getArgument(0);
                return function.apply(uow);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return factory;
    }

    private static final class Fixture {
        AggregateArchiveRegistry registry;
        AggregateClosingBooksGenerationAccessProvider accessProvider;
        ConfigurableEventStore eventStore;
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> unitOfWorkFactory;
        AggregateArchiveExporter exporter;
        AggregateArchiveDestination destination;
    }
}
