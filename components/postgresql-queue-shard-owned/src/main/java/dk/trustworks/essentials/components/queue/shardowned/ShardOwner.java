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
import dk.trustworks.essentials.components.queue.shardowned.spi.RedeliveryRequested;
import dk.trustworks.essentials.shared.Exceptions;
import org.slf4j.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Owns one shard: reads it, delivers from it, and acknowledges on its behalf. One instance per
 * owned shard, single-threaded, holding all of its own state in memory.
 * <p>
 * Everything expensive about a conventional queue consumer is absent here by construction, and each
 * absence is the point rather than an optimisation:
 * <ul>
 *     <li><b>No claim write.</b> The lease already says this shard is ours, so "this message is
 *         mine" never reaches the database. The steady-state cost of a message is one insert and one
 *         delete.</li>
 *     <li><b>No {@code SKIP LOCKED}.</b> Nobody else reads this shard, so there is nothing to skip
 *         and no scan work is wasted on rows another consumer already took.</li>
 *     <li><b>No stall on a hole.</b> A gap in the sequence means a concurrent enqueue has not
 *         committed yet. The cursor keeps moving and the hole is chased separately, so one unlucky
 *         message pays for its own lateness instead of the whole shard paying for it.</li>
 * </ul>
 * Single-threaded ownership is what makes the hole set, the in-flight set and the cursor safe to
 * keep as plain fields.
 */
final class ShardOwner implements LeasedOwner {
    private static final Logger log = LoggerFactory.getLogger(ShardOwner.class);

    private final    ShardOwnedStorage                            storage;
    private final    int                                          shard;
    private final    long                                         fence;
    private final    ShardOwnerSettings                           settings;
    private final    PayloadHandler                               handler;
    private final    ShardOwnerMetrics                            metrics;
    private final    RedeliveryPolicy                             redeliveryPolicy;
    private final    ShardWakeup                                  wakeup;
    private final    String                                       instanceId;
    /**
     * Whether this owner's instance has confirmed its own liveness recently enough to dispatch —
     * supplied by {@link ShardOwnedQueue} after construction rather than through a constructor
     * already at its argument ceiling. Null for an owner built without one, which then always
     * dispatches.
     */
    private volatile BooleanSupplier                              deliveryGate;
    /**
     * Handlers run here, not on the pump thread.
     * <p>
     * They used to run inline, which was safe while every shard had a thread of its own: a slow
     * handler stalled only its own shard. A pump serves several shards, so inline delivery would let
     * one slow handler stall shards it has nothing to do with. Dispatching to a shared virtual-thread
     * executor keeps shards independent without a platform thread per shard, and
     * {@link ShardOwnerSettings#keyConcurrency} bounds how many messages one shard may have in flight.
     */
    private final    HandlerDispatch                              dispatch;
    /**
     * Guards everything the pump thread and the handler threads both touch. Delivery used to be
     * inline and every field below was single-threaded; it is not any more.
     */
    private final    Object                                       stateLock     = new Object();
    /**
     * Where this owner's lease row stands ({@link LeasedOwner#leaseRow()}). Anything but {@code HELD} stops the owner
     * dispatching, rather than racing its successor. Every transition goes through {@link LeaseRowState#advanceTo}, one
     * compare-and-set, not {@link #stateLock}: no field the lock guards changes with it, and the pump's selection check
     * on every pass reads it without taking the lock the handler threads contend for.
     */
    private final    LeaseRowState                                leaseRow      = new LeaseRowState();
    /**
     * Set by {@link #beginDraining()}: nothing new is read or dispatched, acknowledgements still flush.
     */
    private final    AtomicBoolean                                draining      = new AtomicBoolean();
    /**
     * Messages handed straight from a local enqueue, bypassing the read path entirely.
     * <p>
     * The head sweep deliberately does NOT exclude locally handed-off rows. If a hand-off is ever
     * lost — the process dies between commit and dispatch, or the queue is drained on shutdown — the
     * sweep is what still delivers the message, filtered by the owner's own in-memory dedup. Making
     * the sweep exclude them would turn a lost hand-off into a permanently stuck message.
     */
    private final    Queue<ShardOwnedStorage.Row> localHandoffs = new ConcurrentLinkedQueue<>();

    /**
     * The in-memory retry schedule — §4.6's timer wheel at the scale one shard needs.
     * <p>
     * A failed message is re-dispatched from here without ever being read back. The database write
     * that accompanies a failure exists only so a crash does not lose the backoff; the row sits in
     * the table with {@code visible_at} in the future, where the head sweep will find it if this
     * owner dies. Retries therefore cost the read path nothing at all.
     */
    private final Queue<Retry>         retrySchedule = new PriorityQueue<>(Comparator.comparingLong(Retry::dueNanos));
    private final Map<Long, Integer>   attemptsBySeq = new HashMap<>();

    /**
     * Highest sequence value read. Advances past holes rather than waiting on them.
     */
    private       long                cursor;
    /**
     * Where the cursor started when this owner took the shard. A row at or below it can only be delivered by the head
     * sweep - a late commit, or a row that was not readable yet - so the range acknowledgement never reaches it; it is
     * acknowledged by exact sequence value. See {@link ShardOwnedStorage#takeoverCursor}
     */
    private       long                takeoverCursor;
    private       boolean             cursorPositioned;
    /**
     * Until when the head sweep runs at its base interval without backing off: {@code holeExpiry} after the takeover,
     * the window in which a late commit below {@link #takeoverCursor} would once have been chased as a hole
     */
    private       long                sweepPinnedUntilNanos;
    /**
     * Sequence values the cursor stepped over, mapped to when they were first missed.
     */
    private final NavigableMap<Long, Long> pendingHoles = new TreeMap<>();
    /**
     * In a handler: delivered and not yet returned, so a sweep does not deliver them twice. Each with when it was handed to
     * the handler executor, by {@link System#nanoTime()}, which {@link #chargeInHandlerBeforeRelease} times
     * {@code stuckHandlerTimeout} from.
     */
    private final NavigableMap<Long, Long> inFlight     = new TreeMap<>();
    /**
     * The messages in {@link #inFlight} whose handler has returned or thrown, while the owner records the outcome (still
     * inside the in-flight slot): left out of the candidates of {@link #chargeInHandlerBeforeRelease}. Marked before the
     * handler thread asks {@link ReleaseChargeLedger#completedInHandler} or {@link ReleaseChargeLedger#deadLetteredWhileInHandler},
     * so a charge that takes its candidates after that leaves the message to the outcome its handler records, and one that
     * took them before is seen running and waited for. Charged regardless, a message whose handler had completed was acknowledged and dead-lettered both.
     * <p>
     * A handler that failed is left out too while it records its failure, and that record is a fenced write: when the
     * release commits before it, the write is refused (the lease is no longer this owner's), and the next owner, finding
     * the shard released, charges nothing - that delivery's attempt is not counted. Accepted: the window runs only from the
     * mark until that write commits, and charged in it, the delivery would be counted twice (by the charge and by its own
     * failure write) or dead-lettered by the charge while its handler schedules a retry of it.
     */
    private final Set<Long>                returnedFromHandler = new HashSet<>();
    /**
     * Handled and awaiting the next ack flush.
     */
    private final NavigableSet<Long>       pendingAcks  = new TreeSet<>();

    private long lastChaseNanos;
    private long lastSweepNanos;
    /**
     * Current sweep interval, doubling while the shard stays empty and reset the moment anything
     * arrives. The sweep is the backstop for a lost notification, not the delivery path, so a quiet
     * shard sweeping every thirty seconds instead of twice a second costs recovery time for a rare
     * failure rather than latency for a normal message — and it is the difference between 4 queries a
     * second per idle shard and 0.07.
     */
    private long sweepIntervalNanos;
    private long lastAckFlushNanos;
    /**
     * When this shard's earliest delayed row becomes visible, in {@link System#nanoTime()} terms, or
     * {@link Long#MAX_VALUE} when nothing is waiting. Refreshed by the sweep and used as a park
     * deadline, so a delayed message wakes the pump when it is due rather than when the backstop
     * happens to fire.
     */
    private long nextVisibleAtNanos = Long.MAX_VALUE;

    /**
     * Whether the takeover charge has been made - or is not owed, because the previous owner released the shard
     * cleanly. {@link #onTakeover} runs once per pump connection, a reconnect included, and again on every pump
     * pass until it has completed; charging again on every reconnect cost the in-flight window an attempt each
     * time the database blipped. Set as soon as the charge has committed, not after the exhaustion pass that
     * follows it: a pass that failed left the charge unrecorded, and the call that retried it charged the window
     * a second time.
     */
    private boolean takeoverCharged;

    /**
     * Whether the exhaustion dead-letter pass that follows the takeover charge has yet to complete. Set with
     * {@link #takeoverCharged} and cleared once the pass has run, so a pass that failed is retried - on the pump's
     * next pass while the connection is healthy, which keeps the pump serving its other shards meanwhile, or on
     * the next connection when it is not - and only then. A plain reconnect, with no new charge behind it, must
     * not run the pass: it would park a message this owner is itself delivering on its last attempt.
     */
    private boolean exhaustionPending;

    /**
     * The charge {@link #chargeInHandlerBeforeRelease} makes, shared with the ordered lane: the messages charged before a
     * release past their handlers, and their dead-letter moves. The handler of a message it dead-lettered that completes or
     * throws later records nothing against the row, which is gone: no acknowledgement, no attempt, no retry - a WARN instead
     * (an interrupted one is counted in {@code abandonedOnInterrupt} only)
     * ({@link #completedAfterItsDeadLetter}, {@link #failedAfterItsDeadLetter}).
     */
    private final ReleaseChargeLedger releaseChargeLedger;
    /**
     * The rate limit of the WARN that a release left this owner out, a dead-letter move of it still owed
     * ({@link #owedMoveReleaseRefusals()}).
     */
    private final OwedMoveReleaseRefusals owedMoveReleaseRefusals = new OwedMoveReleaseRefusals();

    ShardOwner(ShardOwnedStorage storage,
               int shard,
               long fence,
               ShardOwnerSettings settings,
               PayloadHandler handler,
               ShardOwnerMetrics metrics,
               RedeliveryPolicy redeliveryPolicy,
               ShardWakeup wakeup,
               String instanceId,
               HandlerDispatch dispatch,
               boolean previousOwnerReleased) {
        this.storage = requireNonNull(storage, "No storage provided");
        this.shard = shard;
        this.fence = fence;
        this.settings = requireNonNull(settings, "No settings provided");
        this.sweepIntervalNanos = settings.sweepIntervalNanos();
        this.handler = requireNonNull(handler, "No handler provided");
        this.metrics = requireNonNull(metrics, "No metrics provided");
        this.redeliveryPolicy = requireNonNull(redeliveryPolicy, "No redeliveryPolicy provided");
        this.wakeup = requireNonNull(wakeup, "No wakeup provided");
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.dispatch = requireNonNull(dispatch, "No dispatch provided");
        this.takeoverCharged = previousOwnerReleased;
        this.releaseChargeLedger = new ReleaseChargeLedger(MessageId.Lane.UNORDERED, shard, new ReleaseChargeLedger.Writes() {
            @Override
            public Map<Long, Integer> chargeRowsInHandler(Connection connection, Collection<Long> seqs) throws SQLException {
                return storage.chargeRowsInHandler(connection, ShardOwnedSchema.UNORDERED_TABLE, shard, seqs, instanceId, fence);
            }

            @Override
            public boolean moveToDeadLetterIfOwned(Connection connection, long seq) throws SQLException {
                return storage.moveToDeadLetterIfOwned(connection, ShardOwnedSchema.UNORDERED_TABLE, "unordered", shard, seq,
                                                       ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER, instanceId, fence);
            }

            @Override
            public boolean isDeadLetteredOnRelease(Connection connection, long seq, int attempts) throws SQLException {
                return storage.isDeadLetteredWith(connection, "unordered", shard, seq, attempts, ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER);
            }

            @Override
            public boolean stillOwns(Connection connection) throws SQLException {
                return storage.stillOwns(connection, "unordered", shard, instanceId, fence);
            }
        }, redeliveryPolicy, metrics, this::recordChargedAttempts, deadLettered -> {
            // No key to block: a dead letter holds nothing back on this lane
        });
    }

    /**
     * See {@link LeasedOwner#deliveryPermitted()}. Set once, by the queue that built this owner.
     */
    void setDeliveryGate(BooleanSupplier deliveryGate) {
        this.deliveryGate = deliveryGate;
    }

    @Override
    public boolean deliveryPermitted() {
        var gate = deliveryGate;
        return gate == null || gate.getAsBoolean();
    }

    @Override
    public void endKeepingLeaseRow() {
        if (leaseRow.advanceTo(LeaseRow.ENDED_ROW_HELD) != LeaseRow.HELD) {
            return;
        }
        log.debug("Shard {}: owner stopping under fence {}; its lease row is still to be released", shard, fence);
        // So the pump drops it on its next pass rather than at its backstop.
        wakeup.signal();
    }

    @Override
    public void onLeaseEnded(LeaseEnd reason) {
        var was = leaseRow.advanceTo(LeaseRow.RELEASED);
        if (was == LeaseRow.RELEASED) {
            return;
        }
        // A unit found gone after a release of its row failed with no error answer from the database
        // (LeaseRowState#mayHaveGoneThrough) is read as given up by that release, which may have gone through on the
        // server (markReleaseInDoubt), not as a lease lost.
        var lost = reason == LeaseEnd.TAKEN && !leaseRow.releaseInDoubt();
        if (lost) {
            // Counted by the call that moved the row: a refused takeover charge may have found the same loss first
            metrics.leasesLost.increment();
        }
        // Per unit, and DEBUG for both: the caller knows how many units it just moved and says so
        // once. At a two-instance handover this fires 32 times in one heartbeat tick.
        log.debug("Shard {}: lease under fence {} {}{}",
                  shard, fence, lost ? "taken" : reason == LeaseEnd.TAKEN ? "found given up by a release whose answer was not received" : "released",
                  was == LeaseRow.HELD ? ", owner stopping" : "");
        if (was == LeaseRow.HELD) {
            wakeup.signal();
        }
    }

    @Override
    public LeaseRow leaseRow() {
        return leaseRow.get();
    }

    @Override
    public void markReleaseInDoubt() {
        leaseRow.markReleaseInDoubt();
    }

    @Override
    public boolean releaseInDoubt() {
        return leaseRow.releaseInDoubt();
    }

    @Override
    public int shard() {
        return shard;
    }

    @Override
    public short queueId() {
        return storage.queueId();
    }

    @Override
    public ShardOwnerMetrics ownerMetrics() {
        return metrics;
    }

    @Override
    public boolean needsAttention() {
        if (wakeup.consume()) {
            metrics.wakeupsHonoured.increment();
            return true;
        }
        if (draining.get()) {
            // A drain only retires acknowledgements when this owner is pumped, and pumping it is cheap:
            // it reads nothing, and flushes only when something finished.
            return true;
        }
        if (!localHandoffs.isEmpty()) {
            return true;
        }
        var now = System.nanoTime();
        // The time-based cases are the backstop, and they are why losing every notification costs
        // latency and nothing else.
        if (now - lastSweepNanos >= sweepIntervalNanos) {
            return true;
        }
        // A delayed message that has come due. Its row was invisible when the cursor passed it, so
        // nothing else will notice it has become visible.
        if (nextVisibleAtNanos != Long.MAX_VALUE && now >= nextVisibleAtNanos) {
            return true;
        }
        synchronized (stateLock) {
            if (!retrySchedule.isEmpty() && retrySchedule.peek().dueNanos() <= now) {
                return true;
            }
            if (!pendingHoles.isEmpty() && now - lastChaseNanos >= settings.chaseDelayNanos()) {
                return true;
            }
            return !pendingAcks.isEmpty() && now - lastAckFlushNanos >= settings.ackFlushIntervalNanos();
        }
    }

    @Override
    public void onTakeover(Connection connection) throws SQLException {
        if (!cursorPositioned) {
            var start = storage.takeoverCursor(connection, shard);
            synchronized (stateLock) {
                cursor = start;
                takeoverCursor = start;
            }
            sweepPinnedUntilNanos = System.nanoTime() + settings.holeExpiry().toNanos();
            cursorPositioned = true;
        }
        if (!takeoverCharged) {
            var charged = storage.bumpAttemptsOnTakeover(connection, shard, dispatch.capacity(), settings.ackBatchSize(), instanceId, fence);
            if (charged == 0 && !storage.stillOwns("unordered", shard, instanceId, fence)) {
                // The charge is fenced: this owner's lease moved on before its pump took it over, and its charge would
                // have fallen on rows the successor may be delivering. Nothing else here is this owner's to do.
                leaseLostBeforeTakeover();
                return;
            }
            metrics.takeoverAttemptBumps.add(charged);
            // The bump has committed: record it before anything else can fail, or the next call charges again
            takeoverCharged = true;
            exhaustionPending = true;
        }
        if (!exhaustionPending) {
            return;
        }
        // A message whose handler took the process down every time never fails, so the failure path never parks
        // it. The charge above is the only count it has; once that reaches the policy's limit it is parked here.
        for (var seq : storage.exhaustedAfterTakeover(connection, ShardOwnedSchema.UNORDERED_TABLE, shard,
                                                       redeliveryPolicy.maxAttempts())) {
            if (storage.moveToDeadLetterIfOwned(connection, ShardOwnedSchema.UNORDERED_TABLE, "unordered", shard, seq,
                                                ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS, instanceId, fence)) {
                // Counted before the observer is told: the move has committed, so an observer that throws must not take
                // the count with it - no later pass finds the row to move again.
                metrics.deadLettered.increment();
                metrics.observer().deadLettered(new MessageId(MessageId.Lane.UNORDERED, shard, seq),
                                                redeliveryPolicy.maxAttempts(), new IllegalStateException(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS));
            }
        }
        exhaustionPending = false;
    }

    /**
     * The takeover charge was refused by its fence: the shard's lease row no longer names this instance under this
     * owner's fence. As on the acknowledgement path, that is the lease lost; the pump drops the owner at the end of its pass.
     * Counted in {@code leasesLost} when this call moved the owner out of {@code HELD}: the unit was taken from an owner
     * that served it. The queue's lease check may find the same unit taken; its {@link #onLeaseEnded} counts only when it
     * is the call that moves the row to {@code RELEASED}, so whichever of the two gets there first counts the loss, once.
     * Not counted when the owner was no longer {@code HELD}, which its pump does not check before the takeover: it runs the
     * takeover of every owner it was handed and drops the ended ones only at the end of that pass. Two cases reach that:
     * <ul>
     *     <li>{@code stop()} had ended the owner ({@code ENDED_ROW_HELD}), and its row was since released by that stop, or
     *     taken by another instance before the stop released it.</li>
     *     <li>The owner was already {@code RELEASED}: this instance released its row (a stop, or a rebalance shedding it),
     *     or the queue's lease check found the unit taken and counted it in {@code leasesLost} then.</li>
     * </ul>
     * Nor when a release of the row had failed with no error answer from the database (a lost connection or a timed-out
     * read, {@link LeaseRowState#mayHaveGoneThrough}; {@link #markReleaseInDoubt()}): that release may have gone through
     * on the server, the row given up by this instance itself; logged at DEBUG.
     */
    void leaseLostBeforeTakeover() {
        var was = leaseRow.advanceTo(LeaseRow.RELEASED);
        if (was == LeaseRow.HELD && !leaseRow.releaseInDoubt()) {
            metrics.leasesLost.increment();
            log.warn("Shard {}: takeover charge rejected under fence {} — lease lost, stopping", shard, fence);
        } else if (was == LeaseRow.HELD) {
            log.debug("Shard {}: takeover charge rejected under fence {} after a release of the row whose answer was not received; "
                              + "taken as given up by that release, stopping", shard, fence);
        } else {
            log.debug("Shard {}: takeover charge rejected under fence {}; the owner had already stopped", shard, fence);
        }
    }

    @Override
    public void flushOnStop(Connection connection) throws SQLException {
        flushAcks(connection, true);
    }

    @Override
    public long parkDeadlineMillis() {
        var now = System.nanoTime();
        // The sweep counts as a deadline too: a pump parked on its backstop must still come back in
        // time to run each of its shards' sweeps, whatever the backstop happens to be set to.
        var untilSweep = (sweepIntervalNanos - (now - lastSweepNanos)) / 1_000_000L;
        var deadline   = Math.max(0L, untilSweep);
        if (nextVisibleAtNanos != Long.MAX_VALUE) {
            deadline = Math.min(deadline, Math.max(0L, (nextVisibleAtNanos - now) / 1_000_000L));
        }
        synchronized (stateLock) {
            if (!retrySchedule.isEmpty()) {
                var untilRetry = Math.max(0L, (retrySchedule.peek().dueNanos() - now) / 1_000_000L);
                deadline = Math.min(deadline, untilRetry);
            }
            if (!pendingAcks.isEmpty()) {
                // Acknowledgements are a deadline too. Without this the pump could park until the
                // next sweep — up to maxSweepInterval once it has backed off — while handled
                // messages sat undeleted, which showed up as a queue that drained tens of seconds
                // after its last delivery.
                var untilFlush = Math.max(0L, (settings.ackFlushIntervalNanos() - (now - lastAckFlushNanos)) / 1_000_000L);
                deadline = Math.min(deadline, untilFlush);
            }
        }
        return deadline;
    }

    /**
     * Accept a message the local enqueue already holds. Called after its transaction commits — never
     * before, or a rollback would deliver a message that does not exist.
     */
    public void handOffLocally(ShardOwnedStorage.Row row) {
        localHandoffs.add(row);
        metrics.localHandoffs.increment();
        wakeup.signal();
    }

    @Override
    public void beginDraining() {
        if (draining.compareAndSet(false, true)) {
            wakeup.signal();
        }
    }

    @Override
    public int inFlight() {
        synchronized (stateLock) {
            return inFlight.size();
        }
    }

    @Override
    public int pumpOnce(Connection connection) throws SQLException {
        if (draining.get()) {
            // Nothing new: not the local hand-offs, not the cursor, not a retry. A hand-off left
            // undelivered is a pre-claimed row, which the next owner picks up - the same recovery a
            // hand-off lost to a crash gets. Only what finished is acknowledged.
            flushAcks(connection, true);
            return 0;
        }
        // Nothing useful to read with no budget to dispatch into: the rows would only be re-read.
        // Acknowledgements still have to go out, or the shard would never retire what it finished.
        if (dispatch.saturated()) {
            maybeFlush(connection, System.nanoTime());
            return 0;
        }
        var delivered = 0;
        while (dispatch.tryAcquire()) {
            var handed = localHandoffs.poll();
            if (handed == null) {
                dispatch.release();
                break;
            }
            synchronized (stateLock) {
                cursor = Math.max(cursor, handed.seq());
            }
            if (deliver(handed)) {
                delivered++;
            }
        }
        // Locally handed-off work needs no read at all — that is the entire point of Tier 2. Issuing
        // the cursor read anyway turned every hand-off into a database round trip and pushed commits
        // per message from 3.1 to 6.0 in the per-message-enqueue configuration, which is the opposite
        // of what the tier is for. When local work was found, process it and come back.
        if (delivered > 0) {
            metrics.readsSkippedByHandoff.increment();
            return delivered;
        }

        long readFrom;
        synchronized (stateLock) {
            readFrom = cursor;
        }
        // Not much more than can be dispatched. Rows past the free permits are not dispatched and the cursor does not
        // move past them, so they are read again next time: reading readBatchSize (500) rows carried 24 rows - payloads
        // included - over the wire per delivered message (ShardOwnedReadAmplificationIT). Capping the read at the free
        // permits alone cut that to 1, but doubled the reads and the drain time: fast handlers free permits while the
        // loop below is still dispatching, and a larger read keeps feeding them. 64, or twice the free permits for a
        // consumer with more, kept the read count and drain time and still cut the rows read to about 5 per message.
        var readLimit = Math.min(settings.readBatchSize(), Math.max(64, 2 * dispatch.availablePermits()));
        var rows      = storage.readFromCursor(connection, shard, readFrom, readLimit, fence);
        metrics.cursorReads.increment();
        metrics.cursorRowsRead.add(rows.size());

        for (var row : rows) {
            // Before the cursor moves, never after. A row we cannot dispatch must still be there for
            // the next read; advancing past it would leave it to the head sweep and register the gap
            // as a hole that no transaction ever owned.
            if (!dispatch.tryAcquire()) {
                break;
            }
            synchronized (stateLock) {
                // Everything skipped over is a hole: allocated by a transaction that has not committed.
                for (var missing = cursor + 1; missing < row.seq(); missing++) {
                    if (pendingHoles.putIfAbsent(missing, System.nanoTime()) == null) {
                        metrics.holesObserved.increment();
                    }
                }
                cursor = Math.max(cursor, row.seq());
            }
            if (deliver(row)) {
                delivered++;
            }
        }

        var now = System.nanoTime();
        delivered += dispatchDueRetries(connection, now);
        if (!pendingHoles.isEmpty() && now - lastChaseNanos >= settings.chaseDelayNanos()) {
            delivered += chaseHoles(connection);
            lastChaseNanos = now;
        }
        var sweptThisPass = false;
        if (now - lastSweepNanos >= sweepIntervalNanos) {
            delivered += sweepFromHead(connection);
            lastSweepNanos = now;
            sweptThisPass = true;
        }
        maybeFlush(connection, now);
        adjustSweepBackoff(delivered, sweptThisPass);
        return delivered;
    }

    /**
     * Grow the sweep interval while nothing arrives, and snap it back the instant something does.
     * <p>
     * <b>Only when a sweep actually ran.</b> This used to double on every pump iteration, and a pump
     * iterates whenever any shard it serves has work — not on the sweep cadence. An idle shard beside
     * a busy one therefore reached the thirty-second ceiling within milliseconds instead of over half
     * a minute, which is not a tuning nicety: the sweep is how a delayed message is noticed and how a
     * lost notification is recovered, so both silently became thirty-second operations.
     */
    private void adjustSweepBackoff(int delivered, boolean sweptThisPass) {
        if (delivered > 0 || System.nanoTime() < sweepPinnedUntilNanos) {
            sweepIntervalNanos = settings.sweepIntervalNanos();
            return;
        }
        if (!sweptThisPass) {
            return;
        }
        var ceiling = Math.max(settings.sweepIntervalNanos(), settings.maxSweepIntervalNanos());
        sweepIntervalNanos = Math.min(ceiling, sweepIntervalNanos * 2);
    }

    private void maybeFlush(Connection connection, long now) throws SQLException {
        boolean due;
        synchronized (stateLock) {
            due = !pendingAcks.isEmpty()
                    && (pendingAcks.size() >= settings.ackBatchSize()
                    || now - lastAckFlushNanos >= settings.ackFlushIntervalNanos());
        }
        if (due) {
            flushAcks(connection, false);
            lastAckFlushNanos = now;
        }
    }


    private int chaseHoles(Connection connection) throws SQLException {
        List<Long> candidates;
        synchronized (stateLock) {
            candidates = pendingHoles.keySet().stream().limit(settings.maxHolesPerChase()).toList();
        }
        var found = storage.readSpecific(connection, shard, candidates);
        metrics.holeChaseQueries.increment();

        var delivered = 0;
        for (var row : found) {
            if (!dispatch.tryAcquire()) {
                break;
            }
            Long missedAt;
            synchronized (stateLock) {
                missedAt = pendingHoles.remove(row.seq());
            }
            if (missedAt != null) {
                metrics.holeResolutionNanos.add(System.nanoTime() - missedAt);
                metrics.holesResolved.increment();
            }
            if (deliver(row)) {
                delivered++;
            }
        }

        // A value that never appears was burned by an aborted transaction. Abandoning it has to be
        // bounded, or one rollback keeps a shard chasing forever.
        var now = System.nanoTime();
        synchronized (stateLock) {
            pendingHoles.entrySet().removeIf(entry -> {
                if (now - entry.getValue() > settings.holeExpiryNanos()) {
                    metrics.holesAbandoned.increment();
                    return true;
                }
                return false;
            });
            metrics.maxPendingHoles.accumulateAndGet(pendingHoles.size(), Math::max);
        }
        return delivered;
    }

    /**
     * The correctness backstop. Reads from the head regardless of cursor and delivers anything not
     * recognised — which is how a late commit behind the cursor, or work a crashed owner abandoned,
     * still gets delivered without any of it being tracked durably.
     */
    private int sweepFromHead(Connection connection) throws SQLException {
        var rows = storage.sweepFromHead(connection, shard, settings.readBatchSize(), fence,
                                         settings.holeExpiry().toMillis());
        metrics.headSweeps.increment();
        var untilNext = storage.millisUntilNextVisible(connection, shard);
        nextVisibleAtNanos = untilNext.isPresent()
                             ? System.nanoTime() + untilNext.getAsLong() * 1_000_000L
                             : Long.MAX_VALUE;
        var delivered = 0;
        for (var row : rows) {
            if (!dispatch.tryAcquire()) {
                break;
            }
            if (deliver(row)) {
                metrics.sweepRecoveries.increment();
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Deliver inline, on the pump thread.
     * <p>
     * <b>This was asynchronous for a while, and it was a mistake.</b> Dispatching to a virtual thread
     * decouples a slow handler from the other shards a pump serves, which is a real benefit — but it
     * makes the cursor, the hole map, the in-flight set and the acknowledgement floor concurrent, and
     * those four are what the design's correctness rests on. It produced five distinct defects in one
     * change: a dead pump thread from an unguarded iteration, a message dead-lettered because its
     * in-flight slot was released before its failure was recorded, 350 of 500 hand-offs discarded by a
     * poll-before-check, the head sweep quietly taking over 97% of delivery from the fast path, and a
     * lease wrongly dropped because an acknowledgement that matched no rows was read as a fencing
     * rejection.
     * <p>
     * Inline delivery means a slow handler occupies its pump, and therefore the other shards that
     * pump serves. That is a real cost, and the honest one to pay for now: it is bounded and tunable
     * through {@link ShardOwnerSettings#pumpThreads}, whereas the alternative was an engine whose
     * tests passed while the mechanism underneath them had stopped working.
     */
    private boolean deliver(ShardOwnedStorage.Row row) {
        synchronized (stateLock) {
            if (inFlight.containsKey(row.seq()) || pendingAcks.contains(row.seq())) {
                // Already ours. Hand the permit back — it was taken speculatively by the caller.
                dispatch.release();
                return false;
            }
            inFlight.put(row.seq(), System.nanoTime());
            // Whichever path gets there first invalidates the other. A failed message exists in two
            // places timed by different clocks — the table's `visible_at` by the database's now(),
            // the in-memory schedule by System.nanoTime() — so the sweep can deliver, acknowledge
            // and delete a row whose schedule entry then fires against a message that is gone.
            retrySchedule.removeIf(pending -> pending.row().seq() == row.seq());
        }
        dispatch.execute(() -> runHandler(row));
        return true;
    }

    private void runHandler(ShardOwnedStorage.Row row) {
        var handled      = false;
        var interrupted  = false;
        var startedNanos = System.nanoTime();
        try {
            try {
                handler.handle(new MessageId(MessageId.Lane.UNORDERED, shard, row.seq()),
                               row.payload(), row.payloadType());
                markReturnedFromHandler(row.seq());
                // An interrupted handler has NOT necessarily finished its work. A handler that catches
                // InterruptedException and returns normally — which is what well-behaved code does on
                // shutdown — would otherwise be indistinguishable from success, and the message would
                // be acknowledged without having been processed. That is silent message loss, and it
                // showed up the first time the crash-recovery test ran: the outgoing owner acked
                // everything it had dispatched, leaving the incoming owner nothing to redeliver.
                if (Thread.currentThread().isInterrupted()) {
                    metrics.abandonedOnInterrupt.increment();
                    interrupted = true;
                } else {
                    handled = true;
                }
            } catch (RuntimeException | Error e) {
                // A non-critical Error (an AssertionError, say) is a failed delivery like any other. Letting it
                // escape uncounted freed the slot without recording an attempt, so the message came back
                // through the head sweep forever and never reached the dead-letter table. A critical one
                // (VirtualMachineError, LinkageError) still propagates, as it does from the classic queue.
                Exceptions.rethrowIfCriticalError(e);
                markReturnedFromHandler(row.seq());
                if (!(e instanceof RedeliveryRequested)) {
                    metrics.handlerFailures.increment();
                }
                // Inside the in-flight slot, deliberately. Releasing the slot first let the pump
                // re-read and re-deliver the row before its failure had been recorded, so the message
                // burned two attempts for one failure and was dead-lettered where the policy said
                // retry. It cost exactly one message of forty, every run.
                onFailure(row, e);
            }
            if (handled) {
                // Still inside the in-flight slot, and before stateLock is taken: this may wait for a running charge, which
                // takes stateLock itself. A message whose dead-letter move is still owed is settled as handled, not moved
                // (the ledger's "completion wins"): it is acknowledged below
                var deadLettered = releaseChargeLedger.completedInHandler(row.seq());
                if (deadLettered.isPresent()) {
                    handled = false;
                    completedAfterItsDeadLetter(deadLettered.get());
                }
            } else if (interrupted) {
                // Not finished, so nothing to log; but a row the charge dead-lettered is gone and will not be read again,
                // so its seq is forgotten here, as the ordered lane does
                releaseChargeLedger.deadLetteredWhileInHandler(row.seq())
                                   .ifPresent(deadLettered -> forgetDeadLettered(deadLettered.seq()));
            }
        } finally {
            LeaseOverrun.check(metrics, settings, "unordered", shard, startedNanos);
            // In the finally, not in the branches: a slot that is never released is a shard that
            // never reads again, because the pump refuses to read while this one is at capacity.
            // Anything the handler can throw — including a critical Error, which the catch above
            // rethrows — has to leave the slot free.
            synchronized (stateLock) {
                inFlight.remove(row.seq());
                returnedFromHandler.remove(row.seq());
                if (handled) {
                    pendingAcks.add(row.seq());
                }
            }
            if (handled) {
                metrics.delivered.increment();
            }
            // The permit goes back with the slot, never before it: releasing early would let the
            // pump re-read and re-deliver a row whose failure had not yet been recorded.
            dispatch.release();
            // Budget may have freed up while the pump was parked.
            wakeup.signal();
        }
    }

    /**
     * Mark {@code seq} as a message whose handler has returned ({@link #returnedFromHandler}), before its handler thread asks
     * {@link ReleaseChargeLedger#completedInHandler} or {@link ReleaseChargeLedger#deadLetteredWhileInHandler}.
     */
    private void markReturnedFromHandler(long seq) {
        synchronized (stateLock) {
            returnedFromHandler.add(seq);
        }
    }

    /**
     * Failure path. Rare, so it is allowed to be expensive — one row update (and its index entry), or a dead-letter move.
     */
    private void onFailure(ShardOwnedStorage.Row row, Throwable cause) {
        // Asked before a connection is taken: it may wait for a running charge
        var deadLettered = releaseChargeLedger.deadLetteredWhileInHandler(row.seq());
        if (deadLettered.isPresent()) {
            // Dead-lettered while this handler ran, by the charge before a release (chargeInHandlerBeforeRelease): the row is
            // gone, so there is no attempt to count and nothing to retry.
            failedAfterItsDeadLetter(deadLettered.get(), cause);
            return;
        }
        try (var connection = storage.connection()) {
            onFailure(connection, row, cause);
        } catch (SQLException e) {
            log.error("Shard {}: failed to record failure for seq {}", shard, row.seq(), e);
        }
    }

    /**
     * Count this attempt. The first failure under this owner continues from the count stored on the row - what
     * earlier owners recorded, and what takeovers charged - instead of starting from 1, which gave every message
     * a fresh budget on every takeover.
     */
    private int countAttempt(Connection connection, long seq) throws SQLException {
        Integer counted;
        synchronized (stateLock) {
            counted = attemptsBySeq.get(seq);
        }
        var attempts = (counted != null ? counted : storage.storedAttempts(connection, ShardOwnedSchema.UNORDERED_TABLE, shard, seq)) + 1;
        synchronized (stateLock) {
            attemptsBySeq.put(seq, attempts);
        }
        return attempts;
    }

    /**
     * The attempts counted so far, without counting one: what this owner has counted, or else what the row holds.
     */
    private int attemptsSoFar(Connection connection, long seq) throws SQLException {
        Integer counted;
        synchronized (stateLock) {
            counted = attemptsBySeq.get(seq);
        }
        return counted != null ? counted : storage.storedAttempts(connection, ShardOwnedSchema.UNORDERED_TABLE, shard, seq);
    }

    /**
     * The handler of a message the charge before a release dead-lettered while it ran
     * ({@link ReleaseChargeLedger#deadLetteredWhileInHandler}) has returned without an exception: its row is gone, so there
     * is nothing to acknowledge, and it is not counted delivered. The message has been handled and is dead-lettered all the
     * same, so it is logged at WARN: resurrecting that dead letter delivers it again.
     */
    private void completedAfterItsDeadLetter(ReleaseChargeLedger.ChargedRow deadLettered) {
        forgetDeadLettered(deadLettered.seq());
        log.warn("Shard {} of queue {}: the handler of seq {} completed after the charge before a release of the shard had "
                 + "dead-lettered its message at {} attempts. The message is not acknowledged and stays dead-lettered: "
                 + "resurrecting that dead letter delivers it again", shard, storage.queueId(), deadLettered.seq(),
                 deadLettered.attemptsAfterCharge());
    }

    /**
     * The handler of a message the charge before a release dead-lettered while it ran
     * ({@link ReleaseChargeLedger#deadLetteredWhileInHandler}) has thrown: its row is gone, so there is no attempt to count
     * and nothing to retry. The dead letter carries {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}, not what the
     * handler threw - often why it hung - so that is logged at WARN with the throwable, and a failure (not a requested
     * redelivery) is told to the {@code QueueObserver} as the attempt the charge counted.
     */
    private void failedAfterItsDeadLetter(ReleaseChargeLedger.ChargedRow deadLettered, Throwable cause) {
        forgetDeadLettered(deadLettered.seq());
        log.warn("Shard {} of queue {}: the handler of seq {} threw after the charge before a release of the shard had "
                 + "dead-lettered its message at {} attempts; that dead letter does not carry this exception", shard,
                 storage.queueId(), deadLettered.seq(), deadLettered.attemptsAfterCharge(), cause);
        if (cause instanceof RedeliveryRequested) {
            return;
        }
        try {
            metrics.observer().deliveryFailed(null, deadLettered.attemptsAfterCharge(), cause);
        } catch (RuntimeException | Error e) {
            // On the handler thread, after the outcome is settled: nothing is left for a throwing observer to fail
            Exceptions.rethrowIfCriticalError(e);
            log.warn("Shard {} of queue {}: the QueueObserver told of the failure of seq {} threw", shard, storage.queueId(),
                     deadLettered.seq(), e);
        }
    }

    private void forgetDeadLettered(long seq) {
        synchronized (stateLock) {
            attemptsBySeq.remove(seq);
            seenAcked(seq);
        }
    }

    private void onFailure(Connection connection, ShardOwnedStorage.Row row, Throwable cause) throws SQLException {
        if (cause instanceof RedeliveryRequested request) {
            onRedeliveryRequested(connection, row, request);
            return;
        }
        var attempts = countAttempt(connection, row.seq());
        // Emitted here rather than at the call site, because this is the only place that knows which
        // attempt this was. The SPI's `attempt` argument used to be hardcoded to zero.
        metrics.observer().deliveryFailed(null, attempts, cause);
        if (redeliveryPolicy.isExhausted(attempts)) {
            var moved = storage.moveToDeadLetterIfOwned(connection, ShardOwnedSchema.UNORDERED_TABLE, "unordered", shard,
                                                        row.seq(), DeliveryErrorText.describe(cause),
                                                        instanceId, fence);
            synchronized (stateLock) {
                attemptsBySeq.remove(row.seq());
                seenAcked(row.seq());
            }
            if (!moved && leaseLost("dead-letter")) {
                return;
            }
            if (!moved) {
                // Deleted by an operator or acknowledged elsewhere while it was failing. Nothing was
                // parked, so reporting a dead letter would be a lie — and it is exactly the report
                // that hid a lost message for as long as it did.
                log.warn("Shard {}: seq {} exhausted its attempts but was no longer in the table, so it "
                         + "was not dead-lettered", shard, row.seq());
                return;
            }
            // Counted before the observer is told: the move has committed, and an observer that throws must not take the
            // count with it
            metrics.deadLettered.increment();
            metrics.observer().deadLettered(new MessageId(MessageId.Lane.UNORDERED, shard, row.seq()),
                                            attempts, cause);
            return;
        }
        var delay = redeliveryPolicy.delayAfter(attempts);
        if (!storage.scheduleRetryIfOwned(connection, ShardOwnedSchema.UNORDERED_TABLE, shard, row.seq(), attempts,
                                          delay.toMillis(), instanceId, fence)
                && leaseLost("retry")) {
            // The successor redelivers it; scheduling it here as well would deliver it twice
            synchronized (stateLock) {
                attemptsBySeq.remove(row.seq());
            }
            return;
        }
        scheduleInMemory(row, attempts, delay);
    }

    /**
     * A requested redelivery is not a failure, and not an attempt either: it is scheduled at the delay the handler
     * asked for and leaves the count alone, so it neither uses up the redelivery policy's budget nor brings the
     * message closer to a takeover's dead-letter - see RedeliveryRequested.
     */
    private void onRedeliveryRequested(Connection connection, ShardOwnedStorage.Row row, RedeliveryRequested request) throws SQLException {
        var delay = request.delay();
        if (!storage.scheduleRequestedRedeliveryIfOwned(connection, ShardOwnedSchema.UNORDERED_TABLE, shard, row.seq(),
                                                        delay.toMillis(), instanceId, fence)
                && leaseLost("requested redelivery")) {
            synchronized (stateLock) {
                attemptsBySeq.remove(row.seq());
            }
            return;
        }
        scheduleInMemory(row, attemptsSoFar(connection, row.seq()), delay);
    }

    private void scheduleInMemory(ShardOwnedStorage.Row row, int attempts, Duration delay) {
        synchronized (stateLock) {
            retrySchedule.add(new Retry(System.nanoTime() + delay.toNanos(), row));
        }
        metrics.retriesScheduled.increment();
        metrics.observer().retryScheduled(null, attempts, delay.toMillis());
    }

    /**
     * Whether a fenced failure write changed nothing because this owner no longer holds the lease, rather than
     * because the row was gone. The failure is then the successor's to record: it redelivers the message.
     */
    private boolean leaseLost(String write) throws SQLException {
        if (storage.stillOwns("unordered", shard, instanceId, fence)) {
            return false;
        }
        if (leaseRow.releaseInDoubt()) {
            log.info("Shard {}: {} not recorded under fence {} after a release of the row whose answer was not received - the next "
                     + "owner redelivers the message", shard, write, fence);
        } else {
            log.info("Shard {}: {} not recorded under fence {} - lease lost, the next owner redelivers the message",
                     shard, write, fence);
        }
        return true;
    }

    /**
     * Re-dispatch whatever has come due, straight from memory.
     */
    private int dispatchDueRetries(Connection connection, long now) {
        var delivered = 0;
        while (dispatch.tryAcquire()) {
            Retry retry;
            synchronized (stateLock) {
                if (retrySchedule.isEmpty() || retrySchedule.peek().dueNanos() > now) {
                    dispatch.release();
                    break;
                }
                retry = retrySchedule.poll();
            }
            metrics.retriesDispatched.increment();
            if (deliver(retry.row())) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * A dead-lettered message has already left the live table, so nothing may try to ack it again.
     */
    private void seenAcked(long seq) {
        pendingAcks.remove(seq);
        inFlight.remove(seq);
    }

    private record Retry(long dueNanos, ShardOwnedStorage.Row row) {
    }

    /**
     * Flush acknowledgements as a contiguous range plus a remainder.
     * <p>
     * Because the owner reads in sequence order, what it has handled is usually a contiguous prefix,
     * so the common case collapses to a single range delete over physically adjacent rows.
     */
    private void flushAcks(Connection connection, boolean force) throws SQLException {
        long       contiguousThrough;
        long       rangeAbove;
        List<Long> stragglers;
        // Everything below reads three collections the handler threads mutate. Iterating them
        // unguarded threw ConcurrentModificationException out of pumpOnce, which the pump did not
        // catch — so the pump thread died and took every shard it served with it. Exactly 250 of
        // 1 000 messages went missing, which is one shard of four.
        synchronized (stateLock) {
            if (pendingAcks.isEmpty()) {
                return;
            }
            // The range delete may not cross anything still outstanding, and "outstanding" includes
            // pending HOLES as well as in-flight messages.
            //
            // This is a genuine contradiction in the design as first written: §4.3 acknowledges a
            // contiguous prefix with `DELETE ... WHERE seq <= n`, while §4.4 has the cursor advance past
            // a hole rather than stall on it. Together those lose messages. A hole at seq N is a row
            // that has not committed yet; once it commits it sits BELOW the acked prefix, so the next
            // range delete removes it without it ever having been delivered. It reproduced as 896 of 900
            // messages delivered, every run, as soon as the test constructed the hazard deliberately
            // instead of hoping for it.
            //
            // The floor is therefore the lowest sequence value that is either in flight or a known hole.
            // Nothing at or above it may be deleted, however contiguous the prefix looks.
            //
            // A message waiting out a retry backoff is outstanding too, and it is neither in flight
            // nor a hole: the handler thread has let go of it and only the retry schedule remembers
            // it. Leaving it out made the range delete take it along with the messages after it — the
            // schedule still redelivered it from memory, but its row was gone, so a crash lost it and
            // the dead-letter move on its last attempt found nothing to move. Every unordered poison
            // message that had traffic acknowledged around it vanished that way.
            var inFlightFloor = inFlight.isEmpty() ? Long.MAX_VALUE : inFlight.firstKey();
            var holeFloor     = pendingHoles.isEmpty() ? Long.MAX_VALUE : pendingHoles.firstKey();
            var retryFloor    = Long.MAX_VALUE;
            for (var retry : retrySchedule) {
                retryFloor = Math.min(retryFloor, retry.row().seq());
            }
            var safeFloor     = Math.min(Math.min(inFlightFloor, holeFloor), retryFloor);

            // Only above the takeover cursor: anything at or below it came from the head sweep and is acknowledged
            // by exact sequence value, so the range delete cannot reach a late commit below it - see takeoverCursor
            var rangeCandidates = pendingAcks.tailSet(takeoverCursor, false);
            contiguousThrough = takeoverCursor;
            var expected = rangeCandidates.isEmpty() ? Long.MAX_VALUE : rangeCandidates.first();
            for (var seq : rangeCandidates) {
                // A message that has failed before is acknowledged by its exact seq, never by the
                // range. The range delete skips rows that are not visible yet, so that it cannot take
                // a delayed or backed-off row nobody here remembers; but a retry fires from the
                // in-memory schedule on this JVM's clock, which can run ahead of the database's
                // now() >= visible_at, and a retried message handled in that window would be left
                // behind to be delivered a second time.
                if (seq != expected || seq >= safeFloor || attemptsBySeq.containsKey(seq)) {
                    break;
                }
                contiguousThrough = seq;
                expected = seq + 1;
            }

            stragglers = new ArrayList<>();
            for (var seq : pendingAcks) {
                // NOT bounded by safeFloor. The floor exists because a range delete `seq <= n` would
                // sweep up rows that were never delivered — a hole that commits late, sitting below the
                // acked prefix. A targeted delete addresses exactly the sequence values this owner
                // handled, so it can remove nothing it did not deliver, and the floor has no business
                // restricting it.
                //
                // Applying the floor to both was over-generalising one rule, and it cost real time:
                // under asynchronous delivery the floor is pinned by the oldest of up to
                // in-flight messages, so everything finished behind a slow handler
                // waited for it. With inline delivery there was only ever one in flight, which is why it
                // never showed.
                if (seq <= takeoverCursor || seq > contiguousThrough) {
                    stragglers.add(seq);
                }
            }
            if (contiguousThrough == takeoverCursor && stragglers.isEmpty()) {
                return;
            }
            rangeAbove = takeoverCursor;
        }

        // The round trip happens outside the monitor, so handlers finishing are not blocked on it.
        var deleted = storage.acknowledge(connection, shard, rangeAbove, contiguousThrough, stragglers, instanceId, fence);
        metrics.ackFlushes.increment();
        if (deleted == 0 && !storage.stillOwns("unordered", shard, instanceId, fence)) {
            // Zero rows deleted is ambiguous — the fence clause rejected this owner, or the rows had
            // already gone. Only the first means the shard has moved, and treating the second as if
            // it had cost a lease that was still held: the owner stopped, the shard was re-acquired,
            // and everything in flight was redelivered.
            // Counted and logged only by the refusal that moves the row to RELEASED. One already there was released by
            // this instance (releaseLeaseRows: a stop, a shed, a dead pump's hand-back) or found taken by an earlier check
            // or write: a refusal after that is not a lease lost now.
            if (leaseRow.advanceTo(LeaseRow.RELEASED) == LeaseRow.RELEASED) {
                log.debug("Shard {}: acknowledgement refused under fence {} for a lease row already given up or found taken",
                          shard, fence);
                return;
            }
            // Nor by one refused after a release of the row failed with no error answer from the database (a lost
            // connection or a timed-out read, LeaseRowState#mayHaveGoneThrough; ShardOwnedQueue.releaseLeaseRows marked
            // it): that release may have gone through on the server after all, the row given up by this instance itself.
            if (leaseRow.releaseInDoubt()) {
                log.debug("Shard {}: acknowledgement refused under fence {} after a release of the row whose answer was not received; "
                                  + "taken as given up by that release, stopping", shard, fence);
                return;
            }
            metrics.fencedOutAcks.increment();
            log.warn("Shard {}: acknowledgement rejected under fence {} — lease lost, stopping", shard, fence);
            return;
        }

        var finalContiguous = contiguousThrough;
        var finalRangeAbove = rangeAbove;
        var finalStragglers = stragglers;
        synchronized (stateLock) {
            // Exactly what was deleted: the range above the takeover cursor and the stragglers. A bare
            // `seq <= finalContiguous` would also drop the attempt count of a late commit below the takeover
            // cursor that is still being retried
            pendingAcks.removeIf(seq -> (seq > finalRangeAbove && seq <= finalContiguous) || finalStragglers.contains(seq));
            // A retried message's attempt count is done with once it is acknowledged; nothing removed
            // it on success before, so the map grew by one entry per message that ever recovered.
            attemptsBySeq.keySet().removeIf(seq -> (seq > finalRangeAbove && seq <= finalContiguous) || finalStragglers.contains(seq));
        }
    }

    /**
     * Whether {@link #chargeInHandlerBeforeRelease} has anything to write: a message in a handler longer than
     * {@code stuckHandlerTimeout} it has not charged yet, nor found gone from the table (deleted while in its handler), or a charged one at the redelivery limit whose dead-letter move is
     * still to be made. Lets the queue skip the connection that call needs for an owner it would find nothing to do for.
     */
    boolean owesReleaseCharge() {
        return releaseChargeLedger.owes(inHandlerPastStuckHandlerTimeout());
    }

    @Override
    public boolean owesDeadLetterMove() {
        return releaseChargeLedger.owesDeadLetterMove();
    }

    @Override
    public boolean claimReleaseUnlessMoveOwed() {
        return releaseChargeLedger.claimReleaseUnlessMoveOwed();
    }

    @Override
    public void endReleaseClaim() {
        releaseChargeLedger.endReleaseClaim();
    }

    @Override
    public OwedMoveReleaseRefusals owedMoveReleaseRefusals() {
        return owedMoveReleaseRefusals;
    }

    /**
     * Charge one attempt to each message that has been in a handler longer than {@code stuckHandlerTimeout}
     * ({@link ShardOwnerSettings}), and dead-letter each one the charge brings to the redelivery limit, before the queue
     * releases this owner's shard without waiting for those handlers: by a fair-share shed, by the hand-back from a dead
     * pump, or by {@code stop()} once its drain has given up ({@code ShardOwnedQueue.chargeInHandlerBeforeRelease}).
     * <p>
     * Whoever takes a released shard next charges nothing ({@link ShardOwnedStorage.LeaseGrant#previousOwnerReleased()}),
     * and a handler that hangs never returns to record its attempt, so without this a message whose handler hung on every
     * delivery was delivered again after every release with its count unchanged, never reaching the dead-letter table, and
     * each delivery left one more handler hung, holding a {@link HandlerDispatch} permit. It is the charge an unclean
     * takeover makes for a delivery whose outcome was never recorded ({@link #onTakeover}), made by the owner that knows
     * which messages those are. Only those in a handler past {@code stuckHandlerTimeout}: a handler that has run for less is
     * taken to be slow, not hung, and its message is released uncharged, as before - the next owner delivers it again, which
     * this lane allows. A row the charge brings to the limit is dead-lettered here with
     * {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}, as the next owner runs no exhaustion pass for a released
     * shard; the handler may still be running it.
     * <p>
     * Fenced, as the release after it is: the row must still name this instance under this owner's fence, which releasing
     * does not bump. Each message is charged once per owner: recorded as soon as the charge has committed, so a release that
     * failed and is retried does not charge it again, and a message that has gone past the bound since is charged by the
     * retry; the dead-letter moves are made until a call completes them. Callers do not run it for one owner at the same
     * time (the queue's heartbeat, or a stop's budget thread once the heartbeat is cancelled); the ledger's monitor makes it
     * safe if they do.
     * <p>
     * The charge itself is {@link ReleaseChargeLedger}'s, which the ordered lane composes too
     * ({@code OrderedShardOwner.chargeKeysInHandlerBeforeRelease}); this owner supplies only its candidates - the messages
     * in a handler past the bound, taken once the ledger's charge is running and leaving out a message whose handler has
     * returned - and its table.
     *
     * @return what this owner has charged and dead-lettered so far, this call included; nothing when no message is in a
     *         handler past the bound, or when the row no longer names this owner
     * @throws SQLException when the charge, the lease lookup after a charge that left a row out, or a dead-letter move fails
     * @throws RuntimeException what the {@code QueueObserver} threw when told of a dead letter (or a non-critical
     *                          {@link Error}), thrown once every move is made, so {@link #owesReleaseCharge()} then answers
     *                          as after a call that returned
     */
    ReleaseCharge chargeInHandlerBeforeRelease(Connection connection) throws SQLException {
        return toReleaseCharge(releaseChargeLedger.charge(connection, this::inHandlerPastStuckHandlerTimeout));
    }

    /**
     * What {@link #chargeInHandlerBeforeRelease} has charged and dead-lettered so far, without writing anything.
     */
    ReleaseCharge releaseCharge() {
        return toReleaseCharge(releaseChargeLedger.answer());
    }

    private static ReleaseCharge toReleaseCharge(ReleaseChargeLedger.Answer answer) {
        return answer.charged() == 0 ? ReleaseCharge.NONE
                                     : new ReleaseCharge(answer.charged(), answer.deadLettered().stream().map(ReleaseChargeLedger.ChargedRow::seq).toList());
    }

    /**
     * The count after the charge of each message {@link #chargeInHandlerBeforeRelease} charged, as the count this owner has
     * for it, so a failure its handler reports later - the release having failed, and this owner serving on - counts on from
     * the charge: {@link #countAttempt} prefers this map to the row, and {@code scheduleRetryIfOwned} writes the count it is
     * given, which would undo the charge.
     */
    private void recordChargedAttempts(Map<Long, Integer> charged) {
        synchronized (stateLock) {
            attemptsBySeq.putAll(charged);
        }
    }

    /**
     * The messages in a handler since longer than {@code stuckHandlerTimeout} ago, in sequence order, each with no key
     * ({@link ReleaseChargeLedger.Candidate#UNORDERED}): the candidates of {@link #chargeInHandlerBeforeRelease}. A message
     * whose handler has returned ({@link #returnedFromHandler}) is not one: its handler records its outcome.
     */
    private Map<Long, ReleaseChargeLedger.Candidate> inHandlerPastStuckHandlerTimeout() {
        var now   = System.nanoTime();
        var bound = settings.stuckHandlerTimeout().toNanos();
        var stuck = new TreeMap<Long, ReleaseChargeLedger.Candidate>();
        synchronized (stateLock) {
            inFlight.forEach((seq, sinceNanos) -> {
                if (now - sinceNanos >= bound && !returnedFromHandler.contains(seq)) {
                    stuck.put(seq, ReleaseChargeLedger.Candidate.UNORDERED);
                }
            });
        }
        return stuck;
    }

    /**
     * What {@link #chargeInHandlerBeforeRelease} has done for this owner.
     *
     * @param charged          how many messages in a handler past {@code stuckHandlerTimeout} were charged an attempt
     * @param deadLetteredSeqs the sequence values of those the charge brought to the redelivery limit, and which were
     *                         dead-lettered
     */
    record ReleaseCharge(int charged, List<Long> deadLetteredSeqs) {
        static final ReleaseCharge NONE = new ReleaseCharge(0, List.of());
    }

    @Override
    public String lane() {
        return "unordered";
    }

    @Override
    public long fence() {
        return fence;
    }
}
