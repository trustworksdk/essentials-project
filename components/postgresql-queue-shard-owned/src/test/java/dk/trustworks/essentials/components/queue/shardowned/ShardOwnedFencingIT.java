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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

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
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

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
            queue.startConsuming((payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
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
