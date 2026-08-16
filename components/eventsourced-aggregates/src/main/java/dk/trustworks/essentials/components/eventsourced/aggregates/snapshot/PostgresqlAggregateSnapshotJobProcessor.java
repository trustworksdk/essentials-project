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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

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

    /**
     * The jobs this node has locked and either submitted or is still running.
     * <p>
     * {@link AggregateSnapshotJobRepository#lockNextBatch(int, OffsetDateTime, OffsetDateTime)} reclaims
     * {@code PROCESSING} rows whose {@code processing_started_ts} is older than
     * {@link DurableAsyncSnapshotSettings#processingTimeout()}, which is how a job orphaned by a crashed node gets
     * picked back up. A job that is merely taking a long time on this node looks exactly the same from the database,
     * so without this set the reclaim would hand the same job to a second worker while the first is still running it.
     */
    private final Set<UUID> locallyHeldJobIds = ConcurrentHashMap.newKeySet();

    public PostgresqlAggregateSnapshotJobProcessor(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                                   AggregateSnapshotStore snapshotStore,
                                                   AggregateSnapshotJobRepository jobRepository,
                                                   HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                   DurableAsyncSnapshotSettings settings) {
        this(eventStore,
             snapshotStore,
             jobRepository,
             unitOfWorkFactory,
             settings,
             Optional.empty());
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    /**
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
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

    /**
     * Lock a batch of jobs and hand them to {@code workerExecutor}.
     * <p>
     * The returned count is the number of jobs actually submitted, which can be lower than the number locked: a job
     * the reclaim branch handed back while this node is still running it is skipped rather than processed twice.
     * <p>
     * This method does not wait for the submitted jobs to finish, so the caller has to be the one that bounds
     * in-flight work — see {@link DurableAsyncSnapshotManager}, which passes a bounded executor that runs jobs on the
     * polling thread once its queue is full. With an unbounded executor a poll interval shorter than the time it takes
     * to drain a batch makes the executor queue — and with it the retained snapshot payloads — grow without limit.
     *
     * @param workerExecutor the executor to run the locked jobs on; should apply backpressure when saturated
     * @return the number of jobs submitted to {@code workerExecutor}
     */
    public int processNextBatch(ExecutorService workerExecutor) {
        requireNonNull(workerExecutor, "No workerExecutor provided");
        var now = OffsetDateTime.now();
        var jobs = jobRepository.lockNextBatch(settings.batchSize(),
                                                now,
                                                now.minus(settings.processingTimeout()));
        var submitted = 0;
        for (var job : jobs) {
            if (!locallyHeldJobIds.add(job.jobId())) {
                log.debug("Skipping AggregateSnapshotJob '{}' for aggregate '{}:{}' because this node is already processing it — " +
                                  "it was reclaimed as stale after {} while still in progress",
                          job.jobId(),
                          job.aggregateType(),
                          job.serializedAggregateId(),
                          settings.processingTimeout());
                measurementSupport.incrementProcessOutcome(job, "already_in_progress");
                continue;
            }
            try {
                workerExecutor.execute(() -> {
                    try {
                        processJob(job);
                    } finally {
                        locallyHeldJobIds.remove(job.jobId());
                    }
                });
                submitted++;
            } catch (RejectedExecutionException e) {
                // The executor is shutting down. Leave the row PROCESSING so the reclaim branch picks it up, either
                // after a restart or on another node.
                locallyHeldJobIds.remove(job.jobId());
                log.debug("AggregateSnapshotJob '{}' was rejected by the worker executor — leaving it to be reclaimed",
                          job.jobId(),
                          e);
            }
        }
        return submitted;
    }

    void processJob(AggregateSnapshotJob job) {
        measurementSupport.recordProcessJob(job, () -> {
            try {
                unitOfWorkFactory.usingUnitOfWork(uow -> applyJob(job));
                measurementSupport.incrementProcessOutcome(job, "completed");
            } catch (Exception e) {
                var retryCountExceeded = job.attempts() >= settings.maxRetries();
                var error              = describe(e);
                if (retryCountExceeded) {
                    jobRepository.markParked(job.jobId(), error, OffsetDateTime.now());
                } else {
                    jobRepository.markFailed(job.jobId(),
                                             error,
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

    /**
     * {@code last_error} is what an operator has to work from when a job ends up FAILED or PARKED, and plenty of
     * exceptions carry no message — a bare NullPointerException or ClassCastException would have stored SQL NULL and
     * left the row saying nothing at all about why. Falls back to the exception's type.
     */
    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
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

    /**
     * Creates a builder for a {@link PostgresqlAggregateSnapshotJobProcessor}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link PostgresqlAggregateSnapshotJobProcessor}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload for Spring {@code @Bean} methods.
     */
    public static final class Builder {
        private ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore;
        private AggregateSnapshotStore snapshotStore;
        private AggregateSnapshotJobRepository jobRepository;
        private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
        private DurableAsyncSnapshotSettings settings;
        private MeterRegistry meterRegistryOptional;

        /**
         * @param eventStore required
         * @return this builder
         */
        public Builder setEventStore(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore) {
            this.eventStore = eventStore;
            return this;
        }

        /**
         * @param snapshotStore required
         * @return this builder
         */
        public Builder setSnapshotStore(AggregateSnapshotStore snapshotStore) {
            this.snapshotStore = snapshotStore;
            return this;
        }

        /**
         * @param jobRepository required
         * @return this builder
         */
        public Builder setJobRepository(AggregateSnapshotJobRepository jobRepository) {
            this.jobRepository = jobRepository;
            return this;
        }

        /**
         * @param unitOfWorkFactory required
         * @return this builder
         */
        public Builder setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            return this;
        }

        /**
         * @param settings required
         * @return this builder
         */
        public Builder setSettings(DurableAsyncSnapshotSettings settings) {
            this.settings = settings;
            return this;
        }

        /**
         * @param meterRegistryOptional optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setMeterRegistry(MeterRegistry meterRegistryOptional) {
            this.meterRegistryOptional = meterRegistryOptional;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setMeterRegistry(MeterRegistry)}.
         *
         * @param meterRegistryOptional the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setMeterRegistry(Optional<MeterRegistry> meterRegistryOptional) {
            requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided");
            return setMeterRegistry(meterRegistryOptional.orElse(null));
        }

        /**
         * @return the new {@link PostgresqlAggregateSnapshotJobProcessor}
         */
        @SuppressWarnings("removal")
        public PostgresqlAggregateSnapshotJobProcessor build() {
            return new PostgresqlAggregateSnapshotJobProcessor(eventStore,
                                                               snapshotStore,
                                                               jobRepository,
                                                               unitOfWorkFactory,
                                                               settings,
                                                               Optional.ofNullable(meterRegistryOptional));
        }
    }

}
