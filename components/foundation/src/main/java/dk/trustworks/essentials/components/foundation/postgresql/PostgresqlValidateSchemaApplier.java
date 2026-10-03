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
import org.jdbi.v3.core.Jdbi;
import org.slf4j.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The {@code validate} mode: executes nothing, and fails with every difference between the described schema and the
 * database - for deployments whose application user has no DDL rights.
 * <p>
 * A contribution is plain SQL, so there is no structured description of the objects to compare the catalog against.
 * What is compared is the schema history ledger: every described change must be recorded there, with the checksum of
 * the statements it has now. A missing row means the change was never applied; a different checksum means the
 * release describes the object differently from what was applied. Both are fixed the same way: generate the script
 * with {@link PostgresqlEmitSchemaApplier}, have it run by whoever holds DDL rights - it records the ledger rows
 * itself - and start again.
 * <p>
 * The ledger is trusted: an object dropped by hand after it was recorded is not noticed.
 */
public final class PostgresqlValidateSchemaApplier implements SchemaApplier {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlValidateSchemaApplier.class);

    private final Jdbi   jdbi;
    private final String schemaHistoryTableName;

    /**
     * Reads {@link PostgresqlCreateSchemaApplier#DEFAULT_SCHEMA_HISTORY_TABLE_NAME}.
     *
     * @param jdbi the database to validate
     */
    public PostgresqlValidateSchemaApplier(Jdbi jdbi) {
        this(jdbi, PostgresqlCreateSchemaApplier.DEFAULT_SCHEMA_HISTORY_TABLE_NAME);
    }

    /**
     * @param jdbi                   the database to validate
     * @param schemaHistoryTableName the ledger table. <b>Concatenated into SQL</b>: validated with
     *                               {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)}
     */
    public PostgresqlValidateSchemaApplier(Jdbi jdbi, String schemaHistoryTableName) {
        this.jdbi = requireNonNull(jdbi, "No jdbi provided");
        this.schemaHistoryTableName = requireNonBlank(schemaHistoryTableName, "No schemaHistoryTableName provided").toLowerCase(Locale.ROOT);
        PostgresqlUtil.checkIsValidTableOrColumnName(this.schemaHistoryTableName);
    }

    public String getSchemaHistoryTableName() {
        return schemaHistoryTableName;
    }

    /**
     * @throws SchemaValidationException listing every change that is not recorded, or recorded with other statements
     */
    @Override
    public void apply(List<SchemaChangeSet> changeSets) {
        requireNonNull(changeSets, "No changeSets provided");
        changeSets.forEach(changeSet -> changeSet.changes().forEach(change -> PostgresqlUtil.checkIsValidTableOrColumnName(change.objectName())));

        var recorded = jdbi.withHandle(handle -> PostgresqlSchemaHistory.exists(handle, schemaHistoryTableName)
                                                 ? PostgresqlSchemaHistory.read(handle, schemaHistoryTableName, null)
                                                 : Map.<ChangeKey, String>of());
        var problems = new ArrayList<String>();
        var checked  = 0;
        for (var changeSet : changeSets) {
            for (var change : changeSet.changes()) {
                checked++;
                var recordedChecksum = recorded.get(ChangeKey.of(changeSet, change));
                if (recordedChecksum == null) {
                    problems.add(msg("'{}' change '{}' on '{}': not applied", changeSet.moduleId(), change.changeId(), change.objectName()));
                } else if (!recordedChecksum.equals(change.checksum())) {
                    problems.add(msg("'{}' change '{}' on '{}': applied with other statements{} (recorded checksum {}, now {})",
                                     changeSet.moduleId(), change.changeId(), change.objectName(),
                                     change.repeatable() ? "" : " - a one-shot change edited after it was applied, ship it under a new change id",
                                     recordedChecksum, change.checksum()));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new SchemaValidationException(msg("The database schema does not match what {} schema change(s) describe - {} problem(s) in ledger '{}'. " +
                                                    "Generate the script with the emit mode, have it run by a user with DDL rights, and start again:\n  {}",
                                                    checked, problems.size(), schemaHistoryTableName, String.join("\n  ", problems)),
                                                problems);
        }
        log.info("Validated {} schema change(s) in {} change set(s) against ledger '{}'", checked, changeSets.size(), schemaHistoryTableName);
    }
}
