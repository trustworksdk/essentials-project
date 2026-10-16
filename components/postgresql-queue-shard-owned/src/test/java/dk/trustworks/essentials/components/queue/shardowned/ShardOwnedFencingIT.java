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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fencing (§4.7): a lease is a time-based guarantee, and time-based guarantees can be violated by a
 * stop-the-world pause or a clock jump. An owner that believes it still holds a shard which has
 * already moved must not be able to write.
 * <p>
 * The dangerous write is the acknowledgement, because it <em>deletes</em>. A superseded owner
 * acknowledging its in-flight work would remove exactly the messages the new owner is about to
 * deliver — silent loss, at the moment the system is already in trouble.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedFencingIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 2;

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(30);
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
    void a_superseded_owner_cannot_acknowledge() throws Exception {
        var storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
        try (var connection = dataSource.getConnection()) {
            storage.enqueueBatch(connection, 0, List.of("a".getBytes(StandardCharsets.UTF_8),
                                                        "b".getBytes(StandardCharsets.UTF_8)), 1);
        }

        var staleFence = storage.acquireLease("unordered", 0, "instance-1", 60_000L).orElseThrow();
        assertThat(storage.stillOwns("unordered", 0, "instance-1", staleFence)).isTrue();

        // The shard moves on: the lease is expired and taken by another instance, which bumps the
        // fence. instance-1 has no way to know locally — that is the whole point of fencing.
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE " + ShardOwnedSchema.LEASE_TABLE + " SET lease_until = now() - interval '1 second'"
                     + " WHERE queue_id = ? AND lane = 'unordered' AND shard = 0")) {
            statement.setShort(1, QUEUE_ID);
            statement.executeUpdate();
        }
        var newFence = storage.acquireLease("unordered", 0, "instance-2", 60_000L).orElseThrow();
        assertThat(newFence).isGreaterThan(staleFence);
        assertThat(storage.stillOwns("unordered", 0, "instance-1", staleFence)).isFalse();

        var before = storage.countRemaining(0);
        try (var connection = dataSource.getConnection()) {
            var deleted = storage.acknowledge(connection, 0, Long.MAX_VALUE, List.of(), "instance-1", staleFence);
            assertThat(deleted)
                    .as("a superseded owner's acknowledgement must delete nothing")
                    .isZero();
        }
        assertThat(storage.countRemaining(0))
                .as("the messages must still be there for the new owner")
                .isEqualTo(before);

        // And the rightful owner's acknowledgement still works, so the fence is not simply blocking
        // everything.
        try (var connection = dataSource.getConnection()) {
            assertThat(storage.acknowledge(connection, 0, Long.MAX_VALUE, List.of(), "instance-2", newFence))
                    .as("the current owner must still be able to acknowledge")
                    .isEqualTo((int) before);
        }
        assertThat(storage.countRemaining(0)).isZero();
    }

    /**
     * One of the two ways out of {@link LeasedOwner.LeaseRow#ENDED_ROW_HELD} that do not go through
     * {@link LeasedOwner#onLeaseEnded} (the other, a takeover attempt charge refused by its fence, is
     * {@code ShardOwnedTakeoverAttemptsIT}'s): the owner's own acknowledgement flush is refused by its fence, and the
     * owner moves itself to {@code RELEASED}.
     * {@code stop()} ends every held owner keeping its row and then flushes the acknowledgements of those that were
     * serving, so a successor that took the unit in between fences that flush out. From either state the flush ran in,
     * the owner must then stop counting the unit as this instance's, and nothing may move it back.
     */
    @ParameterizedTest(name = "{0} lane, flushed while {1}")
    @CsvSource({"unordered, HELD", "unordered, ENDED_ROW_HELD", "ordered, HELD", "ordered, ENDED_ROW_HELD"})
    void an_owner_whose_acknowledgement_flush_is_fenced_out_no_longer_holds_its_lease_row(String lane, LeasedOwner.LeaseRow flushedIn) throws Exception {
        var storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
        var payload = "a".getBytes(StandardCharsets.UTF_8);
        try (var connection = dataSource.getConnection()) {
            if ("ordered".equals(lane)) {
                storage.enqueueOrderedBatch(connection, 0, List.of(new ShardOwnedStorage.OrderedPayload("key-1", 1L, payload, 1)));
            } else {
                storage.enqueueBatch(connection, 0, List.of(payload), 1);
            }
        }
        var grant = storage.acquireLeaseGrant(lane, 0, "instance-1", 60_000L).orElseThrow();

        var metrics = new ShardOwnerMetrics();
        var handled = new AtomicInteger();
        var owner   = owner(lane, storage, grant, metrics, handled);
        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
            // Handled inline, on this thread; the flush interval is out of the pass's reach, so the acknowledgement stays pending.
            Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10))
                      .until(() -> {
                          owner.pumpOnce(connection);
                          return handled.get() == 1;
                      });
        }
        assertThat(remaining(storage, lane)).as("handled, not yet acknowledged").isEqualTo(1L);
        assertThat(metrics.fencedOutAcks.sum()).isZero();

        if (flushedIn == LeasedOwner.LeaseRow.ENDED_ROW_HELD) {
            owner.endKeepingLeaseRow();
        }
        assertThat(owner.leaseRow()).isEqualTo(flushedIn);

        // A successor takes the unit: the lease expires and another instance acquires it, bumping the fence.
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE " + ShardOwnedSchema.LEASE_TABLE + " SET lease_until = now() - interval '1 second'"
                     + " WHERE queue_id = ? AND lane = ? AND shard = 0")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            assertThat(statement.executeUpdate()).isOne();
        }
        assertThat(storage.acquireLease(lane, 0, "instance-2", 60_000L).orElseThrow()).isGreaterThan(grant.fence());

        try (var connection = dataSource.getConnection()) {
            owner.flushOnStop(connection);
        }

        assertThat(metrics.fencedOutAcks.sum()).as("the flush was refused by the fence").isEqualTo(1L);
        assertThat(remaining(storage, lane)).as("left for the successor").isEqualTo(1L);
        assertThat(owner.leaseRow()).as("the row names the successor now").isEqualTo(LeasedOwner.LeaseRow.RELEASED);
        assertThat(owner.leaseHeld()).isFalse();
        assertThat(owner.holdsLeaseRow()).as("no longer counted as this instance's").isFalse();

        owner.endKeepingLeaseRow();
        assertThat(owner.leaseRow()).as("a released row is never held again").isEqualTo(LeasedOwner.LeaseRow.RELEASED);
        assertThat(owner.holdsLeaseRow()).isFalse();
    }

    private static LeasedOwner owner(String lane, ShardOwnedStorage storage, ShardOwnedStorage.LeaseGrant grant, ShardOwnerMetrics metrics,
                                     AtomicInteger handled) {
        var d = ShardOwnerSettings.defaults();
        // An acknowledgement batch size and flush interval no pump pass reaches, so the acknowledgement is still pending
        // when flushOnStop runs.
        var settings = new ShardOwnerSettings(d.readBatchSize(), 1_000, Duration.ofDays(36_500), d.chaseDelay(), d.holeExpiry(),
                                              d.sweepInterval(), d.maxHolesPerChase(), d.keyConcurrency(), d.pollBackstop(),
                                              d.maxSweepInterval(), d.pumpThreads(), d.shedGrace(), d.leaseTtl(), d.watermarkCap());
        var policy = RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3);
        if ("ordered".equals(lane)) {
            return new OrderedShardOwner(storage, 0, grant.fence(), settings, (id, key, payload, type) -> handled.incrementAndGet(),
                                         metrics, policy, "instance-1", new ShardWakeup(), HandlerDispatch.inline(),
                                         grant.previousOwnerReleased());
        }
        return new ShardOwner(storage, 0, grant.fence(), settings, (id, payload, type) -> handled.incrementAndGet(), metrics, policy,
                              new ShardWakeup(), "instance-1", HandlerDispatch.inline(), grant.previousOwnerReleased());
    }

    private static long remaining(ShardOwnedStorage storage, String lane) throws Exception {
        return "ordered".equals(lane) ? storage.countOrderedRemaining(0) : storage.countRemaining(0);
    }

    /**
     * The heartbeat has to keep a long-running owner's lease alive. Without it a slow handler would
     * lose its shard to the expiry sweep while it is still working perfectly well.
     */
    @Test
    void a_working_owner_keeps_its_lease_alive() throws Exception {
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        // A lease TTL of 900ms, so several renewals must happen inside the test window.
        var shortLease = new ShardOwnerSettings(500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
                                                Duration.ofMillis(300), Duration.ofMillis(100),
                                                1_000, 8, Duration.ofMillis(50), Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofMillis(1000), Duration.ofSeconds(60));

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 shortLease, SHARD_COUNT);

            // Idle for well over the lease lifetime, then prove the owner still works.
            Thread.sleep(3_000L);

            var metrics = queue.metrics().snapshot();
            assertThat((Long) metrics.get("leaseRenewals"))
                    .as("the heartbeat must have renewed while idle: %s", metrics)
                    .isPositive();
            assertThat((Long) metrics.get("leasesLost"))
                    .as("nothing else is competing, so no lease should have been lost: %s", metrics)
                    .isZero();

            queue.enqueue(List.of("still-alive".getBytes(StandardCharsets.UTF_8)), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(received).containsExactly("still-alive"));
            assertThat((Long) queue.metrics().snapshot().get("fencedOutAcks"))
                    .as("a renewed owner must never be fenced out")
                    .isZero();
        }
    }
}
