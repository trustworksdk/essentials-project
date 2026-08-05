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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.foundation.transaction.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AsyncAggregateSnapshotRepositoryTest {

    private AsyncAggregateSnapshotRepository repository;

    @AfterEach
    void cleanup() {
        if (repository != null && repository.isStarted()) {
            repository.stop();
        }
    }

    @Test
    void async_dispatch_eventually_invokes_save_snapshot() {
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var strategy = mock(AddNewAggregateSnapshotStrategy.class);
        var deletionStrategy = mock(AggregateSnapshotDeletionStrategy.class);
        repository = new AsyncAggregateSnapshotRepository(snapshotStore,
                                                          jsonSerializer,
                                                          strategy,
                                                          deletionStrategy,
                                                          new AsyncAggregateSnapshotSettings(SnapshotExecutionMode.ASYNC_IN_MEMORY, 1));
        repository.start();

        var aggregate = new TestAggregate();
        var aggregateType = AggregateType.of("Orders");
        var aggregateId = "order-1";
        var eventOrder = EventOrder.of(3);
        var persistedEvent = mock(PersistedEvent.class);
        when(persistedEvent.eventOrder()).thenReturn(eventOrder);
        var persistedEvents = mock(AggregateEventStream.class);
        when(persistedEvents.aggregateType()).thenReturn(aggregateType);
        when(persistedEvents.aggregateId()).thenReturn(aggregateId);
        when(persistedEvents.eventList()).thenReturn(List.of(persistedEvent));
        when(snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, aggregateId, TestAggregate.class)).thenReturn(Optional.of(EventOrder.of(1)));
        when(strategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, Optional.of(EventOrder.of(1)))).thenReturn(true);
        when(deletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()).thenReturn(false);
        when(jsonSerializer.serialize(any())).thenReturn("{\"type\":\"snapshot\"}");

        repository.aggregateUpdated(aggregate, persistedEvents);

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(snapshotStore).deleteSnapshotsOlderThan(aggregateType, aggregateId, TestAggregate.class, eventOrder);
            verify(snapshotStore).saveSnapshot(aggregateType, aggregateId, TestAggregate.class, eventOrder, "{\"type\":\"snapshot\"}");
        });
    }

    @SuppressWarnings("unchecked")
    @Test
    void aggregate_updated_registers_after_commit_callback_when_unit_of_work_is_active() {
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var strategy = mock(AddNewAggregateSnapshotStrategy.class);
        var deletionStrategy = mock(AggregateSnapshotDeletionStrategy.class);
        var unitOfWorkFactory = mock(UnitOfWorkFactory.class);
        var unitOfWork = mock(UnitOfWork.class);
        repository = new AsyncAggregateSnapshotRepository(snapshotStore,
                                                          jsonSerializer,
                                                          strategy,
                                                          deletionStrategy,
                                                          AsyncAggregateSnapshotSettings.asynchronous(),
                                                          unitOfWorkFactory);
        repository.start();

        var aggregate = new TestAggregate();
        var aggregateType = AggregateType.of("Orders");
        var aggregateId = "order-1";
        var eventOrder = EventOrder.of(3);
        var persistedEvent = mock(PersistedEvent.class);
        when(persistedEvent.eventOrder()).thenReturn(eventOrder);
        var persistedEvents = mock(AggregateEventStream.class);
        when(persistedEvents.aggregateType()).thenReturn(aggregateType);
        when(persistedEvents.aggregateId()).thenReturn(aggregateId);
        when(persistedEvents.eventList()).thenReturn(List.of(persistedEvent));
        when(snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, aggregateId, TestAggregate.class)).thenReturn(Optional.of(EventOrder.of(1)));
        when(strategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, Optional.of(EventOrder.of(1)))).thenReturn(true);
        when(deletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()).thenReturn(false);
        when(jsonSerializer.serialize(any())).thenReturn("{\"type\":\"snapshot\"}");
        when(unitOfWorkFactory.getCurrentUnitOfWork()).thenReturn(Optional.of(unitOfWork));

        var registeredTasks = new ArrayList<Runnable>();
        var registeredCallbacks = new ArrayList<UnitOfWorkLifecycleCallback<Runnable>>();
        when(unitOfWork.registerLifecycleCallbackForResource(any(Runnable.class), any(UnitOfWorkLifecycleCallback.class))).thenAnswer(invocation -> {
            registeredTasks.add(invocation.getArgument(0));
            registeredCallbacks.add(invocation.getArgument(1));
            return invocation.getArgument(0);
        });

        repository.aggregateUpdated(aggregate, persistedEvents);

        assertThat(registeredTasks).hasSize(1);
        verify(snapshotStore, never()).saveSnapshot(any(), any(), any(), any(), any());

        registeredCallbacks.get(0).afterCommit(unitOfWork, registeredTasks);

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            verify(snapshotStore).deleteSnapshotsOlderThan(aggregateType, aggregateId, TestAggregate.class, eventOrder);
            verify(snapshotStore).saveSnapshot(aggregateType, aggregateId, TestAggregate.class, eventOrder, "{\"type\":\"snapshot\"}");
        });
    }

    @Test
    void aggregate_updated_does_nothing_when_strategy_skips_snapshot() {
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var strategy = mock(AddNewAggregateSnapshotStrategy.class);
        var deletionStrategy = mock(AggregateSnapshotDeletionStrategy.class);
        repository = new AsyncAggregateSnapshotRepository(snapshotStore,
                                                          jsonSerializer,
                                                          strategy,
                                                          deletionStrategy,
                                                          AsyncAggregateSnapshotSettings.synchronous());
        repository.start();

        var aggregate = new TestAggregate();
        var aggregateType = AggregateType.of("Orders");
        var aggregateId = "order-1";
        var persistedEvent = mock(PersistedEvent.class);
        when(persistedEvent.eventOrder()).thenReturn(EventOrder.of(1));
        var persistedEvents = mock(AggregateEventStream.class);
        when(persistedEvents.aggregateType()).thenReturn(aggregateType);
        when(persistedEvents.aggregateId()).thenReturn(aggregateId);
        when(persistedEvents.eventList()).thenReturn(List.of(persistedEvent));
        when(snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, aggregateId, TestAggregate.class)).thenReturn(Optional.empty());
        when(strategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, Optional.empty())).thenReturn(false);

        repository.aggregateUpdated(aggregate, persistedEvents);

        verifyNoMoreInteractions(jsonSerializer);
        verify(snapshotStore, never()).saveSnapshot(any(), any(), any(), any(), any());
    }

    @Test
    void aggregate_updated_deletes_selected_historic_snapshots_before_saving_in_sync_mode() {
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var strategy = mock(AddNewAggregateSnapshotStrategy.class);
        var deletionStrategy = mock(AggregateSnapshotDeletionStrategy.class);
        repository = new AsyncAggregateSnapshotRepository(snapshotStore,
                                                          jsonSerializer,
                                                          strategy,
                                                          deletionStrategy,
                                                          AsyncAggregateSnapshotSettings.synchronous());
        repository.start();

        var aggregate = new TestAggregate();
        var aggregateType = AggregateType.of("Orders");
        var aggregateId = "order-1";
        var persistedEvent = mock(PersistedEvent.class);
        when(persistedEvent.eventOrder()).thenReturn(EventOrder.of(5));
        var persistedEvents = mock(AggregateEventStream.class);
        when(persistedEvents.aggregateType()).thenReturn(aggregateType);
        when(persistedEvents.aggregateId()).thenReturn(aggregateId);
        when(persistedEvents.eventList()).thenReturn(List.of(persistedEvent));
        when(snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, aggregateId, TestAggregate.class)).thenReturn(Optional.of(EventOrder.of(3)));
        when(strategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, Optional.of(EventOrder.of(3)))).thenReturn(true);
        when(deletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()).thenReturn(true);
        when(snapshotStore.loadAllSnapshots(aggregateType, aggregateId, TestAggregate.class, false)).thenReturn(List.of(snapshot(EventOrder.of(1)),
                                                                                                                       snapshot(EventOrder.of(3))));
        when(deletionStrategy.resolveSnapshotsToDelete(anyList())).thenAnswer(invocation -> {
            List<AggregateSnapshot<String, TestAggregate>> existingSnapshots = invocation.getArgument(0);
            return existingSnapshots.stream().limit(1);
        });
        when(jsonSerializer.serialize(any())).thenReturn("{\"type\":\"snapshot\"}");

        repository.aggregateUpdated(aggregate, persistedEvents);

        verify(snapshotStore).deleteSnapshots(aggregateType,
                                              aggregateId,
                                              TestAggregate.class,
                                              List.of(EventOrder.of(1)));
        verify(snapshotStore).saveSnapshot(aggregateType,
                                           aggregateId,
                                           TestAggregate.class,
                                           EventOrder.of(5),
                                           "{\"type\":\"snapshot\"}");
    }

    @Test
    void aggregate_updated_records_snapshot_serialization_metric() {
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var strategy = mock(AddNewAggregateSnapshotStrategy.class);
        var deletionStrategy = mock(AggregateSnapshotDeletionStrategy.class);
        var meterRegistry = new SimpleMeterRegistry();
        repository = new AsyncAggregateSnapshotRepository(snapshotStore,
                                                          jsonSerializer,
                                                          strategy,
                                                          deletionStrategy,
                                                          AsyncAggregateSnapshotSettings.synchronous(),
                                                          Optional.empty(),
                                                          Optional.of(meterRegistry));
        repository.start();

        var aggregate = new TestAggregate();
        var aggregateType = AggregateType.of("Orders");
        var aggregateId = "order-1";
        var persistedEvent = mock(PersistedEvent.class);
        when(persistedEvent.eventOrder()).thenReturn(EventOrder.of(2));
        var persistedEvents = mock(AggregateEventStream.class);
        when(persistedEvents.aggregateType()).thenReturn(aggregateType);
        when(persistedEvents.aggregateId()).thenReturn(aggregateId);
        when(persistedEvents.eventList()).thenReturn(List.of(persistedEvent));
        when(snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, aggregateId, TestAggregate.class)).thenReturn(Optional.empty());
        when(strategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, Optional.empty())).thenReturn(true);
        when(deletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()).thenReturn(false);
        when(jsonSerializer.serialize(any())).thenReturn("{\"type\":\"snapshot\"}");

        repository.aggregateUpdated(aggregate, persistedEvents);

        assertThat(meterRegistry.find(AggregateSnapshotMeasurementSupport.METRIC_PREFIX + ".serialize_snapshot")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", TestAggregate.class.getName())
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
    }

    @Test
    void async_persistence_task_exception_is_caught_and_logged_not_propagated() {
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var strategy = mock(AddNewAggregateSnapshotStrategy.class);
        var deletionStrategy = mock(AggregateSnapshotDeletionStrategy.class);
        repository = new AsyncAggregateSnapshotRepository(snapshotStore,
                                                          jsonSerializer,
                                                          strategy,
                                                          deletionStrategy,
                                                          new AsyncAggregateSnapshotSettings(SnapshotExecutionMode.ASYNC_IN_MEMORY, 1));
        repository.start();

        var aggregate = new TestAggregate();
        var aggregateType = AggregateType.of("Orders");
        var aggregateId = "order-1";
        var persistedEvent = mock(PersistedEvent.class);
        when(persistedEvent.eventOrder()).thenReturn(EventOrder.of(3));
        var persistedEvents = mock(AggregateEventStream.class);
        when(persistedEvents.aggregateType()).thenReturn(aggregateType);
        when(persistedEvents.aggregateId()).thenReturn(aggregateId);
        when(persistedEvents.eventList()).thenReturn(List.of(persistedEvent));
        when(snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, aggregateId, TestAggregate.class)).thenReturn(Optional.empty());
        when(strategy.shouldANewAggregateSnapshotBeAdded(aggregate, persistedEvents, Optional.empty())).thenReturn(true);
        when(deletionStrategy.requiresExistingSnapshotDetailsToDetermineWhichAggregateSnapshotsToDelete()).thenReturn(false);
        when(jsonSerializer.serialize(any())).thenReturn("{\"type\":\"snapshot\"}");
        doThrow(new IllegalStateException("boom")).when(snapshotStore).saveSnapshot(any(), any(), any(), any(), any());

        // aggregateUpdated must not propagate the persistence-task exception even though the worker
        // thread will hit it. Submitting a second task afterwards must still succeed — proving the
        // worker thread did not die.
        repository.aggregateUpdated(aggregate, persistedEvents);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(snapshotStore).saveSnapshot(any(), any(), any(), any(), any()));

        when(snapshotStore.findMostRecentLastIncludedEventOrder(aggregateType, "order-2", TestAggregate.class)).thenReturn(Optional.empty());
        var nextEvents = mock(AggregateEventStream.class);
        when(nextEvents.aggregateType()).thenReturn(aggregateType);
        when(nextEvents.aggregateId()).thenReturn("order-2");
        when(nextEvents.eventList()).thenReturn(List.of(persistedEvent));
        when(strategy.shouldANewAggregateSnapshotBeAdded(aggregate, nextEvents, Optional.empty())).thenReturn(true);
        repository.aggregateUpdated(aggregate, nextEvents);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(snapshotStore, atLeast(2)).saveSnapshot(any(), any(), any(), any(), any()));
    }

    @Test
    void aggregate_updated_throws_when_repository_is_not_started() {
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var strategy = mock(AddNewAggregateSnapshotStrategy.class);
        var deletionStrategy = mock(AggregateSnapshotDeletionStrategy.class);
        repository = new AsyncAggregateSnapshotRepository(snapshotStore,
                                                          jsonSerializer,
                                                          strategy,
                                                          deletionStrategy,
                                                          AsyncAggregateSnapshotSettings.synchronous());

        var aggregate = new TestAggregate();
        var persistedEvents = mock(AggregateEventStream.class);

        try {
            repository.aggregateUpdated(aggregate, persistedEvents);
            org.junit.jupiter.api.Assertions.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("not started");
        }
    }

    private AggregateSnapshot<String, TestAggregate> snapshot(EventOrder eventOrder) {
        return new AggregateSnapshot<>(AggregateType.of("Orders"),
                                       "order-1",
                                       TestAggregate.class,
                                       null,
                                       eventOrder);
    }

    private static final class TestAggregate {
    }
}
