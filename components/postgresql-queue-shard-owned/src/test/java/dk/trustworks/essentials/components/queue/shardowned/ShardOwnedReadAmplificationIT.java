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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unordered lane's cursor read fetches not much more than it can dispatch.
 * <p>
 * Rows past the free handler permits are not dispatched and the cursor does not move past them, so they are read
 * again next time. Reading {@code readBatchSize} (500) rows per read carried about 24 rows - payloads included - per
 * delivered message; the capped read carries about 4, with the same number of reads and the same drain time.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedReadAmplificationIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;
    private static final int   MESSAGES    = 4_000;

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
    void a_backlog_is_not_read_many_times_over() throws Exception {
        try (var producer = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "producer")) {
            for (var batch = 0; batch < MESSAGES / 500; batch++) {
                var payloads = new ArrayList<byte[]>(500);
                for (var index = 0; index < 500; index++) {
                    payloads.add(("m-" + batch + "-" + index).getBytes(StandardCharsets.UTF_8));
                }
                producer.enqueue(payloads, 1);
            }
        }

        var handled = new AtomicInteger();
        var startedAt = System.nanoTime();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> handled.incrementAndGet(), ShardOwnerSettings.defaults(), SHARD_COUNT);
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(queue.remaining()).isZero());

            var drainMillis = (System.nanoTime() - startedAt) / 1_000_000;
            var snapshot = queue.metrics().snapshot();
            var rowsRead = (Long) snapshot.get("cursorRowsRead");
            var delivered = (Long) snapshot.get("delivered");
            System.out.printf("%n=== Unordered drain of %,d messages in %,d ms: %,d cursor reads returning %,d rows for %,d delivered (%.2f rows per message) ===%n%n",
                              MESSAGES, drainMillis, (Long) snapshot.get("cursorReads"), rowsRead, delivered, (double) rowsRead / delivered);
            assertThat(delivered).isGreaterThanOrEqualTo(MESSAGES);
            assertThat(rowsRead).as("rows read per delivered message - about 24 when every read took readBatchSize rows").isLessThanOrEqualTo(delivered * 8);
        }
    }
}
