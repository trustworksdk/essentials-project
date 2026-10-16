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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How attempts survive a change of owner.
 * <ul>
 *     <li>A message that failed under one owner continues from its stored count under the next, instead of
 *     starting from 1 and getting a fresh budget on every takeover.</li>
 *     <li>A clean handover - the previous owner released the shard - charges nothing, so routine deploys and
 *     rebalances cost queued messages no attempts.</li>
 *     <li>A takeover after a crash charges only the rows the crashed owner could have had in flight, not the
 *     whole backlog.</li>
 *     <li>A message whose handler takes the process down every time is dead-lettered once those charges reach
 *     the policy's limit - it never fails, so nothing else would ever park it.</li>
 *     <li>A redelivery the handler asked for is not counted, so however often it asked, the next unclean takeover
 *     charges the message one attempt and does not dead-letter it.</li>
 *     <li>The charge is fenced: an owner whose lease has already moved on when its pump first takes it over charges
 *     nothing, so the successor's in-flight rows are not charged a second time, and the owner reads as having lost
 *     its lease. An owner that still holds its lease and finds nothing to charge keeps it.</li>
 * </ul>
 * Which rows a takeover charges, exactly: {@code ShardOwnedTakeoverChargeIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedTakeoverAttemptsIT {

    private static final short    QUEUE_ID    = 1;
    private static final int      SHARD_COUNT = 4;
    private static final Duration LEASE_TTL   = Duration.ofMillis(1_000);

    /**
     * Defaults, except for a short lease, so a takeover after a crash does not wait out a production TTL
     */
    private static final ShardOwnerSettings SHORT_LEASE = new ShardOwnerSettings(500, 200,
                                                                                Duration.ofMillis(1), Duration.ofMillis(2),
                                                                                Duration.ofMillis(300), Duration.ofMillis(100),
                                                                                1_000, 8, Duration.ofMillis(100), Duration.ofSeconds(30), 2,
                                                                                Duration.ofSeconds(5), LEASE_TTL, Duration.ofSeconds(60));

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;
    private final CountDownLatch crashedHandlersRelease = new CountDownLatch(1);

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
        crashedHandlersRelease.countDown();
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void a_message_that_failed_under_the_previous_owner_continues_from_its_stored_attempt_count() throws Exception {
        var deliveries = new AtomicInteger();
        // A backoff long enough to hand the shards over while the message waits for its retry
        var policy = RedeliveryPolicy.fixed(Duration.ofSeconds(2), 3);

        try (var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            first.startConsuming((messageId, payload, payloadType) -> {
                deliveries.incrementAndGet();
                throw new IllegalStateException("always fails");
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, policy);
            first.enqueue(List.of(bytes("failing")), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> deliveries.get() == 1);
        }
        // Closed cleanly with the message waiting out its first backoff, its count (1) on the row

        try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-2")) {
            second.startConsuming((messageId, payload, payloadType) -> {
                deliveries.incrementAndGet();
                throw new IllegalStateException("always fails");
            }, ShardOwnerSettings.defaults(), SHARD_COUNT, policy);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(second.deadLetterCount()).isEqualTo(1L));
            assertThat(deliveries.get())
                    .as("3 attempts in total across both owners, not 3 more under the second")
                    .isEqualTo(3);
        }
    }

    @Test
    void a_clean_handover_charges_no_attempts() throws Exception {
        enqueueBacklog(1_000);
        var started = new CountDownLatch(1);

        try (var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            first.startConsuming((messageId, payload, payloadType) -> {
                started.countDown();
                awaitUninterruptibly(crashedHandlersRelease, Duration.ofMillis(50));
            }, ShardOwnerSettings.defaults(), SHARD_COUNT);
            assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
        }

        try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-2")) {
            var handled = new AtomicInteger();
            second.startConsuming((messageId, payload, payloadType) -> handled.incrementAndGet(),
                                  ShardOwnerSettings.defaults(), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(second.remaining()).isZero());

            assertThat((Long) second.metrics().snapshot().get("takeoverAttemptBumps"))
                    .as("the first owner released its shards, so it left nothing mid-delivery")
                    .isZero();
        }
    }

    @Test
    void a_takeover_after_a_crash_charges_only_what_the_crashed_owner_could_have_had_in_flight() throws Exception {
        var backlog = 2_000;
        // One enqueue call puts its whole batch on one shard
        enqueueBacklog(backlog);
        var occupiedShards = countOccupiedShards();
        var started = new CountDownLatch(1);

        var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1");
        first.startConsuming((messageId, payload, payloadType) -> {
            started.countDown();
            awaitUninterruptibly(crashedHandlersRelease, null);
        }, SHORT_LEASE, SHARD_COUNT);
        assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
        first.stopAbruptly();
        Thread.sleep(LEASE_TTL.toMillis() + 200);

        try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-2")) {
            second.startConsuming((messageId, payload, payloadType) -> {
            }, ShardOwnerSettings.defaults(), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(second.remaining()).isZero());

            var charged = (Long) second.metrics().snapshot().get("takeoverAttemptBumps");
            assertThat(charged).as("the crashed owner may have left a handler mid-delivery").isPositive();
            // The window is per shard: the backlog's single enqueue may still have been spread over a few
            assertThat(charged)
                    .as("at most parallelConsumers (8) + ackBatchSize (200) rows per occupied shard (%s), not the %s-row backlog",
                        occupiedShards, backlog)
                    .isLessThanOrEqualTo((8L + 200L) * occupiedShards)
                    .isLessThan(backlog);
        } finally {
            first.close();
        }
    }

    @Test
    void an_unordered_message_that_crashes_every_owner_is_dead_lettered_after_the_policy_is_exhausted() throws Exception {
        var deliveries = new AtomicInteger();
        var policy     = RedeliveryPolicy.fixed(Duration.ofMillis(10), 3);
        var queues     = new ArrayList<ShardOwnedQueue>();
        try {
            var seed = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "seed");
            queues.add(seed);
            seed.enqueue(List.of(bytes("crashes-the-process")), 1);

            for (var owner = 1; owner <= 3; owner++) {
                var expectedDeliveries = owner;
                var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-" + owner);
                queues.add(queue);
                queue.startConsuming((messageId, payload, payloadType) -> {
                    deliveries.incrementAndGet();
                    awaitUninterruptibly(crashedHandlersRelease, null);
                }, SHORT_LEASE, SHARD_COUNT, policy);
                Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> deliveries.get() == expectedDeliveries);
                queue.stopAbruptly();
                Thread.sleep(LEASE_TTL.toMillis() + 200);
            }

            try (var last = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-4")) {
                last.startConsuming((messageId, payload, payloadType) -> deliveries.incrementAndGet(),
                                    SHORT_LEASE, SHARD_COUNT, policy);
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> assertThat(last.deadLetterCount()).isEqualTo(1L));
                assertThat(deliveries.get()).as("delivered exactly maxAttempts times, then parked").isEqualTo(3);
                assertThat(last.deadLetters().getFirst().error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS);
                assertThat(last.remaining()).isZero();
            }
        } finally {
            crashedHandlersRelease.countDown();
            for (var queue : queues) {
                queue.close();
            }
        }
    }

    @Test
    void redeliveries_a_handler_requested_never_bring_a_message_closer_to_a_takeover_dead_letter() throws Exception {
        var deliveries = new AtomicInteger();
        var handled    = new AtomicInteger();
        var policy     = RedeliveryPolicy.fixed(Duration.ofMillis(10), 3);
        var requests   = 5;

        var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1");
        try {
            first.startConsuming((messageId, payload, payloadType) -> {
                if (deliveries.incrementAndGet() <= requests) {
                    throw new RedeliveryRequested(Duration.ofMillis(10));
                }
                // Then the process goes down in the middle of the next delivery
                awaitUninterruptibly(crashedHandlersRelease, null);
            }, SHORT_LEASE, SHARD_COUNT, policy);
            first.enqueue(List.of(bytes("asks-to-wait")), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> deliveries.get() == requests + 1);
            first.stopAbruptly();
            Thread.sleep(LEASE_TTL.toMillis() + 200);

            try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-2")) {
                second.startConsuming((messageId, payload, payloadType) -> handled.incrementAndGet(), SHORT_LEASE, SHARD_COUNT, policy);
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(handled.get()).isEqualTo(1));
                assertThat(second.deadLetterCount())
                        .as("%s requested redeliveries against a policy of 3 attempts, then one unclean takeover: one attempt charged", requests)
                        .isZero();
                assertThat(second.remaining()).isZero();
            }
        } finally {
            crashedHandlersRelease.countDown();
            first.close();
        }
    }

    @Test
    void an_ordered_message_that_crashes_every_owner_is_dead_lettered_and_blocks_its_key() throws Exception {
        var deliveries = new AtomicInteger();
        var delivered  = new CopyOnWriteArrayList<String>();
        var policy     = RedeliveryPolicy.fixed(Duration.ofMillis(10), 3);
        var queues     = new ArrayList<ShardOwnedQueue>();
        try {
            var seed = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "seed");
            queues.add(seed);
            seed.enqueueOrdered(List.of(new OrderedPayload("key-1", 0, bytes("crashes-the-process"), 1, Duration.ZERO),
                                        new OrderedPayload("key-1", 1, bytes("behind-it"), 1, Duration.ZERO)));

            for (var owner = 1; owner <= 3; owner++) {
                var expectedDeliveries = owner;
                var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-" + owner);
                queues.add(queue);
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    deliveries.incrementAndGet();
                    awaitUninterruptibly(crashedHandlersRelease, null);
                }, SHORT_LEASE, SHARD_COUNT, policy);
                Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> deliveries.get() == expectedDeliveries);
                queue.stopAbruptly();
                Thread.sleep(LEASE_TTL.toMillis() + 200);
            }

            try (var last = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-4")) {
                last.startConsumingOrdered((messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                           SHORT_LEASE, SHARD_COUNT, policy);
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> assertThat(last.deadLetters())
                                  .anySatisfy(deadLetter -> assertThat(deadLetter.error()).isEqualTo(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS)));
                // The key stops at its dead letter: the message behind it must not be delivered past it
                Thread.sleep(1_000);
                assertThat(delivered).isEmpty();
                assertThat(deliveries.get()).isEqualTo(3);
            }
        } finally {
            crashedHandlersRelease.countDown();
            for (var queue : queues) {
                queue.close();
            }
        }
    }

    @Test
    void an_unordered_owner_whose_lease_was_taken_before_its_takeover_charges_nothing() throws Exception {
        var policy = RedeliveryPolicy.fixed(Duration.ofSeconds(1), 2);
        try (var connection = dataSource.getConnection()) {
            storage().enqueueBatch(connection, 0, List.of(bytes("a"), bytes("b"), bytes("c")), 1);
        }
        var stale     = takeUncleanly("unordered", "instance-1");
        // instance-1 has no membership row, so it counts as gone and its lease is taken under a new fence
        var successor = storage().acquireLeaseGrant("unordered", 0, "instance-2", LEASE_TTL.toMillis()).orElseThrow();
        assertThat(successor.fence()).isGreaterThan(stale.fence());

        var successorMetrics = new ShardOwnerMetrics();
        var successorOwner = new ShardOwner(storage(), 0, successor.fence(), ShardOwnerSettings.defaults(), (id, payload, type) -> { },
                                            successorMetrics, policy, new ShardWakeup(), "instance-2", HandlerDispatch.inline(),
                                            successor.previousOwnerReleased());
        var staleMetrics = new ShardOwnerMetrics();
        var staleOwner = new ShardOwner(storage(), 0, stale.fence(), ShardOwnerSettings.defaults(), (id, payload, type) -> { },
                                        staleMetrics, policy, new ShardWakeup(), "instance-1", HandlerDispatch.inline(),
                                        stale.previousOwnerReleased());

        try (var connection = dataSource.getConnection()) {
            // The successor is delivering: its charge has landed
            successorOwner.onTakeover(connection);
            assertThat(successorMetrics.takeoverAttemptBumps.sum()).as("the control: the rows are chargeable").isEqualTo(3);
            // ... and then the superseded owner's pump runs its takeover
            staleOwner.onTakeover(connection);
        }

        assertThat(staleMetrics.takeoverAttemptBumps.sum()).as("refused by the fence").isZero();
        assertThat(attemptsOf(ShardOwnedSchema.UNORDERED_TABLE)).as("charged once, by the successor").containsOnly(1);
        assertThat(staleOwner.leaseRow()).as("a refused charge is a lost lease, as on the ack path").isEqualTo(LeasedOwner.LeaseRow.RELEASED);
        assertThat(successorOwner.leaseHeld()).isTrue();
    }

    @Test
    void an_ordered_owner_whose_lease_was_taken_before_its_takeover_charges_nothing() throws Exception {
        var policy = RedeliveryPolicy.fixed(Duration.ofSeconds(1), 2);
        insertOrdered("key-1", 0, 1);
        insertOrdered("key-2", 0, 2);
        var stale     = takeUncleanly("ordered", "instance-1");
        // instance-1 has no membership row, so it counts as gone and its lease is taken under a new fence
        var successor = storage().acquireLeaseGrant("ordered", 0, "instance-2", LEASE_TTL.toMillis()).orElseThrow();
        assertThat(successor.fence()).isGreaterThan(stale.fence());

        var successorMetrics = new ShardOwnerMetrics();
        var successorOwner = new OrderedShardOwner(storage(), 0, successor.fence(), ShardOwnerSettings.defaults(), (id, key, payload, type) -> { },
                                                   successorMetrics, policy, "instance-2", new ShardWakeup(), HandlerDispatch.inline(),
                                                   successor.previousOwnerReleased());
        var staleMetrics = new ShardOwnerMetrics();
        var staleOwner = new OrderedShardOwner(storage(), 0, stale.fence(), ShardOwnerSettings.defaults(), (id, key, payload, type) -> { },
                                               staleMetrics, policy, "instance-1", new ShardWakeup(), HandlerDispatch.inline(),
                                               stale.previousOwnerReleased());

        try (var connection = dataSource.getConnection()) {
            successorOwner.onTakeover(connection);
            assertThat(successorMetrics.takeoverAttemptBumps.sum()).as("the control: the rows are chargeable").isEqualTo(2);
            staleOwner.onTakeover(connection);
        }

        assertThat(staleMetrics.takeoverAttemptBumps.sum()).as("refused by the fence").isZero();
        assertThat(attemptsOf(ShardOwnedSchema.ORDERED_TABLE)).as("charged once, by the successor").containsOnly(1);
        assertThat(staleOwner.leaseRow()).as("a refused charge is a lost lease, as on the ack path").isEqualTo(LeasedOwner.LeaseRow.RELEASED);
        assertThat(successorOwner.leaseHeld()).isTrue();
    }

    @Test
    void an_unordered_owner_that_takes_an_empty_shard_uncleanly_keeps_its_lease() throws Exception {
        var grant   = takeUncleanly("unordered", "instance-1");
        var metrics = new ShardOwnerMetrics();
        var owner = new ShardOwner(storage(), 0, grant.fence(), ShardOwnerSettings.defaults(), (id, payload, type) -> { },
                                   metrics, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 2), new ShardWakeup(), "instance-1",
                                   HandlerDispatch.inline(), grant.previousOwnerReleased());

        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
        }

        assertThat(metrics.takeoverAttemptBumps.sum()).as("nothing to charge").isZero();
        assertThat(owner.leaseRow()).as("charging nothing under a lease still held is not a lost lease").isEqualTo(LeasedOwner.LeaseRow.HELD);
        assertThat(owner.leaseHeld()).isTrue();
    }

    @Test
    void an_ordered_owner_that_takes_an_empty_unit_uncleanly_keeps_its_lease() throws Exception {
        var grant   = takeUncleanly("ordered", "instance-1");
        var metrics = new ShardOwnerMetrics();
        var owner = new OrderedShardOwner(storage(), 0, grant.fence(), ShardOwnerSettings.defaults(), (id, key, payload, type) -> { },
                                          metrics, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 2), "instance-1", new ShardWakeup(),
                                          HandlerDispatch.inline(), grant.previousOwnerReleased());

        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
        }

        assertThat(metrics.takeoverAttemptBumps.sum()).as("nothing to charge").isZero();
        assertThat(owner.leaseRow()).as("charging nothing under a lease still held is not a lost lease").isEqualTo(LeasedOwner.LeaseRow.HELD);
        assertThat(owner.leaseHeld()).isTrue();
    }

    private ShardOwnedStorage storage() {
        return new ShardOwnedStorage(dataSource, QUEUE_ID);
    }

    /**
     * Take unit 0 of {@code lane} for {@code instanceId} from a holder that did not release it: an instance with no
     * membership row, which counts as gone.
     */
    private ShardOwnedStorage.LeaseGrant takeUncleanly(String lane, String instanceId) throws Exception {
        var storage = storage();
        storage.acquireLease(lane, 0, "crashed-before-" + instanceId, LEASE_TTL.toMillis()).orElseThrow();
        var grant = storage.acquireLeaseGrant(lane, 0, instanceId, LEASE_TTL.toMillis()).orElseThrow();
        assertThat(grant.previousOwnerReleased()).as("an unclean takeover, which owes a charge").isFalse();
        return grant;
    }

    private void insertOrdered(String key, long keyOrder, long seq) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + ShardOwnedSchema.ORDERED_TABLE + " (queue_id, shard, msg_key, key_order, seq, payload, payload_type, attempts)"
                             + " VALUES (?, 0, ?, ?, ?, '\\x00'::bytea, 1, 0)")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, key);
            statement.setLong(3, keyOrder);
            statement.setLong(4, seq);
            statement.executeUpdate();
        }
    }

    private List<Integer> attemptsOf(String table) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT attempts FROM " + table + " WHERE queue_id = ? AND shard = 0")) {
            statement.setShort(1, QUEUE_ID);
            try (var resultSet = statement.executeQuery()) {
                var attempts = new ArrayList<Integer>();
                while (resultSet.next()) {
                    attempts.add(resultSet.getInt(1));
                }
                assertThat(attempts).as("rows on unit 0 of " + table).isNotEmpty();
                return attempts;
            }
        }
    }

    private long countOccupiedShards() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT count(DISTINCT shard) FROM " + ShardOwnedSchema.UNORDERED_TABLE)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private void enqueueBacklog(int messages) throws Exception {
        try (var producer = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "producer")) {
            var payloads = new ArrayList<byte[]>(messages);
            for (var index = 0; index < messages; index++) {
                payloads.add(bytes("m-" + index));
            }
            producer.enqueue(payloads, 1);
        }
    }

    /**
     * A handler that never returns stands in for one that took the process down. Uninterruptible, so stopping the
     * queue cannot turn it into a completed delivery; {@code maxWait} bounds it where the test needs it to end.
     */
    private static void awaitUninterruptibly(CountDownLatch latch, Duration maxWait) {
        var interrupted = false;
        var deadline    = maxWait != null ? System.nanoTime() + maxWait.toNanos() : Long.MAX_VALUE;
        while (System.nanoTime() < deadline) {
            try {
                if (latch.await(Math.min(100, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)), TimeUnit.MILLISECONDS)) {
                    break;
                }
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
