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
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A message waiting out its retry backoff keeps its key blocked across a hand-over (N-16, RN-4).
 * <p>
 * The owner that scheduled a retry blocks the key in memory. A successor knows nothing of that memory,
 * and reads only rows whose {@code visible_at} has passed — so before the fix a later {@code key_order}
 * for the key was delivered by the successor while the failed message was still in its backoff. The
 * successor now reloads, on takeover, the ordered rows a retry has pushed into the future, and holds their
 * keys until each retry is due.
 * <p>
 * <b>How the hazard is constructed rather than hoped for.</b> Every key's first message fails until the
 * test flips {@link #release}, which it does only once the hand-over is complete; the backoff is far longer
 * than the hand-over takes. So at the moment ownership moves every key has its head in backoff and its
 * second message visible, and the assertion is simply that no key's second message is ever handed to a
 * handler before its first has succeeded. Covered for each way a unit changes owner: a rebalance, a graceful
 * stop and a crash.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedOrderedRetryHandoverIT {

    private static final short    QUEUE_ID    = 1;
    private static final int      SHARD_COUNT = 4;
    private static final int      KEYS        = 48;
    /**
     * Longer than any hand-over below takes, so the heads are invisible when ownership moves.
     */
    private static final Duration BACKOFF     = Duration.ofSeconds(4);

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    private final AtomicBoolean                release    = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Event> deliveries = new ConcurrentLinkedQueue<>();

    private record Event(String key, long keyOrder, String instance, boolean succeeded) {
    }

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
     * A short lease so a crashed instance's units are takeable in about a second, and a grace long enough
     * that a shed never has to be abandoned.
     */
    private static ShardOwnerSettings settings() {
        return new ShardOwnerSettings(500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
                                      Duration.ofMillis(300), Duration.ofMillis(100), 1_000, 8,
                                      Duration.ofMillis(50), Duration.ofMillis(200), 2, Duration.ofSeconds(5),
                                      Duration.ofMillis(1000), Duration.ofSeconds(60));
    }

    private static RedeliveryPolicy policy() {
        return RedeliveryPolicy.fixed(BACKOFF, 1_000);
    }

    /**
     * Fails every key's {@code key_order} 0 until {@link #release} is set; everything else succeeds.
     */
    private OrderedPayloadHandler handler(String instance) {
        return new OrderedPayloadHandler() {
            @Override
            public void handle(MessageId messageId, String key, byte[] payload, int payloadType) {
                throw new UnsupportedOperationException("the engine calls the keyOrder overload");
            }

            @Override
            public void handle(MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
                var fail = keyOrder == 0 && !release.get();
                deliveries.add(new Event(key, keyOrder, instance, !fail));
                if (fail) {
                    throw new IllegalStateException("head of " + key + " fails until released");
                }
            }
        };
    }

    private void enqueueTwoPerKey(ShardOwnedQueue queue) throws Exception {
        var messages = new ArrayList<ShardOwnedStorage.OrderedPayload>();
        for (var order = 0; order < 2; order++) {
            for (var key = 0; key < KEYS; key++) {
                messages.add(new ShardOwnedStorage.OrderedPayload("key-" + key, order,
                                                                  ("key-" + key + "#" + order).getBytes(StandardCharsets.UTF_8), 1));
            }
        }
        queue.enqueueOrdered(messages);
    }

    private long headFailuresOn(String instance) {
        return deliveries.stream().filter(event -> event.instance().equals(instance) && !event.succeeded()).map(Event::key).distinct().count();
    }

    /**
     * Every key's {@code key_order} 1 was delivered, and never before its {@code key_order} 0 had succeeded.
     */
    private void awaitDrainedInOrder() {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                deliveries.stream().filter(event -> event.keyOrder() == 1).map(Event::key).distinct().count())
                .isEqualTo(KEYS));
        var overtaken = new TreeSet<String>();
        var headDone  = new HashSet<String>();
        for (var event : List.copyOf(deliveries)) {
            if (event.keyOrder() == 0 && event.succeeded()) {
                headDone.add(event.key());
            } else if (event.keyOrder() == 1 && !headDone.contains(event.key())) {
                overtaken.add(event.key() + " (on " + event.instance() + ")");
            }
        }
        assertThat(overtaken)
                .as("a key's second message was delivered while its first was still waiting out a retry backoff")
                .isEmpty();
    }

    @Test
    void a_rebalance_does_not_let_a_later_message_overtake_one_waiting_out_its_backoff() throws Exception {
        var first  = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "retry-a");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "retry-b");
        try {
            first.startConsumingOrdered(handler("retry-a"), settings(), SHARD_COUNT, policy());
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
            enqueueTwoPerKey(first);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> headFailuresOn("retry-a") == KEYS);

            second.startConsumingOrdered(handler("retry-b"), settings(), SHARD_COUNT, policy());
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(first.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS / 2);
                assertThat(second.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS / 2);
            });
            assertThat(first.metrics().shedsCompleted.sum()).as("the units must have moved by a shed").isPositive();
            release.set(true);

            awaitDrainedInOrder();
            assertThat(deliveries).as("some key must have finished on the instance that took it over mid-backoff")
                                  .anyMatch(event -> event.instance().equals("retry-b") && event.keyOrder() == 0 && event.succeeded());
        } finally {
            release.set(true);
            first.close();
            second.close();
        }
    }

    @Test
    void a_graceful_stop_does_not_let_a_later_message_overtake_one_waiting_out_its_backoff() throws Exception {
        var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "retry-c");
        try {
            first.startConsumingOrdered(handler("retry-c"), settings(), SHARD_COUNT, policy());
            enqueueTwoPerKey(first);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> headFailuresOn("retry-c") == KEYS);
        } finally {
            first.stop();
        }

        try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "retry-d")) {
            second.startConsumingOrdered(handler("retry-d"), settings(), SHARD_COUNT, policy());
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(second.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
            release.set(true);
            awaitDrainedInOrder();
        } finally {
            release.set(true);
        }
    }

    /**
     * The other half of telling a retry from a delay. A delayed ordered message does not block its key
     * (documented, and pinned without a hand-over by {@code ShardOwnedOrderingBoundaryIT}), and taking
     * over must not change that. It would if a takeover bumped the delayed row's attempts: the next
     * takeover would then read it as a retry in backoff and hold the key until the delay ran out.
     */
    @Test
    void a_delayed_message_still_does_not_block_its_key_after_two_hand_overs() throws Exception {
        try (var producer = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "delay-producer")) {
            producer.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("delayed-key", 0, "delayed-key#0".getBytes(StandardCharsets.UTF_8),
                                                                                 1, Duration.ofMinutes(10))));
        }
        // Two takeovers of the unit holding the delayed row, each by a graceful stop.
        for (var instance : List.of("delay-a", "delay-b")) {
            var owner = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, instance);
            try {
                owner.startConsumingOrdered(handler(instance), settings(), SHARD_COUNT, policy());
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(owner.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
            } finally {
                owner.stop();
            }
        }

        try (var third = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "delay-c")) {
            third.startConsumingOrdered(handler("delay-c"), settings(), SHARD_COUNT, policy());
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(third.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
            third.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("delayed-key", 1, "delayed-key#1".getBytes(StandardCharsets.UTF_8), 1)));

            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .alias("an undelayed later message must still overtake a delayed earlier one after hand-overs")
                      .until(() -> deliveries.stream().anyMatch(event -> event.key().equals("delayed-key") && event.keyOrder() == 1));
            assertThat(deliveries).noneMatch(event -> event.keyOrder() == 0);
        }
    }

    @Test
    void a_crash_does_not_let_a_later_message_overtake_one_waiting_out_its_backoff() throws Exception {
        var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "retry-e");
        try {
            first.startConsumingOrdered(handler("retry-e"), settings(), SHARD_COUNT, policy());
            enqueueTwoPerKey(first);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> headFailuresOn("retry-e") == KEYS);
            // No drain, no release, no deregistration: the successor has to wait for the lease to lapse.
            first.stopAbruptly();

            try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "retry-f")) {
                second.startConsumingOrdered(handler("retry-f"), settings(), SHARD_COUNT, policy());
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(second.shardsHeld()).isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
                release.set(true);
                awaitDrainedInOrder();
            }
        } finally {
            release.set(true);
            first.close();
        }
    }
}
