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

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.slf4j.*;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A PostgreSQL implementation of the {@link AggregateSnapshotJobRepository}.
 * This class provides methods to enqueue, lock, process, and manage the lifecycle of
 * aggregate snapshot jobs using a PostgreSQL database as the storage backend.
 * It also supports optional integration with a metrics system for monitoring purposes.
 */
public class PostgresqlAggregateSnapshotJobRepository implements AggregateSnapshotJobRepository {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlAggregateSnapshotJobRepository.class);
    public static final String DEFAULT_TABLE_NAME = "aggregate_snapshot_jobs";

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final String                                                        tableName;
    private final AggregateSnapshotDurableQueueMeasurementSupport               measurementSupport;
    private final String                                                        pendingIndexName;
    private final String                                                        processingIndexName;

    public PostgresqlAggregateSnapshotJobRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(unitOfWorkFactory, Optional.empty());
    }

    /**
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public PostgresqlAggregateSnapshotJobRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                    Optional<String> tableName) {
        this(unitOfWorkFactory, tableName, Optional.empty());
    }

    /**
     * Constructs a new instance of {@code PostgresqlAggregateSnapshotJobRepository}.
     *
     * @param unitOfWorkFactory       The factory responsible for creating and managing {@link HandleAwareUnitOfWork} instances. Must not be null.
     * @param tableName               An optional custom table name to use for storing aggregate snapshot jobs. Defaults to a predefined name if not provided.
     * @param meterRegistryOptional   An optional {@link MeterRegistry} for metric collection and monitoring. Can be empty if metric support is not required.
     * @throws IllegalArgumentException If {@code unitOfWorkFactory} or {@code tableName} are null.
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Deprecated(forRemoval = true, since = "0.40.x")
    public PostgresqlAggregateSnapshotJobRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                    Optional<String> tableName,
                                                    Optional<MeterRegistry> meterRegistryOptional) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.tableName = requireNonNull(tableName, "No tableName provided").orElse(DEFAULT_TABLE_NAME).toLowerCase();
        this.measurementSupport = new AggregateSnapshotDurableQueueMeasurementSupport(meterRegistryOptional);
        this.pendingIndexName = this.tableName + "_pending_idx";
        this.processingIndexName = this.tableName + "_processing_idx";
        initializeStorage();
        registerQueueDepthGauges();
    }

    private void initializeStorage() {
        PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
        // Derived, so they can exceed PostgresqlUtil.MAX_IDENTIFIER_LENGTH even when the table name does not. Postgres
        // would silently truncate them to 63 characters, and two long table names could then derive the same index name.
        PostgresqlUtil.checkIsValidTableOrColumnName(pendingIndexName);
        PostgresqlUtil.checkIsValidTableOrColumnName(processingIndexName);
        // One transaction, holding the framework's bootstrap lock: CREATE ... IF NOT EXISTS is not atomic against
        // concurrent sessions, so two JVMs starting together can both see "doesn't exist" and one fails on a duplicate
        // catalog entry. See PostgresqlUtil#acquireBootstrapLock.
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            PostgresqlUtil.acquireBootstrapLock(uow.handle());
            uow.handle().execute("CREATE TABLE IF NOT EXISTS " + tableName + " (\n" +
                                                                             "job_id UUID PRIMARY KEY,\n" +
                                                                             "aggregate_type TEXT NOT NULL,\n" +
                                                                             "aggregate_id TEXT NOT NULL,\n" +
                                                                             "aggregate_impl_type TEXT NOT NULL,\n" +
                                                                             "last_included_event_order BIGINT NOT NULL,\n" +
                                                                             "snapshot JSONB NOT NULL,\n" +
                                                                             "delete_all_existing_snapshots BOOLEAN NOT NULL,\n" +
                                                                             "snapshot_event_orders_to_delete BIGINT[],\n" +
                                                                             "created_ts TIMESTAMP WITH TIME ZONE NOT NULL,\n" +
                                                                             "next_attempt_ts TIMESTAMP WITH TIME ZONE NOT NULL,\n" +
                                                                             "processing_started_ts TIMESTAMP WITH TIME ZONE,\n" +
                                                                             "attempts INT NOT NULL,\n" +
                                                                             "status TEXT NOT NULL,\n" +
                                                                             "last_error TEXT,\n" +
                                                                             "UNIQUE (aggregate_type, aggregate_impl_type, aggregate_id, last_included_event_order)\n" +
                                                                             ")");
            // Hot-path index for the PENDING/FAILED branch of `lockNextBatch`. The third column
            // (`created_ts`) covers the ORDER BY so Postgres can yield rows in queue order
            // without an external sort step.
            uow.handle().execute("CREATE INDEX IF NOT EXISTS " + pendingIndexName + " ON " + tableName + " (status, next_attempt_ts, created_ts)");
            // Recovery-path partial index for the PROCESSING reclaim branch. Small in steady
            // state (PROCESSING rows are short-lived) and supports `processing_started_ts <= ...`
            // ordered by `created_ts`.
            uow.handle().execute("CREATE INDEX IF NOT EXISTS " + processingIndexName + " ON " + tableName + " (processing_started_ts, created_ts) WHERE status = 'PROCESSING'");
        });
        log.info("Ensured that aggregate snapshot job table '{}' exists", tableName);
    }

    @Override
    public void enqueue(AggregateSnapshotJob job) {
        requireNonNull(job, "No job provided");
        // Insert. On a unique-key conflict (aggregate_type, aggregate_impl_type, aggregate_id, last_included_event_order):
        //   - If the existing row is PARKED, replace its payload and reset its retry state — operators
        //     that produce a corrected payload after parking the previous attempt can re-enqueue safely.
        //   - For any other status (PENDING / PROCESSING / FAILED), keep the existing row to avoid
        //     racing with an in-flight attempt or losing accumulated retry/processing-started bookkeeping.
        measurementSupport.recordEnqueue(job,
                                         () -> unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("INSERT INTO " + tableName + " (\n" +
                                                                                                                           "job_id, aggregate_type, aggregate_id, aggregate_impl_type, last_included_event_order,\n" +
                                                                                                                           "snapshot, delete_all_existing_snapshots, snapshot_event_orders_to_delete,\n" +
                                                                                                                           "created_ts, next_attempt_ts, attempts, status, last_error)\n" +
                                                                                                                           "VALUES (\n" +
                                                                                                                           ":job_id, :aggregate_type, :aggregate_id, :aggregate_impl_type, :last_included_event_order,\n" +
                                                                                                                           ":snapshot::jsonb, :delete_all_existing_snapshots, :snapshot_event_orders_to_delete,\n" +
                                                                                                                           ":created_ts, :next_attempt_ts, :attempts, :status, :last_error)\n" +
                                                                                                                           "ON CONFLICT (aggregate_type, aggregate_impl_type, aggregate_id, last_included_event_order)\n" +
                                                                                                                           "DO UPDATE SET\n" +
                                                                                                                           "    job_id = EXCLUDED.job_id,\n" +
                                                                                                                           "    snapshot = EXCLUDED.snapshot,\n" +
                                                                                                                           "    delete_all_existing_snapshots = EXCLUDED.delete_all_existing_snapshots,\n" +
                                                                                                                           "    snapshot_event_orders_to_delete = EXCLUDED.snapshot_event_orders_to_delete,\n" +
                                                                                                                           "    created_ts = EXCLUDED.created_ts,\n" +
                                                                                                                           "    next_attempt_ts = EXCLUDED.next_attempt_ts,\n" +
                                                                                                                           "    attempts = 0,\n" +
                                                                                                                           "    processing_started_ts = NULL,\n" +
                                                                                                                           "    status = EXCLUDED.status,\n" +
                                                                                                                           "    last_error = NULL\n" +
                                                                                                                           "WHERE " + tableName + ".status = 'PARKED'")
                                                                                                .bind("job_id", job.jobId())
                                                                                                .bind("aggregate_type", job.aggregateType())
                                                                                                .bind("aggregate_id", job.serializedAggregateId())
                                                                                                .bind("aggregate_impl_type", job.aggregateImplementationType())
                                                                                                .bind("last_included_event_order", job.lastIncludedEventOrder())
                                                                                                .bind("snapshot", job.serializedSnapshot())
                                                                                                .bind("delete_all_existing_snapshots", job.deleteAllExistingSnapshots())
                                                                                                .bindArray("snapshot_event_orders_to_delete", Long.class, job.snapshotEventOrdersToDelete())
                                                                                                .bind("created_ts", job.createdTs())
                                                                                                .bind("next_attempt_ts", job.nextAttemptTs())
                                                                                                .bind("attempts", job.attempts())
                                                                                                .bind("status", job.status().name())
                                                                                                .bind("last_error", job.lastError())
                                                                                                .execute()));
    }

    @Override
    public List<AggregateSnapshotJob> lockNextBatch(int batchSize, OffsetDateTime now, OffsetDateTime reclaimStaleStartedBefore) {
        var jobs = measurementSupport.recordLockNextBatch(batchSize,
                                                          () -> unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("WITH next_jobs AS (\n" +
                                                                                                                                          "    SELECT job_id FROM " + tableName + "\n" +
                                                                                                                                          "    WHERE (status IN ('PENDING', 'FAILED') AND next_attempt_ts <= :now)\n" +
                                                                                                                                          "       OR (status = 'PROCESSING' AND (processing_started_ts IS NULL OR processing_started_ts < :reclaim_stale_started_before))\n" +
                                                                                                                                          "    ORDER BY created_ts\n" +
                                                                                                                                          "    LIMIT :batch_size\n" +
                                                                                                                                          "    FOR UPDATE SKIP LOCKED\n" +
                                                                                                                                          ")\n" +
                                                                                                                                          "UPDATE " + tableName + " j\n" +
                                                                                                                                          "SET status = 'PROCESSING',\n" +
                                                                                                                                          "    attempts = j.attempts + 1,\n" +
                                                                                                                                          "    processing_started_ts = :now,\n" +
                                                                                                                                          "    last_error = NULL\n" +
                                                                                                                                          "FROM next_jobs\n" +
                                                                                                                                          "WHERE j.job_id = next_jobs.job_id\n" +
                                                                                                                                          "RETURNING j.*")
                                                                                                            .bind("now", now)
                                                                                                            .bind("reclaim_stale_started_before", reclaimStaleStartedBefore)
                                                                                                            .bind("batch_size", batchSize)
                                                                                                            .map(new AggregateSnapshotJobRowMapper())
                                                                                                            .list()));
        measurementSupport.recordLockedBatchSize(jobs.size());
        return jobs;
    }

    @Override
    public void markCompleted(UUID jobId) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("DELETE FROM " + tableName + " WHERE job_id = :job_id")
                                                          .bind("job_id", jobId)
                                                          .execute());
    }

    @Override
    public void markFailed(UUID jobId, String error, OffsetDateTime nextAttemptTs) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("UPDATE " + tableName + " SET status = 'FAILED', last_error = :last_error, next_attempt_ts = :next_attempt_ts WHERE job_id = :job_id")
                                                          .bind("job_id", jobId)
                                                          .bind("last_error", error)
                                                          .bind("next_attempt_ts", nextAttemptTs)
                                                          .execute());
    }

    @Override
    public void markParked(UUID jobId, String error, OffsetDateTime parkedAt) {
        // PARKED rows are not picked up by lockNextBatch (status filter only matches PENDING/FAILED/
        // stale-PROCESSING). The next_attempt_ts column is still set to a stable value so existing
        // queue ordering tooling continues to work, but the status gate is what keeps the row off
        // the polling path.
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("UPDATE " + tableName + " SET status = 'PARKED', last_error = :last_error, next_attempt_ts = :parked_ts, processing_started_ts = NULL WHERE job_id = :job_id")
                                                          .bind("job_id", jobId)
                                                          .bind("last_error", error)
                                                          .bind("parked_ts", parkedAt)
                                                          .execute());
    }

    private void registerQueueDepthGauges() {
        measurementSupport.registerQueueDepthGauge(AggregateSnapshotJobStatus.PENDING.name(),
                                                   () -> countJobsByStatus(AggregateSnapshotJobStatus.PENDING));
        measurementSupport.registerQueueDepthGauge(AggregateSnapshotJobStatus.PROCESSING.name(),
                                                   () -> countJobsByStatus(AggregateSnapshotJobStatus.PROCESSING));
        measurementSupport.registerQueueDepthGauge(AggregateSnapshotJobStatus.FAILED.name(),
                                                   () -> countJobsByStatus(AggregateSnapshotJobStatus.FAILED));
        measurementSupport.registerQueueDepthGauge(AggregateSnapshotJobStatus.PARKED.name(),
                                                   () -> countJobsByStatus(AggregateSnapshotJobStatus.PARKED));
    }

    private long countJobsByStatus(AggregateSnapshotJobStatus status) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                      .createQuery("SELECT count(*) FROM " + tableName + " WHERE status = :status")
                                                      .bind("status", status.name())
                                                      .mapTo(Long.class)
                                                      .one());
    }

    private static List<Long> readEventOrders(ResultSet rs) throws SQLException {
        var array = rs.getArray("snapshot_event_orders_to_delete");
        if (array == null) {
            return List.of();
        }
        var values = (Long[]) array.getArray();
        return values == null ? List.of() : List.of(values);
    }

    private final class AggregateSnapshotJobRowMapper implements RowMapper<AggregateSnapshotJob> {
        @Override
        public AggregateSnapshotJob map(ResultSet rs, StatementContext ctx) throws SQLException {
            return new AggregateSnapshotJob(UUID.fromString(rs.getString("job_id")),
                                            rs.getString("aggregate_type"),
                                            rs.getString("aggregate_id"),
                                            rs.getString("aggregate_impl_type"),
                                            rs.getLong("last_included_event_order"),
                                            rs.getString("snapshot"),
                                            rs.getBoolean("delete_all_existing_snapshots"),
                                            readEventOrders(rs),
                                            rs.getObject("created_ts", OffsetDateTime.class),
                                            rs.getObject("next_attempt_ts", OffsetDateTime.class),
                                            rs.getInt("attempts"),
                                            AggregateSnapshotJobStatus.valueOf(rs.getString("status")),
                                            rs.getString("last_error"));
        }
    }

    /**
     * Creates a builder for a {@link PostgresqlAggregateSnapshotJobRepository}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link PostgresqlAggregateSnapshotJobRepository}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload for Spring {@code @Bean} methods.
     */
    public static final class Builder {
        private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
        private String tableName;
        private MeterRegistry meterRegistryOptional;

        /**
         * @param unitOfWorkFactory required
         * @return this builder
         */
        public Builder setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            return this;
        }

        /**
         * @param tableName optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setTableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setTableName(String)}.
         *
         * @param tableName the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setTableName(Optional<String> tableName) {
            requireNonNull(tableName, "No tableName provided");
            return setTableName(tableName.orElse(null));
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
         * @return the new {@link PostgresqlAggregateSnapshotJobRepository}
         */
        @SuppressWarnings("removal")
        public PostgresqlAggregateSnapshotJobRepository build() {
            return new PostgresqlAggregateSnapshotJobRepository(unitOfWorkFactory,
                                                                Optional.ofNullable(tableName),
                                                                Optional.ofNullable(meterRegistryOptional));
        }
    }

}
