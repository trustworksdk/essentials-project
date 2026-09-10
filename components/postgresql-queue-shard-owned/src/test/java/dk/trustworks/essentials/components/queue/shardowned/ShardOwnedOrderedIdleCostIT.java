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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a QUIET ordered queue costs the database, and whether that cost scales with the routing space.
 * <p>
 * This is the gate the fixed routing space needed and did not have. The ordered lane owns
 * {@link ShardOwnedSchema#ORDERED_UNITS} units rather than a handful of shards, and an owner with
 * anything to do issues three statements — cursor read, head sweep, next-visible. Unbatched that is
 * three per unit per sweep, so raising the routing space raises idle cost in proportion, and at 64
 * units across a few hundred queues it is the multi-queue budget gone. The pump therefore batches
 * them per queue, and this asserts the property that makes the routing space affordable: <b>idle
 * statement cost is a function of the number of QUEUES a pump serves, not of the number of units.</b>
 * <p>
 * {@code ShardOwnedMultiQueueCostIT} does not cover this. It consumes the unordered lane only and
 * reports 0.0 idle queries per owned shard, so its green result says nothing about the ordered lane —
 * which is exactly how 64 units shipped unmeasured.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedOrderedIdleCostIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    /**
     * A fixed sweep cadence with no backoff, so the number of sweep cycles in the window is
     * arithmetic rather than a guess. The backoff is what normally makes a quiet queue cheap; turning
     * it off measures the mechanism under test instead of hiding behind it.
     */
    private static final Duration SWEEP  = Duration.ofMillis(200);
    private static final Duration WINDOW = Duration.ofSeconds(3);

    private static final ShardOwnerSettings STEADY_SWEEP =
            new ShardOwnerSettings(500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
                                   Duration.ofSeconds(10),
                                   SWEEP,                    // sweepInterval
                                   1_000, 8, Duration.ofMillis(500),
                                   SWEEP,                    // maxSweepInterval: no backoff
                                   2, Duration.ofSeconds(5), Duration.ofSeconds(30),
                                   Duration.ofSeconds(60));

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
    void an_idle_ordered_queue_costs_statements_per_queue_not_per_unit() throws Exception {
        var received = new CopyOnWriteArrayList<String>();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "idle-1")) {
            queue.startConsumingOrdered((key, payload, payloadType) ->
                                                received.add(new String(payload, StandardCharsets.UTF_8)),
                                        STEADY_SWEEP,
                                        ShardOwnedSchema.ORDERED_UNITS);

            // The engine must be ALIVE, or "cheap" is just "broken". One message through, then quiet.
            queue.enqueueOrdered(List.of(new OrderedPayload("warm-up", 0,
                                                            "hello".getBytes(StandardCharsets.UTF_8), 1)));
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(received).containsExactly("hello"));
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(queue.orderedRemaining()).isZero());

            // Let the last delivery's effects settle out of the window being measured.
            Thread.sleep(500);

            var before = queue.metrics().orderedReadStatements.sum();
            Thread.sleep(WINDOW.toMillis());
            var statements = queue.metrics().orderedReadStatements.sum() - before;

            var served = queue.metrics().cursorReads.sum();
            var cycles = WINDOW.toMillis() / SWEEP.toMillis();
            var units  = ShardOwnedSchema.ORDERED_UNITS;
            // Unbatched this is 3 statements x 64 units x 15 cycles = 2 880. Batched it is 3 per
            // queue per pump per cycle, so a couple of hundred at the very worst. The bound is set an
            // order of magnitude below the per-unit figure: anything that scales with units cannot
            // pass, and the exact batched number is left free because pump count and cycle alignment
            // move it around.
            var perUnitCost = 3L * units * cycles;
            System.out.printf("idle ordered queue: %d units, %s window, %d sweep cycles -> "
                              + "%d statements (%.1f/s), %d owner-passes; per-unit would be %d%n",
                              units, WINDOW, cycles, statements,
                              statements * 1000.0 / WINDOW.toMillis(), served, perUnitCost);
            assertThat(statements)
                    .as("an idle ordered queue must not pay per unit: %d statements over %s with %d "
                        + "units owned, against %d if every unit read for itself",
                        statements, WINDOW, units, perUnitCost)
                    .isLessThan(perUnitCost / 10);

            // And the owners really were idle-but-attentive, not asleep: with the backoff pinned off,
            // the sweep is due every 200ms and someone must have been doing it.
            assertThat(statements)
                    .as("the sweep must still be running — zero statements would mean the owners "
                        + "stopped rather than got cheap")
                    .isPositive();

            // The property stated directly, and independent of the arithmetic above: statements
            // issued against owners SERVED. Unbatched the two move together by construction, because
            // every served owner issues its own. A wide gap is the batching, and nothing else
            // produces one.
            assertThat(statements)
                    .as("idle cost must follow the number of queues, not the %d owners served: %d "
                        + "statements for %d owner-passes", units, statements, served)
                    .isLessThan(served / 5);
        }
    }

}
