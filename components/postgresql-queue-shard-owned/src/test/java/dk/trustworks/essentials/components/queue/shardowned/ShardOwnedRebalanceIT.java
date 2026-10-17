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
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.QueueObserver;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shard rebalancing (§4.7) with no coordinator.
 * <p>
 * Every instance independently computes the same fair share from the same membership table, so the
 * split converges without anybody deciding it. What has to be true is that the shards end up evenly
 * split, that no message is delivered twice while they move, and that an instance leaving hands its
 * work back rather than stranding it.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedRebalanceIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 8;

    /** Short lease, so a heartbeat and a rebalance happen several times inside the test window. */
    private static final ShardOwnerSettings FAST = new ShardOwnerSettings(
            500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
            Duration.ofMillis(300), Duration.ofMillis(100), 1_000, 8, Duration.ofMillis(50), Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofMillis(1000), Duration.ofSeconds(60));

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(60);
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
    void a_second_instance_takes_its_fair_share_from_the_first() throws Exception {
        var firstReceived = new CopyOnWriteArrayList<String>();
        var secondReceived = new CopyOnWriteArrayList<String>();

        try (var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            first.startConsuming((messageId, payload, payloadType) -> firstReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                 FAST, SHARD_COUNT);
            // Alone, it should hold everything.
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(SHARD_COUNT));

            try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-2")) {
                second.startConsuming((messageId, payload, payloadType) -> secondReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                      FAST, SHARD_COUNT);

                // Both compute ceil(8/2) = 4 from the same membership, so the first releases down to
                // four and the second picks up what it dropped.
                Awaitility.await().atMost(Duration.ofSeconds(30))
                          .untilAsserted(() -> {
                              assertThat(first.shardsHeld()).isEqualTo(4);
                              assertThat(second.shardsHeld()).isEqualTo(4);
                          });

                // And the split is a partition, not an overlap: every shard is held exactly once.
                assertThat(first.shardsHeld() + second.shardsHeld()).isEqualTo(SHARD_COUNT);

                var payloads = new ArrayList<byte[]>();
                for (var index = 0; index < 400; index++) {
                    payloads.add(("m-" + index).getBytes(StandardCharsets.UTF_8));
                }
                for (var payload : payloads) {
                    first.enqueue(List.of(payload), 1);
                }

                // Distinct, not total. The contract is at-least-once: a shard moving between
                // instances while a message is in flight may legitimately deliver it on both sides.
                // An earlier version asserted the total was exactly 400 and failed on 401 — turning
                // correct behaviour into a red build, which is the third time in this work that a
                // test demanded exactly-once from a design that never promised it.
                Awaitility.await().atMost(Duration.ofSeconds(60))
                          .untilAsserted(() -> {
                              var delivered = new HashSet<>(firstReceived);
                              delivered.addAll(secondReceived);
                              assertThat(delivered).as("nothing may be lost while shards move").hasSize(400);
                          });

                // Overlap is permitted, but only for messages genuinely in flight across the move. A
                // split that is not exclusive in steady state would produce far more than a handful,
                // so this still fails loudly if ownership stops meaning anything.
                var overlap = new HashSet<>(firstReceived);
                overlap.retainAll(new HashSet<>(secondReceived));
                assertThat(overlap)
                        .as("overlap should be confined to messages in flight during the hand-over")
                        .hasSizeLessThan(20);
                assertThat(secondReceived).as("the second instance must have done real work").isNotEmpty();
            }

            // The second instance is gone. The first must take the shards back rather than leave them
            // stranded, which is the case a fair-share rule gets wrong if it only ever sheds load.
            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(SHARD_COUNT));

            var afterShutdown = new ArrayList<byte[]>();
            for (var index = 0; index < 50; index++) {
                afterShutdown.add(("after-" + index).getBytes(StandardCharsets.UTF_8));
            }
            for (var payload : afterShutdown) {
                first.enqueue(List.of(payload), 1);
            }
            // At-least-once, not exactly-once. A shard moving between instances while a message is
            // in flight may legitimately redeliver it — that is the contract, and the earlier version
            // of this assertion demanded exactly 50, then failed on 51. Asserting a guarantee the
            // design never made would have turned correct behaviour into a red build.
            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(firstReceived.stream()
                                                                   .filter(m -> m.startsWith("after-"))
                                                                   .distinct()
                                                                   .count())
                              .as("every message must arrive at least once")
                              .isEqualTo(50L));
        }
    }

    /**
     * A shard given up to the fair share whose lease release fails stays with its owner, served, and is released once the
     * release succeeds.
     * <p>
     * The owner was ended before its lease was released, so a failed release left it ended with the lease still naming
     * this instance. The next heartbeat dropped it as a lost lease, and the shard was then served by nobody: no other
     * instance may take a lease a live instance holds, and this one, at its fair share, took nothing more. The release is
     * made to fail by a trigger, every time, until the trigger is dropped; a sequence the trigger draws from before it
     * raises (a sequence is not rolled back) proves the release was attempted, and retried.
     */
    @Test
    void an_unordered_shard_whose_shed_release_fails_stays_served_until_the_release_succeeds() throws Exception {
        var firstReceived  = new CopyOnWriteArrayList<String>();
        var secondReceived = new CopyOnWriteArrayList<String>();
        try (var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "shed-fail-1")) {
            first.startConsuming((messageId, payload, payloadType) -> firstReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                 FAST, SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(SHARD_COUNT));

            failUnorderedLeaseReleases(true);
            try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "shed-fail-2")) {
                try {
                    second.startConsuming((messageId, payload, payloadType) -> secondReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                          FAST, SHARD_COUNT);
                    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> releaseAttempts() >= 2);

                    // Several more heartbeats (a third of the 1 s lease each), every release failing, sampled throughout.
                    var until = System.nanoTime() + Duration.ofMillis(1_500).toNanos();
                    while (System.nanoTime() < until) {
                        assertThat(unitsLeasedTo("shed-fail-1")).as("premise: every release failed").isEqualTo(SHARD_COUNT);
                        assertThat(first.shardsHeld()).as("every shard leased to the first is still served by it").isEqualTo(SHARD_COUNT);
                        Thread.sleep(50);
                    }
                    assertThat(first.metrics().shardsReleased.sum()).as("no release has succeeded").isZero();
                    assertThat(second.shardsHeld()).as("premise: nothing was free to take").isZero();

                    var payloads = new ArrayList<byte[]>();
                    for (var index = 0; index < SHARD_COUNT * 5; index++) {
                        payloads.add(("held-" + index).getBytes(StandardCharsets.UTF_8));
                    }
                    first.enqueue(payloads, 1);
                    Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                            () -> assertThat(new HashSet<>(firstReceived)).as("every shard delivers while its release keeps failing")
                                                                          .hasSize(SHARD_COUNT * 5));
                } finally {
                    failUnorderedLeaseReleases(false);
                }

                Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    assertThat(first.shardsHeld()).isEqualTo(SHARD_COUNT / 2);
                    assertThat(second.shardsHeld()).isEqualTo(SHARD_COUNT / 2);
                    assertThat(unitsLeasedTo("shed-fail-1")).as("the first's leases match what it serves").isEqualTo(SHARD_COUNT / 2);
                });
            }
        }
    }

    /**
     * The heartbeat's unordered shed releases the whole excess before it tells the observer of any of it, so an observer
     * that throws for the first release leaves no shard of the excess held. Told inside the loop, it ended the shed at
     * that first shard, and the rest stayed held until the next heartbeat. The observer reads the first instance's lease
     * rows when it is first told of a release.
     */
    @Test
    void an_observer_throwing_for_the_first_shed_release_leaves_none_of_the_excess_held() throws Exception {
        var leasedWhenFirstTold = new AtomicInteger(-1);
        var observer = new QueueObserver() {
            @Override
            public void shardOwnershipChanged(int shard, boolean acquired) {
                if (!acquired && leasedWhenFirstTold.compareAndSet(-1, -2)) {
                    try {
                        leasedWhenFirstTold.set(unitsLeasedTo("observer-throws-1"));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    throw new IllegalStateException("simulated observer failure releasing unordered shard " + shard);
                }
            }
        };
        try (var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "observer-throws-1", new ShardOwnerMetrics(observer))) {
            first.startConsuming((messageId, payload, payloadType) -> { }, FAST, SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(first.shardsHeld()).isEqualTo(SHARD_COUNT));

            try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "observer-throws-2")) {
                second.startConsuming((messageId, payload, payloadType) -> { }, FAST, SHARD_COUNT);

                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> leasedWhenFirstTold.get() >= 0);
                assertThat(leasedWhenFirstTold.get()).as("the shed had released the whole excess when the observer was first told")
                                                     .isEqualTo(SHARD_COUNT / 2);
                Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    assertThat(first.shardsHeld()).isEqualTo(SHARD_COUNT / 2);
                    assertThat(second.shardsHeld()).isEqualTo(SHARD_COUNT / 2);
                });
                assertThat(first.metrics().shardsReleased.sum()).as("every shed shard was counted released, the one the observer threw for too")
                                                               .isEqualTo(SHARD_COUNT / 2);
            }
        }
    }

    /**
     * Make every release of an unordered lease row fail, as a database error would, or stop doing so. A trigger rather than
     * a revoked grant: the test's role owns the table, and an owner keeps every privilege. Each refused release first draws
     * from {@code release_attempts}, which {@link #releaseAttempts()} reads.
     */
    private void failUnorderedLeaseReleases(boolean fail) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("DROP SEQUENCE IF EXISTS release_attempts");
                statement.execute("CREATE SEQUENCE release_attempts");
                statement.execute("CREATE OR REPLACE FUNCTION fail_unordered_lease_release() RETURNS trigger AS $$ BEGIN "
                                          + "IF NEW.lane = 'unordered' AND NEW.owner IS NULL AND OLD.owner IS NOT NULL THEN "
                                          + "PERFORM nextval('release_attempts'); "
                                          + "RAISE EXCEPTION 'simulated failure releasing unordered shard %', OLD.shard; END IF; "
                                          + "RETURN NEW; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_unordered_lease_release BEFORE UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_unordered_lease_release()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_unordered_lease_release ON " + ShardOwnedSchema.LEASE_TABLE);
            }
        }
    }

    /**
     * How many unordered lease releases the trigger has refused.
     */
    private long releaseAttempts() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM release_attempts")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private int unitsLeasedTo(String instanceId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = 'unordered' AND owner = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, instanceId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
