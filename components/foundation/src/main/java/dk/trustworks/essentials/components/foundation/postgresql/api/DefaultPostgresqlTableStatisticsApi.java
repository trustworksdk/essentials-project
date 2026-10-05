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

import dk.trustworks.essentials.components.foundation.postgresql.stats.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.math.BigDecimal;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

/**
 * Default {@link PostgresqlTableStatisticsApi}, reporting the tables its {@link PostgresqlStatisticsTableProvider}s
 * contribute.
 * <p>
 * Tables are resolved with {@code to_regclass}, so a name resolves the way the owning component's own SQL does -
 * through the {@code search_path}, or schema-qualified where it was configured that way - and a table that is
 * reported but not created (yet) is left out rather than failing the request. A table contributed by more than one
 * provider is reported once, under the section of the first.
 * <p>
 * Sections are reported in a fixed order - the Essentials sections as listed on {@link PostgresqlStatisticsTable},
 * then any other section in the order its first table is contributed - so the result does not depend on the order
 * the providers happen to be registered in.
 */
public class DefaultPostgresqlTableStatisticsApi implements PostgresqlTableStatisticsApi {

    private static final String SQL = """
            SELECT
                s.relid,
                t.section,
                t.table_name,
                pg_total_relation_size(s.relid)                AS total_bytes,
                pg_relation_size(s.relid)                      AS table_bytes,
                pg_indexes_size(s.relid)                       AS index_bytes,
                pg_size_pretty(pg_total_relation_size(s.relid)) AS total_size,
                pg_size_pretty(pg_relation_size(s.relid))       AS table_size,
                pg_size_pretty(pg_indexes_size(s.relid))        AS index_size,
                s.n_live_tup,
                s.n_dead_tup,
                coalesce(s.seq_scan, 0)                        AS seq_scan,
                coalesce(s.seq_tup_read, 0)                    AS seq_tup_read,
                coalesce(s.idx_scan, 0)                        AS idx_scan,
                coalesce(s.idx_tup_fetch, 0)                   AS idx_tup_fetch,
                s.n_tup_ins,
                s.n_tup_upd,
                s.n_tup_hot_upd,
                s.n_tup_del,
                round(100.0 * (coalesce(io.heap_blks_hit, 0) + coalesce(io.idx_blks_hit, 0))
                      / nullif(coalesce(io.heap_blks_hit, 0) + coalesce(io.idx_blks_hit, 0)
                               + coalesce(io.heap_blks_read, 0) + coalesce(io.idx_blks_read, 0), 0), 1) AS cache_hit_ratio,
                greatest(s.last_vacuum, s.last_autovacuum)     AS last_vacuum,
                greatest(s.last_analyze, s.last_autoanalyze)   AS last_analyze
            FROM unnest(:sections, :tableNames) WITH ORDINALITY AS t(section, table_name, position)
            JOIN pg_stat_user_tables s ON s.relid = to_regclass(t.table_name)
            JOIN pg_statio_user_tables io ON io.relid = s.relid
            ORDER BY t.position
            """;

    private static final String INDEX_SQL = """
            SELECT
                si.relid,
                si.indexrelname                              AS index_name,
                pg_relation_size(si.indexrelid)              AS size_bytes,
                pg_size_pretty(pg_relation_size(si.indexrelid)) AS size,
                coalesce(si.idx_scan, 0)                     AS idx_scan,
                coalesce(si.idx_tup_read, 0)                 AS idx_tup_read,
                coalesce(si.idx_tup_fetch, 0)                AS idx_tup_fetch,
                i.indisunique                                AS is_unique,
                i.indisprimary                               AS is_primary,
                i.indisvalid                                 AS is_valid,
                round(100.0 * coalesce(io.idx_blks_hit, 0)
                      / nullif(coalesce(io.idx_blks_hit, 0) + coalesce(io.idx_blks_read, 0), 0), 1) AS cache_hit_ratio
            FROM pg_stat_user_indexes si
            JOIN pg_index i ON i.indexrelid = si.indexrelid
            JOIN pg_statio_user_indexes io ON io.indexrelid = si.indexrelid
            WHERE si.relid::bigint = ANY(:relids)
            ORDER BY si.relid, pg_relation_size(si.indexrelid) DESC, si.indexrelname
            """;

    private static final List<String> SECTION_ORDER = List.of(PostgresqlStatisticsTable.SECTION_EVENT_STORE,
                                                              PostgresqlStatisticsTable.SECTION_SUBSCRIPTIONS,
                                                              PostgresqlStatisticsTable.SECTION_CDC,
                                                              PostgresqlStatisticsTable.SECTION_DURABLE_QUEUES,
                                                              PostgresqlStatisticsTable.SECTION_SHARD_OWNED_QUEUES,
                                                              PostgresqlStatisticsTable.SECTION_FENCED_LOCKS,
                                                              PostgresqlStatisticsTable.SECTION_AGGREGATES,
                                                              PostgresqlStatisticsTable.SECTION_INFRASTRUCTURE);

    private final EssentialsSecurityProvider                                    securityProvider;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final List<PostgresqlStatisticsTableProvider>                       tableProviders;

    /**
     * @param securityProvider  decides who may read the statistics
     * @param unitOfWorkFactory the unit of work factory for the database the tables live in
     * @param tableProviders    the providers of the tables to report
     */
    public DefaultPostgresqlTableStatisticsApi(EssentialsSecurityProvider securityProvider,
                                               HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                               List<? extends PostgresqlStatisticsTableProvider> tableProviders) {
        this.securityProvider = requireNonNull(securityProvider, "No securityProvider provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.tableProviders = List.copyOf(requireNonNull(tableProviders, "No tableProviders provided"));
    }

    @Override
    public List<ApiTableStatistics> fetchTableStatistics(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, POSTGRESQL_STATS_READER, ESSENTIALS_ADMIN);
        var tables = reportedTables();
        if (tables.isEmpty()) {
            return List.of();
        }
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var handle = uow.handle();
            var rows = handle.createQuery(SQL)
                             .bindArray("sections", String.class, tables.stream().map(PostgresqlStatisticsTable::section).toList())
                             .bindArray("tableNames", String.class, tables.stream().map(PostgresqlStatisticsTable::tableName).toList())
                             .map((rs, ctx) -> new TableRow(rs.getLong("relid"), rs))
                             .list();
            if (rows.isEmpty()) {
                return List.of();
            }
            var indexesByTable = new HashMap<Long, List<ApiIndexStatistics>>();
            handle.createQuery(INDEX_SQL)
                  .bindArray("relids", Long.class, rows.stream().map(TableRow::relid).toList())
                  .map((rs, ctx) -> Map.entry(rs.getLong("relid"), toApiIndexStatistics(rs)))
                  .forEach(entry -> indexesByTable.computeIfAbsent(entry.getKey(), relid -> new ArrayList<>()).add(entry.getValue()));
            return rows.stream()
                       .map(row -> row.withIndexes(indexesByTable.getOrDefault(row.relid(), List.of())))
                       .toList();
        });
    }

    /**
     * A table row read before its indexes, keyed by the table's oid so the indexes can be joined on
     */
    private record TableRow(long relid, ApiTableStatistics statistics) {

        TableRow(long relid, ResultSet rs) throws SQLException {
            this(relid, toApiTableStatistics(rs));
        }

        ApiTableStatistics withIndexes(List<ApiIndexStatistics> indexes) {
            var s = statistics;
            return new ApiTableStatistics(s.section(), s.tableName(), s.totalSizeBytes(), s.tableSizeBytes(), s.indexSizeBytes(),
                                          s.totalSize(), s.tableSize(), s.indexSize(), s.liveRows(), s.deadRows(),
                                          s.seqScan(), s.seqTupRead(), s.idxScan(), s.idxTupFetch(),
                                          s.rowsInserted(), s.rowsUpdated(), s.rowsHotUpdated(), s.rowsDeleted(),
                                          s.cacheHitRatio(), s.lastVacuum(), s.lastAnalyze(), indexes);
        }
    }

    /**
     * Groups the contributed tables by section and drops repeats of a table, so a section's tables are reported
     * together even when more than one provider contributes to it.
     */
    private List<PostgresqlStatisticsTable> reportedTables() {
        var bySection = new LinkedHashMap<String, List<PostgresqlStatisticsTable>>();
        var seen      = new HashSet<String>();
        for (var provider : tableProviders) {
            for (var table : requireNonNull(provider.statisticsTables(), "A PostgresqlStatisticsTableProvider returned null")) {
                if (seen.add(table.tableName().toLowerCase(Locale.ROOT))) {
                    bySection.computeIfAbsent(table.section(), section -> new ArrayList<>()).add(table);
                }
            }
        }
        return bySection.entrySet().stream()
                        .sorted(Comparator.comparingInt(entry -> sectionRank(entry.getKey())))
                        .flatMap(entry -> entry.getValue().stream())
                        .toList();
    }

    /**
     * Unknown sections all rank last; the sort is stable, so among themselves they keep first-seen order
     */
    private static int sectionRank(String section) {
        var rank = SECTION_ORDER.indexOf(section);
        return rank >= 0 ? rank : SECTION_ORDER.size();
    }

    private static ApiTableStatistics toApiTableStatistics(ResultSet rs) throws SQLException {
        BigDecimal cacheHitRatio = rs.getBigDecimal("cache_hit_ratio");
        return new ApiTableStatistics(rs.getString("section"),
                                      rs.getString("table_name"),
                                      rs.getLong("total_bytes"),
                                      rs.getLong("table_bytes"),
                                      rs.getLong("index_bytes"),
                                      rs.getString("total_size"),
                                      rs.getString("table_size"),
                                      rs.getString("index_size"),
                                      rs.getLong("n_live_tup"),
                                      rs.getLong("n_dead_tup"),
                                      rs.getLong("seq_scan"),
                                      rs.getLong("seq_tup_read"),
                                      rs.getLong("idx_scan"),
                                      rs.getLong("idx_tup_fetch"),
                                      rs.getLong("n_tup_ins"),
                                      rs.getLong("n_tup_upd"),
                                      rs.getLong("n_tup_hot_upd"),
                                      rs.getLong("n_tup_del"),
                                      cacheHitRatio != null ? cacheHitRatio.doubleValue() : null,
                                      rs.getObject("last_vacuum", OffsetDateTime.class),
                                      rs.getObject("last_analyze", OffsetDateTime.class),
                                      List.of());
    }

    private static ApiIndexStatistics toApiIndexStatistics(ResultSet rs) throws SQLException {
        BigDecimal cacheHitRatio = rs.getBigDecimal("cache_hit_ratio");
        return new ApiIndexStatistics(rs.getString("index_name"),
                                      rs.getLong("size_bytes"),
                                      rs.getString("size"),
                                      rs.getLong("idx_scan"),
                                      rs.getLong("idx_tup_read"),
                                      rs.getLong("idx_tup_fetch"),
                                      rs.getBoolean("is_unique"),
                                      rs.getBoolean("is_primary"),
                                      rs.getBoolean("is_valid"),
                                      cacheHitRatio != null ? cacheHitRatio.doubleValue() : null);
    }
}
