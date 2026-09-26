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

import java.time.OffsetDateTime;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Renders change sets as one SQL script that does what {@link PostgresqlCreateSchemaApplier} does: the statements in
 * order, each change recorded in the schema history ledger, one-shot changes guarded so they run once, all under the
 * framework's bootstrap lock in one transaction. Running the script and then starting with
 * {@link PostgresqlValidateSchemaApplier} succeeds.
 * <p>
 * One combined script with a header per module - what a DBA runs, and still diffable between releases. Every
 * statement is safe against objects that already exist, so the script may be re-run.
 */
public final class PostgresqlSchemaScript {
    /** What the ledger's {@code applied_by} says for a change the script applied */
    public static final String APPLIED_BY = "essentials-schema-script";

    private static final String ONCE_TAG      = "$essentials_once$";
    private static final String STATEMENT_TAG = "$essentials_statement$";

    private final String schemaHistoryTableName;

    /**
     * @param schemaHistoryTableName the ledger table. <b>Concatenated into SQL</b>: validated with
     *                               {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)}
     */
    public PostgresqlSchemaScript(String schemaHistoryTableName) {
        this.schemaHistoryTableName = requireNonBlank(schemaHistoryTableName, "No schemaHistoryTableName provided").toLowerCase(Locale.ROOT);
        PostgresqlUtil.checkIsValidTableOrColumnName(this.schemaHistoryTableName);
    }

    /**
     * @param changeSets  ordered as the harness orders them
     * @param generatedBy for the header only, e.g. the host
     * @return the complete script: header, ledger table, every change set
     */
    public String render(List<SchemaChangeSet> changeSets, String generatedBy) {
        requireNonNull(changeSets, "No changeSets provided");
        var script = new StringBuilder();
        script.append("-- Essentials database schema\n")
              .append("-- Generated ").append(OffsetDateTime.now()).append(" by ").append(singleLine(generatedBy)).append('\n')
              .append("-- Run as a whole, as a user allowed to create these objects. Every statement is safe against objects\n")
              .append("-- that already exist, so the script may be re-run. Each change is recorded in ").append(schemaHistoryTableName).append(",\n")
              .append("-- which is what the validate mode checks.\n\n");
        appendTransaction(script, changeSets, true);
        return script.toString();
    }

    /**
     * @return the change sets as a self-contained block, without the header or the ledger table - for objects
     * registered after the script was first written
     */
    public String renderAddition(List<SchemaChangeSet> changeSets) {
        requireNonNull(changeSets, "No changeSets provided");
        var script = new StringBuilder();
        script.append("\n-- Registered after start-up, ").append(OffsetDateTime.now()).append("\n\n");
        appendTransaction(script, changeSets, false);
        return script.toString();
    }

    private void appendTransaction(StringBuilder script, List<SchemaChangeSet> changeSets, boolean withLedgerTable) {
        script.append("BEGIN;\n")
              .append("SELECT pg_advisory_xact_lock(").append(PostgresqlUtil.ESSENTIALS_BOOTSTRAP_LOCK_KEY).append(");\n\n");
        if (withLedgerTable) {
            script.append(PostgresqlSchemaHistory.createTableStatement(schemaHistoryTableName)).append(";\n");
        }
        for (var changeSet : changeSets) {
            script.append("\n-- ============================================================\n")
                  .append("-- Module ").append(singleLine(changeSet.moduleId())).append(" (order ").append(changeSet.order()).append(")\n")
                  .append("-- ============================================================\n");
            for (var change : changeSet.changes()) {
                PostgresqlUtil.checkIsValidTableOrColumnName(change.objectName());
                script.append("\n-- ").append(singleLine(change.changeId())).append(" on ").append(change.objectName())
                      .append(change.repeatable() ? " (repeatable)" : " (one-shot)").append('\n');
                if (change.repeatable()) {
                    for (var statement : change.statements()) {
                        script.append(statement.strip()).append(";\n");
                    }
                    script.append(recordStatement(changeSet, change)).append(";\n");
                } else {
                    appendOneShot(script, changeSet, change);
                }
            }
        }
        script.append("\nCOMMIT;\n");
    }

    /**
     * A one-shot change runs only when the ledger has no row for it - the same rule the create applier follows - so
     * its statements go through {@code EXECUTE} inside a guard. Dollar-quoted, so they need no escaping.
     */
    private void appendOneShot(StringBuilder script, SchemaChangeSet changeSet, SchemaChange change) {
        script.append("DO ").append(ONCE_TAG).append('\n')
              .append("BEGIN\n")
              .append("    IF NOT EXISTS (SELECT 1 FROM ").append(schemaHistoryTableName)
              .append(" WHERE module_id = ").append(literal(changeSet.moduleId()))
              .append(" AND change_id = ").append(literal(change.changeId()))
              .append(" AND object_name = ").append(literal(change.objectName())).append(") THEN\n");
        for (var statement : change.statements()) {
            if (statement.contains(STATEMENT_TAG) || statement.contains(ONCE_TAG)) {
                throw new IllegalArgumentException(msg("'{}' change '{}' contains the script's own quoting tag and cannot be emitted", changeSet.moduleId(), change.changeId()));
            }
            script.append("        EXECUTE ").append(STATEMENT_TAG).append(statement.strip()).append(STATEMENT_TAG).append(";\n");
        }
        script.append("        ").append(recordStatement(changeSet, change)).append(";\n")
              .append("    END IF;\n")
              .append("END\n")
              .append(ONCE_TAG).append(";\n");
    }

    /**
     * Inserts the ledger row, or updates a repeatable change's checksum - keeping the first {@code applied_ts}, as the
     * create applier does.
     */
    private String recordStatement(SchemaChangeSet changeSet, SchemaChange change) {
        return "INSERT INTO " + schemaHistoryTableName + " (module_id, change_id, object_name, checksum, applied_ts, applied_by) VALUES (" +
                literal(changeSet.moduleId()) + ", " + literal(change.changeId()) + ", " + literal(change.objectName()) + ", " +
                literal(change.checksum()) + ", now(), " + literal(APPLIED_BY) + ")" +
                " ON CONFLICT (module_id, change_id, object_name) DO UPDATE SET checksum = EXCLUDED.checksum, applied_by = EXCLUDED.applied_by";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ");
    }
}
