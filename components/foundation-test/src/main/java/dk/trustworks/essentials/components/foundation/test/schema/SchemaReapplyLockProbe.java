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
package dk.trustworks.essentials.components.foundation.test.schema;

import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.schema.*;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.fail;

/**
 * Every instance re-applies its components' schema when it starts, while the instances already running keep writing to
 * the same tables. A re-apply against a schema that is already in place must therefore take no lock on those tables:
 * any lock it holds for the rest of its transaction blocks the running instances' writes, and a later statement in the
 * same transaction that needs another table - or a stronger lock on the same one - can close a lock cycle with one of
 * them, which PostgreSQL breaks by aborting one side with "deadlock detected".
 * <p>
 * {@code CREATE INDEX IF NOT EXISTS} takes a {@code SHARE} lock on its table even when the index exists, and
 * {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} takes {@code ACCESS EXCLUSIVE} even when the column exists, so
 * neither may be a repeatable change's statement as it stands. {@link #assertNoRepeatableStatementLocksItsTable} checks
 * the statements for them, and every re-apply probe here runs it first; at runtime {@link PostgresqlCreateSchemaApplier}
 * WARNs of them.
 */
public final class SchemaReapplyLockProbe {
    private static final Duration REAPPLY_TIMEOUT = Duration.ofSeconds(10);

    private SchemaReapplyLockProbe() {
    }

    /**
     * {@link #assertReapplyDoesNotWaitForWritersOn(Jdbi, EssentialsSchemaContributor, Collection)} for every table the
     * contributor's changes name as their {@code objectName}
     *
     * @param jdbi        the database
     * @param contributor the component whose schema is re-applied
     */
    public static void assertReapplyDoesNotWaitForWritersOnItsTables(Jdbi jdbi, EssentialsSchemaContributor contributor) {
        assertNoRepeatableStatementLocksItsTable(contributor);
        // The schema is applied once, here: the tables must exist for the to_regclass filter, and the probe re-applies onto this
        PostgresqlCreateSchemaApplier.applyOwnSchema(jdbi, contributor);
        var tables = contributor.contribute(SchemaContext.empty())
                                .stream()
                                .map(SchemaChange::objectName)
                                .distinct()
                                .filter(objectName -> jdbi.withHandle(handle -> handle.createQuery("SELECT EXISTS (SELECT 1 FROM pg_class WHERE oid = to_regclass(:name) AND relkind IN ('r', 'p'))")
                                                                                      .bind("name", objectName)
                                                                                      .mapTo(Boolean.class)
                                                                                      .one()))
                                .toList();
        if (tables.isEmpty()) {
            fail("'" + contributor.moduleId() + "' names no table in its changes");
        }
        assertReapplyUnderWritersDoesNotWait(jdbi, contributor, tables);
    }

    /**
     * Fail when a {@link SchemaChange#repeatable() repeatable} change of the contributor holds a statement that
     * {@link PostgresqlSchemaStatements#takesTableLockEvenWhenPresent(String) locks its table even when what it ensures
     * is already in place} - on any table, not only the ones a re-apply probe has a writer on. The statements are those
     * {@code contribute(SchemaContext.empty())} returns.
     *
     * @param contributor the component
     */
    public static void assertNoRepeatableStatementLocksItsTable(EssentialsSchemaContributor contributor) {
        var locking = contributor.contribute(SchemaContext.empty())
                                 .stream()
                                 .filter(SchemaChange::repeatable)
                                 .flatMap(change -> change.statements()
                                                          .stream()
                                                          .filter(PostgresqlSchemaStatements::takesTableLockEvenWhenPresent)
                                                          .map(statement -> change.changeId() + " on " + change.objectName() + ": " + statement))
                                 .toList();
        if (!locking.isEmpty()) {
            fail("'" + contributor.moduleId() + "' has repeatable statement(s) that create an index, add a column or create a trigger without checking the catalog first, " +
                         "so they lock their table on every start - wrap an index or a column in PostgresqlSchemaStatements.createIndexUnlessPresent / addColumnUnlessPresent, " +
                         "and run a trigger from a DO block that looks it up in pg_trigger first, as ListenNotify.changeNotificationTriggerStatements does:\n  " +
                         String.join("\n  ", locking));
        }
    }

    /**
     * {@link #assertNoRepeatableStatementLocksItsTable(EssentialsSchemaContributor)}, then apply the contributor's
     * schema and apply it again while another transaction holds {@code ROW EXCLUSIVE} - what every in-flight insert,
     * update or delete holds - on each of the given tables, and fail if the re-apply has to wait for that transaction.
     *
     * @param jdbi        the database
     * @param contributor the component whose schema is re-applied
     * @param tables      the tables other instances write to while the re-apply runs
     */
    public static void assertReapplyDoesNotWaitForWritersOn(Jdbi jdbi, EssentialsSchemaContributor contributor, Collection<String> tables) {
        assertNoRepeatableStatementLocksItsTable(contributor);
        PostgresqlCreateSchemaApplier.applyOwnSchema(jdbi, contributor);
        assertReapplyUnderWritersDoesNotWait(jdbi, contributor, tables);
    }

    /**
     * Apply the contributor's schema - already applied by the caller - again while another transaction holds
     * {@code ROW EXCLUSIVE} on each of the given tables, and fail if the re-apply has to wait for that transaction.
     */
    private static void assertReapplyUnderWritersDoesNotWait(Jdbi jdbi, EssentialsSchemaContributor contributor, Collection<String> tables) {
        var executor = Executors.newSingleThreadExecutor();
        try (var writer = jdbi.open()) {
            writer.begin();
            tables.forEach(table -> writer.execute("LOCK TABLE " + table + " IN ROW EXCLUSIVE MODE"));

            var reapply = executor.submit(() -> PostgresqlCreateSchemaApplier.applyOwnSchema(jdbi, contributor));
            try {
                reapply.get(REAPPLY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                var waitingFor = jdbi.withHandle(handle -> handle.createQuery("SELECT relation::regclass::text || ' (' || mode || ')' FROM pg_locks WHERE NOT granted AND relation IS NOT NULL")
                                                                 .mapTo(String.class)
                                                                 .list());
                fail("Re-applying the schema of '" + contributor.moduleId() + "' waited for a transaction writing to " + tables +
                             " - it takes a lock on a table it does not change. Waiting for: " + waitingFor);
            } catch (ExecutionException e) {
                fail("Re-applying the schema of '" + contributor.moduleId() + "' failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted", e);
            } finally {
                writer.rollback();
                reapply.cancel(true);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
