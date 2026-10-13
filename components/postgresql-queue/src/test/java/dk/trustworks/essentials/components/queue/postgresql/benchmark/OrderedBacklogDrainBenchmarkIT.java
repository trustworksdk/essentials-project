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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end drain time of an ordered backlog, for three shapes of the same 20 000 messages: few long keys, many
 * short keys, and one message per key. Uses only APIs that exist on every 0.60 release, so the same benchmark runs
 * before and after a change.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class OrderedBacklogDrainBenchmarkIT {
    private static final int MESSAGES           = 20_000;
    private static final int PARALLEL_CONSUMERS = 32;

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("ordered-drain-db");

    private HikariDataSource        dataSource;
    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private Jdbi                    jdbi;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        config.setUsername(postgreSQLContainer.getUsername());
        config.setPassword(postgreSQLContainer.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        jdbi = Jdbi.create(dataSource);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void drain_ordered_backlogs_of_three_shapes() {
        var results = new ArrayList<String>();
        // -Dordered.drain.keys=2000 measures one shape, e.g. to bisect a change
        var shapes = System.getProperty("ordered.drain.keys") != null
                     ? Arrays.stream(System.getProperty("ordered.drain.keys").split(",")).map(Integer::parseInt).toList()
                     : List.of(20, 2_000, 20_000);
        for (var keys : shapes) {
            results.add(drain(keys));
        }
        System.out.printf("%n=== Ordered backlog drain, %,d messages, %d consumers ===%n%s%n%n", MESSAGES, PARALLEL_CONSUMERS, String.join("\n", results));
    }

    /**
     * Each shape on its own, freshly created table: one shape's run must not leave the next one's table in a different
     * state (statistics, dead tuples), or the results depend on the order the shapes run in
     */
    private String drain(int keys) {
        try (var shapeWired = new SpringWiredDurableQueues(jdbi, unitOfWorkFactory, "ordered_drain_" + keys)) {
            return drain(keys, shapeWired.durableQueues);
        }
    }

    private String drain(int keys, PostgresqlDurableQueues durableQueues) {
        var queueName = QueueName.of("OrderedDrain-" + keys);
        var perKey    = MESSAGES / keys;
        for (var offset = 0; offset < MESSAGES; offset += 1_000) {
            var messages = new ArrayList<Message>(1_000);
            for (var i = offset; i < offset + 1_000; i++) {
                messages.add(OrderedMessage.of("payload-" + i, "key-" + (i % keys), i / keys));
            }
            unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(queueName, messages));
        }
        var handled   = new AtomicInteger();
        var startedAt = System.nanoTime();
        var consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                      .setQueueName(queueName)
                                                                      .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                      .setParallelConsumers(PARALLEL_CONSUMERS)
                                                                      .setQueueMessageHandler(message -> handled.incrementAndGet())
                                                                      .build());
        Awaitility.waitAtMost(Duration.ofMinutes(10))
                  .pollInterval(Duration.ofMillis(50))
                  .until(() -> handled.get() >= MESSAGES && durableQueues.getTotalMessagesQueuedFor(queueName) == 0);
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        consumer.cancel();
        return String.format("  %,6d keys x %,5d: %,8d ms, %,7.0f msg/s", keys, perKey, elapsedMs, MESSAGES * 1_000d / Math.max(1, elapsedMs));
    }
}
