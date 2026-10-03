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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * The new SPI, exercised end to end.
 * <p>
 * The point is not that the engine works — that is covered elsewhere — but that the contract can be
 * used without reaching past it. An interface that needs the implementation's own types to be useful
 * has not replaced anything.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedSpiIT {

    /** Set by the wrapper for the duration of one delivery, so the handler can prove it ran inside it. */
    private static final ThreadLocal<java.util.concurrent.atomic.AtomicBoolean> insideWrapper = new ThreadLocal<>();

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
        config.setMaximumPoolSize(50);
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

    private MessageQueue queue(String instanceId) {
        return new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
    }

    @Test
    void a_mixed_batch_is_split_by_lane_and_both_reach_the_same_handler() throws Exception {
        var unordered = new CopyOnWriteArrayList<String>();
        var orderedByKey = new java.util.concurrent.ConcurrentHashMap<String, List<Long>>();

        try (var queue = queue("spi-1")) {
            queue.consume((messageId, key, payload, payloadType) -> {
                var body = new String(payload, StandardCharsets.UTF_8);
                if (key == null) {
                    unordered.add(body);
                } else {
                    orderedByKey.computeIfAbsent(key, ignored -> Collections.synchronizedList(new ArrayList<>()))
                                .add(Long.parseLong(body));
                }
            }, ConsumerOptions.defaults());

            // One call, both lanes — the caller does not route, and does not know there are lanes.
            var batch = new ArrayList<Message>();
            for (var index = 0; index < 40; index++) {
                batch.add(Message.of(("u-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            for (var key = 0; key < 5; key++) {
                for (var order = 0; order < 8; order++) {
                    batch.add(Message.ordered(Long.toString(order).getBytes(StandardCharsets.UTF_8),
                                              1, "k-" + key, order));
                }
            }
            var ids = queue.enqueue(batch);
            assertThat(ids).hasSize(80);
            assertThat(ids).anyMatch(id -> id.lane() == MessageId.Lane.UNORDERED);
            assertThat(ids).anyMatch(id -> id.lane() == MessageId.Lane.ORDERED);

            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> {
                          assertThat(unordered).hasSize(40);
                          assertThat(orderedByKey.values().stream().mapToInt(List::size).sum()).isEqualTo(40);
                      });

            // The ordering guarantee survives the trip through the contract.
            orderedByKey.forEach((key, orders) -> assertThat(List.copyOf(orders))
                    .as("key %s", key).isSorted().doesNotHaveDuplicates());

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.depth().total()).isZero());
        }
    }

    @Test
    void depth_dead_letters_resurrect_and_purge_are_usable_through_the_contract() throws Exception {
        try (var queue = queue("spi-2")) {
            var alwaysFails = new ConsumerOptions(8, SHARD_COUNT, 2, Duration.ofMillis(10), 1.0d, Duration.ofMillis(10));
            queue.consume((messageId, key, payload, payloadType) -> {
                throw new IllegalStateException("nope");
            }, alwaysFails);

            queue.enqueue(Message.of("doomed".getBytes(StandardCharsets.UTF_8), 1));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isEqualTo(1L));

            var parked = queue.deadLetters(0, 10);
            assertThat(parked).hasSize(1);
            assertThat(parked.getFirst().lastError()).contains("nope");
            assertThat(new String(parked.getFirst().payload(), StandardCharsets.UTF_8)).isEqualTo("doomed");
            assertThat(parked.getFirst().attempts()).isPositive();

            // Resurrect puts it back in its lane with a fresh attempt count, so it is redelivered
            // rather than going straight back to the dead letter lane.
            assertThat(queue.resurrect(parked.getFirst().id())).isTrue();
            assertThat(queue.resurrect(parked.getFirst().id()))
                    .as("resurrecting twice must not duplicate it")
                    .isFalse();

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isEqualTo(1L));

            assertThat(queue.purge()).isPositive();
            assertThat(queue.depth().total()).isZero();
            assertThat(queue.depth().deadLettered()).isZero();
        }
    }

    /**
     * Instrumentation attaches without the observer needing to know anything about the engine.
     * <p>
     * The wrapping hook is tested separately from the notifications because they exist for different
     * reasons: a counter can be told after the fact, a tracing span cannot — it has to enclose the
     * work, and an observer that is merely notified would produce spans that do not contain what they
     * claim to measure.
     */
    @Test
    void observers_see_enqueue_delivery_and_can_wrap_the_handler() throws Exception {
        var enqueued = new java.util.concurrent.atomic.AtomicInteger();
        var delivered = new java.util.concurrent.atomic.AtomicInteger();
        var wrapped = new java.util.concurrent.atomic.AtomicInteger();
        var handlerRanInsideWrapper = new java.util.concurrent.atomic.AtomicBoolean();
        var totalNanos = new java.util.concurrent.atomic.AtomicLong();

        try (var queue = queue("spi-4")) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void enqueued(int messageCount, boolean ordered) {
                    enqueued.addAndGet(messageCount);
                }

                @Override
                public void aroundDelivery(String key, Runnable delivery) {
                    wrapped.incrementAndGet();
                    // Per invocation, not a global counter. Comparing a shared counter across
                    // delivery.run() asserted that no OTHER delivery completed meanwhile — which is a
                    // claim about the engine being single-threaded, not about the wrapper. Handlers
                    // run concurrently on virtual threads now, and the wrapper's actual contract is
                    // only that this handler runs inside this call.
                    var ranHere = new java.util.concurrent.atomic.AtomicBoolean();
                    insideWrapper.set(ranHere);
                    try {
                        delivery.run();
                    } finally {
                        insideWrapper.remove();
                    }
                    assertThat(ranHere.get()).as("the handler must run inside the wrapper").isTrue();
                    handlerRanInsideWrapper.set(true);
                }

                @Override
                public void delivered(String key, long durationNanos) {
                    delivered.incrementAndGet();
                    totalNanos.addAndGet(durationNanos);
                }
            });

            queue.consume((messageId, key, payload, payloadType) -> {
                var marker = insideWrapper.get();
                if (marker != null) {
                    marker.set(true);
                }
            }, ConsumerOptions.defaults());

            var batch = new ArrayList<Message>();
            for (var index = 0; index < 30; index++) {
                batch.add(Message.of(("m-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            queue.enqueue(batch);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(delivered.get()).isEqualTo(30));

            assertThat(enqueued.get()).isEqualTo(30);
            assertThat(wrapped.get()).as("every delivery must pass through the wrapper").isEqualTo(30);
            assertThat(handlerRanInsideWrapper).isTrue();
            assertThat(totalNanos.get()).as("delivery duration must actually be measured").isPositive();
        }
    }

    /**
     * A failing handler must reach the observer as a failure, not silently as a success.
     */
    @Test
    void observers_see_delivery_failures() throws Exception {
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        var successes = new java.util.concurrent.atomic.AtomicInteger();

        try (var queue = queue("spi-5")) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void deliveryFailed(String key, int attempt, Throwable cause) {
                    assertThat(cause).hasMessageContaining("boom");
                    failures.incrementAndGet();
                }

                @Override
                public void delivered(String key, long durationNanos) {
                    successes.incrementAndGet();
                }
            });
            queue.consume((messageId, key, payload, payloadType) -> {
                throw new IllegalStateException("boom");
            }, new ConsumerOptions(8, SHARD_COUNT, 2, Duration.ofMillis(10), 1.0d, Duration.ofMillis(10)));

            queue.enqueue(Message.of("bad".getBytes(StandardCharsets.UTF_8), 1));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isEqualTo(1L));
            assertThat(failures.get()).as("each attempt should be reported").isGreaterThanOrEqualTo(2);
            assertThat(successes.get()).as("nothing succeeded, so nothing may be reported as delivered").isZero();
        }
    }

    @Test
    void a_pull_session_is_reachable_through_the_contract() throws Exception {
        try (var queue = queue("spi-6")) {
            queue.enqueue(Message.of("pulled".getBytes(StandardCharsets.UTF_8), 1));

            try (var session = queue.openSession(SessionScope.SHARD, Duration.ofSeconds(30))) {
                var pulled = new ArrayList<QueueSession.PulledMessage>();
                for (var attempt = 0; attempt < 10 && pulled.isEmpty(); attempt++) {
                    pulled.addAll(session.poll(10));
                }
                assertThat(pulled).hasSize(1);
                assertThat(new String(pulled.getFirst().payload(), StandardCharsets.UTF_8)).isEqualTo("pulled");
                assertThat(session.acknowledge(List.of(pulled.getFirst().id()))).isTrue();
            }
            assertThat(queue.depth().total()).isZero();
        }
    }
}
