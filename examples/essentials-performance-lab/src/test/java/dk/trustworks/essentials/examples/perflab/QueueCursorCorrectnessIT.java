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
     * What happens when several of a key's messages are acknowledged in one statement — which the exclusive
     * claim cannot produce, but a future batching implementation might.
     * <p>
     * The answer, established here rather than assumed, is that the cheap clamp degrades <b>conservatively</b>:
     * it under-advances rather than skipping. Acknowledging orders 5 and 7 together with 6 dead-lettered
     * between them leaves the cursor at 4, not 7. Nothing is lost — order 6 keeps blocking its key exactly as
     * the {@code NOT EXISTS} barrier would, and the key resumes when 6 is resurrected or deleted. The cost is
     * one wasted step, not a message.
     * <p>
     * This matters because it removes a constraint from the design. The interval scan reads the pre-DELETE
     * snapshot, so rows being acknowledged in the same statement still count as present and pull the clamp
     * down; that is why the earlier explicit anti-join against the acknowledged set was unnecessary. Removing
     * it took the acknowledge phase from 128s back to ~280ms at 50k messages. An ordered batching
     * implementation is therefore free to group by key or not — one message per key per statement is simply
     * more efficient, not a correctness requirement.
     */
    @Test
    void acknowledging_several_of_a_keys_messages_at_once_under_advances_rather_than_skipping() {
        givenOrderedMessages("key-a", 5, 6, 7);
        deadLetter("key-a", 6);

        acknowledge(QueueSchemaPrototype.ackOrderedViaSafeCursorSql(messageTable, keyStateTable), List.of("key-a#5", "key-a#7"));

        // 4, not 7: the interval (cursor, 7) still sees order 5 in the pre-DELETE snapshot, so the clamp stops
        // below it. Conservative, and safe.
        assertThat(completedThrough("key-a")).as("the clamp under-advances rather than skipping the dead-lettered order 6")
                                             .isEqualTo(4L);

        // And the proof that nothing was lost: resurrect 6 and it is delivered.
        resurrect("key-a", 6);
        assertThat(claim(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable), 10))
                .as("no message is lost - the resurrected dead letter is still reachable")
                .containsExactly("key-a#6");
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

    // ---- fixture helpers ----

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
