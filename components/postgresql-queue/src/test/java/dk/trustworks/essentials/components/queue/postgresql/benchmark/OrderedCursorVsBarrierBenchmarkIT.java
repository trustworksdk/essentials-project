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

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Barrier versus per-key cursor, through the real component rather than a prototype.
 *
 * <h2>What this is for</h2>
 * The cursor's numbers so far come from raw SQL against prototype schemas. Those established that the effect is
 * real and where it lives, but they cannot answer the question a deployment actually has: what does switching
 * {@code setUseOrderedMessageCursor(true)} do to <em>my</em> traffic. This measures the shipped flag, both arms
 * built the same way and driven through the same public API.
 *
 * <h2>Why it sweeps messages-per-key</h2>
 * Because that is the dimension the answer depends on, and reporting a single ratio would be the same mistake this
 * investigation has already made four times. The barrier's correlated {@code NOT EXISTS} rescans a key's depth per
 * candidate row, so its claim cost grows with the backlog <em>per key</em>; the cursor's is a range scan from the
 * key's cursor. Prototype numbers ranged from 26–217× when keys are few and deep down to ~2.6× when they are many
 * and shallow — so a benchmark that fixed the shape would tell you almost nothing.
 *
 * <h2>Reading the output</h2>
 * Opt-in via {@code -Dbenchmark.run=true}, and it prints rather than asserts a threshold, because a ratio that
 * varies by two orders of magnitude across shapes has no single meaningful bound. The one thing it does assert is
 * that both arms delivered every message — a fast arm that lost messages is not a faster queue, and given that the
 * first cursor prototype lost messages silently, that check is not ceremony.
 * <p>
 * Sweep it with {@code -Dcursorbench.keys=8,64,500} and {@code -Dcursorbench.messagesPerKey=...}.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class OrderedCursorVsBarrierBenchmarkIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("cursor-benchmark-db");

    private static final String TABLE = PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME;

    private JdbiUnitOfWorkFactory unitOfWorkFactory;
    private HikariDataSource      dataSource;

    /**
     * A bounded pool, not {@code Jdbi.create(url, ...)}. Without it every operation opens a fresh connection and
     * the run dies part-way through with {@code BindException: Cannot assign requested address} - the client runs
     * out of ephemeral ports long before PostgreSQL runs out of connections.
     */
    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        dataSource.setUsername(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());
        dataSource.setAutoCommit(false);
        dataSource.setMaximumPoolSize(24);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void barrier_versus_cursor_across_key_depths() {
        var keyCounts       = intsFrom("cursorbench.keys", "8,64,500");
        var messagesPerKeys = intsFrom("cursorbench.messagesPerKey", "200");

        System.out.printf("%n%-8s %-16s %-14s %-14s %-10s%n", "keys", "messages/key", "barrier (ms)", "cursor (ms)", "ratio");
        for (var keys : keyCounts) {
            for (var messagesPerKey : messagesPerKeys) {
                var barrierMs = drain(false, keys, messagesPerKey);
                var cursorMs  = drain(true, keys, messagesPerKey);
                System.out.printf("%-8d %-16d %-14d %-14d %-10.2f%n",
                                  keys, messagesPerKey, barrierMs, cursorMs,
                                  cursorMs == 0 ? Double.NaN : (double) barrierMs / cursorMs);
            }
        }
    }

    /**
     * Enqueues {@code keys × messagesPerKey} ordered messages, drains them through a consumer, and returns the
     * wall-clock drain time.
     *
     * @return milliseconds to drain, or the timeout if it did not finish
     */
    private long drain(boolean useCursor, int keys, int messagesPerKey) {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + TABLE + "_key_cursor");
            uow.handle().execute("DROP TABLE IF EXISTS " + TABLE);
        });

        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setUseOrderedMessageCursor(useCursor)
                                                   .build();
        durableQueues.start();
        try {
            var queueName = QueueName.of("CursorBench");
            var total     = keys * messagesPerKey;
            for (var order = 0; order < messagesPerKey; order++) {
                for (var key = 0; key < keys; key++) {
                    durableQueues.queueMessage(queueName, OrderedMessage.of("m-" + key + "-" + order, "key-" + key, order));
                }
            }

            // ANALYZE before timing, because a freshly-seeded table has no planner statistics and §25 measured that
            // costing ~11x on the ordered claim - larger than the effect this benchmark exists to measure. Applied
            // to both arms. Set -Dcursorbench.analyze=false to reproduce the original, un-analysed numbers.
            if (!"false".equals(System.getProperty("cursorbench.analyze"))) {
                unitOfWorkFactory.getJdbi().useHandle(handle -> {
                    try {
                        handle.getConnection().setAutoCommit(true);
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException("Could not switch to autoCommit for ANALYZE", e);
                    }
                    handle.execute("ANALYZE " + TABLE);
                    var cursorTable = TABLE + "_key_cursor";
                    if (Boolean.TRUE.equals(handle.createQuery("SELECT to_regclass(:t) IS NOT NULL").bind("t", cursorTable).mapTo(Boolean.class).one())) {
                        handle.execute("ANALYZE " + cursorTable);
                    }
                });
            }

            var handled = new java.util.concurrent.atomic.AtomicInteger();
            var startedAt = System.nanoTime();
            durableQueues.consumeFromQueue(ConsumeFromQueueBuilderHelper.build(queueName, message -> handled.incrementAndGet()));

            var deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            while (handled.get() < total && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            var elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            // The only assertion, and it is the one that matters: a faster arm that dropped messages is not a
            // faster queue. The first cursor prototype lost them silently.
            assertThat(handled.get())
                    .as("%s arm must deliver every message (keys=%d, messagesPerKey=%d)", useCursor ? "cursor" : "barrier", keys, messagesPerKey)
                    .isEqualTo(total);
            return elapsedMs;
        } finally {
            durableQueues.stop();
        }
    }

    private static List<Integer> intsFrom(String property, String defaultValue) {
        return Arrays.stream(System.getProperty(property, defaultValue).split(","))
                     .map(String::trim)
                     .filter(value -> !value.isEmpty())
                     .map(Integer::parseInt)
                     .toList();
    }

    /**
     * Keeps the consumer configuration identical between arms — the point of the benchmark is the claim strategy,
     * so anything else differing would confound it.
     */
    private static final class ConsumeFromQueueBuilderHelper {
        static dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue build(QueueName queueName,
                                                                                                               QueuedMessageHandler handler) {
            return dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue
                    .builder()
                    .setQueueName(queueName)
                    .setRedeliveryPolicy(dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy
                                                 .fixedBackoff()
                                                 .setRedeliveryDelay(Duration.ofMillis(100))
                                                 .setMaximumNumberOfRedeliveries(3)
                                                 .build())
                    .setParallelConsumers(10)
                    .setQueueMessageHandler(handler)
                    .build();
        }
    }
}
