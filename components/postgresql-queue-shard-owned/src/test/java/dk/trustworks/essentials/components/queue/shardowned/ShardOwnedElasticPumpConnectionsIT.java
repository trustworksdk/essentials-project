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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pumps beyond {@link PumpConnections#minHeld()} give their connection back to the pool after
 * once they have delivered nothing for {@link PumpConnections#releaseIdleAfter()}, and borrow one only for a pass with database work. Held connections are counted as the pool's checked-out ones: the wake-up listener holds one, each connected
 * pump one more.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedElasticPumpConnectionsIT {
    private static final int SHARD_COUNT  = 2;
    private static final int PUMP_THREADS = 2;

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource    dataSource;
    private QueueName           name;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        name = QueueName.of("elastic-pumps");
        ShardOwnedSchema.registerQueue(dataSource, name, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var closeable : closeables.reversed()) {
            closeable.close();
        }
        dataSource.close();
    }

    @Test
    void an_idle_runtime_keeps_only_the_listeners_connection_and_a_released_pump_delivers_again() throws Exception {
        var metrics   = new ShardOwnerMetrics();
        var runtime   = runtime(PumpConnections.elastic(0, Duration.ofMillis(500)), metrics);
        var queue     = queue(runtime);
        var delivered = consume(queue);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(heldConnections()).as("listener only").isEqualTo(1));
        assertThat(metrics.pumpConnectionsReleased.sum()).isPositive();

        queue.enqueue(List.of(Message.of(bytes("after-release"), 1), Message.ordered(bytes("ordered-after-release"), 1, "k", 0)));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(delivered).containsExactlyInAnyOrder("after-release", "ordered-after-release"));

        var releasedSoFar = metrics.pumpConnectionsReleased.sum();
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(heldConnections()).as("released again once idle").isEqualTo(1);
            assertThat(metrics.pumpConnectionsReleased.sum()).isGreaterThan(releasedSoFar);
        });
    }

    /**
     * Quiet is measured in deliveries, not database work. Every idle owner keeps sweeping, and with sweeps coming due
     * every 200 ms (64 ordered units and 2 shards per pump pair) no pump ever goes the release window without a
     * statement - the first version, which waited for that, never released here, as it never did in the trading demo.
     */
    @Test
    void a_pump_whose_owners_keep_sweeping_still_releases_between_sweeps() throws Exception {
        var metrics  = new ShardOwnerMetrics();
        var runtime  = ShardRuntime.builder()
                                   .setDataSource(dataSource)
                                   .setSettings(sweepingEvery200Millis())
                                   .setMetrics(metrics)
                                   .setPumpConnections(PumpConnections.elastic(0, Duration.ofSeconds(1)))
                                   .build();
        closeables.add(runtime);
        var queue = PostgresqlMessageQueue.builder()
                                          .setDataSource(dataSource)
                                          .setQueueName(name)
                                          .setInstanceId("elastic")
                                          .setSettings(sweepingEvery200Millis())
                                          .setRuntime(runtime)
                                          .build();
        closeables.add(queue);
        var delivered = consume(queue);
        queue.enqueue(List.of(Message.of(bytes("before-quiet"), 1)));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(delivered).contains("before-quiet"));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(metrics.pumpConnectionsReleased.sum()).isPositive());
        // A quiet pump borrows for a sweep and gives the connection back after it, so most samples see the listener alone
        Thread.sleep(1_500);
        var listenerOnly = 0;
        for (var sample = 0; sample < 40; sample++) {
            if (heldConnections() == 1) {
                listenerOnly++;
            }
            Thread.sleep(50);
        }
        assertThat(listenerOnly).as("samples, of 40, holding the listener's connection only").isGreaterThan(20);
    }

    @Test
    void the_held_pumps_keep_their_connection_while_idle() throws Exception {
        var runtime = runtime(PumpConnections.elastic(1, Duration.ofMillis(500)), new ShardOwnerMetrics());
        consume(queue(runtime));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(heldConnections()).as("listener + one held pump").isEqualTo(2));
        // Never fewer over a while: the held pump keeps its connection. More for a moment is fine - the other pump
        // borrows one for a sweep, the heartbeat one to renew - so the floor is what is asserted
        var fewest = Integer.MAX_VALUE;
        for (var sample = 0; sample < 40; sample++) {
            fewest = Math.min(fewest, heldConnections());
            Thread.sleep(50);
        }
        assertThat(fewest).as("fewest held over two seconds").isEqualTo(2);
    }

    @Test
    void by_default_every_pump_holds_its_connection() throws Exception {
        var metrics = new ShardOwnerMetrics();
        var runtime = runtime(PumpConnections.allHeld(), metrics);
        consume(queue(runtime));

        Thread.sleep(3_000);
        assertThat(heldConnections()).as("listener + every pump").isEqualTo(PUMP_THREADS + 1);
        assertThat(metrics.pumpConnectionsReleased.sum()).isZero();
    }

    /**
     * Released after no idle time at all, a pump borrows and releases around almost every pass. Everything still
     * arrives exactly as it would with held connections: all of it, and each key in order.
     */
    @Test
    void releasing_around_every_pass_loses_nothing_and_keeps_key_order() throws Exception {
        var metrics   = new ShardOwnerMetrics();
        var runtime   = runtime(PumpConnections.elastic(0, Duration.ZERO), metrics);
        var queue     = queue(runtime);
        var delivered = new ConcurrentHashMap<String, List<Long>>();
        var unordered = ConcurrentHashMap.<String>newKeySet();
        var subscription = queue.consume(new MessageHandler() {
            @Override
            public void handle(MessageId messageId, String key, byte[] payload, int payloadType) {
                throw new IllegalStateException("the engine calls the overload with the key order");
            }

            @Override
            public void handle(MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
                if (key == null) {
                    unordered.add(new String(payload, StandardCharsets.UTF_8));
                } else {
                    delivered.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(keyOrder);
                }
            }
        }, ConsumerOptions.defaults());
        awaitOwnership(subscription);

        var keys = 20;
        var perKey = 25;
        for (var round = 0; round < perKey; round++) {
            var batch = new ArrayList<Message>();
            for (var key = 0; key < keys; key++) {
                batch.add(Message.ordered(bytes("o"), 1, "key-" + key, round));
                batch.add(Message.of(bytes("u-" + round + "-" + key), 1));
            }
            queue.enqueue(batch);
            Thread.sleep(20);
        }

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(unordered).hasSize(keys * perKey);
            assertThat(delivered.values().stream().mapToInt(List::size).sum()).isEqualTo(keys * perKey);
        });
        var expected = new ArrayList<Long>();
        for (var order = 0L; order < perKey; order++) {
            expected.add(order);
        }
        assertThat(delivered).allSatisfy((key, orders) -> assertThat(orders).as(key).containsExactlyElementsOf(expected));
        assertThat(metrics.pumpConnectionsReleased.sum()).as("released along the way").isPositive();
    }

    private Set<String> consume(PostgresqlMessageQueue queue) throws Exception {
        var delivered    = ConcurrentHashMap.<String>newKeySet();
        var subscription = queue.consume((messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                         ConsumerOptions.defaults());
        awaitOwnership(subscription);
        return delivered;
    }

    private static void awaitOwnership(Subscription subscription) {
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .until(() -> subscription.unorderedShardsHeld() == SHARD_COUNT && subscription.orderedUnitsHeld() == ShardOwnedSchema.ORDERED_UNITS);
    }

    private int heldConnections() {
        return dataSource.getHikariPoolMXBean().getActiveConnections();
    }

    private PostgresqlMessageQueue queue(ShardRuntime runtime) {
        var queue = PostgresqlMessageQueue.builder()
                                          .setDataSource(dataSource)
                                          .setQueueName(name)
                                          .setInstanceId("elastic")
                                          .setSettings(settings())
                                          .setRuntime(runtime)
                                          .build();
        closeables.add(queue);
        return queue;
    }

    private ShardRuntime runtime(PumpConnections pumpConnections, ShardOwnerMetrics metrics) {
        var runtime = ShardRuntime.builder()
                                  .setDataSource(dataSource)
                                  .setSettings(settings())
                                  .setMetrics(metrics)
                                  .setPumpConnections(pumpConnections)
                                  .build();
        closeables.add(runtime);
        return runtime;
    }

    private static ShardOwnerSettings settings() {
        var d = ShardOwnerSettings.defaults();
        assertThat(d.pumpThreads()).isEqualTo(PUMP_THREADS);
        return d;
    }

    private static ShardOwnerSettings sweepingEvery200Millis() {
        var d = settings();
        return new ShardOwnerSettings(d.readBatchSize(), d.ackBatchSize(), d.ackFlushInterval(), d.chaseDelay(), d.holeExpiry(),
                                      Duration.ofMillis(100), d.maxHolesPerChase(), d.keyConcurrency(), d.pollBackstop(),
                                      Duration.ofMillis(200), d.pumpThreads(), d.shedGrace(), d.leaseTtl(), d.watermarkCap());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
