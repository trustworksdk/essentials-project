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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresqlAggregateSnapshotJobRepositoryIT {
    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest").withDatabaseName("event-store")
                                                                                                           .withUsername("test-user")
                                                                                                           .withPassword("secret-password");

    private EventStoreManagedUnitOfWorkFactory    unitOfWorkFactory;
    private PostgresqlAggregateSnapshotJobRepository repository;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        repository = new PostgresqlAggregateSnapshotJobRepository(unitOfWorkFactory);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void enqueue_lock_retry_and_complete_job() {
        var jobId = UUID.randomUUID();
        var createdAt = OffsetDateTime.now().minusMinutes(1);
        var firstRetryAt = OffsetDateTime.now().plusSeconds(5);
        var job = new AggregateSnapshotJob(jobId,
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           7L,
                                           "{\"snapshot\":true}",
                                           false,
                                           List.of(1L, 3L),
                                           createdAt,
                                           createdAt,
                                           0,
                                           AggregateSnapshotJobStatus.PENDING,
                                           null);

        repository.enqueue(job);

        var lockedJobs = repository.lockNextBatch(10, OffsetDateTime.now());
        assertThat(lockedJobs).singleElement().satisfies(lockedJob -> {
            assertThat(lockedJob.jobId()).isEqualTo(jobId);
            assertThat(lockedJob.status()).isEqualTo(AggregateSnapshotJobStatus.PROCESSING);
            assertThat(lockedJob.attempts()).isEqualTo(1);
            assertThat(lockedJob.snapshotEventOrdersToDelete()).containsExactly(1L, 3L);
            assertThat(lockedJob.deleteAllExistingSnapshots()).isFalse();
        });

        assertThat(repository.lockNextBatch(10, OffsetDateTime.now())).isEmpty();

        repository.markFailed(jobId, "boom", firstRetryAt);

        assertThat(repository.lockNextBatch(10, firstRetryAt.minusSeconds(1))).isEmpty();

        var retriedJobs = repository.lockNextBatch(10, firstRetryAt.plusSeconds(1));
        assertThat(retriedJobs).singleElement().satisfies(retriedJob -> {
            assertThat(retriedJob.jobId()).isEqualTo(jobId);
            assertThat(retriedJob.status()).isEqualTo(AggregateSnapshotJobStatus.PROCESSING);
            assertThat(retriedJob.attempts()).isEqualTo(2);
            assertThat(retriedJob.lastError()).isNull();
        });

        repository.markCompleted(jobId);

        var remainingJobs = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                                      .createQuery("SELECT count(*) FROM " + PostgresqlAggregateSnapshotJobRepository.DEFAULT_TABLE_NAME)
                                                                      .mapTo(Integer.class)
                                                                      .one());
        assertThat(remainingJobs).isZero();
    }

    @Test
    void lock_reclaims_stale_processing_jobs() {
        var jobId = UUID.randomUUID();
        var createdAt = OffsetDateTime.now().minusMinutes(10);
        var job = new AggregateSnapshotJob(jobId,
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           7L,
                                           "{\"snapshot\":true}",
                                           false,
                                           List.of(),
                                           createdAt,
                                           createdAt,
                                           0,
                                           AggregateSnapshotJobStatus.PENDING,
                                           null);

        repository.enqueue(job);
        var lockedJobs = repository.lockNextBatch(10, OffsetDateTime.now(), OffsetDateTime.MIN);
        assertThat(lockedJobs).singleElement().satisfies(locked ->
                assertThat(locked.status()).isEqualTo(AggregateSnapshotJobStatus.PROCESSING));

        // While the job is PROCESSING, a fresh poll without reclaim sees nothing.
        assertThat(repository.lockNextBatch(10, OffsetDateTime.now(), OffsetDateTime.MIN)).isEmpty();

        // Simulating the worker that locked the job dying: reclaim threshold pushed past the start time.
        var reclaimable = repository.lockNextBatch(10,
                                                    OffsetDateTime.now(),
                                                    OffsetDateTime.now().plusMinutes(1));
        assertThat(reclaimable).singleElement().satisfies(reclaimed -> {
            assertThat(reclaimed.jobId()).isEqualTo(jobId);
            assertThat(reclaimed.status()).isEqualTo(AggregateSnapshotJobStatus.PROCESSING);
            assertThat(reclaimed.attempts()).isEqualTo(2);
        });
    }

    @Test
    void records_enqueue_and_lock_metrics() {
        var meterRegistry = new SimpleMeterRegistry();
        repository = new PostgresqlAggregateSnapshotJobRepository(unitOfWorkFactory,
                                                                  java.util.Optional.empty(),
                                                                  java.util.Optional.of(meterRegistry));
        var createdAt = OffsetDateTime.now().minusMinutes(1);
        var job = new AggregateSnapshotJob(UUID.randomUUID(),
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           7L,
                                           "{\"snapshot\":true}",
                                           false,
                                           List.of(1L, 3L),
                                           createdAt,
                                           createdAt,
                                           0,
                                           AggregateSnapshotJobStatus.PENDING,
                                           null);

        repository.enqueue(job);
        repository.lockNextBatch(10, OffsetDateTime.now());

        assertThat(meterRegistry.find(AggregateSnapshotDurableQueueMeasurementSupport.METRIC_PREFIX + ".enqueue")
                                .tag("aggregate_type", "Orders")
                                .tag("aggregate_impl_type", TestAggregate.class.getName())
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find(AggregateSnapshotDurableQueueMeasurementSupport.METRIC_PREFIX + ".lock_next_batch")
                                .tag("batch_size", "10")
                                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(meterRegistry.find(AggregateSnapshotDurableQueueMeasurementSupport.METRIC_PREFIX + ".locked_batch_size")
                                .summary())
                .isNotNull()
                .extracting(summary -> summary.count())
                .isEqualTo(1L);
    }

    @Test
    void concurrent_workers_lock_disjoint_jobs_via_skip_locked() throws InterruptedException {
        // Insert N jobs all eligible for locking immediately (next_attempt_ts in the past).
        var jobCount = 50;
        var workerCount = 4;
        var batchSize = 10;
        var enqueuedAt = OffsetDateTime.now().minusMinutes(1);
        var expectedJobIds = new HashSet<UUID>();
        for (var i = 0; i < jobCount; i++) {
            var jobId = UUID.randomUUID();
            expectedJobIds.add(jobId);
            repository.enqueue(new AggregateSnapshotJob(jobId,
                                                        "Orders",
                                                        "order-" + i,
                                                        TestAggregate.class.getName(),
                                                        i + 1L,
                                                        "{\"snapshot\":" + i + "}",
                                                        false,
                                                        List.of(),
                                                        enqueuedAt,
                                                        enqueuedAt,
                                                        0,
                                                        AggregateSnapshotJobStatus.PENDING,
                                                        null));
        }

        // Each worker repeatedly calls lockNextBatch from its own thread until empty,
        // pushing every locked job ID into a shared concurrent queue.
        var lockedJobIds = new ConcurrentLinkedQueue<UUID>();
        var startGate = new CountDownLatch(1);
        var pollCounter = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(workerCount);
        try {
            var futures = IntStream.range(0, workerCount)
                                   .mapToObj(workerIndex -> workers.submit(() -> {
                                       startGate.await();
                                       while (true) {
                                           var batch = repository.lockNextBatch(batchSize, OffsetDateTime.now(), OffsetDateTime.MIN);
                                           pollCounter.incrementAndGet();
                                           if (batch.isEmpty()) {
                                               return null;
                                           }
                                           batch.forEach(job -> lockedJobIds.add(job.jobId()));
                                       }
                                   }))
                                   .toList();
            startGate.countDown();
            for (var future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // Every job must appear exactly once across all workers — SKIP LOCKED guarantees disjoint claims.
        var lockedList = List.copyOf(lockedJobIds);
        Set<UUID> uniqueLocked = Set.copyOf(lockedList);
        assertThat(uniqueLocked).hasSize(lockedList.size());          // no duplicate claims
        assertThat(uniqueLocked).isEqualTo(expectedJobIds);            // every enqueued job claimed
        assertThat(pollCounter.get()).isGreaterThanOrEqualTo(workerCount);

        // All claimed jobs must have transitioned to PROCESSING.
        var processingCount = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                                          .createQuery("SELECT count(*) FROM " + PostgresqlAggregateSnapshotJobRepository.DEFAULT_TABLE_NAME +
                                                                                       " WHERE status = 'PROCESSING'")
                                                                          .mapTo(Long.class)
                                                                          .one());
        assertThat(processingCount).isEqualTo(jobCount);
    }

    @Test
    void mark_parked_excludes_job_from_polling_and_records_in_gauge() {
        var meterRegistry = new SimpleMeterRegistry();
        repository = new PostgresqlAggregateSnapshotJobRepository(unitOfWorkFactory,
                                                                  java.util.Optional.empty(),
                                                                  java.util.Optional.of(meterRegistry));
        var jobId = UUID.randomUUID();
        var createdAt = OffsetDateTime.now().minusMinutes(1);
        repository.enqueue(new AggregateSnapshotJob(jobId,
                                                    "Orders",
                                                    "order-park",
                                                    TestAggregate.class.getName(),
                                                    9L,
                                                    "{\"snapshot\":true}",
                                                    false,
                                                    List.of(),
                                                    createdAt,
                                                    createdAt,
                                                    0,
                                                    AggregateSnapshotJobStatus.PENDING,
                                                    null));

        repository.markParked(jobId, "poison", OffsetDateTime.now());

        // Polling no longer returns parked jobs.
        assertThat(repository.lockNextBatch(10, OffsetDateTime.now(), OffsetDateTime.MIN)).isEmpty();
        assertThat(meterRegistry.find(AggregateSnapshotDurableQueueMeasurementSupport.METRIC_PREFIX + ".queue_depth")
                                .tag("status", "PARKED")
                                .gauge().value()).isEqualTo(1.0d);
    }

    @Test
    void enqueue_replaces_a_parked_row_for_the_same_aggregate_event_order() {
        var firstJobId = UUID.randomUUID();
        var staleAt = OffsetDateTime.now().minusMinutes(1);
        repository.enqueue(new AggregateSnapshotJob(firstJobId,
                                                    "Orders",
                                                    "order-replay",
                                                    TestAggregate.class.getName(),
                                                    7L,
                                                    "{\"snapshot\":\"broken\"}",
                                                    false,
                                                    List.of(),
                                                    staleAt,
                                                    staleAt,
                                                    0,
                                                    AggregateSnapshotJobStatus.PENDING,
                                                    null));

        repository.markParked(firstJobId, "poison", OffsetDateTime.now());

        // Re-enqueue at the same (impl_type, aggregate_id, last_included_event_order). The PARKED
        // row's payload should be replaced with the new one and its retry state reset.
        var newJobId = UUID.randomUUID();
        repository.enqueue(new AggregateSnapshotJob(newJobId,
                                                    "Orders",
                                                    "order-replay",
                                                    TestAggregate.class.getName(),
                                                    7L,
                                                    "{\"snapshot\":\"fixed\"}",
                                                    false,
                                                    List.of(),
                                                    OffsetDateTime.now().minusSeconds(5),
                                                    OffsetDateTime.now().minusSeconds(5),
                                                    0,
                                                    AggregateSnapshotJobStatus.PENDING,
                                                    null));

        var locked = repository.lockNextBatch(10, OffsetDateTime.now(), OffsetDateTime.MIN);
        assertThat(locked).singleElement().satisfies(job -> {
            assertThat(job.jobId()).isEqualTo(newJobId);
            assertThat(job.status()).isEqualTo(AggregateSnapshotJobStatus.PROCESSING);
            // JSONB normalises whitespace; use a substring check for the value.
            assertThat(job.serializedSnapshot()).contains("\"fixed\"");
            assertThat(job.serializedSnapshot()).doesNotContain("broken");
            assertThat(job.attempts()).isEqualTo(1);
            assertThat(job.lastError()).isNull();
        });
    }

    @Test
    void enqueue_does_not_replace_a_pending_or_processing_row_for_the_same_aggregate_event_order() {
        var existingJobId = UUID.randomUUID();
        var createdAt = OffsetDateTime.now().minusMinutes(1);
        repository.enqueue(new AggregateSnapshotJob(existingJobId,
                                                    "Orders",
                                                    "order-keep",
                                                    TestAggregate.class.getName(),
                                                    7L,
                                                    "{\"snapshot\":\"first\"}",
                                                    false,
                                                    List.of(),
                                                    createdAt,
                                                    createdAt,
                                                    0,
                                                    AggregateSnapshotJobStatus.PENDING,
                                                    null));

        repository.enqueue(new AggregateSnapshotJob(UUID.randomUUID(),
                                                    "Orders",
                                                    "order-keep",
                                                    TestAggregate.class.getName(),
                                                    7L,
                                                    "{\"snapshot\":\"second\"}",
                                                    false,
                                                    List.of(),
                                                    OffsetDateTime.now(),
                                                    OffsetDateTime.now(),
                                                    0,
                                                    AggregateSnapshotJobStatus.PENDING,
                                                    null));

        var locked = repository.lockNextBatch(10, OffsetDateTime.now(), OffsetDateTime.MIN);
        assertThat(locked).singleElement().satisfies(job -> {
            assertThat(job.jobId()).isEqualTo(existingJobId);
            assertThat(job.serializedSnapshot()).contains("\"first\"");
            assertThat(job.serializedSnapshot()).doesNotContain("second");
        });
    }

    @Test
    void records_queue_depth_gauges_by_status() {
        var meterRegistry = new SimpleMeterRegistry();
        repository = new PostgresqlAggregateSnapshotJobRepository(unitOfWorkFactory,
                                                                  java.util.Optional.empty(),
                                                                  java.util.Optional.of(meterRegistry));
        var jobId = UUID.randomUUID();
        var createdAt = OffsetDateTime.now().minusMinutes(1);
        var firstRetryAt = OffsetDateTime.now().plusSeconds(5);
        var job = new AggregateSnapshotJob(jobId,
                                           "Orders",
                                           "order-1",
                                           TestAggregate.class.getName(),
                                           7L,
                                           "{\"snapshot\":true}",
                                           false,
                                           List.of(1L, 3L),
                                           createdAt,
                                           createdAt,
                                           0,
                                           AggregateSnapshotJobStatus.PENDING,
                                           null);

        repository.enqueue(job);

        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.PENDING)).isEqualTo(1.0d);
        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.PROCESSING)).isZero();
        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.FAILED)).isZero();

        repository.lockNextBatch(10, OffsetDateTime.now());

        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.PENDING)).isZero();
        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.PROCESSING)).isEqualTo(1.0d);
        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.FAILED)).isZero();

        repository.markFailed(jobId, "boom", firstRetryAt);

        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.PENDING)).isZero();
        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.PROCESSING)).isZero();
        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.FAILED)).isEqualTo(1.0d);

        repository.markCompleted(jobId);

        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.PENDING)).isZero();
        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.PROCESSING)).isZero();
        assertThat(queueDepthGauge(meterRegistry, AggregateSnapshotJobStatus.FAILED)).isZero();
    }

    private double queueDepthGauge(SimpleMeterRegistry meterRegistry, AggregateSnapshotJobStatus status) {
        return meterRegistry.find(AggregateSnapshotDurableQueueMeasurementSupport.METRIC_PREFIX + ".queue_depth")
                            .tag("status", status.name())
                            .gauge()
                            .value();
    }

    private static final class TestAggregate {
    }

    /**
     * The table and both of its indexes are created in one bootstrap-locked transaction. Losing an index would not fail
     * any functional test — both only affect the plan lockNextBatch gets — so assert they are actually there, and that
     * constructing the repository again over an existing table stays idempotent, as a restart does.
     */
    @Test
    void creates_the_table_and_its_indexes_and_is_idempotent_across_restarts() {
        new PostgresqlAggregateSnapshotJobRepository(unitOfWorkFactory);

        var indexNames = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                                    .createQuery("SELECT indexname FROM pg_indexes WHERE tablename = :table_name")
                                                                    .bind("table_name", PostgresqlAggregateSnapshotJobRepository.DEFAULT_TABLE_NAME)
                                                                    .mapTo(String.class)
                                                                    .list());

        assertThat(indexNames).contains(PostgresqlAggregateSnapshotJobRepository.DEFAULT_TABLE_NAME + "_pending_idx",
                                        PostgresqlAggregateSnapshotJobRepository.DEFAULT_TABLE_NAME + "_processing_idx");
    }
}
