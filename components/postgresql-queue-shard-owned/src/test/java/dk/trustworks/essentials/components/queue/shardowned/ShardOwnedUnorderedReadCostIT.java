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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read statements per delivered message on the unordered lane when messages arrive one at a time across many shards.
 * <p>
 * Each unordered shard owner reads for itself, so every shard a wake-up touches costs one read statement; the ordered
 * lane batches its reads per queue. This measures what that costs under a steady trickle across 64 shards, from a
 * producer on another instance (so no local hand-off skips the read), to decide whether batching the unordered reads
 * would pay.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedUnorderedReadCostIT {
    private static final short    QUEUE_ID    = 1;
    private static final int      SHARD_COUNT = 64;
    private static final Duration DURATION    = Duration.ofSeconds(10);

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
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void read_statements_per_message_across_64_shards() throws Exception {
        var results = new ArrayList<String>();
        for (var rate : List.of(200, 2_000)) {
            results.add(measure(rate));
        }
        System.out.printf("%n=== Unordered lane, %d shards, single-message enqueues from another instance ===%n%s%n%n",
                          SHARD_COUNT, String.join("\n", results));
    }

    private String measure(int messagesPerSecond) throws Exception {
        // A fresh schema per rate: a second consumer on shards the first one drained is a takeover, and a takeover
        // records every sequence value below the shard's first remaining row as a hole - that would be measured instead
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        var latencies = new ConcurrentLinkedQueue<Long>();
        var handled   = new AtomicInteger();
        try (var consumer = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "consumer-" + messagesPerSecond);
             var producer = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "producer-" + messagesPerSecond)) {
            consumer.startConsuming((messageId, payload, payloadType) -> {
                latencies.add((System.nanoTime() - ByteBuffer.wrap(payload).getLong()) / 1_000_000);
                handled.incrementAndGet();
            }, ShardOwnerSettings.defaults(), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> consumer.shardsHeld() == SHARD_COUNT);
            Thread.sleep(1_000);

            var before   = consumer.metrics().snapshot();
            var perTick  = Math.max(1, messagesPerSecond / 100);
            var sent     = 0;
            var until    = System.nanoTime() + DURATION.toNanos();
            var next     = System.nanoTime();
            while (System.nanoTime() < until) {
                for (var i = 0; i < perTick; i++) {
                    producer.enqueue(List.of(ByteBuffer.allocate(8).putLong(System.nanoTime()).array()), 1);
                    sent++;
                }
                next += 10_000_000L;
                var sleep = next - System.nanoTime();
                if (sleep > 0) {
                    Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                }
            }
            var total = sent;
            Awaitility.await().atMost(Duration.ofMinutes(2)).until(() -> handled.get() >= total);
            var after  = consumer.metrics().snapshot();
            var reads  = (Long) after.get("cursorReads") - (Long) before.get("cursorReads");
            var counters = new StringBuilder();
            for (var counter : List.of("cursorRowsRead", "wakeupsHonoured", "headSweeps", "sweepRecoveries", "holeChaseQueries", "holesObserved", "holesResolved", "holesAbandoned", "readsSkippedByHandoff")) {
                if (after.get(counter) instanceof Long value && before.get(counter) instanceof Long previous) {
                    counters.append(String.format(", %s %,d", counter, value - previous));
                }
            }
            var sorted = latencies.stream().sorted().toList();
            return String.format("  %,5d msg/s: %,6d messages, %,6d cursor reads (%.2f per message, %.0f per second), latency p50 %d ms, p99 %d ms%s",
                                 messagesPerSecond, total, reads, (double) reads / total, reads * 1_000d / DURATION.toMillis(),
                                 sorted.get(sorted.size() / 2), sorted.get((int) (sorted.size() * 0.99)), counters);
        }
    }
}
