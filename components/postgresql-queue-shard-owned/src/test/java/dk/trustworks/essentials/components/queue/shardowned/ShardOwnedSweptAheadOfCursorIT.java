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
 * A row the head sweep delivers above the cursor, and that is handled and acknowledged - deleted - before the cursor
 * read reaches it, is not a hole.
 * <p>
 * The cursor read used to record it as one when it stepped over the value: a hole no chase could find, as the row was
 * gone, so it was chased every {@code chaseDelay} and held the range acknowledgement back until {@code holeExpiry}.
 * Seen as one such hole in two of five runs of {@code ShardOwnedUnorderedReadCostIT} at 2 000 msg/s, chased about 3 400
 * times. Here a lost local hand-off makes it deterministic: the cursor read skips this owner's own pre-claims, and the
 * head sweep delivers them once older than the hand-off grace.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedSweptAheadOfCursorIT {
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
    void a_row_the_sweep_delivered_above_the_cursor_is_not_recorded_as_a_hole_once_acknowledged() throws Exception {
        var storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
        var settings = ShardOwnerSettings.builder()
                                         .setHoleExpiry(HOLE_EXPIRY)
                                         .setSweepInterval(Duration.ofMillis(100))
                                         .setMaxSweepInterval(Duration.ofMillis(100))
                                         .build();
        var received = new CopyOnWriteArrayList<String>();

        try (var owner = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "owner")) {
            owner.setLocalHandoffEnabled(false);
            owner.startConsuming((messageId, payload, payloadType) -> received.add(text(payload)), settings, SHARD_COUNT);

            var sent = enqueue(storage, "before", 5);
            final var sentBefore = sent;
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> received.size() == sentBefore);

            // A hand-off this owner stamped and never dispatched: the cursor read skips it, the head sweep delivers it
            long sweptSeq;
            try (var connection = dataSource.getConnection()) {
                sweptSeq = storage.enqueuePreClaimed(connection, SHARD, List.of(bytes("swept")), 1, ownersFence()).getFirst();
            }
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> received.contains("swept") && !rowPresent(sweptSeq));
            var afterSweep = owner.metrics().snapshot();
            assertThat((Long) afterSweep.get("sweepRecoveries")).as("delivered by the head sweep: %s", afterSweep).isGreaterThanOrEqualTo(1L);

            // The cursor now reads past the swept value
            sent += enqueue(storage, "after", 5);
            final var sentAll = sent;
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> received.size() == sentAll + 1);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> owner.remaining() == 0);

            var after = owner.metrics().snapshot();
            assertThat((Long) after.get("holesObserved")).as("holes observed (swept seq %d): %s", sweptSeq, after).isZero();
        }
    }

    private long ownersFence() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT fence FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = 'unordered' AND shard = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("the shard's lease row").isTrue();
                return resultSet.getLong(1);
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
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.UNORDERED_TABLE
                                                                 + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setLong(3, seq);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) == 1;
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
