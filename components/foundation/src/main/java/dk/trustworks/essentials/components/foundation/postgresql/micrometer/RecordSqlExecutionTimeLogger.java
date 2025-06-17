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

package dk.trustworks.essentials.components.foundation.postgresql.micrometer;

import dk.trustworks.essentials.shared.measurement.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.jdbi.v3.core.statement.*;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

import static dk.trustworks.essentials.components.foundation.jdbi.EssentialsQueryTagger.QUERY_TAG;

/**
 * A logger class that records SQL execution times. This class implements the {@link SqlLogger} interface
 * and is responsible for tracking and logging the time taken for SQL query execution.
 * <p>
 * The class supports truncating SQL queries for logging purposes and includes configuration
 * options for enabling/disabling execution time recording and defining module-specific tags.
 */
public class RecordSqlExecutionTimeLogger implements SqlLogger {


    private final       MeasurementTaker measurementTaker;
    public static final String           MODULE_TAG_NAME           = "Module";
    public static final String           METRIC_PREFIX             = "essentials.sql.query.";
    private final       boolean          recordExecutionTimeEnabled;
    private final       String           moduleTag;
    public static final int              TRUNCATE_SQL_AFTER_LENGTH = 50;
    public static final String           DOT_DOT_DOT               = "...";

    public RecordSqlExecutionTimeLogger(Optional<MeterRegistry> meterRegistryOptional,
                                        boolean recordExecutionTimeEnabled,
                                        LogThresholds thresholds,
                                        String moduleTag) {
        this.recordExecutionTimeEnabled = recordExecutionTimeEnabled;
        this.moduleTag = moduleTag;
        this.measurementTaker = MeasurementTaker.builder()
                                                .addRecorder(
                                                        new LoggingMeasurementRecorder(
                                                                LoggerFactory.getLogger(this.getClass()),
                                                                thresholds))
                                                .withOptionalMicrometerMeasurementRecorder(meterRegistryOptional)
                                                .build();
    }

    @Override
    public void logAfterExecution(StatementContext context) {
        if (recordExecutionTimeEnabled && shouldLog(context)) {
            var sql = truncateSql(context.getRenderedSql());
            if (context.getExecutionMoment() != null) {
                var queryDuration = Duration.between(context.getExecutionMoment(), context.getCompletionMoment());
                measurementTaker.context(METRIC_PREFIX + sql)
                                .description("Time taken to query database")
                                .tag("sql_query", sql)
                                .optionalTag(MODULE_TAG_NAME, moduleTag)
                                .record(queryDuration);
            }
        }

    }

    private boolean shouldLog(StatementContext context) {
        return context.getRenderedSql().startsWith(QUERY_TAG) && isQuery(context);
    }

    /**
     * Determines if the given SQL statement represented by the provided
     * {@link StatementContext} is a query. This involves analyzing the
     * initial non-whitespace portion of the SQL to identify common query keywords
     * such as SELECT, WITH, or VALUES, among others.
     * <p>
     * Comment lines and SQL block comments are skipped during processing.
     *
     * @param context the {@link StatementContext} containing the SQL statement to evaluate
     * @return {@code true} if the specified SQL statement is identified as a query,
     * {@code false} otherwise
     */
    private boolean isQuery(StatementContext context) {
        String sql = context.getRenderedSql();
        int    len = sql.length(), i = 0;

        while (i < len) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < len && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            break;
        }

        if (i >= len) {
            return false;
        }

        char c0 = sql.charAt(i);
        return switch (Character.toLowerCase(c0)) {
            case '(' ->
                // e.g. "(SELECT ...)"
                    true;
            case 's' ->
                // SELECT or SHOW
                    (i + 6 <= len && sql.regionMatches(true, i, "select", 0, 6));
            case 'w' ->
                // WITH ... AS (
                    (i + 4 <= len && sql.regionMatches(true, i, "with", 0, 4));
            case 'v' ->
                // VALUES (...)
                    (i + 6 <= len && sql.regionMatches(true, i, "values", 0, 6));
            default -> false;
        };
    }

    /**
     * Truncates the provided SQL string to a predefined maximum length. If the SQL
     * begins with a block comment (e.g., "/* comment
     */
    private String truncateSql(String sql) {
        if (sql == null) {
            return null;
        }

        int start = 0;
        if (sql.startsWith("/*")) {
            int end = sql.indexOf("*/", 2);
            start = (end >= 0) ? end + 2 : sql.length();
        }

        int remaining = sql.length() - start;
        if (remaining > TRUNCATE_SQL_AFTER_LENGTH) {
            return sql.substring(start, start + TRUNCATE_SQL_AFTER_LENGTH) + DOT_DOT_DOT;
        } else {
            return sql.substring(start);
        }
    }

}
