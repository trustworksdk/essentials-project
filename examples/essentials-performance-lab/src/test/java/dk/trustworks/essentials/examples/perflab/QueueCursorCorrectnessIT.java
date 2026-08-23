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

import dk.trustworks.essentials.examples.perflab.queuedesign.QueueSchemaPrototype;
import org.jdbi.v3.core.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Establishes, against the real SQL rather than by reading it, that the measured per-key cursor prototype is
 * <b>not correct</b> — and that the corrected variant fixes both faults.
 *
 * <h2>Why this exists</h2>
 * The cursor arm produced the largest number in the whole queue investigation: 4.0x on the ordered claim,
 * 2.64x end to end. Those figures were measured on
 * {@link QueueSchemaPrototype#claimOrderedViaCursorSql} and
 * {@link QueueSchemaPrototype#ackOrderedViaCursorSql}, which have two defects that the measuring harness
 * cannot expose because it is single-connection with claim and acknowledge strictly alternating — nothing is
 * ever in flight at the moment a claim runs, and no message is ever retried or dead-lettered mid-drain.
 * <p>
 * A design decision worth weeks of implementation should not rest on an argument from reading SQL, in either
 * direction. So each fault is reproduced here as a failing case for the measured statement and a passing case
 * for the corrected one, using the same fixture for both so the difference is the statement and nothing else.
 */
@Testcontainers(disabledWithoutDocker = true)
class QueueCursorCorrectnessIT {

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials");

    private static Jdbi jdbi;

    private String messageTable;
    private String keyStateTable;

    @BeforeAll
    static void startContainer() {
        postgres.start();
        jdbi = Jdbi.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @BeforeEach
    void createSchema(TestInfo testInfo) {
        var suffix = "cur" + Math.abs(testInfo.getDisplayName().hashCode());
        messageTable = "m_" + suffix;
        keyStateTable = "k_" + suffix;
        jdbi.useHandle(handle -> {
            handle.execute("DROP TABLE IF EXISTS " + messageTable);
            handle.execute("DROP TABLE IF EXISTS " + keyStateTable);
            QueueSchemaPrototype.cursorOrderedTableDdl(messageTable, 100).forEach(handle::execute);
            QueueSchemaPrototype.cursorKeyStateDdl(keyStateTable).forEach(handle::execute);
        });
    }

    /**
     * Fault 1, as measured: while a key's next message is in flight, the claim hands out the one after it.
     * <p>
     * The {@code is_being_delivered = FALSE} filter sits inside the per-key LATERAL lookup, so the in-flight
     * row is skipped rather than blocking, and the cursor has not moved — the lookup therefore returns the
     * successor. Two worker threads on a single node are enough to reach this; it is not a multi-node-only
     * concern.
     */
    @Test
    void measured_cursor_claim_releases_a_successor_while_its_predecessor_is_in_flight() {
        givenOrderedMessages("key-a", 0, 1, 2);

        var firstClaim = claim(QueueSchemaPrototype.claimOrderedViaCursorSql(messageTable, keyStateTable), 10);
        assertThat(firstClaim).as("the first claim takes the key's lowest order").containsExactly("key-a#0");

        // No acknowledgement, so order 0 is still in flight and the cursor is still -1.
        var secondClaim = claim(QueueSchemaPrototype.claimOrderedViaCursorSql(messageTable, keyStateTable), 10);
        assertThat(secondClaim).as("the measured statement releases order 1 while order 0 is still being handled, "
                                           + "which violates per-key ordering")
                               .containsExactly("key-a#1");
    }

    /**
     * The same fixture against the corrected claim: the key yields nothing at all while it has work in flight.
     */
    @Test
    void safe_cursor_claim_yields_nothing_for_a_key_that_has_a_message_in_flight() {
        givenOrderedMessages("key-a", 0, 1, 2);

        var firstClaim = claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10);
        assertThat(firstClaim).containsExactly("key-a#0");

        var secondClaim = claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10);
        assertThat(secondClaim).as("a key with a message in flight must yield nothing until it is acknowledged")
                               .isEmpty();
    }

    /**
     * Fault 2, as measured: acknowledging across a dead-lettered predecessor advances the cursor past it, and
     * because the claim only looks above the cursor the skipped message becomes permanently invisible.
     * <p>
     * This is message loss rather than reordering, and it is a property the {@code NOT EXISTS} barrier has for
     * free — a dead-lettered predecessor simply keeps blocking its successors.
     */
    @Test
    void measured_cursor_ack_advances_past_a_dead_lettered_message_and_loses_it() {
        givenOrderedMessages("key-a", 5, 6, 7);
        deadLetter("key-a", 6);

        // Orders 5 and 7 are handled; 6 is dead-lettered and still present.
        acknowledge(QueueSchemaPrototype.ackOrderedViaCursorSql(messageTable, keyStateTable), List.of("key-a#5", "key-a#7"));

        assertThat(completedThrough("key-a")).as("the measured statement advances to MAX(key_order) = 7, past the "
                                                        + "dead-lettered order 6")
                                             .isEqualTo(7L);

        // Resurrecting order 6 is now futile: the claim only looks above the cursor, so it can never be seen
        // again. That is the loss.
        resurrect("key-a", 6);
        var afterResurrect = claim(QueueSchemaPrototype.claimOrderedViaCursorSql(messageTable, keyStateTable), 10);
        assertThat(afterResurrect).as("order 6 is permanently invisible once the cursor has passed it")
                                  .isEmpty();
    }

    /**
     * The same fixture against the corrected acknowledgement: the cursor is clamped so it never passes a row
     * still present for the key, so resurrecting the dead letter puts it back in line.
     */
    @Test
    void safe_cursor_ack_stops_below_a_dead_lettered_message_so_it_can_still_be_delivered() {
        givenOrderedMessages("key-a", 5, 6, 7);
        deadLetter("key-a", 6);

        // One acknowledgement per key, which is the only shape the exclusive claim can produce: the row stays
        // is_being_delivered until its acknowledgement is flushed, so the key yields nothing else until then.
        // See the invariant test below for why this matters.
        acknowledge(QueueSchemaPrototype.ackOrderedViaSafeCursorSql(messageTable, keyStateTable), List.of("key-a#5"));

        assertThat(completedThrough("key-a")).as("the corrected statement clamps to just below the lowest row still "
                                                        + "present for the key")
                                             .isEqualTo(5L);

        resurrect("key-a", 6);
        var afterResurrect = claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10);
        assertThat(afterResurrect).as("a resurrected dead letter is delivered rather than skipped")
                                  .containsExactly("key-a#6");
    }

    /**
     * The prefix property is now a <b>requirement</b>, not a nicety, and this pins the consequence of breaking
     * it.
     * <p>
     * The acknowledgement scans {@code (cursor, min_acknowledged)} so that a run can advance the cursor across
     * its whole length. That makes it sound only for prefix batches — which is all
     * {@code claimOrderedRunViaSafeCursorSql} produces. Hand it a non-prefix batch, orders 5 and 7 with 6
     * dead-lettered between them, and it advances to 7 and skips 6.
     * <p>
     * An earlier formulation bounded the scan by {@code max_acknowledged} instead, which was safe for any batch
     * but could not advance a run at all: every row in the interval was one being deleted, so the clamp pulled
     * back to the old cursor and the gap scan grew from a stale value. Independent safety and useful runs are
     * mutually exclusive here, so the coupling is the deliberate choice — and any future ordered
     * acknowledgement path must preserve the prefix property.
     */
    @Test
    void a_non_prefix_ack_batch_skips_a_blocked_message_which_is_why_the_claim_must_produce_prefixes() {
        givenOrderedMessages("key-a", 5, 6, 7);
        deadLetter("key-a", 6);

        acknowledge(QueueSchemaPrototype.ackOrderedViaSafeCursorSql(messageTable, keyStateTable), List.of("key-a#5", "key-a#7"));

        assertThat(completedThrough("key-a")).as("a non-prefix batch advances past the dead-lettered order 6 - the "
                                                        + "documented consequence of breaking the coupling, not desired behaviour")
                                             .isEqualTo(7L);
    }

    /**
     * A late acknowledgement must not drag a cursor backwards and re-deliver everything after it. The measured
     * statement assigns unconditionally; the corrected one takes the greater of current and new.
     */
    @Test
    void safe_cursor_ack_never_moves_a_cursor_backwards() {
        givenOrderedMessages("key-a", 1, 2, 3);
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE " + keyStateTable + " SET completed_through = 3 WHERE key = 'key-a'").execute());

        acknowledge(QueueSchemaPrototype.ackOrderedViaSafeCursorSql(messageTable, keyStateTable), List.of("key-a#1"));

        assertThat(completedThrough("key-a")).isEqualTo(3L);
    }

    /**
     * The sharp edge of the cursor design: a key with messages but <b>no cursor row is invisible to a cursor
     * pod entirely</b>. Not delayed, not dead-lettered — never claimed.
     * <p>
     * The claim drives from the key-state table, so a key absent from it contributes no candidate. This is the
     * failure mode a rolling deploy walks into: an old pod enqueues ordered messages without creating cursor
     * rows, and while any barrier pod survives those keys still get handled, so nothing looks wrong. Once the
     * fleet is fully migrated they are stranded silently. A one-off backfill before the deploy cannot close the
     * window, because the window <em>is</em> the deploy.
     */
    @Test
    void a_key_with_no_cursor_row_is_invisible_to_a_cursor_pod() {
        givenOrderedMessagesWithoutCursorRow("orphan-key", 0, 1, 2);

        var claimed = claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10);

        assertThat(claimed).as("the cursor claim drives from key-state, so a key with no row yields nothing at all")
                           .isEmpty();
    }

    /**
     * And the recovery: the idempotent reconciliation statement gives the orphaned key a cursor row, after
     * which it is claimed normally from its lowest order.
     * <p>
     * This is what makes the rollout safe without operator involvement — run it when a claim comes back empty
     * and the fleet converges on its own. It is bounded by the number of distinct keys rather than by the
     * backlog, so it is cheap enough to run repeatedly.
     */
    @Test
    void reconciliation_recovers_a_key_that_was_enqueued_without_a_cursor_row() {
        givenOrderedMessagesWithoutCursorRow("orphan-key", 0, 1, 2);
        assertThat(claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10)).isEmpty();

        jdbi.useHandle(handle -> handle.createUpdate(QueueSchemaPrototype.reconcileKeyStateSql(keyStateTable, messageTable))
                                       .bind("queueName", "q")
                                       .execute());

        assertThat(claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10))
                .as("after reconciliation the orphaned key is claimed from its lowest order")
                .containsExactly("orphan-key#0");
    }

    /**
     * Reconciliation must never reset a key that is already making progress. It is intended to run repeatedly
     * on a live queue, so an {@code INSERT} that overwrote an existing cursor would redeliver every message the
     * key had already completed.
     */
    @Test
    void reconciliation_does_not_rewind_a_cursor_that_is_already_advanced() {
        givenOrderedMessages("key-a", 0, 1, 2);
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE " + keyStateTable + " SET completed_through = 1 WHERE key = 'key-a'").execute());

        jdbi.useHandle(handle -> handle.createUpdate(QueueSchemaPrototype.reconcileKeyStateSql(keyStateTable, messageTable))
                                       .bind("queueName", "q")
                                       .execute());

        assertThat(completedThrough("key-a")).as("an existing cursor must survive reconciliation untouched").isEqualTo(1L);
        assertThat(claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10))
                .as("and delivery resumes where it left off rather than replaying the key")
                .containsExactly("key-a#2");
    }

    /**
     * The enqueue-time upsert is the primary mechanism, with reconciliation only the net beneath it: a key
     * enqueued through it is claimable immediately, with no reconciliation pass at all.
     */
    @Test
    void a_key_enqueued_with_the_cursor_upsert_is_claimable_without_reconciliation() {
        givenOrderedMessagesWithoutCursorRow("fresh-key", 0, 1);
        jdbi.useHandle(handle -> handle.createUpdate(QueueSchemaPrototype.upsertKeyStateOnEnqueueSql(keyStateTable))
                                       .bind("queueName", "q")
                                       .bind("key", "fresh-key")
                                       .execute());

        assertThat(claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10))
                .containsExactly("fresh-key#0");
    }

    /**
     * The claim in §8's payoff argument, tested: <b>does the cursor actually unlock batched ordered
     * acknowledgement?</b>
     * <p>
     * The argument was that the barrier unblocks a key only when its predecessor's row physically disappears —
     * so deferring the delete stalls the key, measured at 0.82x — whereas the cursor <em>records</em>
     * completion and therefore should not care when the row is deleted.
     * <p>
     * It does care. Per-key exclusivity in the corrected cursor comes from {@code is_being_delivered}, and a
     * deferred acknowledgement leaves that flag set, so the key yields nothing until the flush. The stall is
     * identical to the barrier's; only the mechanism differs. Both are asserted here side by side so the
     * symmetry is explicit rather than inferred.
     * <p>
     * This is not a defect in either design. It follows from per-key ordering with at most one message in
     * flight: a key's successor may not be delivered until the predecessor's completion is durably recorded,
     * and any batching defers exactly that record. Ordered throughput per key is therefore bounded by one
     * committed round trip per message under both designs, and no cursor can change that.
     */
    @Test
    void deferring_an_ordered_ack_stalls_the_key_under_the_cursor_exactly_as_under_the_barrier() {
        givenOrderedMessages("key-a", 0, 1, 2);

        // Cursor: claim order 0, do not acknowledge - the handler has "finished" but the ack is buffered.
        var cursorFirst = claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10);
        assertThat(cursorFirst).containsExactly("key-a#0");
        assertThat(claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10))
                .as("cursor: the key is stalled while the acknowledgement is buffered")
                .isEmpty();

        // Barrier, same fixture and same buffered-ack situation, for comparison.
        jdbi.useHandle(handle -> handle.execute("UPDATE " + messageTable + " SET is_being_delivered = FALSE, next_delivery_ts = now()"));
        var barrierFirst = claim(QueueSchemaPrototype.claimOrderedSql(messageTable, true), 10);
        assertThat(barrierFirst).containsExactly("key-a#0");
        assertThat(claim(QueueSchemaPrototype.claimOrderedSql(messageTable, true), 10))
                .as("barrier: identically stalled - the cursor confers no advantage here")
                .isEmpty();
    }

    /**
     * Where the ordered acknowledgement win actually lives, and why it belongs to the cursor: <b>the barrier can
     * only ever hand over a key's single head, while the cursor can hand over a run.</b>
     * <p>
     * The barrier tests {@code NOT EXISTS (… key_order < mine)} per candidate row, so orders 1 and 2 are
     * ineligible while 0 is still present no matter how high the limit goes. The cursor tests
     * {@code key_order > completed_through}, a range, so the next N messages of the key come out of one index
     * scan.
     * <p>
     * That asymmetry is the payoff. One claimer owns a contiguous run, handles it in order, and acknowledges the
     * whole run in one statement and one transaction — so §7's 16.5x transaction saving reaches ordered traffic,
     * which under the barrier it cannot. Per-key exclusivity survives because a single claimer owns the run.
     */
    @Test
    void the_barrier_yields_only_a_keys_head_while_the_cursor_yields_a_run() {
        givenOrderedMessages("key-a", 0, 1, 2);

        // Barrier, limit 3: still only the head. Raising the limit buys nothing.
        assertThat(claim(QueueSchemaPrototype.claimOrderedSql(messageTable, true), 3))
                .as("the barrier is a per-row test, so a key can only ever yield its head")
                .containsExactly("key-a#0");

        // Reset, then the cursor with a run length of 3.
        jdbi.useHandle(handle -> handle.execute("UPDATE " + messageTable + " SET is_being_delivered = FALSE, next_delivery_ts = now()"));
        var run = claimRun(QueueSchemaPrototype.claimOrderedRunViaSafeCursorSql(messageTable, keyStateTable), 3, 10);
        // Sorted by the returned key_order, because UPDATE ... RETURNING does not preserve index order - this
        // run came back as 1,2,0 before the sort was added, and a consumer handling them as returned would
        // violate the ordering the design exists to preserve.
        assertThat(run).as("the cursor is a range test, so it hands over the key's next three")
                       .containsExactly("key-a#0", "key-a#1", "key-a#2");

        // And the run is acknowledged as one batch: one statement, one transaction, three messages.
        acknowledge(QueueSchemaPrototype.ackOrderedViaSafeCursorSql(messageTable, keyStateTable), run);
        assertThat(completedThrough("key-a")).as("the cursor advances across the whole run").isEqualTo(2L);
        assertThat(claimRun(QueueSchemaPrototype.claimOrderedRunViaSafeCursorSql(messageTable, keyStateTable), 3, 10)).isEmpty();
    }

    /**
     * A run must stop at a blocked message rather than stepping over it, or run-claiming would reintroduce the
     * skipping fault by another route: the run would hand a worker orders 5 and 7 with 6 dead-lettered between
     * them, and the worker would handle 7 before 6 was ever delivered.
     */
    @Test
    void a_run_stops_at_a_dead_lettered_message_instead_of_stepping_over_it() {
        givenOrderedMessages("key-a", 5, 6, 7);
        deadLetter("key-a", 6);

        var run = claimRun(QueueSchemaPrototype.claimOrderedRunViaSafeCursorSql(messageTable, keyStateTable), 5, 10);

        assertThat(run).as("the run must end at the dead-lettered order 6, not jump to 7")
                       .containsExactly("key-a#5");
    }

    // ---- fixture helpers ----

    /**
     * Inserts messages but deliberately skips seeding the cursor row, reproducing what an old pod's enqueue
     * leaves behind during a rolling deploy.
     */
    private void givenOrderedMessagesWithoutCursorRow(String key, long... orders) {
        jdbi.useHandle(handle -> {
            for (var order : orders) {
                handle.createUpdate(QueueSchemaPrototype.insertOrderedSql(messageTable, true))
                      .bind("id", key + "#" + order)
                      .bind("queueName", "q")
                      .bind("payload", "{}")
                      .bind("payloadType", "Test")
                      .bind("now", OffsetDateTime.now())
                      .bind("key", key)
                      .bind("keyOrder", order)
                      .execute();
            }
        });
    }


    private void givenOrderedMessages(String key, long... orders) {
        jdbi.useHandle(handle -> {
            for (var order : orders) {
                handle.createUpdate(QueueSchemaPrototype.insertOrderedSql(messageTable, true))
                      .bind("id", key + "#" + order)
                      .bind("queueName", "q")
                      .bind("payload", "{}")
                      .bind("payloadType", "Test")
                      .bind("now", OffsetDateTime.now())
                      .bind("key", key)
                      .bind("keyOrder", order)
                      .execute();
            }
            handle.createUpdate(QueueSchemaPrototype.seedKeyStateSql(keyStateTable, messageTable))
                  .bind("queueName", "q")
                  .execute();
        });
    }

    private void deadLetter(String key, long order) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE " + messageTable + " SET is_dead_letter_message = TRUE WHERE id = :id")
                                       .bind("id", key + "#" + order)
                                       .execute());
    }

    private void resurrect(String key, long order) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE " + messageTable
                                                             + " SET is_dead_letter_message = FALSE, is_being_delivered = FALSE, next_delivery_ts = :now WHERE id = :id")
                                       .bind("now", OffsetDateTime.now())
                                       .bind("id", key + "#" + order)
                                       .execute());
    }

    /**
     * Claims a run and returns it sorted by {@code key_order}, which is what a consumer must do:
     * {@code UPDATE … RETURNING} emits rows in executor order, not index order.
     */
    private List<String> claimRun(String sql, int runLength, int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(sql)
                                              .bind("queueName", "q")
                                              .bind("now", OffsetDateTime.now())
                                              .bind("runLength", runLength)
                                              .bind("limit", limit)
                                              .map((rs, ctx) -> new Object[]{rs.getString("id"), rs.getLong("key_order")})
                                              .list()
                                              .stream()
                                              .sorted(Comparator.comparingLong(row -> (Long) row[1]))
                                              .map(row -> (String) row[0])
                                              .toList());
    }

    private List<String> claim(String sql, int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(sql)
                                               .bind("queueName", "q")
                                               .bind("now", OffsetDateTime.now())
                                               .bind("limit", limit)
                                               .mapTo(String.class)
                                               .list());
    }

    private void acknowledge(String sql, List<String> ids) {
        jdbi.useHandle(handle -> handle.createUpdate(sql).bindList("ids", ids).execute());
    }

    private long completedThrough(String key) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT completed_through FROM " + keyStateTable + " WHERE key = :key")
                                               .bind("key", key)
                                               .mapTo(Long.class)
                                               .one());
    }
}
