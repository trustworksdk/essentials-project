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
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedStorage.OrderedPayload;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retries and dead letters (§4.6). The claim is that failure handling lives in an in-memory timer
 * wheel with the database row as durability backup, so retries cost the read path nothing.
 * <p>
 * Correctness first: a message that eventually succeeds must not be lost or duplicated, a message
 * that never succeeds must end up parked exactly once, and — in the ordered lane — a failing message
 * must not let later messages of its key overtake it.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedRetryAndDeadLetterIT {

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
    void a_message_that_fails_once_is_retried_and_then_succeeds() throws Exception {
        var attemptsSeen = new ConcurrentHashMap<String, AtomicInteger>();
        var succeeded = new CopyOnWriteArrayList<String>();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((payload, payloadType) -> {
                var body = new String(payload, StandardCharsets.UTF_8);
                var attempt = attemptsSeen.computeIfAbsent(body, ignored -> new AtomicInteger()).incrementAndGet();
                if (attempt == 1) {
                    throw new IllegalStateException("fail once");
                }
                succeeded.add(body);
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(20), 5));

            var payloads = new ArrayList<byte[]>();
            for (var index = 0; index < 40; index++) {
                payloads.add(("m-" + index).getBytes(StandardCharsets.UTF_8));
            }
            queue.enqueue(payloads, 1);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(succeeded).hasSize(40));
            assertThat(new HashSet<>(succeeded)).as("a retried message must not be delivered twice on success").hasSize(40);

            var metrics = queue.metrics().snapshot();
            assertThat((Long) metrics.get("retriesScheduled")).as("%s", metrics).isEqualTo(40L);
            assertThat((Long) metrics.get("retriesDispatched")).as("%s", metrics).isEqualTo(40L);
            assertThat((Long) metrics.get("deadLettered")).as("nothing should be parked: %s", metrics).isZero();

            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
            assertThat(queue.deadLetterCount()).isZero();
        }
    }

    @Test
    void a_message_that_always_fails_is_dead_lettered_once_after_the_policy_is_exhausted() throws Exception {
        var deliveries = new AtomicInteger();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((payload, payloadType) -> {
                deliveries.incrementAndGet();
                throw new IllegalStateException("always fails");
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3));

            queue.enqueue(List.of("poison".getBytes(StandardCharsets.UTF_8)), 1);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.deadLetterCount()).isEqualTo(1L));

            // Exactly maxAttempts deliveries, then parked — not fewer (giving up early) and not more
            // (the head sweep re-offering a message the policy had already exhausted).
            assertThat(deliveries.get()).as("policy allows 3 attempts").isEqualTo(3);

            var parked = queue.deadLetters();
            assertThat(parked).hasSize(1);
            assertThat(parked.getFirst().lane()).isEqualTo("unordered");
            assertThat(parked.getFirst().attempts()).isPositive();
            assertThat(parked.getFirst().error()).contains("always fails");
            assertThat(new String(parked.getFirst().payload(), StandardCharsets.UTF_8)).isEqualTo("poison");

            // Dead-lettering removes it from the live lane in the same transaction, so it cannot be
            // both parked and redelivered.
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
            // And it stays parked — a sweep must not resurrect it.
            Thread.sleep(1_500L);
            assertThat(deliveries.get()).isEqualTo(3);
            assertThat(queue.deadLetterCount()).isEqualTo(1L);
        }
    }

    /**
     * The ordering-critical case: a message that fails must keep its key blocked, or a later message
     * for that key overtakes the one still being retried — which would break the guarantee while
     * every message still eventually arrives.
     */
    @Test
    void a_failing_message_does_not_let_later_messages_of_its_key_overtake_it() throws Exception {
        var received = Collections.synchronizedList(new ArrayList<Long>());
        var firstAttempt = new AtomicBoolean(true);

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((key, payload, payloadType) -> {
                var order = Long.parseLong(new String(payload, StandardCharsets.UTF_8));
                // key_order 0 fails on its first attempt only. If ordering is broken, 1..5 arrive
                // while 0 is waiting out its backoff.
                if (order == 0 && firstAttempt.compareAndSet(true, false)) {
                    throw new IllegalStateException("fail the head once");
                }
                received.add(order);
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(300), 5));

            var messages = new ArrayList<OrderedPayload>();
            for (var order = 0; order < 6; order++) {
                messages.add(new OrderedPayload("k", order, Long.toString(order).getBytes(StandardCharsets.UTF_8), 1));
            }
            queue.enqueueOrdered(messages);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).hasSize(6));

            assertThat(List.copyOf(received))
                    .as("the retried head must still be delivered before everything after it")
                    .containsExactly(0L, 1L, 2L, 3L, 4L, 5L);

            var metrics = queue.metrics().snapshot();
            assertThat((Long) metrics.get("orderViolations")).as("%s", metrics).isZero();
            assertThat((Long) metrics.get("retriesScheduled")).as("%s", metrics).isEqualTo(1L);

            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(queue.orderedRemaining()).isZero());
        }
    }
}
