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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link dk.trustworks.essentials.shared.Lifecycle} semantics, which the rest of this project's
 * long-lived resources expose — {@code DurableQueues} and {@code DurableQueueConsumer} both extend it.
 * <p>
 * The contract has three requirements worth asserting rather than assuming: start and stop are both
 * idempotent, {@code isStarted} reports the truth, and a resource that has been stopped can be
 * started again. The last is the one that bites: an engine whose stop leaves stale owners behind
 * restarts into a state where its fences are long superseded.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedLifecycleIT {

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

    @Test
    void a_configured_queue_starts_stops_and_starts_again_delivering_each_time() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "life-1");
        try {
            // Configured at construction time and started later, which is the shape a container needs:
            // it has nowhere to pass a handler at start().
            queue.configureUnordered((payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                     ShardOwnerSettings.defaults(), SHARD_COUNT,
                                     RedeliveryPolicy.fixed(Duration.ofMillis(50), 3));
            assertThat(queue.isStarted()).isFalse();

            queue.start();
            assertThat(queue.isStarted()).isTrue();
            queue.start(); // idempotent — must not lease a second set of owners onto the same shards
            assertThat(queue.shardsHeld()).isEqualTo(SHARD_COUNT);

            queue.enqueue(List.of("a".getBytes(StandardCharsets.UTF_8)), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(delivered).contains("a"));

            queue.stop();
            assertThat(queue.isStarted()).isFalse();
            queue.stop(); // idempotent
            assertThat(queue.shardsHeld()).isZero();

            // The restart is the part that would break on a stop that left stale owners behind: the
            // engine must lease afresh rather than resurrect owners whose fences are superseded.
            queue.start();
            assertThat(queue.isStarted()).isTrue();
            queue.enqueue(List.of("b".getBytes(StandardCharsets.UTF_8)), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(delivered).contains("b"));
        } finally {
            queue.stop();
        }
    }

    @Test
    void the_spi_queue_and_its_subscription_report_and_honour_their_lifecycle() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "life-2")) {
            // Not started until something is actually running. This asserted isTrue() while the
            // flag was seeded true, which is the bug rather than the contract.
            assertThat(queue.isStarted()).isFalse();

            var subscription = queue.consume(
                    (key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                    ConsumerOptions.defaults());
            assertThat(subscription.isStarted()).isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> subscription.shardsHeld() > 0);

            queue.enqueue(List.of(Message.of("one".getBytes(StandardCharsets.UTF_8), 1)));
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(delivered).contains("one"));

            // stop() releases the shards, so another instance can take them without waiting out a
            // lease — the same thing close() did, now under the name the framework uses.
            subscription.stop();
            assertThat(subscription.isStarted()).isFalse();
            assertThat(subscription.shardsHeld()).isZero();

            queue.stop();
            assertThat(queue.isStarted()).isFalse();
        }
    }

    /**
     * {@code ShardRuntime} is the last long-lived resource in the module, and it was the only one not
     * on {@code Lifecycle} — {@code AutoCloseable} and unrestartable, while everything above it could
     * be stopped and started again. Restarting matters because its executors cannot be revived, so a
     * restart has to rebuild them rather than reuse them.
     */
    @Test
    void the_runtime_stops_and_starts_again() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var runtime = new ShardRuntime(dataSource, ShardOwnerSettings.defaults())) {
            assertThat(runtime.isStarted()).isTrue();
            assertThat(runtime.pumpCount()).isPositive();

            runtime.stop();
            assertThat(runtime.isStarted()).isFalse();
            runtime.stop();
            assertThat(runtime.isStarted()).describedAs("stop is idempotent").isFalse();

            runtime.start();
            assertThat(runtime.isStarted()).isTrue();

            // A queue handed the restarted runtime must actually work on it — the point of the
            // restart is that the rebuilt pumps and listener serve shards, not merely that a flag
            // flipped back.
            var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT).setInstanceId("runtime-restart").setRuntime(runtime).build();
            try {
                queue.startConsuming((payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                     ShardOwnerSettings.defaults(), SHARD_COUNT);
                queue.enqueue(List.of("after-restart".getBytes(StandardCharsets.UTF_8)), 1);
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> assertThat(delivered).contains("after-restart"));
            } finally {
                queue.stop();
            }
        }
    }
}
