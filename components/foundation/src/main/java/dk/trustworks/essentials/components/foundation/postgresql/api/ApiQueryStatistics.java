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

import dk.trustworks.essentials.components.foundation.postgresql.stats.QueryStatistics;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Statistics for one normalized statement, as recorded by PostgreSQL's {@code pg_stat_statements}.
 * <p>
 * Times are in milliseconds. {@code totalTime} and {@code meanTime} include planning time; {@code minTime},
 * {@code maxTime} and {@code stddevTime} are execution time only, as {@code pg_stat_statements} does not track
 * those for planning.
 * <p>
 * The {@link #from(QueryStatistics)} method converts from the internal {@link QueryStatistics}.
 *
 * @param query          the normalized statement text
 * @param totalTime      total planning and execution time across all calls
 * @param calls          number of calls
 * @param meanTime       mean planning and execution time per call
 * @param rows           rows retrieved or affected, summed over all calls
 * @param minTime        fastest execution
 * @param maxTime        slowest execution
 * @param stddevTime     standard deviation of the execution time
 * @param sharedBlksHit  shared blocks found in shared buffers
 * @param sharedBlksRead shared blocks read from outside shared buffers
 * @param cacheHitRatio  {@code sharedBlksHit} as a percentage 0-100 of all shared blocks accessed, one decimal.
 *                       {@code null} when the statement accessed no shared blocks
 */
public record ApiQueryStatistics(
        String query,
        double totalTime,
        long calls,
        double meanTime,
        long rows,
        double minTime,
        double maxTime,
        double stddevTime,
        long sharedBlksHit,
        long sharedBlksRead,
        Double cacheHitRatio
) {

    /**
     * The statistics as reported before the per-call and buffer statistics were added
     */
    public ApiQueryStatistics(String query, double totalTime, long calls, double meanTime) {
        this(query, totalTime, calls, meanTime, 0, 0, 0, 0, 0, 0, null);
    }

    public static ApiQueryStatistics from(QueryStatistics queryStats) {
        requireNonNull(queryStats, "queryStats must not be null");
        return new ApiQueryStatistics(queryStats.query(),
                                      queryStats.totalTime(),
                                      queryStats.calls(),
                                      queryStats.meanTime(),
                                      queryStats.rows(),
                                      queryStats.minTime(),
                                      queryStats.maxTime(),
                                      queryStats.stddevTime(),
                                      queryStats.sharedBlksHit(),
                                      queryStats.sharedBlksRead(),
                                      queryStats.cacheHitRatio());
    }
}
