/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.foundation.ttl;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import java.util.Locale;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Utility class for constructing SQL DELETE statements with specific conditions.
 * This class provides methods to build DELETE statements, including support for quoting
 * identifiers and constructing WHERE clauses with time-based operations
 *
 * <p>
 * SECURITY warning:<br>
 * {@code tableName} and {@code timestampColumn} are checked for valid SQL table/column name as an initial layer of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input<br>
 * However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
 * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
 * Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.
 */
public class DeleteStatementBuilder {

    private DeleteStatementBuilder() {
    }

    /**
     * Constructs a SQL DELETE statement targeting a specified table, using a timestamp-based
     * condition in the WHERE clause. The condition is based on the provided timestamp column,
     * a comparison {@link ComparisonOperator}, and a time range calculated using the current
     * date minus the specified number of days.
     * <p>
     * The method validates the input to ensure that the table name, timestamp column, and operator
     * adhere to constraints such as being non-null, valid identifiers, and, in the case of days,
     * non-negative.
     *
     * <p>
     * SECURITY warning:<br>
     * {@code tableName} and {@code timestampColumn} are checked for valid SQL table/column name as an initial layer of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input<br>
     * However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
     * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
     * Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
     *
     * <p><strong>Example Usage:</strong></p>
     * <pre>{@code
     * String deleteStatement = DeleteStatementBuilder.build(
     *     "users",
     *     "created_at",
     *     ComparisonOperator.LESS_THAN_EQUALS,
     *     30
     * );
     * System.out.println(deleteStatement);
     * // Output: "DELETE FROM users WHERE created_at <= NOW() - INTERVAL '30 day'"
     * }</pre>
     *
     * @param tableName        the name of the table from which rows will be deleted;
     *                         must be non-null and a valid PostgreSQL identifier.
     * @param timestampColumn  the name of the column containing timestamp values;
     *                         must be non-null and a valid PostgreSQL identifier.
     * @param operator         a {@link ComparisonOperator} specifying the comparison to
     *                         apply in the WHERE clause; must not be null.
     * @param days             the number of days to subtract from the current time in the WHERE clause;
     *                         must be a non-negative value.
     * @return the constructed SQL DELETE statement as a {@link String}.
     * @throws IllegalArgumentException if {@code tableName}, {@code timestampColumn}, or {@code operator}
     *                                  is null, or if {@code days} is negative.
     * @throws IllegalArgumentException if {@code tableName} or {@code timestampColumn} is not a valid
     *                                  PostgreSQL identifier.
     */
    public static String build(String tableName,
                               String timestampColumn,
                               ComparisonOperator operator,
                               long days) {
        requireNonNull(tableName, "tableName must not be null");
        requireNonNull(timestampColumn, "timestampColumn must not be null");
        requireNonNull(operator, "operator must not be null");
        requireTrue(days >= 0, "days must be non-negative");
        PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
        PostgresqlUtil.checkIsValidTableOrColumnName(timestampColumn);
        String where = buildWhereClause(timestampColumn, operator, days);
        return String.format(
                "DELETE FROM %s WHERE %s",
                tableName,
                where
                            );
    }

    /**
     * Constructs a SQL WHERE clause for timestamp-based conditions using a specified column,
     * comparison operator, and time range in days.
     * <p>
     * The generated clause compares a timestamp column against a dynamic date range
     * calculated using the current date and time (NOW()). For example, it can produce clauses
     * such as <code>timestamp_column &lt;= (NOW() - INTERVAL '30 day')</code> or similar time-based predicates,
     * depending on the provided {@link ComparisonOperator}.
     *
     * <p>
     * SECURITY warning:<br>
     * {@code timestampColumn} is checked for valid SQL column name as an initial layer of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input<br>
     * However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
     * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
     * Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
     *
     * @param timestampColumn the name of the column containing timestamp values;
     *                        must not be null or invalid.
     * @param operator        the {@link ComparisonOperator} to use for the
     *                        comparison; must not be null.
     * @param days            the number of days for the interval to subtract from NOW()
     *                        in the generated WHERE clause; must be non-negative.
     * @return the constructed SQL WHERE clause as a {@link String}.
     * @throws IllegalArgumentException if {@code timestampColumn} or {@code operator}
     *                                  is null, or if {@code days} is negative.
     * @throws IllegalArgumentException if {@code timestampColumn} is not a valid
     *                                  PostgreSQL identifier.
     *
     *                                  <p><strong>Usage Example:</strong></p>
     *                                  <pre>
     *                                  {@code
     *                                  var clause = buildWhereClause("created_at", ComparisonOperator.LESS_THAN_EQUALS, 30);
     *                                  System.out.println(clause);
     *                                  // Output: "created_at <= (NOW() - INTERVAL '30 day')"
     *                                  }
     *                                  </pre>
     */
    public static String buildWhereClause(String timestampColumn,
                                          ComparisonOperator operator,
                                          long days) {
        requireNonNull(timestampColumn, "timestampColumn must not be null");
        requireNonNull(operator, "operator must not be null");
        requireTrue(days >= 0, "days must be non-negative");
        PostgresqlUtil.checkIsValidTableOrColumnName(timestampColumn);
        String opSql;
        if (operator == ComparisonOperator.LESS_THAN
                || operator == ComparisonOperator.LESS_THAN_EQUALS) {
            opSql = operator.getSql();
        } else {
            opSql = String.format(Locale.ROOT, operator.getSql(), days);
        }
        return String.format(
                "%s %s (NOW() - INTERVAL '%d day')",
                timestampColumn,
                opSql,
                days
                            );
    }
}
