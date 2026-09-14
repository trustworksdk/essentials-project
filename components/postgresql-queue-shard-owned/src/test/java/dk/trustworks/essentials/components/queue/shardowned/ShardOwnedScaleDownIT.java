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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to a queue when an autoscaler removes an instance.
 * <p>
 * A graceful scale-down is the common case in a deployment that scales, and it is not the same event
 * as a crash. On a crash the shards are unavailable until the dead owner's leases expire, and the
 * lease TTL is the designed cost of that. On a graceful stop the leases are handed back immediately —
 * so the shards are free at once, and the only thing that should decide how fast a survivor picks
 * them up is how often it rebalances.
 * <p>
 * The membership row is what can get that wrong. {@code fairShare} is
 * {@code ceil(shardCount / liveInstances)}, so an instance that has departed but still counts as live
 * keeps every survivor at a quota computed for a cluster that no longer exists — the shards are free
 * and nobody is allowed to take them.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedScaleDownIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 8;

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
     * A short lease so the test does not have to wait out the thirty-second default to observe a
     * behaviour that is about rebalancing rather than about lease expiry.
     */
    private static ShardOwnerSettings fastLease() {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(),
                                      defaults.ackFlushInterval(), defaults.chaseDelay(),
                                      Duration.ofMillis(300), Duration.ofMillis(100),
                                      defaults.maxHolesPerChase(), defaults.keyConcurrency(),
                                      Duration.ofMillis(50), Duration.ofSeconds(30),
                                      defaults.pumpThreads(), defaults.shedGrace(),
                                      Duration.ofSeconds(3), Duration.ofSeconds(60));
    }

    @Test
    void a_gracefully_stopped_instance_stops_counting_towards_the_fair_share() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "scale-1");
        var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "scale-2");
        try {
            first.startConsuming((messageId, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                 fastLease(), SHARD_COUNT);
            second.startConsuming((messageId, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                  fastLease(), SHARD_COUNT);

            // Both registered and sharing the shards four and four.
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(liveInstances())
                              .describedAs("both instances have registered")
                              .isEqualTo(2));

            // The autoscaler removes one. Graceful: it releases its leases on the way out.
            second.stop();

            assertThat(liveInstances())
                    .describedAs("a departed instance must stop counting immediately — while it counts, "
                                 + "fairShare stays ceil(shards / 2) and the survivor is held at half "
                                 + "the queue with the other half free and owned by nobody")
                    .isEqualTo(1);

            // And the survivor must actually take the whole queue back.
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(first.shardsHeld())
                              .describedAs("the survivor takes over every shard")
                              .isEqualTo(SHARD_COUNT));

            first.enqueue(java.util.List.of("after-scale-down".getBytes(StandardCharsets.UTF_8)), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(delivered).contains("after-scale-down"));
        } finally {
            first.stop();
            second.stop();
        }
    }

    /**
     * Instance rows were only ever upserted, never removed. With an instance id that changes per boot
     * — which is what the Spring starter defaults to, and what any autoscaled deployment produces —
     * the membership table grows by one row per pod that has ever run, forever, and every heartbeat
     * of every queue scans it.
     */
    @Test
    void departed_instances_do_not_accumulate_rows_forever() throws Exception {
        for (var generation = 0; generation < 5; generation++) {
            var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ephemeral-" + generation);
            queue.startConsuming((messageId, payload, payloadType) -> {
            }, fastLease(), SHARD_COUNT);
            // Wait for the instance to actually REGISTER, not merely to hold shards. Leases are
            // taken synchronously by start(); the membership row is written by the first heartbeat.
            // An earlier version of this test stopped in between, so no row was ever created and it
            // passed while proving nothing.
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(totalInstanceRows())
                              .describedAs("generation must have registered before it departs")
                              .isPositive());
            queue.stop();
        }

        assertThat(totalInstanceRows())
                .describedAs("five pods came and went; the membership table must not have five dead rows in it")
                .isLessThanOrEqualTo(1);
    }

    private int liveInstances() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + ShardOwnedSchema.INSTANCE_TABLE
                     + " WHERE queue_id = ? AND last_seen > now() - interval '3 seconds'")) {
            statement.setShort(1, QUEUE_ID);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private int totalInstanceRows() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + ShardOwnedSchema.INSTANCE_TABLE + " WHERE queue_id = ?")) {
            statement.setShort(1, QUEUE_ID);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
