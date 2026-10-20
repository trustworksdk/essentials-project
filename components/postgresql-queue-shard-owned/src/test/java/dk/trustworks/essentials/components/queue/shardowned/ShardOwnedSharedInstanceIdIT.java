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
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two live processes running a queue under one instance id are reported; a restart and one process's two lanes are
 * not.
 * <p>
 * Two processes given the same instance id - configured to one value, or derived from something they share, such as
 * the hostname - are counted by the rebalance as one
 * instance and they keep taking the same shards from each other. Each process stamps the membership row with its own
 * token on every heartbeat, so finding another process's stamp after this one's first heartbeat means the id is
 * shared. A second process is simulated by re-stamping the row with a different token.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedSharedInstanceIdIT {
    private static final short    QUEUE_ID    = 1;
    private static final int      SHARD_COUNT = 2;
    private static final String   INSTANCE_ID = "host-1";
    /**
     * A 1 s lease, so the heartbeat runs every ~333 ms instead of every 10 s
     */
    private static final ShardOwnerSettings SHORT_LEASE = new ShardOwnerSettings(500, 200,
                                                                                Duration.ofMillis(1), Duration.ofMillis(2),
                                                                                Duration.ofSeconds(10), Duration.ofMillis(500),
                                                                                1_000, 8, Duration.ofMillis(500), Duration.ofSeconds(30), 2,
                                                                                Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(60),
                                                                                ShardOwnerSettings.DEFAULT_STUCK_HANDLER_TIMEOUT);

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
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
    void another_live_process_under_the_same_instance_id_is_reported() throws Exception {
        var otherProcess = Executors.newSingleThreadScheduledExecutor();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, INSTANCE_ID)) {
            queue.startConsuming((messageId, payload, payloadType) -> { }, SHORT_LEASE, SHARD_COUNT);
            otherProcess.scheduleAtFixedRate(() -> stamp("another-process"), 0, 100, TimeUnit.MILLISECONDS);
            Thread.sleep(2_000);

            assertThat(sharedIdHeartbeats(queue)).isPositive();
        } finally {
            otherProcess.shutdownNow();
        }
    }

    @Test
    void a_restart_after_a_crash_is_not_reported() throws Exception {
        // The crashed predecessor's stamp, fresh enough that its row still counts as live
        stamp("crashed-predecessor");
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, INSTANCE_ID)) {
            queue.startConsuming((messageId, payload, payloadType) -> { }, SHORT_LEASE, SHARD_COUNT);
            Thread.sleep(2_000);

            assertThat(sharedIdHeartbeats(queue)).isZero();
        }
    }

    @Test
    void one_processs_unordered_and_ordered_consumers_are_not_reported() throws Exception {
        try (var unordered = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, INSTANCE_ID);
             var ordered = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, INSTANCE_ID)) {
            unordered.startConsuming((messageId, payload, payloadType) -> { }, SHORT_LEASE, SHARD_COUNT);
            ordered.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, SHORT_LEASE, SHARD_COUNT);
            Thread.sleep(2_000);

            assertThat(sharedIdHeartbeats(unordered) + sharedIdHeartbeats(ordered)).isZero();
        }
    }

    private void stamp(String incarnation) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + ShardOwnedSchema.INSTANCE_TABLE + " (queue_id, instance_id, last_seen, incarnation) VALUES (?, ?, now(), ?)"
                             + " ON CONFLICT (queue_id, instance_id) DO UPDATE SET last_seen = now(), incarnation = EXCLUDED.incarnation")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, INSTANCE_ID);
            statement.setString(3, incarnation);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long sharedIdHeartbeats(ShardOwnedQueue queue) {
        return (Long) queue.metrics().snapshot().get("sharedInstanceIdHeartbeats");
    }
}
