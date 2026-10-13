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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a new owner of an unordered shard does with the shard's history.
 * <ul>
 *     <li>A new owner's cursor started at 0, so its first read recorded every sequence value below the shard's first
 *     remaining row - every message the shard had ever delivered and deleted - as a hole, and chased them all for
 *     {@code holeExpiry}. The cost grew with the shard's history on every restart, deploy and rebalance.</li>
 *     <li>What the holes were protecting must still hold: a message whose sequence value was allocated before the
 *     takeover but committed after it is delivered, exactly once, and no acknowledgement deletes it first.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedTakeoverHistoryIT {
    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 1;
    private static final int   HISTORY     = 5_000;

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
    void a_new_owner_does_not_chase_the_shards_history_as_holes() throws Exception {
        consumeHistory();

        var received = new CopyOnWriteArrayList<String>();
        try (var successor = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "successor");
             var producer = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "producer")) {
            successor.startConsuming((messageId, payload, payloadType) -> received.add(text(payload)), ShardOwnerSettings.defaults(), SHARD_COUNT);
            producer.enqueue(List.of(bytes("after-takeover")), 1);
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(received).containsExactly("after-takeover"));
            Thread.sleep(500);

            var metrics = successor.metrics().snapshot();
            assertThat((Long) metrics.get("holesObserved")).as("holes recorded by the new owner: %s", metrics).isLessThan(10L);
            assertThat((Long) metrics.get("holeChaseQueries")).as("hole chase queries: %s", metrics).isLessThan(10L);
        }
    }

    @Test
    void a_message_allocated_before_the_takeover_and_committed_after_it_is_delivered_once() throws Exception {
        consumeHistory();
        var storage = new ShardOwnedStorage(dataSource, QUEUE_ID);

        // A producer that has allocated its sequence value but not committed when the shard changes hands
        try (var lateProducer = dataSource.getConnection()) {
            lateProducer.setAutoCommit(false);
            storage.enqueueBatch(lateProducer, 0, List.of(bytes("late")), 1);

            var received = new CopyOnWriteArrayList<String>();
            try (var successor = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "successor")) {
                successor.startConsuming((messageId, payload, payloadType) -> received.add(text(payload)), ShardOwnerSettings.defaults(), SHARD_COUNT);
                // Committed after the late one was allocated, so above it, and handled and acknowledged - by range
                // delete where contiguous - while the late one is still uncommitted below them
                for (var batch = 0; batch < 5; batch++) {
                    try (var connection = dataSource.getConnection()) {
                        var messages = new ArrayList<byte[]>();
                        for (var index = 0; index < 20; index++) {
                            messages.add(bytes("after-" + batch + "-" + index));
                        }
                        storage.enqueueBatch(connection, 0, messages, 1);
                    }
                    Thread.sleep(200);
                }
                Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> received.size() == 100);
                Thread.sleep(1_000);

                lateProducer.commit();
                // Traffic right after the commit: it is handled and acknowledged by range within milliseconds, well
                // before the head sweep reaches the late message. A range delete reaching below the takeover cursor
                // would delete the late message, now committed, before it was ever delivered
                try (var connection = dataSource.getConnection()) {
                    var messages = new ArrayList<byte[]>();
                    for (var index = 0; index < 20; index++) {
                        messages.add(bytes("after-commit-" + index));
                    }
                    storage.enqueueBatch(connection, 0, messages, 1);
                }

                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(received).contains("late"));
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> successor.remaining() == 0);
                Thread.sleep(2_000);
                assertThat(received.stream().filter("late"::equals).count()).as("delivered exactly once").isEqualTo(1L);
                assertThat(received).hasSize(121);
            }
        }
    }

    /**
     * A first owner delivers and acknowledges a history of messages, then hands the shard back cleanly
     */
    private void consumeHistory() throws Exception {
        var handled = new CopyOnWriteArrayList<String>();
        try (var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "first")) {
            first.startConsuming((messageId, payload, payloadType) -> handled.add(text(payload)), ShardOwnerSettings.defaults(), SHARD_COUNT);
            for (var offset = 0; offset < HISTORY; offset += 500) {
                var payloads = new ArrayList<byte[]>(500);
                for (var index = offset; index < offset + 500; index++) {
                    payloads.add(bytes("history-" + index));
                }
                first.enqueue(payloads, 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(first.remaining()).isZero());
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
