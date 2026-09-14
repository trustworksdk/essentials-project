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

package dk.trustworks.essentials.examples.perflab;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.examples.perflab.harness.*;
import dk.trustworks.essentials.components.queue.shardowned.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enqueue-to-handler latency, measured at an offered rate well below capacity.
 * <p>
 * Phase 0 established that latency at or above capacity is fixed by Little's Law at
 * {@code depth / throughput} regardless of implementation, so it says nothing about a design. The
 * rate here is deliberately low: what is being measured is how quickly a message that has nothing
 * queued ahead of it reaches a handler.
 * <p>
 * The comparison is Tier 2 on against Tier 2 off. It is the only measurement that can judge the
 * tier, because Tier 2 is a latency mechanism — judging it on WAL bytes, which it does not improve
 * and slightly worsens, would be measuring it with the wrong instrument.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedLatencyIT {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedLatencyIT.class);

    private static final short QUEUE_ID      = 1;
    private static final int   SHARD_COUNT   = 8;
    private static final int   MESSAGE_COUNT = 2_000;
    private static final long  INTERVAL_MICROS = 2_000L; // 500/s, far below the measured capacity

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void measure_enqueue_to_handler_latency_with_and_without_local_handoff() throws Exception {
        // Warm up BOTH paths before measuring either. The first version of this test measured
        // Tier 2 first with no warmup and charged it a single ~287 ms stall — JIT, class loading and
        // connection-pool growth — which coordinated-omission accounting then propagated into the
        // response time of everything queued behind it, showing as a 260 ms p99. Phase 0 established
        // warmup and interleaving as harness rules; this test was written without applying them.
        measure(true);
        measure(false);

        var withHandoff = measure(true);
        var withoutHandoff = measure(false);
        var withHandoffService = lastServiceTime;
        var withoutHandoffService = lastServiceTimeOff;

        log.info("");
        log.info("===== ENQUEUE-TO-HANDLER LATENCY, {} messages at {}/s =====",
                 MESSAGE_COUNT, 1_000_000L / INTERVAL_MICROS);
        log.info(String.format("%-22s %10s %10s %10s %10s", "configuration", "p50", "p90", "p99", "max"));
        log.info(format("Tier 2 on  response", withHandoff));
        log.info(format("Tier 2 on  service ", withHandoffService));
        log.info(format("Tier 2 off response", withoutHandoff));
        log.info(format("Tier 2 off service ", withoutHandoffService));
        log.info("response = from the intended schedule slot; service = from when the producer actually sent.");
        log.info("A gap between them is the producer stalling, not the engine delivering slowly.");
        log.info("Baseline engine at its 20ms poll, measured in Phase 0: p50 20.7ms, p99 27.1ms");
        log.info("===========================================================");

        assertThat(withHandoff.count()).isEqualTo(MESSAGE_COUNT);
        assertThat(withoutHandoff.count()).isEqualTo(MESSAGE_COUNT);
    }

    private static String format(String label, LatencyRecorder.Summary summary) {
        return String.format("%-22s %9.2fms %9.2fms %9.2fms %9.2fms",
                             label,
                             summary.p50Micros() / 1000.0d,
                             summary.p90Micros() / 1000.0d,
                             summary.p99Micros() / 1000.0d,
                             summary.maxMicros() / 1000.0d);
    }

    private LatencyRecorder.Summary lastServiceTime;
    private LatencyRecorder.Summary lastServiceTimeOff;

    private LatencyRecorder.Summary measure(boolean localHandoff) throws Exception {
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);

        var recorder = new LatencyRecorder("enqueueToHandler");
        var handled = new AtomicInteger();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "latency")) {
            queue.setLocalHandoffEnabled(localHandoff);
            queue.startConsuming((messageId, payload, payloadType) -> {
                // Intended send time travels in the payload, so latency is measured against the
                // schedule rather than against when the producer got round to sending.
                var buffer = ByteBuffer.wrap(payload);
                var intended = buffer.getLong();
                var actualStart = buffer.getLong();
                recorder.record(intended, actualStart, System.nanoTime());
                handled.incrementAndGet();
            }, ShardOwnerSettings.defaults(), SHARD_COUNT);

            var scheduleStart = System.nanoTime();
            for (var index = 0; index < MESSAGE_COUNT; index++) {
                var intended = scheduleStart + index * INTERVAL_MICROS * 1_000L;
                var wait = intended - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                var payload = new byte[200];
                // Both times travel with the message: the schedule slot it should have had, and when
                // the producer actually got to it. Their difference is the producer's own stall, and
                // separating them is what tells a slow engine apart from a slow load generator.
                ByteBuffer.wrap(payload).putLong(intended).putLong(System.nanoTime());
                queue.enqueue(List.of(payload), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(handled.get()).isEqualTo(MESSAGE_COUNT));
            log.info("metrics (localHandoff={}): {}", localHandoff, queue.metrics().snapshot());
        }
        if (localHandoff) {
            lastServiceTime = recorder.serviceTimeSummary();
        } else {
            lastServiceTimeOff = recorder.serviceTimeSummary();
        }
        return recorder.responseTimeSummary();
    }
}
