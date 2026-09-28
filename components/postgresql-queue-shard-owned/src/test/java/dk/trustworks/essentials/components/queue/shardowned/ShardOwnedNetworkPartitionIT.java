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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An instance that is alive but cannot reach the database must lose its units, and must not be able
 * to write when it comes back.
 *
 * <h2>What this covers that nothing else did</h2>
 * The suite already had two neighbouring failures, and neither is this one:
 * <ul>
 *   <li>{@code ShardOwnedConnectionLossIT} terminates the backends — a connection <b>reset</b>. The
 *       client gets an RST, learns immediately, reconnects and keeps its leases.</li>
 *   <li>{@code ShardOwnedMultiProcessIT} SIGSTOPs a node — <b>frozen</b>. It delivers nothing while
 *       stopped, so there is no window in which it acts on stale beliefs.</li>
 * </ul>
 * A partition is neither. The process keeps running, keeps handlers going, keeps whatever it already
 * read in memory — and learns nothing until a socket timeout. That gap, where a live owner believes
 * it owns units another instance has taken, is exactly what fencing exists for, and it was the one
 * failure the engine's own documentation listed as unmeasured.
 *
 * <h2>How the partition is made</h2>
 * {@link PartitionableProxy} sits between one instance and PostgreSQL and stops forwarding without
 * closing anything. The other instance talks to the database directly, so only one side is cut. A
 * short {@code socketTimeout} on the partitioned side is what a deployment should set anyway;
 * without one the JDBC calls hang until the OS gives up, which is a worse version of the same story.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedNetworkPartitionIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 8;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private PartitionableProxy proxy;
    private HikariDataSource   partitionable;
    private HikariDataSource   direct;

    @BeforeEach
    void setUp() throws Exception {
        proxy  = new PartitionableProxy(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
        direct = pool(postgres.getJdbcUrl());
        // Same database, reached through the proxy, so the partition cuts this instance alone.
        partitionable = pool("jdbc:postgresql://127.0.0.1:" + proxy.localPort() + "/" + postgres.getDatabaseName()
                             + "?socketTimeout=3&connectTimeout=3&loginTimeout=3");
        ShardOwnedSchema.recreate(direct);
        ShardOwnedSchema.registerQueue(direct, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (partitionable != null) {
            partitionable.close();
        }
        if (direct != null) {
            direct.close();
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    @Test
    void a_partitioned_owner_loses_its_units_and_is_fenced_out_when_it_returns() throws Exception {
        var cutOffReceived  = new CopyOnWriteArrayList<String>();
        var survivorReceived = new CopyOnWriteArrayList<String>();

        try (var cutOff = new ShardOwnedQueue(partitionable, QUEUE_ID, SHARD_COUNT, "cut-off", new ShardOwnerMetrics());
             var survivor = new ShardOwnedQueue(direct, QUEUE_ID, SHARD_COUNT, "survivor", new ShardOwnerMetrics())) {

            cutOff.startConsuming((messageId, payload, type) -> cutOffReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                  shortLease(), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(cutOff.shardsHeld())
                              .as("the instance that will be cut off must own something to lose")
                              .isEqualTo(SHARD_COUNT));

            for (var index = 0; index < 20; index++) {
                enqueue(direct, "before-" + index);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(cutOffReceived).hasSize(20));

            // The partition. No RST, no FIN — its heartbeat simply stops arriving, and it is not told.
            proxy.partition();

            survivor.startConsuming((messageId, payload, type) -> survivorReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                    shortLease(), SHARD_COUNT);

            // Liveness is the instance row, so the takeover waits out the staleness bound and no
            // longer. Nothing here depends on the cut-off instance noticing anything.
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(survivor.shardsHeld())
                              .as("a partitioned owner's units must be taken by a live instance")
                              .isEqualTo(SHARD_COUNT));

            // And the queue keeps working while one instance is cut off, which is the whole point of
            // taking the units rather than merely noticing they are stale.
            for (var index = 0; index < 20; index++) {
                enqueue(direct, "during-" + index);
            }
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(survivorReceived.stream().filter(m -> m.startsWith("during-")).distinct().count())
                              .as("the survivor must deliver everything enqueued during the partition")
                              .isEqualTo(20L));

            // Heal it. The returning instance still believes it owns the shards it held.
            proxy.heal();

            // Its fence is stale, so every write it attempts is refused. It converges on holding
            // nothing rather than competing with the survivor for the same units.
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(cutOff.shardsHeld())
                              .as("a returning instance must not keep units another instance now owns")
                              .isZero());

            assertThat(survivor.shardsHeld())
                    .as("and the survivor must not have lost them in the exchange")
                    .isEqualTo(SHARD_COUNT);

            // Nothing enqueued was lost. Duplicates across the partition are legal — at-least-once —
            // so this asserts on distinct payloads.
            var everything = new java.util.HashSet<String>();
            everything.addAll(cutOffReceived);
            everything.addAll(survivorReceived);
            assertThat(everything)
                    .as("every message must have been delivered by somebody")
                    .hasSize(40);
        }
    }

    private static void enqueue(HikariDataSource dataSource, String payload) throws Exception {
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "producer", new ShardOwnerMetrics())) {
            queue.enqueue(List.of(payload.getBytes(StandardCharsets.UTF_8)), 1);
        }
    }

    /**
     * A short lease TTL so the staleness bound elapses inside a test. It is the only thing that
     * decides how long a partitioned instance's units stay unavailable — correctness comes from the
     * fence, not from this number.
     */
    private static ShardOwnerSettings shortLease() {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(),
                                      defaults.ackFlushInterval(), defaults.chaseDelay(),
                                      defaults.holeExpiry(), defaults.sweepInterval(),
                                      defaults.maxHolesPerChase(), defaults.keyConcurrency(),
                                      defaults.pollBackstop(), defaults.maxSweepInterval(),
                                      defaults.pumpThreads(), defaults.shedGrace(),
                                      Duration.ofSeconds(3), defaults.watermarkCap());
    }

    private static HikariDataSource pool(String jdbcUrl) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(10);
        // Or a partitioned pool blocks the test rather than the engine.
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        return new HikariDataSource(config);
    }
}
