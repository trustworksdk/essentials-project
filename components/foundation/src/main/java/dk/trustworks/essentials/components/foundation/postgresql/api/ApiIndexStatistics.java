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
 * Usage and size of one index, as part of {@link ApiTableStatistics}.
 * <p>
 * The counters come from {@code pg_stat_user_indexes} and {@code pg_statio_user_indexes} and count from the last
 * statistics reset. They cover this server only: an index used solely by queries on a read replica shows no scans
 * here.
 *
 * @param indexName     the index name
 * @param sizeBytes     the index size, in bytes
 * @param size          {@code sizeBytes} in human-readable form, e.g. {@code "12 MB"}
 * @param idxScan       index scans started on this index. {@code 0} on a non-unique index means it costs writes
 *                      and space without serving a single query since the statistics were reset
 * @param idxTupRead    index entries returned by scans of this index
 * @param idxTupFetch   live table rows fetched by simple index scans of this index
 * @param unique        whether the index enforces uniqueness - such an index does its job without being scanned
 * @param primary       whether the index backs the primary key
 * @param valid         whether the index is usable. {@code false} after a failed {@code CREATE INDEX CONCURRENTLY}:
 *                      it is maintained on every write but never used, and should be dropped or rebuilt
 * @param cacheHitRatio share of this index's block requests served from shared buffers, as a percentage 0-100 with
 *                      one decimal. {@code null} while there has been no block access to compute it from
 */
public record ApiIndexStatistics(
        String indexName,
        long sizeBytes,
        String size,
        long idxScan,
        long idxTupRead,
        long idxTupFetch,
        boolean unique,
        boolean primary,
        boolean valid,
        Double cacheHitRatio
) {

    /**
     * @return whether the index has served no scan since the statistics were reset, and is not kept for a
     * uniqueness or primary-key constraint - a candidate for dropping, once the statistics cover a representative
     * period
     */
    public boolean unused() {
        return idxScan == 0 && !unique && !primary;
    }
}
