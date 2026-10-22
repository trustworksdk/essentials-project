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
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The charge an owner makes before its shard or ordered unit is released past handlers that have not returned, kept once
 * for both lanes: {@code ShardOwner.chargeInHandlerBeforeRelease} and {@code OrderedShardOwner.chargeKeysInHandlerBeforeRelease}
 * each compose one, and supply only what differs - which messages in a handler are candidates (unordered: those in a
 * handler longer than {@code stuckHandlerTimeout}; ordered: by {@code OrderedShardOwner.ReleaseChargeScope}), the writes
 * against their own table under their own fence ({@link Writes}), their lane, and where the charged counts go.
 * <p>
 * Whoever takes a released unit next charges nothing ({@link ShardOwnedStorage.LeaseGrant#previousOwnerReleased()}), and a
 * handler that hangs never returns to record its attempt, so without the charge a message whose handler hung on every
 * delivery was handed on with its count unchanged and never reached the dead-letter table. {@link #charge} charges one
 * attempt to each candidate this ledger has not charged yet and dead-letters each one the charge brings to the redelivery
 * limit with {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}, as the next owner runs no exhaustion pass for a
 * released unit.
 * <p>
 * One map holds the protocol's state, a {@link ChargedRow} per message charged, keyed by sequence value:
 * <ul>
 *     <li>recorded as soon as the charge has committed, so a release that failed and is retried does not charge a message
 *     again, and a message that has come into the candidates since is charged by the retry;</li>
 *     <li>its {@link DeadLetterMove}: {@link DeadLetterMove#PENDING} at the redelivery limit until the move is made, then
 *     {@link DeadLetterMove#MOVED} or {@link DeadLetterMove#ROW_GONE}, each in one write, so what was dead-lettered - the
 *     sequence values and keys {@link #answer()} reports, and {@link #deadLetteredWhileInHandler} - is derived from that
 *     write and cannot be seen half-made;</li>
 *     <li>written only under the monitor; read by the handler threads ({@link #deadLetteredWhileInHandler}) without it
 *     unless a charge is running or the message's move is still to be made, when they wait for that charge: a move commits
 *     before its write here, so a handler returning in between would otherwise take its message for one still in the
 *     table.</li>
 * </ul>
 * A {@code QueueObserver} told of a message dead-lettered here is told after the move, and what it throws is held until
 * every move is made, then thrown: by then nothing is owed ({@link #owes}), so the queue tells that failure from a failed
 * charge and releases the unit all the same. Callers do not run {@link #charge} for one owner at the same time; the monitor
 * makes it safe if they do.
 */
final class ReleaseChargeLedger {

    /**
     * The two writes the charge makes, each fenced by the owner as the release after it is: the row must still name the
     * owner's instance under its fence, which releasing does not bump.
     */
    interface Writes {
        /**
         * Charge one attempt to each row of {@code seqs} (see {@link ShardOwnedStorage#chargeRowsInHandler}).
         *
         * @return the attempt count after the charge of each row charged; a row gone or no longer the owner's is left out
         */
        Map<Long, Integer> chargeRowsInHandler(Connection connection, Collection<Long> seqs) throws SQLException;

        /**
         * Move the row of {@code seq} to the dead-letter table with {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}
         * (see {@link ShardOwnedStorage#moveToDeadLetterIfOwned}).
         *
         * @return whether it moved the row: {@code false} when the row was gone or no longer the owner's
         */
        boolean moveToDeadLetterIfOwned(Connection connection, long seq) throws SQLException;
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
     * A message charged before a release.
     *
     * @param seq                 its sequence value
     * @param key                 its key; {@code null} on the unordered lane, which has none
     * @param attemptsAfterCharge its attempt count after the charge
     * @param move                where its dead-letter move stands
     */
    record ChargedRow(long seq, String key, int attemptsAfterCharge, DeadLetterMove move) {
        ChargedRow {
            requireNonNull(move, "No move provided");
        }

        ChargedRow moved(boolean moved) {
            return new ChargedRow(seq, key, attemptsAfterCharge, moved ? DeadLetterMove.MOVED : DeadLetterMove.ROW_GONE);
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
     * Whether {@link #charge} is running: set and cleared under {@link #monitor}, read without it by
     * {@link #deadLetteredWhileInHandler}, which then waits for that charge.
     */
    private volatile boolean                     charging;

    /**
     * @param lane             the owner's lane, for the {@link MessageId} the {@code QueueObserver} is told of
     * @param shard            the owner's shard or ordered unit
     * @param writes           the owner's fenced writes
     * @param redeliveryPolicy the owner's redelivery policy, which says when a charged count is at the limit
     * @param metrics          the owner's metrics: the charge is counted in {@code takeoverAttemptBumps}, a dead letter in
     *                         {@code deadLettered}, and its {@code QueueObserver} is told of each dead letter
     * @param onCharged        told the attempt count after the charge of each message charged, under the monitor, as soon as
     *                         the charge has committed: the owner records them as its own counts, so a failure the
     *                         handler reports later - the release having failed, and the owner serving on - counts on from
     *                         the charge rather than writing an older count over it
     */
    ReleaseChargeLedger(MessageId.Lane lane,
                        int shard,
                        Writes writes,
                        RedeliveryPolicy redeliveryPolicy,
                        ShardOwnerMetrics metrics,
                        Consumer<Map<Long, Integer>> onCharged) {
        this.lane = requireNonNull(lane, "No lane provided");
        this.shard = shard;
        this.writes = requireNonNull(writes, "No writes provided");
        this.redeliveryPolicy = requireNonNull(redeliveryPolicy, "No redeliveryPolicy provided");
        this.metrics = requireNonNull(metrics, "No metrics provided");
        this.onCharged = requireNonNull(onCharged, "No onCharged provided");
    }

    /**
     * Whether {@link #charge} has anything to write for {@code candidates}: one this ledger has not charged yet, or a
     * charged message at the redelivery limit whose dead-letter move is still to be made. {@code false} after a call whose
     * {@code QueueObserver} threw when told of a dead letter, as it throws only after those writes, unless a candidate has
     * come in since. Lets the queue tell a failed charge, after which the unit is held and the release retried, from a
     * failure that came after the writes, after which it is released; and skip the connection {@link #charge} needs.
     *
     * @param candidates the messages in a handler the owner would charge, by sequence value, each with its key
     *                   ({@code null} on the unordered lane)
     */
    boolean owes(Map<Long, String> candidates) {
        requireNonNull(candidates, "No candidates provided");
        synchronized (monitor) {
            return !notChargedYet(candidates).isEmpty() || rows.values().stream().anyMatch(row -> row.move() == DeadLetterMove.PENDING);
        }
    }

    /**
     * Charge one attempt to each of {@code candidates} not charged yet, then make every dead-letter move still owed.
     *
     * @param candidates the messages in a handler to charge, by sequence value, each with its key ({@code null} on the
     *                   unordered lane)
     * @return what this ledger has charged and dead-lettered so far, this call included
     * @throws SQLException     when the charge or a dead-letter move fails; {@link #owes} then answers {@code true}
     * @throws RuntimeException what the {@code QueueObserver} threw when told of a dead letter (or a non-critical
     *                          {@link Error}), thrown once every move is made, so {@link #owes} then answers as after a call
     *                          that returned, and {@link #answer()} gives the answer
     */
    Answer charge(Connection connection, Map<Long, String> candidates) throws SQLException {
        requireNonNull(candidates, "No candidates provided");
        synchronized (monitor) {
            charging = true;
            try {
                return chargeAndMove(connection, candidates);
            } finally {
                charging = false;
            }
        }
    }

    /**
     * {@link #charge}'s work. Called under {@link #monitor}.
     */
    private Answer chargeAndMove(Connection connection, Map<Long, String> candidates) throws SQLException {
        var toCharge = notChargedYet(candidates);
        if (!toCharge.isEmpty()) {
            var charged = writes.chargeRowsInHandler(connection, toCharge.keySet());
            metrics.takeoverAttemptBumps.add(charged.size());
            // The charge has committed: recorded before anything else can fail, or a retried release charges again
            charged.forEach((seq, attempts) -> rows.put(seq, new ChargedRow(seq, toCharge.get(seq), attempts,
                                                                            redeliveryPolicy.isExhausted(attempts) ? DeadLetterMove.PENDING : DeadLetterMove.NONE_OWED)));
            onCharged.accept(charged);
        }
        Throwable observerFailure = null;
        for (var row : rows.values()) {
            if (row.move() != DeadLetterMove.PENDING) {
                continue;
            }
            boolean moved;
            try {
                moved = writes.moveToDeadLetterIfOwned(connection, row.seq());
            } catch (SQLException | RuntimeException | Error e) {
                if (observerFailure != null) {
                    e.addSuppressed(observerFailure);
                }
                throw e;
            }
            rows.put(row.seq(), row.moved(moved));
            if (moved) {
                // Counted before the observer is told: one that throws leaves the move made and remembered, so no later
                // call reaches this line for the message again.
                metrics.deadLettered.increment();
                try {
                    metrics.observer().deadLettered(new MessageId(lane, shard, row.seq()), row.attemptsAfterCharge(),
                                                    new IllegalStateException(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER));
                } catch (RuntimeException | Error e) {
                    // The other moves are made first, and then this is thrown: with nothing left owed (owes), the queue
                    // releases the unit all the same.
                    Exceptions.rethrowIfCriticalError(e);
                    if (observerFailure == null) {
                        observerFailure = e;
                    } else if (!observerFailure.equals(e)) {
                        observerFailure.addSuppressed(e);
                    }
                }
            }
        }
        if (observerFailure != null) {
            Exceptions.sneakyThrow(observerFailure);
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
     * Asked by a handler thread once its handler has returned, before the owner records the outcome. Answered without the
     * monitor while no charge is running and the message has no dead-letter move still to be made; otherwise it waits for
     * the running charge to finish, so a move that has committed but is not recorded here yet is not missed. A charge that
     * starts after this call is not waited for.
     */
    Optional<ChargedRow> deadLetteredWhileInHandler(long seq) {
        // Read before the row: a charge that was running is then either seen running, or its record is read below
        var chargeRunning = charging;
        var row           = rows.get(seq);
        if (chargeRunning || (row != null && row.move() == DeadLetterMove.PENDING)) {
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
     * {@code candidates} less those charged already, in their order. Called under {@link #monitor}.
     */
    private Map<Long, String> notChargedYet(Map<Long, String> candidates) {
        var notCharged = new LinkedHashMap<>(candidates);
        notCharged.keySet().removeAll(rows.keySet());
        return notCharged;
    }
}
