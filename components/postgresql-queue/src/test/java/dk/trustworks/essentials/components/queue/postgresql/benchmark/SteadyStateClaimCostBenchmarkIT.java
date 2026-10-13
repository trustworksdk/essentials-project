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

import com.zaxxer.hikari.*;
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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.jdbi.v3.core.statement.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Database cost and latency at a steady offered load with the default 20 ms polling: claim statements per delivered
 * message, and the delay from enqueue to handler. Also a retry wave: messages that each fail once and come back after
 * a redelivery delay. Uses only APIs that exist on every 0.60 release, so it runs before and after a change.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class SteadyStateClaimCostBenchmarkIT {
    private static final int      PARALLEL_CONSUMERS = 8;
    private static final Duration LOAD_DURATION      = Duration.ofSeconds(10);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("steady-state-db");

    private HikariDataSource        dataSource;
    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    private SpringWiredDurableQueues wired;
    private final AtomicLong        claimStatements = new AtomicLong();

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        config.setUsername(postgreSQLContainer.getUsername());
        config.setPassword(postgreSQLContainer.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        var jdbi = Jdbi.create(dataSource);
        jdbi.setSqlLogger(new SqlLogger() {
            @Override
            public void logBeforeExecution(StatementContext context) {
                var sql = context.getRenderedSql();
                if (sql != null && (sql.contains("cte_ordered") || sql.contains("cte_unordered"))) {
                    claimStatements.incrementAndGet();
                }
            }
        });
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        wired = new SpringWiredDurableQueues(jdbi, unitOfWorkFactory);
        durableQueues = wired.durableQueues;
    }

    @AfterEach
    void tearDown() {
        wired.close();
        dataSource.close();
    }

    @Test
    void steady_load_and_retry_wave() throws Exception {
        var results = new ArrayList<String>();
        for (var rate : List.of(200, 2_000)) {
            results.add(steadyLoad(rate));
        }
        results.add(retryWave(1_000, Duration.ofMillis(500)));
        System.out.printf("%n=== Steady-state claim cost, %d consumers, 20 ms polling ===%n%s%n%n", PARALLEL_CONSUMERS, String.join("\n", results));
    }

    private String steadyLoad(int messagesPerSecond) throws Exception {
        var queueName = QueueName.of("Steady-" + messagesPerSecond);
        var latencies = new ConcurrentLinkedQueue<Long>();
        var handled   = new AtomicInteger();
        var consumer = consume(queueName, message -> {
            latencies.add(Duration.between(message.getAddedTimestamp(), OffsetDateTime.now()).toMillis());
            handled.incrementAndGet();
        }, RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3));
        Thread.sleep(500);
        claimStatements.set(0);

        var perTick = Math.max(1, messagesPerSecond / 100);
        var sent    = 0;
        var until   = System.nanoTime() + LOAD_DURATION.toNanos();
        var next    = System.nanoTime();
        while (System.nanoTime() < until) {
            var batch = new ArrayList<Message>(perTick);
            for (var i = 0; i < perTick; i++) {
                batch.add(Message.of("payload-" + sent++));
            }
            unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(queueName, batch));
            next += 10_000_000L;
            var sleep = next - System.nanoTime();
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
            }
        }
        var total = sent;
        Awaitility.waitAtMost(Duration.ofMinutes(2)).until(() -> handled.get() >= total);
        var claims = claimStatements.get();
        consumer.cancel();
        var sorted = latencies.stream().sorted().toList();
        return String.format("  %,5d msg/s offered: %,7d messages, %.3f claim statements per message, latency p50 %,d ms, p99 %,d ms, max %,d ms",
                             messagesPerSecond, total, (double) claims / total,
                             percentile(sorted, 0.50), percentile(sorted, 0.99), sorted.getLast());
    }

    private String retryWave(int messages, Duration redeliveryDelay) {
        var queueName = QueueName.of("RetryWave");
        var attempts  = new ConcurrentHashMap<String, AtomicInteger>();
        var handled   = new AtomicInteger();
        var consumer = consume(queueName, message -> {
            if (attempts.computeIfAbsent(message.getPayload().toString(), p -> new AtomicInteger()).incrementAndGet() == 1) {
                throw new IllegalStateException("fail once");
            }
            handled.incrementAndGet();
        }, RedeliveryPolicy.fixedBackoff(redeliveryDelay, 3));
        var batch = new ArrayList<Message>(messages);
        for (var i = 0; i < messages; i++) {
            batch.add(Message.of("retry-" + i));
        }
        claimStatements.set(0);
        var startedAt = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(queueName, batch));
        Awaitility.waitAtMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(10)).until(() -> handled.get() >= messages);
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        var claims    = claimStatements.get();
        consumer.cancel();
        return String.format("  retry wave, %,d messages failing once, %d ms redelivery delay: all handled after %,d ms, %,d claim statements",
                             messages, redeliveryDelay.toMillis(), elapsedMs, claims);
    }

    private DurableQueueConsumer consume(QueueName queueName, QueuedMessageHandler handler, RedeliveryPolicy policy) {
        return durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                              .setQueueName(queueName)
                                                              .setRedeliveryPolicy(policy)
                                                              .setParallelConsumers(PARALLEL_CONSUMERS)
                                                              .setQueueMessageHandler(handler)
                                                              .build());
    }

    private static long percentile(List<Long> sorted, double percentile) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1));
    }
}
