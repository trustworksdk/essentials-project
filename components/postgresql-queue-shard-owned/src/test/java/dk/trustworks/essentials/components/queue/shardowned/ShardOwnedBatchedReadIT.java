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
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batched reads must return exactly what the per-shard reads they replace return — same rows, same
 * order, for every shard, at every cursor position.
 * <p>
 * This exists because the first attempt at batching the pump's reads stalled delivery at 380 of 1 000
 * messages with no statement failure and no fallback taken, which meant the statements succeeded and
 * returned something subtly different. Comparing whole engine behaviour could not say what; comparing
 * the two reads directly can.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedBatchedReadIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 8;
    private static final int   BATCH_SIZE  = 500;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource  dataSource;
    private ShardOwnedStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);

        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        storage = new ShardOwnedStorage(dataSource, QUEUE_ID);

        // A realistic spread: many keys, several messages each, routed the way the engine routes them,
        // so the shards hold uneven counts and the shared ordered sequence interleaves them.
        try (var connection = dataSource.getConnection()) {
            var byShard = new HashMap<Integer, List<OrderedPayload>>();
            for (var keyIndex = 0; keyIndex < 50; keyIndex++) {
                var key = "key-" + keyIndex;
                for (var order = 0; order < 20; order++) {
                    byShard.computeIfAbsent(ShardOwnedSchema.shardForKey(key, SHARD_COUNT), s -> new ArrayList<>())
                           .add(new OrderedPayload(key, order,
                                                   (key + ":" + order).getBytes(StandardCharsets.UTF_8), 1));
                }
            }
            for (var entry : byShard.entrySet()) {
                storage.enqueueOrderedBatch(connection, entry.getKey(), entry.getValue());
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void the_batched_cursor_read_returns_exactly_what_the_per_shard_reads_return() throws Exception {
        // Cursor positions chosen to cover the interesting cases at once: at the head, part way
        // through, and past the end of a shard.
        var shards  = new int[SHARD_COUNT];
        var cursors = new long[SHARD_COUNT];
        for (var shard = 0; shard < SHARD_COUNT; shard++) {
            shards[shard] = shard;
            cursors[shard] = switch (shard % 3) {
                case 0 -> 0L;
                case 1 -> 400L;
                default -> 100_000L;
            };
        }

        try (var connection = dataSource.getConnection()) {
            var batched = storage.readOrderedFromCursors(connection, QUEUE_ID, shards, cursors, BATCH_SIZE);

            for (var index = 0; index < shards.length; index++) {
                var shard    = shards[index];
                var expected = storage.readOrderedFromCursor(connection, shard, cursors[index], BATCH_SIZE);
                var actual   = batched.getOrDefault(shard, List.of());

                assertThat(seqsOf(actual))
                        .as("shard %d from cursor %d must return the same sequence values, in the same "
                            + "order, as the per-shard read", shard, cursors[index])
                        .isEqualTo(seqsOf(expected));
                assertThat(keysOf(actual))
                        .as("shard %d must return the same keys as the per-shard read", shard)
                        .isEqualTo(keysOf(expected));
            }
        }
    }

    /**
     * The per-shard limit must be a full batch each, not a share of one.
     * <p>
     * Splitting a batch across the shards was the first attempt's mistake in the making: it returns
     * fewer rows per shard than the read it replaces, so the two are no longer interchangeable and the
     * owners see a different world depending on how many of their siblings happened to be attentive
     * that pass.
     */
    @Test
    void a_shard_returns_a_full_batch_regardless_of_how_many_shards_are_read_with_it() throws Exception {
        var busiest = busiestShard();
        try (var connection = dataSource.getConnection()) {
            var alone = storage.readOrderedFromCursors(connection, QUEUE_ID, new int[]{busiest}, new long[]{0L}, BATCH_SIZE);

            var shards  = new int[SHARD_COUNT];
            var cursors = new long[SHARD_COUNT];
            for (var shard = 0; shard < SHARD_COUNT; shard++) {
                shards[shard] = shard;
            }
            var together = storage.readOrderedFromCursors(connection, QUEUE_ID, shards, cursors, BATCH_SIZE);

            assertThat(seqsOf(together.getOrDefault(busiest, List.of())))
                    .as("shard %d must return the same rows whether read alone or with seven others",
                        busiest)
                    .isEqualTo(seqsOf(alone.getOrDefault(busiest, List.of())));
        }
    }

    @Test
    void the_batched_head_sweep_returns_exactly_what_the_per_shard_sweeps_return() throws Exception {
        var shards = new int[SHARD_COUNT];
        for (var shard = 0; shard < SHARD_COUNT; shard++) {
            shards[shard] = shard;
        }
        try (var connection = dataSource.getConnection()) {
            var batched = storage.sweepOrderedFromHeads(connection, QUEUE_ID, shards, BATCH_SIZE);
            for (var shard : shards) {
                var expected = storage.sweepOrderedFromHead(connection, shard, BATCH_SIZE);
                assertThat(seqsOf(batched.getOrDefault(shard, List.of())))
                        .as("shard %d's batched sweep must match its per-shard sweep", shard)
                        .isEqualTo(seqsOf(expected));
            }
        }
    }

    @Test
    void the_batched_next_visible_matches_the_per_shard_answer() throws Exception {
        var delayedShard = ShardOwnedSchema.shardForKey("delayed-key", SHARD_COUNT);
        try (var connection = dataSource.getConnection()) {
            storage.enqueueOrderedBatch(connection, delayedShard,
                                        List.of(new OrderedPayload("delayed-key", 0,
                                                                   "later".getBytes(StandardCharsets.UTF_8), 1,
                                                                   java.time.Duration.ofSeconds(60))));

            var shards = new int[SHARD_COUNT];
            for (var shard = 0; shard < SHARD_COUNT; shard++) {
                shards[shard] = shard;
            }
            var batched = storage.millisUntilNextVisibleByShard(connection, QUEUE_ID, ShardOwnedSchema.ORDERED_TABLE, shards);

            for (var shard : shards) {
                var expected = storage.millisUntilNextVisible(connection, ShardOwnedSchema.ORDERED_TABLE, shard);
                if (expected.isPresent()) {
                    assertThat(batched.get(shard))
                            .as("shard %d has a delayed row, so the batched form must report one", shard)
                            .isNotNull();
                    // Both are server-computed intervals taken moments apart, so they differ by the
                    // time between the two statements rather than being equal.
                    assertThat(batched.get(shard)).isCloseTo(expected.getAsLong(), org.assertj.core.data.Offset.offset(2_000L));
                } else {
                    assertThat(batched.get(shard))
                            .as("shard %d has nothing delayed, so it must be absent rather than zero", shard)
                            .isNull();
                }
            }
        }
    }

    private int busiestShard() throws Exception {
        var busiest = 0;
        var most    = -1;
        try (var connection = dataSource.getConnection()) {
            for (var shard = 0; shard < SHARD_COUNT; shard++) {
                var count = storage.readOrderedFromCursor(connection, shard, 0L, BATCH_SIZE).size();
                if (count > most) {
                    most = count;
                    busiest = shard;
                }
            }
        }
        return busiest;
    }

    private static List<Long> seqsOf(List<ShardOwnedStorage.OrderedRow> rows) {
        return rows.stream().map(ShardOwnedStorage.OrderedRow::seq).toList();
    }

    private static List<String> keysOf(List<ShardOwnedStorage.OrderedRow> rows) {
        return rows.stream().map(ShardOwnedStorage.OrderedRow::key).toList();
    }
}
