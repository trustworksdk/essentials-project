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

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Size, activity and cache statistics for one table, as reported by {@link PostgresqlTableStatisticsApi}.
 * <p>
 * The counters come from PostgreSQL's cumulative statistics ({@code pg_stat_user_tables} and
 * {@code pg_statio_user_tables}) and count from the last statistics reset - not from application start.
 *
 * @param section          the section the table belongs to, e.g.
 *                         {@link dk.trustworks.essentials.components.foundation.postgresql.stats.PostgresqlStatisticsTable#SECTION_DURABLE_QUEUES}
 * @param tableName        the table name
 * @param totalSizeBytes   the table, its indexes and its TOAST data, in bytes
 * @param tableSizeBytes   the table's main data (heap), in bytes
 * @param indexSizeBytes   the table's indexes, in bytes
 * @param totalSize        {@code totalSizeBytes} in human-readable form, e.g. {@code "12 MB"}
 * @param tableSize        {@code tableSizeBytes} in human-readable form
 * @param indexSize        {@code indexSizeBytes} in human-readable form
 * @param liveRows         estimated number of live rows
 * @param deadRows         estimated number of dead rows not yet vacuumed - a high count on a queue table is bloat
 * @param seqScan          sequential scans started on the table
 * @param seqTupRead       live rows fetched by sequential scans
 * @param idxScan          index scans started on the table's indexes
 * @param idxTupFetch      live rows fetched by index scans
 * @param rowsInserted     rows inserted
 * @param rowsUpdated      rows updated, HOT updates included
 * @param rowsHotUpdated   rows updated as heap-only tuples (HOT): no indexed column changed and the new version fit
 *                         on the same page, so no index had to be touched. On a frequently updated table a low share
 *                         of {@code rowsUpdated} points at an index on an updated column, or too little free space per
 *                         page ({@code fillfactor})
 * @param rowsDeleted      rows deleted
 * @param cacheHitRatio    share of the table's and its indexes' block requests served from shared buffers, as a
 *                         percentage 0-100 with one decimal. {@code null} while there has been no block access to
 *                         compute it from
 * @param lastVacuum       the most recent manual or automatic vacuum, {@code null} if never vacuumed
 * @param lastAnalyze      the most recent manual or automatic analyze, {@code null} if never analyzed
 * @param indexes          the table's indexes, largest first
 */
public record ApiTableStatistics(
        String section,
        String tableName,
        long totalSizeBytes,
        long tableSizeBytes,
        long indexSizeBytes,
        String totalSize,
        String tableSize,
        String indexSize,
        long liveRows,
        long deadRows,
        long seqScan,
        long seqTupRead,
        long idxScan,
        long idxTupFetch,
        long rowsInserted,
        long rowsUpdated,
        long rowsHotUpdated,
        long rowsDeleted,
        Double cacheHitRatio,
        OffsetDateTime lastVacuum,
        OffsetDateTime lastAnalyze,
        List<ApiIndexStatistics> indexes
) {

    public ApiTableStatistics {
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
    }

    /**
     * @return {@code rowsHotUpdated} as a percentage 0-100 of {@code rowsUpdated}, one decimal. {@code null} when the
     * table has had no updates
     */
    public Double hotUpdateRatio() {
        return rowsUpdated == 0 ? null : Math.round(1000.0 * rowsHotUpdated / rowsUpdated) / 10.0;
    }
}
