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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delayed delivery, on both lanes.
 * <p>
 * Two properties, and the second is the one that is easy to get wrong. A delayed message must not be
 * delivered early — that is the feature. It must also not be delivered *very* late, and nothing about
 * the normal read path makes that true: the row is invisible when the cursor passes it, so the reader
 * books it as a hole; the hole chase filters on visibility too and cannot resolve it; the hole is
 * abandoned after {@code holeExpiry}; and the head sweep it falls back on has backed off to
 * {@code maxSweepInterval} on a quiet shard. Left alone, a one-second delay arrives up to thirty
 * seconds late.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedDelayedDeliveryIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 2;

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
    void a_delayed_message_is_not_delivered_before_its_delay_and_not_long_after() throws Exception {
        var arrivals = new ConcurrentHashMap<String, Long>();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "delay-1")) {
            queue.consume((messageId, key, payload, payloadType) -> arrivals.putIfAbsent(new String(payload, StandardCharsets.UTF_8),
                                                                 System.currentTimeMillis()),
                          ConsumerOptions.defaults());
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> queue.isStarted());

            var enqueuedAt = System.currentTimeMillis();
            queue.enqueue(List.of(Message.of("immediate".getBytes(StandardCharsets.UTF_8), 1),
                                  Message.delayed("late-2s".getBytes(StandardCharsets.UTF_8), 1, Duration.ofSeconds(2)),
                                  Message.delayedOrdered("late-ordered".getBytes(StandardCharsets.UTF_8), 1,
                                                         "k", 0L, Duration.ofSeconds(2))));

            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> arrivals.containsKey("immediate"));
            assertThat(arrivals.get("immediate") - enqueuedAt)
                    .describedAs("an undelayed message in the same batch must not be held up by the delayed ones")
                    .isLessThan(2_000L);
            assertThat(arrivals)
                    .describedAs("nothing delayed may arrive while the immediate one has only just landed")
                    .doesNotContainKeys("late-2s", "late-ordered");

            // Generous upper bound, but far below the 30s maxSweepInterval a quiet shard backs off
            // to — which is what this would take without the owner parking on the due time.
            Awaitility.await().atMost(Duration.ofSeconds(12))
                      .until(() -> arrivals.containsKey("late-2s") && arrivals.containsKey("late-ordered"));

            for (var body : List.of("late-2s", "late-ordered")) {
                var waited = arrivals.get(body) - enqueuedAt;
                assertThat(waited).describedAs("%s must not arrive early", body).isGreaterThanOrEqualTo(1_900L);
                assertThat(waited).describedAs("%s must not arrive long after it was due", body).isLessThan(8_000L);
            }
        }
    }

    /**
     * A delayed row sits below the cursor as a hole until its delay passes, and the hole is written off
     * after {@code holeExpiry}. From then on nothing in memory protects it from the range delete that
     * acknowledges the messages after it, so with traffic flowing it used to be deleted undelivered.
     * {@code holeExpiry} is shortened so that happens well inside the delay.
     */
    @Test
    void a_delayed_message_survives_the_acknowledgement_of_the_traffic_after_it() throws Exception {
        var arrivals = ConcurrentHashMap.<String>newKeySet();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "delay-3",
                                                    withHoleExpiry(Duration.ofMillis(200)))) {
            queue.consume((messageId, key, payload, payloadType) -> arrivals.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> queue.isStarted());

            queue.enqueue(Message.delayed("late".getBytes(StandardCharsets.UTF_8), 1, Duration.ofSeconds(2)));
            var sent = 0;
            var until = System.nanoTime() + Duration.ofMillis(1_500).toNanos();
            while (System.nanoTime() < until) {
                var batch = new ArrayList<Message>();
                for (var index = 0; index < 20; index++) {
                    batch.add(Message.of(("m-" + sent++).getBytes(StandardCharsets.UTF_8), 1));
                }
                queue.enqueue(batch);
                Thread.sleep(25L);
            }
            var expected = sent + 1;

            Awaitility.await().atMost(Duration.ofSeconds(15))
                      .untilAsserted(() -> assertThat(arrivals)
                              .as("the delayed message must be delivered, not deleted by a later message's ack")
                              .hasSize(expected)
                              .contains("late"));
        }
    }

    private static ShardOwnerSettings withHoleExpiry(Duration holeExpiry) {
        var d = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(d.readBatchSize(), d.ackBatchSize(), d.ackFlushInterval(), d.chaseDelay(),
                                      holeExpiry, d.sweepInterval(), d.maxHolesPerChase(), d.keyConcurrency(),
                                      d.pollBackstop(), d.maxSweepInterval(), d.pumpThreads(), d.shedGrace(),
                                      d.leaseTtl(), d.watermarkCap());
    }

    /**
     * The delay is applied by the server, which is what keeps it independent of the enqueueing node's
     * clock — the same rule every other durable moment in this engine follows. Checked structurally,
     * because with one clock in the test there is no skew to observe.
     */
    @Test
    void the_delay_is_applied_server_side() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "delay-2")) {
            queue.enqueue(Message.delayed("d".getBytes(StandardCharsets.UTF_8), 1, Duration.ofMinutes(30)));

            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(
                         "SELECT visible_at > now() + interval '25 minutes',"
                         + "       visible_at < now() + interval '35 minutes'"
                         + "  FROM " + ShardOwnedSchema.UNORDERED_TABLE + " WHERE queue_id = ?")) {
                statement.setShort(1, QUEUE_ID);
                try (var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getBoolean(1) && resultSet.getBoolean(2))
                            .describedAs("visible_at must be the server's now() plus the delay")
                            .isTrue();
                }
            }
        }
    }
}
