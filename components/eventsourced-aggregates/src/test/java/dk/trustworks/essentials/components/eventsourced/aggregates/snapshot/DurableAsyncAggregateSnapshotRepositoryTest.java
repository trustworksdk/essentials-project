package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class DurableAsyncAggregateSnapshotRepositoryTest {
    @Test
    void aggregate_updated_enqueues_durable_job() {
        var eventStore = mock(ConfigurableEventStore.class);
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jobRepository = mock(AggregateSnapshotJobRepository.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var strategy = mock(AddNewAggregateSnapshotStrategy.class);
        var deletionStrategy = mock(AggregateSnapshotDeletionStrategy.class);
        var repository = new DurableAsyncAggregateSnapshotRepository(eventStore,
                                                                     snapshotStore,
                                                                     jobRepository,
                                                                     jsonSerializer,
                                                                     strategy,
                                                                     deletionStrategy);

        var aggregateType = AggregateType.of("Orders");
        var config = SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(aggregateType,
                                                                                                         jsonSerializer,
                                                                                                         new AggregateIdSerializer.StringIdSerializer(),
                                                                                                         IdentifierColumnType.TEXT,
                                                                                                         JSONColumnType.JSONB);
        var aggregate = new TestAggregate();
        var aggregateId = "order-1";
        var persistedEvent = mock(PersistedEvent.class);
        when(persistedEvent.eventOrder()).thenReturn(EventOrder.of(3));
        var persistedEvents = mock(AggregateEventStream.class);
        when(persistedEvents.aggregateType()).thenReturn(aggregateType);
        when(persistedEvents.aggregateId()).thenReturn(aggregateId);
        when(persistedEvents.eventList()).thenReturn(List.of(persistedEvent));
        when(snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, aggregateId, TestAggregate.class)).thenReturn(Optional.of(EventOrder.of(1)));
        when(strategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, Optional.of(EventOrder.of(1)))).thenReturn(true);
        when(eventStore.getAggregateEventStreamConfiguration(aggregateType)).thenReturn(config);
        when(deletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()).thenReturn(false);
        when(jsonSerializer.serialize(any())).thenReturn("{\"snapshot\":true}");

        repository.aggregateUpdated(aggregate, persistedEvents);

        verify(jobRepository).enqueue(argThat(job -> {
            assertThat(job.aggregateType()).isEqualTo("Orders");
            assertThat(job.serializedAggregateId()).isEqualTo("order-1");
            assertThat(job.aggregateImplementationType()).isEqualTo(TestAggregate.class.getName());
            assertThat(job.lastIncludedEventOrder()).isEqualTo(3L);
            assertThat(job.deleteAllExistingSnapshots()).isTrue();
            assertThat(job.serializedSnapshot()).isEqualTo("{\"snapshot\":true}");
            return true;
        }));
    }

    @Test
    void aggregate_updated_records_snapshot_serialization_metric() {
        var eventStore = mock(ConfigurableEventStore.class);
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jobRepository = mock(AggregateSnapshotJobRepository.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var strategy = mock(AddNewAggregateSnapshotStrategy.class);
        var deletionStrategy = mock(AggregateSnapshotDeletionStrategy.class);
        var meterRegistry = new SimpleMeterRegistry();
        var repository = new DurableAsyncAggregateSnapshotRepository(eventStore,
                                                                     snapshotStore,
                                                                     jobRepository,
                                                                     jsonSerializer,
                                                                     strategy,
                                                                     deletionStrategy,
                                                                     Optional.of(meterRegistry));

        var aggregateType = AggregateType.of("Orders");
        var config = SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(aggregateType,
                                                                                                         jsonSerializer,
                                                                                                         new AggregateIdSerializer.StringIdSerializer(),
                                                                                                         IdentifierColumnType.TEXT,
                                                                                                         JSONColumnType.JSONB);
        var aggregate = new TestAggregate();
        var aggregateId = "order-1";
        var persistedEvent = mock(PersistedEvent.class);
        when(persistedEvent.eventOrder()).thenReturn(EventOrder.of(3));
        var persistedEvents = mock(AggregateEventStream.class);
        when(persistedEvents.aggregateType()).thenReturn(aggregateType);
        when(persistedEvents.aggregateId()).thenReturn(aggregateId);
        when(persistedEvents.eventList()).thenReturn(List.of(persistedEvent));
        when(snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, aggregateId, TestAggregate.class)).thenReturn(Optional.empty());
        when(strategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, Optional.empty())).thenReturn(true);
        when(eventStore.getAggregateEventStreamConfiguration(aggregateType)).thenReturn(config);
        when(deletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()).thenReturn(false);
        when(jsonSerializer.serialize(any())).thenReturn("{\"snapshot\":true}");

        repository.aggregateUpdated(aggregate, persistedEvents);

        assertThat(meterRegistry.find(AggregateSnapshotMeasurementSupport.METRIC_PREFIX + ".serialize_snapshot")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", TestAggregate.class.getName())
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
    }

    private static final class TestAggregate {
    }
}
