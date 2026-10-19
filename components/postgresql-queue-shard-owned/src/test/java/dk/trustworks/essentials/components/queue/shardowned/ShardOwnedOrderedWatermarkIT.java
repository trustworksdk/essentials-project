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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ordered lane's cursor is a <em>safe watermark</em>: it does not step over a sequence value while
 * a write transaction that could still commit that value is running.
 * <p>
 * The mechanism it replaced chased each stepped-over value and wrote it off after {@code holeExpiry}.
 * That is a guess with a cliff on the far side of it — past the expiry the value is no longer chased
 * at all, and the only thing that still finds it is the head sweep, whose interval backs off to
 * {@code maxSweepInterval} on a quiet shard. These tests construct exactly that: an enqueue
 * transaction held open longer than {@code holeExpiry}, with the sweep set far enough out that it
 * cannot rescue the result and disguise a failure as a slow success.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedOrderedWatermarkIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;
    /** The cursor read's row limit in the truncated-read tests: smaller than the held writer's six rows. */
    private static final int   TRUNCATING_LIMIT = 5;

    /**
     * Deliberately hostile, and every value here is load-bearing.
     * <p>
     * {@code holeExpiry} of 200 ms is far shorter than the transaction the test holds open — under the
     * mechanism this replaced, that is long enough for the value to be written off and never looked at
     * again. Both sweep intervals are 20 s, so the backstop cannot quietly cover for that and let a
     * failure read as a slow success. {@code watermarkCap} is 30 s, comfortably longer than the test,
     * so the watermark has to be right rather than rescued by its own escape hatch.
     */
    private static final ShardOwnerSettings HOSTILE = new ShardOwnerSettings(500,
                                                                             200,
                                                                             Duration.ofMillis(1),
                                                                             Duration.ofMillis(2),
                                                                             Duration.ofMillis(200),   // holeExpiry
                                                                             Duration.ofSeconds(20),   // sweepInterval
                                                                             1_000,
                                                                             8,
                                                                             Duration.ofMillis(500),
                                                                             Duration.ofSeconds(20),   // maxSweepInterval
                                                                             2,
                                                                             Duration.ofSeconds(5),
                                                                             Duration.ofSeconds(30),
                                                                             Duration.ofSeconds(30));  // watermarkCap

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

    /**
     * The hazard, constructed rather than hoped for: a message whose enqueue transaction is still open
     * while a LATER message on the same shard commits and is delivered. The cursor has every
     * opportunity to step over the open one, and must not take it.
     */
    @Test
    void a_value_whose_transaction_is_still_open_is_not_stepped_over() throws Exception {
        var key      = "key-held";
        var shard    = ShardOwnedSchema.shardForKey(key, SHARD_COUNT);
        var received = new CopyOnWriteArrayList<String>();
        var storage  = new ShardOwnedStorage(dataSource, QUEUE_ID);

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((messageId, k, payload, payloadType) ->
                                                received.add(new String(payload, StandardCharsets.UTF_8)),
                                        HOSTILE,
                                        SHARD_COUNT);

            try (var held = dataSource.getConnection()) {
                // The outbox shape: the enqueue runs inside the caller's transaction, so the sequence
                // value is allocated now and the row becomes visible only at the caller's commit.
                held.setAutoCommit(false);
                var heldSeq = storage.enqueueOrderedBatch(held, shard,
                                                          List.of(new OrderedPayload(key, 0, "held".getBytes(StandardCharsets.UTF_8), 1)))
                                     .getFirst();

                // A later value on the same shard, on its own connection so it commits while the held
                // one does not. The held value is now a gap the cursor is being invited to pass.
                try (var committing = dataSource.getConnection()) {
                    storage.enqueueOrderedBatch(committing, shard,
                                                List.of(new OrderedPayload(key, 1, "after".getBytes(StandardCharsets.UTF_8), 1)));
                }

                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .until(() -> received.contains("after"));

                // Keep the shard BUSY while the transaction is held. Without this the owner simply
                // parks — it has nothing to do — and wakes only when the held transaction commits, at
                // which point every mechanism looks correct because the value is already visible by
                // the time anything re-examines the gap. A first version of this test made exactly
                // that mistake and passed against a deliberately broken cap. The traffic forces the
                // owner to keep re-deciding whether it may step over the gap, which is the decision
                // under test.
                var others = keysOnShard(shard, 15);
                for (var index = 0; index < others.size(); index++) {
                    try (var busy = dataSource.getConnection()) {
                        storage.enqueueOrderedBatch(busy, shard,
                                                    List.of(new OrderedPayload(others.get(index), 0,
                                                                               ("busy-" + index).getBytes(StandardCharsets.UTF_8), 1)));
                    }
                    Thread.sleep(100);
                }

                // Well past holeExpiry (200 ms), and the shard has been polled throughout.
                assertThat(received).as("the held message cannot be delivered before it is committed")
                                    .doesNotContain("held");
                // The discriminating assertion, taken at the moment of the hazard rather than inferred
                // afterwards: the owner's cursor is still below the open value while later values on
                // the same unit have committed and been delivered. A watermark that stepped over it
                // would sit at or above the "after" value here.
                assertThat(orderedCursor(queue, shard))
                        .as("the cursor must not step over seq %d while its transaction is open", heldSeq)
                        .isLessThan(heldSeq);

                held.commit();
            }

            // No sweep can rescue this: sweepInterval and maxSweepInterval are both 20 s, so a pass
            // here means the cursor read found it, not the backstop.
            Awaitility.await().atMost(Duration.ofSeconds(8))
                      .untilAsserted(() -> assertThat(received)
                              .as("the value must be delivered once its transaction commits, from the "
                                  + "cursor read rather than from the head sweep")
                              .contains("held"));

            var metrics = queue.metrics().snapshot();
            assertThat((Long) metrics.get("watermarkAdvances"))
                    .as("the watermark must actually have moved, or this test proves nothing: %s", metrics)
                    .isPositive();
            assertThat((Long) metrics.get("holesAbandoned"))
                    .as("the ordered lane no longer writes values off on a timer: %s", metrics)
                    .isZero();
            // Deliberately NOT sweepRecoveries == 0. That counter also counts a row that commits
            // between a pass's cursor statement and its sweep statement — two snapshots, so the sweep
            // sees it first although the cursor never passed it. Every unit sweeps on its first pass,
            // which is exactly when this test commits "after", and a loaded build widens that window
            // enough to hit it (reproduced with a 300 ms pause between the two statements: 3 of 3,
            // headSweeps=64, i.e. only the start-up sweep). QueueStatistics documents it as not a
            // defect by itself. Whether the cursor stepped over the value is asserted directly above,
            // before the commit.
            assertThat((Long) metrics.get("watermarkCapped"))
                    .as("the horizon, not the wall-clock cap, must be what held the cursor back: %s", metrics)
                    .isZero();
        }
    }

    /**
     * The everyday case must not pay for the hazard case. With no transaction held open the watermark
     * keeps up, so nothing is re-read for long and no message waits on the horizon.
     */
    @Test
    void ordinary_traffic_is_delivered_without_the_watermark_falling_behind() throws Exception {
        var received = new ConcurrentHashMap<String, List<Long>>();
        var keyCount = 20;
        var perKey   = 25;

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> received
                                                .computeIfAbsent(key, ignored -> Collections.synchronizedList(new ArrayList<>()))
                                                .add(Long.parseLong(new String(payload, StandardCharsets.UTF_8))),
                                        ShardOwnerSettings.defaults(),
                                        SHARD_COUNT);

            var messages = new ArrayList<OrderedPayload>();
            for (var keyIndex = 0; keyIndex < keyCount; keyIndex++) {
                for (var order = 0; order < perKey; order++) {
                    messages.add(new OrderedPayload("key-" + keyIndex, order,
                                                    Long.toString(order).getBytes(StandardCharsets.UTF_8), 1));
                }
            }
            queue.enqueueOrdered(messages);

            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(received.values().stream().mapToInt(List::size).sum())
                              .isEqualTo(keyCount * perKey));
            received.forEach((key, orders) -> assertThat(List.copyOf(orders))
                    .as("key %s must be delivered in key_order", key).isSorted());

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.orderedRemaining()).isZero());

            var metrics = queue.metrics().snapshot();
            // The re-read window is what the watermark costs, and cursorReadsPerMessage is the honest
            // way to size it. maxWatermarkLagSeq cannot be: the ordered lane draws from ONE sequence
            // per queue, so an owner's span of sequence values covers the other shards' rows too and
            // reads as the whole batch however little each owner actually re-reads. An earlier version
            // of this assertion used it and failed the moment the sequences were collapsed.
            // The engine's own gate, not a stricter one: cursorReadsPerMessage counts owners SERVED
            // per message, so it rises with the number of units an instance holds and a sub-1.0 bound
            // measures the unit count rather than the re-read cost. 2.0 is the figure the per-shard
            // wake-up was introduced to reach (14.5 before it).
            assertThat((Double) metrics.get("cursorReadsPerMessage"))
                    .as("re-reading from the watermark must not undo the per-shard wake-up: %s", metrics)
                    .isLessThan(2.0d);
            assertThat((Long) metrics.get("watermarkAdvances"))
                    .as("the watermark must keep up rather than stall: %s", metrics)
                    .isPositive();
            assertThat((Long) metrics.get("watermarkCapped"))
                    .as("no advance should need the wall-clock cap when nothing holds a transaction "
                        + "open: %s", metrics)
                    .isZero();
        }
    }

    /**
     * A pass whose cursor read returned as many rows as its limit may have stopped short of the backlog, so the watermark
     * advances no further than the last row that read returned. This drives one owner by hand with a limit of 5 through
     * the step-over that cap exists for: a writer's rows committing below values a pass already saw, filling the next
     * reads, and the writer's retirement then due while the read is still short of the candidate's {@code maxSeen}.
     * Without the cap the watermark jumps to that {@code maxSeen}, over the writer's sixth row, which the cursor has
     * never read and only the head sweep would find.
     */
    @Test
    void a_read_that_hit_the_owners_limit_holds_the_watermark_at_its_last_row() throws Exception {
        assertNotSteppedOverByATruncatedRead(byHand(TRUNCATING_LIMIT), (owner, connection, storage) -> owner.pumpOnce(connection));
    }

    /**
     * The same step-over, with the rows read by the pump: its batched read is issued with the runtime's
     * {@code readBatchSize}, which can be smaller than the queue's own (a runtime keeps its first borrower's settings, and a
     * queue given a runtime keeps its own). The owner judged "the read hit its limit" against its own 500, so a batch
     * truncated at 5 passed for a complete read and the watermark stepped over the row past it. Each pass here does what
     * {@code ShardPump.batchReadQueue} does: one {@code readOrderedFromCursors} with the pump's limit, handed to the owner
     * with that limit, then the owner's pass.
     */
    @Test
    void a_batched_read_that_hit_the_pumps_smaller_limit_holds_the_watermark_at_its_last_row() throws Exception {
        assertNotSteppedOverByATruncatedRead(byHand(500), (owner, connection, storage) -> {
            var rows = storage.readOrderedFromCursors(connection, QUEUE_ID, new int[]{owner.shard()}, new long[]{owner.batchReadCursor()},
                                                      TRUNCATING_LIMIT);
            owner.applyBatchRead(rows.getOrDefault(owner.shard(), List.of()), TRUNCATING_LIMIT, null, OptionalLong.empty());
            return owner.pumpOnce(connection);
        });
    }

    /**
     * A candidate retires against the probe taken BEFORE the pass's cursor read, never the one taken after it. This builds
     * the window between the two without relying on timing: the rows a pass applies are read while a writer is open, the
     * writer then commits, and only then does the pass run, so the probe after the read no longer has the writer although
     * the read never returned its row. {@code applyBatchRead} is the seam that lets the read and the pass be separated,
     * as the pump's batched read separates them. Retiring on the after-read probe moves the watermark over the writer's
     * row on that pass, every time; {@code ShardOwnedConcurrentProducersOrderIT} reaches the same window only under load.
     */
    @Test
    void a_writer_committing_between_the_read_and_the_probe_is_not_stepped_over() throws Exception {
        var shard     = 0;
        var storage   = new ShardOwnedStorage(dataSource, QUEUE_ID);
        var delivered = new CopyOnWriteArrayList<String>();
        var metrics   = new ShardOwnerMetrics();
        var settings  = byHand(500);
        var grant     = storage.acquireLeaseGrant("ordered", shard, "instance-1", 60_000L).orElseThrow();
        var owner = new OrderedShardOwner(storage, shard, grant.fence(), settings,
                                          (id, key, payload, type) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                          metrics, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3), "instance-1", new ShardWakeup(),
                                          HandlerDispatch.inline(), grant.previousOwnerReleased());
        var keys = keysOnShard(shard, 2);

        try (var pumping = dataSource.getConnection();
             var held = dataSource.getConnection()) {
            owner.onTakeover(pumping);

            // Writer W allocates s1 and stays open; writer V commits s2 above it.
            held.setAutoCommit(false);
            var heldSeq = storage.enqueueOrderedBatch(held, shard,
                                                      List.of(new OrderedPayload(keys.get(0), 0, "held".getBytes(StandardCharsets.UTF_8), 1)))
                                 .getFirst();
            long laterSeq;
            try (var committing = dataSource.getConnection()) {
                laterSeq = storage.enqueueOrderedBatch(committing, shard,
                                                       List.of(new OrderedPayload(keys.get(1), 0, "later".getBytes(StandardCharsets.UTF_8), 1)))
                                  .getFirst();
            }
            assertThat(heldSeq).as("premise: the held row sits below the committed one").isLessThan(laterSeq);

            // Pass 1 reads "later" and records a candidate at its seq, with W in the candidate's running set.
            owner.pumpOnce(pumping);
            assertThat(delivered).as("premise: the committed row is read while W is open").containsExactly("later");
            assertThat(metrics.maxWatermarkLagSeq.get()).as("premise: pass 1 recorded a candidate above the watermark").isPositive();
            assertThat(storage.runningWriteTransactionIds(pumping))
                    .as("premise: W holds an xid the horizon probe sees, so pass 1's candidate records W as running")
                    .contains(backendXidOf(pumping, held));

            // Pass 2's read, taken while W is still open: it returns "later" again (acknowledgements are never flushed,
            // see byHand) and not "held". W commits after the read and before the pass, which is the window under test.
            var readWhileWOpen = storage.readOrderedFromCursor(pumping, shard, owner.batchReadCursor(), settings.readBatchSize());
            assertThat(readWhileWOpen).as("premise: the read did not see W's row").extracting(ShardOwnedStorage.OrderedRow::seq)
                                      .doesNotContain(heldSeq);
            held.commit();
            owner.applyBatchRead(readWhileWOpen, settings.readBatchSize(), null, OptionalLong.empty());
            owner.pumpOnce(pumping);

            // The discriminating assertion. The probe before pass 2's read still had W, so the candidate stays. The
            // probe after it no longer does; retiring on that one would put the watermark at "later", over "held",
            // which no read has returned and which only the head sweep (20 s away) could find.
            assertThat(owner.batchReadCursor())
                    .as("W committed after pass 2's read returned without its seq %d; the watermark must not pass it to "
                        + "the candidate's maxSeen %d", heldSeq, laterSeq)
                    .isLessThan(heldSeq);

            // Pass 3 reads from the watermark, finds "held" through the cursor, and retires the candidate.
            owner.pumpOnce(pumping);
            assertThat(delivered).as("W's row delivered by the cursor read").containsExactly("later", "held");
            assertThat(owner.batchReadCursor()).isEqualTo(laterSeq);
            assertThat(metrics.sweepRecoveries.sum()).as("nothing was left for the head sweep to recover").isZero();
            assertThat(metrics.watermarkCapped.sum()).as("the horizon, not the wall-clock cap, moved the watermark").isZero();
        }
    }

    /**
     * The wall-clock cap is counted once per candidate it advances the watermark for, not once per pass. A candidate the
     * cap took while the read was full advances only as far as that read reached and stays at the head of the deque, so
     * while a backlog keeps every read full the same expired candidate moves the watermark again on each pass. Counted
     * on each of those passes, one long writer read as several cap events on {@code watermarkCapped}. Here a writer that
     * never commits keeps one candidate unretired past a 2 s cap while twelve rows committed below its {@code maxSeen}
     * are read five at a time: three passes advance the watermark on that one candidate, and the cap is counted once.
     */
    @Test
    void a_capped_candidate_advanced_over_several_full_reads_is_counted_once() throws Exception {
        var shard     = 0;
        var storage   = new ShardOwnedStorage(dataSource, QUEUE_ID);
        var delivered = new CopyOnWriteArrayList<String>();
        var metrics   = new ShardOwnerMetrics();
        var cap       = Duration.ofSeconds(2);
        var grant     = storage.acquireLeaseGrant("ordered", shard, "instance-1", 60_000L).orElseThrow();
        var owner = new OrderedShardOwner(storage, shard, grant.fence(), byHand(TRUNCATING_LIMIT, cap),
                                          (id, key, payload, type) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                          metrics, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3), "instance-1", new ShardWakeup(),
                                          HandlerDispatch.inline(), grant.previousOwnerReleased());
        var keys = keysOnShard(shard, 14);

        try (var pumping = dataSource.getConnection();
             var longWriter = dataSource.getConnection();
             var held = dataSource.getConnection()) {
            owner.onTakeover(pumping);

            // The long writer: an xid and nothing else, open for the whole test. It is what keeps the candidate unretired.
            longWriter.setAutoCommit(false);
            try (var statement = longWriter.prepareStatement("SELECT pg_current_xact_id()")) {
                statement.executeQuery().close();
            }

            // Twelve rows allocated and held invisible, then two committed above them.
            held.setAutoCommit(false);
            var heldPayloads = new ArrayList<OrderedPayload>();
            for (var index = 0; index < 12; index++) {
                heldPayloads.add(new OrderedPayload(keys.get(index), 0, ("held-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            var heldSeqs = storage.enqueueOrderedBatch(held, shard, heldPayloads);
            List<Long> laterSeqs;
            try (var committing = dataSource.getConnection()) {
                laterSeqs = storage.enqueueOrderedBatch(committing, shard,
                                                        List.of(new OrderedPayload(keys.get(12), 0, "later-0".getBytes(StandardCharsets.UTF_8), 1),
                                                                new OrderedPayload(keys.get(13), 0, "later-1".getBytes(StandardCharsets.UTF_8), 1)));
            }
            assertThat(heldSeqs.getLast()).as("premise: the held rows sit below the committed ones").isLessThan(laterSeqs.getFirst());

            // Pass 1 reads the two committed rows and records the candidate at their maxSeen, the long writer running.
            owner.pumpOnce(pumping);
            assertThat(delivered).as("premise: the committed rows are read while the held rows are invisible")
                                 .containsExactlyInAnyOrder("later-0", "later-1");
            assertThat(metrics.maxWatermarkLagSeq.get()).as("premise: pass 1 recorded a candidate above the watermark").isPositive();
            assertThat(storage.runningWriteTransactionIds(pumping))
                    .as("premise: the long writer holds an xid the horizon probe sees, so the candidate records it as running")
                    .contains(backendXidOf(pumping, longWriter));

            held.commit();
            Thread.sleep(cap.toMillis() + 200);

            // Passes 2 and 3: each read is full (5 of the 12 held rows), the candidate has expired and the long writer still
            // runs, so the cap moves the watermark to the last row read and the candidate stays at the head of the deque.
            owner.pumpOnce(pumping);
            assertThat(owner.batchReadCursor()).as("premise: the cap advanced the watermark to the full read's last row")
                                               .isEqualTo(heldSeqs.get(4));
            assertThat(metrics.watermarkCapped.sum()).as("premise: the cap fired").isEqualTo(1L);
            owner.pumpOnce(pumping);
            assertThat(owner.batchReadCursor()).as("premise: the same capped candidate advanced the watermark again")
                                               .isEqualTo(heldSeqs.get(9));

            // Pass 4: the read is short (4 rows), so the candidate is taken whole and leaves the deque.
            owner.pumpOnce(pumping);
            assertThat(owner.batchReadCursor()).isEqualTo(laterSeqs.getLast());
            assertThat(delivered).as("every row delivered, by the cursor read").hasSize(14);
            assertThat(metrics.watermarkAdvances.sum()).as("each of the three passes moved the watermark").isEqualTo(3L);
            // The discriminating assertion: one candidate, one cap event, however many passes it took to advance past it.
            assertThat(metrics.watermarkCapped.sum())
                    .as("one candidate taken on the cap, advanced over three passes, is one cap event")
                    .isEqualTo(1L);
            longWriter.rollback();
        }
    }

    /** The xid {@code writer}'s backend holds, as {@code pg_stat_activity.backend_xid} shows it to {@code observer}; fails if it holds none. */
    private static long backendXidOf(Connection observer, Connection writer) throws SQLException {
        int pid;
        try (var statement = writer.prepareStatement("SELECT pg_backend_pid()");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            pid = resultSet.getInt(1);
        }
        try (var statement = observer.prepareStatement("SELECT backend_xid::text::bigint FROM pg_stat_activity WHERE pid = ? AND backend_xid IS NOT NULL")) {
            statement.setInt(1, pid);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("backend %d holds no xid", pid).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    @FunctionalInterface
    private interface Pass {
        int run(OrderedShardOwner owner, Connection connection, ShardOwnedStorage storage) throws Exception;
    }

    private void assertNotSteppedOverByATruncatedRead(ShardOwnerSettings settings, Pass pass) throws Exception {
        var shard     = 0;
        var storage   = new ShardOwnedStorage(dataSource, QUEUE_ID);
        var delivered = new CopyOnWriteArrayList<String>();
        var metrics   = new ShardOwnerMetrics();
        var grant     = storage.acquireLeaseGrant("ordered", shard, "instance-1", 60_000L).orElseThrow();
        var owner = new OrderedShardOwner(storage, shard, grant.fence(), settings,
                                          (id, key, payload, type) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                          metrics, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3), "instance-1", new ShardWakeup(),
                                          HandlerDispatch.inline(), grant.previousOwnerReleased());
        var keys = keysOnShard(shard, 8);

        try (var pumping = dataSource.getConnection();
             var held = dataSource.getConnection()) {
            owner.onTakeover(pumping);

            // Six rows allocated, invisible until the commit below, then two committed above them.
            held.setAutoCommit(false);
            var heldPayloads = new ArrayList<OrderedPayload>();
            for (var index = 0; index < 6; index++) {
                heldPayloads.add(new OrderedPayload(keys.get(index), 0, ("held-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            var heldSeqs = storage.enqueueOrderedBatch(held, shard, heldPayloads);
            List<Long> laterSeqs;
            try (var committing = dataSource.getConnection()) {
                laterSeqs = storage.enqueueOrderedBatch(committing, shard,
                                                        List.of(new OrderedPayload(keys.get(6), 0, "later-0".getBytes(StandardCharsets.UTF_8), 1),
                                                                new OrderedPayload(keys.get(7), 0, "later-1".getBytes(StandardCharsets.UTF_8), 1)));
            }
            assertThat(heldSeqs.getLast()).as("premise: the held rows sit below the committed ones").isLessThan(laterSeqs.getFirst());

            // Pass 1 reads the two committed rows and records a candidate at their maxSeen, the held writer running.
            pass.run(owner, pumping, storage);
            assertThat(delivered).as("premise: the committed rows are read while the writer is open")
                                 .containsExactlyInAnyOrder("later-0", "later-1");
            assertThat(metrics.maxWatermarkLagSeq.get()).as("premise: pass 1 recorded a candidate above the watermark").isPositive();
            assertThat(storage.runningWriteTransactionIds(pumping))
                    .as("premise: the held writer holds an xid the horizon probe sees, so pass 1's candidate records it as running")
                    .contains(backendXidOf(pumping, held));

            held.commit();

            // Pass 2: the held rows fill the read (5 of 6). The candidate is not retired yet - the probe before this
            // read still saw the writer. Every pass from here re-reads the same five: acknowledgements are never
            // flushed (byHand), so the rows stay in the table.
            pass.run(owner, pumping, storage);
            assertThat(owner.batchReadCursor()).isLessThanOrEqualTo(heldSeqs.get(4));

            // Pass 3: the probe before this read no longer has the writer, so the candidate retires - while the read,
            // full at 5 rows, never reached heldSeqs[5]. The discriminating assertion.
            pass.run(owner, pumping, storage);
            assertThat(owner.batchReadCursor())
                    .as("a read that returned its limit of %d rows reached seq %d; the watermark must not pass it to the "
                        + "candidate's maxSeen %d, over seq %d that no read has returned", TRUNCATING_LIMIT, heldSeqs.get(4),
                        laterSeqs.getLast(), heldSeqs.get(5))
                    .isEqualTo(heldSeqs.get(4));

            // Pass 4 reads on from there and finds the sixth row - through the cursor, not the head sweep.
            pass.run(owner, pumping, storage);
            assertThat(delivered).as("every row delivered, by the cursor read").contains("held-5").hasSize(8);
            assertThat(owner.batchReadCursor()).isEqualTo(laterSeqs.getLast());
            assertThat(metrics.sweepRecoveries.sum()).as("nothing was left for the head sweep to recover").isZero();
            assertThat(metrics.watermarkCapped.sum()).as("the horizon, not the wall-clock cap, moved the watermark").isZero();
        }
    }

    /**
     * For an owner driven pass by pass: an acknowledgement flush interval and batch size no pass reaches, so a delivered row
     * stays in the table and every read from the watermark returns it again, and a 20 s sweep interval, so the head sweep
     * runs at most on the first pass and cannot recover a row the watermark stepped over. {@code watermarkCap} is 30 s,
     * longer than the test.
     */
    private static ShardOwnerSettings byHand(int readBatchSize) {
        return byHand(readBatchSize, Duration.ofSeconds(30));
    }

    /** {@link #byHand(int)} with the given {@code watermarkCap}, for a test that wants the cap to fire. */
    private static ShardOwnerSettings byHand(int readBatchSize, Duration watermarkCap) {
        return new ShardOwnerSettings(readBatchSize, 1_000, Duration.ofDays(36_500), Duration.ofMillis(2), Duration.ofMillis(200),
                                      Duration.ofSeconds(20), 1_000, 8, Duration.ofMillis(500), Duration.ofSeconds(20), 2,
                                      Duration.ofSeconds(5), Duration.ofSeconds(30), watermarkCap);
    }

    /**
     * The safe cursor of the owner serving {@code unit}, found among the queue's owners
     * ({@link ShardOwnedQueue#owners()}). The cursor is written by the pump thread without a barrier,
     * so a stale read can only be an OLDER, lower value — it could mask a step-over, never invent one.
     */
    private static long orderedCursor(ShardOwnedQueue queue, int unit) {
        return queue.owners().stream()
                     .filter(owner -> owner instanceof OrderedShardOwner && owner.shard() == unit && owner.leaseHeld())
                     .map(owner -> ((OrderedShardOwner) owner).batchReadCursor())
                     .findFirst()
                     .orElseThrow(() -> new AssertionError("no ordered owner holds unit " + unit));
    }

    /** Keys that hash to a given shard, so the traffic lands where the held value is. */
    private static List<String> keysOnShard(int shard, int count) {
        var keys = new ArrayList<String>(count);
        for (var candidate = 0; keys.size() < count; candidate++) {
            var key = "busy-key-" + candidate;
            if (ShardOwnedSchema.shardForKey(key, SHARD_COUNT) == shard) {
                keys.add(key);
            }
        }
        return keys;
    }
}
