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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 2 local hand-off (§4.5): when the enqueuing JVM owns the target shard, the message is never
 * read back. The row is stamped with the owner's fence — invisible to that owner's own cursor read —
 * and handed over in memory once the insert commits.
 * <p>
 * The risk this carries is double delivery, so that is what gets tested hardest, alongside the
 * recovery path that makes the stamp safe to use at all.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedLocalHandoffIT {

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
        config.setMaximumPoolSize(40);
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
    void an_owned_shard_delivers_without_reading_the_message_back() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(), SHARD_COUNT);

            for (var batch = 0; batch < 10; batch++) {
                var payloads = new ArrayList<byte[]>();
                for (var index = 0; index < 50; index++) {
                    payloads.add(("m-" + batch + "-" + index).getBytes(StandardCharsets.UTF_8));
                }
                queue.enqueue(payloads, 1);
            }

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).hasSize(500));

            // The property that matters: every message arrived exactly once, despite being both
            // written to the table and handed over in memory.
            assertThat(new HashSet<>(received))
                    .as("a locally handed-off message must not also be delivered by the read path")
                    .hasSize(500);

            var metrics = queue.metrics().snapshot();
            assertThat((Long) metrics.get("localHandoffs"))
                    .as("every message went to an owned shard, so all of them should have been handed off: %s", metrics)
                    .isEqualTo(500L);

            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
        }
    }

    /**
     * The stamp has to be self-expiring. A message pre-claimed by an owner that then dies is
     * invisible to <em>that</em> fence — so if the exclusion were not fence-scoped, it would be
     * invisible forever. A new owner takes the shard under a new fence and must see it.
     */
    @Test
    void a_message_pre_claimed_by_a_dead_owner_is_delivered_by_the_next_one() throws Exception {
        var firstReceived = new CopyOnWriteArrayList<String>();
        var first = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1");
        var shortLease = new ShardOwnerSettings(500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
                                                Duration.ofMillis(300), Duration.ofMillis(100),
                                                1_000, 8, Duration.ofMillis(50), Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofMillis(1000), Duration.ofSeconds(60));
        // A handler that never returns: messages are handed off and dispatched, never acknowledged.
        first.startConsuming((payload, payloadType) -> {
            firstReceived.add(new String(payload, StandardCharsets.UTF_8));
            try {
                Thread.sleep(Duration.ofMinutes(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, shortLease, SHARD_COUNT);

        var payloads = new ArrayList<byte[]>();
        for (var index = 0; index < 100; index++) {
            payloads.add(("m-" + index).getBytes(StandardCharsets.UTF_8));
        }
        first.enqueue(payloads, 1);

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !firstReceived.isEmpty());
        first.stopAbruptly();
        Thread.sleep(1_200L);

        var secondReceived = new CopyOnWriteArrayList<String>();
        try (var second = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-2")) {
            second.startConsuming((payload, payloadType) -> secondReceived.add(new String(payload, StandardCharsets.UTF_8)),
                                  ShardOwnerSettings.defaults(), SHARD_COUNT);

            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(second.remaining()).isZero());

            var union = new HashSet<>(firstReceived);
            union.addAll(secondReceived);
            assertThat(union)
                    .as("a pre-claimed message whose owner died must not stay invisible")
                    .hasSize(100);
            assertThat(secondReceived)
                    .as("the new owner must actually have had work to do")
                    .isNotEmpty();
        } finally {
            first.close();
        }
    }
}
