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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.shared.reflection.Classes;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The PostgresqlAggregateSnapshotJobProcessor is responsible for processing aggregate snapshot jobs
 * for PostgreSQL-based event-sourced systems. This processor works with locked batches of snapshot
 * jobs and handles their execution asynchronously, while supporting metrics and error handling.
 * <p>
 * Responsibilities:
 * - Pulls snapshot jobs from the repository in batches.
 * - Processes each job by managing snapshots (e.g., creating, deleting, saving) as specified in the job details.
 * - Handles retries and error scenarios based on configured settings.
 * - Provides metrics for monitoring the processing outcomes.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class PostgresqlAggregateSnapshotJobProcessor {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlAggregateSnapshotJobProcessor.class);

    private final ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore;
    private final AggregateSnapshotStore                                              snapshotStore;
    private final AggregateSnapshotJobRepository                                      jobRepository;
    private final DurableAsyncSnapshotSettings                                        settings;
    private final AggregateSnapshotDurableQueueMeasurementSupport                     measurementSupport;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork>       unitOfWorkFactory;

    public PostgresqlAggregateSnapshotJobProcessor(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                                   AggregateSnapshotStore snapshotStore,
                                                   AggregateSnapshotJobRepository jobRepository,
                                                   HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                   DurableAsyncSnapshotSettings settings) {
        this(eventStore, snapshotStore, jobRepository, unitOfWorkFactory, settings, Optional.empty());
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public PostgresqlAggregateSnapshotJobProcessor(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                                   AggregateSnapshotStore snapshotStore,
                                                   AggregateSnapshotJobRepository jobRepository,
                                                   HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                   DurableAsyncSnapshotSettings settings,
                                                   Optional<MeterRegistry> meterRegistryOptional) {
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.snapshotStore = requireNonNull(snapshotStore, "No snapshotStore provided");
        this.jobRepository = requireNonNull(jobRepository, "No jobRepository provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.measurementSupport = new AggregateSnapshotDurableQueueMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
    }

    public int processNextBatch(ExecutorService workerExecutor) {
        var now = OffsetDateTime.now();
        var jobs = jobRepository.lockNextBatch(settings.batchSize(),
                                                now,
                                                now.minus(settings.processingTimeout()));
        jobs.forEach(job -> workerExecutor.submit(() -> processJob(job)));
        return jobs.size();
    }

    void processJob(AggregateSnapshotJob job) {
        measurementSupport.recordProcessJob(job, () -> {
            try {
                unitOfWorkFactory.usingUnitOfWork(uow -> applyJob(job));
                measurementSupport.incrementProcessOutcome(job, "completed");
            } catch (Exception e) {
                var retryCountExceeded = job.attempts() >= settings.maxRetries();
                if (retryCountExceeded) {
                    jobRepository.markParked(job.jobId(), e.getMessage(), OffsetDateTime.now());
                } else {
                    jobRepository.markFailed(job.jobId(),
                                             e.getMessage(),
                                             OffsetDateTime.now().plus(settings.retryDelay()));
                }
                measurementSupport.incrementProcessOutcome(job, retryCountExceeded ? "retry_exhausted" : "retry_scheduled");
                log.warn("Failed processing AggregateSnapshotJob '{}' for aggregate '{}:{}' (attempt {} of {}){}",
                         job.jobId(),
                         job.aggregateType(),
                         job.serializedAggregateId(),
                         job.attempts(),
                         settings.maxRetries(),
                         retryCountExceeded ? " — retry budget exhausted, parking" : "",
                         e);
            }
        });
    }

    private void applyJob(AggregateSnapshotJob job) {
        var aggregateType = AggregateType.of(job.aggregateType());
        var config = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var aggregateId = config.aggregateIdSerializer.deserialize(job.serializedAggregateId());
        var aggregateImplementationType = Classes.forName(job.aggregateImplementationType(), getClass().getClassLoader());

        var lastIncludedEventOrder = EventOrder.of(job.lastIncludedEventOrder());
        if (job.deleteAllExistingSnapshots()) {
            snapshotStore.deleteSnapshotsOlderThan(aggregateType,
                                                    aggregateId,
                                                    aggregateImplementationType,
                                                    lastIncludedEventOrder);
        } else if (!job.snapshotEventOrdersToDelete().isEmpty()) {
            snapshotStore.deleteSnapshots(aggregateType,
                                          aggregateId,
                                          aggregateImplementationType,
                                          job.snapshotEventOrdersToDelete().stream().map(EventOrder::of).toList());
        }

        snapshotStore.saveSnapshot(aggregateType,
                                   aggregateId,
                                   aggregateImplementationType,
                                   lastIncludedEventOrder,
                                   job.serializedSnapshot());
        jobRepository.markCompleted(job.jobId());
    }
}
