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

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The release charge both lanes compose ({@code ShardOwner.chargeInHandlerBeforeRelease},
 * {@code OrderedShardOwner.chargeKeysInHandlerBeforeRelease}), driven without a database: each message charged once, as soon
 * as its charge commits; the dead-letter moves retried until made; a {@code QueueObserver} that throws held until every move
 * is made, with nothing owed afterwards; and what was dead-lettered derived from the one record per message.
 */
class ReleaseChargeLedgerTest {

    private static final int SHARD = 7;

    @Test
    void each_message_is_charged_once_as_soon_as_its_charge_commits_and_a_new_candidate_alone_is_charged_later() throws Exception {
        var writes  = new FakeWrites(Map.of(1L, 1, 2L, 1, 3L, 1));
        var counts  = new ArrayList<Map<Long, Integer>>();
        var metrics = new ShardOwnerMetrics();
        var ledger  = ledger(writes, 3, metrics, counts);

        assertThat(ledger.owes(candidates(1L, 2L))).as("uncharged candidates are owed").isTrue();
        var first = ledger.charge(null, candidates(1L, 2L));
        var again = ledger.charge(null, candidates(1L, 2L));
        var later = ledger.charge(null, candidates(1L, 2L, 3L));

        assertThat(writes.chargeCalls).as("charged once each: a retry charges only what was not charged before")
                                      .containsExactly(Set.of(1L, 2L), Set.of(3L));
        assertThat(first.charged()).isEqualTo(2);
        assertThat(again.charged()).isEqualTo(2);
        assertThat(later.charged()).isEqualTo(3);
        assertThat(later.deadLettered()).as("below the limit nothing is dead-lettered").isEmpty();
        assertThat(writes.moveCalls).isEmpty();
        assertThat(metrics.takeoverAttemptBumps.sum()).isEqualTo(3);
        assertThat(counts).as("the owner is told each charged count, once").containsExactly(Map.of(1L, 1, 2L, 1), Map.of(3L, 1));
        assertThat(ledger.owes(candidates(1L, 2L, 3L))).as("nothing is owed once charged").isFalse();
    }

    @Test
    void a_row_the_charge_leaves_out_is_not_recorded_and_is_charged_by_a_later_call() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.leaveOut.add(2L);
        var ledger = ledger(writes, 3, new ShardOwnerMetrics(), new ArrayList<>());

        assertThat(ledger.charge(null, candidates(1L, 2L)).charged()).isEqualTo(1);
        assertThat(ledger.owes(candidates(1L, 2L))).as("a row gone or no longer the owner's at the charge is still a candidate").isTrue();
    }

    @Test
    void a_dead_letter_move_that_fails_is_retried_without_charging_again_until_it_is_made() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.failMoves = 1;
        var counts  = new ArrayList<Map<Long, Integer>>();
        var metrics = new ShardOwnerMetrics();
        var ledger  = ledger(writes, 1, metrics, counts);

        assertThatThrownBy(() -> ledger.charge(null, candidates(1L))).isInstanceOf(SQLException.class);

        assertThat(writes.chargeCalls).as("premise: the charge committed before the move failed").containsExactly(Set.of(1L));
        assertThat(counts).as("the charged count is handed over though the move failed").containsExactly(Map.of(1L, 1));
        assertThat(ledger.owes(candidates(1L))).as("the move is still owed").isTrue();
        assertThat(ledger.answer().charged()).isEqualTo(1);
        assertThat(ledger.answer().deadLettered()).isEmpty();
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isFalse();

        var answer = ledger.charge(null, candidates(1L));

        assertThat(writes.chargeCalls).as("the retry does not charge again").containsExactly(Set.of(1L));
        assertThat(writes.moveCalls).as("the move is retried").containsExactly(1L, 1L);
        assertThat(answer.deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::seq).containsExactly(1L);
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isTrue();
        assertThat(ledger.owes(candidates(1L))).as("nothing is owed once the move is made").isFalse();
        assertThat(metrics.deadLettered.sum()).isEqualTo(1);

        ledger.charge(null, candidates(1L));
        assertThat(writes.moveCalls).as("a made move is not made again").containsExactly(1L, 1L);
    }

    @Test
    void an_observer_that_throws_is_held_until_every_move_is_made_and_leaves_nothing_owed() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1, 2L, 1));
        var told   = new ArrayList<MessageId>();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                told.add(id);
                throw new IllegalStateException("observer of " + id.sequence());
            }
        });
        var ledger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { });

        assertThatThrownBy(() -> ledger.charge(null, candidates(Map.of(1L, "A", 2L, "B"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("observer of 1")
                .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage).containsExactly("observer of 2"));

        assertThat(writes.moveCalls).as("every move is made before the observer's failure is thrown").containsExactly(1L, 2L);
        assertThat(told).containsExactly(new MessageId(MessageId.Lane.ORDERED, SHARD, 1L), new MessageId(MessageId.Lane.ORDERED, SHARD, 2L));
        assertThat(metrics.deadLettered.sum()).as("each dead letter counted before the observer is told").isEqualTo(2);
        assertThat(ledger.owes(candidates(Map.of(1L, "A", 2L, "B")))).as("a throwing observer is not a failed charge").isFalse();
        assertThat(ledger.answer().deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::key).containsExactly("A", "B");
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isTrue();
        assertThat(ledger.deadLetteredWhileInHandler(2L)).isTrue();
    }

    @Test
    void a_move_that_finds_the_row_gone_is_made_and_dead_letters_nothing() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 2));
        writes.rowsGone.add(1L);
        var metrics = new ShardOwnerMetrics();
        var ledger  = ledger(writes, 2, metrics, new ArrayList<>());

        var answer = ledger.charge(null, candidates(1L));

        assertThat(writes.moveCalls).containsExactly(1L);
        assertThat(answer.charged()).isEqualTo(1);
        assertThat(answer.deadLettered()).isEmpty();
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isFalse();
        assertThat(ledger.owes(candidates(1L))).isFalse();
        assertThat(metrics.deadLettered.sum()).isZero();
    }

    @Test
    void nothing_charged_answers_none() {
        var ledger = ledger(new FakeWrites(Map.of()), 3, new ShardOwnerMetrics(), new ArrayList<>());

        assertThat(ledger.answer()).isEqualTo(ReleaseChargeLedger.Answer.NONE);
        assertThat(ledger.owes(Map.of())).isFalse();
    }

    private static ReleaseChargeLedger ledger(FakeWrites writes, int maxAttempts, ShardOwnerMetrics metrics, List<Map<Long, Integer>> counts) {
        return new ReleaseChargeLedger(MessageId.Lane.UNORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), maxAttempts), metrics,
                                       charged -> counts.add(Map.copyOf(charged)));
    }

    private static Map<Long, String> candidates(Long... seqs) {
        var candidates = new TreeMap<Long, String>();
        for (var seq : seqs) {
            candidates.put(seq, null);
        }
        return candidates;
    }

    private static Map<Long, String> candidates(Map<Long, String> keyBySeq) {
        return new TreeMap<>(keyBySeq);
    }

    /**
     * The owner's writes, recorded: each charge returns the count after the charge given for the row, unless it is left out.
     */
    private static final class FakeWrites implements ReleaseChargeLedger.Writes {
        final Map<Long, Integer> countAfterCharge;
        final Set<Long>          leaveOut    = new HashSet<>();
        final Set<Long>          rowsGone    = new HashSet<>();
        final List<Set<Long>>    chargeCalls = new ArrayList<>();
        final List<Long>         moveCalls   = new ArrayList<>();
        int                      failMoves;

        FakeWrites(Map<Long, Integer> countAfterCharge) {
            this.countAfterCharge = countAfterCharge;
        }

        @Override
        public Map<Long, Integer> chargeRowsInHandler(Connection connection, Collection<Long> seqs) {
            chargeCalls.add(Set.copyOf(seqs));
            var charged = new TreeMap<Long, Integer>();
            seqs.stream().filter(seq -> !leaveOut.contains(seq)).forEach(seq -> charged.put(seq, countAfterCharge.get(seq)));
            return charged;
        }

        @Override
        public boolean moveToDeadLetterIfOwned(Connection connection, long seq) throws SQLException {
            moveCalls.add(seq);
            if (failMoves > 0) {
                failMoves--;
                throw new SQLException("move refused");
            }
            return !rowsGone.contains(seq);
        }
    }
}
