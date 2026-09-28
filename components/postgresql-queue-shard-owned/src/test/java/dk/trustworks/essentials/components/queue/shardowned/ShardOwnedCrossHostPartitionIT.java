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
import dk.trustworks.essentials.components.queue.shardowned.node.ShardOwnedNodeMain;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How long a partitioned node takes to find out, over a real network rather than a forwarder.
 *
 * <h2>What this measures that {@code ShardOwnedNetworkPartitionIT} cannot</h2>
 * That test asserts the <b>behaviour</b>: a partitioned owner loses its units and is fenced out when
 * it returns. It does it with {@link PartitionableProxy}, an in-JVM loopback forwarder that stops
 * reading, and for the property being asserted that is the right tool — correctness there comes from
 * the fence, which is server-side and does not know what the network looked like.
 * <p>
 * What a forwarder cannot reproduce is <b>timing</b>. Stalling by TCP backpressure blocks the sender
 * in a write; dropping packets at the IP layer leaves it retransmitting, which is what a real
 * partition does and what decides how long a node keeps acting on beliefs that are no longer true.
 * Both instances also sharing one JVM means they share a heap, a garbage collector and a clock — the
 * one thing a partition is supposed to separate.
 * <p>
 * So here the cut-off node is a JVM in its own container, reached over a Docker network, and the
 * partition is {@code docker network disconnect}: its packets stop being routed while every socket
 * stays open and nothing is told anything. It connects by <b>IP rather than by alias</b> on purpose —
 * with a name, the disconnect also breaks DNS and a new connection fails fast on an unknown host,
 * which is not what a partition does to a deployment that reached its database by address.
 *
 * <h2>Why it is measurement-gated</h2>
 * It reports numbers and asserts only what it can assert honestly: that a survivor's takeover does not
 * depend on the cut-off node noticing anything, and that {@code socketTimeout} is what decides when
 * that node finds out. Those are properties an operator tunes against, not a regression gate, so this
 * runs under {@code -Dbenchmark.run=true} and not in the normal build.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedCrossHostPartitionIT {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedCrossHostPartitionIT.class);

    private static final short  QUEUE_ID      = 1;
    private static final int    SHARD_COUNT   = 4;
    private static final String NODE_IMAGE    = "eclipse-temurin:25-jre";
    /**
     * Long enough that the answer is "it had not noticed yet" rather than "the test gave up too
     * early", short enough to stay a test. The no-timeout arm is bounded by this rather than measured
     * to completion: an untimed socket retransmits for minutes, and the point is made once the number
     * is an order of magnitude apart.
     */
    private static final Duration OBSERVATION_WINDOW = Duration.ofSeconds(90);

    private Network          network;
    private PostgreSQLContainer<?> postgres;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        network = Network.newNetwork();
        postgres = LabPostgres.create().withNetwork(network).withNetworkAliases("db");
        postgres.start();

        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);

        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS shard_queue_observed ("
                              + "id bigserial PRIMARY KEY, instance_id text, msg_key text, value bigint)");
        }
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void a_socket_timeout_is_what_decides_when_a_partitioned_node_finds_out() throws Exception {
        var withTimeout    = measure("socketTimeout=3", "?socketTimeout=3&connectTimeout=3");
        var withoutTimeout = measure("no socketTimeout", "");

        log.info("""

                 Cross-host partition, {} shards, Docker network disconnect
                 | arm | survivor took over after | cut-off node noticed after |
                 |---|---|---|
                 | {} | {} ms | {} |
                 | {} | {} ms | {} |
                 """,
                 SHARD_COUNT,
                 withTimeout.arm(), withTimeout.takeoverMillis(), withTimeout.describeNotice(),
                 withoutTimeout.arm(), withoutTimeout.takeoverMillis(), withoutTimeout.describeNotice());

        assertThat(withTimeout.noticedMillis())
                .as("with a socketTimeout the cut-off node must find out, and soon")
                .isNotNull();

        assertThat(withTimeout.noticedMillis())
                .as("and it must find out sooner than a node without one")
                .isLessThan(withoutTimeout.noticedMillis() == null ? OBSERVATION_WINDOW.toMillis()
                                                                   : withoutTimeout.noticedMillis());

        // The property the numbers exist to put in proportion: what the survivor does is bounded by
        // the staleness of the cut-off node's membership row, and owes nothing to that node noticing.
        assertThat(withTimeout.takeoverMillis()).isLessThan(OBSERVATION_WINDOW.toMillis());
        assertThat(withoutTimeout.takeoverMillis()).isLessThan(OBSERVATION_WINDOW.toMillis());
    }

    /**
     * One arm: start a node in its own container, let it take the shards, cut it off at the network,
     * and time both sides of what follows.
     */
    private Arm measure(String arm, String urlSuffix) throws Exception {
        var databaseIp = databaseIpOn(network);
        var jdbcUrl = "jdbc:postgresql://" + databaseIp + ":" + PostgreSQLContainer.POSTGRESQL_PORT
                      + "/" + postgres.getDatabaseName() + urlSuffix;

        try (var node = new GenericContainer<>(NODE_IMAGE)
                .withNetwork(network)
                .withFileSystemBind("/workspace", "/workspace", BindMode.READ_ONLY)
                .withFileSystemBind(System.getProperty("user.home") + "/.m2",
                                    System.getProperty("user.home") + "/.m2", BindMode.READ_ONLY)
                .withCommand("java", "-cp", System.getProperty("java.class.path"),
                             ShardOwnedNodeMain.class.getName(),
                             jdbcUrl, postgres.getUsername(), postgres.getPassword(),
                             "cut-off", Integer.toString(SHARD_COUNT), Integer.toString(SHARD_COUNT),
                             "unordered")
                .waitingFor(Wait.forLogMessage(".*READY cut-off.*", 1)
                                .withStartupTimeout(Duration.ofMinutes(3)))) {
            node.start();

            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(shardsHeldBy("cut-off"))
                              .as("the node must own the queue before it is cut off")
                              .isEqualTo(SHARD_COUNT));

            var received = new CopyOnWriteArrayList<String>();
            try (var survivor = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "survivor", new ShardOwnerMetrics())) {
                var logsBefore = node.getLogs().length();

                // The partition. Routing stops; every socket stays open and nobody is told.
                var cutOffAt = System.nanoTime();
                postgres.getDockerClient().disconnectFromNetworkCmd()
                        .withContainerId(node.getContainerId())
                        .withNetworkId(network.getId())
                        .exec();

                survivor.startConsuming((messageId, payload, type) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                        shortLease(), SHARD_COUNT);

                Long noticedMillis = null;
                Long takeoverMillis = null;
                var deadline = System.nanoTime() + OBSERVATION_WINDOW.toNanos();
                while (System.nanoTime() < deadline && (noticedMillis == null || takeoverMillis == null)) {
                    if (takeoverMillis == null && shardsHeldBy("survivor") == SHARD_COUNT) {
                        takeoverMillis = millisSince(cutOffAt);
                    }
                    if (noticedMillis == null && noticedItLostTheDatabase(node.getLogs(), logsBefore)) {
                        noticedMillis = millisSince(cutOffAt);
                    }
                    Thread.sleep(250);
                }

                assertThat(takeoverMillis)
                        .as("the survivor must take the units over whatever the cut-off node believes")
                        .isNotNull();
                return new Arm(arm, takeoverMillis, noticedMillis);
            }
        } finally {
            // Both arms start from an unowned queue, or the second one measures a takeover that has
            // already happened.
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("UPDATE shard_queue_lease SET owner = NULL, fence = fence + 1");
                statement.execute("DELETE FROM shard_queue_instance");
            }
        }
    }

    /**
     * The container's address on this network, not its alias.
     * <p>
     * Disconnecting a container also removes its name from the network's DNS, so a node that reached
     * the database by alias fails its next connection immediately with an unknown host — fast, and
     * nothing like a partition. By address there is no name to lose: the packets simply stop
     * arriving, which is the case being measured.
     */
    private String databaseIpOn(Network target) {
        var networks = postgres.getCurrentContainerInfo().getNetworkSettings().getNetworks();
        return networks.values().stream()
                       .map(settings -> settings.getIpAddress())
                       .filter(address -> address != null && !address.isBlank())
                       .findFirst()
                       .orElseThrow(() -> new IllegalStateException("The database has no address on " + target.getId()));
    }

    /**
     * The first thing the engine says when its database stops answering. Any of these means the node
     * has learned something is wrong; which one arrives first depends on which call was in flight.
     */
    private static boolean noticedItLostTheDatabase(String logs, int from) {
        var since = logs.length() <= from ? "" : logs.substring(from);
        return since.contains("Lease check failed")
               || since.contains("Rebalance failed")
               || since.contains("Instance heartbeat failed")
               || since.contains("Could not re-read the shard count");
    }

    private int shardsHeldBy(String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM shard_queue_lease WHERE lane = 'unordered' AND owner = ?")) {
            statement.setString(1, instanceId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

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

    private record Arm(String arm, Long takeoverMillis, Long noticedMillis) {
        String describeNotice() {
            return noticedMillis == null ? "not within " + OBSERVATION_WINDOW.toSeconds() + " s"
                                         : noticedMillis + " ms";
        }
    }
}
