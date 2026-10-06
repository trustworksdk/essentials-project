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
import org.testcontainers.postgresql.PostgreSQLContainer;
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
    static PostgreSQLContainer postgres = LabPostgres.create();

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
            queue.startConsuming((messageId, payload, payloadType) -> {
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
            queue.startConsuming((messageId, payload, payloadType) -> {
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
     * The poison message above has its shard to itself, so nothing is ever acknowledged around it.
     * With traffic flowing, the messages after it are acknowledged while it waits out its backoff, and
     * the acknowledgement is a range delete ({@code seq <= n}) over the handled prefix. A message
     * waiting for a retry is neither in flight nor a hole, so the range used to take it with it: the
     * in-memory schedule still redelivered it, the final failure's dead-letter move found no row, and
     * the message disappeared — not parked, not queued, no error. Found by the trading demo's
     * fault-injection harness; every unordered poison message went missing.
     */
    @Test
    void a_poison_message_is_dead_lettered_while_the_traffic_around_it_is_acknowledged() throws Exception {
        var poisonDeliveries = new AtomicInteger();
        var handled = ConcurrentHashMap.<String>newKeySet();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> {
                var body = new String(payload, StandardCharsets.UTF_8);
                if (body.equals("poison")) {
                    poisonDeliveries.incrementAndGet();
                    throw new IllegalStateException("always fails");
                }
                handled.add(body);
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(300), 3));

            queue.enqueue(List.of("poison".getBytes(StandardCharsets.UTF_8)), 1);
            // Traffic on every shard for the whole of the poison message's backoff, so acknowledgements
            // above it keep arriving while it is waiting.
            var sent = 0;
            var until = System.nanoTime() + Duration.ofMillis(1_500).toNanos();
            while (System.nanoTime() < until) {
                var batch = new ArrayList<byte[]>();
                for (var index = 0; index < 20; index++) {
                    batch.add(("m-" + sent++).getBytes(StandardCharsets.UTF_8));
                }
                queue.enqueue(batch, 1);
                Thread.sleep(25L);
            }
            var expected = sent;

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(handled).hasSize(expected));
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.deadLetterCount())
                              .as("the poison message must be parked, not swept away by a neighbour's ack")
                              .isEqualTo(1L));
            assertThat(poisonDeliveries.get()).as("policy allows 3 attempts").isEqualTo(3);
            assertThat(new String(queue.deadLetters().getFirst().payload(), StandardCharsets.UTF_8)).isEqualTo("poison");
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
        }
    }

    /**
     * The same hazard for a message that recovers: while it waits for its retry, its row is the only
     * durable record of it. Checked in the table rather than by the eventual delivery, because the
     * in-memory schedule redelivers it either way — the row having gone only shows after a crash.
     */
    @Test
    void a_message_waiting_for_its_retry_keeps_its_row_while_later_messages_are_acknowledged() throws Exception {
        var failedOnce = new CountDownLatch(1);
        var flakyHandled = new AtomicInteger();
        var handled = new AtomicInteger();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> {
                if (new String(payload, StandardCharsets.UTF_8).equals("flaky")) {
                    if (failedOnce.getCount() > 0) {
                        failedOnce.countDown();
                        throw new IllegalStateException("fail once");
                    }
                    flakyHandled.incrementAndGet();
                    return;
                }
                handled.incrementAndGet();
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofSeconds(3), 3));

            queue.enqueue(List.of("flaky".getBytes(StandardCharsets.UTF_8)), 1);
            assertThat(failedOnce.await(10, TimeUnit.SECONDS)).isTrue();

            // One enqueue call places its whole batch on one shard, round-robin per call, so two
            // rounds of SHARD_COUNT batches put later messages on the flaky message's shard too.
            for (var batch = 0; batch < SHARD_COUNT * 2; batch++) {
                var later = new ArrayList<byte[]>();
                for (var index = 0; index < 10; index++) {
                    later.add(("m-" + batch + "-" + index).getBytes(StandardCharsets.UTF_8));
                }
                queue.enqueue(later, 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(handled).hasValue(SHARD_COUNT * 20));
            // Well inside the 3s backoff, and long after the ack flush for the later messages.
            Thread.sleep(300L);
            assertThat(flakyHandled).as("still waiting out its backoff").hasValue(0);
            assertThat(queue.remaining()).as("the retrying message's row must survive the range acknowledgement").isEqualTo(1L);

            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(flakyHandled).hasValue(1));
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(queue.remaining()).isZero());
            Thread.sleep(1_000L);
            assertThat(flakyHandled).as("acknowledged once it succeeded, not redelivered").hasValue(1);
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
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
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
