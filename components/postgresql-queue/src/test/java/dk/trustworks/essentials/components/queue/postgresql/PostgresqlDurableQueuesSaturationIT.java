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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A saturated queue is not held to one poll per polling interval.
 * <p>
 * The {@code CentralizedMessageFetcher} claims at most a queue's free worker slots per poll. Polling only once per
 * interval capped a queue at {@code parallelConsumers / pollingInterval} messages per second however fast its handler
 * was - with the long interval used here, 8 / 200 ms = 40 msg/s, so draining the backlog would take about 50 s. When a
 * poll fills every free slot the fetcher now polls again as soon as slots free up, so the drain is bounded by the
 * handler and the database rather than by the interval.
 */
@Testcontainers
class PostgresqlDurableQueuesSaturationIT {
    private static final QueueName QUEUE              = QueueName.of("SaturationQueue");
    private static final int       MESSAGES           = 2_000;
    private static final int       PARALLEL_CONSUMERS = 8;
    private static final Duration  POLLING_INTERVAL   = Duration.ofMillis(200);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("saturation-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    private DurableQueueConsumer    consumer;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                   postgreSQLContainer.getUsername(),
                                                                   postgreSQLContainer.getPassword()));
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setCentralizedMessageFetcherPollingInterval(POLLING_INTERVAL)
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.cancel();
        }
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void a_saturated_queue_drains_faster_than_one_poll_per_polling_interval_allows() {
        for (var offset = 0; offset < MESSAGES; offset += 500) {
            var messages = new ArrayList<Message>(500);
            for (var i = offset; i < offset + 500; i++) {
                messages.add(Message.of("payload-" + i));
            }
            unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(QUEUE, messages));
        }

        var handled   = new AtomicInteger();
        var startedAt = System.nanoTime();
        consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                  .setQueueName(QUEUE)
                                                                  .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                  .setParallelConsumers(PARALLEL_CONSUMERS)
                                                                  .setQueueMessageHandler(message -> handled.incrementAndGet())
                                                                  .build());

        Awaitility.waitAtMost(Duration.ofMinutes(2))
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGES));
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        var ceilingMessagesPerSecond = PARALLEL_CONSUMERS * 1_000d / POLLING_INTERVAL.toMillis();
        var fixedRateDrain           = Duration.ofMillis((long) (MESSAGES / ceilingMessagesPerSecond * 1_000));
        System.out.printf("%n=== Drained %,d messages with %d consumers and %d ms polling in %,d ms "
                                  + "(one poll per interval would need at least %,d ms) ===%n%n",
                          MESSAGES, PARALLEL_CONSUMERS, POLLING_INTERVAL.toMillis(), elapsed.toMillis(), fixedRateDrain.toMillis());

        assertThat(elapsed)
                .as("draining %s messages at one poll per %s ms interval takes at least %s", MESSAGES, POLLING_INTERVAL.toMillis(), fixedRateDrain)
                .isLessThan(fixedRateDrain.dividedBy(3));
    }
}
