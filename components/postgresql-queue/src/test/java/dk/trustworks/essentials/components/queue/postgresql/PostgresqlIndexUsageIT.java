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
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of the six indexes on {@code durable_queues} does anything actually use?
 *
 * <h2>Why this exists</h2>
 * The split's <em>prototype</em> win (1.38× total, 1.62× insert for unordered traffic - through the component it
 * is ~1.1-1.36×, all of it insert) was attributed to index count —
 * six secondary indexes down to one. Part of that is available with no new tables and no API change, because
 * {@code PostgresqlDurableQueues.initializeQueueTables()} creates all six **unconditionally**, regardless of
 * {@code useOrderedUnorderedQuery}. With the flag on — which it now is by default everywhere — the three indexes
 * that exist to serve the *unified* query are still built and still maintained on every insert, claim and delete.
 * <p>
 * Dropping an index nothing reads is the cheapest change available anywhere in this investigation: no migration,
 * no contract change, and it targets the one lever besides transaction count that has measured as significant.
 * But it cannot be done on inspection — a predicate can look redundant and still be the one the planner picks.
 * So this drives every {@link DurableQueues} operation against a realistic volume and reports what PostgreSQL
 * says it used.
 *
 * <h2>Reading the result honestly</h2>
 * A zero here means "not scanned by any operation this test performs", which is weaker than "unused". Two things
 * bound it, and both are why this reports rather than asserts:
 * <ul>
 *     <li><b>Coverage.</b> An index used only by an operation this test misses looks dead. The operation list
 *     below is deliberately exhaustive against the SPI for that reason.</li>
 *     <li><b>Volume.</b> On a small table the planner prefers sequential scans and *every* index looks dead. The
 *     row count here is chosen so index paths win; a much smaller or much larger table could shift which index
 *     is chosen for the same query.</li>
 * </ul>
 * Opt-in via {@code -Dbenchmark.run=true}: it measures rather than asserting behaviour, so per the project's
 * testing conventions it must not cost anything on a normal build.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class PostgresqlIndexUsageIT {

    /**
     * Large enough that the planner prefers index paths over sequential scans, which is a precondition for the
     * result meaning anything at all.
     */
    private static final int MESSAGE_COUNT = Integer.getInteger("indexusage.messages", 40_000);
    /**
     * Swept via {@code -Dindexusage.orderedKeys}: §11 showed the ordered claim's plan is highly sensitive to
     * messages-per-key, so an index that looks dead at one cardinality could be chosen at another. A finding here
     * is only safe to act on if it survives a second shape.
     */
    private static final int ORDERED_KEYS  = Integer.getInteger("indexusage.orderedKeys", 200);

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("index-usage-queue-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    // Pooled deliberately: an unpooled Jdbi opens a connection per handle, and sixteen consumer threads plus a
    // polling loop exhaust the ephemeral port range - the first run of this test died with
    // "BindException: Cannot assign requested address" rather than producing a report.
    private HikariDataSource        dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        dataSource.setUsername(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());
        dataSource.setAutoCommit(false);
        dataSource.setMaximumPoolSize(24);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                  .execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
        durableQueues = PostgresqlDurableQueues.builder()
                                              .setUnitOfWorkFactory(unitOfWorkFactory)
                                              .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                              .build();
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void report_which_indexes_every_durable_queues_operation_actually_uses() {
        var unorderedQueue = QueueName.of("IndexUsageUnordered");
        var orderedQueue   = QueueName.of("IndexUsageOrdered");

        // ---- enqueue, both delivery modes ----
        var unorderedIds = new ArrayList<QueueEntryId>();
        for (var offset = 0; offset < MESSAGE_COUNT / 2; offset += 1000) {
            var chunk = new ArrayList<Message>();
            for (var i = offset; i < Math.min(offset + 1000, MESSAGE_COUNT / 2); i++) {
                chunk.add(Message.of("unordered-" + i));
            }
            unorderedIds.addAll(unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.queueMessages(unorderedQueue, chunk)));
        }
        for (var offset = 0; offset < MESSAGE_COUNT / 2; offset += 1000) {
            var chunk = new ArrayList<Message>();
            for (var i = offset; i < Math.min(offset + 1000, MESSAGE_COUNT / 2); i++) {
                chunk.add(OrderedMessage.of("ordered-" + i, "key-" + (i % ORDERED_KEYS), (long) (i / ORDERED_KEYS)));
            }
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessages(orderedQueue, chunk));
        }

        // ---- the single-message and query operations, each at least once ----
        var probeId = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.queueMessage(unorderedQueue, Message.of("probe")));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessage(unorderedQueue, Message.of("delayed"), Duration.ofHours(1)));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessageAsDeadLetterMessage(unorderedQueue, Message.of("born-dead"), new RuntimeException("seed")));

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            durableQueues.getQueuedMessage(probeId);
            durableQueues.getNextMessageReadyForDelivery(unorderedQueue);
            durableQueues.getNextMessageReadyForDelivery(orderedQueue);
            durableQueues.getTotalMessagesQueuedFor(unorderedQueue);
            durableQueues.getQueuedMessageCountsFor(unorderedQueue);
            durableQueues.getTotalDeadLetterMessagesQueuedFor(unorderedQueue);
            durableQueues.getQueuedMessages(unorderedQueue, DurableQueues.QueueingSortOrder.ASC, 0, 100);
            durableQueues.getDeadLetterMessages(unorderedQueue, DurableQueues.QueueingSortOrder.ASC, 0, 100);
            durableQueues.getQueueNames();
        });

        // retry, dead-letter both ways, resurrect, delete - the state transitions each touch different predicates
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.retryMessage(probeId, new RuntimeException("retry"), Duration.ofMillis(1)));
        var toDeadLetter = unorderedIds.get(0);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.markAsDeadLetterMessage(toDeadLetter, new RuntimeException("dlq")));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.getDeadLetterMessage(toDeadLetter));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.resurrectDeadLetterMessage(toDeadLetter, Duration.ofMillis(1)));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.markAsDeadLetterMessageDirect(unorderedIds.get(1), new RuntimeException("dlq-direct")));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.deleteMessage(unorderedIds.get(2)));

        // ---- the hot path: real consumers claiming and acknowledging both queues ----
        var handled = new java.util.concurrent.atomic.AtomicInteger();
        var consumers = List.of(consume(unorderedQueue, handled), consume(orderedQueue, handled));
        try {
            Awaitility.waitAtMost(Duration.ofMinutes(3))
                      .pollInterval(Duration.ofMillis(250))
                      .untilAsserted(() -> {
                          long remaining = unitOfWorkFactory.withUnitOfWork(unitOfWork -> durableQueues.getTotalMessagesQueuedFor(unorderedQueue)
                                  + durableQueues.getTotalMessagesQueuedFor(orderedQueue));
                          // The delayed message stays, so this is the floor rather than zero.
                          assertThat(remaining).isLessThanOrEqualTo(2L);
                      });
        } finally {
            consumers.forEach(DurableQueueConsumer::cancel);
        }
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.purgeQueue(unorderedQueue));

        // PostgreSQL flushes index statistics asynchronously and coalesces within roughly a second, so an
        // immediate read under-reports. This is the same hazard that made pg_stat_database unusable for the
        // framework-overhead scenario; here a wait is enough because the counters are cumulative.
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var usage = unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                            .createQuery("""
                                                                                         SELECT indexrelname                AS index_name,
                                                                                                idx_scan                    AS scans,
                                                                                                pg_relation_size(indexrelid) AS bytes
                                                                                           FROM pg_stat_user_indexes
                                                                                          WHERE relname = :table
                                                                                          ORDER BY idx_scan, indexrelname
                                                                                         """)
                                                                            .bind("table", PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME)
                                                                            .map((rs, ctx) -> new IndexUsage(rs.getString("index_name"),
                                                                                                             rs.getLong("scans"),
                                                                                                             rs.getLong("bytes")))
                                                                            .list());

        System.out.println("############# durable_queues index usage after exercising every DurableQueues operation");
        System.out.printf("%-46s %12s %12s%n", "index", "scans", "bytes");
        usage.forEach(u -> System.out.printf("%-46s %12d %12d%n", u.indexName(), u.scans(), u.bytes()));
        var unused = usage.stream().filter(u -> u.scans() == 0).toList();
        System.out.println("############# never scanned by any operation in this suite: "
                                   + (unused.isEmpty() ? "(none)" : unused.stream().map(IndexUsage::indexName).toList()));
        System.out.println("############# bytes held by never-scanned indexes: "
                                   + unused.stream().mapToLong(IndexUsage::bytes).sum());

        // The report is the deliverable. The only assertion is that the suite actually exercised index paths at
        // all - if nothing was scanned, the volume was too low and the whole result is meaningless rather than
        // interesting.
        assertThat(usage).isNotEmpty();
        assertThat(usage.stream().mapToLong(IndexUsage::scans).sum())
                .as("if no index was scanned at all, the table is too small for the planner to prefer index paths "
                            + "and this report says nothing")
                .isPositive();
    }

    private DurableQueueConsumer consume(QueueName queueName, java.util.concurrent.atomic.AtomicInteger handled) {
        return durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                              .setQueueName(queueName)
                                                              .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), 3))
                                                              .setParallelConsumers(8)
                                                              .setQueueMessageHandler(message -> handled.incrementAndGet())
                                                              .build());
    }

    private record IndexUsage(String indexName, long scans, long bytes) {
    }
}
