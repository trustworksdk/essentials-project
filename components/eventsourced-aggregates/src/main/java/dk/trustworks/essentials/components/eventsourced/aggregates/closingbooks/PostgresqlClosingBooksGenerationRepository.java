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

import org.postgresql.util.PSQLException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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

    /**
     * Classifier for the two-argument advisory-lock key space used by {@link #withGenerationLock}. Postgres keeps the
     * one-argument and two-argument advisory-lock spaces separate, so this cannot collide with
     * {@link PostgresqlUtil#ESSENTIALS_BOOTSTRAP_LOCK_KEY}; within the two-argument space this classifier separates
     * closing-books rollovers from an application's own keys. 0xE55E is the same "ESSE" marker the bootstrap key uses.
     */
    private static final int GENERATION_LOCK_CLASSIFIER = 0xE55E_C10B;

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final String                                                        tableName;
    private final ClosingBooksLogicalAggregateIdSerializer<ID>                  logicalAggregateIdSerializer;
    private final String                                                        oneOpenGenerationIndexName;
    /** Postgres' default name for the unnamed PRIMARY KEY declared by {@link #initializeStorage()}. */
    private final String                                                        primaryKeyName;

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
        this.oneOpenGenerationIndexName = this.tableName + "_one_open_idx";
        this.primaryKeyName = this.tableName + "_pkey";
        initializeStorage();
    }

    private void initializeStorage() {
        PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
        // Derived, so it can exceed PostgresqlUtil.MAX_IDENTIFIER_LENGTH even when the table name does not. Postgres
        // would silently truncate it to 63 characters, and two long table names could then derive the same index name.
        PostgresqlUtil.checkIsValidTableOrColumnName(oneOpenGenerationIndexName);
        // One transaction, holding the framework's bootstrap lock: CREATE / ALTER ... IF NOT EXISTS is not atomic
        // against concurrent sessions, so two JVMs starting together can both see "doesn't exist" and one fails on a
        // duplicate catalog entry. See PostgresqlUtil#acquireBootstrapLock. Keeping the table, its column additions and
        // its indexes in the same transaction also means a partially created table is never left behind.
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            PostgresqlUtil.acquireBootstrapLock(uow.handle());
            uow.handle().execute(bind("""
                                      CREATE TABLE IF NOT EXISTS {:tableName} (
                                          aggregate_type TEXT NOT NULL,
                                          logical_aggregate_id TEXT NOT NULL,
                                          generation BIGINT NOT NULL,
                                          stream_aggregate_id TEXT NOT NULL,
                                          state TEXT NOT NULL,
                                          opened_ts TIMESTAMP WITH TIME ZONE NOT NULL,
                                          closed_ts TIMESTAMP WITH TIME ZONE,
                                          next_scan_ts TIMESTAMP WITH TIME ZONE,
                                          PRIMARY KEY (aggregate_type, logical_aggregate_id, generation),
                                          UNIQUE (aggregate_type, stream_aggregate_id)
                                      )
                                      """, arg("tableName", tableName)));
            // Added after the table shipped without it, so existing installations get it here rather than only via
            // CREATE TABLE. NULL means "eligible for scanning now", which is what every pre-existing row should be.
            uow.handle().execute(bind("""
                                      ALTER TABLE {:tableName}
                                      ADD COLUMN IF NOT EXISTS next_scan_ts TIMESTAMP WITH TIME ZONE
                                      """, arg("tableName", tableName)));
            uow.handle().execute(bind("""
                                      CREATE UNIQUE INDEX IF NOT EXISTS {:indexName}
                                      ON {:tableName} (aggregate_type, logical_aggregate_id)
                                      WHERE state = 'OPEN'
                                      """,
                                      arg("indexName", oneOpenGenerationIndexName),
                                      arg("tableName", tableName)));
        });
        log.info("Ensured that closing books generation table '{}' exists", tableName);
    }

    /**
     * Serialized with a transaction-scoped advisory lock on the logical aggregate, so concurrent rollovers of the same
     * logical aggregate queue instead of racing. The lock is released when the surrounding transaction ends.
     * <p>
     * {@code hashtext} means two different logical aggregate ids can share a lock key, which costs contention but
     * never correctness. The partial unique index remains the authority on the one-open-generation invariant, for
     * callers that bypass this method entirely.
     */
    @Override
    public <R> R withGenerationLock(AggregateType aggregateType,
                                    LogicalAggregateId<ID> logicalAggregateId,
                                    Supplier<R> rollover) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(rollover, "No rollover provided");

        return unitOfWorkFactory.withUnitOfWork(uow -> {
            uow.handle().execute("SELECT pg_advisory_xact_lock(?, hashtext(?))",
                                 GENERATION_LOCK_CLASSIFIER,
                                 aggregateType.value() + "/" + serializeLogicalAggregateId(logicalAggregateId));
            return rollover.get();
        });
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
        return loadOpenGenerations(aggregateType, limit, null);
    }

    @Override
    public List<AggregateGeneration<ID>> loadOpenGenerations(AggregateType aggregateType,
                                                             int limit,
                                                             OffsetDateTime eligibleAt) {
        requireNonNull(aggregateType, "No aggregateType provided");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }

        // Built in two shapes rather than binding a nullable :eligible_at, because Postgres cannot infer the type of a
        // NULL parameter that only ever appears in an IS NULL test.
        var eligibilityFilter = eligibleAt != null
                                ? "AND (next_scan_ts IS NULL OR next_scan_ts <= :eligible_at)"
                                : "";
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var query = uow.handle().createQuery(bind("""
                                                      SELECT *
                                                      FROM {:tableName}
                                                      WHERE aggregate_type = :aggregate_type
                                                        AND state = :open_state
                                                        {:eligibilityFilter}
                                                      ORDER BY opened_ts ASC, generation ASC
                                                      LIMIT :limit
                                                      """,
                                                      arg("tableName", tableName),
                                                      arg("eligibilityFilter", eligibilityFilter)))
                           .bind("aggregate_type", aggregateType.value())
                           .bind("open_state", GenerationState.OPEN.name())
                           .bind("limit", limit);
            if (eligibleAt != null) {
                query = query.bind("eligible_at", eligibleAt);
            }
            return query.map((rs, ctx) -> mapAggregateGeneration(rs,
                                                                 aggregateType,
                                                                 logicalAggregateIdSerializer.deserialize(rs.getString("logical_aggregate_id"))))
                        .list();
        });
    }

    @Override
    public void deferScan(AggregateType aggregateType,
                          LogicalAggregateId<ID> logicalAggregateId,
                          OffsetDateTime nextScanTs) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(nextScanTs, "No nextScanTs provided");

        // Scoped to the open row: a generation closed in the meantime needs no deferral, and updating 0 rows is a
        // valid outcome rather than an error.
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate(bind("""
                                                                               UPDATE {:tableName}
                                                                               SET next_scan_ts = :next_scan_ts
                                                                               WHERE aggregate_type = :aggregate_type
                                                                                 AND logical_aggregate_id = :logical_aggregate_id
                                                                                 AND state = :open_state
                                                                               """, arg("tableName", tableName)))
                                                    .bind("next_scan_ts", nextScanTs)
                                                    .bind("aggregate_type", aggregateType.value())
                                                    .bind("logical_aggregate_id", serializeLogicalAggregateId(logicalAggregateId))
                                                    .bind("open_state", GenerationState.OPEN.name())
                                                    .execute());
    }

    @Override
    public AggregateGeneration<ID> openNextGeneration(AggregateType aggregateType,
                                                      LogicalAggregateId<ID> logicalAggregateId,
                                                      ClosingBooksStreamIdGenerator<ID> streamIdGenerator) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(streamIdGenerator, "No streamIdGenerator provided");

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
                var streamAggregateId = requireNonNull(streamIdGenerator.generate(aggregateType, logicalAggregateId, nextGeneration),
                                                       "streamIdGenerator returned no streamAggregateId");
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
            // The check above and the insert are one transaction but not one atomic step: at READ COMMITTED a
            // concurrent opener is invisible until it commits, so both can pass the check and the partial unique index
            // rejects the loser. That is the same condition the check reports, so report it the same way instead of
            // letting a driver-level constraint violation escape to a caller that is documented to get an
            // IllegalStateException. withGenerationLock avoids the race for callers that use it; this covers the rest.
            if (lostRaceToOpenGeneration(e)) {
                throw new IllegalStateException(msg("AggregateType '{}' with logicalAggregateId '{}' already has an open generation",
                                                    aggregateType,
                                                    logicalAggregateId),
                                                e);
            }
            throw e;
        }
    }

    /**
     * Whether {@code throwable} says a concurrent caller got there first with its own open generation.
     * <p>
     * Which constraint catches the loser depends on how the two callers raced. Two openers starting from the same state
     * both compute the same {@code MAX(generation) + 1} and collide on the primary key; an opener racing a
     * close-and-open, where the generation numbers differ, collides on the one-open-generation partial index. Both mean
     * the same thing to the caller. The table's remaining unique constraint, on
     * {@code (aggregate_type, stream_aggregate_id)}, does not: reusing a stream aggregate id is a caller mistake and
     * has to keep surfacing as itself.
     */
    private boolean lostRaceToOpenGeneration(Throwable throwable) {
        for (var cause = throwable; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof PSQLException psqlException && "23505".equals(psqlException.getSQLState())) {
                var serverErrorMessage = psqlException.getServerErrorMessage();
                var violatedConstraint = serverErrorMessage != null ? serverErrorMessage.getConstraint() : null;
                if (violatedConstraint != null) {
                    return oneOpenGenerationIndexName.equals(violatedConstraint) || primaryKeyName.equals(violatedConstraint);
                }
                // Not every driver path populates the structured server message; fall back to the text.
                var message = psqlException.getMessage();
                return message != null && (message.contains(oneOpenGenerationIndexName) || message.contains(primaryKeyName));
            }
        }
        return false;
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
