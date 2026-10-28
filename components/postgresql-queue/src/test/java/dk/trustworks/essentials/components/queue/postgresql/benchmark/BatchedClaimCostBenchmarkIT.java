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

package dk.trustworks.essentials.components.queue.postgresql.benchmark;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.*;

/**
 * Cost of one batched claim ({@code useBatchedFetch}) against the statement it replaced
 * ({@code LegacyBatchedClaimStatement}), and against the per-queue claims for the same queues, with fresh table
 * statistics and with statistics recorded while the table was empty. Each claim runs in a transaction that is rolled
 * back, so every run sees the same backlog. Prints the median of {@value #RUNS} runs; asserts nothing.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class BatchedClaimCostBenchmarkIT {
    private static final String    QUEUE_TABLE     = "batched_claim_cost_queue";
    private static final QueueName UNORDERED_QUEUE = QueueName.of("BatchedCostUnordered");
    private static final QueueName ORDERED_QUEUE   = QueueName.of("BatchedCostOrdered");
    private static final int       SLOTS           = 128;
    private static final int       WARMUP_RUNS     = 3;
    private static final int       RUNS            = 15;

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("batched-claim-cost-db");

    private Jdbi                    jdbi;
    private PostgresqlDurableQueues durableQueues;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .build();
        durableQueues.start();
        jdbi.useHandle(handle -> handle.execute("ALTER TABLE " + QUEUE_TABLE + " SET (autovacuum_enabled = false)"));
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void unordered_and_many_keys_2_queues_x_20_000() {
        Runnable backlog = () -> {
            insert(UNORDERED_QUEUE, 20_000, null);
            insert(ORDERED_QUEUE, 20_000, 2_000);
        };
        measure("2 x 20 000 (unordered + 2 000 keys x 10)", backlog, List.of(UNORDERED_QUEUE, ORDERED_QUEUE), false);
    }

    @Test
    void few_long_keys_20_x_1_000() {
        measure("20 keys x 1 000", () -> insert(ORDERED_QUEUE, 20_000, 20), List.of(ORDERED_QUEUE), true);
    }

    @Test
    void many_short_keys_20_000_x_1() {
        measure("20 000 keys x 1", () -> insert(ORDERED_QUEUE, 20_000, 20_000), List.of(ORDERED_QUEUE), false);
    }

    private void measure(String scenario, Runnable backlog, List<QueueName> queues, boolean perQueueHeadScan) {
        var slots = new HashMap<QueueName, Integer>();
        queues.forEach(queue -> slots.put(queue, SLOTS));
        var current = new DurableQueuesSql(QUEUE_TABLE).buildBatchedSqlStatement(Map.of(), slots, queues);
        var legacy  = LegacyBatchedClaimStatement.build(QUEUE_TABLE, Map.of(), slots, queues);

        // Statistics that say the table is empty: fill, empty, analyze, refill
        jdbi.useHandle(handle -> handle.execute("TRUNCATE " + QUEUE_TABLE));
        backlog.run();
        jdbi.useHandle(handle -> {
            handle.execute("DELETE FROM " + QUEUE_TABLE);
            handle.execute("ANALYZE " + QUEUE_TABLE);
        });
        backlog.run();
        report(scenario, "stale statistics", current, legacy, queues, perQueueHeadScan);

        jdbi.useHandle(handle -> handle.execute("VACUUM ANALYZE " + QUEUE_TABLE));
        report(scenario, "fresh statistics", current, legacy, queues, perQueueHeadScan);
    }

    private void report(String scenario, String statistics, DurableQueuesSql.BatchedSqlResult current, DurableQueuesSql.BatchedSqlResult legacy,
                        List<QueueName> queues, boolean perQueueHeadScan) {
        var sql            = new DurableQueuesSql(QUEUE_TABLE);
        var perQueueMillis = 0d;
        for (var queue : queues) {
            if (queue == UNORDERED_QUEUE) {
                perQueueMillis += medianMillis(() -> claim(sql.buildUnorderedSqlStatement(), queue));
            } else {
                // The per-queue chooser settles on the cheaper of its two ordered statements. The head scan is only
                // measured where it can win: with many keys it reads the queue once per key on stale statistics.
                var defaultMillis = medianMillis(() -> claim(sql.buildOrderedSqlStatement(false), queue));
                perQueueMillis += perQueueHeadScan
                                  ? Math.min(defaultMillis, medianMillis(() -> claim(sql.buildOrderedHeadScanSqlStatement(false), queue)))
                                  : defaultMillis;
            }
        }
        System.out.printf("[batched claim] %-42s %-17s legacy %8.1f ms   current %7.1f ms   per-queue claims %7.1f ms%n",
                          scenario, statistics, medianMillis(() -> claim(legacy)), medianMillis(() -> claim(current)), perQueueMillis);
    }

    private double medianMillis(Runnable claim) {
        for (var i = 0; i < WARMUP_RUNS; i++) {
            claim.run();
        }
        var nanos = new long[RUNS];
        for (var i = 0; i < RUNS; i++) {
            var start = System.nanoTime();
            claim.run();
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        return nanos[RUNS / 2] / 1_000_000d;
    }

    private void claim(DurableQueuesSql.BatchedSqlResult statement) {
        jdbi.useHandle(handle -> {
            handle.begin();
            try {
                var query = handle.createQuery(statement.getSql()).bind("now", Instant.now());
                statement.getSingleValueBindings().forEach(query::bind);
                statement.getListBindings().forEach(query::bindList);
                query.mapToMap().list();
            } finally {
                handle.rollback();
            }
        });
    }

    private void claim(String perQueueSql, QueueName queue) {
        jdbi.useHandle(handle -> {
            handle.begin();
            try {
                handle.createQuery(perQueueSql)
                      .bind("queueName", queue.toString())
                      .bind("now", Instant.now())
                      .bind("limit", SLOTS)
                      .mapToMap()
                      .list();
            } finally {
                handle.rollback();
            }
        });
    }

    /**
     * {@code messages} messages, oldest first; ordered over {@code keys} keys when {@code keys} is given
     */
    private void insert(QueueName queue, int messages, Integer keys) {
        jdbi.useHandle(handle -> handle.createUpdate("INSERT INTO " + QUEUE_TABLE + " (id, queue_name, message_payload, message_payload_type, added_ts, " +
                                                             "next_delivery_ts, delivery_mode, key, key_order) " +
                                                             "SELECT :queueName || '-' || i, :queueName, '{\"payload\":\"x\"}'::jsonb, 'java.lang.String', " +
                                                             "now() - interval '1 hour' + i * interval '1 ms', now() - interval '1 hour' + i * interval '1 ms', " +
                                                             "CASE WHEN :keys IS NULL THEN 'NORMAL' ELSE 'IN_ORDER' END, " +
                                                             "CASE WHEN :keys IS NULL THEN NULL ELSE 'key-' || (i % :keys) END, " +
                                                             "CASE WHEN :keys IS NULL THEN -1 ELSE i / :keys END " +
                                                             "FROM generate_series(0, :messages - 1) i")
                                       .bind("queueName", queue.toString())
                                       .bind("keys", keys)
                                       .bind("messages", messages)
                                       .execute());
    }
}
