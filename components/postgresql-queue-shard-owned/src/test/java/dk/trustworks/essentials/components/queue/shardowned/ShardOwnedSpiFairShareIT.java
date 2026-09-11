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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One process consuming through the SPI must end up owning every shard of both lanes.
 * <p>
 * The hazard this constructs is time-delayed and therefore invisible to every other test in this
 * module: nothing shows up until the first heartbeat runs {@code rebalance}, which is at a third of
 * the lease lifetime. A test that finishes inside that window cannot distinguish "this instance owns
 * everything" from "this instance has not yet been told it owns too much".
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedSpiFairShareIT {

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
    void a_single_process_still_owns_every_shard_after_the_first_rebalance() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "spi-fair-1")) {
            var subscription = queue.consume((key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                             ConsumerOptions.defaults());

            // The default lease TTL is holeExpiry x 3 = 30s and the heartbeat runs at a third of it,
            // so rebalances land at about 10, 20 and 30 seconds. Wait past several of them: the two
            // consumers tick in an arbitrary order, so the first tick may see only one instance row.
            Thread.sleep(Duration.ofSeconds(25));

            assertThat(countLiveInstanceRows())
                    .describedAs("one process consuming through the contract is ONE instance, not one per lane")
                    .isEqualTo(1);
            assertThat(subscription.shardsHeld())
                    .describedAs("units of BOTH lanes still held after several rebalances — this used to "
                                 + "read SHARD_COUNT because shardsHeld() counted the unordered lane alone")
                    .isEqualTo(SHARD_COUNT + ShardOwnedSchema.ORDERED_UNITS);
            assertThat(countUnownedShards("unordered") + countUnownedShards("ordered"))
                    .describedAs("shards with no live owner")
                    .isZero();

            // The property that actually matters. Enqueued AFTER the rebalance and one call per
            // message, so the messages spread across every shard rather than landing on whichever
            // one a single batched call happened to pick.
            for (var i = 0; i < 200; i++) {
                queue.enqueue(Message.of(("m" + i).getBytes(StandardCharsets.UTF_8), 1));
            }
            assertThat(rowsPerShard().keySet())
                    .describedAs("round-robin must use every shard, not just one per call")
                    .hasSize(SHARD_COUNT);

            org.awaitility.Awaitility.await()
                                     .atMost(Duration.ofSeconds(30))
                                     .untilAsserted(() -> assertThat(delivered)
                                             .describedAs("everything enqueued after the rebalance is delivered")
                                             .hasSize(200));
        }
    }

    private Map<Integer, Integer> rowsPerShard() throws Exception {
        var perShard = new TreeMap<Integer, Integer>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT shard, count(*) FROM " + ShardOwnedSchema.UNORDERED_TABLE
                     + " WHERE queue_id = ? GROUP BY shard ORDER BY shard")) {
            statement.setShort(1, QUEUE_ID);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    perShard.put(resultSet.getInt(1), resultSet.getInt(2));
                }
            }
        }
        return perShard;
    }

    private int countUnownedShards(String lane) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE
                     + " WHERE queue_id = ? AND lane = ? AND (owner IS NULL OR lease_until <= now())")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private int countLiveInstanceRows() throws Exception {
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
