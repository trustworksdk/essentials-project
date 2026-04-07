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
import java.util.List;
import java.util.UUID;

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
}
