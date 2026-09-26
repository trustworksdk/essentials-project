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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An instance that cannot vouch for itself stops dispatching, and starts again when it can.
 *
 * <h2>The window this closes</h2>
 * A unit's liveness is its instance's row in {@code shard_queue_instance}, and everybody else's fair
 * share treats a row older than the lease as dead. So one lease TTL after an instance last managed to
 * write that row, its units are already takeable — and nothing has happened <em>inside that JVM</em>
 * to say so. It still holds its owners, still has whatever it read, and goes on delivering.
 * <p>
 * Correctness was never the problem: the successor takes the unit under a new fence and the old
 * owner's acknowledgements are refused. The problem is that everything it delivers in that window is
 * work its successor is doing too, and the window is not short — a partitioned node without a
 * {@code socketTimeout} was measured not noticing for ninety seconds, and a disk slow enough to push
 * commits past the lease puts every instance on that database here at once.
 * <p>
 * So an owner now asks whether its instance has confirmed liveness inside the lease before it
 * dispatches. This is not a fence and does not replace one; it stops an instance from spending the
 * window acting on a belief it has no way to check.
 *
 * <h2>How the belief is made stale</h2>
 * {@link PartitionableProxy} again, because the point is an instance that is alive and cannot reach
 * the database — a `SIGSTOP` would stop it doing anything at all, and killing the backends lets it
 * reconnect immediately.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedStaleLivenessIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private PartitionableProxy proxy;
    private HikariDataSource   partitionable;
    private HikariDataSource   direct;

    @BeforeEach
    void setUp() throws Exception {
        proxy = new PartitionableProxy(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
        direct = pool(postgres.getJdbcUrl());
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
    void an_instance_that_cannot_confirm_its_liveness_pauses_delivery_and_resumes_on_its_own() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var metrics  = new ShardOwnerMetrics();

        try (var queue = new ShardOwnedQueue(partitionable, QUEUE_ID, SHARD_COUNT, "instance-1", metrics)) {
            queue.startConsuming((messageId, payload, type) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 shortLease(), SHARD_COUNT);

            enqueue(direct, "before");
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).contains("before"));

            proxy.partition();

            // One lease TTL of silence is the whole condition. Past it, the cluster considers this
            // instance dead, so it must stop behaving as though it were not.
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(metrics.deliveryPauses.sum())
                              .as("an instance that has not confirmed its liveness within the lease must pause")
                              .isEqualTo(1L));

            // Pausing is not giving up. The units stay held, because releasing them needs the very
            // database this instance cannot reach — and a successor takes them on the lease instead.
            assertThat(queue.shardsHeld())
                    .as("a paused instance keeps its units; it is the fence that decides who may write")
                    .isEqualTo(SHARD_COUNT);

            proxy.heal();

            // Recovery is unattended: the next successful heartbeat is the whole resume condition.
            enqueue(direct, "after");
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(received)
                              .as("delivery must resume once the instance can confirm itself again")
                              .contains("after"));

            assertThat(metrics.deliveryPauses.sum())
                    .as("and it must not be flapping in and out of the pause")
                    .isEqualTo(1L);
        }
    }

    private static void enqueue(HikariDataSource dataSource, String payload) throws Exception {
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "producer", new ShardOwnerMetrics())) {
            queue.enqueue(List.of(payload.getBytes(StandardCharsets.UTF_8)), 1);
        }
    }

    /** Short enough that the staleness bound elapses inside a test. */
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
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        return new HikariDataSource(config);
    }
}
