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
 * identifiers and constructing WHERE clauses with time-based operations.
 */
public class DeleteStatementBuilder {

    private DeleteStatementBuilder() {  }

    public static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

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
                quoteIdent(tableName),
                where
                            );
    }

    public static String buildWhereClause(String timestampColumn,
                                          ComparisonOperator operator,
                                          long days) {
        requireNonNull(timestampColumn, "timestampColumn must not be null");
        requireNonNull(operator, "operator must not be null");
        requireTrue(days >= 0, "days must be non-negative");
        PostgresqlUtil.checkIsValidTableOrColumnName(timestampColumn);
        String col = quoteIdent(timestampColumn);
        String opSql;
        if (operator == ComparisonOperator.LESS_THAN
                || operator == ComparisonOperator.LESS_THAN_EQUALS) {
            opSql = operator.getSql();
        } else {
            opSql = String.format(Locale.ROOT, operator.getSql(), days);
        }
        return String.format(
                "%s %s (NOW() - INTERVAL '%d day')",
                col,
                opSql,
                days
                            );
    }
}
