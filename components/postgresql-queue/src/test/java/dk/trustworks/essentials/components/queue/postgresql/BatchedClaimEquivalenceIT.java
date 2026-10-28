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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batched claim takes each queue's messages under a per-queue {@code LIMIT} instead of numbering every eligible
 * message of every queue in one window ({@link LegacyBatchedClaimStatement}). Both statements must claim the same
 * rows: each runs against the same fixture in a transaction that is rolled back, and the claimed ids are compared.
 * The hand-written fixtures also pin the expected rows, so the two cannot agree on a wrong answer.
 */
@Testcontainers
class BatchedClaimEquivalenceIT {
    private static final String    QUEUE_TABLE = "batched_equivalence_queue";
    private static final QueueName QUEUE_A     = QueueName.of("BatchedEquivalenceA");
    private static final QueueName QUEUE_B     = QueueName.of("BatchedEquivalenceB");
    private static final QueueName QUEUE_C     = QueueName.of("BatchedEquivalenceC");
    /**
     * A queue that is never polled: none of its messages may be claimed
     */
    private static final QueueName IDLE_QUEUE  = QueueName.of("BatchedEquivalenceIdle");
    private static final Instant   NOW         = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("batched-equivalence-db");

    private Jdbi                    jdbi;
    private PostgresqlDurableQueues durableQueues;
    private List<Row>               rows;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .build();
        durableQueues.start();
        jdbi.useHandle(handle -> handle.execute("DELETE FROM " + QUEUE_TABLE));
        rows = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void unordered_only() {
        for (var i = 0; i < 300; i++) {
            unordered(QUEUE_A, "a-" + i, -i);
            unordered(QUEUE_B, "b-" + i, -i);
        }
        unordered(IDLE_QUEUE, "idle", -1_000);

        var claimed = claimWithBoth(Map.of(QUEUE_A, 5, QUEUE_B, 400), Map.of());

        // Oldest first: the highest i is the oldest
        assertThat(claimed).hasSize(305)
                           .contains("a-299", "a-295", "b-0", "b-299")
                           .doesNotContain("a-294", "idle");
    }

    @Test
    void ordered_with_many_keys() {
        for (var key = 0; key < 200; key++) {
            for (var order = 0; order < 3; order++) {
                ordered(QUEUE_A, "a-" + key + "-" + order, "key-" + key, order, -10_000 + key * 10 + order);
            }
        }

        var claimed = claimWithBoth(Map.of(QUEUE_A, 10), Map.of());

        assertThat(claimed).containsExactlyInAnyOrder("a-0-0", "a-1-0", "a-2-0", "a-3-0", "a-4-0", "a-5-0", "a-6-0", "a-7-0", "a-8-0", "a-9-0");
        // More slots than heads: one per key, never a second message of a key
        assertThat(claimWithBoth(Map.of(QUEUE_A, 1_000), Map.of())).hasSize(200).allMatch(id -> id.endsWith("-0"));
    }

    @Test
    void ordered_with_few_long_keys() {
        for (var order = 0; order < 100; order++) {
            for (var key = 0; key < 5; key++) {
                ordered(QUEUE_A, "a-" + key + "-" + order, "key-" + key, order, -10_000 + order * 10 + key);
            }
        }

        assertThat(claimWithBoth(Map.of(QUEUE_A, 3), Map.of())).containsExactlyInAnyOrder("a-0-0", "a-1-0", "a-2-0");
        assertThat(claimWithBoth(Map.of(QUEUE_A, 20), Map.of())).containsExactlyInAnyOrder("a-0-0", "a-1-0", "a-2-0", "a-3-0", "a-4-0");
    }

    @Test
    void mixed_queue_shares_its_slots_between_ordered_and_unordered_messages() {
        for (var i = 0; i < 10; i++) {
            unordered(QUEUE_A, "u-" + i, -100 + i * 2);
            ordered(QUEUE_A, "o-" + i, "key-" + i, 0, -100 + i * 2 + 1);
        }

        // Oldest by next_delivery_ts across both kinds: u-0, o-0, u-1, o-1, u-2, o-2, u-3
        assertThat(claimWithBoth(Map.of(QUEUE_A, 7), Map.of())).containsExactlyInAnyOrder("u-0", "o-0", "u-1", "o-1", "u-2", "o-2", "u-3");
    }

    @Test
    void excluded_keys_are_skipped_and_do_not_starve_unordered_messages() {
        ordered(QUEUE_A, "k1-0", "key-1", 0, -100);
        ordered(QUEUE_A, "k1-1", "key-1", 1, -99);
        ordered(QUEUE_A, "k2-0", "key-2", 0, -98);
        ordered(QUEUE_A, "k3-0", "key-3", 5, -97);
        unordered(QUEUE_A, "u-0", -96);
        ordered(QUEUE_B, "b-k1-0", "key-1", 0, -100);

        var claimed = claimWithBoth(Map.of(QUEUE_A, 10, QUEUE_B, 10),
                                    Map.of(QUEUE_A, Set.of("key-1", "key-3", "no-such-key")));

        // key-1 is excluded on queue A only
        assertThat(claimed).containsExactlyInAnyOrder("k2-0", "u-0", "b-k1-0");
    }

    @Test
    void a_head_being_delivered_dead_lettered_or_not_yet_due_blocks_its_key() {
        row(new Row("inflight-0", QUEUE_A, "key-inflight", 0L, null, true, false));
        ordered(QUEUE_A, "inflight-1", "key-inflight", 1, -50);
        row(new Row("dead-0", QUEUE_A, "key-dead", 0L, null, false, true));
        ordered(QUEUE_A, "dead-1", "key-dead", 1, -50);
        ordered(QUEUE_A, "delayed-0", "key-delayed", 0, 60_000);
        ordered(QUEUE_A, "delayed-1", "key-delayed", 1, -50);
        ordered(QUEUE_A, "free-0", "key-free", 0, -40);
        ordered(QUEUE_A, "free-1", "key-free", 1, -30);
        row(new Row("u-inflight", QUEUE_A, null, -1L, null, true, false));
        row(new Row("u-dead", QUEUE_A, null, -1L, null, false, true));
        unordered(QUEUE_A, "u-delayed", 60_000);
        unordered(QUEUE_A, "u-due", -20);

        assertThat(claimWithBoth(Map.of(QUEUE_A, 10), Map.of())).containsExactlyInAnyOrder("free-0", "u-due");
    }

    @Test
    void equal_next_delivery_ts_is_broken_by_id_and_duplicate_heads_are_all_candidates() {
        for (var i = 0; i < 20; i++) {
            unordered(QUEUE_A, "u-" + (char) ('a' + i), -10);
        }
        // Two messages share the key's lowest key_order: both are heads, as the per-key barrier is strict
        ordered(QUEUE_B, "dup-1", "key-dup", 3, -10);
        ordered(QUEUE_B, "dup-2", "key-dup", 3, -10);
        ordered(QUEUE_B, "dup-3", "key-dup", 4, -10);

        assertThat(claimWithBoth(Map.of(QUEUE_A, 3, QUEUE_B, 5), Map.of())).containsExactlyInAnyOrder("u-a", "u-b", "u-c", "dup-1", "dup-2");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void random_fixture(long seed) {
        var random = new Random(seed);
        var queues = List.of(QUEUE_A, QUEUE_B, QUEUE_C, IDLE_QUEUE);
        var slots  = new HashMap<QueueName, Integer>();
        var excludeKeys = new HashMap<QueueName, Set<String>>();
        for (var queue : queues) {
            var prefix = queue.toString() + "-";
            for (int i = 0, n = random.nextInt(60); i < n; i++) {
                row(randomState(random, new Row(prefix + "u-" + i, queue, null, -1L, randomOffset(random), false, false)));
            }
            var keys = random.nextInt(30);
            for (var key = 0; key < keys; key++) {
                var order = (long) random.nextInt(5);
                for (int i = 0, n = 1 + random.nextInt(random.nextBoolean() ? 3 : 15); i < n; i++) {
                    row(randomState(random, new Row(prefix + "k" + key + "-" + i, queue, "key-" + key, order, randomOffset(random), false, false)));
                    // Now and then two messages share a key_order
                    order += random.nextInt(20) == 0 ? 0 : 1;
                }
            }
            if (queue != IDLE_QUEUE) {
                slots.put(queue, 1 + random.nextInt(40));
                var excluded = new HashSet<String>();
                for (int i = 0, n = random.nextInt(4); i < n; i++) {
                    excluded.add("key-" + random.nextInt(Math.max(keys, 1) + 2));
                }
                if (!excluded.isEmpty()) {
                    excludeKeys.put(queue, excluded);
                }
            }
        }

        var claimed = claimWithBoth(slots, excludeKeys);

        var claimedPerQueue = new HashMap<QueueName, Integer>();
        rows.stream().filter(row -> claimed.contains(row.id())).forEach(row -> claimedPerQueue.merge(row.queueName(), 1, Integer::sum));
        claimedPerQueue.forEach((queue, count) -> assertThat(count).as(queue.toString()).isLessThanOrEqualTo(slots.getOrDefault(queue, 0)));
    }

    /**
     * Claim with the current and the legacy statement, each in a transaction that is rolled back, and require the same
     * rows
     *
     * @return the ids claimed
     */
    private Set<String> claimWithBoth(Map<QueueName, Integer> slots, Map<QueueName, Set<String>> excludeKeys) {
        insertRows();
        var activeQueues = slots.keySet().stream().sorted(Comparator.comparing(QueueName::toString)).toList();
        var current      = durableQueues.getDurableQueuesSql().buildBatchedSqlStatement(excludeKeys, slots, activeQueues);
        var legacy       = LegacyBatchedClaimStatement.build(QUEUE_TABLE, excludeKeys, slots, activeQueues);

        var claimed       = claim(current);
        var legacyClaimed = claim(legacy);

        assertThat(claimed).as("each row claimed at most once").doesNotHaveDuplicates();
        assertThat(claimed).as("the same rows as the legacy statement").containsExactlyInAnyOrderElementsOf(legacyClaimed);
        return new HashSet<>(claimed);
    }

    private List<String> claim(DurableQueuesSql.BatchedSqlResult statement) {
        return jdbi.withHandle(handle -> {
            handle.begin();
            try {
                var query = handle.createQuery(statement.getSql()).bind("now", NOW);
                statement.getSingleValueBindings().forEach(query::bind);
                statement.getListBindings().forEach(query::bindList);
                return query.map((resultSet, context) -> resultSet.getString("id")).list();
            } finally {
                handle.rollback();
            }
        });
    }

    private void insertRows() {
        jdbi.useHandle(handle -> {
            handle.execute("DELETE FROM " + QUEUE_TABLE);
            var batch = handle.prepareBatch("INSERT INTO " + QUEUE_TABLE + " (id, queue_name, message_payload, message_payload_type, added_ts, " +
                                                    "next_delivery_ts, delivery_ts, is_being_delivered, is_dead_letter_message, delivery_mode, key, key_order) " +
                                                    "VALUES (:id, :queueName, '{}'::jsonb, 'java.lang.String', :addedTs, :nextDeliveryTs, :deliveryTs, " +
                                                    ":beingDelivered, :deadLetter, :deliveryMode, :key, :keyOrder)");
            for (var row : rows) {
                batch.bind("id", row.id())
                     .bind("queueName", row.queueName().toString())
                     .bind("addedTs", NOW.minusSeconds(3_600))
                     .bindByType("nextDeliveryTs", row.nextDeliveryOffsetMillis() == null ? null : NOW.plusMillis(row.nextDeliveryOffsetMillis()), Instant.class)
                     .bindByType("deliveryTs", row.beingDelivered() ? NOW.minusSeconds(1) : null, Instant.class)
                     .bind("beingDelivered", row.beingDelivered())
                     .bind("deadLetter", row.deadLetter())
                     .bind("deliveryMode", row.key() == null ? "NORMAL" : "IN_ORDER")
                     .bind("key", row.key())
                     .bind("keyOrder", row.keyOrder())
                     .add();
            }
            if (!rows.isEmpty()) {
                batch.execute();
            }
        });
    }

    private void unordered(QueueName queueName, String id, long nextDeliveryOffsetMillis) {
        row(new Row(id, queueName, null, -1L, nextDeliveryOffsetMillis, false, false));
    }

    private void ordered(QueueName queueName, String id, String key, long keyOrder, long nextDeliveryOffsetMillis) {
        row(new Row(id, queueName, key, keyOrder, nextDeliveryOffsetMillis, false, false));
    }

    private void row(Row row) {
        rows.add(row);
    }

    /**
     * Mostly due, with ties; some not yet due
     */
    private static long randomOffset(Random random) {
        return random.nextInt(10) == 0 ? 1_000L * (1 + random.nextInt(100)) : -1_000L * random.nextInt(200);
    }

    private static Row randomState(Random random, Row row) {
        return switch (random.nextInt(20)) {
            case 0, 1 -> new Row(row.id(), row.queueName(), row.key(), row.keyOrder(), null, true, false);
            case 2 -> new Row(row.id(), row.queueName(), row.key(), row.keyOrder(), null, false, true);
            default -> row;
        };
    }

    private record Row(String id, QueueName queueName, String key, Long keyOrder, Long nextDeliveryOffsetMillis, boolean beingDelivered, boolean deadLetter) {
    }
}
