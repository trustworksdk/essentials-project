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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drain throughput through a bounded connection pool, with a handler that writes in its transaction.
 * <p>
 * Every handler runs inside a unit of work that takes a pooled connection for its whole duration, and the
 * acknowledgement takes another. With more consumers than connections, handlers queue on the pool, and this
 * reports how many waited, alongside the throughput.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class QueueDrainThroughputIT {
    private static final int      QUEUES             = 2;
    private static final int      MESSAGES_PER_QUEUE = 20_000;
    private static final int      PARALLEL_CONSUMERS = 32;
    private static final int      POOL_SIZE          = 10;
    private static final int      ROUNDS             = 3;

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("drain-throughput-db");

    private HikariDataSource        dataSource;
    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    private final List<DurableQueueConsumer> consumers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        config.setUsername(postgreSQLContainer.getUsername());
        config.setPassword(postgreSQLContainer.getPassword());
        config.setMaximumPoolSize(POOL_SIZE);
        dataSource = new HikariDataSource(config);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .build();
        durableQueues.start();
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("CREATE TABLE IF NOT EXISTS handled (queue TEXT, payload TEXT)"));
    }

    @AfterEach
    void tearDown() {
        consumers.forEach(DurableQueueConsumer::cancel);
        durableQueues.stop();
        dataSource.close();
    }

    @Test
    void drain_through_a_bounded_pool() throws Exception {
        var results = new ArrayList<String>();
        for (var round = 1; round <= ROUNDS; round++) {
            results.add(drainOnce(round));
        }
        System.out.printf("%n=== Drain %d queues x %,d messages, %d consumers each, pool %d ===%n%s%n%n",
                          QUEUES, MESSAGES_PER_QUEUE, PARALLEL_CONSUMERS, POOL_SIZE, String.join("\n", results));
    }

    private String drainOnce(int round) throws Exception {
        consumers.forEach(DurableQueueConsumer::cancel);
        consumers.clear();
        var queueNames = new ArrayList<QueueName>();
        for (var q = 0; q < QUEUES; q++) {
            var queueName = QueueName.of("Drain-" + round + "-" + q);
            queueNames.add(queueName);
            for (var offset = 0; offset < MESSAGES_PER_QUEUE; offset += 500) {
                var messages = new ArrayList<Message>(500);
                for (var i = offset; i < offset + 500; i++) {
                    messages.add(Message.of("payload-" + i));
                }
                unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(queueName, messages));
            }
        }

        var handled        = new AtomicInteger();
        var maxPending     = new AtomicInteger();
        var pendingSamples = new LongAdder();
        var samples        = new LongAdder();
        var sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> {
            var pending = dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection();
            maxPending.accumulateAndGet(pending, Math::max);
            pendingSamples.add(pending);
            samples.increment();
        }, 0, 10, TimeUnit.MILLISECONDS);

        var startedAt = System.nanoTime();
        for (var queueName : queueNames) {
            consumers.add(durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                         .setQueueName(queueName)
                                                                         .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                         .setParallelConsumers(PARALLEL_CONSUMERS)
                                                                         .setQueueMessageHandler(message -> {
                                                                             unitOfWorkFactory.getRequiredUnitOfWork().handle()
                                                                                              .execute("INSERT INTO handled VALUES (?, ?)", queueName.toString(), message.getPayload().toString());
                                                                             handled.incrementAndGet();
                                                                         })
                                                                         .build()));
        }
        var total = QUEUES * MESSAGES_PER_QUEUE;
        Awaitility.waitAtMost(Duration.ofMinutes(5))
                  .pollInterval(Duration.ofMillis(20))
                  .until(() -> handled.get() >= total && queueNames.stream().allMatch(q -> durableQueues.getTotalMessagesQueuedFor(q) == 0));
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        sampler.shutdownNow();

        assertThat(handled.get()).isEqualTo(total);
        return String.format("  round %d: %,6d ms, %,7.0f msg/s, threads awaiting a connection: avg %.1f, max %d",
                             round, elapsed.toMillis(), total * 1_000d / elapsed.toMillis(),
                             (double) pendingSamples.sum() / Math.max(1, samples.sum()), maxPending.get());
    }
}
