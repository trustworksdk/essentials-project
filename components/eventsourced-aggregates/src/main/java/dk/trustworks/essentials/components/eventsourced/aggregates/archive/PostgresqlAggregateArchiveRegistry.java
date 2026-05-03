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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

public class PostgresqlAggregateArchiveRegistry implements AggregateArchiveRegistry {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlAggregateArchiveRegistry.class);
    public static final String DEFAULT_TABLE_NAME = "aggregate_archives";

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final String tableName;

    public PostgresqlAggregateArchiveRegistry(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(unitOfWorkFactory, Optional.empty());
    }

    public PostgresqlAggregateArchiveRegistry(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                              Optional<String> tableName) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.tableName = requireNonNull(tableName, "No tableName provided").orElse(DEFAULT_TABLE_NAME).toLowerCase();
        initializeStorage();
    }

    private void initializeStorage() {
        PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
        unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().execute(bind("""
                                                                          CREATE TABLE IF NOT EXISTS {:tableName} (
                                                                              aggregate_type TEXT NOT NULL,
                                                                              logical_aggregate_id TEXT NOT NULL,
                                                                              generation BIGINT NOT NULL,
                                                                              stream_aggregate_id TEXT NOT NULL,
                                                                              archive_status TEXT NOT NULL,
                                                                              archive_format TEXT,
                                                                              archive_location TEXT,
                                                                              event_count BIGINT,
                                                                              checksum TEXT,
                                                                              closed_ts TIMESTAMP WITH TIME ZONE,
                                                                              archived_ts TIMESTAMP WITH TIME ZONE,
                                                                              archive_error TEXT,
                                                                              PRIMARY KEY (aggregate_type, logical_aggregate_id, generation)
                                                                          )
                                                                          """, arg("tableName", tableName))));
        unitOfWorkFactory.withUnitOfWork(uow -> {
            uow.handle().execute(bind("""
                                      CREATE INDEX IF NOT EXISTS {:indexName}
                                      ON {:tableName} (aggregate_type, archived_ts DESC)
                                      """,
                                      arg("indexName", tableName + "_aggregate_type_archived_ts_idx"),
                                      arg("tableName", tableName)));
            return null;
        });
        log.info("Ensured that aggregate archive table '{}' exists", tableName);
    }

    @Override
    public void save(AggregateArchiveEntry entry) {
        requireNonNull(entry, "No entry provided");
        unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createUpdate(bind("""
                                                                            INSERT INTO {:tableName} (
                                                                                aggregate_type,
                                                                                logical_aggregate_id,
                                                                                generation,
                                                                                stream_aggregate_id,
                                                                                archive_status,
                                                                                archive_format,
                                                                                archive_location,
                                                                                event_count,
                                                                                checksum,
                                                                                closed_ts,
                                                                                archived_ts,
                                                                                archive_error
                                                                            ) VALUES (
                                                                                :aggregate_type,
                                                                                :logical_aggregate_id,
                                                                                :generation,
                                                                                :stream_aggregate_id,
                                                                                :archive_status,
                                                                                :archive_format,
                                                                                :archive_location,
                                                                                :event_count,
                                                                                :checksum,
                                                                                :closed_ts,
                                                                                :archived_ts,
                                                                                :archive_error
                                                                            )
                                                                            ON CONFLICT (aggregate_type, logical_aggregate_id, generation)
                                                                            DO UPDATE SET
                                                                                stream_aggregate_id = EXCLUDED.stream_aggregate_id,
                                                                                archive_status = EXCLUDED.archive_status,
                                                                                archive_format = EXCLUDED.archive_format,
                                                                                archive_location = EXCLUDED.archive_location,
                                                                                event_count = EXCLUDED.event_count,
                                                                                checksum = EXCLUDED.checksum,
                                                                                closed_ts = EXCLUDED.closed_ts,
                                                                                archived_ts = EXCLUDED.archived_ts,
                                                                                archive_error = EXCLUDED.archive_error
                                                                            """, arg("tableName", tableName)))
                                                   .bind("aggregate_type", entry.aggregateType().value())
                                                   .bind("logical_aggregate_id", entry.logicalAggregateId())
                                                   .bind("generation", entry.generation())
                                                   .bind("stream_aggregate_id", entry.streamAggregateId())
                                                   .bind("archive_status", entry.status().name())
                                                   .bind("archive_format", entry.format().name())
                                                   .bind("archive_location", entry.archiveLocation())
                                                   .bind("event_count", entry.eventCount())
                                                   .bind("checksum", entry.checksum())
                                                   .bind("closed_ts", entry.closedAt())
                                                   .bind("archived_ts", entry.archivedAt())
                                                   .bind("archive_error", entry.archiveError())
                                                   .execute());
    }

    @Override
    public boolean tryClaim(AggregateType aggregateType,
                            String logicalAggregateId,
                            long generation,
                            String streamAggregateId,
                            OffsetDateTime claimedAt) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(streamAggregateId, "No streamAggregateId provided");
        requireNonNull(claimedAt, "No claimedAt provided");
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createUpdate(bind("""
                                                                                       INSERT INTO {:tableName} (
                                                                                           aggregate_type,
                                                                                           logical_aggregate_id,
                                                                                           generation,
                                                                                           stream_aggregate_id,
                                                                                           archive_status,
                                                                                           archived_ts
                                                                                       ) VALUES (
                                                                                           :aggregate_type,
                                                                                           :logical_aggregate_id,
                                                                                           :generation,
                                                                                           :stream_aggregate_id,
                                                                                           'IN_PROGRESS',
                                                                                           :claimed_ts
                                                                                       )
                                                                                       ON CONFLICT (aggregate_type, logical_aggregate_id, generation)
                                                                                       DO NOTHING
                                                                                       """, arg("tableName", tableName)))
                                                          .bind("aggregate_type", aggregateType.value())
                                                          .bind("logical_aggregate_id", logicalAggregateId)
                                                          .bind("generation", generation)
                                                          .bind("stream_aggregate_id", streamAggregateId)
                                                          .bind("claimed_ts", claimedAt)
                                                          .execute()) == 1;
    }

    @Override
    public Optional<AggregateArchiveEntry> findArchivedGeneration(AggregateType aggregateType,
                                                                  String logicalAggregateId,
                                                                  long generation) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(bind("""
                                                                                     SELECT *
                                                                                     FROM {:tableName}
                                                                                     WHERE aggregate_type = :aggregate_type
                                                                                       AND logical_aggregate_id = :logical_aggregate_id
                                                                                       AND generation = :generation
                                                                                     """, arg("tableName", tableName)))
                                                          .bind("aggregate_type", aggregateType.value())
                                                          .bind("logical_aggregate_id", logicalAggregateId)
                                                          .bind("generation", generation)
                                                          .map((rs, ctx) -> mapEntry(rs))
                                                          .findOne());
    }

    @Override
    public List<AggregateArchiveEntry> findArchivedGenerations(AggregateType aggregateType,
                                                               String logicalAggregateId) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(bind("""
                                                                                     SELECT *
                                                                                     FROM {:tableName}
                                                                                     WHERE aggregate_type = :aggregate_type
                                                                                       AND logical_aggregate_id = :logical_aggregate_id
                                                                                     ORDER BY generation ASC
                                                                                     """, arg("tableName", tableName)))
                                                          .bind("aggregate_type", aggregateType.value())
                                                          .bind("logical_aggregate_id", logicalAggregateId)
                                                          .map((rs, ctx) -> mapEntry(rs))
                                                          .list());
    }

    @Override
    public List<AggregateArchiveSummary> summarizeArchivedGenerations() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(bind("""
                                                                                     SELECT aggregate_type,
                                                                                            COUNT(*) FILTER (WHERE archive_status = 'ARCHIVED') AS archived_generation_count,
                                                                                            COUNT(*) FILTER (WHERE archive_status = 'FAILED') AS failed_generation_count,
                                                                                            COALESCE(SUM(event_count) FILTER (WHERE archive_status = 'ARCHIVED'), 0) AS total_archived_event_count,
                                                                                            MAX(archived_ts) FILTER (WHERE archive_status = 'ARCHIVED') AS last_archived_ts
                                                                                     FROM {:tableName}
                                                                                     GROUP BY aggregate_type
                                                                                     ORDER BY aggregate_type
                                                                                     """, arg("tableName", tableName)))
                                                          .map((rs, ctx) -> new AggregateArchiveSummary(AggregateType.of(rs.getString("aggregate_type")),
                                                                                                       rs.getLong("archived_generation_count"),
                                                                                                       rs.getLong("failed_generation_count"),
                                                                                                       rs.getLong("total_archived_event_count"),
                                                                                                       rs.getObject("last_archived_ts", OffsetDateTime.class)))
                                                          .list());
    }

    private AggregateArchiveEntry mapEntry(ResultSet rs) throws SQLException {
        var rawFormat = rs.getString("archive_format");
        return new AggregateArchiveEntry(AggregateType.of(rs.getString("aggregate_type")),
                                         rs.getString("logical_aggregate_id"),
                                         rs.getLong("generation"),
                                         rs.getString("stream_aggregate_id"),
                                         AggregateArchiveStatus.valueOf(rs.getString("archive_status")),
                                         rawFormat == null ? null : AggregateArchiveFormat.valueOf(rawFormat),
                                         rs.getString("archive_location"),
                                         rs.getLong("event_count"),
                                         rs.getString("checksum"),
                                         rs.getObject("closed_ts", OffsetDateTime.class),
                                         rs.getObject("archived_ts", OffsetDateTime.class),
                                         rs.getString("archive_error"));
    }
}
