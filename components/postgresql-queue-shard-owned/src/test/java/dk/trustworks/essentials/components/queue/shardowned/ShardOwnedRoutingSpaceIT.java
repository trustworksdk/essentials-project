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
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedStorage.OrderedPayload;
import dk.trustworks.essentials.components.queue.shardowned.spi.QueueName;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * The two things that decide whether a FIXED routing space is a safe thing to ship.
 * <p>
 * {@link ShardOwnedSchema#ORDERED_UNITS} is frozen for the life of a queue's data, because a key's
 * unit is {@code mix(hash(key)) mod} that number. Freezing a number is only acceptable if both of its
 * failure directions are bounded:
 * <ul>
 *     <li><b>Too small.</b> More instances than units. This must DEGRADE — the extras hold nothing
 *         for that lane — and must not lose, duplicate or reorder anything.</li>
 *     <li><b>Changed.</b> A build whose default differs from the space a queue was created under.
 *         That must be a NON-EVENT: the queue keeps routing by its own recorded space, so upgrading
 *         cannot re-route live keys and cannot demand that anyone drain anything.</li>
 * </ul>
 * Neither is hypothetical: the first decides whether 64 is a ceiling anyone can hit, and the second is
 * what lets the default change in a later version without a manual migration.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedRoutingSpaceIT {

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
        config.setMaximumPoolSize(30);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * The "too small" direction, with the real numbers rather than a stand-in.
     * <p>
     * {@code maxShards} does not apply to this lane — honouring it would strand units — so the space
     * cannot be shrunk to make this cheap. One more instance than there are units is therefore
     * literally {@code ORDERED_UNITS + 1} consumers, which is the point: this is the ceiling a
     * deployment would actually hit, and what it does when it hits it.
     */
    @Test
    void more_instances_than_units_degrades_rather_than_breaks() throws Exception {
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);

        var units     = ShardOwnedSchema.ORDERED_UNITS;
        var received  = new ConcurrentHashMap<String, List<Long>>();
        var instances = new ArrayList<ShardOwnedQueue>();
        try {
            for (var index = 0; index < units + 1; index++) {
                var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "over-" + index);
                instances.add(queue);
                queue.startConsumingOrdered((key, payload, payloadType) -> received
                                                    .computeIfAbsent(key, ignored -> Collections.synchronizedList(new ArrayList<>()))
                                                    .add(Long.parseLong(new String(payload, StandardCharsets.UTF_8))),
                                            fastLease(),
                                            units);
            }

            // Wait for CONVERGENCE, not merely for every unit to be owned. The first starter takes
            // all sixty-four, so "the total is sixty-four" is true a millisecond in and says nothing
            // — an earlier version of this assertion passed in two seconds for exactly that reason,
            // with sixty-four of the sixty-five instances idle because rebalancing had not run yet.
            // fairShare is ceil(64/65) = 1, so the converged state is sixty-four holders of one unit.
            Awaitility.await().atMost(Duration.ofSeconds(120))
                      .untilAsserted(() -> {
                          var held    = instances.stream().mapToInt(ShardOwnedQueue::shardsHeld).sum();
                          var holders = instances.stream().filter(queue -> queue.shardsHeld() > 0).count();
                          assertThat(held).as("every unit must have exactly one owner").isEqualTo(units);
                          assertThat(holders)
                                  .as("the units must be SPREAD, one per instance — not all held by "
                                      + "whoever started first")
                                  .isEqualTo(units);
                      });

            var messages = new ArrayList<OrderedPayload>();
            for (var keyIndex = 0; keyIndex < 40; keyIndex++) {
                for (var order = 0; order < 10; order++) {
                    messages.add(new OrderedPayload("key-" + keyIndex, order,
                                                    Long.toString(order).getBytes(StandardCharsets.UTF_8), 1));
                }
            }
            instances.get(0).enqueueOrdered(messages);

            // Nothing is stranded: an instance holding no units is idle, not a black hole.
            Awaitility.await().atMost(Duration.ofSeconds(90))
                      .untilAsserted(() -> assertThat(received.values().stream().mapToInt(List::size).sum())
                              .isEqualTo(40 * 10));

            received.forEach((key, orders) -> {
                var snapshot = List.copyOf(orders);
                assertThat(snapshot).as("key %s must still be in key_order", key).isSorted();
                assertThat(snapshot).as("key %s must not be duplicated", key).doesNotHaveDuplicates();
            });

            assertThat(instances.stream().filter(queue -> queue.shardsHeld() == 0).count())
                    .as("the surplus instance holds nothing rather than displacing another — that is "
                        + "the entire cost of the routing space being too small")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            instances.forEach(ShardOwnedQueue::close);
        }
    }

    /** Short lease and heartbeat, so rebalancing across many instances converges inside the test. */
    private static ShardOwnerSettings fastLease() {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(),
                                      defaults.ackFlushInterval(), defaults.chaseDelay(),
                                      defaults.holeExpiry(), defaults.sweepInterval(),
                                      defaults.maxHolesPerChase(), defaults.keyConcurrency(),
                                      defaults.pollBackstop(), defaults.maxSweepInterval(),
                                      defaults.pumpThreads(), Duration.ofSeconds(2),
                                      Duration.ofSeconds(2), defaults.watermarkCap());
    }

    /**
     * The "changed" direction, and the reason it is no longer a failure at all.
     * <p>
     * An earlier revision REFUSED here, on the grounds that a build with a different
     * {@code ORDERED_UNITS} would re-route live keys. The refusal was correct about the hazard and
     * wrong as an answer: it turned a version upgrade into "drain this queue and re-create it",
     * which is the manual, outage-shaped step this engine exists not to have — the same non-procedure
     * §1 of the design rejects for {@code shardCount}.
     * <p>
     * Routing now reads the queue's OWN recorded space, so a differing build uses its default for
     * queues it creates and leaves existing ones exactly as they are. Nothing to refuse, and nothing
     * for anyone to do.
     */
    @Test
    void a_queue_keeps_the_routing_space_it_was_created_with() throws Exception {
        var name = QueueName.of("space-kept");
        var registered = ShardOwnedSchema.registerQueue(dataSource, name, SHARD_COUNT);

        // Rewrite history: this queue's data was written by a build with half the space.
        var otherSpace = ShardOwnedSchema.ORDERED_UNITS / 2;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE " + ShardOwnedSchema.REGISTRY_TABLE + " SET ordered_units = ? WHERE queue_name = ?")) {
            statement.setInt(1, otherSpace);
            statement.setString(2, name.value());
            assertThat(statement.executeUpdate()).as("the rewrite must have taken effect").isEqualTo(1);
        }

        // Re-registering under today's build must NOT refuse.
        assertThatCode(() -> ShardOwnedSchema.registerQueue(dataSource, name, SHARD_COUNT))
                .as("a differing build must not demand that the queue be drained and re-created")
                .doesNotThrowAnyException();

        // And a message must be routed by the queue's space, not by this build's default. The key is
        // chosen so the two disagree — otherwise the assertion would hold either way.
        var key = keyRoutedDifferentlyBy(otherSpace);
        try (var queue = new ShardOwnedQueue(dataSource, registered.queueId(), SHARD_COUNT, "kept-1")) {
            queue.enqueueOrdered(List.of(new OrderedPayload(key, 0,
                                                            "x".getBytes(StandardCharsets.UTF_8), 1)));
        }

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT shard FROM " + ShardOwnedSchema.ORDERED_TABLE + " WHERE queue_id = ?")) {
            statement.setShort(1, registered.queueId());
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1))
                        .as("the message must land where the queue's OWN space puts it (%d), not where "
                            + "this build's default would (%d)",
                            ShardOwnedSchema.unitForKey(key, otherSpace),
                            ShardOwnedSchema.unitForKey(key, ShardOwnedSchema.ORDERED_UNITS))
                        .isEqualTo(ShardOwnedSchema.unitForKey(key, otherSpace));
            }
        }
    }

    /** A key whose unit differs between the given space and this build's default. */
    private static String keyRoutedDifferentlyBy(int otherSpace) {
        for (var candidate = 0; candidate < 100_000; candidate++) {
            var key = "key-" + candidate;
            if (ShardOwnedSchema.unitForKey(key, otherSpace)
                != ShardOwnedSchema.unitForKey(key, ShardOwnedSchema.ORDERED_UNITS)) {
                return key;
            }
        }
        throw new IllegalStateException("no key distinguishes the two spaces");
    }

    /**
     * The ceiling is now a per-queue decision rather than a property of the build, which is what
     * stops it being absolute. A queue that will be consumed by more instances than the default
     * allows can simply be created with a larger space — and everything else keeps the default,
     * because a larger space costs per-unit state that a process running hundreds of queues should
     * not pay without reason.
     */
    @Test
    void a_queue_can_be_created_with_a_larger_routing_space() throws Exception {
        var name  = QueueName.of("space-larger");
        var wider = ShardOwnedSchema.ORDERED_UNITS * 4;
        var registered = ShardOwnedSchema.registerQueue(dataSource, name, SHARD_COUNT, wider);

        assertThat(registered.orderedUnits()).isEqualTo(wider);

        // Seeded against the queue's own space, or the extra units would have no lease row and could
        // never be owned.
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE
                     + " WHERE queue_id = ? AND lane = 'ordered'")) {
            statement.setShort(1, registered.queueId());
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1))
                        .as("every unit of the wider space needs a lease row")
                        .isEqualTo(wider);
            }
        }

        // And a key routes into the wider space, not the default one.
        var key = keyRoutedDifferentlyBy(wider);
        try (var queue = new ShardOwnedQueue(dataSource, registered.queueId(), SHARD_COUNT, "wide-1")) {
            queue.enqueueOrdered(List.of(new OrderedPayload(key, 0,
                                                            "x".getBytes(StandardCharsets.UTF_8), 1)));
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT shard FROM " + ShardOwnedSchema.ORDERED_TABLE + " WHERE queue_id = ?")) {
            statement.setShort(1, registered.queueId());
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(ShardOwnedSchema.unitForKey(key, wider));
            }
        }
    }

    @Test
    void the_routing_space_a_queue_was_created_under_is_recorded() throws Exception {
        var name = QueueName.of("space-recorded");
        var registered = ShardOwnedSchema.registerQueue(dataSource, name, SHARD_COUNT);
        assertThat(registered.orderedUnits())
                .as("recorded at creation, so a later build can tell whether it agrees")
                .isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
    }
}
