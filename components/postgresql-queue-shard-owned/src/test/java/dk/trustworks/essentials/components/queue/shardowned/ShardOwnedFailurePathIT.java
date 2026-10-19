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
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedStorage.OrderedPayload;
import dk.trustworks.essentials.components.queue.shardowned.spi.RedeliveryRequested;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure path's edges: what counts as a failure, what does not, and who may record one.
 * <ul>
 *     <li>A non-critical {@link Error} thrown by a handler is a failed delivery. It used to escape the owner's
 *     {@code catch (RuntimeException)} uncounted, so the message came back forever and never reached the
 *     dead-letter table.</li>
 *     <li>A {@link RedeliveryRequested} is not a failure: it is redelivered after the requested delay, however
 *     many times it is asked for, and never dead-lettered.</li>
 *     <li>A failure is recorded under the owner's fence. An owner that has lost its lease used to be able to
 *     dead-letter, or push out the visibility of, a message its successor was about to deliver.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedFailurePathIT {

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
    void an_error_thrown_by_an_unordered_handler_counts_as_a_failed_attempt() throws Exception {
        var deliveries = new AtomicInteger();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> {
                deliveries.incrementAndGet();
                throw new AssertionError("handler asserted");
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3));

            queue.enqueue(List.of(bytes("poison")), 1);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.deadLetterCount()).isEqualTo(1L));
            assertThat(deliveries.get()).as("policy allows 3 attempts").isEqualTo(3);
            assertThat(queue.deadLetters().getFirst().error()).contains("AssertionError", "handler asserted");
        }
    }

    @Test
    void an_error_thrown_by_an_ordered_handler_counts_as_a_failed_attempt() throws Exception {
        var deliveries = new AtomicInteger();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                deliveries.incrementAndGet();
                throw new AssertionError("handler asserted");
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(10), 3));

            queue.enqueueOrdered(List.of(new OrderedPayload("key-1", 0, bytes("poison"), 1, Duration.ZERO)));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.deadLetterCount()).isEqualTo(1L));
            assertThat(deliveries.get()).as("policy allows 3 attempts").isEqualTo(3);
        }
    }

    @Test
    void a_requested_redelivery_is_delivered_after_the_requested_delay_and_never_dead_lettered() throws Exception {
        // Five requests against a policy that allows three attempts, with a backoff far longer than the test:
        // a request treated as a failure would either be parked or not come back in time
        var deliveries = new AtomicInteger();
        var handled    = new CountDownLatch(1);

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> {
                if (deliveries.incrementAndGet() <= 5) {
                    throw new RedeliveryRequested(Duration.ofMillis(50));
                }
                handled.countDown();
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMinutes(5), 3));

            queue.enqueue(List.of(bytes("waits")), 1);

            assertThat(handled.await(20, TimeUnit.SECONDS)).as("redelivered at the requested delay").isTrue();
            assertThat(deliveries.get()).isEqualTo(6);
            assertThat(queue.deadLetterCount()).isZero();
            assertThat((Long) queue.metrics().snapshot().get("handlerFailures"))
                    .as("a requested redelivery is not a failure")
                    .isZero();
        }
    }

    @Test
    void a_requested_redelivery_of_an_ordered_message_is_never_dead_lettered() throws Exception {
        var deliveries = new AtomicInteger();
        var handled    = new CountDownLatch(1);

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                if (deliveries.incrementAndGet() <= 5) {
                    throw new RedeliveryRequested(Duration.ofMillis(50));
                }
                handled.countDown();
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMinutes(5), 3));

            queue.enqueueOrdered(List.of(new OrderedPayload("key-1", 0, bytes("waits"), 1, Duration.ZERO)));

            assertThat(handled.await(20, TimeUnit.SECONDS)).as("redelivered at the requested delay").isTrue();
            assertThat(deliveries.get()).isEqualTo(6);
            assertThat(queue.deadLetterCount()).isZero();
        }
    }

    @Test
    void a_failure_after_the_lease_was_lost_is_not_dead_lettered_by_the_superseded_owner() throws Exception {
        // One attempt allowed, so the superseded owner's failure would park the message if it were recorded. It is
        // parked all the same - by the successor, which counts the interrupted delivery as the one attempt - and
        // the recorded error says which of the two did it
        var firstDeliveryStarted = new CountDownLatch(1);
        var leaseTaken           = new CountDownLatch(1);

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> {
                firstDeliveryStarted.countDown();
                awaitUninterruptibly(leaseTaken);
                throw new IllegalStateException("failed after the lease was lost");
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(10), 1));

            queue.enqueue(List.of(bytes("contested")), 1);
            assertThat(firstDeliveryStarted.await(20, TimeUnit.SECONDS)).isTrue();
            takeEveryUnorderedShard();
            leaseTaken.countDown();

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.deadLetterCount()).isEqualTo(1L));
            assertThat(queue.deadLetters().getFirst().error())
                    .as("parked by the successor, not by the owner that had lost the shard")
                    .isEqualTo(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS);
        }
    }

    @Test
    void a_failure_after_the_lease_was_lost_does_not_delay_the_successors_delivery() throws Exception {
        // A backoff far longer than the test, so a recorded retry would hide the message from the successor
        assertSuccessorRedeliversAfterAFailureUnderALostLease(RedeliveryPolicy.fixed(Duration.ofMinutes(5), 5));
    }

    private void assertSuccessorRedeliversAfterAFailureUnderALostLease(RedeliveryPolicy policy) throws Exception {
        var firstDeliveryStarted = new CountDownLatch(1);
        var leaseTaken           = new CountDownLatch(1);
        var deliveries           = new AtomicInteger();
        var handled              = new CountDownLatch(1);

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> {
                if (deliveries.incrementAndGet() == 1) {
                    firstDeliveryStarted.countDown();
                    awaitUninterruptibly(leaseTaken);
                    throw new IllegalStateException("failed after the lease was lost");
                }
                handled.countDown();
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, policy);

            queue.enqueue(List.of(bytes("contested")), 1);
            assertThat(firstDeliveryStarted.await(20, TimeUnit.SECONDS)).isTrue();

            takeEveryUnorderedShard();
            leaseTaken.countDown();

            // The superseded owner's failure must leave the row exactly as the successor will find it. The
            // successor is this same instance re-acquiring the shards the lease table now credits to an
            // instance that does not exist
            assertThat(handled.await(30, TimeUnit.SECONDS)).as("the successor delivers the message").isTrue();
            assertThat(queue.deadLetterCount()).isZero();
        }
    }

    /**
     * Another instance takes every unordered shard: a new owner under a new fence
     */
    private void takeEveryUnorderedShard() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("UPDATE " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " SET owner = 'successor', fence = fence + 1"
                                                                 + " WHERE queue_id = ? AND lane = 'unordered'")) {
            statement.setShort(1, QUEUE_ID);
            assertThat(statement.executeUpdate()).isPositive();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        var interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
