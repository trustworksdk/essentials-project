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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Guards the plan shape of the claim statements against stale table statistics.
 * <p>
 * A queue table that drains to empty and is then analyzed records {@code reltuples = 0} while keeping its pages.
 * When the next burst arrives the planner expects no rows, and a claim whose {@code UPDATE} joins the
 * {@code FOR UPDATE SKIP LOCKED} CTE back to the table picks a nested loop that compares every claimed id with every
 * row of the queue - 185 ms instead of 4 ms per claim at 20 000 queued messages. The claim statements therefore
 * select the rows to update by primary-key array. This test recreates the stale statistics and asserts that no
 * claim joins on {@code id}. Asserting on the plan, not on timing, keeps it deterministic.
 */
@Testcontainers
class PostgresqlDurableQueuesClaimPlanIT {
    private static final String    QUEUE_TABLE     = "claim_plan_queue";
    private static final QueueName UNORDERED_QUEUE = QueueName.of("ClaimPlanUnorderedQueue");
    private static final QueueName ORDERED_QUEUE   = QueueName.of("ClaimPlanOrderedQueue");
    private static final int       MESSAGES        = 20_000;
    private static final int       CLAIM_LIMIT     = 128;
    private static final Pattern   JOIN_FILTER     = Pattern.compile("\"Join Filter\":\\s*\"([^\"]*)\"");
    private static final Pattern   JOIN_ON_ID      = Pattern.compile("\\bid = \\w+\\.id\\b");
    private static final Pattern   PRIMARY_KEY_ARRAY_LOOKUP =
            Pattern.compile("\"Index Name\":\\s*\"" + QUEUE_TABLE + "_pkey\",[^}]*?\"Index Cond\":\\s*\"\\(id = ANY ");
    /**
     * A Sort or Incremental Sort node with {@code next_delivery_ts} among its sort keys, capturing the rows it returned
     */
    private static final Pattern   SORT_ON_NEXT_DELIVERY_TS =
            Pattern.compile("\"Node Type\":\\s*\"(?:Incremental )?Sort\",[^\\[]*?\"Actual Rows\":\\s*([0-9.]+),[^\\[]*?\"Sort Key\":\\s*\\[[^\\]]*next_delivery_ts");
    private static final Pattern   ROWS_REMOVED_BY_JOIN_FILTER = Pattern.compile("\"Rows Removed by Join Filter\":\\s*([0-9.]+)");

    /**
     * The claim shape before the fix, kept as a control: if it no longer degrades, the test has not recreated
     * the stale statistics and proves nothing.
     */
    private static final String LEGACY_UNORDERED_CLAIM = """
                                                         WITH cte_unordered AS (
                                                           SELECT id FROM %1$s q
                                                           WHERE queue_name = :queueName
                                                             AND is_dead_letter_message = FALSE
                                                             AND is_being_delivered     = FALSE
                                                             AND next_delivery_ts <= :now
                                                             AND key IS NULL
                                                           ORDER BY next_delivery_ts
                                                           LIMIT :limit
                                                           FOR UPDATE SKIP LOCKED
                                                         )
                                                         UPDATE %1$s q
                                                         SET total_attempts = q.total_attempts + 1, next_delivery_ts = NULL,
                                                             is_being_delivered = TRUE, delivery_ts = :now
                                                         FROM cte_unordered u
                                                         WHERE q.id = u.id AND q.queue_name = :queueName
                                                         RETURNING q.*
                                                         """.formatted(QUEUE_TABLE);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("claim-plan-db");

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
        // Autovacuum would refresh the statistics behind the test's back
        jdbi.useHandle(handle -> handle.execute("ALTER TABLE " + QUEUE_TABLE + " SET (autovacuum_enabled = false)"));
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void the_unordered_claim_does_not_join_on_id_when_statistics_say_the_queue_is_empty() {
        createStaleStatistics(i -> Message.of("payload-" + i), UNORDERED_QUEUE);

        var legacyPlan = explainAnalyze(LEGACY_UNORDERED_CLAIM, UNORDERED_QUEUE);
        assumeThat(joinFiltersOnId(legacyPlan))
                .as("control: the legacy claim must degrade, or the stale statistics were not recreated")
                .isNotEmpty();

        var plan = explainAnalyze(durableQueues.getDurableQueuesSql().buildUnorderedSqlStatement(), UNORDERED_QUEUE);

        assertThat(joinFiltersOnId(plan)).as(plan).isEmpty();
        assertThat(plan).as("the claim must still claim a full batch").contains("\"Actual Rows\": " + CLAIM_LIMIT);
    }

    @Test
    void the_ordered_claim_does_not_join_on_id_when_statistics_say_the_queue_is_empty() {
        createStaleStatistics(i -> OrderedMessage.of("payload-" + i, "key-" + (i % 2_000), i / 2_000), ORDERED_QUEUE);

        var plan = explainAnalyze(durableQueues.getDurableQueuesSql().buildOrderedSqlStatement(false), ORDERED_QUEUE);

        // The per-key NOT EXISTS barrier has a join filter of its own (on key and key_order), which is not
        // what this test guards
        assertThat(joinFiltersOnId(plan)).as(plan).isEmpty();
    }

    /**
     * The opt-in batched fetch joined on {@code id} twice - the candidates to the locking scan, and the locked rows to
     * the {@code UPDATE} - and took 37.6 s for one poll over two queues of 20 000 messages each before it selected by
     * primary-key array too.
     */
    @Test
    void the_batched_claim_does_not_join_on_id_when_statistics_say_the_queue_is_empty() {
        createStaleStatistics(() -> {
            queue(i -> Message.of("payload-" + i), UNORDERED_QUEUE);
            queue(i -> OrderedMessage.of("payload-" + i, "key-" + (i % 2_000), i / 2_000), ORDERED_QUEUE);
        });

        var legacyPlan = explainAnalyze(LEGACY_UNORDERED_CLAIM, UNORDERED_QUEUE);
        assumeThat(joinFiltersOnId(legacyPlan))
                .as("control: the legacy claim must degrade, or the stale statistics were not recreated")
                .isNotEmpty();

        var batchedSql = durableQueues.getDurableQueuesSql()
                                      .buildBatchedSqlStatement(Map.of(),
                                                                Map.of(UNORDERED_QUEUE, CLAIM_LIMIT, ORDERED_QUEUE, CLAIM_LIMIT),
                                                                List.of(UNORDERED_QUEUE, ORDERED_QUEUE));
        var plan = explainAnalyze(batchedSql);

        // The per-key NOT EXISTS barrier has a join filter of its own (on key and key_order), which is not
        // what this test guards
        assertThat(joinFiltersOnId(plan)).as(plan).isEmpty();
        assertThat(PRIMARY_KEY_ARRAY_LOOKUP.matcher(plan).results().count())
                .as("both the locking scan and the UPDATE must select their rows by primary-key array: " + plan)
                .isEqualTo(2);
        assertThat(plan).as("the claim must still claim a full batch for each queue").contains("\"Actual Rows\": " + 2 * CLAIM_LIMIT);

        // The statement numbered every eligible message of every queue in one ROW_NUMBER() window ordered by
        // next_delivery_ts, which sorted the whole backlog - 40 000 rows here - on every poll. Each queue's candidates
        // are now taken under a LIMIT of its slots, so every sort on next_delivery_ts hands on at most one queue's slots.
        var nextDeliverySortRows = SORT_ON_NEXT_DELIVERY_TS.matcher(plan).results()
                                                           .map(result -> Double.parseDouble(result.group(1)))
                                                           .toList();
        assertThat(nextDeliverySortRows).as("the plan sorts candidates on next_delivery_ts: " + plan).isNotEmpty();
        assertThat(nextDeliverySortRows).as("every sort on next_delivery_ts is bounded by the slots: " + plan)
                                        .allMatch(rows -> rows <= CLAIM_LIMIT);
        // Nor does any join compare rows of the queue with each other: joined back to the table on key and key_order,
        // the ordered heads were planned as a nested loop over the whole queue and discarded 20 million rows (5 s)
        var rowsRemovedByJoinFilter = ROWS_REMOVED_BY_JOIN_FILTER.matcher(plan).results()
                                                                 .mapToDouble(result -> Double.parseDouble(result.group(1)))
                                                                 .sum();
        assertThat(rowsRemovedByJoinFilter).as("rows removed by join filters: " + plan).isLessThanOrEqualTo(MESSAGES);
    }

    private void createStaleStatistics(Runnable queueBacklog) {
        queueBacklog.run();
        jdbi.useHandle(handle -> {
            handle.execute("DELETE FROM " + QUEUE_TABLE);
            handle.execute("ANALYZE " + QUEUE_TABLE);
        });
        queueBacklog.run();

        var reltuples = jdbi.withHandle(handle -> handle.createQuery("SELECT reltuples FROM pg_class WHERE relname = :table")
                                                        .bind("table", QUEUE_TABLE)
                                                        .mapTo(Double.class)
                                                        .one());
        assertThat(reltuples).as("precondition: the statistics must say the table is empty").isEqualTo(0d);
    }

    private void createStaleStatistics(java.util.function.IntFunction<Message> messageFactory, QueueName queueName) {
        createStaleStatistics(() -> queue(messageFactory, queueName));
    }

    private void queue(java.util.function.IntFunction<Message> messageFactory, QueueName queueName) {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        for (var offset = 0; offset < MESSAGES; offset += 1_000) {
            var messages = new ArrayList<Message>(1_000);
            for (var i = offset; i < offset + 1_000; i++) {
                messages.add(messageFactory.apply(i));
            }
            unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(queueName, messages));
        }
    }

    /**
     * EXPLAIN ANALYZE executes the claim, so it runs in a transaction that is rolled back
     */
    private String explainAnalyze(String claimSql, QueueName queueName) {
        return jdbi.withHandle(handle -> {
            handle.begin();
            try {
                return handle.createQuery("EXPLAIN (ANALYZE, FORMAT JSON) " + claimSql)
                             .bind("queueName", queueName)
                             .bind("now", Instant.now())
                             .bind("limit", CLAIM_LIMIT)
                             .mapTo(String.class)
                             .one();
            } finally {
                handle.rollback();
            }
        });
    }

    private String explainAnalyze(DurableQueuesSql.BatchedSqlResult batchedSql) {
        return jdbi.withHandle(handle -> {
            handle.begin();
            try {
                var query = handle.createQuery("EXPLAIN (ANALYZE, FORMAT JSON) " + batchedSql.getSql())
                                  .bind("now", Instant.now());
                batchedSql.getSingleValueBindings().forEach(query::bind);
                batchedSql.getListBindings().forEach(query::bindList);
                return query.mapTo(String.class).one();
            } finally {
                handle.rollback();
            }
        });
    }

    private static List<String> joinFiltersOnId(String plan) {
        var joinFilters = new ArrayList<String>();
        var matcher     = JOIN_FILTER.matcher(plan);
        while (matcher.find()) {
            if (JOIN_ON_ID.matcher(matcher.group(1)).find()) {
                joinFilters.add(matcher.group(1));
            }
        }
        return joinFilters;
    }
}
