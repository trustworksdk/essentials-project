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

import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId;
import dk.trustworks.essentials.shared.Exceptions;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
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
 *     {@link DeadLetterMove#MOVED} or {@link DeadLetterMove#ROW_GONE}, each in one write, so what was dead-lettered - the
 *     sequence values and keys {@link #answer()} reports, {@link #deadLetteredWhileInHandler}, and the row the owner is
 *     handed once it is {@link DeadLetterMove#MOVED} (the constructor's {@code onMoved}) - is derived from that write and
 *     cannot be seen half-made;</li>
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
 *     <li>so a caller of {@link #charge}, {@link #owes}, {@link #answer} or {@link #deadLetteredWhileInHandler} must not hold
 *     the owner's state lock, nor any lock those callbacks take: {@link #owes} and {@link #answer} take the monitor, and
 *     {@link #deadLetteredWhileInHandler} waits on it while a running charge may move the message. A caller holding one
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
     * The owner's fenced statements the charge runs - the two writes it makes and the lease lookup that explains a row the
     * charge left out - each fenced by the owner as the release after it is: the row must still name the owner's instance
     * under its fence, which releasing does not bump. Statements only: what the owner does once a message is dead-lettered
     * is the constructor's {@code onMoved}.
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
         * @return whether it moved the row: {@code false} when the row was gone or no longer the owner's
         */
        boolean moveToDeadLetterIfOwned(Connection connection, long seq) throws SQLException;

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
         * The move was made and moved nothing: the row was gone or no longer the owner's.
         */
        ROW_GONE
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

    private final MessageId.Lane                lane;
    private final int                           shard;
    private final Writes                        writes;
    private final RedeliveryPolicy              redeliveryPolicy;
    private final ShardOwnerMetrics             metrics;
    private final Consumer<Map<Long, Integer>>  onCharged;
    private final Consumer<ChargedRow>          onMoved;
    /**
     * Guards every write to {@link #rows}, and every read but {@link #deadLetteredWhileInHandler}'s, which takes it only to
     * wait for a running charge.
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
     * The {@link #charge} running, and the messages it may move to the dead-letter table; {@code null} while none runs. Set
     * and cleared under {@link #monitor}, read without it by {@link #deadLetteredWhileInHandler}, which waits for that charge
     * only when it may move the handler's message.
     */
    private volatile RunningCharge               running;

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
            return !notChargedYet(candidates).isEmpty() || rows.values().stream().anyMatch(row -> row.move() == DeadLetterMove.PENDING);
        }
    }

    /**
     * Charge one attempt to each of {@code candidates} not charged yet, then make every dead-letter move still owed.
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
     * @throws SQLException     when the charge, the lease lookup after a charge that left a row out, or a dead-letter move
     *                          fails; {@link #owes} then answers {@code true}. The {@code QueueObserver} is still told of each
     *                          move made before the failure, and what it threw is added to this as suppressed
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
                running = RunningCharge.TAKING_CANDIDATES;
                try {
                    var toCharge = notChargedYet(requireNonNull(candidates.get(), "No candidates supplied"));
                    var mayMove  = new HashSet<>(toCharge.keySet());
                    rows.values().stream().filter(row -> row.move() == DeadLetterMove.PENDING).forEach(row -> mayMove.add(row.seq()));
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
            if (row.move() != DeadLetterMove.PENDING) {
                continue;
            }
            var made = row.moved(writes.moveToDeadLetterIfOwned(connection, row.seq()));
            rows.put(row.seq(), made);
            if (made.move() == DeadLetterMove.MOVED) {
                // Counted and queued for the observer before the owner is handed it: the move has committed and no later call
                // reaches this line for the message again, so an onMoved that throws must not leave it uncounted and
                // unreported. The observer is told once charge() has let go of the monitor; an observer that throws leaves the
                // move made and remembered all the same.
                metrics.deadLettered.increment();
                moved.add(made);
                onMoved.accept(made);
            }
        }
        return currentAnswer();
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
     * {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}, not the handler's outcome.
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
