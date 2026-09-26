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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a key stalled behind a dead letter costs the database — {@code docs/durable-queue-shard-owned.md} §9.1.
 * <p>
 * A key never advances past a dead letter, and the messages behind it are moved to the dead-letter
 * table rather than left queued. That keeps undeliverable rows out of the cursor's way, and it is not
 * free: every message arriving for a stalled key is written <b>twice and deleted once</b> — the
 * producer's insert into {@code shard_queue_ordered}, then the owner's insert into
 * {@code shard_queue_dead_letter} and delete from the lane. Block-mode would have written it once and
 * left it.
 * <p>
 * Three questions, which is what the arms below are:
 * <ol>
 *     <li><b>WAL per message.</b> How much does the second write actually cost, against the same
 *         workload delivered normally? That is the number that decides whether a stall is an
 *         operational nuisance or a database event.</li>
 *     <li><b>Can the owner keep up?</b> If the poison path drains slower than a producer fills, the
 *         ordered table grows anyway and the placement bought nothing.</li>
 *     <li><b>What happens to a table nobody can opt out of.</b> {@code shard_queue_dead_letter} is one
 *         table for every queue on the database, so a stalled key inflates something every other
 *         queue's dead-letter listing reads.</li>
 * </ol>
 * Measure-only — no thresholds, so it buys nothing per build and is gated off with the other
 * benchmark suites: {@code -Dbenchmark.run=true}.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedStalledKeyCostIT {

    private static final Logger log = LoggerFactory.getLogger(ShardOwnedStalledKeyCostIT.class);

    private static final short  QUEUE_ID     = 1;
    private static final int    SHARD_COUNT  = 1;
    private static final int    PAYLOAD_TYPE = 1;
    private static final String KEY          = "stalled-key";
    /** Enough that the fixed cost of the stall itself does not dominate the per-message figure. */
    private static final int    MESSAGES     = 2_000;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void what_a_stalled_key_costs_against_the_same_workload_delivered() throws Exception {
        log.warn("=== a key stalled behind a dead letter: cost per message ===");
        log.warn(String.format("%-12s %14s %16s %14s %16s", "arm", "WAL bytes", "WAL/message", "msg/s", "dlq bytes/msg"));

        var delivering = measure("delivering", false);
        var stalled = measure("stalled", true);

        var walRatio = (double) stalled.walPerMessage() / Math.max(1, delivering.walPerMessage());
        log.warn("");
        log.warn("WAL per message, stalled vs delivering: {}x ({} vs {} bytes)",
                 String.format("%.2f", walRatio), stalled.walPerMessage(), delivering.walPerMessage());
        log.warn("Dead-letter table after the stall: {} bytes for {} rows — this table is SHARED by every "
                 + "queue on the database, so that is what a stalled key costs everyone else.",
                 stalled.deadLetterBytes(), MESSAGES);
        log.warn("At this rate a key stalled for an hour at 100 msg/s would add roughly {} MB.",
                 String.format("%.1f", stalled.deadLetterBytes() / (double) MESSAGES * 100 * 3600 / 1_000_000d));

        // The one thing worth asserting rather than printing: the lane must actually drain. If the
        // owner cannot move rows out as fast as the producer puts them in, the ordered table grows
        // behind a stalled key and dispatch-side poisoning bought nothing over leaving them queued.
        assertThat(stalled.remainingInLane())
                .as("the poison path must keep up with the producer, or the lane grows behind the stall")
                .isZero();
    }

    private record Arm(long walBytes, long walPerMessage, double messagesPerSecond, long deadLetterBytes,
                       long remainingInLane) {
    }

    private Arm measure(String name, boolean stall) throws Exception {
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);

        var handled = new AtomicLong();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "cost-" + name)) {
            queue.consume((messageId, key, payload, payloadType) -> {
                if (stall && Long.parseLong(new String(payload, StandardCharsets.UTF_8)) == 0L) {
                    throw new IllegalStateException("poison");
                }
                handled.incrementAndGet();
            }, new ConsumerOptions(4, SHARD_COUNT, 1, Duration.ofMillis(10), 1.0d, Duration.ofMillis(10)));

            if (stall) {
                // Stall the key first, so every message that follows meets a block rather than a
                // handler. key_order 0 exhausts its single attempt immediately.
                queue.enqueue(List.of(ordered(0L)));
                Awaitility.await().atMost(Duration.ofSeconds(30))
                          .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isEqualTo(1L));
            }

            var walBefore = walPosition();
            var start = System.nanoTime();
            for (var order = 1; order <= MESSAGES; order++) {
                queue.enqueue(List.of(ordered(order)));
            }
            Awaitility.await().atMost(Duration.ofMinutes(5))
                      .untilAsserted(() -> {
                          if (stall) {
                              assertThat(queue.depth().deadLettered()).isEqualTo(MESSAGES + 1L);
                          } else {
                              assertThat(handled.get()).isEqualTo(MESSAGES);
                          }
                      });
            var elapsedNanos = System.nanoTime() - start;
            var walBytes = walPosition() - walBefore;

            var arm = new Arm(walBytes,
                              walBytes / MESSAGES,
                              MESSAGES / (elapsedNanos / 1_000_000_000d),
                              tableBytes(ShardOwnedSchema.DLQ_TABLE),
                              queue.depth().ordered());
            log.warn(String.format("%-12s %14d %16d %14.0f %16d", name, arm.walBytes(), arm.walPerMessage(),
                                   arm.messagesPerSecond(), arm.deadLetterBytes() / MESSAGES));
            return arm;
        }
    }

    private static Message ordered(long order) {
        return Message.ordered(Long.toString(order).getBytes(StandardCharsets.UTF_8), PAYLOAD_TYPE, KEY, order);
    }

    /** Bytes of WAL written so far, as an absolute position the caller subtracts. */
    private long walPosition() throws Exception {
        try (var connection = dataSource.getConnection()) {
            return scalar(connection, "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint");
        }
    }

    private long tableBytes(String table) throws Exception {
        try (var connection = dataSource.getConnection()) {
            // Total relation size: the heap, its indexes and any TOAST, which is what the table
            // actually occupies for everyone sharing the database.
            return scalar(connection, "SELECT pg_total_relation_size('" + table + "')");
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
