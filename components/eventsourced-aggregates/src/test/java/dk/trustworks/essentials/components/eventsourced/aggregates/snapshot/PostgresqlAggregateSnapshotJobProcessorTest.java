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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.shared.functional.CheckedConsumer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class PostgresqlAggregateSnapshotJobProcessorTest {
    @Test
    void process_job_saves_snapshot_and_marks_completed() {
        var eventStore = mock(ConfigurableEventStore.class);
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jobRepository = mock(AggregateSnapshotJobRepository.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var config = SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(AggregateType.of("Orders"),
                                                                                                         jsonSerializer,
                                                                                                         new AggregateIdSerializer.StringIdSerializer(),
                                                                                                         IdentifierColumnType.TEXT,
                                                                                                         JSONColumnType.JSONB);
        when(eventStore.getAggregateEventStreamConfiguration(AggregateType.of("Orders"))).thenReturn(config);

        var processor = new PostgresqlAggregateSnapshotJobProcessor(eventStore,
                                                                    snapshotStore,
                                                                    jobRepository,
                                                                    inlineUnitOfWorkFactory(),
                                                                    new DurableAsyncSnapshotSettings(Duration.ofSeconds(1), 25, 2, 3, Duration.ofSeconds(5)));
        var job = new AggregateSnapshotJob(UUID.randomUUID(),
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           7L,
                                           "{\"snapshot\":true}",
                                           true,
                                           List.of(),
                                           OffsetDateTime.now(),
                                           OffsetDateTime.now(),
                                           1,
                                           AggregateSnapshotJobStatus.PROCESSING,
                                           null);

        processor.processJob(job);

        verify(snapshotStore).deleteSnapshotsOlderThan(AggregateType.of("Orders"),
                                                        "order-1",
                                                        TestAggregate.class,
                                                        EventOrder.of(7));
        verify(snapshotStore).saveSnapshot(AggregateType.of("Orders"),
                                           "order-1",
                                           TestAggregate.class,
                                           EventOrder.of(7),
                                           "{\"snapshot\":true}");
        verify(jobRepository).markCompleted(job.jobId());
    }

    @Test
    void process_job_deletes_selected_snapshots_before_saving() {
        var eventStore = mock(ConfigurableEventStore.class);
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jobRepository = mock(AggregateSnapshotJobRepository.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var config = SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(AggregateType.of("Orders"),
                                                                                                         jsonSerializer,
                                                                                                         new AggregateIdSerializer.StringIdSerializer(),
                                                                                                         IdentifierColumnType.TEXT,
                                                                                                         JSONColumnType.JSONB);
        when(eventStore.getAggregateEventStreamConfiguration(AggregateType.of("Orders"))).thenReturn(config);

        var processor = new PostgresqlAggregateSnapshotJobProcessor(eventStore,
                                                                    snapshotStore,
                                                                    jobRepository,
                                                                    inlineUnitOfWorkFactory(),
                                                                    new DurableAsyncSnapshotSettings(Duration.ofSeconds(1), 25, 2, 3, Duration.ofSeconds(5)));
        var job = new AggregateSnapshotJob(UUID.randomUUID(),
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           9L,
                                           "{\"snapshot\":true}",
                                           false,
                                           List.of(1L, 5L),
                                           OffsetDateTime.now(),
                                           OffsetDateTime.now(),
                                           1,
                                           AggregateSnapshotJobStatus.PROCESSING,
                                           null);

        processor.processJob(job);

        verify(snapshotStore).deleteSnapshots(AggregateType.of("Orders"),
                                              "order-1",
                                              TestAggregate.class,
                                              List.of(EventOrder.of(1), EventOrder.of(5)));
        verify(snapshotStore).saveSnapshot(AggregateType.of("Orders"),
                                           "order-1",
                                           TestAggregate.class,
                                           EventOrder.of(9),
                                           "{\"snapshot\":true}");
        verify(jobRepository).markCompleted(job.jobId());
    }

    @Test
    void process_job_marks_job_failed_with_retry_when_processing_fails() {
        var eventStore = mock(ConfigurableEventStore.class);
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jobRepository = mock(AggregateSnapshotJobRepository.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var config = SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(AggregateType.of("Orders"),
                                                                                                         jsonSerializer,
                                                                                                         new AggregateIdSerializer.StringIdSerializer(),
                                                                                                         IdentifierColumnType.TEXT,
                                                                                                         JSONColumnType.JSONB);
        when(eventStore.getAggregateEventStreamConfiguration(AggregateType.of("Orders"))).thenReturn(config);
        doThrow(new IllegalStateException("boom")).when(snapshotStore).saveSnapshot(any(), any(), any(), any(), any());

        var processor = new PostgresqlAggregateSnapshotJobProcessor(eventStore,
                                                                    snapshotStore,
                                                                    jobRepository,
                                                                    inlineUnitOfWorkFactory(),
                                                                    new DurableAsyncSnapshotSettings(Duration.ofSeconds(1), 25, 2, 3, Duration.ofSeconds(5)));
        var job = new AggregateSnapshotJob(UUID.randomUUID(),
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           9L,
                                           "{\"snapshot\":true}",
                                           true,
                                           List.of(),
                                           OffsetDateTime.now(),
                                           OffsetDateTime.now(),
                                           1,
                                           AggregateSnapshotJobStatus.PROCESSING,
                                           null);

        processor.processJob(job);

        verify(jobRepository, never()).markCompleted(any());
        verify(jobRepository).markFailed(eq(job.jobId()), eq("boom"), argThat(nextAttemptTs ->
                nextAttemptTs.isAfter(OffsetDateTime.now().plusSeconds(3)) &&
                        nextAttemptTs.isBefore(OffsetDateTime.now().plusSeconds(7))));
    }

    @Test
    void process_job_parks_job_when_max_retries_are_exceeded() {
        var eventStore = mock(ConfigurableEventStore.class);
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jobRepository = mock(AggregateSnapshotJobRepository.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var config = SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(AggregateType.of("Orders"),
                                                                                                         jsonSerializer,
                                                                                                         new AggregateIdSerializer.StringIdSerializer(),
                                                                                                         IdentifierColumnType.TEXT,
                                                                                                         JSONColumnType.JSONB);
        when(eventStore.getAggregateEventStreamConfiguration(AggregateType.of("Orders"))).thenReturn(config);
        doThrow(new IllegalStateException("boom")).when(snapshotStore).saveSnapshot(any(), any(), any(), any(), any());

        var processor = new PostgresqlAggregateSnapshotJobProcessor(eventStore,
                                                                    snapshotStore,
                                                                    jobRepository,
                                                                    inlineUnitOfWorkFactory(),
                                                                    new DurableAsyncSnapshotSettings(Duration.ofSeconds(1), 25, 2, 2, Duration.ofSeconds(5)));
        var job = new AggregateSnapshotJob(UUID.randomUUID(),
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           9L,
                                           "{\"snapshot\":true}",
                                           true,
                                           List.of(),
                                           OffsetDateTime.now(),
                                           OffsetDateTime.now(),
                                           2,
                                           AggregateSnapshotJobStatus.PROCESSING,
                                           null);

        processor.processJob(job);

        verify(jobRepository).markParked(eq(job.jobId()), eq("boom"), any(OffsetDateTime.class));
        verify(jobRepository, never()).markFailed(any(), any(), any());
    }

    @Test
    void process_job_records_completed_outcome_metric() {
        var eventStore = mock(ConfigurableEventStore.class);
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jobRepository = mock(AggregateSnapshotJobRepository.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var meterRegistry = new SimpleMeterRegistry();
        var config = SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(AggregateType.of("Orders"),
                                                                                                         jsonSerializer,
                                                                                                         new AggregateIdSerializer.StringIdSerializer(),
                                                                                                         IdentifierColumnType.TEXT,
                                                                                                         JSONColumnType.JSONB);
        when(eventStore.getAggregateEventStreamConfiguration(AggregateType.of("Orders"))).thenReturn(config);

        var processor = new PostgresqlAggregateSnapshotJobProcessor(eventStore,
                                                                    snapshotStore,
                                                                    jobRepository,
                                                                    inlineUnitOfWorkFactory(),
                                                                    new DurableAsyncSnapshotSettings(Duration.ofSeconds(1), 25, 2, 3, Duration.ofSeconds(5)),
                                                                    java.util.Optional.of(meterRegistry));
        var job = new AggregateSnapshotJob(UUID.randomUUID(),
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           7L,
                                           "{\"snapshot\":true}",
                                           true,
                                           List.of(),
                                           OffsetDateTime.now(),
                                           OffsetDateTime.now(),
                                           1,
                                           AggregateSnapshotJobStatus.PROCESSING,
                                           null);

        processor.processJob(job);

        assertThat(meterRegistry.find(AggregateSnapshotDurableQueueMeasurementSupport.METRIC_PREFIX + ".process_job")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", TestAggregate.class.getName())
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find(AggregateSnapshotDurableQueueMeasurementSupport.METRIC_PREFIX + ".process_job.outcome")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", TestAggregate.class.getName())
                                .tag("outcome", "completed")
                                .counter())
                .isNotNull()
                .extracting(counter -> counter.count())
                .isEqualTo(1.0d);
    }

    @Test
    void process_job_records_retry_exhausted_outcome_metric() {
        var eventStore = mock(ConfigurableEventStore.class);
        var snapshotStore = mock(AggregateSnapshotStore.class);
        var jobRepository = mock(AggregateSnapshotJobRepository.class);
        var jsonSerializer = mock(JSONEventSerializer.class);
        var meterRegistry = new SimpleMeterRegistry();
        var config = SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(AggregateType.of("Orders"),
                                                                                                         jsonSerializer,
                                                                                                         new AggregateIdSerializer.StringIdSerializer(),
                                                                                                         IdentifierColumnType.TEXT,
                                                                                                         JSONColumnType.JSONB);
        when(eventStore.getAggregateEventStreamConfiguration(AggregateType.of("Orders"))).thenReturn(config);
        doThrow(new IllegalStateException("boom")).when(snapshotStore).saveSnapshot(any(), any(), any(), any(), any());

        var processor = new PostgresqlAggregateSnapshotJobProcessor(eventStore,
                                                                    snapshotStore,
                                                                    jobRepository,
                                                                    inlineUnitOfWorkFactory(),
                                                                    new DurableAsyncSnapshotSettings(Duration.ofSeconds(1), 25, 2, 1, Duration.ofSeconds(5)),
                                                                    java.util.Optional.of(meterRegistry));
        var job = new AggregateSnapshotJob(UUID.randomUUID(),
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           9L,
                                           "{\"snapshot\":true}",
                                           true,
                                           List.of(),
                                           OffsetDateTime.now(),
                                           OffsetDateTime.now(),
                                           1,
                                           AggregateSnapshotJobStatus.PROCESSING,
                                           null);

        processor.processJob(job);

        assertThat(meterRegistry.find(AggregateSnapshotDurableQueueMeasurementSupport.METRIC_PREFIX + ".process_job.outcome")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", TestAggregate.class.getName())
                                .tag("outcome", "retry_exhausted")
                                .counter())
                .isNotNull()
                .extracting(counter -> counter.count())
                .isEqualTo(1.0d);
    }

    private static HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> inlineUnitOfWorkFactory() {
        @SuppressWarnings("unchecked")
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> uowFactory = mock(HandleAwareUnitOfWorkFactory.class);
        var uow = mock(HandleAwareUnitOfWork.class);
        try {
            doAnswer(invocation -> {
                CheckedConsumer<HandleAwareUnitOfWork> consumer = invocation.getArgument(0);
                consumer.accept(uow);
                return null;
            }).when(uowFactory).usingUnitOfWork(any(CheckedConsumer.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return uowFactory;
    }

    private static final class TestAggregate {
    }
}
