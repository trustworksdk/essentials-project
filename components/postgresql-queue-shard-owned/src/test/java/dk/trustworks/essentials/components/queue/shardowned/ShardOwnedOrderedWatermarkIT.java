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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ordered lane's cursor is a <em>safe watermark</em>: it does not step over a sequence value while
 * a write transaction that could still commit that value is running.
 * <p>
 * The mechanism it replaced chased each stepped-over value and wrote it off after {@code holeExpiry}.
 * That is a guess with a cliff on the far side of it — past the expiry the value is no longer chased
 * at all, and the only thing that still finds it is the head sweep, whose interval backs off to
 * {@code maxSweepInterval} on a quiet shard. These tests construct exactly that: an enqueue
 * transaction held open longer than {@code holeExpiry}, with the sweep set far enough out that it
 * cannot rescue the result and disguise a failure as a slow success.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedOrderedWatermarkIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    /**
     * Deliberately hostile, and every value here is load-bearing.
     * <p>
     * {@code holeExpiry} of 200 ms is far shorter than the transaction the test holds open — under the
     * mechanism this replaced, that is long enough for the value to be written off and never looked at
     * again. Both sweep intervals are 20 s, so the backstop cannot quietly cover for that and let a
     * failure read as a slow success. {@code watermarkCap} is 30 s, comfortably longer than the test,
     * so the watermark has to be right rather than rescued by its own escape hatch.
     */
    private static final ShardOwnerSettings HOSTILE = new ShardOwnerSettings(500,
                                                                             200,
                                                                             Duration.ofMillis(1),
                                                                             Duration.ofMillis(2),
                                                                             Duration.ofMillis(200),   // holeExpiry
                                                                             Duration.ofSeconds(20),   // sweepInterval
                                                                             1_000,
                                                                             8,
                                                                             Duration.ofMillis(500),
                                                                             Duration.ofSeconds(20),   // maxSweepInterval
                                                                             2,
                                                                             Duration.ofSeconds(5),
                                                                             Duration.ofSeconds(30),
                                                                             Duration.ofSeconds(30));  // watermarkCap

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

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

    /**
     * The hazard, constructed rather than hoped for: a message whose enqueue transaction is still open
     * while a LATER message on the same shard commits and is delivered. The cursor has every
     * opportunity to step over the open one, and must not take it.
     */
    @Test
    void a_value_whose_transaction_is_still_open_is_not_stepped_over() throws Exception {
        var key      = "key-held";
        var shard    = ShardOwnedSchema.shardForKey(key, SHARD_COUNT);
        var received = new CopyOnWriteArrayList<String>();
        var storage  = new ShardOwnedStorage(dataSource, QUEUE_ID);

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((k, payload, payloadType) ->
                                                received.add(new String(payload, StandardCharsets.UTF_8)),
                                        HOSTILE,
                                        SHARD_COUNT);

            try (var held = dataSource.getConnection()) {
                // The outbox shape: the enqueue runs inside the caller's transaction, so the sequence
                // value is allocated now and the row becomes visible only at the caller's commit.
                held.setAutoCommit(false);
                storage.enqueueOrderedBatch(held, shard,
                                            List.of(new OrderedPayload(key, 0, "held".getBytes(StandardCharsets.UTF_8), 1)));

                // A later value on the same shard, on its own connection so it commits while the held
                // one does not. The held value is now a gap the cursor is being invited to pass.
                try (var committing = dataSource.getConnection()) {
                    storage.enqueueOrderedBatch(committing, shard,
                                                List.of(new OrderedPayload(key, 1, "after".getBytes(StandardCharsets.UTF_8), 1)));
                }

                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .until(() -> received.contains("after"));

                // Keep the shard BUSY while the transaction is held. Without this the owner simply
                // parks — it has nothing to do — and wakes only when the held transaction commits, at
                // which point every mechanism looks correct because the value is already visible by
                // the time anything re-examines the gap. A first version of this test made exactly
                // that mistake and passed against a deliberately broken cap. The traffic forces the
                // owner to keep re-deciding whether it may step over the gap, which is the decision
                // under test.
                var others = keysOnShard(shard, 15);
                for (var index = 0; index < others.size(); index++) {
                    try (var busy = dataSource.getConnection()) {
                        storage.enqueueOrderedBatch(busy, shard,
                                                    List.of(new OrderedPayload(others.get(index), 0,
                                                                               ("busy-" + index).getBytes(StandardCharsets.UTF_8), 1)));
                    }
                    Thread.sleep(100);
                }

                // Well past holeExpiry (200 ms), and the shard has been polled throughout.
                assertThat(received).as("the held message cannot be delivered before it is committed")
                                    .doesNotContain("held");

                held.commit();
            }

            // No sweep can rescue this: sweepInterval and maxSweepInterval are both 20 s, so a pass
            // here means the cursor read found it, not the backstop.
            Awaitility.await().atMost(Duration.ofSeconds(8))
                      .untilAsserted(() -> assertThat(received)
                              .as("the value must be delivered once its transaction commits, from the "
                                  + "cursor read rather than from the head sweep")
                              .contains("held"));

            var metrics = queue.metrics().snapshot();
            assertThat((Long) metrics.get("watermarkAdvances"))
                    .as("the watermark must actually have moved, or this test proves nothing: %s", metrics)
                    .isPositive();
            assertThat((Long) metrics.get("holesAbandoned"))
                    .as("the ordered lane no longer writes values off on a timer: %s", metrics)
                    .isZero();
            // The discriminating assertion. Delivery alone does not distinguish a correct watermark
            // from a cursor that stepped over the value and left the head sweep to clean up — and the
            // sweep is exactly what the mechanism this replaced fell back on. Insist the CURSOR found
            // it.
            assertThat((Long) metrics.get("sweepRecoveries"))
                    .as("the value must be found by the cursor read, not recovered by the head "
                        + "sweep: %s", metrics)
                    .isZero();
            assertThat((Long) metrics.get("watermarkCapped"))
                    .as("the horizon, not the wall-clock cap, must be what held the cursor back: %s", metrics)
                    .isZero();
        }
    }

    /**
     * The everyday case must not pay for the hazard case. With no transaction held open the watermark
     * keeps up, so nothing is re-read for long and no message waits on the horizon.
     */
    @Test
    void ordinary_traffic_is_delivered_without_the_watermark_falling_behind() throws Exception {
        var received = new ConcurrentHashMap<String, List<Long>>();
        var keyCount = 20;
        var perKey   = 25;

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((key, payload, payloadType) -> received
                                                .computeIfAbsent(key, ignored -> Collections.synchronizedList(new ArrayList<>()))
                                                .add(Long.parseLong(new String(payload, StandardCharsets.UTF_8))),
                                        ShardOwnerSettings.defaults(),
                                        SHARD_COUNT);

            var messages = new ArrayList<OrderedPayload>();
            for (var keyIndex = 0; keyIndex < keyCount; keyIndex++) {
                for (var order = 0; order < perKey; order++) {
                    messages.add(new OrderedPayload("key-" + keyIndex, order,
                                                    Long.toString(order).getBytes(StandardCharsets.UTF_8), 1));
                }
            }
            queue.enqueueOrdered(messages);

            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(received.values().stream().mapToInt(List::size).sum())
                              .isEqualTo(keyCount * perKey));
            received.forEach((key, orders) -> assertThat(List.copyOf(orders))
                    .as("key %s must be delivered in key_order", key).isSorted());

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.orderedRemaining()).isZero());

            var metrics = queue.metrics().snapshot();
            // The re-read window is what the watermark costs. Measured at 8-20 rows against a 1 200/s
            // outbox workload; a batch enqueued in one transaction should sit far below that, and a
            // window that grows without bound would mean the horizon is never retiring anything.
            assertThat((Integer) metrics.get("maxWatermarkLagRows"))
                    .as("the re-read window must stay small: %s", metrics)
                    .isLessThan(keyCount * perKey);
            assertThat((Long) metrics.get("watermarkCapped"))
                    .as("no advance should need the wall-clock cap when nothing holds a transaction "
                        + "open: %s", metrics)
                    .isZero();
        }
    }

    /** Keys that hash to a given shard, so the traffic lands where the held value is. */
    private static List<String> keysOnShard(int shard, int count) {
        var keys = new ArrayList<String>(count);
        for (var candidate = 0; keys.size() < count; candidate++) {
            var key = "busy-key-" + candidate;
            if (ShardOwnedSchema.shardForKey(key, SHARD_COUNT) == shard) {
                keys.add(key);
            }
        }
        return keys;
    }
}
