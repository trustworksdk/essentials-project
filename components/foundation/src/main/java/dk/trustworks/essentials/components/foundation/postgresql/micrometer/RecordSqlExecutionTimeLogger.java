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

package dk.trustworks.essentials.components.foundation.postgresql.micrometer;

import dk.trustworks.essentials.shared.measurement.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.jdbi.v3.core.statement.*;

import java.time.Duration;
import java.util.Optional;

import static dk.trustworks.essentials.components.foundation.jdbi.EssentialsQueryTagger.QUERY_TAG;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

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

    /**
     * Constructs a new SQL execution-time logger recording to the supplied {@link MeasurementTaker}.
     * <p>
     * There is no separate "enabled" flag: pass {@link MeasurementTaker#none()} to switch recording off. The logger
     * branches on {@link MeasurementTaker#isRecording()}, so a disabled logger still skips rendering and truncating
     * the SQL — which matters, since this runs after every statement JDBI executes.
     *
     * @param measurementTaker where query durations are recorded. {@link MeasurementTaker#none()} disables recording
     * @param moduleTag        Optional {@value #MODULE_TAG_NAME} Tag value. May be {@code null}, in which case the tag is omitted
     */
    public RecordSqlExecutionTimeLogger(MeasurementTaker measurementTaker,
                                        String moduleTag) {
        this.measurementTaker = requireNonNull(measurementTaker, "No measurementTaker provided - use MeasurementTaker.none() to disable recording");
        this.recordExecutionTimeEnabled = measurementTaker.isRecording();
        this.moduleTag = moduleTag;
    }

    /**
     * @param meterRegistryOptional      an Optional MeterRegistry to enable Micrometer metrics
     * @param recordExecutionTimeEnabled whether to record execution times or not
     * @param thresholds                 the logging thresholds configuration
     * @param moduleTag                  Optional {@value #MODULE_TAG_NAME} Tag value
     * @deprecated Use {@link #RecordSqlExecutionTimeLogger(MeasurementTaker, String)}. Assemble the
     *         {@link MeasurementTaker} once — typically one per metrics subsystem in the Spring Boot starter — rather
     *         than re-deriving one from an {@code Optional<MeterRegistry>}. Pass {@link MeasurementTaker#none()} where
     *         {@code recordExecutionTimeEnabled} was {@code false}. This constructor delegates and behaves
     *         identically, except that the logging recorder is now named after this class rather than after the
     *         runtime subclass.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public RecordSqlExecutionTimeLogger(Optional<MeterRegistry> meterRegistryOptional,
                                        boolean recordExecutionTimeEnabled,
                                        LogThresholds thresholds,
                                        String moduleTag) {
        this(recordExecutionTimeEnabled
             ? MeasurementTaker.builder()
                               .setLoggingRecorder(RecordSqlExecutionTimeLogger.class, thresholds)
                               .setMeterRegistry(requireNonNull(meterRegistryOptional, "meterRegistryOptional cannot be null"))
                               .build()
             : MeasurementTaker.none(),
             moduleTag);
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
        var sql = context.getRenderedSql();
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
