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

    /**
     * Constructs a new instance of {@code PostgresqlAggregateSnapshotJobProcessor}.
     *
     * @param eventStore The event store used for accessing event streams and performing operations related to aggregate events.
     * @param snapshotStore The snapshot store used for managing aggregate snapshots during the job processing.
     * @param jobRepository The repository responsible for managing snapshot job metadata and persistence.
     * @param settings The configuration settings for processing durable asynchronous snapshot jobs.
     */
    public PostgresqlAggregateSnapshotJobProcessor(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                                   AggregateSnapshotStore snapshotStore,
                                                   AggregateSnapshotJobRepository jobRepository,
                                                   DurableAsyncSnapshotSettings settings) {
        this(eventStore, snapshotStore, jobRepository, settings, Optional.empty());
    }

    /**
     * Constructs a new instance of {@code PostgresqlAggregateSnapshotJobProcessor}.
     *
     * @param eventStore The event store used for accessing event streams and performing operations related to aggregate events.
     * @param snapshotStore The snapshot store used for managing aggregate snapshots during the job processing.
     * @param jobRepository The repository responsible for managing snapshot job metadata and persistence.
     * @param settings The configuration settings for processing durable asynchronous snapshot jobs.
     * @param meterRegistryOptional An optional registry for recording metrics related to snapshot job processing.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public PostgresqlAggregateSnapshotJobProcessor(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                                   AggregateSnapshotStore snapshotStore,
                                                   AggregateSnapshotJobRepository jobRepository,
                                                   DurableAsyncSnapshotSettings settings,
                                                   Optional<MeterRegistry> meterRegistryOptional) {
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.snapshotStore = requireNonNull(snapshotStore, "No snapshotStore provided");
        this.jobRepository = requireNonNull(jobRepository, "No jobRepository provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.measurementSupport = new AggregateSnapshotDurableQueueMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
    }

    public int processNextBatch(ExecutorService workerExecutor) {
        var jobs = jobRepository.lockNextBatch(settings.batchSize(), OffsetDateTime.now());
        jobs.forEach(job -> workerExecutor.submit(() -> processJob(job)));
        return jobs.size();
    }

    void processJob(AggregateSnapshotJob job) {
        measurementSupport.recordProcessJob(job, () -> {
            try {
                var aggregateType = AggregateType.of(job.aggregateType());
                var config = eventStore.getAggregateEventStreamConfiguration(aggregateType);
                var aggregateId = config.aggregateIdSerializer.deserialize(job.serializedAggregateId());
                var aggregateImplementationType = Classes.forName(job.aggregateImplementationType(), getClass().getClassLoader());

                if (job.deleteAllExistingSnapshots()) {
                    snapshotStore.deleteSnapshots(aggregateType, aggregateId, aggregateImplementationType);
                } else if (!job.snapshotEventOrdersToDelete().isEmpty()) {
                    snapshotStore.deleteSnapshots(aggregateType,
                                                  aggregateId,
                                                  aggregateImplementationType,
                                                  job.snapshotEventOrdersToDelete().stream().map(EventOrder::of).toList());
                }

                snapshotStore.saveSnapshot(aggregateType,
                                           aggregateId,
                                           aggregateImplementationType,
                                           EventOrder.of(job.lastIncludedEventOrder()),
                                           job.serializedSnapshot());
                jobRepository.markCompleted(job.jobId());
                measurementSupport.incrementProcessOutcome(job, "completed");
            } catch (Exception e) {
                var retryCountExceeded = job.attempts() >= settings.maxRetries();
                var nextAttemptTs = OffsetDateTime.now().plus(settings.retryDelay());
                jobRepository.markFailed(job.jobId(),
                                         e.getMessage(),
                                         retryCountExceeded ? OffsetDateTime.MAX.minusYears(1000) : nextAttemptTs);
                measurementSupport.incrementProcessOutcome(job, retryCountExceeded ? "retry_exhausted" : "retry_scheduled");
                log.warn("Failed processing AggregateSnapshotJob '{}' for aggregate '{}:{}' (attempt {} of {})",
                         job.jobId(),
                         job.aggregateType(),
                         job.serializedAggregateId(),
                         job.attempts(),
                         settings.maxRetries(),
                         e);
            }
        });
    }
}
