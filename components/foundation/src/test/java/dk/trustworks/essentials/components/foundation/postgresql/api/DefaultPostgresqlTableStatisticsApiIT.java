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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.shared.security.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.List;

import static dk.trustworks.essentials.components.foundation.postgresql.stats.PostgresqlStatisticsTable.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class DefaultPostgresqlTableStatisticsApiIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("test-db");

    private static final EssentialsSecurityProvider ALL_ACCESS = new EssentialsSecurityProvider.AllAccessSecurityProvider();

    private Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE IF NOT EXISTS stats_queue (id BIGINT PRIMARY KEY, payload TEXT)");
            handle.execute("CREATE TABLE IF NOT EXISTS stats_lock (name TEXT PRIMARY KEY)");
            handle.execute("CREATE TABLE IF NOT EXISTS stats_no_index (value TEXT)");
            // Room on every page for an updated row version, so updates of the unindexed column can be HOT
            handle.execute("CREATE TABLE IF NOT EXISTS stats_indexed (id BIGINT PRIMARY KEY, code TEXT UNIQUE, status TEXT, note TEXT) WITH (fillfactor = 50)");
            handle.execute("CREATE INDEX IF NOT EXISTS stats_indexed_status ON stats_indexed (status)");
            handle.execute("CREATE INDEX IF NOT EXISTS stats_indexed_never_used ON stats_indexed (note, status)");
        });
    }

    @Test
    void tables_are_reported_grouped_by_section_and_missing_or_repeated_tables_are_left_out() {
        var api = api(List.of(PostgresqlStatisticsTableProvider.of(SECTION_DURABLE_QUEUES, "stats_queue", "not_created_yet"),
                              PostgresqlStatisticsTableProvider.of(SECTION_FENCED_LOCKS, "stats_lock"),
                              PostgresqlStatisticsTableProvider.of(SECTION_INFRASTRUCTURE, "stats_no_index"),
                              // A second contribution to an earlier section, and a repeat of an earlier table
                              PostgresqlStatisticsTableProvider.of(SECTION_DURABLE_QUEUES, "STATS_LOCK")));

        var statistics = api.fetchTableStatistics("principal");

        assertThat(statistics).extracting(ApiTableStatistics::section, ApiTableStatistics::tableName)
                              .containsExactly(tuple(SECTION_DURABLE_QUEUES, "stats_queue"),
                                               tuple(SECTION_FENCED_LOCKS, "stats_lock"),
                                               tuple(SECTION_INFRASTRUCTURE, "stats_no_index"));
        var queue = statistics.getFirst();
        assertThat(queue.totalSizeBytes()).isGreaterThanOrEqualTo(queue.tableSizeBytes() + queue.indexSizeBytes());
        assertThat(queue.indexSizeBytes()).isPositive();
        assertThat(queue.totalSize()).isNotBlank();
    }

    @Test
    void the_cache_hit_ratio_is_a_percentage_and_null_until_there_has_been_block_access() {
        var api = api(List.of(PostgresqlStatisticsTableProvider.of(SECTION_DURABLE_QUEUES, "stats_queue"),
                              PostgresqlStatisticsTableProvider.of(SECTION_INFRASTRUCTURE, "stats_no_index")));
        assertThat(api.fetchTableStatistics("principal"))
                .filteredOn(table -> table.tableName().equals("stats_no_index"))
                .singleElement()
                .satisfies(table -> assertThat(table.cacheHitRatio()).isNull());

        jdbi.useHandle(handle -> {
            for (int i = 0; i < 100; i++) {
                handle.execute("INSERT INTO stats_queue (id, payload) VALUES (?, 'x') ON CONFLICT DO NOTHING", i);
            }
            for (int i = 0; i < 100; i++) {
                handle.createQuery("SELECT payload FROM stats_queue WHERE id = :id").bind("id", i).mapTo(String.class).findOne();
            }
            handle.execute("SELECT pg_stat_force_next_flush()");
        });

        // Cumulative statistics are flushed by the backend that did the work, asynchronously to this reader
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var queue = api.fetchTableStatistics("principal").getFirst();
            assertThat(queue.cacheHitRatio()).isNotNull().isBetween(1.0, 100.0);
            assertThat(queue.rowsInserted()).isGreaterThanOrEqualTo(100);
            assertThat(queue.idxScan()).isPositive();
        });
    }

    @Test
    void indexes_are_reported_with_their_usage_and_updates_that_touch_no_index_count_as_hot() {
        var api = api(List.of(PostgresqlStatisticsTableProvider.of(SECTION_DURABLE_QUEUES, "stats_indexed")));
        jdbi.useHandle(handle -> {
            for (int i = 0; i < 50; i++) {
                handle.execute("INSERT INTO stats_indexed (id, code, status) VALUES (?, ?, 'NEW') ON CONFLICT DO NOTHING", i, "code-" + i);
            }
            handle.execute("SET enable_seqscan = off");
            for (int i = 0; i < 50; i++) {
                handle.createQuery("SELECT id FROM stats_indexed WHERE status = :status").bind("status", "NEW").mapTo(Long.class).list();
                // Every indexed column keeps its value, so the new row version needs no index entry: HOT
                handle.execute("UPDATE stats_indexed SET status = status WHERE id = ?", i);
            }
            handle.execute("SELECT pg_stat_force_next_flush()");
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var table = api.fetchTableStatistics("principal").getFirst();
            assertThat(table.indexes()).extracting(ApiIndexStatistics::indexName)
                                       .containsExactlyInAnyOrder("stats_indexed_pkey", "stats_indexed_code_key",
                                                                  "stats_indexed_status", "stats_indexed_never_used");
            assertThat(table.indexes()).allSatisfy(index -> {
                assertThat(index.sizeBytes()).isPositive();
                assertThat(index.valid()).isTrue();
            });
            assertThat(index(table, "stats_indexed_pkey").primary()).isTrue();
            assertThat(index(table, "stats_indexed_code_key").unique()).isTrue();
            assertThat(index(table, "stats_indexed_code_key").unused()).as("a unique index is kept for its constraint").isFalse();
            assertThat(index(table, "stats_indexed_status").idxScan()).isPositive();
            assertThat(index(table, "stats_indexed_never_used").unused()).isTrue();

            assertThat(table.rowsUpdated()).isGreaterThanOrEqualTo(50);
            assertThat(table.rowsHotUpdated()).isPositive();
            assertThat(table.hotUpdateRatio()).isBetween(0.1, 100.0);
        });
    }

    private static ApiIndexStatistics index(ApiTableStatistics table, String indexName) {
        return table.indexes().stream().filter(index -> index.indexName().equals(indexName)).findFirst().orElseThrow();
    }

    @Test
    void the_statistics_require_the_stats_reader_role() {
        var api = new DefaultPostgresqlTableStatisticsApi(new EssentialsSecurityProvider.NoAccessSecurityProvider(),
                                                          new JdbiUnitOfWorkFactory(jdbi),
                                                          List.of(PostgresqlStatisticsTableProvider.of(SECTION_DURABLE_QUEUES, "stats_queue")));

        assertThatThrownBy(() -> api.fetchTableStatistics("principal")).isInstanceOf(EssentialsSecurityException.class);
    }

    @Test
    void no_providers_report_nothing() {
        assertThat(api(List.of()).fetchTableStatistics("principal")).isEmpty();
    }

    private DefaultPostgresqlTableStatisticsApi api(List<PostgresqlStatisticsTableProvider> providers) {
        return new DefaultPostgresqlTableStatisticsApi(ALL_ACCESS, new JdbiUnitOfWorkFactory(jdbi), providers);
    }
}
