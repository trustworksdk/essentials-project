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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivery latency of a message enqueued by another node to a queue that has been idle, wired like the Spring Boot
 * starter (see {@link SpringWiredDurableQueues}): two
 * {@link PostgresqlDurableQueues} instances share the table, one consumes, the other produces. Uses only APIs that
 * exist on every 0.60 release, so it runs before and after a change.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class CrossNodeLatencyBenchmarkIT {
    private static final QueueName QUEUE   = QueueName.of("CrossNode");
    private static final int       SAMPLES = 15;

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("cross-node-db");

    private HikariDataSource        dataSource;
    private PostgresqlDurableQueues consumingNode;
    private PostgresqlDurableQueues producingNode;
    private JdbiUnitOfWorkFactory   producerUnitOfWorkFactory;
    private SpringWiredDurableQueues consumingWired;
    private SpringWiredDurableQueues producingWired;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        config.setUsername(postgreSQLContainer.getUsername());
        config.setPassword(postgreSQLContainer.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        var consumerJdbi = Jdbi.create(dataSource);
        consumingWired = new SpringWiredDurableQueues(consumerJdbi, new JdbiUnitOfWorkFactory(consumerJdbi));
        consumingNode = consumingWired.durableQueues;
        var producerJdbi = Jdbi.create(dataSource);
        producerUnitOfWorkFactory = new JdbiUnitOfWorkFactory(producerJdbi);
        producingWired = new SpringWiredDurableQueues(producerJdbi, producerUnitOfWorkFactory);
        producingNode = producingWired.durableQueues;
    }

    @AfterEach
    void tearDown() {
        producingWired.close();
        consumingWired.close();
        dataSource.close();
    }

    @Test
    void latency_after_idle() throws Exception {
        var handledAt = new AtomicLong();
        var consumer = consumingNode.consumeFromQueue(ConsumeFromQueue.builder()
                                                                      .setQueueName(QUEUE)
                                                                      .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                      .setParallelConsumers(1)
                                                                      .setQueueMessageHandler(message -> handledAt.set(System.nanoTime()))
                                                                      .build());
        var latencies = new ArrayList<Long>();
        for (var sample = 0; sample < SAMPLES; sample++) {
            // Idle long enough for any backoff to reach its cap
            Thread.sleep(2_000);
            handledAt.set(0);
            var queuedAt = System.nanoTime();
            producerUnitOfWorkFactory.usingUnitOfWork(() -> producingNode.queueMessage(QUEUE, Message.of("payload")));
            Awaitility.waitAtMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(1)).until(() -> handledAt.get() != 0);
            latencies.add((handledAt.get() - queuedAt) / 1_000_000);
        }
        consumer.cancel();
        var sorted = latencies.stream().sorted().toList();
        System.out.printf("%n=== Message from another node after 2 s idle, Spring wiring, %d samples: p50 %d ms, max %d ms ===%n%n",
                          SAMPLES, sorted.get(SAMPLES / 2), sorted.getLast());
    }
}
