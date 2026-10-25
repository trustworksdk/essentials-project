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

import dk.trustworks.essentials.components.foundation.schema.SchemaChange;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.*;

/**
 * Statements for a {@link SchemaChange#repeatable() repeatable} schema change that take no lock on the table when what
 * they ensure is already in place.
 * <p>
 * A repeatable change runs on every start, against tables the instances already running keep writing to.
 * {@code CREATE INDEX IF NOT EXISTS} takes a {@code SHARE} lock on its table even when the index exists, and
 * {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} takes {@code ACCESS EXCLUSIVE} even when the column exists. Either
 * lock is held until the schema change set's transaction commits: it blocks every write to the table meanwhile, and
 * when the same transaction then needs a lock on another table - one change set can cover several tables, such as the
 * event store's event-stream tables - or a stronger one on the same table, a running transaction that writes to those
 * tables in the other order closes a lock cycle, and PostgreSQL aborts one side with "deadlock detected": either the
 * starting instance or the running instance's write.
 * <p>
 * Each statement here is a {@code DO} block that looks the index or column up in the catalog first, which takes no lock
 * on the table, and runs the given statement only when it is missing. Valid on every supported PostgreSQL version.
 */
public final class PostgresqlSchemaStatements {
    private static final String DOLLAR_QUOTE = "$essentials_schema$";

    private PostgresqlSchemaStatements() {
    }

    /**
     * Wrap a {@code CREATE INDEX} so it only runs when the table has no index of that name
     *
     * @param tableName            the indexed table, as written in {@code createIndexStatement}. <b>Concatenated into
     *                             SQL</b>: validated with {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)},
     *                             but only ever pass a name from a trusted source
     * @param indexName            the index's unqualified name, as written in {@code createIndexStatement}. <b>Concatenated
     *                             into SQL</b>: restricted to identifier characters, but not to
     *                             {@link PostgresqlUtil#MAX_IDENTIFIER_LENGTH} - an index name is usually derived from its
     *                             table's, which may already use the full length, and PostgreSQL cuts it the same way here
     *                             as when it creates the index
     * @param createIndexStatement the {@code CREATE INDEX} statement - keep its {@code IF NOT EXISTS}, which still
     *                             covers a relation of that name that is not an index on this table
     * @return the statement
     */
    public static String createIndexUnlessPresent(String tableName, String indexName, String createIndexStatement) {
        PostgresqlUtil.checkIsValidTableOrColumnName(requireNonBlank(tableName, "No tableName provided"));
        if (!PostgresqlUtil.VALID_SQL_IDENTIFIER_CHARACTERS_PATTERN.matcher(requireNonBlank(indexName, "No indexName provided")).matches()) {
            throw new InvalidTableOrColumnNameException(msg("Invalid index name: '{}'. Names must start with a letter or underscore, followed by letters, digits, or underscores", indexName));
        }
        return doBlock(bind("NOT EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid\n" +
                                    "                   WHERE i.indrelid = to_regclass('{:tableName}')\n" +
                                    // Created with an unquoted name, which PostgreSQL stores folded to lower case and cut to 63 characters
                                    "                     AND c.relname = left(lower('{:indexName}'), 63))",
                            arg("tableName", tableName),
                            arg("indexName", indexName)),
                       requireNonBlank(createIndexStatement, "No createIndexStatement provided"));
    }

    /**
     * Wrap an {@code ALTER TABLE ... ADD COLUMN} so it only runs when the table has no column of that name
     *
     * @param tableName          the altered table, as written in {@code addColumnStatement}. <b>Concatenated into
     *                           SQL</b>: validated with {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)}, but
     *                           only ever pass a name from a trusted source
     * @param columnName         the added column's unquoted name, as written in {@code addColumnStatement}.
     *                           <b>Concatenated into SQL</b>, validated the same way
     * @param addColumnStatement the {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} statement
     * @return the statement
     */
    public static String addColumnUnlessPresent(String tableName, String columnName, String addColumnStatement) {
        PostgresqlUtil.checkIsValidTableOrColumnName(requireNonBlank(tableName, "No tableName provided"));
        PostgresqlUtil.checkIsValidTableOrColumnName(requireNonBlank(columnName, "No columnName provided"));
        return doBlock(bind("NOT EXISTS (SELECT 1 FROM pg_attribute\n" +
                                    "                   WHERE attrelid = to_regclass('{:tableName}')\n" +
                                    "                     AND attname = left(lower('{:columnName}'), 63)\n" +
                                    "                     AND NOT attisdropped)",
                            arg("tableName", tableName),
                            arg("columnName", columnName)),
                       requireNonBlank(addColumnStatement, "No addColumnStatement provided"));
    }

    private static String doBlock(String condition, String statement) {
        requireFalse(statement.contains(DOLLAR_QUOTE), msg("The statement must not contain '{}': {}", DOLLAR_QUOTE, statement));
        var body = statement.strip();
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).strip();
        }
        requireFalse(body.isEmpty(), "The statement is blank");
        return "DO " + DOLLAR_QUOTE + "\n" +
                "BEGIN\n" +
                "    IF " + condition + " THEN\n" +
                "        " + body + ";\n" +
                "    END IF;\n" +
                "END\n" +
                DOLLAR_QUOTE;
    }
}
