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

package dk.trustworks.essentials.components.queue.shardowned;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two numbers a caller uses to ask "am I serving this queue, and is it drained" have to count
 * the ordered lane, and both of them silently did not.
 * <p>
 * Neither failure is visible as an error. {@code shardsHeld()} returned a plausible smaller number
 * and {@code remaining()} returned a plausible zero, which is the worse of the two: a drain
 * assertion written against it passes before anything has been delivered. Both were found while
 * preparing the engine for publication, where these become frozen contract.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedLaneAccountingIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * A subscription covers both lanes, so the units it holds are both lanes' too. Reporting the
     * unordered lane alone understated a sole consumer of this queue by 64 against 68 — and the
     * ordered lane is the one whose ownership failures cannot be seen in queue depth.
     */
    @Test
    void shards_held_counts_both_lanes() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "lane-accounting-1")) {
            var subscription = queue.consume((messageId, key, payload, payloadType) -> {
            }, ConsumerOptions.defaults());

            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(subscription.shardsHeld())
                              .as("a sole consumer holds every unit of both lanes")
                              .isEqualTo(SHARD_COUNT + ShardOwnedSchema.ORDERED_UNITS));
        }
    }

    /**
     * {@code remaining()} iterated {@code shardCount} and counted the unordered table whichever lane
     * was configured, so on an ordered queue it was zero before delivery and zero after — the same
     * answer for "drained" and "nothing has happened yet".
     */
    @Test
    void remaining_counts_the_ordered_lane_it_was_configured_for() throws Exception {
        var handlerMayProceed = new java.util.concurrent.CountDownLatch(1);
        var delivered         = ConcurrentHashMap.<String>newKeySet();

        try (var queue = ShardOwnedQueue.builder()
                                        .setDataSource(dataSource)
                                        .setQueueId(QUEUE_ID)
                                        .setShardCount(SHARD_COUNT)
                                        .setInstanceId("lane-accounting-2")
                                        .build()) {
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                                            try {
                                                handlerMayProceed.await();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            delivered.add(new String(payload, StandardCharsets.UTF_8));
                                        },
                                        ShardOwnerSettings.defaults(),
                                        ShardOwnedSchema.ORDERED_UNITS,
                                        RedeliveryPolicy.fixed(Duration.ofMillis(50), 3));

            var payloads = new java.util.ArrayList<ShardOwnedStorage.OrderedPayload>();
            for (var i = 0; i < 20; i++) {
                payloads.add(new ShardOwnedStorage.OrderedPayload("key-" + i, i,
                                                                  ("ordered-" + i).getBytes(StandardCharsets.UTF_8), 0));
            }
            queue.enqueueOrdered(payloads);

            // Held by the blocked handlers, so the rows are genuinely still there. Against the old
            // implementation this read zero — the unordered table, over 4 of 64 units.
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(queue.remaining())
                              .as("20 ordered messages are queued and their handlers are blocked")
                              .isPositive());

            handlerMayProceed.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> {
                          assertThat(delivered).hasSize(20);
                          assertThat(queue.remaining())
                                  .as("and it must still reach zero once they drain, or it is merely wrong the other way")
                                  .isZero();
                      });
        }
    }

    /** The unordered lane keeps counting the unordered table over its shard count. */
    @Test
    void remaining_still_counts_the_unordered_lane() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "lane-accounting-3")) {
            var delivered = ConcurrentHashMap.<String>newKeySet();
            queue.enqueue(List.of(Message.of("plain".getBytes(StandardCharsets.UTF_8), 0)));
            queue.consume((messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(delivered).containsExactly("plain"));
        }
    }
}
