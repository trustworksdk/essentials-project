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

// Single-type imports, not the package: docker-java also exports a Container, which collides with
// the JUnit extension's @Container below.
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.zaxxer.hikari.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database going away and coming back with its disk intact.
 *
 * <h2>What this covers that nothing else did</h2>
 * Three neighbouring failures already have tests, and none of them is this one:
 * <ul>
 *   <li>{@code ShardOwnedConnectionLossIT} terminates the backends. The <b>server keeps running</b>;
 *       only the sockets go.</li>
 *   <li>{@code ShardOwnedNetworkPartitionIT} cuts one instance off. The server keeps running and
 *       keeps serving <em>somebody</em>.</li>
 *   <li>{@code ShardOwnedMultiProcessIT} kills and freezes application processes. The database is
 *       never the thing that fails.</li>
 * </ul>
 * A restart is the one where the server itself stops: every connection dies at once, nothing is
 * served for a few seconds, and what comes back has the committed rows and none of the session
 * state. It is a routine event — a minor-version upgrade, a failover, an operator bouncing a
 * container — and the engine keeps state in exactly two places, memory and committed rows, so this
 * is the test that says the second half is enough.
 *
 * <h2>Why the host port is pinned</h2>
 * {@code docker restart} re-allocates a dynamically published port (measured: 37576 becomes 37577),
 * so an engine holding a pool would be unable to reconnect for a reason that has nothing to do with
 * the engine. Pinning one free port keeps the address stable across the restart, which is what a
 * deployment has anyway — a service name, or a pooler's address, not a port that moves.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedDatabaseRestartIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    /**
     * Chosen before the container starts and bound explicitly, so the restart keeps it. The window
     * between closing the probe socket and Docker binding the port is a race in theory; in practice
     * the port is one the OS has just handed out and nothing else is asking for.
     */
    private static final int HOST_PORT = freePort();

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create()
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                                                      .withPortBindings(new PortBinding(Ports.Binding.bindPort(HOST_PORT),
                                                                                        ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT))));

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = pool();
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
    void the_engine_resumes_and_loses_nothing_when_the_database_restarts() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        // Held across the outage so that the messages enqueued just before it are still there when
        // the server goes down. Without it they are delivered in well under a millisecond — the
        // enqueuing JVM owns the shard, so the local hand-off reaches the handler directly — and the
        // durability half of this test would assert nothing.
        var heldUntilAfterTheRestart = new CountDownLatch(1);

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1", new ShardOwnerMetrics())) {
            queue.startConsuming((payload, type) -> {
                                     var message = new String(payload, StandardCharsets.UTF_8);
                                     if (message.startsWith("during-")) {
                                         await(heldUntilAfterTheRestart);
                                     }
                                     received.add(message);
                                 },
                                 shortLease(), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.shardsHeld())
                              .as("the instance must own its shards before the database is taken away")
                              .isEqualTo(SHARD_COUNT));

            for (var index = 0; index < 25; index++) {
                queue.enqueue(List.of(("before-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(distinctWithPrefix(received, "before-")).isEqualTo(25L));

            // Enqueued and committed, then the server goes down immediately. Some of these will have
            // been delivered and some will not, and which is which is deliberately not controlled:
            // both sides of that line have to survive, and only one of them is about memory.
            for (var index = 0; index < 25; index++) {
                queue.enqueue(List.of(("during-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }

            // Assert the outage happened rather than assuming it. A restart that silently did not
            // take would leave every assertion below passing against a server that never went away,
            // and this test would then be an expensive way of enqueuing 75 messages.
            var startedBefore = postmasterStartTime();
            restartDatabase();
            assertThat(postmasterStartTime())
                    .as("the server must actually have restarted")
                    .isAfter(startedBefore);
            assertThat(distinctWithPrefix(received, "during-"))
                    .as("nothing enqueued into the outage may have been handled before it")
                    .isZero();
            heldUntilAfterTheRestart.countDown();

            // Committed rows survive; the owner reconnects and keeps reading where its cursor was.
            Awaitility.await().atMost(Duration.ofSeconds(90))
                      .untilAsserted(() -> assertThat(distinctWithPrefix(received, "during-"))
                              .as("a message committed before the restart must still be delivered after it")
                              .isEqualTo(25L));

            // And the engine is working, not merely finished: a shard that came back unserved would
            // pass the assertion above on what it had already read.
            for (var index = 0; index < 25; index++) {
                queue.enqueue(List.of(("after-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(90))
                      .untilAsserted(() -> assertThat(distinctWithPrefix(received, "after-"))
                              .as("every shard must accept and deliver new work once the database is back")
                              .isEqualTo(25L));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
            assertThat(queue.shardsHeld())
                    .as("the instance must still hold its shards - nothing else was competing for them")
                    .isEqualTo(SHARD_COUNT);
        }
    }

    /**
     * Stops and starts the container, which is the server dying with its storage intact — the data
     * directory is the container's own filesystem and a restart does not touch it.
     */
    private void restartDatabase() {
        postgres.getDockerClient().restartContainerCmd(postgres.getContainerId()).exec();
        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .pollInterval(Duration.ofMillis(500))
                  .until(this::accepting);
    }

    /**
     * Probes on a connection of its own rather than through the pool, so that waiting for the server
     * never depends on the pool the engine is also trying to recover.
     */
    private boolean accepting() {
        try (var connection = DriverManager.getConnection(jdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Bounded, so that a handler left waiting by a bug fails the test rather than hanging the suite.
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(90, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The delivery gate was never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding a delivery", e);
        }
    }

    /**
     * When this server process started. The one fact that distinguishes a restart that happened from
     * one that did not, and it survives nothing — which is the point.
     */
    private OffsetDateTime postmasterStartTime() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT pg_postmaster_start_time()")) {
            resultSet.next();
            return resultSet.getObject(1, OffsetDateTime.class);
        }
    }

    private static long distinctWithPrefix(List<String> received, String prefix) {
        return received.stream().filter(message -> message.startsWith(prefix)).distinct().count();
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + HOST_PORT + "/" + postgres.getDatabaseName();
    }

    private static HikariDataSource pool() {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(30);
        // Or the pool blocks the test for the length of the outage rather than failing and retrying,
        // which is also what a deployment wants: the engine's own retry is the thing under test.
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        return new HikariDataSource(config);
    }

    /**
     * A short lease TTL so that anything the restart makes stale expires inside the test rather than
     * at the default bound.
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

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not find a free port to pin the database to", e);
        }
    }
}
