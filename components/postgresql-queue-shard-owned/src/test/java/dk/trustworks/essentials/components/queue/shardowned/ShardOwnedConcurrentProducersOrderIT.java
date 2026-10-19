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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-key order under the shape the trading demo's ledger load produces, which surfaced out-of-order delivery there
 * (examples/essentials-trading-demo, {@code _demo_harness/CLAUDE.md}, "Parallel producers"):
 * <ul>
 *     <li>several producer threads enqueue concurrently, each owning a disjoint set of keys;</li>
 *     <li>each producer numbers a key's {@code key_order} itself and commits its batches strictly one after another,
 *     so per key, numbering order and commit order agree — the producer side of the contract is kept;</li>
 *     <li>a batch is one transaction spanning many ordered units (the {@code Inbox}-in-a-unit-of-work shape), not one
 *     auto-committed statement per unit;</li>
 *     <li>two instances consume.</li>
 * </ul>
 * In the demo, every run with more than one producer logged "key 'X' is being delivered key_order N after M was already
 * delivered", and a 200 000-message run lost a cycle's outcome to it.
 * <p>
 * The cause was the watermark retiring a candidate against the horizon probe taken AFTER a pass's read: a writer that
 * committed between that read and that probe was missing from the probe, so its rows - invisible to the read - were
 * stepped over and reached the owner only through the head sweep, behind later rows of the same key. Producer
 * concurrency, not consumer parallelism, is what opens the window (measured: 1 producer, 0 of 5 runs affected; 8, 4 of 5).
 * <p>
 * Two assertions, because the second alone is luck-dependent: a stepped-over row only shows as a violation when its key
 * has a later row delivered first. The first is {@code sweepRecoveriesBehindCursor}, the rows the head sweep found at
 * or below an owner's watermark: with no delayed message, no failing or interrupted handler and no wall-clock cap firing, as here, it
 * is zero unless the watermark stepped over a row. Not {@code sweepRecoveries}: that also counts a row a producer
 * committed between a pass's cursor read and its sweep, which nothing stepped over, and with eight producers
 * committing throughout it can be non-zero on a correct engine.
 * <p>
 * Delivery is at-least-once, and a unit that changes hands mid-flight may legitimately redeliver: the test asserts every
 * message delivered, redeliveries within a small bound, and key order over each message's first delivery only.
 * <p>
 * A load suite: each repetition enqueues and drains {@code KEYS * PER_KEY} (40 000) messages. <b>On by default</b>, it
 * asserts behaviour under volume; turn it off for a faster inner loop with {@code -Dloadtest.skip=true}. The
 * deterministic pin of the same rule, {@code ShardOwnedOrderedWatermarkIT}, is not gated and keeps running.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisabledIfSystemProperty(named = "loadtest.skip", matches = "true")
class ShardOwnedConcurrentProducersOrderIT {
    private static final String QUEUE_NAME    = "concurrent-producers";
    private static final int    SHARD_COUNT   = 4;
    private static final int    ORDERED_UNITS = 64;
    private static final int    KEYS          = 1_000;
    private static final int    PER_KEY       = 40;
    private static final int    PRODUCERS     = Integer.getInteger("repro.producers", 8);
    private static final int    BATCH         = 500;
    private static final int    PARALLEL_CONSUMERS = Integer.getInteger("repro.parallelConsumers", 8);

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;
    private short            queueId;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);

        ShardOwnedSchema.recreate(dataSource);
        queueId = ShardOwnedSchema.registerQueue(dataSource, QueueName.of(QUEUE_NAME), SHARD_COUNT, ORDERED_UNITS).queueId();
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @RepeatedTest(3)
    void every_key_is_delivered_in_key_order_when_several_producers_enqueue_disjoint_keys_concurrently() throws Exception {
        var highestDelivered = new ConcurrentHashMap<String, Long>();
        var violations       = new ConcurrentLinkedQueue<String>();
        var duplicates       = new ConcurrentLinkedQueue<String>();
        var seen             = ConcurrentHashMap.<String>newKeySet();
        var sweepRecoveries             = 0L;
        var sweepRecoveriesBehindCursor = 0L;
        KeyOrderedHandler handler = (key, order) -> {
            if (!seen.add(key + "#" + order)) {
                // A redelivery, which at-least-once allows. Not an order check: the key's later orders may rightly
                // have been delivered since the first time, and that is not a reordering.
                duplicates.add(key + "#" + order);
            } else {
                // Against the highest order delivered for the key so far, so a first delivery below it is a violation
                // whatever was redelivered in between.
                highestDelivered.compute(key, (ignored, highest) -> {
                    if (highest != null && highest > order) {
                        violations.add(key + ": " + order + " after " + highest);
                        return highest;
                    }
                    return order;
                });
            }
            try {
                Thread.sleep(1); // a handler that does some work, as the demo's (~2 ms) did
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Built exactly as PostgresqlMessageQueue.consume builds its ordered consumer for the DurableQueues adapter, with
        // the parallel consumers the demo's inbox asked for: several handlers per instance working the ordered units
        try (var first = orderedConsumer("producers-a", handler);
             var second = orderedConsumer("producers-b", handler)) {

            produceConcurrently();

            Awaitility.await().atMost(Duration.ofMinutes(5))
                      .until(() -> seen.size() >= KEYS * PER_KEY);
            Thread.sleep(2_000); // let any late redelivery show itself
            // The engine's own count of rows its watermark had passed when the head sweep found them
            for (var queue : List.of(first, second)) {
                var metrics = queue.metrics().snapshot();
                sweepRecoveries += (Long) metrics.get("sweepRecoveries");
                sweepRecoveriesBehindCursor += (Long) metrics.get("sweepRecoveriesBehindCursor");
            }
        }

        assertThat(sweepRecoveriesBehindCursor)
                .as("rows the cursor stepped over and the head sweep recovered late - the cause of out-of-order delivery, "
                    + "whether or not it showed this time (all sweep recoveries, including rows committed between a "
                    + "pass's cursor read and its sweep: %d)", sweepRecoveries)
                .isZero();
        assertThat(violations).as("first deliveries out of key_order (%d of them)", violations.size()).isEmpty();
        assertThat(seen).as("every message delivered").hasSize(KEYS * PER_KEY);
        // Redelivery is legal when a unit changes hands mid-flight (the 32-unit rebalance to producers-b runs while
        // messages flow), and is confined to what was in a handler or awaiting its acknowledgement flush at that
        // moment. Ownership that stopped being exclusive would redeliver far more than 1% of the messages.
        assertThat(duplicates).as("messages delivered more than once, within the at-least-once overlap of a hand-over")
                              .hasSizeLessThan(KEYS * PER_KEY / 100);
    }

    @FunctionalInterface
    private interface KeyOrderedHandler {
        void handle(String key, long keyOrder);
    }

    private ShardOwnedQueue orderedConsumer(String instanceId, KeyOrderedHandler handler) throws Exception {
        var queue = ShardOwnedQueue.builder()
                                   .setDataSource(dataSource)
                                   .setQueueId(queueId)
                                   .setShardCount(SHARD_COUNT)
                                   .setInstanceId(instanceId)
                                   .setParallelConsumers(PARALLEL_CONSUMERS)
                                   .build();
        queue.configureOrdered(new OrderedPayloadHandler() {
                                   @Override
                                   public void handle(MessageId messageId, String key, byte[] payload, int payloadType) {
                                       throw new IllegalStateException("The ordered owner delivers with the key order");
                                   }

                                   @Override
                                   public void handle(MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
                                       handler.handle(key, keyOrder);
                                   }
                               },
                               ShardOwnerSettings.defaults(), ORDERED_UNITS, RedeliveryPolicy.fixed(Duration.ofMillis(50), 3));
        queue.start();
        return queue;
    }

    /**
     * Each producer owns the keys {@code key % PRODUCERS == producer}, shuffles their messages into an interleaved
     * stream (a key's own messages stay in order), and commits batches of {@link #BATCH} one after another, each batch
     * one transaction across whatever units its keys hash to.
     */
    private void produceConcurrently() throws Exception {
        var pool = Executors.newFixedThreadPool(PRODUCERS);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var producer = 0; producer < PRODUCERS; producer++) {
                var owned = producer;
                futures.add(pool.submit(() -> {
                    produce(owned);
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(5, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void produce(int producer) throws Exception {
        var random  = new Random(producer);
        var keys    = new ArrayList<String>();
        for (var k = producer; k < KEYS; k += PRODUCERS) {
            keys.add("key-" + k);
        }
        var next    = new HashMap<String, Long>();
        var stream  = new ArrayList<String>();
        for (var i = 0; i < keys.size() * PER_KEY; i++) {
            String key;
            do {
                key = keys.get(random.nextInt(keys.size()));
            } while (next.getOrDefault(key, 0L) >= PER_KEY);
            next.merge(key, 1L, Long::sum);
            stream.add(key);
        }
        var numbered = new HashMap<String, Long>();
        var storage  = new ShardOwnedStorage(dataSource, queueId);
        for (var from = 0; from < stream.size(); from += BATCH) {
            var byUnit = new TreeMap<Integer, List<OrderedPayload>>();
            for (var i = from; i < Math.min(stream.size(), from + BATCH); i++) {
                var key   = stream.get(i);
                var order = numbered.merge(key, 1L, Long::sum) - 1;
                byUnit.computeIfAbsent(ShardOwnedSchema.unitForKey(key, ORDERED_UNITS), unit -> new ArrayList<>())
                      .add(new OrderedPayload(key, order, String.valueOf(order).getBytes(StandardCharsets.UTF_8), 1));
            }
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                for (var entry : byUnit.entrySet()) {
                    storage.enqueueOrderedBatch(connection, entry.getKey(), entry.getValue());
                }
                connection.commit();
            }
        }
    }
}
