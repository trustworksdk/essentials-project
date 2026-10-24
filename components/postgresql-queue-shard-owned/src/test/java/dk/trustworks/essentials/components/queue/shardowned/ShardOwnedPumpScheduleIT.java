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
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedStorage.*;
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
 * The pump visits only the owners that signalled or whose scheduled time has come ({@link LeasedOwner#nextAttentionNanos()}),
 * with a full scan of every owner only every {@code pollBackstop}. These tests set {@code pollBackstop} - and so the
 * full scan - and the head sweep to a minute, and then need every case that does not depend on the sweep to arrive
 * within seconds: a notification, a finished handler, a retry falling due, an acknowledgement to flush. A case the
 * signals and the schedule miss would wait a minute and fail the test on its timeout; {@code attentionFoundOnlyByFullScan}
 * must stay at zero either way. Verified failing with the retry case removed from both owners' {@code nextAttentionNanos}.
 * <p>
 * Delayed messages are not here: an owner learns when a delayed row becomes visible only from the head sweep, in this
 * design as before it, so with the sweep off nothing delivers them. {@code ShardOwnedDelayedDeliveryIT} holds that
 * path, with the sweep on, to "not before its delay and not long after".
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedPumpScheduleIT {

    private static final int      SHARD_COUNT = 2;
    private static final int      QUEUES      = 3;
    private static final Duration NO_SCAN     = Duration.ofMinutes(1);

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

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
        for (short queueId = 1; queueId <= QUEUES; queueId++) {
            ShardOwnedSchema.registerQueue(dataSource, queueId, SHARD_COUNT);
        }
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void ordered_queues_deliver_and_retry_without_the_full_scan_or_the_sweep() throws Exception {
        var settings  = withoutFullScan();
        var runtime   = new ShardRuntime(dataSource, settings);
        var queues    = new ArrayList<ShardOwnedQueue>();
        var delivered = new ConcurrentHashMap<String, Long>();
        var failedOnce = ConcurrentHashMap.<String>newKeySet();
        var outOfOrder = new ConcurrentLinkedQueue<String>();
        try {
            for (short queueId = 1; queueId <= QUEUES; queueId++) {
                var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(queueId).setShardCount(SHARD_COUNT)
                                           .setInstanceId("schedule-o-" + queueId).setRuntime(runtime).build();
                var prefix = queueId + ":";
                queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                    var body = prefix + new String(payload, StandardCharsets.UTF_8);
                    // Every "retry" message fails its first delivery, so it comes back through the retry schedule
                    if (body.contains("retry") && failedOnce.add(body)) {
                        throw new IllegalStateException("first delivery fails: " + body);
                    }
                    var previous = delivered.putIfAbsent(body, System.nanoTime());
                    if (previous == null && body.endsWith("#1") && !delivered.containsKey(body.substring(0, body.length() - 1) + "0")) {
                        outOfOrder.add(body);
                    }
                }, settings, ShardOwnedSchema.ORDERED_UNITS, RedeliveryPolicy.fixed(Duration.ofMillis(300), 5));
                queues.add(queue);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .until(() -> queues.stream().mapToInt(ShardOwnedQueue::shardsHeld).sum() == QUEUES * ShardOwnedSchema.ORDERED_UNITS);
            // Past the start-up, so what follows is served by signals and the schedule alone
            Thread.sleep(1_500);

            var expected = new HashSet<String>();
            for (var queue : queues) {
                var prefix   = queue.storage().queueId() + ":";
                var messages = new ArrayList<OrderedPayload>();
                for (var key = 0; key < 20; key++) {
                    messages.add(payload("now-" + key + "#0", "now-" + key, 0, Duration.ZERO));
                    messages.add(payload("now-" + key + "#1", "now-" + key, 1, Duration.ZERO));
                    messages.add(payload("retry-" + key + "#0", "retry-" + key, 0, Duration.ZERO));
                    messages.add(payload("retry-" + key + "#1", "retry-" + key, 1, Duration.ZERO));
                }
                queue.enqueueOrdered(messages);
                messages.forEach(message -> expected.add(prefix + new String(message.payload(), StandardCharsets.UTF_8)));
            }

            Awaitility.await().atMost(Duration.ofSeconds(15))
                      .untilAsserted(() -> assertThat(delivered.keySet()).containsExactlyInAnyOrderElementsOf(expected));
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(queues).allSatisfy(queue -> assertThat(queue.orderedRemaining()).as("acknowledged").isZero()));
            assertThat(outOfOrder).as("a key's second message before its first").isEmpty();
            assertThat(queues).allSatisfy(queue -> assertThat(queue.metrics().attentionFoundOnlyByFullScan.sum()).isZero());
        } finally {
            queues.forEach(ShardOwnedQueue::close);
            runtime.close();
        }
    }

    @Test
    void unordered_queues_deliver_and_retry_without_the_full_scan_or_the_sweep() throws Exception {
        var settings  = withoutFullScan();
        var runtime   = new ShardRuntime(dataSource, settings);
        var queues    = new ArrayList<ShardOwnedQueue>();
        var delivered = ConcurrentHashMap.<String>newKeySet();
        var failedOnce = ConcurrentHashMap.<String>newKeySet();
        try {
            for (short queueId = 1; queueId <= QUEUES; queueId++) {
                var queue = ShardOwnedQueue.builder().setDataSource(dataSource).setQueueId(queueId).setShardCount(SHARD_COUNT)
                                           .setInstanceId("schedule-u-" + queueId).setRuntime(runtime).build();
                var prefix = queueId + ":";
                queue.startConsuming((messageId, payload, payloadType) -> {
                    var body = prefix + new String(payload, StandardCharsets.UTF_8);
                    if (body.contains("retry") && failedOnce.add(body)) {
                        throw new IllegalStateException("first delivery fails: " + body);
                    }
                    delivered.add(body);
                }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(300), 5));
                queues.add(queue);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .until(() -> queues.stream().mapToInt(ShardOwnedQueue::shardsHeld).sum() == QUEUES * SHARD_COUNT);
            Thread.sleep(1_500);

            var expected = new HashSet<String>();
            for (var queue : queues) {
                var prefix = queue.storage().queueId() + ":";
                var now    = new ArrayList<byte[]>();
                for (var i = 0; i < 20; i++) {
                    now.add(("now-" + i).getBytes(StandardCharsets.UTF_8));
                    now.add(("retry-" + i).getBytes(StandardCharsets.UTF_8));
                    expected.add(prefix + "now-" + i);
                    expected.add(prefix + "retry-" + i);
                }
                queue.enqueue(now, 1);
            }

            Awaitility.await().atMost(Duration.ofSeconds(15))
                      .untilAsserted(() -> assertThat(delivered).containsExactlyInAnyOrderElementsOf(expected));
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(queues).allSatisfy(queue -> assertThat(queue.remaining()).as("acknowledged").isZero()));
            assertThat(queues).allSatisfy(queue -> assertThat(queue.metrics().attentionFoundOnlyByFullScan.sum()).isZero());
        } finally {
            queues.forEach(ShardOwnedQueue::close);
            runtime.close();
        }
    }

    private static OrderedPayload payload(String body, String key, long keyOrder, Duration delay) {
        return new OrderedPayload(key, keyOrder, body.getBytes(StandardCharsets.UTF_8), 1, delay);
    }

    /**
     * {@code pollBackstop} - the full scan's interval - and the head sweep at a minute; everything else default. The
     * sweep has to go too: it reads failed rows whose retry is due, so it covers for a retry case the schedule misses
     * (it did, in the first version of this test).
     */
    private static ShardOwnerSettings withoutFullScan() {
        var d = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(d.readBatchSize(), d.ackBatchSize(), d.ackFlushInterval(), d.chaseDelay(),
                                      d.holeExpiry(), NO_SCAN, d.maxHolesPerChase(), d.keyConcurrency(),
                                      NO_SCAN, NO_SCAN, 2, d.shedGrace(), d.leaseTtl(), d.watermarkCap());
    }
}
