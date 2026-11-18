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
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An unordered enqueue whose transaction commits more than {@code holeExpiry} after it took its sequence value.
 * <p>
 * The owner records the value as a hole when the cursor reads past it, and abandons the hole after
 * {@code holeExpiry}. From then on nothing in memory holds the range acknowledgement back below the late value, and
 * the cursor is already past it, so only the head sweep can deliver it once it commits. The message must still be
 * delivered: a range acknowledgement of later traffic must not delete it first.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedLateCommitAfterHoleExpiryIT {
    private static final short    QUEUE_ID    = 1;
    private static final int      SHARD_COUNT = 1;
    private static final int      SHARD       = 0;
    private static final Duration HOLE_EXPIRY = Duration.ofSeconds(1);

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
    void a_message_committed_after_its_hole_expired_is_delivered_while_later_traffic_is_acknowledged() throws Exception {
        var storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
        var settings = ShardOwnerSettings.builder()
                                         .setHoleExpiry(HOLE_EXPIRY)
                                         .build();
        var received = new CopyOnWriteArrayList<String>();

        try (var owner = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "owner")) {
            owner.setLocalHandoffEnabled(false);
            owner.startConsuming((messageId, payload, payloadType) -> received.add(text(payload)), settings, SHARD_COUNT);

            // Traffic before the late producer, so the owner's takeover cursor is well below the late value and the
            // range acknowledgement - not the exact-seq path below the takeover cursor - is the one that covers it
            var sent = enqueue(storage, "before", 20);
            final var sentBefore = sent;
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> received.size() == sentBefore);

            try (var lateProducer = dataSource.getConnection()) {
                lateProducer.setAutoCommit(false);
                var lateSeq = storage.enqueueRows(lateProducer, SHARD, List.of(new ShardOwnedStorage.PayloadRow(bytes("late"), 1, Duration.ZERO))).getFirst();

                // Keep the shard busy across and well past holeExpiry: every batch commits above the late value, is
                // read past it - recording it as a hole - and is handled and acknowledged
                var busyUntil = System.nanoTime() + HOLE_EXPIRY.multipliedBy(3).toNanos();
                var batch = 0;
                while (System.nanoTime() < busyUntil) {
                    sent += enqueue(storage, "busy-" + batch++, 10);
                    Thread.sleep(50);
                }
                final var sentBusy = sent;
                Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> received.size() == sentBusy);
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .until(() -> rowsAbove(lateSeq) == 0);

                // The injection worked: the late value was recorded as a hole and abandoned, and the traffic above it
                // was acknowledged - deleted - while it was still uncommitted
                var beforeCommit = owner.metrics().snapshot();
                assertThat((Long) beforeCommit.get("holesObserved")).as("holes observed: %s", beforeCommit).isGreaterThanOrEqualTo(1L);
                assertThat((Long) beforeCommit.get("holesAbandoned")).as("holes abandoned: %s", beforeCommit).isGreaterThanOrEqualTo(1L);
                assertThat(rowsAbove(lateSeq)).as("traffic above the late value acknowledged before the commit").isZero();
                assertThat(received).doesNotContain("late");

                lateProducer.commit();
                assertThat(rowPresent(lateSeq)).as("the late row is committed").isTrue();

                // Traffic right after the commit, acknowledged by range within milliseconds - well before the head
                // sweep comes round - and then more, for several sweep intervals
                for (var after = 0; after < 10; after++) {
                    sent += enqueue(storage, "after-commit-" + after, 10);
                    Thread.sleep(100);
                }
                final var sentAll = sent;
                Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> received.size() >= sentAll);

                // Several sweep intervals for the head sweep to deliver the late message, unless it has already been
                // delivered or its row is gone without it
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .until(() -> received.contains("late") || !rowPresent(lateSeq));
                Thread.sleep(settings.sweepInterval().multipliedBy(4).toMillis());

                var after = owner.metrics().snapshot();
                var lateDeliveries = received.stream().filter("late"::equals).count();
                assertThat(lateDeliveries)
                        .as("deliveries of the late message (seq %d); its row still present: %s; metrics: %s",
                            lateSeq, rowPresent(lateSeq), after)
                        .isGreaterThanOrEqualTo(1L);
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> owner.remaining() == 0);
                assertThat(received).hasSize(sentAll + 1);
            }
        }
    }

    private int enqueue(ShardOwnedStorage storage, String prefix, int count) throws SQLException {
        var messages = new ArrayList<byte[]>(count);
        for (var index = 0; index < count; index++) {
            messages.add(bytes(prefix + "-" + index));
        }
        try (var connection = dataSource.getConnection()) {
            storage.enqueueBatch(connection, SHARD, messages, 1);
        }
        return count;
    }

    private boolean rowPresent(long seq) throws SQLException {
        return count("seq = ?", seq) == 1;
    }

    private long rowsAbove(long seq) throws SQLException {
        return count("seq > ?", seq);
    }

    private long count(String predicate, long seq) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.UNORDERED_TABLE
                                                                 + " WHERE queue_id = ? AND shard = ? AND " + predicate)) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setLong(3, seq);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
