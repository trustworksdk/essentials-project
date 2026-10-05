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


package dk.trustworks.essentials.components.foundation.postgresql.api;

/**
 * What {@link PostgresqlQueryStatisticsApi#getSlowestQueries(Object, QueryStatisticsOrder, int)} ranks queries by.
 * <p>
 * The choice changes the answer considerably. {@link #TOTAL_TIME} favours cheap statements that run constantly -
 * queue polling dominates it on a busy system - while {@link #MEAN_TIME} and {@link #MAX_TIME} surface the
 * statements that are slow per call, however rarely they run.
 */
public enum QueryStatisticsOrder {
    /** Cumulative planning and execution time across all calls - where the database spends its time */
    TOTAL_TIME,
    /** Mean planning and execution time per call - the statements that are slow each time they run */
    MEAN_TIME,
    /** Slowest single execution - outliers such as lock waits */
    MAX_TIME,
    /** Number of calls - the busiest statements */
    CALLS,
    /** Shared blocks read from outside shared buffers - the statements doing the most I/O */
    BLOCKS_READ
}
