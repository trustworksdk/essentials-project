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
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures which of the queue table's indexes the claim queries actually use, by reading
 * {@code pg_stat_user_indexes.idx_scan} after driving a representative ordered and unordered workload.
 * <p>
 * This exists because removing the unified claim query in 0.60 changed which statements the surviving indexes
 * have to serve, and two of the four plausibly serve the same {@code NOT EXISTS} barrier. Which of them still
 * earns its write cost is a question for a scan count, not for an argument — the refactor plan says so
 * explicitly, and this is the measurement it asks for.
 * <p>
 * Reports rather than asserts, beyond a sanity check that the workload touched the table at all, so it is gated
 * off by default like the other benchmark suites. Run it with:
 * <pre>{@code mvn verify -pl components/postgresql-queue -Dit.test=QueueIndexScanCountIT -Dbenchmark.run=true}</pre>
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class QueueIndexScanCountIT {
    private static final String QUEUE_TABLE = "durable_queues";
    private static final int    MESSAGES    = 200;

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("index-scan-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    private final List<DurableQueueConsumer> consumers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                   postgreSQLContainer.getUsername(),
                                                                   postgreSQLContainer.getPassword()));
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
        consumers.forEach(DurableQueueConsumer::cancel);
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void report_index_scan_counts_for_high_key_cardinality() {
        // idx_..._ordered_ready is key-leading, so if any shape chooses it, it is one where the key is highly
        // selective: one message per key rather than a few long per-key runs.
        var orderedQueue = QueueName.of("IndexScanHighCardinalityQueue");
        durableQueues.purgeQueue(orderedQueue);

        resetIndexStatistics();

        unitOfWorkFactory.usingUnitOfWork(() -> {
            for (var i = 0; i < MESSAGES; i++) {
                durableQueues.queueMessage(orderedQueue, OrderedMessage.of("payload-" + i, "key-" + i, 0));
            }
        });

        var handled = new AtomicInteger();
        consumeFrom(orderedQueue, handled);
        Awaitility.waitAtMost(Duration.ofMinutes(2))
                  .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGES));

        var scans = readIndexScanCounts();
        System.out.println("\n=== Queue index scan counts, " + MESSAGES + " ordered messages each with a distinct key ===");
        scans.forEach((index, count) -> System.out.printf("  %-44s %,10d%n", index, count));
        System.out.println();

        assertThat(scans.values().stream().mapToLong(Long::longValue).sum()).isPositive();
    }

    @Test
    void report_index_scan_counts_for_an_ordered_and_unordered_workload() {
        var orderedQueue   = QueueName.of("IndexScanOrderedQueue");
        var unorderedQueue = QueueName.of("IndexScanUnorderedQueue");
        durableQueues.purgeQueue(orderedQueue);
        durableQueues.purgeQueue(unorderedQueue);

        resetIndexStatistics();

        unitOfWorkFactory.usingUnitOfWork(() -> {
            for (var i = 0; i < MESSAGES; i++) {
                // Several keys, so the ordered barrier has something to exclude rather than trivially passing
                durableQueues.queueMessage(orderedQueue, OrderedMessage.of("payload-" + i, "key-" + (i % 10), i));
                durableQueues.queueMessage(unorderedQueue, Message.of("payload-" + i));
            }
        });

        var handled = new AtomicInteger();
        consumeFrom(orderedQueue, handled);
        consumeFrom(unorderedQueue, handled);

        Awaitility.waitAtMost(Duration.ofMinutes(2))
                  .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGES * 2));

        var scans = readIndexScanCounts();
        System.out.println("\n=== Queue index scan counts after " + (MESSAGES * 2) + " ordered + unordered deliveries ===");
        scans.forEach((index, count) -> System.out.printf("  %-44s %,10d%n", index, count));
        System.out.println("  An index at 0 scans is paying write cost for nothing on this workload.\n");

        assertThat(scans)
                .as("the queue table's indexes must be visible to pg_stat_user_indexes")
                .isNotEmpty();
        assertThat(scans.values().stream().mapToLong(Long::longValue).sum())
                .as("the workload must have driven at least some index scans, or the measurement says nothing")
                .isPositive();
    }

    @Test
    void report_index_scan_counts_at_a_table_size_where_the_planner_has_a_real_choice() {
        // Index choice is size-dependent: on a few hundred rows the planner may pick anything. This fills the
        // table, ANALYZEs it so the statistics are real, then drives the ordered claim query directly rather
        // than through consumers, so the counts belong to the statement under investigation.
        var orderedQueue = QueueName.of("IndexScanBulkQueue");
        durableQueues.purgeQueue(orderedQueue);

        var bulk = 20_000;
        for (var batch = 0; batch < bulk / 1_000; batch++) {
            var offset   = batch * 1_000;
            var messages = new ArrayList<Message>(1_000);
            for (var i = 0; i < 1_000; i++) {
                messages.add(OrderedMessage.of("payload-" + (offset + i), "key-" + ((offset + i) % 500), offset + i));
            }
            unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(orderedQueue, messages));
        }
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("ANALYZE " + QUEUE_TABLE));

        resetIndexStatistics();
        for (var i = 0; i < 200; i++) {
            unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.getNextMessageReadyForDelivery(orderedQueue));
        }

        var scans = readIndexScanCounts();
        System.out.println("\n=== Queue index scan counts, 200 ordered claims against a " + String.format("%,d", bulk) + "-row table ===");
        scans.forEach((index, count) -> System.out.printf("  %-44s %,10d%n", index, count));
        System.out.println();

        assertThat(scans.values().stream().mapToLong(Long::longValue).sum()).isPositive();
    }

    private void consumeFrom(QueueName queueName, AtomicInteger handled) {
        consumers.add(durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                     .setQueueName(queueName)
                                                                     .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), 3))
                                                                     .setParallelConsumers(2)
                                                                     .setQueueMessageHandler(message -> handled.incrementAndGet())
                                                                     .build()));
    }

    private void resetIndexStatistics() {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("SELECT pg_stat_reset()"));
    }

    /**
     * {@code pg_stat_force_next_flush} makes the backend's pending statistics visible immediately; without it a
     * read taken straight after the workload can report counts that are merely stale.
     */
    private SortedMap<String, Long> readIndexScanCounts() {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            uow.handle().execute("SELECT pg_stat_force_next_flush()");
            var rows = uow.handle()
                          .createQuery("""
                                       SELECT indexrelname, idx_scan
                                       FROM pg_stat_user_indexes
                                       WHERE relname = :tableName
                                       ORDER BY indexrelname
                                       """)
                          .bind("tableName", QUEUE_TABLE)
                          .map((rs, ctx) -> Map.entry(rs.getString("indexrelname"), rs.getLong("idx_scan")))
                          .list();
            return new TreeMap<>(rows.stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        });
    }
}
