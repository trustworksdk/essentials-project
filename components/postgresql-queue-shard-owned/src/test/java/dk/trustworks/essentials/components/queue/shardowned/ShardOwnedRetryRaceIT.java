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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory retry schedule against the head sweep — two paths that can deliver the same message.
 * <p>
 * Asynchronous delivery widened the window between them and produced a duplicate success: 41 handler
 * successes for 40 messages, once in three full runs. Waiting for that to happen again is not a test.
 * This constructs it.
 * <p>
 * <b>The sequence.</b> A failed message is written back with {@code visible_at} in the future and
 * queued in memory. Two things can then deliver it: {@code dispatchDueRetries} from memory, and the
 * head sweep from the table once that timestamp passes. Both are deduplicated against the in-flight
 * and pending-acknowledgement sets — but only while the message is in one of them. {@code
 * dispatchDueRetries} declines to dispatch while the shard is at capacity, whereas the sweep does not
 * check capacity at all. So the sweep can deliver the message, have it acknowledged and deleted, and
 * leave a schedule entry behind that nothing can dedupe against any more.
 * <p>
 * Held at capacity by a handler the test controls, so the ordering is not left to chance.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedRetryRaceIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 1;

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

    @Test
    void a_message_delivered_by_the_sweep_is_not_delivered_again_by_its_stale_retry_entry() throws Exception {
        // The window is narrow and has to be built rather than waited for. Entering pumpOnce under
        // capacity, the cursor-read loop fills it; dispatchDueRetries then breaks at capacity and
        // leaves the due entry in the schedule; and sweepFromHead runs regardless, because it has no
        // capacity check. Saturating the shard with slow filler messages is what keeps that ordering
        // happening over and over.
        var settings = new ShardOwnerSettings(500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
                                              Duration.ofSeconds(10), Duration.ofMillis(20), 1_000,
                                              4, Duration.ofMillis(20), Duration.ofSeconds(30), 1, Duration.ofSeconds(5), Duration.ofMillis(30000), Duration.ofSeconds(60));

        var successes  = new CopyOnWriteArrayList<String>();
        var failedOnce = new ConcurrentHashMap<String, Boolean>();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "retry-race")) {
            // Local hand-off bypasses the cursor and the sweep, which is the path this is not about.
            queue.setLocalHandoffEnabled(false);
            queue.startConsuming((payload, payloadType) -> {
                var body = new String(payload, StandardCharsets.UTF_8);
                if (body.startsWith("flaky") && failedOnce.putIfAbsent(body, Boolean.TRUE) == null) {
                    throw new IllegalStateException("first attempt fails");
                }
                try {
                    // Slow enough to keep the shard at capacity, so the cursor read is repeatedly the
                    // thing that fills the last slot.
                    Thread.sleep(40L);
                } catch (InterruptedException e) {
                    // Do NOT record a success here. An interrupted handler has not finished, the
                    // engine deliberately refuses to acknowledge it, and the message is redelivered —
                    // so counting it would make the engine's own contract look like a duplicate.
                    Thread.currentThread().interrupt();
                    return;
                }
                successes.add(body);
            }, settings, SHARD_COUNT, RedeliveryPolicy.fixed(Duration.ofMillis(60), 5));

            var flaky = 40;
            var payloads = new ArrayList<byte[]>();
            for (var index = 0; index < flaky; index++) {
                payloads.add(("flaky-" + index).getBytes(StandardCharsets.UTF_8));
                for (var filler = 0; filler < 4; filler++) {
                    payloads.add(("filler-" + index + "-" + filler).getBytes(StandardCharsets.UTF_8));
                }
            }
            for (var payload : payloads) {
                queue.enqueue(List.of(payload), 1);
            }

            Awaitility.await().atMost(Duration.ofSeconds(90))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
            // Give any entry still sitting in the schedule its chance to fire after the row is gone.
            Thread.sleep(3_000L);

            var duplicated = successes.stream()
                                      .collect(java.util.stream.Collectors.groupingBy(body -> body,
                                                                                      java.util.stream.Collectors.counting()))
                                      .entrySet().stream()
                                      .filter(entry -> entry.getValue() > 1)
                                      .toList();
            var metrics = queue.metrics().snapshot();
            assertThat(duplicated)
                    .as("a message another path already delivered and acknowledged must not be "
                        + "delivered again: %s", metrics)
                    .isEmpty();
            // No assertion on sweepRecoveries here, deliberately. It looked like a fast-path
            // collapse — 97% of deliveries came from the head sweep — until the same measurement was
            // taken with delivery inline and still showed 76%. This workload enqueues faster than a
            // 40ms handler drains, so a sweep running after a long delivery batch legitimately finds
            // work the cursor has not reached. The number is a property of this test's shape, not of
            // the engine, and gating on it would have been gating on the harness.
            assertThat((Long) metrics.get("delivered")).as("%s", metrics).isPositive();
        }
    }
}
