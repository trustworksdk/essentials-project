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
package dk.trustworks.essentials.components.foundation.postgresql;

import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.network.Network;
import org.jdbi.v3.core.*;
import org.slf4j.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The {@code create} {@link SchemaApplier} for PostgreSQL: executes the described schema and records every change in
 * a schema-history ledger. The behaviour every Essentials release before the harness had, plus the ledger.
 * <p>
 * <b>What runs.</b> A {@link SchemaChange#repeatable() repeatable} change runs on every apply. A one-shot change runs
 * once per {@code (module, changeId, objectName)}; afterwards its ledger row skips it. A one-shot change whose
 * statements no longer match its recorded checksum fails the whole apply - with both checksums - before anything
 * executes: it was edited after it shipped, and neither re-running nor skipping it is safe.
 * <p>
 * <b>Concurrency.</b> Each transaction takes {@link PostgresqlUtil#acquireBootstrapLock(Handle)} first, so JVMs
 * starting at the same time serialise instead of racing {@code CREATE ... IF NOT EXISTS}. A contributor never has to
 * take the lock itself.
 * <p>
 * <b>Transactions.</b> The ledger table is created in a transaction of its own. Then each {@link SchemaChangeSet} -
 * one contributor's changes - runs in one transaction together with its ledger rows, so a contributor's schema and
 * its record commit or roll back together.
 * <p>
 * <b>Identifiers.</b> Every {@link SchemaChange#objectName()} is checked with
 * {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} before any statement executes. It is a first line of
 * defence, not a guarantee - see that method.
 */
public final class PostgresqlCreateSchemaApplier implements SchemaApplier {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlCreateSchemaApplier.class);

    /** The ledger table's name unless configured otherwise */
    public static final String DEFAULT_SCHEMA_HISTORY_TABLE_NAME = "essentials_schema_history";

    private final Transactions transactions;
    private final String       schemaHistoryTableName;
    private final String       appliedBy;

    /**
     * Records into {@link #DEFAULT_SCHEMA_HISTORY_TABLE_NAME}, as this machine's host name.
     *
     * @param jdbi where the schema is created
     */
    public PostgresqlCreateSchemaApplier(Jdbi jdbi) {
        this(jdbi, DEFAULT_SCHEMA_HISTORY_TABLE_NAME, Network.hostName());
    }

    /**
     * @param jdbi                   where the schema is created
     * @param schemaHistoryTableName the ledger table. <b>Concatenated into SQL</b>: validated with
     *                               {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)}, but only ever pass a
     *                               name from a trusted source
     * @param appliedBy              recorded with every ledger row, for forensics only - typically the host or instance
     */
    public PostgresqlCreateSchemaApplier(Jdbi jdbi, String schemaHistoryTableName, String appliedBy) {
        this(Transactions.of(requireNonNull(jdbi, "No jdbi provided")), schemaHistoryTableName, appliedBy);
    }

    /**
     * Records into {@link #DEFAULT_SCHEMA_HISTORY_TABLE_NAME}, as this machine's host name. Each transaction is a
     * {@link HandleAwareUnitOfWork} - which joins one already active on the calling thread, as every Essentials
     * component's DDL always has.
     *
     * @param unitOfWorkFactory where the schema is created
     */
    public PostgresqlCreateSchemaApplier(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(Transactions.of(requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided")), DEFAULT_SCHEMA_HISTORY_TABLE_NAME, Network.hostName());
    }

    /**
     * Apply one contributor's own schema, with this applier - what a component does in
     * {@link SchemaOwnership#COMPONENT} mode.
     *
     * @param jdbi        where the schema is created
     * @param contributor the component
     */
    public static void applyOwnSchema(Jdbi jdbi, EssentialsSchemaContributor contributor) {
        applyOwnSchema(new PostgresqlCreateSchemaApplier(jdbi), contributor);
    }

    /**
     * Apply one contributor's own schema, with this applier - what a component does in
     * {@link SchemaOwnership#COMPONENT} mode.
     *
     * @param unitOfWorkFactory where the schema is created
     * @param contributor       the component
     */
    public static void applyOwnSchema(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory, EssentialsSchemaContributor contributor) {
        applyOwnSchema(new PostgresqlCreateSchemaApplier(unitOfWorkFactory), contributor);
    }

    private static void applyOwnSchema(PostgresqlCreateSchemaApplier applier, EssentialsSchemaContributor contributor) {
        new EssentialsSchemaHarness(applier, SchemaContext.empty(), List.of(requireNonNull(contributor, "No contributor provided"))).apply();
    }

    private PostgresqlCreateSchemaApplier(Transactions transactions, String schemaHistoryTableName, String appliedBy) {
        this.transactions = transactions;
        this.schemaHistoryTableName = requireNonBlank(schemaHistoryTableName, "No schemaHistoryTableName provided").toLowerCase(Locale.ROOT);
        PostgresqlUtil.checkIsValidTableOrColumnName(this.schemaHistoryTableName);
        this.appliedBy = requireNonBlank(appliedBy, "No appliedBy provided");
    }

    public String getSchemaHistoryTableName() {
        return schemaHistoryTableName;
    }

    @Override
    public void apply(List<SchemaChangeSet> changeSets) {
        requireNonNull(changeSets, "No changeSets provided");
        changeSets.forEach(changeSet -> changeSet.changes().forEach(change -> PostgresqlUtil.checkIsValidTableOrColumnName(change.objectName())));

        transactions.inTransaction(handle -> {
            PostgresqlUtil.acquireBootstrapLock(handle);
            handle.execute("CREATE TABLE IF NOT EXISTS " + schemaHistoryTableName + " (\n" +
                                   "    module_id   TEXT        NOT NULL,\n" +
                                   "    change_id   TEXT        NOT NULL,\n" +
                                   "    object_name TEXT        NOT NULL,\n" +
                                   "    checksum    TEXT        NOT NULL,\n" +
                                   "    applied_ts  TIMESTAMPTZ NOT NULL,\n" +
                                   "    applied_by  TEXT        NOT NULL,\n" +
                                   "    PRIMARY KEY (module_id, change_id, object_name)\n" +
                                   ")");
        });

        var recorded = readLedger();
        rejectEditedOneShotChanges(changeSets, recorded);

        for (var changeSet : changeSets) {
            transactions.inTransaction(handle -> {
                PostgresqlUtil.acquireBootstrapLock(handle);
                // Re-read under the lock: another JVM may have applied this set while we waited for it
                var current = readLedger(handle, changeSet.moduleId());
                var executed = 0;
                for (var change : changeSet.changes()) {
                    var checksum      = change.checksum();
                    var recordedEntry = current.get(new ChangeKey(changeSet.moduleId(), change.changeId(), change.objectName()));
                    if (!change.repeatable() && recordedEntry != null) {
                        continue;
                    }
                    for (var statement : change.statements()) {
                        execute(handle, changeSet.moduleId(), change, statement);
                    }
                    executed++;
                    record(handle, changeSet.moduleId(), change, checksum, recordedEntry != null);
                }
                log.debug("[{}] {} of {} schema change(s) executed", changeSet.moduleId(), executed, changeSet.changes().size());
            });
        }
    }

    /**
     * Straight to JDBC: contributor statements are raw DDL - PL/pgSQL bodies, {@code ::} casts - and must not be parsed
     * for Jdbi's {@code :name} or {@code ?} parameters.
     */
    private static void execute(Handle handle, String moduleId, SchemaChange change, String statement) {
        try (var jdbcStatement = handle.getConnection().createStatement()) {
            jdbcStatement.execute(statement);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(msg("'{}' schema change '{}' on '{}' failed: {}", moduleId, change.changeId(), change.objectName(), statement), e);
        }
    }

    private Map<ChangeKey, String> readLedger() {
        return transactions.withHandle(handle -> readLedger(handle, null));
    }

    private Map<ChangeKey, String> readLedger(Handle handle, String onlyModuleId) {
        var query = handle.createQuery("SELECT module_id, change_id, object_name, checksum FROM " + schemaHistoryTableName +
                                               (onlyModuleId != null ? " WHERE module_id = :moduleId" : ""));
        if (onlyModuleId != null) {
            query.bind("moduleId", onlyModuleId);
        }
        var result = new HashMap<ChangeKey, String>();
        query.map((rs, ctx) -> Map.entry(new ChangeKey(rs.getString("module_id"), rs.getString("change_id"), rs.getString("object_name")),
                                         rs.getString("checksum")))
             .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static void rejectEditedOneShotChanges(List<SchemaChangeSet> changeSets, Map<ChangeKey, String> recorded) {
        var edited = new ArrayList<String>();
        for (var changeSet : changeSets) {
            for (var change : changeSet.changes()) {
                if (change.repeatable()) {
                    continue;
                }
                var recordedChecksum = recorded.get(new ChangeKey(changeSet.moduleId(), change.changeId(), change.objectName()));
                if (recordedChecksum != null && !recordedChecksum.equals(change.checksum())) {
                    edited.add(msg("'{}' change '{}' on '{}': recorded checksum {}, now {}",
                                   changeSet.moduleId(), change.changeId(), change.objectName(), recordedChecksum, change.checksum()));
                }
            }
        }
        if (!edited.isEmpty()) {
            throw new IllegalStateException(msg("One-shot schema change(s) were edited after they were applied - refusing to start, " +
                                                "as neither re-running nor skipping them is safe. Ship the new statements as a new change id instead:\n  {}",
                                                String.join("\n  ", edited)));
        }
    }

    private void record(Handle handle, String moduleId, SchemaChange change, String checksum, boolean alreadyRecorded) {
        if (alreadyRecorded) {
            // A repeatable change: keep the first applied_ts, track the statements it runs now
            handle.createUpdate("UPDATE " + schemaHistoryTableName + " SET checksum = :checksum, applied_by = :appliedBy " +
                                        "WHERE module_id = :moduleId AND change_id = :changeId AND object_name = :objectName")
                  .bind("checksum", checksum)
                  .bind("appliedBy", appliedBy)
                  .bind("moduleId", moduleId)
                  .bind("changeId", change.changeId())
                  .bind("objectName", change.objectName())
                  .execute();
            return;
        }
        handle.createUpdate("INSERT INTO " + schemaHistoryTableName + " (module_id, change_id, object_name, checksum, applied_ts, applied_by) " +
                                    "VALUES (:moduleId, :changeId, :objectName, :checksum, :appliedTs, :appliedBy)")
              .bind("moduleId", moduleId)
              .bind("changeId", change.changeId())
              .bind("objectName", change.objectName())
              .bind("checksum", checksum)
              .bind("appliedTs", OffsetDateTime.now())
              .bind("appliedBy", appliedBy)
              .execute();
    }

    private record ChangeKey(String moduleId, String changeId, String objectName) {
    }

    /**
     * The two ways Essentials components reach the database: a plain {@link Jdbi}, or a unit-of-work factory.
     */
    private interface Transactions {
        void inTransaction(Consumer<Handle> work);

        <R> R withHandle(Function<Handle, R> work);

        static Transactions of(Jdbi jdbi) {
            return new Transactions() {
                @Override
                public void inTransaction(Consumer<Handle> work) {
                    jdbi.useTransaction(work::accept);
                }

                @Override
                public <R> R withHandle(Function<Handle, R> work) {
                    return jdbi.withHandle(work::apply);
                }
            };
        }

        static Transactions of(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
            return new Transactions() {
                @Override
                public void inTransaction(Consumer<Handle> work) {
                    unitOfWorkFactory.usingUnitOfWork(unitOfWork -> work.accept(unitOfWork.handle()));
                }

                @Override
                public <R> R withHandle(Function<Handle, R> work) {
                    return unitOfWorkFactory.withUnitOfWork(unitOfWork -> work.apply(unitOfWork.handle()));
                }
            };
        }
    }
}
