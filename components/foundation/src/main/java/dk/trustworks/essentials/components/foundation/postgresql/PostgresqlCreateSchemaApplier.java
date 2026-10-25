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

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlSchemaHistory.ChangeKey;
import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.foundation.transaction.TransactionProbe;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.network.Network;
import org.jdbi.v3.core.*;
import org.slf4j.*;

import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
 * its record commit or roll back together. When PostgreSQL aborts that transaction to break a deadlock - a change that
 * really has to create an index or column on a table running instances write to locks it - the change set is applied
 * again, up to {@value #MAX_ATTEMPTS_ON_DEADLOCK} times. Only when this applier began the transaction: one that joined
 * a unit of work or transaction the caller started cannot be retried, because the abort undid the caller's work too.
 * Through a {@link Jdbi} the applier cannot tell whether a Spring transaction active on the calling thread is the one
 * its connection joins (it is for a {@code Jdbi} built on Spring's {@code TransactionAwareDataSourceProxy}), so while
 * one is active a deadlock is not retried there either.
 * <p>
 * <b>Repeatable statements that lock their table.</b> A {@link SchemaChange#repeatable() repeatable} change runs on
 * every start, against tables the instances already running keep writing to. A {@code CREATE INDEX} or
 * {@code ALTER TABLE ... ADD COLUMN} in one, not wrapped by {@link PostgresqlSchemaStatements#createIndexUnlessPresent}
 * or {@link PostgresqlSchemaStatements#addColumnUnlessPresent}, or a top-level {@code CREATE [OR REPLACE] TRIGGER},
 * locks its table even when the index, column or trigger exists
 * ({@link PostgresqlSchemaStatements#takesTableLockEvenWhenPresent(String)} tells; it does not look inside {@code DO}
 * blocks, so one that skips the catalog check is not caught). The applier runs it all the same
 * and logs a WARN, once per {@code (module, changeId, objectName)} in the JVM, naming the statement's lock and what to
 * do instead.
 * <p>
 * <b>Identifiers.</b> Every {@link SchemaChange#objectName()} is checked with
 * {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} before any statement executes. It is a first line of
 * defence, not a guarantee - see that method.
 */
public final class PostgresqlCreateSchemaApplier implements SchemaApplier {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlCreateSchemaApplier.class);

    /** How often a change set's transaction is tried when PostgreSQL aborts it to break a deadlock */
    public static final  int      MAX_ATTEMPTS_ON_DEADLOCK = 3;
    private static final String   DEADLOCK_DETECTED        = "40P01";
    private static final Duration DEADLOCK_RETRY_DELAY     = Duration.ofMillis(100);

    /** The ledger table's name unless configured otherwise */
    public static final String DEFAULT_SCHEMA_HISTORY_TABLE_NAME = "essentials_schema_history";

    /** {@code module + '\0' + changeId + '\0' + objectName} of every repeatable change already warned about in this JVM */
    private static final Set<String> WARNED_TABLE_LOCKING_CHANGES = ConcurrentHashMap.newKeySet();

    private final Transactions transactions;
    private final String       schemaHistoryTableName;
    private final String       appliedBy;

    /**
     * Records into {@link #DEFAULT_SCHEMA_HISTORY_TABLE_NAME}, as this machine's host name. A change set PostgreSQL
     * aborts to break a deadlock is applied again - unless a Spring transaction is active on the calling thread, which
     * a {@code Jdbi} on a Spring transaction-aware {@code DataSource} joins.
     *
     * @param jdbi where the schema is created
     */
    public PostgresqlCreateSchemaApplier(Jdbi jdbi) {
        this(jdbi, DEFAULT_SCHEMA_HISTORY_TABLE_NAME, Network.hostName());
    }

    /**
     * A change set PostgreSQL aborts to break a deadlock is applied again - unless a Spring transaction is active on
     * the calling thread, which a {@code Jdbi} on a Spring transaction-aware {@code DataSource} joins.
     *
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

    /**
     * Apply one contributor's own schema inside a unit of work that is already active - for a component whose schema
     * has always been created within a caller's transaction. Everything runs in that transaction.
     *
     * @param unitOfWork  the active unit of work
     * @param contributor the component
     */
    public static void applyOwnSchema(HandleAwareUnitOfWork unitOfWork, EssentialsSchemaContributor contributor) {
        applyOwnSchema(new PostgresqlCreateSchemaApplier(Transactions.of(requireNonNull(unitOfWork, "No unitOfWork provided")),
                                                         DEFAULT_SCHEMA_HISTORY_TABLE_NAME,
                                                         Network.hostName()),
                       contributor);
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

    /**
     * @return {@code true}: this applier executes the statements
     */
    @Override
    public boolean createsSchema() {
        return true;
    }

    public String getSchemaHistoryTableName() {
        return schemaHistoryTableName;
    }

    @Override
    public void apply(List<SchemaChangeSet> changeSets) {
        requireNonNull(changeSets, "No changeSets provided");
        changeSets.forEach(changeSet -> changeSet.changes().forEach(change -> PostgresqlUtil.checkIsValidTableOrColumnName(change.objectName())));
        warnOfRepeatableChangesLockingTheirTable(changeSets);

        transactions.inTransaction(handle -> {
            PostgresqlUtil.acquireBootstrapLock(handle);
            handle.execute(PostgresqlSchemaHistory.createTableStatement(schemaHistoryTableName));
        });

        var recorded = readLedger();
        rejectEditedOneShotChanges(changeSets, recorded);

        for (var changeSet : changeSets) {
            applyRetryingOnDeadlock(changeSet);
        }
    }

    /**
     * WARN, once per {@code (module, changeId, objectName)} in the JVM, of each repeatable change holding a statement
     * that {@link PostgresqlSchemaStatements#takesTableLockEvenWhenPresent(String)}. Keyed like a change's ledger row:
     * a contributor that emits one change id per table has each table warned about. Only a WARN: an application's own
     * contributor goes through this applier too, and refusing its start would break it.
     *
     * @return the changes warned about by this call, as {@code module/changeId/objectName}
     */
    static List<String> warnOfRepeatableChangesLockingTheirTable(List<SchemaChangeSet> changeSets) {
        var warned = new ArrayList<String>();
        for (var changeSet : changeSets) {
            for (var change : changeSet.changes()) {
                if (!change.repeatable()) {
                    continue;
                }
                var lockingStatement = change.statements()
                                             .stream()
                                             .flatMap(statement -> PostgresqlSchemaStatements.tableLockingStatement(statement)
                                                                                             .map(kind -> Map.entry(statement, kind))
                                                                                             .stream())
                                             .findFirst();
                if (lockingStatement.isPresent() && WARNED_TABLE_LOCKING_CHANGES.add(changeSet.moduleId() + '\0' + change.changeId() + '\0' + change.objectName())) {
                    var kind = lockingStatement.get().getValue();
                    log.warn("[{}] Repeatable schema change '{}' on '{}' {} without checking the catalog first. " +
                                     "It runs on every start, and PostgreSQL takes {} on the statement's table even when {} already exists, " +
                                     "until the change set's transaction commits: the start waits for, " +
                                     "then blocks, the writes of the instances already running, and can deadlock with them. " +
                                     "{} Statement: {}",
                             changeSet.moduleId(), change.changeId(), change.objectName(), kind.does, kind.lock, kind.ensures, kind.remedy, lockingStatement.get().getKey());
                    warned.add(changeSet.moduleId() + "/" + change.changeId() + "/" + change.objectName());
                }
            }
        }
        return warned;
    }

    private void applyRetryingOnDeadlock(SchemaChangeSet changeSet) {
        // Decided before the first attempt: once it runs, the transaction it began is the one that is active
        var retryable = transactions.beginsItsOwnTransaction();
        for (var attempt = 1; ; attempt++) {
            try {
                applyInOneTransaction(changeSet);
                return;
            } catch (RuntimeException e) {
                if (!retryable || attempt >= MAX_ATTEMPTS_ON_DEADLOCK || !isDeadlock(e)) {
                    throw e;
                }
                log.warn("[{}] Schema change set was aborted by PostgreSQL to break a deadlock with another transaction - attempt {} of {}, applying it again",
                         changeSet.moduleId(), attempt, MAX_ATTEMPTS_ON_DEADLOCK);
                try {
                    Thread.sleep(DEADLOCK_RETRY_DELAY.toMillis() * attempt + ThreadLocalRandom.current().nextLong(DEADLOCK_RETRY_DELAY.toMillis()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    // The deadlock stays the exception thrown: it is what the caller acts on
                    e.addSuppressed(interrupted);
                    throw e;
                }
            }
        }
    }

    private static boolean isDeadlock(Throwable e) {
        // Bounded: a cause chain can be cyclic
        var depth = 0;
        for (var cause = e; cause != null && depth < 100; cause = cause.getCause(), depth++) {
            if (cause instanceof SQLException sqlException && DEADLOCK_DETECTED.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private void applyInOneTransaction(SchemaChangeSet changeSet) {
        transactions.inTransaction(handle -> {
            PostgresqlUtil.acquireBootstrapLock(handle);
            // Re-read under the lock: another JVM may have applied this set while we waited for it
            var current = readLedger(handle, changeSet.moduleId());
            var executed = 0;
            for (var change : changeSet.changes()) {
                var checksum      = change.checksum();
                var recordedEntry = current.get(ChangeKey.of(changeSet, change));
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
        return PostgresqlSchemaHistory.read(handle, schemaHistoryTableName, onlyModuleId);
    }

    private static void rejectEditedOneShotChanges(List<SchemaChangeSet> changeSets, Map<ChangeKey, String> recorded) {
        var edited = new ArrayList<String>();
        for (var changeSet : changeSets) {
            for (var change : changeSet.changes()) {
                if (change.repeatable()) {
                    continue;
                }
                var recordedChecksum = recorded.get(ChangeKey.of(changeSet, change));
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

    /**
     * The two ways Essentials components reach the database: a plain {@link Jdbi}, or a unit-of-work factory.
     */
    private interface Transactions {
        void inTransaction(Consumer<Handle> work);

        /**
         * @return whether {@link #inTransaction(Consumer)} called now would begin a transaction of its own rather than
         * join one the caller started
         */
        boolean beginsItsOwnTransaction();

        <R> R withHandle(Function<Handle, R> work);

        static Transactions of(Jdbi jdbi) {
            return new Transactions() {
                @Override
                public void inTransaction(Consumer<Handle> work) {
                    jdbi.useTransaction(work::accept);
                }

                @Override
                public boolean beginsItsOwnTransaction() {
                    // A Jdbi built on Spring's TransactionAwareDataSourceProxy (spring-boot-starter-postgresql's
                    // jdbi bean) joins an active Spring transaction. Whether this one does cannot be told from the
                    // Jdbi, so an active Spring transaction counts as joined: at worst a deadlock is not retried
                    return !TransactionProbe.isSpringTransactionActive();
                }

                @Override
                public <R> R withHandle(Function<Handle, R> work) {
                    return jdbi.withHandle(work::apply);
                }
            };
        }

        static Transactions of(HandleAwareUnitOfWork unitOfWork) {
            return new Transactions() {
                @Override
                public void inTransaction(Consumer<Handle> work) {
                    work.accept(unitOfWork.handle());
                }

                @Override
                public boolean beginsItsOwnTransaction() {
                    return false;
                }

                @Override
                public <R> R withHandle(Function<Handle, R> work) {
                    return work.apply(unitOfWork.handle());
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
                public boolean beginsItsOwnTransaction() {
                    // Spring-aware: counts a Spring transaction no unit of work is bound to yet
                    return !TransactionProbe.isUnitOfWorkActive(unitOfWorkFactory);
                }

                @Override
                public <R> R withHandle(Function<Handle, R> work) {
                    return unitOfWorkFactory.withUnitOfWork(unitOfWork -> work.apply(unitOfWork.handle()));
                }
            };
        }
    }
}
