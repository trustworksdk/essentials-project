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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.queryForInt;
import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.queryForLong;
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
    /**
     * Short, so the heartbeat - and the {@code rebalance} it runs - ticks about once a second instead of every 10 s on
     * the default 30 s lease. The property is "still owns every shard after several rebalances", not "after 25 s".
     */
    private static final Duration LEASE_TTL   = Duration.ofSeconds(3);
    /** The heartbeat period: a third of the lease TTL ({@code ShardOwnedQueue}). */
    private static final Duration HEARTBEAT   = LEASE_TTL.dividedBy(3);

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
    void a_single_process_still_owns_every_shard_after_the_first_rebalance() throws Exception {
        var delivered        = ConcurrentHashMap.<String>newKeySet();
        var deliveredShards  = ConcurrentHashMap.<Integer>newKeySet();
        var settings = ShardOwnerSettings.builder().setLeaseTtl(LEASE_TTL).build();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "spi-fair-1", settings)) {
            var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                                                 delivered.add(new String(payload, StandardCharsets.UTF_8));
                                                 deliveredShards.add(messageId.shard());
                                             },
                                             ConsumerOptions.defaults());

            // The heartbeat runs at a third of the lease TTL and runs rebalance on every tick. Wait past
            // several of them: the two consumers tick in an arbitrary order, so the first tick may see
            // only one instance row. Counted in heartbeats, not in wall-clock time: only a heartbeat
            // writes last_seen, so once it has moved three heartbeat periods past its first value,
            // at least three ticks have run.
            var firstSeenMillis = org.awaitility.Awaitility.await()
                                                           .atMost(Duration.ofSeconds(30))
                                                           .until(this::lastSeenMillis, millis -> millis > 0);
            org.awaitility.Awaitility.await()
                                     .atMost(Duration.ofSeconds(30))
                                     .pollInterval(Duration.ofMillis(100))
                                     .until(this::lastSeenMillis,
                                            millis -> millis >= firstSeenMillis + 3 * HEARTBEAT.toMillis());

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

            org.awaitility.Awaitility.await()
                                     .atMost(Duration.ofSeconds(30))
                                     .untilAsserted(() -> assertThat(delivered)
                                             .describedAs("everything enqueued after the rebalance is delivered")
                                             .hasSize(200));

            // Read the spread off the DELIVERIES, not off the rows. The shard is part of the message
            // id, so this is the same fact the enqueue recorded — but counting rows per shard races
            // the consumer that is draining them: local hand-off delivers and deletes in under a
            // millisecond, so a shard can already be empty by the time the loop above returns. That
            // read reported 2 shards of 4 for a round-robin that had used all four.
            assertThat(deliveredShards)
                    .describedAs("round-robin must use every shard, not just one per call")
                    .hasSize(SHARD_COUNT);
        }
    }

    private int countUnownedShards(String lane) throws Exception {
        return queryForInt(dataSource, "Counting the free " + lane + " lease rows of queue " + QUEUE_ID,
                           "SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE
                           + " WHERE queue_id = ? AND lane = ? AND (owner IS NULL OR lease_until <= now())",
                           QUEUE_ID, lane);
    }

    /** The latest heartbeat of any instance row of the queue, in epoch milliseconds, or 0 while there is none. */
    private long lastSeenMillis() throws Exception {
        return queryForLong(dataSource, "Reading the latest heartbeat of queue " + QUEUE_ID,
                            "SELECT coalesce((extract(epoch FROM max(last_seen)) * 1000)::bigint, 0) FROM "
                            + ShardOwnedSchema.INSTANCE_TABLE + " WHERE queue_id = ?", QUEUE_ID);
    }

    private int countLiveInstanceRows() throws Exception {
        return queryForInt(dataSource, "Counting the instance rows of queue " + QUEUE_ID,
                           "SELECT count(*) FROM " + ShardOwnedSchema.INSTANCE_TABLE + " WHERE queue_id = ?", QUEUE_ID);
    }
}
