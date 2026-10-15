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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pump that dies on a critical error hands its shards back to the cluster, through its queues' heartbeat.
 * <p>
 * They used to stay leased to this instance - an instance-owned lease has no expiry, and the instance kept heartbeating -
 * and were served by nobody until the {@code ShardRuntime} or the application was restarted, which is worse than a crash:
 * a crashed instance's shards are taken over a lease lifetime later. Now the pump ends its owners as it dies and the
 * queue releases their leases: an unordered shard at once, an ordered unit only once no key is in one of its handlers,
 * so no successor starts a key beside it. A re-acquired shard lands on a live pump; with none left the instance acquires
 * nothing, and its shards stay free for the cluster rather than bouncing between it and its dead pump.
 * <p>
 * The pump is killed the way a critical error kills it: an owner it serves throws a {@code LinkageError}, which the
 * per-owner guards rethrow. That owner belongs to no queue here, so the real queue's owners are only its bystanders - the
 * shape of a death the queue did not cause and cannot see coming.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedDeadPumpHandBackIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;
    /**
     * Not {@link #QUEUE_ID}: the owner that kills the pump is no shard of the queue under test.
     */
    private static final short KILLER_QUEUE_ID = 99;

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
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
    void an_unordered_shard_on_a_dead_pump_is_handed_back_and_served_again_on_a_live_pump() throws Exception {
        var settings  = settings(2);
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-u");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                     settings, SHARD_COUNT);
                assertThat(queue.shardsHeld()).as("premise: one instance holds every shard").isEqualTo(SHARD_COUNT);
                var doomed       = runtime.pumpFor(QUEUE_ID, "unordered", 0);
                var doomedShards = unitsOn(runtime, doomed, "unordered", SHARD_COUNT);
                var fencesBefore = fences("unordered", doomedShards);

                kill(doomed);

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var shard : doomedShards) {
                        assertThat(fenceOf("unordered", shard)).as("shard %d was handed back and taken again under a new fence", shard)
                                                               .isGreaterThan(fencesBefore.get(shard));
                        assertThat(ownerOf("unordered", shard)).isEqualTo("dead-pump-u");
                    }
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                    assertThat(queue.shardsHeld()).isEqualTo(SHARD_COUNT);
                });
                for (var shard : doomedShards) {
                    assertThat(runtime.pumpFor(QUEUE_ID, "unordered", shard).isDead()).as("re-acquired on a live pump").isFalse();
                }

                var payloads = new ArrayList<byte[]>();
                for (var index = 0; index < SHARD_COUNT * 2; index++) {
                    payloads.add(("m" + index).getBytes(StandardCharsets.UTF_8));
                }
                queue.enqueue(payloads, 1);
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                        () -> assertThat(delivered).as("every shard delivers again, the dead pump's included").hasSize(SHARD_COUNT * 2));
            } finally {
                queue.stop();
            }
        }
    }

    /**
     * The ordered lane never hands a unit over with a key in a handler, a dead pump's included: the handlers run on the
     * runtime's shared executor, not on the pump, so one may outlive it. The unit is kept until that handler returns, and
     * handed back then; the dead pump's other units are handed back at once.
     */
    @Test
    void an_ordered_unit_on_a_dead_pump_is_handed_back_only_once_no_key_is_in_its_handler() throws Exception {
        var settings  = settings(2);
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var heldUnit  = new CompletableFuture<Integer>();
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-o");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    var text = new String(payload, StandardCharsets.UTF_8);
                    if ("HOLD-0".equals(text)) {
                        heldUnit.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    }
                    delivered.add(text);
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unit         = heldUnit.get();
                var doomed       = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var doomedUnits  = unitsOn(runtime, doomed, "ordered", ShardOwnedSchema.ORDERED_UNITS);
                var idleUnits    = doomedUnits.stream().filter(candidate -> candidate != unit).toList();
                var fencesBefore = fences("ordered", doomedUnits);
                assertThat(idleUnits).as("premise: the dead pump serves other units too").isNotEmpty();

                kill(doomed);

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var idle : idleUnits) {
                        assertThat(fenceOf("ordered", idle)).as("idle unit %d was handed back and taken again", idle)
                                                            .isGreaterThan(fencesBefore.get(idle));
                    }
                });
                // Several heartbeats (a third of the 1 s lease each) with the key still in its handler.
                Thread.sleep(1_500);
                assertThat(fenceOf("ordered", unit)).as("the unit with a key in its handler is not handed over").isEqualTo(fencesBefore.get(unit));
                assertThat(ownerOf("ordered", unit)).isEqualTo("dead-pump-o");
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("it is still on the dead pump").isEqualTo(1);

                release.countDown();

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(fenceOf("ordered", unit)).as("handed back once the handler returned").isGreaterThan(fencesBefore.get(unit));
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
                assertThat(runtime.pumpFor(QUEUE_ID, "ordered", unit).isDead()).isFalse();
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 1, "HOLD-1".getBytes(StandardCharsets.UTF_8), 1)));
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                        () -> assertThat(delivered).as("the key is served again on a live pump").contains("HOLD-1"));
            } finally {
                release.countDown();
                queue.stop();
            }
        }
    }

    /**
     * With no live pump left the shards are handed back and left free, not taken again: a shard taken now would land on
     * the dead pump, be ended and be handed back on every heartbeat. Each lane has its own acquire loop and its own guard.
     */
    @ParameterizedTest
    @ValueSource(strings = {"unordered", "ordered"})
    void with_no_live_pump_left_the_shards_are_handed_back_and_not_taken_again(String lane) throws Exception {
        var settings = settings(1);
        var units    = "ordered".equals(lane) ? ShardOwnedSchema.ORDERED_UNITS : SHARD_COUNT;
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-all");
            try {
                if ("ordered".equals(lane)) {
                    queue.startConsumingOrdered((messageId, key, payload, payloadType) -> { }, settings, SHARD_COUNT);
                } else {
                    queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                }
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(queue.shardsHeld()).as("premise").isEqualTo(units));
                var acquiredBefore = queue.metrics().shardsAcquired.sum();

                kill(runtime.pumpFor(QUEUE_ID, lane, 0));

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(unitsOwnedBy(lane, "dead-pump-all")).as("every lease was handed back").isZero();
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
                // Several heartbeats, each of which would have taken them again.
                Thread.sleep(1_500);
                assertThat(unitsOwnedBy(lane, "dead-pump-all")).as("nothing is taken back onto a dead pump").isZero();
                assertThat(queue.metrics().shardsAcquired.sum()).isEqualTo(acquiredBefore);
                assertThat(queue.shardsHeld()).isZero();
            } finally {
                queue.stop();
            }
        }
    }

    /**
     * A hand-back whose release fails leaves the dead pump's units held, and the next heartbeat retries it: none is taken
     * again meanwhile, by the acquire loop on a live pump, not even once its key has left the handler.
     * <p>
     * The owner was ended before the release, and the ordered acquire loop counted only owners whose lease was held, so a
     * failed release made the unit look free to the same tick's rebalance; {@code acquireLeaseGrant} accepts a unit this
     * instance already owns and took it again on a live pump. The next hand-back then released that new owner's row, the
     * lease check marked the new owner taken while its handlers could still run a key, and the unit was taken a third
     * time: the same key in two handlers. The release is made to fail by a trigger, so it fails the way a database error
     * would, every time, until the trigger is dropped.
     */
    @Test
    void an_ordered_unit_whose_hand_back_fails_is_not_taken_again_until_its_release_succeeds() throws Exception {
        var settings  = settings(2);
        var entered   = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var heldUnit  = new CompletableFuture<Integer>();
        var delivered = new CopyOnWriteArrayList<String>();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-fail");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    var text = new String(payload, StandardCharsets.UTF_8);
                    if ("HOLD-0".equals(text)) {
                        heldUnit.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    }
                    delivered.add(text);
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unit         = heldUnit.get();
                var doomed       = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var doomedUnits  = unitsOn(runtime, doomed, "ordered", ShardOwnedSchema.ORDERED_UNITS);
                var fencesBefore = fences("ordered", doomedUnits);
                assertThat(doomedUnits.size()).as("premise: the dead pump serves other units too").isGreaterThan(1);

                failOrderedLeaseReleases(true);
                try {
                    kill(doomed);

                    // Several heartbeats (a third of the 1 s lease each), every hand-back failing.
                    Thread.sleep(1_500);
                    assertNoneTakenAgain(queue, doomedUnits, fencesBefore, "while the key is in its handler");
                    assertThat(queue.metrics().shardsReleased.sum()).as("premise: no hand-back has succeeded").isZero();

                    release.countDown();
                    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> delivered.contains("HOLD-0"));
                    // The held unit is now ready to be handed back too, and its release fails like the others'.
                    Thread.sleep(1_500);
                    assertNoneTakenAgain(queue, doomedUnits, fencesBefore, "once the key has left its handler");
                } finally {
                    failOrderedLeaseReleases(false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var doomedUnit : doomedUnits) {
                        assertThat(fenceOf("ordered", doomedUnit)).as("unit %d handed back and taken again exactly once", doomedUnit)
                                                                  .isEqualTo(fencesBefore.get(doomedUnit) + 1);
                        assertThat(ownerOf("ordered", doomedUnit)).isEqualTo("dead-pump-fail");
                    }
                    assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
                });
                assertThat(runtime.pumpFor(QUEUE_ID, "ordered", unit).isDead()).isFalse();
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 1, "HOLD-1".getBytes(StandardCharsets.UTF_8), 1)));
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                        () -> assertThat(delivered).as("the key is served again on a live pump").contains("HOLD-1"));
            } finally {
                release.countDown();
                failOrderedLeaseReleases(false);
                queue.stop();
            }
        }
    }

    /**
     * A queue stopped while an ordered unit on a dead pump still has a key in its handler keeps that unit, as any stop
     * does, and hands back the rest - and a runtime that outlives the queue stops counting all of them in
     * {@code shardsOnDeadPumps}. The heartbeat never hands that unit back, so the stop is the only thing that uncounts it.
     */
    @Test
    void a_queue_stopped_with_a_key_in_a_dead_pumps_handler_keeps_that_unit_and_stops_counting_it() throws Exception {
        var settings = settings(2, Duration.ofMillis(300), Duration.ofSeconds(1));
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        var heldUnit = new CompletableFuture<Integer>();
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-stop-o");
            try {
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    if ("HOLD-0".equals(new String(payload, StandardCharsets.UTF_8))) {
                        heldUnit.complete(messageId.shard());
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, settings, SHARD_COUNT);
                queue.enqueueOrdered(List.of(new ShardOwnedStorage.OrderedPayload("HOLD", 0, "HOLD-0".getBytes(StandardCharsets.UTF_8), 1)));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unit         = heldUnit.get();
                var doomed       = runtime.pumpFor(QUEUE_ID, "ordered", unit);
                var doomedUnits  = unitsOn(runtime, doomed, "ordered", ShardOwnedSchema.ORDERED_UNITS);
                var idleUnits    = doomedUnits.stream().filter(candidate -> candidate != unit).toList();
                var fencesBefore = fences("ordered", doomedUnits);
                assertThat(idleUnits).as("premise: the dead pump serves other units too").isNotEmpty();

                kill(doomed);
                Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    for (var idle : idleUnits) {
                        assertThat(fenceOf("ordered", idle)).isGreaterThan(fencesBefore.get(idle));
                    }
                });
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("premise: the busy unit is still on the dead pump").isEqualTo(1);

                queue.stop();

                assertThat(runtime.isStarted()).as("premise: the runtime outlives the queue").isTrue();
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("the stopped queue counts nothing on the dead pump").isZero();
                for (var idle : idleUnits) {
                    assertThat(ownerOf("ordered", idle)).as("idle unit %d, re-taken on a live pump, released on stop", idle).isNull();
                }
                assertThat(ownerOf("ordered", unit)).as("the unit with a key in its handler is kept, not handed over mid-key")
                                                    .isEqualTo("dead-pump-stop-o");
                assertThat(fenceOf("ordered", unit)).isEqualTo(fencesBefore.get(unit));
            } finally {
                release.countDown();
                queue.stop();
            }
        }
    }

    /**
     * A queue stopped right after a pump died, before a heartbeat could hand its unordered shards back, releases them
     * itself: their owners' leases ended in memory at the death, so only the dead-pump check puts them among the leases
     * the stop gives back. A long lease keeps the heartbeat (a third of it) from running first.
     */
    @Test
    void a_queue_stopped_before_its_heartbeat_hands_back_a_dead_pumps_shards_releases_them_itself() throws Exception {
        var settings = settings(2, ShardOwnerSettings.defaults().shedGrace(), Duration.ofSeconds(60));
        try (var runtime = new ShardRuntime(dataSource, settings)) {
            var queue = queue(runtime, "dead-pump-stop-u");
            try {
                queue.startConsuming((messageId, payload, payloadType) -> { }, settings, SHARD_COUNT);
                assertThat(queue.shardsHeld()).as("premise: one instance holds every shard").isEqualTo(SHARD_COUNT);
                var doomed       = runtime.pumpFor(QUEUE_ID, "unordered", 0);
                var doomedShards = unitsOn(runtime, doomed, "unordered", SHARD_COUNT);
                var releasedBefore = queue.metrics().shardsReleased.sum();

                kill(doomed);
                assertThat(queue.metrics().shardsOnDeadPumps.get()).as("premise: on the dead pump").isEqualTo(doomedShards.size());
                assertThat(queue.metrics().shardsReleased.sum()).as("premise: no heartbeat has handed them back").isEqualTo(releasedBefore);
                for (var shard : doomedShards) {
                    assertThat(ownerOf("unordered", shard)).as("premise: shard %d still leased", shard).isEqualTo("dead-pump-stop-u");
                }

                queue.stop();

                assertThat(runtime.isStarted()).as("premise: the runtime outlives the queue").isTrue();
                assertThat(unitsOwnedBy("unordered", "dead-pump-stop-u")).as("every lease, the dead pump's included, released").isZero();
                assertThat(queue.metrics().shardsOnDeadPumps.get()).isZero();
            } finally {
                queue.stop();
            }
        }
    }

    private ShardOwnedQueue queue(ShardRuntime runtime, String instanceId) {
        return ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(QUEUE_ID).setShardCount(SHARD_COUNT)
                              .setInstanceId(instanceId).setRuntime(runtime).build();
    }

    /**
     * A one-second lease, so the heartbeat that hands units back runs every third of a second.
     */
    private static ShardOwnerSettings settings(int pumpThreads) {
        return settings(pumpThreads, ShardOwnerSettings.defaults().shedGrace(), Duration.ofSeconds(1));
    }

    private static ShardOwnerSettings settings(int pumpThreads, Duration shedGrace, Duration leaseTtl) {
        var d = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(d.readBatchSize(), d.ackBatchSize(), d.ackFlushInterval(), d.chaseDelay(), d.holeExpiry(),
                                      d.sweepInterval(), d.maxHolesPerChase(), d.keyConcurrency(), d.pollBackstop(),
                                      d.maxSweepInterval(), pumpThreads, shedGrace, leaseTtl, d.watermarkCap());
    }

    /**
     * Make every release of an ordered lease row fail, as a database error would, or stop doing so. A trigger rather than a
     * revoked grant: the test's role owns the table, and an owner keeps every privilege.
     */
    private void failOrderedLeaseReleases(boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_ordered_lease_release() RETURNS trigger AS $$ BEGIN "
                                          + "IF NEW.lane = 'ordered' AND NEW.owner IS NULL AND OLD.owner IS NOT NULL THEN "
                                          + "RAISE EXCEPTION 'simulated failure releasing ordered unit %', OLD.shard; END IF; "
                                          + "RETURN NEW; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_ordered_lease_release BEFORE UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_ordered_lease_release()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_ordered_lease_release ON " + ShardOwnedSchema.LEASE_TABLE);
            }
        }
    }

    /**
     * Every unit of the dead pump still leased to the instance under the fence it had at the death, and still counted on
     * the dead pump: its hand-back has failed (the injection worked), and nothing took it again.
     */
    private void assertNoneTakenAgain(ShardOwnedQueue queue, List<Integer> units, Map<Integer, Long> fencesBefore, String when) throws Exception {
        for (var unit : units) {
            assertThat(ownerOf("ordered", unit)).as("unit %d still leased %s: its release failed", unit, when).isEqualTo("dead-pump-fail");
            assertThat(fenceOf("ordered", unit)).as("unit %d not taken again %s", unit, when).isEqualTo(fencesBefore.get(unit));
        }
        assertThat(queue.metrics().shardsOnDeadPumps.get()).as("still on the dead pump %s", when).isEqualTo(units.size());
    }

    /**
     * End {@code pump}'s thread the way a critical error does: an owner it serves throws a {@code LinkageError}.
     */
    private static void kill(ShardPump pump) {
        pump.add(new DyingOwner());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(pump::isDead);
    }

    private static List<Integer> unitsOn(ShardRuntime runtime, ShardPump pump, String lane, int units) {
        var on = new ArrayList<Integer>();
        for (var unit = 0; unit < units; unit++) {
            if (runtime.pumpFor(QUEUE_ID, lane, unit) == pump) {
                on.add(unit);
            }
        }
        return on;
    }

    private Map<Integer, Long> fences(String lane, List<Integer> units) throws Exception {
        var fences = new HashMap<Integer, Long>();
        for (var unit : units) {
            fences.put(unit, fenceOf(lane, unit));
        }
        return fences;
    }

    private long fenceOf(String lane, int shard) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT fence FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND shard = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private String ownerOf(String lane, int shard) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT owner FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND shard = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private int unitsOwnedBy(String lane, String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = ? AND owner = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, lane);
            statement.setString(3, instanceId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Taken over, then throws a critical error the first time the pump asks whether it needs attention.
     */
    private static final class DyingOwner implements LeasedOwner {
        private final ShardOwnerMetrics metrics = new ShardOwnerMetrics();

        @Override
        public int pumpOnce(Connection connection) {
            return 0;
        }

        @Override
        public boolean needsAttention() {
            throw new LinkageError("simulated critical error on the pump thread");
        }

        @Override
        public void onTakeover(Connection connection) {
        }

        @Override
        public void flushOnStop(Connection connection) {
        }

        @Override
        public long parkDeadlineMillis() {
            return Long.MAX_VALUE;
        }

        @Override
        public int shard() {
            return 0;
        }

        @Override
        public short queueId() {
            return KILLER_QUEUE_ID;
        }

        @Override
        public ShardOwnerMetrics ownerMetrics() {
            return metrics;
        }

        @Override
        public long fence() {
            return 1;
        }

        @Override
        public String lane() {
            return "unordered";
        }

        @Override
        public boolean leaseHeld() {
            return true;
        }

        @Override
        public void beginDraining() {
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void onLeaseEnded(LeaseEnd reason) {
        }
    }
}
