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

package dk.trustworks.essentials.components.foundation.postgresql.api;

import java.util.List;

/**
 * Provides an API for retrieving statistics related to PostgreSQL queries.
 * <p>
 * This interface defines a contract for fetching structured information about
 * database queries, enabling insights into the performance and characteristics
 * of query execution. Specifically, it supports retrieving data such as the
 * slowest queries executed in the database.
 */
public interface PostgresqlQueryStatisticsApi {

    /**
     * Retrieves the top ten queries with the highest execution times within the database.
     * The returned list provides detailed statistics for each query, such as the query string,
     * total execution time, number of executions, and average execution time.
     * <p>
     * Note: The {@code query} property on each {@link ApiQueryStatistics} instance is
     * populated from PostgreSQL’s {@code pg_stat_statements} view. By default, pg_stat_statements
     * normalizes SQL by replacing literal constants with placeholders ({@code 1}, {@code 2}, …),
     * so that similar statements are aggregated and sensitive values aren’t exposed.
     * <p>
     * Caveat: pg_stat_statements only retains up to
     * {@code pg_stat_statements.max} distinct query shapes. If you issue more unique statements
     * than this limit, older slots are deallocated and reused. In those cases, you may see the
     * original SQL with literal values (e.g., {@code WHERE id = 42}) instead of placeholder-based
     * normalization. To detect evictions, inspect the {@code dealloc} counter in
     * {@code pg_stat_statements_info}, and consider increasing
     * {@code pg_stat_statements.max} if needed.
     *
     * @return a list of the ten slowest queries, represented as {@code ApiQueryStats} objects,
     *         sorted in descending order of execution time.
     */
    List<ApiQueryStatistics> getTopTenSlowestQueries(Object principal);

}
