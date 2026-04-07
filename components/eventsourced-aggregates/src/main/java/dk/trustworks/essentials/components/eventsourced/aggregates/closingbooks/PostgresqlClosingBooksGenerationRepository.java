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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException;
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
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Repository for managing the generation lifecycle of closing books in a PostgreSQL database.
 * Extends functionality to handle open generations across logical aggregates.
 *
 * @param <ID> The type of the identifier used for aggregate generations.
 */
public class PostgresqlClosingBooksGenerationRepository<ID> implements ClosingBooksOpenGenerationRepository<ID> {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlClosingBooksGenerationRepository.class);

    public static final String DEFAULT_TABLE_NAME = "aggregate_generations";

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final String                                                        tableName;
    private final ClosingBooksLogicalAggregateIdSerializer<ID>                  logicalAggregateIdSerializer;

    /**
     * Constructs an instance of {@code PostgresqlClosingBooksGenerationRepository} using the specified unit of work
     * factory. This constructor sets the logical aggregate ID serializer to its default implementation and uses
     * the default table name.
     *
     * @param unitOfWorkFactory the factory responsible for creating and managing {@link HandleAwareUnitOfWork}
     *                          instances; must not be null
     * @throws IllegalArgumentException if the {@code unitOfWorkFactory} parameter is null
     */
    public PostgresqlClosingBooksGenerationRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(unitOfWorkFactory,
             Optional.empty(),
             defaultLogicalAggregateIdSerializer());
    }

    /**
     * Constructs an instance of {@code PostgresqlClosingBooksGenerationRepository} with the specified unit of work
     * factory and optional table name. The logical aggregate ID serializer is set to the default implementation.
     *
     * @param unitOfWorkFactory the factory responsible for creating and managing {@link HandleAwareUnitOfWork}
     *                          instances; must not be null
     * @param tableName an optional name of the table to be used for storage; if not provided, a default table
     *                  name is used
     * @throws IllegalArgumentException if the {@code unitOfWorkFactory} parameter is null
     */
    public PostgresqlClosingBooksGenerationRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                      Optional<String> tableName) {
        this(unitOfWorkFactory,
             tableName,
             defaultLogicalAggregateIdSerializer());
    }

    /**
     * Constructs an instance of {@code PostgresqlClosingBooksGenerationRepository} with the specified unit of work
     * factory, optional table name, and logical aggregate ID serializer.
     *
     * @param unitOfWorkFactory the factory responsible for creating and managing {@link HandleAwareUnitOfWork}
     *                          instances; must not be null
     * @param tableName an optional name of the table to be used for storage; if not provided, a default table
     *                  name is used
     * @param logicalAggregateIdSerializer the serializer used for logical aggregate ID serialization and
     *                                      deserialization; must not be null
     * @throws IllegalArgumentException if any of the required parameters are null
     */
    public PostgresqlClosingBooksGenerationRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                      Optional<String> tableName,
                                                      ClosingBooksLogicalAggregateIdSerializer<ID> logicalAggregateIdSerializer) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.tableName = requireNonNull(tableName, "No tableName provided").orElse(DEFAULT_TABLE_NAME).toLowerCase();
        this.logicalAggregateIdSerializer = requireNonNull(logicalAggregateIdSerializer, "No logicalAggregateIdSerializer provided");
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
                                                                              state TEXT NOT NULL,
                                                                              opened_ts TIMESTAMP WITH TIME ZONE NOT NULL,
                                                                              closed_ts TIMESTAMP WITH TIME ZONE,
                                                                              PRIMARY KEY (aggregate_type, logical_aggregate_id, generation),
                                                                              UNIQUE (aggregate_type, stream_aggregate_id)
                                                                          )
                                                                          """, arg("tableName", tableName))));
        unitOfWorkFactory.withUnitOfWork(uow -> {
            uow.handle().execute(bind("""
                                      CREATE UNIQUE INDEX IF NOT EXISTS {:indexName}
                                      ON {:tableName} (aggregate_type, logical_aggregate_id)
                                      WHERE state = 'OPEN'
                                      """,
                                      arg("indexName", tableName + "_one_open_idx"),
                                      arg("tableName", tableName)));
            return null;
        });
        log.info("Ensured that closing books generation table '{}' exists", tableName);
    }

    @Override
    public Optional<AggregateGeneration<ID>> resolveCurrentGeneration(AggregateType aggregateType,
                                                                      LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");

        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(bind("""
                                                                                     SELECT *
                                                                                     FROM {:tableName}
                                                                                     WHERE aggregate_type = :aggregate_type
                                                                                       AND logical_aggregate_id = :logical_aggregate_id
                                                                                       AND state = 'OPEN'
                                                                                     ORDER BY generation DESC
                                                                                     LIMIT 1
                                                                                     """, arg("tableName", tableName)))
                                                          .bind("aggregate_type", aggregateType.value())
                                                          .bind("logical_aggregate_id", serializeLogicalAggregateId(logicalAggregateId))
                                                          .map((rs, ctx) -> mapAggregateGeneration(rs, aggregateType, logicalAggregateId))
                                                          .findOne());
    }

    @Override
    public List<AggregateGeneration<ID>> loadGenerations(AggregateType aggregateType,
                                                         LogicalAggregateId<ID> logicalAggregateId) {
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
                                                          .bind("logical_aggregate_id", serializeLogicalAggregateId(logicalAggregateId))
                                                          .map((rs, ctx) -> mapAggregateGeneration(rs, aggregateType, logicalAggregateId))
                                                          .list());
    }

    @Override
    public List<AggregateGeneration<ID>> loadOpenGenerations(AggregateType aggregateType,
                                                             int limit) {
        requireNonNull(aggregateType, "No aggregateType provided");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }

        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(bind("""
                                                                                     SELECT *
                                                                                     FROM {:tableName}
                                                                                     WHERE aggregate_type = :aggregate_type
                                                                                       AND state = :open_state
                                                                                     ORDER BY opened_ts ASC, generation ASC
                                                                                     LIMIT :limit
                                                                                     """, arg("tableName", tableName)))
                                                          .bind("aggregate_type", aggregateType.value())
                                                          .bind("open_state", GenerationState.OPEN.name())
                                                          .bind("limit", limit)
                                                          .map((rs, ctx) -> mapAggregateGeneration(rs,
                                                                                                   aggregateType,
                                                                                                   logicalAggregateIdSerializer.deserialize(rs.getString("logical_aggregate_id"))))
                                                          .list());
    }

    @Override
    public AggregateGeneration<ID> openNextGeneration(AggregateType aggregateType,
                                                      LogicalAggregateId<ID> logicalAggregateId,
                                                      String streamAggregateId) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(streamAggregateId, "No streamAggregateId provided");

        try {
            return unitOfWorkFactory.withUnitOfWork(uow -> {
                if (resolveCurrentGeneration(aggregateType, logicalAggregateId).isPresent()) {
                    throw new IllegalStateException(msg("AggregateType '{}' with logicalAggregateId '{}' already has an open generation",
                                                        aggregateType,
                                                        logicalAggregateId));
                }

                var nextGeneration = uow.handle().createQuery(bind("""
                                                                   SELECT COALESCE(MAX(generation), 0) + 1
                                                                   FROM {:tableName}
                                                                   WHERE aggregate_type = :aggregate_type
                                                                     AND logical_aggregate_id = :logical_aggregate_id
                                                                   """, arg("tableName", tableName)))
                                        .bind("aggregate_type", aggregateType.value())
                                        .bind("logical_aggregate_id", serializeLogicalAggregateId(logicalAggregateId))
                                        .mapTo(Long.class)
                                        .one();
                var openedAt = OffsetDateTime.now();

                return uow.handle().createQuery(bind("""
                                                     INSERT INTO {:tableName} (
                                                         aggregate_type,
                                                         logical_aggregate_id,
                                                         generation,
                                                         stream_aggregate_id,
                                                         state,
                                                         opened_ts,
                                                         closed_ts
                                                     ) VALUES (
                                                         :aggregate_type,
                                                         :logical_aggregate_id,
                                                         :generation,
                                                         :stream_aggregate_id,
                                                         :state,
                                                         :opened_ts,
                                                         :closed_ts
                                                     )
                                                     RETURNING *
                                                     """, arg("tableName", tableName)))
                          .bind("aggregate_type", aggregateType.value())
                          .bind("logical_aggregate_id", serializeLogicalAggregateId(logicalAggregateId))
                          .bind("generation", nextGeneration)
                          .bind("stream_aggregate_id", streamAggregateId)
                          .bind("state", GenerationState.OPEN.name())
                          .bind("opened_ts", openedAt)
                          .bindNull("closed_ts", java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                          .map((rs, ctx) -> mapAggregateGeneration(rs, aggregateType, logicalAggregateId))
                          .one();
            });
        } catch (UnitOfWorkException e) {
            if (e.getCause() instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw e;
        }
    }

    @Override
    public AggregateGeneration<ID> closeCurrentGeneration(AggregateType aggregateType,
                                                          LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");

        var closedAt = OffsetDateTime.now();
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(bind("""
                                                                                     UPDATE {:tableName}
                                                                                     SET state = :closed_state,
                                                                                         closed_ts = :closed_ts
                                                                                     WHERE aggregate_type = :aggregate_type
                                                                                       AND logical_aggregate_id = :logical_aggregate_id
                                                                                       AND state = :open_state
                                                                                     RETURNING *
                                                                                     """, arg("tableName", tableName)))
                                                          .bind("closed_state", GenerationState.CLOSED.name())
                                                          .bind("closed_ts", closedAt)
                                                          .bind("aggregate_type", aggregateType.value())
                                                          .bind("logical_aggregate_id", serializeLogicalAggregateId(logicalAggregateId))
                                                          .bind("open_state", GenerationState.OPEN.name())
                                                          .map((rs, ctx) -> mapAggregateGeneration(rs, aggregateType, logicalAggregateId))
                                                          .findOne()
                                                          .orElseThrow(() -> new IllegalStateException(msg("AggregateType '{}' with logicalAggregateId '{}' doesn't have an open generation to close",
                                                                                                           aggregateType,
                                                                                                           logicalAggregateId))));
    }

    private String serializeLogicalAggregateId(LogicalAggregateId<ID> logicalAggregateId) {
        return logicalAggregateIdSerializer.serialize(logicalAggregateId);
    }

    private AggregateGeneration<ID> mapAggregateGeneration(ResultSet rs,
                                                           AggregateType aggregateType,
                                                           LogicalAggregateId<ID> logicalAggregateId) throws SQLException {
        return new AggregateGeneration<>(aggregateType,
                                         logicalAggregateId,
                                         rs.getLong("generation"),
                                         rs.getString("stream_aggregate_id"),
                                         GenerationState.valueOf(rs.getString("state")),
                                         rs.getObject("opened_ts", OffsetDateTime.class),
                                         Optional.ofNullable(rs.getObject("closed_ts", OffsetDateTime.class)));
    }

    @SuppressWarnings("unchecked")
    private static <ID> ClosingBooksLogicalAggregateIdSerializer<ID> defaultLogicalAggregateIdSerializer() {
        return (ClosingBooksLogicalAggregateIdSerializer<ID>) ClosingBooksLogicalAggregateIdSerializer.stringBased();
    }
}
