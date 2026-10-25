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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

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
        var first = ledger.charge(null, () -> candidates(1L, 2L));
        var again = ledger.charge(null, () -> candidates(1L, 2L));
        var later = ledger.charge(null, () -> candidates(1L, 2L, 3L));

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

    /**
     * A message whose row the charge did not find while the owner still holds its lease - deleted through the admin API
     * while its handler ran - is settled: not counted as charged, not owed, and not asked for again. Left a candidate, every
     * later call re-issued the charge for it and {@link ReleaseChargeLedger#owes} stayed true.
     */
    @Test
    void a_row_the_charge_finds_gone_while_the_lease_is_held_is_settled_uncharged_and_not_asked_for_again() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.leaveOut.add(2L);
        var metrics = new ShardOwnerMetrics();
        var ledger  = ledger(writes, 3, metrics, new ArrayList<>());

        assertThat(ledger.charge(null, () -> candidates(1L, 2L)).charged()).as("only the row found is charged").isEqualTo(1);
        assertThat(writes.stillOwnsCalls).as("premise: a row left out is asked about").isEqualTo(1);
        assertThat(ledger.owes(candidates(1L, 2L))).as("a row gone is not owed").isFalse();

        var again = ledger.charge(null, () -> candidates(1L, 2L));

        assertThat(writes.chargeCalls).as("the row gone is not asked for again").containsExactly(Set.of(1L, 2L));
        assertThat(again.charged()).as("nor counted as charged").isEqualTo(1);
        assertThat(metrics.takeoverAttemptBumps.sum()).isEqualTo(1);
    }

    /**
     * A charge that leaves rows out because the owner's lease is lost - the fenced charge then charges nothing - keeps them
     * owed: the release after it finds the unit no longer the owner's.
     */
    @Test
    void a_row_the_charge_leaves_out_once_the_lease_is_lost_stays_owed_and_is_asked_for_by_a_later_call() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.leaveOut.add(1L);
        writes.owns = false;
        var ledger = ledger(writes, 3, new ShardOwnerMetrics(), new ArrayList<>());

        assertThat(ledger.charge(null, () -> candidates(1L)).charged()).isZero();
        assertThat(ledger.owes(candidates(1L))).as("a row left out with the lease lost is still owed").isTrue();

        writes.leaveOut.clear();
        writes.owns = true;
        assertThat(ledger.charge(null, () -> candidates(1L)).charged()).as("charged by a later call").isEqualTo(1);
        assertThat(writes.chargeCalls).containsExactly(Set.of(1L), Set.of(1L));
    }

    /**
     * A {@code QueueObserver} that throws when told of a dead letter, in a charge that also asked for a row no longer in the
     * table, leaves nothing owed, so the queue releases the unit as after any observer failure. With the row gone still owed,
     * the queue read the observer's failure as a failed charge and held the unit, retrying a release it should have made.
     */
    @Test
    void an_observer_that_throws_in_a_charge_that_found_a_row_gone_leaves_nothing_owed() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.leaveOut.add(2L);
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                throw new IllegalStateException("observer failure");
            }
        });
        var ledger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                             deadLettered -> { });

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B")))).hasMessage("observer failure");

        assertThat(writes.moveCalls).as("premise: the charged message was moved").containsExactly(1L);
        assertThat(ledger.owes(candidates(Map.of(1L, "A", 2L, "B")))).as("nothing owed: the observer's failure is not a failed charge").isFalse();
        assertThat(ledger.answer().charged()).isEqualTo(1);
        assertThat(ledger.answer().deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::key).containsExactly("A");
    }

    @Test
    void a_dead_letter_move_that_fails_is_retried_without_charging_again_until_it_is_made() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.failMoves = 1;
        var counts  = new ArrayList<Map<Long, Integer>>();
        var metrics = new ShardOwnerMetrics();
        var ledger  = ledger(writes, 1, metrics, counts);

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(1L))).isInstanceOf(SQLException.class);

        assertThat(writes.chargeCalls).as("premise: the charge committed before the move failed").containsExactly(Set.of(1L));
        assertThat(counts).as("the charged count is handed over though the move failed").containsExactly(Map.of(1L, 1));
        assertThat(ledger.owes(candidates(1L))).as("the move is still owed").isTrue();
        assertThat(ledger.answer().charged()).isEqualTo(1);
        assertThat(ledger.answer().deadLettered()).isEmpty();
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isEmpty();

        var answer = ledger.charge(null, () -> candidates(1L));

        assertThat(writes.chargeCalls).as("the retry does not charge again").containsExactly(Set.of(1L));
        assertThat(writes.moveCalls).as("the move is retried").containsExactly(1L, 1L);
        assertThat(answer.deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::seq).containsExactly(1L);
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isPresent();
        assertThat(ledger.owes(candidates(1L))).as("nothing is owed once the move is made").isFalse();
        assertThat(metrics.deadLettered.sum()).isEqualTo(1);

        ledger.charge(null, () -> candidates(1L));
        assertThat(writes.moveCalls).as("a made move is not made again").containsExactly(1L, 1L);
    }

    @Test
    void a_dead_letter_move_owed_is_owed_whatever_the_candidates_until_it_is_made() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.failMoves = 1;
        var ledger = ledger(writes, 1, new ShardOwnerMetrics(), new ArrayList<>());
        assertThat(ledger.owesDeadLetterMove()).as("nothing charged, nothing owed").isFalse();

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(1L))).isInstanceOf(SQLException.class);

        assertThat(ledger.owesDeadLetterMove()).as("the move of 1 is still to be made").isTrue();
        assertThat(ledger.owes(Map.of())).as("premise: owed with no candidate either").isTrue();

        ledger.charge(null, Map::of);

        assertThat(writes.moveCalls).as("premise: a call with no candidate makes the move").containsExactly(1L, 1L);
        assertThat(ledger.owesDeadLetterMove()).as("nothing owed once the move is made").isFalse();
    }

    /**
     * A release claimed once its check found nothing owed ({@link ReleaseChargeLedger#claimReleaseUnlessMoveOwed}) holds off
     * a charge that starts after it until every claim has ended: so no charge leaves a move owed between the check and the
     * release statement. Checked alone ({@link ReleaseChargeLedger#owesDeadLetterMove}), a charge on another thread started
     * after the check, its move failed, and the row was released with the move owed all the same. Claims are counted: the
     * charge waits for the last one.
     */
    @Test
    void a_charge_waits_for_every_release_claimed_before_it_and_no_release_is_claimed_while_the_move_it_left_is_owed() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.failMoves = 1;
        var ledger = ledger(writes, 1, new ShardOwnerMetrics(), new ArrayList<>());

        assertThat(ledger.claimReleaseUnlessMoveOwed()).as("nothing owed: a release is claimed").isTrue();
        assertThat(ledger.claimReleaseUnlessMoveOwed()).as("and a second release of the owner beside it").isTrue();
        var charge = CompletableFuture.runAsync(() -> assertThatThrownBy(() -> ledger.charge(null, () -> candidates(1L))).isInstanceOf(SQLException.class));

        assertThatThrownBy(() -> charge.get(300, TimeUnit.MILLISECONDS)).as("the charge waits for the claimed releases")
                                                                          .isInstanceOf(TimeoutException.class);
        assertThat(ledger.owesDeadLetterMove()).as("no move left owed while a release is claimed").isFalse();
        ledger.endReleaseClaim();
        assertThatThrownBy(() -> charge.get(300, TimeUnit.MILLISECONDS)).as("still waiting for the second claim")
                                                                          .isInstanceOf(TimeoutException.class);
        assertThat(ledger.owesDeadLetterMove()).isFalse();

        ledger.endReleaseClaim();
        charge.get(10, TimeUnit.SECONDS);

        assertThat(writes.chargeCalls).as("charged once the last claim ended").containsExactly(Set.of(1L));
        assertThat(ledger.owesDeadLetterMove()).as("premise: that charge left the move owed").isTrue();
        assertThat(ledger.claimReleaseUnlessMoveOwed()).as("no release is claimed while the move is owed").isFalse();
        assertThatThrownBy(ledger::endReleaseClaim).as("nothing was claimed").isInstanceOf(IllegalStateException.class);
    }

    /**
     * A release claimed while a charge runs waits for that charge, and is refused when the charge left a move owed.
     */
    @Test
    void a_release_claimed_while_a_charge_runs_waits_for_it_and_is_refused_when_it_left_a_move_owed() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.holdMoves();
        writes.failMoves = 1;
        var ledger = ledger(writes, 1, new ShardOwnerMetrics(), new ArrayList<>());

        var charge = CompletableFuture.runAsync(() -> assertThatThrownBy(() -> ledger.charge(null, () -> candidates(1L))).isInstanceOf(SQLException.class));
        assertThat(writes.moving.await(10, TimeUnit.SECONDS)).as("premise: the charge is moving the message").isTrue();
        var claim = CompletableFuture.supplyAsync(ledger::claimReleaseUnlessMoveOwed);

        assertThatThrownBy(() -> claim.get(300, TimeUnit.MILLISECONDS)).as("the claim waits for the running charge")
                                                                         .isInstanceOf(TimeoutException.class);
        writes.proceed.countDown();

        assertThat(claim.get(10, TimeUnit.SECONDS)).as("refused: the charge it waited for left the move owed").isFalse();
        charge.get(10, TimeUnit.SECONDS);
    }

    /**
     * A charge on a thread whose waits for the database are bounded ({@link NetworkWaitBound}) - a stop's budget thread, the
     * hand-back of a kept unit, a release on request - waits for a release claimed before it no longer than that bound, then
     * throws with nothing charged and nothing recorded, with an SQL state outside class {@code 08}, as nothing was sent. A
     * release statement on a heartbeat tick runs with no bound, so waiting for its claim unbounded held such a charge, and
     * the thread the bound was to free, for as long as that statement ran. A charge with no bound still waits for the claim
     * to end.
     */
    @Test
    void a_charge_under_a_network_wait_bound_waits_for_a_release_claimed_before_it_no_longer_than_that_bound_and_charges_nothing() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        var ledger = ledger(writes, 3, new ShardOwnerMetrics(), new ArrayList<>());
        var bound  = Duration.ofMillis(300);
        assertThat(ledger.claimReleaseUnlessMoveOwed()).as("premise: a release is claimed").isTrue();

        var unbounded = CompletableFuture.supplyAsync(() -> charge(ledger, candidates(1L)));
        var waited    = new AtomicReference<Duration>();
        var bounded = CompletableFuture.supplyAsync(() -> {
            var started = System.nanoTime();
            try {
                NetworkWaitBound.call(bound, () -> ledger.charge(null, () -> candidates(1L)));
                return null;
            } catch (SQLException e) {
                return e;
            } finally {
                waited.set(Duration.ofNanos(System.nanoTime() - started));
            }
        });

        var failure = bounded.get(10, TimeUnit.SECONDS);
        assertThat((Throwable) failure).as("the bounded charge gives up on the claim").isInstanceOf(SQLException.class);
        assertThat(waited.get()).as("after about the bound").isGreaterThanOrEqualTo(bound.minusMillis(20)).isLessThan(Duration.ofSeconds(5));
        assertThat(failure.getSQLState()).as("not in doubt: nothing was sent").isNotNull().doesNotStartWith("08");
        assertThat(LeaseRowState.mayHaveGoneThrough(failure)).isFalse();
        assertThat(failure.getMessage()).contains("unit " + SHARD).contains(bound.toString());
        assertThat(writes.chargeCalls).as("nothing charged").isEmpty();
        assertThat(ledger.answer()).isEqualTo(ReleaseChargeLedger.Answer.NONE);
        assertThat(ledger.owes(candidates(1L))).as("the candidate is still owed").isTrue();
        assertThatThrownBy(() -> unbounded.get(300, TimeUnit.MILLISECONDS)).as("a charge with no bound still waits for the claim")
                                                                           .isInstanceOf(TimeoutException.class);

        ledger.endReleaseClaim();

        assertThat(unbounded.get(10, TimeUnit.SECONDS).charged()).as("charged once the claim ended").isEqualTo(1);
        assertThat(writes.chargeCalls).containsExactly(Set.of(1L));
    }

    /**
     * A charge under a network wait bound whose claimed release ends within the bound goes on and charges, as one with no
     * bound does.
     */
    @Test
    void a_charge_under_a_network_wait_bound_goes_on_once_the_release_claimed_before_it_ends_within_the_bound() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        var ledger = ledger(writes, 3, new ShardOwnerMetrics(), new ArrayList<>());
        assertThat(ledger.claimReleaseUnlessMoveOwed()).as("premise: a release is claimed").isTrue();

        var charge = CompletableFuture.supplyAsync(() -> NetworkWaitBound.call(Duration.ofSeconds(30), () -> charge(ledger, candidates(1L))));
        assertThatThrownBy(() -> charge.get(300, TimeUnit.MILLISECONDS)).as("the charge waits for the claimed release")
                                                                          .isInstanceOf(TimeoutException.class);
        ledger.endReleaseClaim();

        assertThat(charge.get(10, TimeUnit.SECONDS).charged()).isEqualTo(1);
        assertThat(writes.chargeCalls).containsExactly(Set.of(1L));
    }

    /**
     * The completion wins: a handler that completes while its message's dead-letter move is still owed - the charge
     * committed, the move refused - settles that move as handled, and no later call makes it. The owner records the
     * message handled instead (its acknowledgement queued). Made, the move dead-lettered a message whose handler had
     * completed - on a dead pump, and on a live owner whose release ran before the acknowledgement was flushed - while a
     * live owner whose acknowledgement was flushed first found the row gone.
     */
    @Test
    void a_handler_completing_while_its_dead_letter_move_is_owed_settles_that_move_as_handled_and_it_is_never_made() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.failMoves = 1;
        writes.failMovesSqlState = "P0001";
        var metrics = new ShardOwnerMetrics();
        var handed  = new ArrayList<ReleaseChargeLedger.ChargedRow>();
        var ledger  = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                              handed::add);
        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A")))).isInstanceOf(SQLException.class);
        assertThat(ledger.owesDeadLetterMove()).as("premise: the move is owed").isTrue();

        assertThat(ledger.completedInHandler(1L)).as("not dead-lettered: the owner records it handled").isEmpty();

        assertThat(ledger.owesDeadLetterMove()).as("nothing owed once its handler completed").isFalse();
        assertThat(ledger.owes(candidates(Map.of(1L, "A")))).isFalse();
        var answer = ledger.charge(null, Map::of);
        assertThat(writes.moveCalls).as("the move is not made again").containsExactly(1L);
        assertThat(writes.deadLetterLookups).as("nor the dead-letter table asked: the move that failed was not in doubt").isEmpty();
        assertThat(answer.charged()).as("still charged").isEqualTo(1);
        assertThat(answer.deadLettered()).isEmpty();
        assertThat(metrics.deadLettered.sum()).isZero();
        assertThat(handed).as("no key blocked").isEmpty();
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isEmpty();
    }

    /**
     * A handler that has not completed - it threw, or was interrupted - asks {@link ReleaseChargeLedger#deadLetteredWhileInHandler},
     * which leaves a dead-letter move still to be made owed: the next call makes it.
     */
    @Test
    void a_handler_that_has_not_completed_leaves_its_dead_letter_move_owed() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.failMoves = 1;
        writes.failMovesSqlState = "P0001";
        var ledger = ledger(writes, 1, new ShardOwnerMetrics(), new ArrayList<>());
        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(1L))).isInstanceOf(SQLException.class);

        assertThat(ledger.deadLetteredWhileInHandler(1L)).isEmpty();

        assertThat(ledger.owesDeadLetterMove()).as("the move is still owed").isTrue();
        assertThat(ledger.charge(null, Map::of).deadLettered()).as("and made by the next call").extracting(ReleaseChargeLedger.ChargedRow::seq)
                                                             .containsExactly(1L);
        assertThat(ledger.completedInHandler(1L)).as("a move made before a completion stands").isPresent();
    }

    /**
     * A handler completing after a move of its message threw with no answer from the database: that move may have committed
     * all the same, so the next call asks the dead-letter table rather than settling it unasked. Found there, the message
     * was dead-lettered before its handler completed, and is reported as any move made here is; the move is not made again.
     */
    @Test
    void a_handler_completing_after_a_move_of_it_in_doubt_has_the_dead_letter_table_asked_and_a_move_that_committed_reported() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.commitThenFailMoves.add(1L);
        var metrics = new ShardOwnerMetrics();
        var handed  = new ArrayList<ReleaseChargeLedger.ChargedRow>();
        var ledger  = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                              handed::add);
        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A")))).isInstanceOf(SQLException.class);

        assertThat(ledger.completedInHandler(1L)).as("not known to be dead-lettered yet").isEmpty();

        assertThat(ledger.owesDeadLetterMove()).as("the lookup is still owed").isTrue();
        var answer = ledger.charge(null, Map::of);
        assertThat(writes.moveCalls).as("no move made again").containsExactly(1L);
        assertThat(writes.deadLetterLookups).as("the dead-letter table asked").containsExactly(1L);
        assertThat(answer.deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::seq).containsExactly(1L);
        assertThat(metrics.deadLettered.sum()).isEqualTo(1);
        assertThat(handed).as("the key blocked behind it").extracting(ReleaseChargeLedger.ChargedRow::key).containsExactly("A");
        assertThat(ledger.owesDeadLetterMove()).isFalse();
    }

    /**
     * As above, with the move in doubt not committed: the dead-letter table does not hold the message, and the completion
     * stands - nothing dead-lettered, nothing owed.
     */
    @Test
    void a_handler_completing_after_a_move_of_it_in_doubt_that_did_not_commit_is_settled_as_handled() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.failMoves = 1;
        writes.failMovesSqlState = "08006";
        var metrics = new ShardOwnerMetrics();
        var ledger  = ledger(writes, 1, metrics, new ArrayList<>());
        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(1L))).isInstanceOf(SQLException.class);

        assertThat(ledger.completedInHandler(1L)).isEmpty();
        var answer = ledger.charge(null, Map::of);

        assertThat(writes.moveCalls).as("no move made again").containsExactly(1L);
        assertThat(writes.deadLetterLookups).as("the dead-letter table asked").containsExactly(1L);
        assertThat(answer.deadLettered()).isEmpty();
        assertThat(metrics.deadLettered.sum()).isZero();
        assertThat(ledger.owesDeadLetterMove()).as("nothing owed").isFalse();
        ledger.charge(null, Map::of);
        assertThat(writes.deadLetterLookups).as("asked once").containsExactly(1L);
    }

    @Test
    void a_message_charged_below_the_limit_owes_no_dead_letter_move() throws Exception {
        var ledger = ledger(new FakeWrites(Map.of(1L, 1)), 2, new ShardOwnerMetrics(), new ArrayList<>());

        ledger.charge(null, () -> candidates(1L));

        assertThat(ledger.answer().charged()).as("premise: charged").isEqualTo(1);
        assertThat(ledger.owesDeadLetterMove()).isFalse();
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
        var ledger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                             deadLettered -> { });

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("observer of 1")
                .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage).containsExactly("observer of 2"));

        assertThat(writes.moveCalls).as("every move is made before the observer's failure is thrown").containsExactly(1L, 2L);
        assertThat(told).containsExactly(new MessageId(MessageId.Lane.ORDERED, SHARD, 1L), new MessageId(MessageId.Lane.ORDERED, SHARD, 2L));
        assertThat(metrics.deadLettered.sum()).as("each dead letter counted before the observer is told").isEqualTo(2);
        assertThat(ledger.owes(candidates(Map.of(1L, "A", 2L, "B")))).as("a throwing observer is not a failed charge").isFalse();
        assertThat(ledger.answer().deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::key).containsExactly("A", "B");
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isPresent();
        assertThat(ledger.deadLetteredWhileInHandler(2L)).isPresent();
    }

    /**
     * The owner is handed the row of each message the charge moved to the dead-letter table, with the key and
     * {@code key_order} its candidate gave - after the move is recorded and before the {@code QueueObserver} is told - and
     * of no other: not one charged below the limit, not one whose move found the row gone, not one whose move failed until
     * a later call makes it. The ordered lane blocks the key from that row, and keeps no record of its own beside the
     * ledger's.
     */
    @Test
    void the_owner_is_handed_the_row_of_each_message_moved_with_its_key_and_key_order_and_of_no_other() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 3, 2L, 3, 3L, 1, 4L, 3));
        writes.rowsGone.add(2L);
        writes.failMoves = 1;
        var handed = new ArrayList<ReleaseChargeLedger.ChargedRow>();
        var told   = new ArrayList<Long>();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                assertThat(handed).as("the owner is handed the row before the observer is told").extracting(ReleaseChargeLedger.ChargedRow::seq)
                                  .contains(id.sequence());
                told.add(id.sequence());
            }
        });
        var self   = new AtomicReference<ReleaseChargeLedger>();
        var ledger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 3), metrics, charged -> { },
                                             deadLettered -> {
                                                 assertThat(self.get().deadLetteredWhileInHandler(deadLettered.seq())).as("the move is recorded before the owner is handed it")
                                                                                                                   .isPresent();
                                                 handed.add(deadLettered);
                                             });
        self.set(ledger);

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B", 3L, "C", 4L, "D")))).isInstanceOf(SQLException.class);
        assertThat(handed).as("a move that failed hands nothing over").isEmpty();

        ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B", 3L, "C", 4L, "D")));
        ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B", 3L, "C", 4L, "D")));

        assertThat(writes.moveCalls).as("premise: 1 failed then moved, 2 found gone, 4 moved; 3 below the limit is not moved").containsExactly(1L, 1L, 2L, 4L);
        assertThat(handed).as("only the rows moved, each once, with the key and key_order of its candidate")
                          .extracting(ReleaseChargeLedger.ChargedRow::seq, ReleaseChargeLedger.ChargedRow::key, ReleaseChargeLedger.ChargedRow::keyOrder,
                                      ReleaseChargeLedger.ChargedRow::move)
                          .containsExactly(tuple(1L, "A", 10L, ReleaseChargeLedger.DeadLetterMove.MOVED),
                                           tuple(4L, "D", 40L, ReleaseChargeLedger.DeadLetterMove.MOVED));
        assertThat(told).containsExactly(1L, 4L);
    }

    /**
     * An owner callback ({@code onMoved}) that throws when handed a moved row leaves that move counted and the
     * {@code QueueObserver} told of it: the move has committed, and no later call makes it again. Counted and queued for the
     * observer only after the callback returned, the move was neither. The moves after it are left owed, for a later call.
     */
    @Test
    void an_owner_callback_that_throws_when_handed_a_moved_row_leaves_the_move_counted_and_the_observer_told_of_it() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1, 2L, 1));
        var told   = new CopyOnWriteArrayList<Long>();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                told.add(id.sequence());
            }
        });
        var handed = new ArrayList<Long>();
        var ledger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                             deadLettered -> {
                                                 handed.add(deadLettered.seq());
                                                 if (deadLettered.seq() == 1L) {
                                                     throw new IllegalStateException("owner callback of 1");
                                                 }
                                             });

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B")))).hasMessage("owner callback of 1");

        assertThat(writes.moveCalls).as("premise: the move of 1 was made before its callback threw").containsExactly(1L);
        assertThat(metrics.deadLettered.sum()).as("the move made is counted").isEqualTo(1);
        assertThat(told).as("the observer is told of the move made").containsExactly(1L);
        assertThat(ledger.answer().deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::seq).containsExactly(1L);
        assertThat(ledger.owes(candidates(Map.of(1L, "A", 2L, "B")))).as("the move of 2 is still owed").isTrue();

        ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B")));

        assertThat(writes.moveCalls).as("the made move is not made again").containsExactly(1L, 2L);
        assertThat(handed).containsExactly(1L, 2L);
        assertThat(metrics.deadLettered.sum()).isEqualTo(2);
        assertThat(told).as("the retry tells the observer of its own move alone").containsExactly(1L, 2L);
    }

    @Test
    void a_move_that_finds_the_row_gone_is_made_and_dead_letters_nothing() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 2));
        writes.rowsGone.add(1L);
        var metrics = new ShardOwnerMetrics();
        var ledger  = ledger(writes, 2, metrics, new ArrayList<>());

        var answer = ledger.charge(null, () -> candidates(1L));

        assertThat(writes.moveCalls).containsExactly(1L);
        assertThat(writes.deadLetterLookups).as("no earlier attempt of the move threw: the dead-letter table is not asked").isEmpty();
        assertThat(answer.charged()).isEqualTo(1);
        assertThat(answer.deadLettered()).isEmpty();
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isEmpty();
        assertThat(ledger.owes(candidates(1L))).isFalse();
        assertThat(metrics.deadLettered.sum()).isZero();
    }

    /**
     * A dead-letter move that throws after its {@code COMMIT} went through on the server - the driver stopped waiting for
     * the reply, as a release on request does past its network wait bound - leaves the message in the dead-letter table.
     * The later call that makes the move again finds the row gone, and reports the message dead-lettered once: counted in
     * {@code deadLettered}, the owner handed its row (the ordered lane blocks the key), the {@code QueueObserver} told, and
     * named in the answer. Read as a move that moved nothing, it was reported nowhere.
     */
    @Test
    void a_move_that_committed_before_it_threw_is_reported_dead_lettered_by_the_call_that_makes_it_again() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.commitThenFailMoves.add(1L);
        var told   = new CopyOnWriteArrayList<Long>();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                told.add(id.sequence());
            }
        });
        var handed = new ArrayList<ReleaseChargeLedger.ChargedRow>();
        var ledger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                             handed::add);

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A")))).isInstanceOf(SQLException.class);
        assertThat(writes.deadLetters).as("premise: the move that threw committed").containsExactly(1L);
        assertThat(ledger.owes(candidates(Map.of(1L, "A")))).as("the move is still owed: this call could not tell it committed").isTrue();

        var answer = ledger.charge(null, () -> candidates(Map.of(1L, "A")));

        assertThat(writes.moveCalls).as("premise: the move is made again and finds the row gone").containsExactly(1L, 1L);
        assertThat(answer.deadLettered()).as("the message is dead-lettered").extracting(ReleaseChargeLedger.ChargedRow::seq, ReleaseChargeLedger.ChargedRow::key)
                                         .containsExactly(tuple(1L, "A"));
        assertThat(metrics.deadLettered.sum()).as("counted once").isEqualTo(1);
        assertThat(told).as("the observer is told once").containsExactly(1L);
        assertThat(handed).as("the owner is handed its row once").extracting(ReleaseChargeLedger.ChargedRow::seq).containsExactly(1L);
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isPresent();
        assertThat(writes.deadLetterLookups).as("the dead-letter table is asked").containsExactly(1L);
        assertThat(ledger.owes(candidates(Map.of(1L, "A")))).isFalse();

        ledger.charge(null, () -> candidates(Map.of(1L, "A")));
        assertThat(writes.moveCalls).as("a made move is not made again").containsExactly(1L, 1L);
        assertThat(told).containsExactly(1L);
        assertThat(metrics.deadLettered.sum()).isEqualTo(1);
    }

    /**
     * A dead-letter move that threw without committing, made again once the row is gone - deleted through the admin API
     * meanwhile, say - moved nothing: the dead-letter table does not hold the message, and nothing is reported.
     */
    @Test
    void a_move_that_threw_without_committing_and_then_finds_the_row_gone_dead_letters_nothing() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.failMoves = 1;
        var told   = new CopyOnWriteArrayList<Long>();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                told.add(id.sequence());
            }
        });
        var handed = new ArrayList<ReleaseChargeLedger.ChargedRow>();
        var ledger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                             handed::add);

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A")))).isInstanceOf(SQLException.class);
        writes.rowsGone.add(1L);

        var answer = ledger.charge(null, () -> candidates(Map.of(1L, "A")));

        assertThat(writes.deadLetterLookups).as("the dead-letter table is asked").containsExactly(1L);
        assertThat(answer.deadLettered()).isEmpty();
        assertThat(metrics.deadLettered.sum()).isZero();
        assertThat(told).isEmpty();
        assertThat(handed).isEmpty();
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isEmpty();
        assertThat(ledger.owes(candidates(Map.of(1L, "A")))).as("the move was made: nothing is owed").isFalse();
    }

    /**
     * A dead-letter move the database answered with an error of its own - an SQL state outside class {@code 08}, a trigger
     * refusing it ({@code P0001}), a statement cancelled ({@code 57014}) or a unique constraint violated ({@code 23505}) -
     * rolled back for certain: the move is not in
     * doubt, as a release failing the same way is not ({@link LeaseRowState#mayHaveGoneThrough}). The later attempt that
     * finds the row gone takes it for gone and does not ask the dead-letter table, whose unfenced lookup could otherwise
     * name a dead letter this owner did not make.
     */
    @ParameterizedTest(name = "SQL state {0}")
    @ValueSource(strings = {"P0001", "57014", "23505"})
    void a_move_the_database_refused_with_an_error_of_its_own_is_not_in_doubt_and_the_row_found_gone_after_it_is_taken_for_gone(String sqlState) throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.failMoves = 1;
        writes.failMovesSqlState = sqlState;
        var metrics = new ShardOwnerMetrics();
        var handed  = new ArrayList<ReleaseChargeLedger.ChargedRow>();
        var ledger  = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                              handed::add);

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A")))).isInstanceOf(SQLException.class);
        // Gone meanwhile, and the dead-letter table holds a message of that seq all the same: made by another owner
        writes.rowsGone.add(1L);
        writes.deadLetters.add(1L);

        var answer = ledger.charge(null, () -> candidates(Map.of(1L, "A")));

        assertThat(writes.moveCalls).as("premise: the move is made again and finds the row gone").containsExactly(1L, 1L);
        assertThat(writes.deadLetterLookups).as("the move was not in doubt: the dead-letter table is not asked").isEmpty();
        assertThat(answer.deadLettered()).isEmpty();
        assertThat(metrics.deadLettered.sum()).isZero();
        assertThat(handed).isEmpty();
        assertThat(ledger.deadLetteredWhileInHandler(1L)).isEmpty();
        assertThat(ledger.owes(candidates(Map.of(1L, "A")))).as("the move was made: nothing is owed").isFalse();
    }

    /**
     * A dead-letter move that failed with no answer from the database - a connection lost or a read timed out, which
     * pgjdbc reports as {@code 08006} - may have committed: the later attempt that finds the row gone asks the dead-letter
     * table, for the message at the attempt count the charge gave it, which the move copies unchanged.
     */
    @Test
    void a_move_that_failed_with_a_connection_error_is_in_doubt_and_the_lookup_asks_for_the_charged_attempt_count() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 3));
        writes.failMoves = 1;
        writes.failMovesSqlState = "08006";
        var ledger = ledger(writes, 3, new ShardOwnerMetrics(), new ArrayList<>());

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(1L))).isInstanceOf(SQLException.class);
        writes.rowsGone.add(1L);
        writes.deadLetters.add(1L);

        var answer = ledger.charge(null, () -> candidates(1L));

        assertThat(writes.deadLetterLookups).as("the move was in doubt: the dead-letter table is asked").containsExactly(1L);
        assertThat(writes.deadLetterLookupAttempts).as("for the count after the charge").containsExactly(3);
        assertThat(answer.deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::seq).containsExactly(1L);
    }

    /**
     * A handler that returns while the charge is moving its message to the dead-letter table - the move committed in the
     * database, not yet recorded here - is told its message was dead-lettered, once the charge has recorded the move: it
     * waits for the running charge rather than reading the record half-way. Answered from the record alone, it read "not
     * dead-lettered", and the handler's outcome was recorded against a row that was gone.
     */
    @Test
    void a_handler_returning_while_its_message_is_being_dead_lettered_waits_for_the_move_and_is_told_of_it() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1));
        writes.holdMoves();
        var ledger = ledger(writes, 1, new ShardOwnerMetrics(), new ArrayList<>());

        var charge = CompletableFuture.supplyAsync(() -> charge(ledger, candidates(Map.of(1L, "A"))));
        assertThat(writes.moving.await(10, TimeUnit.SECONDS)).as("premise: the charge is moving the message").isTrue();
        var handlerAsks = CompletableFuture.supplyAsync(() -> ledger.deadLetteredWhileInHandler(1L));

        assertThatThrownBy(() -> handlerAsks.get(300, TimeUnit.MILLISECONDS)).as("the handler waits for the running charge")
                                                                              .isInstanceOf(TimeoutException.class);
        writes.proceed.countDown();

        assertThat(handlerAsks.get(10, TimeUnit.SECONDS)).as("told of the dead letter the charge made")
                                                         .hasValueSatisfying(row -> {
                                                             assertThat(row.seq()).isEqualTo(1L);
                                                             assertThat(row.key()).isEqualTo("A");
                                                             assertThat(row.attemptsAfterCharge()).isEqualTo(1);
                                                         });
        assertThat(charge.get(10, TimeUnit.SECONDS).deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::seq).containsExactly(1L);
    }

    /**
     * A handler that returns while the charge is running, its message not recorded yet, waits for that charge too: the
     * message is recorded and moved by it, and the handler is told so. A charge that dead-letters nothing - the move
     * failing, or finding the row gone - leaves the handler's outcome to be recorded as usual.
     */
    @Test
    void a_handler_returning_while_a_charge_runs_is_told_only_of_a_move_the_charge_made() throws Exception {
        var moved = new FakeWrites(Map.of(1L, 1));
        moved.holdCharges();
        var movedLedger = ledger(moved, 1, new ShardOwnerMetrics(), new ArrayList<>());
        var charge      = CompletableFuture.supplyAsync(() -> charge(movedLedger, candidates(1L)));
        assertThat(moved.charging.await(10, TimeUnit.SECONDS)).as("premise: the charge is running").isTrue();
        var handlerAsks = CompletableFuture.supplyAsync(() -> movedLedger.deadLetteredWhileInHandler(1L));
        assertThatThrownBy(() -> handlerAsks.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        moved.proceed.countDown();
        assertThat(handlerAsks.get(10, TimeUnit.SECONDS)).as("recorded and moved by the charge it waited for").isPresent();
        charge.get(10, TimeUnit.SECONDS);

        var refused = new FakeWrites(Map.of(1L, 1));
        refused.holdMoves();
        refused.failMoves = 1;
        var refusedLedger = ledger(refused, 1, new ShardOwnerMetrics(), new ArrayList<>());
        var refusedCharge = CompletableFuture.runAsync(() -> assertThatThrownBy(() -> refusedLedger.charge(null, () -> candidates(1L))).isInstanceOf(SQLException.class));
        assertThat(refused.moving.await(10, TimeUnit.SECONDS)).isTrue();
        var askedDuringARefusedMove = CompletableFuture.supplyAsync(() -> refusedLedger.deadLetteredWhileInHandler(1L));
        assertThatThrownBy(() -> askedDuringARefusedMove.get(300, TimeUnit.MILLISECONDS)).as("the handler waits for the move being refused")
                                                                                          .isInstanceOf(TimeoutException.class);
        refused.proceed.countDown();
        assertThat(askedDuringARefusedMove.get(10, TimeUnit.SECONDS)).as("a move that failed dead-lettered nothing").isEmpty();
        refusedCharge.get(10, TimeUnit.SECONDS);

        var gone = new FakeWrites(Map.of(1L, 1));
        gone.holdMoves();
        gone.rowsGone.add(1L);
        var goneLedger = ledger(gone, 1, new ShardOwnerMetrics(), new ArrayList<>());
        var goneCharge = CompletableFuture.supplyAsync(() -> charge(goneLedger, candidates(1L)));
        assertThat(gone.moving.await(10, TimeUnit.SECONDS)).isTrue();
        var askedDuringAMoveOfNothing = CompletableFuture.supplyAsync(() -> goneLedger.deadLetteredWhileInHandler(1L));
        assertThatThrownBy(() -> askedDuringAMoveOfNothing.get(300, TimeUnit.MILLISECONDS)).as("the handler waits for the move finding the row gone")
                                                                                             .isInstanceOf(TimeoutException.class);
        gone.proceed.countDown();
        assertThat(askedDuringAMoveOfNothing.get(10, TimeUnit.SECONDS)).as("a move that found the row gone dead-lettered nothing").isEmpty();
        goneCharge.get(10, TimeUnit.SECONDS);
    }

    /**
     * A handler whose message the running charge cannot move - charged below the limit by an earlier call, or never a
     * candidate of it - is answered at once while that charge holds the monitor across a move. It used to wait for any
     * charge running: every handler of the owner returning during a charge blocked for the charge's whole database time,
     * the unordered lane's in its {@code HandlerDispatch} permit.
     */
    @Test
    void a_handler_whose_message_the_running_charge_cannot_move_is_answered_without_waiting_for_it() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 2, 2L, 1));
        var ledger = ledger(writes, 2, new ShardOwnerMetrics(), new ArrayList<>());
        assertThat(ledger.charge(null, () -> candidates(2L)).charged()).as("premise: seq 2 charged below the limit").isEqualTo(1);
        writes.holdMoves();

        var charge = CompletableFuture.supplyAsync(() -> charge(ledger, candidates(1L, 2L)));
        assertThat(writes.moving.await(10, TimeUnit.SECONDS)).as("premise: the charge is moving seq 1").isTrue();
        var askedForTheOneMoved = CompletableFuture.supplyAsync(() -> ledger.deadLetteredWhileInHandler(1L));

        assertThat(CompletableFuture.supplyAsync(() -> ledger.deadLetteredWhileInHandler(2L)).get(5, TimeUnit.SECONDS))
                .as("a message charged below the limit before this charge is answered while the move is held").isEmpty();
        assertThat(CompletableFuture.supplyAsync(() -> ledger.deadLetteredWhileInHandler(3L)).get(5, TimeUnit.SECONDS))
                .as("a message the charge did not take is answered while the move is held").isEmpty();
        assertThatThrownBy(() -> askedForTheOneMoved.get(300, TimeUnit.MILLISECONDS)).as("the message being moved still waits")
                                                                                     .isInstanceOf(TimeoutException.class);
        writes.proceed.countDown();

        assertThat(askedForTheOneMoved.get(10, TimeUnit.SECONDS)).isPresent();
        assertThat(charge.get(10, TimeUnit.SECONDS).deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::seq).containsExactly(1L);
    }

    /**
     * The candidates are taken once the charge is running, and a handler returning while it takes them waits for it: the
     * charge may take that handler's message. Taken before the charge ran, a handler returning in between found no charge
     * running and no record, and its message was dead-lettered after its handler had completed.
     */
    @Test
    void a_handler_returning_while_the_charge_takes_its_candidates_waits_for_that_charge_and_is_told_of_its_move() throws Exception {
        var ledger           = ledger(new FakeWrites(Map.of(1L, 1)), 1, new ShardOwnerMetrics(), new ArrayList<>());
        var takingCandidates = new CountDownLatch(1);
        var candidatesTaken  = new CountDownLatch(1);

        var charge = CompletableFuture.supplyAsync(() -> {
            try {
                return ledger.charge(null, () -> {
                    takingCandidates.countDown();
                    awaitLatch(candidatesTaken);
                    return candidates(1L);
                });
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(takingCandidates.await(10, TimeUnit.SECONDS)).as("premise: the charge is taking its candidates").isTrue();
        var handlerAsks = CompletableFuture.supplyAsync(() -> ledger.deadLetteredWhileInHandler(1L));

        assertThatThrownBy(() -> handlerAsks.get(300, TimeUnit.MILLISECONDS)).as("the handler waits for the charge taking its candidates")
                                                                              .isInstanceOf(TimeoutException.class);
        candidatesTaken.countDown();

        assertThat(handlerAsks.get(10, TimeUnit.SECONDS)).as("told of the dead letter that charge made").isPresent();
        assertThat(charge.get(10, TimeUnit.SECONDS).deadLettered()).extracting(ReleaseChargeLedger.ChargedRow::seq).containsExactly(1L);
    }

    /**
     * The {@code QueueObserver} told of a dead letter is not called under the ledger's monitor: handler threads wait on that
     * monitor ({@link ReleaseChargeLedger#deadLetteredWhileInHandler}), so an observer doing I/O there held every handler
     * waiting on that monitor - one whose message the charge may move - while it ran.
     */
    @Test
    void the_observer_is_told_of_each_dead_letter_once_the_ledger_has_let_go_of_its_monitor() throws Exception {
        var writes       = new FakeWrites(Map.of(1L, 1, 2L, 1));
        var self         = new AtomicReference<ReleaseChargeLedger>();
        var underMonitor = new CopyOnWriteArrayList<Boolean>();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                underMonitor.add(Thread.holdsLock(monitorOf(self.get())));
            }
        });
        var ledger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                             deadLettered -> { });
        self.set(ledger);

        ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B")));

        assertThat(underMonitor).as("told of both dead letters, neither under the ledger's monitor").containsExactly(false, false);
    }

    /**
     * A dead-letter move that fails after an earlier one was made still has the observer told of the earlier one - no later
     * call makes that move again - and its failure carries what the observer threw.
     */
    @Test
    void a_move_that_fails_after_an_earlier_move_still_has_the_observer_told_of_the_earlier_one() throws Exception {
        var writes = new FakeWrites(Map.of(1L, 1, 2L, 1));
        writes.failMoveOf = 2L;
        var told = new CopyOnWriteArrayList<Long>();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                told.add(id.sequence());
                throw new IllegalStateException("observer of " + id.sequence());
            }
        });
        var ledger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), 1), metrics, charged -> { },
                                             deadLettered -> { });

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B"))))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage).containsExactly("observer of 1"));

        assertThat(told).as("told of the move made before the failing one").containsExactly(1L);
        assertThat(ledger.owes(candidates(Map.of(1L, "A", 2L, "B")))).as("the failed move is still owed").isTrue();

        assertThatThrownBy(() -> ledger.charge(null, () -> candidates(Map.of(1L, "A", 2L, "B")))).hasMessage("observer of 2");
        assertThat(told).as("the retry tells the observer of its own move alone").containsExactly(1L, 2L);
    }

    @Test
    void nothing_charged_answers_none() {
        var ledger = ledger(new FakeWrites(Map.of()), 3, new ShardOwnerMetrics(), new ArrayList<>());

        assertThat(ledger.answer()).isEqualTo(ReleaseChargeLedger.Answer.NONE);
        assertThat(ledger.owes(Map.of())).isFalse();
    }

    private static ReleaseChargeLedger ledger(FakeWrites writes, int maxAttempts, ShardOwnerMetrics metrics, List<Map<Long, Integer>> counts) {
        return new ReleaseChargeLedger(MessageId.Lane.UNORDERED, SHARD, writes, RedeliveryPolicy.fixed(Duration.ofSeconds(1), maxAttempts), metrics,
                                       charged -> counts.add(Map.copyOf(charged)), deadLettered -> { });
    }

    private static ReleaseChargeLedger.Answer charge(ReleaseChargeLedger ledger, Map<Long, ReleaseChargeLedger.Candidate> candidates) {
        try {
            return ledger.charge(null, () -> candidates);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object monitorOf(ReleaseChargeLedger ledger) {
        try {
            var field = ReleaseChargeLedger.class.getDeclaredField("monitor");
            field.setAccessible(true);
            return field.get(ledger);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not let proceed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Unordered candidates: no key, {@code key_order} 0.
     */
    private static Map<Long, ReleaseChargeLedger.Candidate> candidates(Long... seqs) {
        var candidates = new TreeMap<Long, ReleaseChargeLedger.Candidate>();
        for (var seq : seqs) {
            candidates.put(seq, ReleaseChargeLedger.Candidate.UNORDERED);
        }
        return candidates;
    }

    /**
     * Ordered candidates, each message's key as given and its {@code key_order} ten times its seq.
     */
    private static Map<Long, ReleaseChargeLedger.Candidate> candidates(Map<Long, String> keyBySeq) {
        var candidates = new TreeMap<Long, ReleaseChargeLedger.Candidate>();
        keyBySeq.forEach((seq, key) -> candidates.put(seq, new ReleaseChargeLedger.Candidate(key, seq * 10)));
        return candidates;
    }

    /**
     * The owner's writes, recorded: each charge returns the count after the charge given for the row, unless it is left out.
     */
    private static final class FakeWrites implements ReleaseChargeLedger.Writes {
        final Map<Long, Integer> countAfterCharge;
        final Set<Long>          leaveOut    = new HashSet<>();
        final Set<Long>          rowsGone    = new HashSet<>();
        /**
         * The seqs whose next move commits - the row moved to {@link #deadLetters}, gone from the lane - and then throws, once
         * each: a {@code COMMIT} whose reply the driver stopped waiting for.
         */
        final Set<Long>          commitThenFailMoves = new HashSet<>();
        /**
         * The seqs the dead-letter table holds, as the moves left it.
         */
        final Set<Long>          deadLetters = new TreeSet<>();
        final List<Long>         deadLetterLookups = new ArrayList<>();
        final List<Set<Long>>    chargeCalls = new ArrayList<>();
        final List<Long>         moveCalls   = new ArrayList<>();
        final CountDownLatch     charging    = new CountDownLatch(1);
        final CountDownLatch     moving      = new CountDownLatch(1);
        final CountDownLatch     proceed     = new CountDownLatch(1);
        int                      failMoves;
        /**
         * The SQL state of the {@link SQLException} each of the {@link #failMoves} throws; {@code null}: none, as a driver
         * that stopped waiting may report.
         */
        String                   failMovesSqlState;
        /**
         * The attempt count each dead-letter lookup asked for, in the order asked.
         */
        final List<Integer>      deadLetterLookupAttempts = new ArrayList<>();
        /**
         * The seq whose next move fails, once, whatever moves come before it; {@code -1}: none.
         */
        long                     failMoveOf  = -1;
        int                      stillOwnsCalls;
        boolean                  owns        = true;
        boolean                  holdCharges;
        boolean                  holdMoves;

        FakeWrites(Map<Long, Integer> countAfterCharge) {
            this.countAfterCharge = countAfterCharge;
        }

        /**
         * Hold each charge, once {@link #charging} counts down, until {@link #proceed} does.
         */
        void holdCharges() {
            holdCharges = true;
        }

        /**
         * Hold each move, as if it had committed and not returned yet, once {@link #moving} counts down, until {@link #proceed}
         * does.
         */
        void holdMoves() {
            holdMoves = true;
        }

        @Override
        public Map<Long, Integer> chargeRowsInHandler(Connection connection, Collection<Long> seqs) {
            if (holdCharges) {
                charging.countDown();
                awaitProceed();
            }
            chargeCalls.add(Set.copyOf(seqs));
            var charged = new TreeMap<Long, Integer>();
            seqs.stream().filter(seq -> !leaveOut.contains(seq)).forEach(seq -> charged.put(seq, countAfterCharge.get(seq)));
            return charged;
        }

        @Override
        public boolean moveToDeadLetterIfOwned(Connection connection, long seq) throws SQLException {
            moveCalls.add(seq);
            if (holdMoves) {
                moving.countDown();
                awaitProceed();
            }
            if (failMoves > 0) {
                failMoves--;
                throw new SQLException("move refused", failMovesSqlState);
            }
            if (seq == failMoveOf) {
                failMoveOf = -1;
                throw new SQLException("move of " + seq + " refused");
            }
            if (rowsGone.contains(seq)) {
                return false;
            }
            deadLetters.add(seq);
            if (commitThenFailMoves.remove(seq)) {
                rowsGone.add(seq);
                throw new SQLException("move of " + seq + " committed, its reply not waited for");
            }
            return true;
        }

        @Override
        public boolean isDeadLetteredOnRelease(Connection connection, long seq, int attempts) {
            deadLetterLookups.add(seq);
            deadLetterLookupAttempts.add(attempts);
            return deadLetters.contains(seq);
        }

        @Override
        public boolean stillOwns(Connection connection) {
            stillOwnsCalls++;
            return owns;
        }

        private void awaitProceed() {
            try {
                if (!proceed.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not let proceed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
