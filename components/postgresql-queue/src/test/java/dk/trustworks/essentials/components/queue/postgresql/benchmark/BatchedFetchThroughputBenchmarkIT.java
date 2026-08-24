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
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batched fetch versus per-queue fetch — the throughput measurement the flag has never had.
 *
 * <h2>Why this exists</h2>
 * {@code setUseBatchedFetch(true)} claims across all active queues in one statement instead of one statement per
 * queue per poll. Its correctness under competing consumers is evidenced
 * ({@code PostgresqlBatchedFetchCompetingConsumersIT}, with a negative control), but <b>nothing measured what it
 * is worth</b>, which is why it has stayed opt-in: shipping a default on the strength of a plausible mechanism is
 * how several claims in this investigation came to be withdrawn.
 *
 * <h2>Why it sweeps the queue count</h2>
 * That is the whole mechanism. Per-queue fetch issues one claim statement per active queue per poll, batched fetch
 * issues one regardless — so the saving is proportional to the number of active queues and there is, by
 * construction, <b>nothing to gain at one queue</b>. A benchmark at a fixed queue count would be measuring noise
 * and calling it a verdict.
 * <p>
 * Note {@code batchedFetchSwitchThreshold} is set to 0 in the batched arm. Its default of 4 means a 4-queue
 * deployment silently stays on per-queue fetch, so a benchmark that left it alone would compare per-queue against
 * per-queue for the smaller shapes and report a dead heat.
 *
 * <h2>What is held constant</h2>
 * Total message count, so the arms drain identical work; the same polling interval; the same
 * {@code parallelConsumers} per queue, kept small so the fetcher's polling rather than worker capacity is what
 * differs. The statement counter is the direct evidence — drain time is the consequence, the statement count is
 * the mechanism, and reporting both is what distinguishes "faster" from "faster for the reason claimed".
 * <p>
 * Opt-in via {@code -Dbenchmark.run=true}; sweep with {@code -Dfetchbench.queues=2,8,32}.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class BatchedFetchThroughputBenchmarkIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("batched-fetch-benchmark-db");

    private static final String TABLE = PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME;

    private JdbiUnitOfWorkFactory unitOfWorkFactory;
    private HikariDataSource      dataSource;

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
    void per_queue_versus_batched_fetch_across_queue_counts() {
        var queueCounts   = intsFrom("fetchbench.queues", "2,8,32");
        var totalMessages = Integer.getInteger("fetchbench.totalMessages", 4000);

        System.out.printf("%n(batched acknowledgement in both arms: %s)%n", Boolean.getBoolean("fetchbench.batchedAck"));
        System.out.printf("%-8s %-14s %-14s %-8s %-14s %-14s%n",
                          "queues", "perQueue (ms)", "batched (ms)", "ratio", "perQueue claims", "batched claims");
        for (var queues : queueCounts) {
            var perQueue = drain(false, queues, totalMessages);
            var batched  = drain(true, queues, totalMessages);
            System.out.printf("%-8d %-14d %-14d %-8.2f %-14d %-14d%n",
                              queues, perQueue.elapsedMs, batched.elapsedMs,
                              batched.elapsedMs == 0 ? Double.NaN : (double) perQueue.elapsedMs / batched.elapsedMs,
                              perQueue.claimStatements, batched.claimStatements);
        }
    }

    private Result drain(boolean useBatchedFetch, int queues, int totalMessages) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + TABLE));

        var claimStatements = new AtomicLong();
        var counting        = new AtomicBoolean();
        // Counting the actual statements PostgreSQL is asked to run, not the messages handled. Message counts are
        // equal by construction, so they can never show whether batching engaged - and "no measurable difference"
        // and "the feature never switched on" look identical without this.
        unitOfWorkFactory.getJdbi().setSqlLogger(new SqlLogger() {
            @Override
            public void logAfterExecution(StatementContext context) {
                var sql = context.getRenderedSql();
                if (counting.get() && sql != null && sql.contains("SKIP LOCKED")) {
                    claimStatements.incrementAndGet();
                }
            }
        });

        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setUseBatchedFetch(useBatchedFetch)
                                                   // 0, not the default 4 - otherwise the smaller shapes silently
                                                   // stay on per-queue fetch in both arms.
                                                   .setBatchedFetchSwitchThreshold(0)
                                                   // Batched acknowledgement in BOTH arms when asked for. Without
                                                   // it the drain is bounded by one ack transaction per message -
                                                   // the 16.5x lever - which swamps any claim saving and makes the
                                                   // fetch strategy unmeasurable. Enabling it equally in both arms
                                                   // does not bias the comparison; it removes the thing hiding it.
                                                   .setUseBatchedAcknowledgement(Boolean.getBoolean("fetchbench.batchedAck"))
                                                   .build();
        durableQueues.start();
        try {
            var messagesPerQueue = totalMessages / queues;
            var queueNames       = new ArrayList<QueueName>();
            for (var q = 0; q < queues; q++) {
                var queueName = QueueName.of("FetchBench-" + q);
                queueNames.add(queueName);
                var messages = new ArrayList<Message>();
                for (var i = 0; i < messagesPerQueue; i++) {
                    messages.add(Message.of("m-" + q + "-" + i));
                }
                durableQueues.queueMessages(queueName, messages);
            }

            var expected  = messagesPerQueue * queues;
            var handled   = new AtomicInteger();
            var startedAt = System.nanoTime();
            // Counting starts with consumption, so the enqueue statements above are excluded.
            counting.set(true);
            queueNames.forEach(queueName -> durableQueues.consumeFromQueue(
                    ConsumeFromQueue.builder()
                                    .setQueueName(queueName)
                                    .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff()
                                                                         .setRedeliveryDelay(Duration.ofMillis(100))
                                                                         .setMaximumNumberOfRedeliveries(3)
                                                                         .build())
                                    .setParallelConsumers(2)
                                    .setQueueMessageHandler(message -> handled.incrementAndGet())
                                    .build()));

            var deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            while (handled.get() < expected && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            var elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            counting.set(false);

            assertThat(handled.get())
                    .as("%s arm must deliver every message (queues=%d)", useBatchedFetch ? "batched" : "per-queue", queues)
                    .isEqualTo(expected);
            return new Result(elapsedMs, claimStatements.get());
        } finally {
            durableQueues.stop();
        }
    }

    private record Result(long elapsedMs, long claimStatements) {
    }

    private static List<Integer> intsFrom(String property, String defaultValue) {
        return Arrays.stream(System.getProperty(property, defaultValue).split(","))
                     .map(String::trim)
                     .filter(value -> !value.isEmpty())
                     .map(Integer::parseInt)
                     .toList();
    }
}
