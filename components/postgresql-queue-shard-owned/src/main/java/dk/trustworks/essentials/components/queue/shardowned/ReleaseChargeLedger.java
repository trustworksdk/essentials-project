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
import dk.trustworks.essentials.shared.Exceptions;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The charge an owner makes before its shard or ordered unit is released past handlers that have not returned, kept once
 * for both lanes: {@code ShardOwner.chargeInHandlerBeforeRelease} and {@code OrderedShardOwner.chargeKeysInHandlerBeforeRelease}
 * each compose one, and supply only what differs - which messages in a handler are candidates (unordered: those in a
 * handler longer than {@code stuckHandlerTimeout}; ordered: by {@code OrderedShardOwner.ReleaseChargeScope}), each with
 * what the lane knows of it ({@link Candidate}), the writes against their own table under their own fence ({@link Writes}),
 * their lane, where the charged counts go, and what to do once a message is dead-lettered (the ordered lane blocks its key).
 * <p>
 * Whoever takes a released unit next charges nothing ({@link ShardOwnedStorage.LeaseGrant#previousOwnerReleased()}), and a
 * handler that hangs never returns to record its attempt, so without the charge a message whose handler hung on every
 * delivery was handed on with its count unchanged and never reached the dead-letter table. {@link #charge} charges one
 * attempt to each candidate this ledger has not charged yet and dead-letters each one the charge brings to the redelivery
 * limit with {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}, as the next owner runs no exhaustion pass for a
 * released unit.
 * <p>
 * One map holds what was charged, a {@link ChargedRow} per message charged, keyed by sequence value, carrying the key and
 * {@code key_order} its {@link Candidate} gave, so an owner keeps no record of its own beside it:
 * <ul>
 *     <li>recorded as soon as the charge has committed, so a release that failed and is retried does not charge a message
 *     again, and a message that has come into the candidates since is charged by the retry. A message the charge asked
 *     for and found no longer in the table, the owner still holding its lease, is settled too ({@link #gone}): not
 *     charged, not counted, not asked for again;</li>
 *     <li>its {@link DeadLetterMove}: {@link DeadLetterMove#PENDING} at the redelivery limit until the move is made, then
 *     {@link DeadLetterMove#MOVED} or {@link DeadLetterMove#ROW_GONE} - or {@link DeadLetterMove#HANDLED} once its handler
 *     has completed (<b>The completion wins</b>, below) - each in one write, so what was dead-lettered - the
 *     sequence values and keys {@link #answer()} reports, {@link #deadLetteredWhileInHandler}, and the row the owner is
 *     handed once it is {@link DeadLetterMove#MOVED} (the constructor's {@code onMoved}) - is derived from that write and
 *     cannot be seen half-made. A move that throws with no answer from the database may have committed all the same (its
 *     {@code COMMIT} reply not waited for; {@link LeaseRowState#mayHaveGoneThrough}), so a later attempt of it that moves
 *     nothing asks the dead-letter table ({@link #movesInDoubt}) rather than taking the row for gone; one the database
 *     answered with an error of its own rolled back, and the dead-letter table is not asked about it;</li>
 *     <li>written only under the monitor; read by the handler threads ({@link #deadLetteredWhileInHandler}) without it
 *     unless a running charge may move the message or its move is still to be made, when they wait for that charge: a
 *     move commits before its write here, so a handler returning in between would otherwise take its message for one
 *     still in the table. A charge takes its candidates once it is running, and the owner leaves out a message whose
 *     handler has returned, so a handler that finds no charge running is never a candidate of one still to start. One
 *     that failed is left out too while it records its failure, and that record is a fenced write: when the release
 *     commits before it, the write is refused, and the next owner, finding the shard or unit released, charges nothing - that
 *     delivery's attempt is not counted. Accepted: the window runs only from the mark until that write commits, and
 *     charged in it, the delivery would be counted twice (by the charge and by its own failure write) or dead-lettered by
 *     the charge while its handler schedules a retry of it.</li>
 * </ul>
 * <b>The completion wins.</b> A message whose handler completes - returns without an exception and is not interrupted -
 * while its dead-letter move is still to be made (the charge committed and the move then failed) is not dead-lettered:
 * {@link #completedInHandler} settles the move {@link DeadLetterMove#HANDLED}, the owner records the message handled, and
 * the release after it, whichever site makes it and whether the owner's pump is live or dead, releases the unit without
 * the move. A live owner's acknowledgement deletes the row when it is flushed before the release, as on {@code stop()}
 * for one queued before its flush ({@code ShardOwnedQueue.handBackWithinBudget} flushes, then charges and releases). A
 * shed releases without a flush - an ordered owner whose shed completed past {@code stuckHandlerTimeout} flushes no
 * acknowledgement once complete - and an acknowledgement flushed after the release is refused; a dead pump's owner's
 * acknowledgements are never flushed (its state is not trusted). There the next owner, finding the unit released,
 * charges nothing and may deliver the message again at the attempt count the charge left (always on a dead pump; on a
 * live owner when the release beats the acknowledgement flush): that redelivery is the cost. Made, the move
 * dead-lettered a message whose handler had completed - counted both {@code delivered} and {@code deadLettered}, its
 * ordered key blocked until an operator resurrected or deleted the dead letter - on a dead pump, and on a live owner
 * whose release ran before the acknowledgement was flushed, while a live owner whose acknowledgement was flushed first
 * found the row gone: one handler history, two outcomes. What stays dead-lettered: a move made before the handler completed (the completion is logged,
 * not acknowledged), and one in doubt ({@link #movesInDoubt}) that the dead-letter table shows committed. A handler that
 * threw - a requested redelivery included - or was interrupted has not completed, and its move is still made.
 * <p>
 * A {@code QueueObserver} told of a message dead-lettered here is told after the move, once {@link #charge} has let go of
 * the monitor, and what it throws is held until every move is made, then thrown: by then nothing is owed ({@link #owes}),
 * so the queue tells that failure from a failed charge and releases the unit all the same. Callers do not run
 * {@link #charge} for one owner at the same time; the monitor makes it safe if they do.
 * <p>
 * <b>Locking.</b> The monitor is always taken before any lock of the owner's, never the other way round:
 * <ul>
 *     <li>{@link #charge} holds the monitor across its database round trips and across every callback the owner gave it -
 *     the candidates {@code Supplier}, the constructor's {@code onCharged} and {@code onMoved}, and every {@link Writes}
 *     method - each of which may take the owner's state lock ({@code stateLock}): both owners take it in their candidates,
 *     {@code onCharged} and (ordered) {@code onMoved};</li>
 *     <li>before any of that, {@link #charge} waits for every release of the owner's row claimed by
 *     {@link #claimReleaseUnlessMoveOwed} to end ({@link #endReleaseClaim}), letting go of the monitor while it waits. A
 *     thread holding a claim takes no lock until it ends it but the monitors of the other owners it claims after this one
 *     for the same statement, each of which it waits for only while a charge is running there, never one waiting for a
 *     claim; so the wait closes no cycle with a lock the waiting caller holds. Nor does it wait on the pool through the
 *     claim holder: {@code ShardOwnedQueue.releaseLeaseRows} takes the statement's connection before it claims any owner.
 *     A waiting charge waits for the claims of those later owners and for the release statement, and keeps its caller's
 *     connection, when it holds one, meanwhile. On a thread whose waits for the database are bounded
 *     ({@link NetworkWaitBound}: a stop's budget thread, the hand-back of a kept unit, a release on request) it waits no
 *     longer than that bound, then throws with nothing charged ({@link #RELEASE_CLAIM_WAIT_TIMED_OUT}), as the release
 *     statement it waits for may run with no bound (a heartbeat tick's); with no bound, for as long as the claims are
 *     held;</li>
 *     <li>so a caller of {@link #charge}, {@link #owes}, {@link #owesDeadLetterMove}, {@link #answer} or
 *     {@link #deadLetteredWhileInHandler} or {@link #completedInHandler} must not hold the owner's state lock, nor any lock
 *     those callbacks take: {@link #owes}, {@link #owesDeadLetterMove} and {@link #answer} take the monitor, and
 *     {@link #deadLetteredWhileInHandler} and {@link #completedInHandler} wait on it while a running charge may move the
 *     message or its move is still to be made. A caller holding one
 *     while a charge waits for it in a callback deadlocks with that charge - a handler thread, say, asking
 *     {@link #deadLetteredWhileInHandler} inside its owner's {@code synchronized (stateLock)}. Both owners ask before they
 *     take it ({@code ReleaseChargeHandlerReturnTest} pins a handler returning mid-charge on each lane);</li>
 *     <li>the {@code QueueObserver} is never called under the monitor: handler threads wait on it, and an observer that
 *     does I/O would hold them for as long. So a handler returning during the charge, or just after it, may learn of its
 *     message's dead letter ({@link #deadLetteredWhileInHandler}), and report its own outcome ({@code deliveryFailed}
 *     included), before the observer is told of that dead letter.</li>
 * </ul>
 */
final class ReleaseChargeLedger {

    /**
     * The owner's statements the charge runs - the two writes it makes and the lease lookup that explains a row the charge
     * left out, each fenced by the owner as the release after it is: the row must still name the owner's instance under
     * its fence, which releasing does not bump; and the unfenced dead-letter lookup that settles a move that threw
     * ({@link #isDeadLetteredOnRelease}), which writes nothing. Statements only: what the owner does once a message is
     * dead-lettered is the constructor's {@code onMoved}.
     * <p>
     * Called under the ledger's monitor, which is taken before any lock of the owner's: a method here may take the owner's
     * state lock, never wait for the monitor (class javadoc, <b>Locking</b>).
     */
    interface Writes {
        /**
         * Charge one attempt to each row of {@code seqs} (see {@link ShardOwnedStorage#chargeRowsInHandler}).
         *
         * @return the attempt count after the charge of each row charged; a row gone or no longer the owner's is left out,
         *         and {@link #stillOwns} tells the two apart
         */
        Map<Long, Integer> chargeRowsInHandler(Connection connection, Collection<Long> seqs) throws SQLException;

        /**
         * Move the row of {@code seq} to the dead-letter table with {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}
         * (see {@link ShardOwnedStorage#moveToDeadLetterIfOwned}).
         *
         * @return whether it moved the row: {@code false} when the row was gone or no longer the owner's - gone because an
         *         earlier attempt of this move, one that threw, committed on the server all the same included, which
         *         {@link #isDeadLetteredOnRelease} tells apart
         */
        boolean moveToDeadLetterIfOwned(Connection connection, long seq) throws SQLException;

        /**
         * Whether the dead-letter table holds the message of {@code seq} as {@link #moveToDeadLetterIfOwned} moves it, with
         * {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER} and {@code attempts} - the count this ledger's charge
         * committed for it ({@link ChargedRow#attemptsAfterCharge}), which the move copies unchanged (see
         * {@link ShardOwnedStorage#isDeadLetteredWith}). Asked only when a move of that message failed in a way that may
         * have committed on the server ({@link LeaseRowState#mayHaveGoneThrough}) and a later attempt of it moved nothing:
         * the attempt that threw may have committed after the driver stopped waiting for its reply - a network timeout
         * during the {@code COMMIT} - which leaves the row moved, not gone. Unfenced, so the attempt count is what tells
         * this owner's move from a dead letter a later owner of the unit made of the same message with the same error: that
         * owner's charge counts on from this one's, so its dead letter carries a higher count - unless an operator retried
         * the message in between ({@link ShardOwnedStorage#retryMessage}, which resets its attempt count to 0). Then the
         * lookup can match a later owner's dead letter, or miss this owner's own.
         */
        boolean isDeadLetteredOnRelease(Connection connection, long seq, int attempts) throws SQLException;

        /**
         * Whether the owner still holds its unit's lease under its fence (see
         * {@link ShardOwnedStorage#stillOwns(Connection, String, int, String, long)}): asked when the charge left a row
         * out, to tell a row no longer in the table from a lease lost.
         */
        boolean stillOwns(Connection connection) throws SQLException;
    }

    /**
     * Where the dead-letter move of a charged message stands.
     */
    enum DeadLetterMove {
        /**
         * The charge left it below the redelivery limit: no move is owed.
         */
        NONE_OWED,
        /**
         * At the redelivery limit, and the move is still to be made.
         */
        PENDING,
        /**
         * Moved to the dead-letter table.
         */
        MOVED,
        /**
         * The move was made and moved nothing: the row was gone or no longer the owner's, and no earlier attempt of the
         * move had moved it. An attempt that threw with no answer from the database ({@link LeaseRowState#mayHaveGoneThrough})
         * may have committed on the server all the same, so a later attempt that moves nothing asks the dead-letter table
         * ({@link Writes#isDeadLetteredOnRelease}) and is {@link #MOVED} when the message is there.
         */
        ROW_GONE,
        /**
         * Its handler completed while the move was still to be made ({@link #PENDING}), and the owner records the message
         * handled ({@link #completedInHandler}): the move is not made, as the completion wins (class javadoc). Unless an
         * earlier attempt of the move threw with no answer from the database ({@link #movesInDoubt}): that attempt may have
         * committed, so the next {@link #charge} asks the dead-letter table ({@link Writes#isDeadLetteredOnRelease}), and
         * the message is {@link #MOVED} when it is there.
         */
        HANDLED
    }

    /**
     * A message in a handler an owner asks {@link #charge} to charge, as the owner knows it.
     *
     * @param key      its key; {@code null} on the unordered lane, which has none
     * @param keyOrder its {@code key_order}; {@code 0} on the unordered lane, as {@code Message.of} stores
     */
    record Candidate(String key, long keyOrder) {
        /**
         * A message of the unordered lane: no key, {@code key_order} {@code 0}.
         */
        static final Candidate UNORDERED = new Candidate(null, 0L);
    }

    /**
     * A message charged before a release.
     *
     * @param seq                 its sequence value
     * @param key                 its key; {@code null} on the unordered lane, which has none
     * @param keyOrder            its {@code key_order}; {@code 0} on the unordered lane
     * @param attemptsAfterCharge its attempt count after the charge
     * @param move                where its dead-letter move stands
     */
    record ChargedRow(long seq, String key, long keyOrder, int attemptsAfterCharge, DeadLetterMove move) {
        ChargedRow {
            requireNonNull(move, "No move provided");
        }

        ChargedRow moved(boolean moved) {
            return new ChargedRow(seq, key, keyOrder, attemptsAfterCharge, moved ? DeadLetterMove.MOVED : DeadLetterMove.ROW_GONE);
        }

        ChargedRow handled() {
            return new ChargedRow(seq, key, keyOrder, attemptsAfterCharge, DeadLetterMove.HANDLED);
        }
    }

    /**
     * What {@link #charge} has done so far.
     *
     * @param charged      how many messages were charged an attempt
     * @param deadLettered those the charge brought to the redelivery limit and which were dead-lettered, in sequence order
     */
    record Answer(int charged, List<ChargedRow> deadLettered) {
        static final Answer NONE = new Answer(0, List.of());
    }

    /**
     * The SQL state of the {@link SQLException} {@link #charge} throws when a release's claim outlasts the calling thread's
     * network wait bound: PostgreSQL's {@code lock_not_available}, which a {@code lock_timeout} raises, as this is a wait
     * for a lock - the claim - given up. Outside class {@code 08}, so the failure is not taken for one that may have gone
     * through on the server ({@link LeaseRowState#mayHaveGoneThrough}): no statement was sent. Public on the SPI as
     * {@link KeptUnitRelease#RELEASE_CLAIM_WAIT_TIMED_OUT_SQL_STATE}, as a release on request throws it to its caller.
     */
    static final String RELEASE_CLAIM_WAIT_TIMED_OUT = KeptUnitRelease.RELEASE_CLAIM_WAIT_TIMED_OUT_SQL_STATE;

    private final MessageId.Lane                lane;
    private final int                           shard;
    private final Writes                        writes;
    private final RedeliveryPolicy              redeliveryPolicy;
    private final ShardOwnerMetrics             metrics;
    private final Consumer<Map<Long, Integer>>  onCharged;
    private final Consumer<ChargedRow>          onMoved;
    /**
     * Guards every write to {@link #rows}, and every read but {@link #deadLetteredWhileInHandler}'s and
     * {@link #completedInHandler}'s, which take it only to wait for a running charge or to read a move still to be made
     * (and, {@link #completedInHandler}, to settle it).
     */
    private final Object                        monitor = new Object();
    /**
     * Every message charged, by sequence value. Concurrent so the handler threads read it without the monitor.
     */
    private final NavigableMap<Long, ChargedRow> rows    = new ConcurrentSkipListMap<>();
    /**
     * The messages a charge asked for and found no longer in the table while the owner still held its lease - deleted
     * through the admin API while its handler ran, say - by sequence value. Settled like a charged message, so no later
     * call asks for it again and {@link #owes} does not count it, but not charged: it is left out of {@link Answer#charged}.
     * Read and written under {@link #monitor} only.
     */
    private final Set<Long>                      gone    = new HashSet<>();
    /**
     * The messages whose dead-letter move threw in a way that may have committed on the server and has not been made since,
     * by sequence value: thrown with no answer from the database - a network timeout while the driver waited for the
     * {@code COMMIT} reply, say - as {@link LeaseRowState#mayHaveGoneThrough} classifies a release that threw. One the
     * database answered with an error of its own (an SQL state outside class {@code 08}) rolled back and is not held here.
     * A later attempt that moves nothing asks {@link Writes#isDeadLetteredOnRelease} before it records
     * {@link DeadLetterMove#ROW_GONE}. Taken for gone, such a message was in the dead-letter table and reported nowhere:
     * not counted in {@code deadLettered}, the {@code QueueObserver} not told, its key in no answer. Read and written under
     * {@link #monitor} only.
     */
    private final Set<Long>                      movesInDoubt = new HashSet<>();
    /**
     * The {@link #charge} running, and the messages it may move to the dead-letter table; {@code null} while none runs. Set
     * and cleared under {@link #monitor}, read without it by {@link #deadLetteredWhileInHandler}, which waits for that charge
     * only when it may move the handler's message.
     */
    private volatile RunningCharge               running;
    /**
     * How many releases of the owner's row hold a claim ({@link #claimReleaseUnlessMoveOwed}) not ended yet
     * ({@link #endReleaseClaim}): {@link #charge} waits while this is above 0. Read and written under {@link #monitor} only.
     */
    private int                                  releasesClaimed;

    /**
     * A {@link #charge} running.
     *
     * @param mayMove the messages it may move to the dead-letter table, by sequence value: those it charges and those whose
     *                move was {@link DeadLetterMove#PENDING} when it started. {@code null} while it is still taking its
     *                candidates, when it may move any
     */
    private record RunningCharge(Set<Long> mayMove) {
        static final RunningCharge TAKING_CANDIDATES = new RunningCharge(null);

        boolean mayMove(long seq) {
            return mayMove == null || mayMove.contains(seq);
        }
    }

    /**
     * @param lane             the owner's lane, for the {@link MessageId} the {@code QueueObserver} is told of
     * @param shard            the owner's shard or ordered unit
     * @param writes           the owner's fenced writes
     * @param redeliveryPolicy the owner's redelivery policy, which says when a charged count is at the limit
     * @param metrics          the owner's metrics: the charge is counted in {@code takeoverAttemptBumps}, a dead letter in
     *                         {@code deadLettered}, under the monitor, and its {@code QueueObserver} is told of each dead
     *                         letter once the monitor is let go
     * @param onCharged        told the attempt count after the charge of each message charged, under the monitor, as soon as
     *                         the charge has committed: the owner records them as its own counts, so a failure the
     *                         handler reports later - the release having failed, and the owner serving on - counts on from
     *                         the charge rather than writing an older count over it. Under the monitor, so it may take the
     *                         owner's state lock and must never wait for the monitor (class javadoc, <b>Locking</b>)
     * @param onMoved          told the row of each message dead-lettered, under the monitor, as soon as its move to the
     *                         dead-letter table is recorded here and counted in {@code deadLettered}, before the
     *                         {@code QueueObserver} is: the ordered lane blocks the key at the row's {@code key_order}. What it
     *                         throws ends the call with that move counted and the moves after it left owed for a later call;
     *                         the {@code QueueObserver} is still told of that move, bar a critical error, which is rethrown at
     *                         once with the observer untold. Never told of a move that moved nothing
     *                         ({@link DeadLetterMove#ROW_GONE}): there is no dead letter to block a key behind. Under the
     *                         monitor, so it may take the owner's state lock and must never wait for the monitor (class
     *                         javadoc, <b>Locking</b>)
     */
    ReleaseChargeLedger(MessageId.Lane lane,
                        int shard,
                        Writes writes,
                        RedeliveryPolicy redeliveryPolicy,
                        ShardOwnerMetrics metrics,
                        Consumer<Map<Long, Integer>> onCharged,
                        Consumer<ChargedRow> onMoved) {
        this.lane = requireNonNull(lane, "No lane provided");
        this.shard = shard;
        this.writes = requireNonNull(writes, "No writes provided");
        this.redeliveryPolicy = requireNonNull(redeliveryPolicy, "No redeliveryPolicy provided");
        this.metrics = requireNonNull(metrics, "No metrics provided");
        this.onCharged = requireNonNull(onCharged, "No onCharged provided");
        this.onMoved = requireNonNull(onMoved, "No onMoved provided");
    }

    /**
     * Whether {@link #charge} has anything to write for {@code candidates}: one this ledger has not charged yet, nor found
     * gone from the table, or a charged message at the redelivery limit whose dead-letter move is still to be made.
     * {@code false} after a call whose {@code QueueObserver} threw when told of a dead letter, as it throws only after those
     * writes, unless a candidate has come in since. Lets the queue tell a failed charge, after which the unit is held and the release retried, from a
     * failure that came after the writes, after which it is released; and skip the connection {@link #charge} needs.
     *
     * @param candidates the messages in a handler the owner would charge, by sequence value, each as the owner knows it
     */
    boolean owes(Map<Long, Candidate> candidates) {
        requireNonNull(candidates, "No candidates provided");
        synchronized (monitor) {
            return !notChargedYet(candidates).isEmpty() || owesDeadLetterMove();
        }
    }

    /**
     * Whether a message this ledger charged is at the redelivery limit with its dead-letter move still to be made
     * ({@link DeadLetterMove#PENDING}), or its handler completed after a move of it threw with no answer from the database
     * and the dead-letter table is still to be asked whether that move committed ({@link DeadLetterMove#HANDLED} in
     * {@link #movesInDoubt}), whatever the owner's candidates: the half of {@link #owes} no scope changes. Asked
     * by {@code ShardOwnedQueue.releaseLeaseRows}, which every release of an owner's row goes through, through
     * {@link #claimReleaseUnlessMoveOwed}, and which releases no owner while this is {@code true}: released with the move
     * owed, a dead letter that had committed went uncounted and unreported, and a message that had not been moved was
     * handed on at its attempt limit to an owner that charges nothing for a released unit. Takes the monitor, so it waits
     * for a running {@link #charge} (class javadoc, <b>Locking</b>).
     */
    boolean owesDeadLetterMove() {
        synchronized (monitor) {
            return rows.values().stream().anyMatch(this::stillToSettle);
        }
    }

    /**
     * {@link #owesDeadLetterMove} for a release of the owner's row about to be made, and, when nothing is owed, a claim on
     * that release, held until {@link #endReleaseClaim}: a {@link #charge} that starts while a claim is held waits for every
     * claim to end before it takes its candidates, or, past its thread's network wait bound, throws without taking them.
     * So no charge leaves a move owed between this check and the release statement after it. Asked and checked alone, a
     * charge on another thread - a second release site of the same owner - could start after the check, leave a move owed
     * (its move failing), and the row was released with it owed all the same. A charge already running when this is asked
     * is waited for, as {@link #owesDeadLetterMove} waits for it.
     * <p>
     * Claims are counted, not exclusive: two releases of the owner may each hold one, and neither waits for the other.
     * Called by {@code ShardOwnedQueue.releaseLeaseRows} alone, which takes the statement's connection before it claims
     * any owner, ends the claim in a {@code finally} right after its statement, before it tells the owner its lease ended,
     * and takes no lock while it holds one but the monitors of the other owners it claims after this one for that
     * statement (class javadoc, <b>Locking</b>); a charge that waits for it waits for those owners' claims and for the
     * release statement. A charge that runs after that release finds the row no longer the owner's: its fenced writes
     * change nothing.
     *
     * @return {@code true} when nothing is owed and the release is claimed; {@code false} when a dead-letter move is still
     *         owed, and nothing is claimed
     */
    boolean claimReleaseUnlessMoveOwed() {
        synchronized (monitor) {
            if (owesDeadLetterMove()) {
                return false;
            }
            releasesClaimed++;
            return true;
        }
    }

    /**
     * End a claim {@link #claimReleaseUnlessMoveOwed} gave, once the release statement has returned or thrown: once no
     * claim is left, a {@link #charge} waiting for one goes on.
     *
     * @throws IllegalStateException when no claim is held
     */
    void endReleaseClaim() {
        synchronized (monitor) {
            if (releasesClaimed == 0) {
                throw new IllegalStateException("No release of unit " + shard + " on the " + lane + " lane is claimed");
            }
            releasesClaimed--;
            if (releasesClaimed == 0) {
                monitor.notifyAll();
            }
        }
    }

    /**
     * Wait while a release of the owner's row holds a claim ({@link #claimReleaseUnlessMoveOwed}), letting go of the
     * monitor while it waits. On a thread whose waits for the database are bounded ({@link NetworkWaitBound#current}: a
     * stop's budget thread, the hand-back of a kept unit, a release on request), no longer than that bound: the release
     * holding the claim may run with no bound (a heartbeat tick's), and waited for unbounded, it held this thread past the
     * bound that was to free it. With no bound, as long as the claims it waits for are held. An interrupt of this thread
     * ends neither wait: it is set again on the thread once the wait is over. Called under {@link #monitor}.
     *
     * @throws SQLException when the bound runs out with a claim still held. Nothing has been charged or recorded, so the
     *                      ledger is as it was, and the SQL state ({@link #RELEASE_CLAIM_WAIT_TIMED_OUT}) is outside class
     *                      {@code 08}: nothing was sent to the database ({@link LeaseRowState#mayHaveGoneThrough})
     */
    private void awaitNoReleaseClaimed() throws SQLException {
        var bound       = NetworkWaitBound.current();
        var boundNanos  = bound.map(ReleaseChargeLedger::saturatedNanos).orElse(0L);
        var started     = System.nanoTime();
        var interrupted = false;
        try {
            while (releasesClaimed > 0) {
                try {
                    if (bound.isEmpty()) {
                        monitor.wait();
                        continue;
                    }
                    var remaining = boundNanos - (System.nanoTime() - started);
                    if (remaining <= 0) {
                        throw new SQLException("Did not charge or dead-letter anything for unit " + shard + " on the " + lane
                                                       + " lane before its release: a release of its lease row held its claim on "
                                                       + "the unit for longer than this thread's network wait bound ("
                                                       + bound.get() + "), so this charge did not start: nothing was charged "
                                                       + "and no dead-letter move was made",
                                               RELEASE_CLAIM_WAIT_TIMED_OUT);
                    }
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * {@code duration} in nanoseconds, {@link Long#MAX_VALUE} when it is longer than that holds.
     */
    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Whether {@link #charge} still has a write or a lookup to make for {@code row}: its dead-letter move
     * ({@link DeadLetterMove#PENDING}), or, its handler having completed after a move of it that is in doubt, the lookup of
     * the dead-letter table that tells whether that move committed. Called under {@link #monitor}.
     */
    private boolean stillToSettle(ChargedRow row) {
        return row.move() == DeadLetterMove.PENDING || (row.move() == DeadLetterMove.HANDLED && movesInDoubt.contains(row.seq()));
    }

    /**
     * Charge one attempt to each of {@code candidates} not charged yet, then make every dead-letter move still owed.
     * Waits first for every release of the owner's row claimed ({@link #claimReleaseUnlessMoveOwed}) to end: no longer than
     * the calling thread's network wait bound when it has one ({@link NetworkWaitBound#current}), for as long as the
     * claims are held when it has none.
     * <p>
     * The candidates are taken once this call is running, under the monitor: a handler of one of them that returns after they
     * are taken finds the call running ({@link #deadLetteredWhileInHandler}) and waits for it, and one that returned before is left
     * out of them by the owner, which marks a handler returned before it asks {@link #deadLetteredWhileInHandler} and
     * leaves such messages out of {@code candidates}. Taken before the call ran, a handler returning in between found no
     * charge running and no record, queued its acknowledgement, and the charge then dead-lettered the message it had
     * handled. A handler that failed and is still recording its failure is left out the same way, so a release committing
     * before that fenced write leaves that delivery's attempt uncounted (the class javadoc).
     *
     * @param candidates the messages in a handler to charge, by sequence value, each as the owner knows it (its key and
     *                   {@code key_order} go into its {@link ChargedRow}); asked once, under the monitor, so it may take the
     *                   owner's state lock, never the other way round
     * @return what this ledger has charged and dead-lettered so far, this call included
     * @throws SQLException     when the calling thread's network wait bound runs out with a release of the owner's row
     *                          still claimed: thrown before the candidates are taken, with SQL state
     *                          {@link #RELEASE_CLAIM_WAIT_TIMED_OUT}, nothing charged and the ledger as it was. Or when the
     *                          charge, the lease lookup after a charge that left a row out, a dead-letter move, or
     *                          the dead-letter lookup after a move that threw fails; {@link #owes} then answers {@code true}.
     *                          A move that threw with no answer from the database may have committed on the server all the
     *                          same; the later call that makes it again finds the row gone, asks the dead-letter table, and
     *                          reports the message dead-lettered when it is there. The {@code QueueObserver} is still told of each move made before the
     *                          failure, and what it threw is added to this as suppressed
     * @throws RuntimeException what the {@code QueueObserver} threw when told of a dead letter (or a non-critical
     *                          {@link Error}), thrown once every move is made, so {@link #owes} then answers as after a call
     *                          that returned, and {@link #answer()} gives the answer. Or what the candidates {@code Supplier},
     *                          {@code onCharged} or {@code onMoved} threw, at once: the {@code QueueObserver} is still told of
     *                          each move made before it, that of the row {@code onMoved} was handed included, and what it threw
     *                          is added as suppressed
     */
    Answer charge(Connection connection, Supplier<Map<Long, Candidate>> candidates) throws SQLException {
        requireNonNull(candidates, "No candidates provided");
        // Filled under the monitor, read once it is let go: the QueueObserver is told of these outside it (class javadoc,
        // Locking)
        var moved = new ArrayList<ChargedRow>();
        Answer answer;
        try {
            synchronized (monitor) {
                // A release claimed once its check found nothing owed: charged now, a move this call left owed would be
                // released with it (claimReleaseUnlessMoveOwed)
                awaitNoReleaseClaimed();
                running = RunningCharge.TAKING_CANDIDATES;
                try {
                    var toCharge = notChargedYet(requireNonNull(candidates.get(), "No candidates supplied"));
                    var mayMove  = new HashSet<>(toCharge.keySet());
                    rows.values().stream().filter(this::stillToSettle).forEach(row -> mayMove.add(row.seq()));
                    running = new RunningCharge(Set.copyOf(mayMove));
                    answer = chargeAndMove(connection, toCharge, moved);
                } finally {
                    running = null;
                }
            }
        } catch (SQLException | RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            // A move made before the failure is made for good - no later call makes it again - so the observer is told of it
            // now or never
            var observerFailure = tellObserverOfDeadLetters(moved);
            if (observerFailure != null) {
                e.addSuppressed(observerFailure);
            }
            throw e;
        }
        var observerFailure = tellObserverOfDeadLetters(moved);
        if (observerFailure != null) {
            // Every move is made: with nothing left owed (owes), the queue releases the unit all the same
            Exceptions.sneakyThrow(observerFailure);
        }
        return answer;
    }

    /**
     * Tell the {@code QueueObserver} of each of {@code moved}, in the order moved, all of them whatever one throws. Called
     * without {@link #monitor}.
     *
     * @return what the observer threw (a non-critical {@link Error} included), the first throw carrying the others as
     *         suppressed; {@code null} when it threw nothing. A critical error is rethrown at once
     */
    private Throwable tellObserverOfDeadLetters(List<ChargedRow> moved) {
        Throwable observerFailure = null;
        for (var row : moved) {
            try {
                metrics.observer().deadLettered(new MessageId(lane, shard, row.seq()), row.attemptsAfterCharge(),
                                                new IllegalStateException(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER));
            } catch (RuntimeException | Error e) {
                Exceptions.rethrowIfCriticalError(e);
                if (observerFailure == null) {
                    observerFailure = e;
                } else if (!observerFailure.equals(e)) {
                    observerFailure.addSuppressed(e);
                }
            }
        }
        return observerFailure;
    }

    /**
     * {@link #charge}'s work, for {@code toCharge}: its candidates not charged yet. Each row it moves to the dead-letter
     * table goes into {@code moved}, as soon as its move is recorded, for {@link #charge} to tell the {@code QueueObserver}
     * of once it has let go of the monitor. Called under {@link #monitor}.
     */
    private Answer chargeAndMove(Connection connection, Map<Long, Candidate> toCharge, List<ChargedRow> moved) throws SQLException {
        if (!toCharge.isEmpty()) {
            var charged = writes.chargeRowsInHandler(connection, toCharge.keySet());
            metrics.takeoverAttemptBumps.add(charged.size());
            // The charge has committed: recorded before anything else can fail, or a retried release charges again
            charged.forEach((seq, attempts) -> {
                var candidate = toCharge.get(seq);
                rows.put(seq, new ChargedRow(seq, candidate.key(), candidate.keyOrder(), attempts,
                                             redeliveryPolicy.isExhausted(attempts) ? DeadLetterMove.PENDING : DeadLetterMove.NONE_OWED));
            });
            onCharged.accept(charged);
            // A row the fenced charge left out is gone from the table, or the lease is lost. Gone, there is nothing to charge:
            // left a candidate, every later call asked for it again and owes() stayed true, which the queue reads as a failed
            // charge - a QueueObserver throwing after the moves then held the unit. Lost, it stays owed: the fenced release
            // after it finds the unit no longer this owner's.
            var leftOut = new HashSet<>(toCharge.keySet());
            leftOut.removeAll(charged.keySet());
            if (!leftOut.isEmpty() && writes.stillOwns(connection)) {
                gone.addAll(leftOut);
            }
        }
        for (var row : rows.values()) {
            if (!stillToSettle(row)) {
                continue;
            }
            if (row.move() == DeadLetterMove.HANDLED) {
                // Its handler completed, and the owner recorded it handled, after a move of it that threw with no answer from
                // the database: no move is made for it, but that one may have committed, so the dead-letter table is asked.
                // There, it was dead-lettered before its handler completed, and is reported as any move made here is
                var deadLettered = writes.isDeadLetteredOnRelease(connection, row.seq(), row.attemptsAfterCharge());
                movesInDoubt.remove(row.seq());
                if (deadLettered) {
                    recordMoved(row.moved(true), moved);
                }
                continue;
            }
            boolean movedNow;
            try {
                movedNow = writes.moveToDeadLetterIfOwned(connection, row.seq());
            } catch (SQLException | RuntimeException | Error e) {
                // Failed with no answer from the database (a connection lost, a read timed out), it may have committed on
                // the server all the same: the next attempt that moves nothing asks the table. An error the database
                // answered with rolled the move back - as for a release (releaseLeaseRows), the same rule
                if (LeaseRowState.mayHaveGoneThrough(e)) {
                    movesInDoubt.add(row.seq());
                }
                throw e;
            }
            var made = row.moved(movedNow || (movesInDoubt.contains(row.seq())
                                              && writes.isDeadLetteredOnRelease(connection, row.seq(), row.attemptsAfterCharge())));
            movesInDoubt.remove(row.seq());
            if (made.move() == DeadLetterMove.MOVED) {
                recordMoved(made, moved);
            } else {
                rows.put(row.seq(), made);
            }
        }
        return currentAnswer();
    }

    /**
     * Record {@code made}, a message found in the dead-letter table as {@link Writes#moveToDeadLetterIfOwned} moves it, count
     * it, queue it in {@code moved} for {@link #charge} to tell the {@code QueueObserver} of, and hand it to the owner
     * ({@code onMoved}). Called under {@link #monitor}.
     */
    private void recordMoved(ChargedRow made, List<ChargedRow> moved) {
        rows.put(made.seq(), made);
        // Counted and queued for the observer before the owner is handed it: the move has committed and no later call
        // reaches this line for the message again, so an onMoved that throws must not leave it uncounted and
        // unreported. The observer is told once charge() has let go of the monitor; an observer that throws leaves the
        // move made and remembered all the same.
        metrics.deadLettered.increment();
        moved.add(made);
        onMoved.accept(made);
    }

    /**
     * What {@link #charge} has charged and dead-lettered so far, without writing anything.
     */
    Answer answer() {
        synchronized (monitor) {
            return currentAnswer();
        }
    }

    /**
     * The message of {@code seq} as {@link #charge} dead-lettered it, if it did: its row is gone, so whatever its handler
     * reports later has nothing to acknowledge, no attempt to count and nothing to retry, and its dead letter carries
     * {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}, not the handler's outcome. For a handler that has not
     * completed - it threw, or was interrupted - so a move still to be made stays owed; a handler that completed asks
     * {@link #completedInHandler} instead.
     * <p>
     * Asked by a handler thread once its handler has returned, before the owner records the outcome, and after the owner
     * has marked the handler returned, so no {@link #charge} that takes its candidates after this call takes this message.
     * Answered without the monitor unless a running charge may move the message - it charges it, or its move was still to
     * be made when the charge started, or the charge is still taking its candidates - or its dead-letter move is still to
     * be made; then it waits for that charge to finish, so a move that has committed but is not recorded here yet is not
     * missed. A charge that cannot move the message is not waited for: it holds the monitor across its database round
     * trips, and the handlers of the messages it leaves alone - those a release past {@code stuckHandlerTimeout} spares, or an
     * earlier call charged below the limit - would wait out every one of them.
     */
    Optional<ChargedRow> deadLetteredWhileInHandler(long seq) {
        // Read before the row: a charge that was running is then either seen running, or its record is read below
        var charge = running;
        var row    = rows.get(seq);
        if ((charge != null && charge.mayMove(seq)) || (row != null && row.move() == DeadLetterMove.PENDING)) {
            synchronized (monitor) {
                row = rows.get(seq);
            }
        }
        return row != null && row.move() == DeadLetterMove.MOVED ? Optional.of(row) : Optional.empty();
    }

    /**
     * {@link #deadLetteredWhileInHandler} for a handler that completed - returned without an exception and not interrupted
     * - whose owner records the message handled unless this answers its dead letter: and when the move of the message is
     * still to be made ({@link DeadLetterMove#PENDING}), it is settled {@link DeadLetterMove#HANDLED} here, under the
     * monitor, so no {@link #charge} after this call makes it. The completion wins (class javadoc). A move made before the
     * handler completed stands: that message is answered, as by {@link #deadLetteredWhileInHandler}.
     * <p>
     * Asked at the same point as {@link #deadLetteredWhileInHandler} - once the handler has returned, after the owner has
     * marked it returned, before the owner records the outcome and without the owner's state lock - and waits for a running
     * charge the same way.
     */
    Optional<ChargedRow> completedInHandler(long seq) {
        var charge = running;
        var row    = rows.get(seq);
        if ((charge != null && charge.mayMove(seq)) || (row != null && row.move() == DeadLetterMove.PENDING)) {
            synchronized (monitor) {
                row = rows.get(seq);
                if (row != null && row.move() == DeadLetterMove.PENDING) {
                    row = row.handled();
                    rows.put(seq, row);
                }
            }
        }
        return row != null && row.move() == DeadLetterMove.MOVED ? Optional.of(row) : Optional.empty();
    }

    /**
     * Called under {@link #monitor}.
     */
    private Answer currentAnswer() {
        if (rows.isEmpty()) {
            return Answer.NONE;
        }
        return new Answer(rows.size(), rows.values().stream().filter(row -> row.move() == DeadLetterMove.MOVED).toList());
    }

    /**
     * {@code candidates} less those charged already and those a charge found {@link #gone}, in their order. Called under
     * {@link #monitor}.
     */
    private Map<Long, Candidate> notChargedYet(Map<Long, Candidate> candidates) {
        var notCharged = new LinkedHashMap<>(candidates);
        notCharged.keySet().removeAll(rows.keySet());
        notCharged.keySet().removeAll(gone);
        return notCharged;
    }
}
