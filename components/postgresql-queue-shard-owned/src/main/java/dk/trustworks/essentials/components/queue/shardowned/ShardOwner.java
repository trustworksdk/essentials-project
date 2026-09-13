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
import org.slf4j.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
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

    private final ShardOwnedStorage      storage;
    private final int                 shard;
    private final long                fence;
    private final ShardOwnerSettings  settings;
    private final PayloadHandler      handler;
    private final ShardOwnerMetrics   metrics;
    private final RedeliveryPolicy    redeliveryPolicy;
    private final ShardWakeup         wakeup;
    private final String              instanceId;
    /**
     * Whether this owner's instance has confirmed its own liveness recently enough to dispatch —
     * supplied by {@link ShardOwnedQueue} after construction rather than through a constructor
     * already at its argument ceiling. Null for an owner built without one, which then always
     * dispatches.
     */
    private volatile BooleanSupplier  deliveryGate;
    /**
     * Handlers run here, not on the pump thread.
     * <p>
     * They used to run inline, which was safe while every shard had a thread of its own: a slow
     * handler stalled only its own shard. A pump serves several shards, so inline delivery would let
     * one slow handler stall shards it has nothing to do with. Dispatching to a shared virtual-thread
     * executor keeps shards independent without a platform thread per shard, and
     * {@link ShardOwnerSettings#keyConcurrency} bounds how many messages one shard may have in flight.
     */
    private final HandlerDispatch     dispatch;
    /**
     * Guards everything the pump thread and the handler threads both touch. Delivery used to be
     * inline and every field below was single-threaded; it is not any more.
     */
    private final Object              stateLock = new Object();
    /** Cleared when the lease is lost, so the owner stops dispatching rather than racing its successor. */
    private final AtomicBoolean       leaseHeld = new AtomicBoolean(true);
    /**
     * Messages handed straight from a local enqueue, bypassing the read path entirely.
     * <p>
     * The head sweep deliberately does NOT exclude locally handed-off rows. If a hand-off is ever
     * lost — the process dies between commit and dispatch, or the queue is drained on shutdown — the
     * sweep is what still delivers the message, filtered by the owner's own in-memory dedup. Making
     * the sweep exclude them would turn a lost hand-off into a permanently stuck message.
     */
    private final ConcurrentLinkedQueue<ShardOwnedStorage.Row> localHandoffs = new ConcurrentLinkedQueue<>();

    /**
     * The in-memory retry schedule — §4.6's timer wheel at the scale one shard needs.
     * <p>
     * A failed message is re-dispatched from here without ever being read back. The database write
     * that accompanies a failure exists only so a crash does not lose the backoff; the row sits in
     * the table with {@code visible_at} in the future, where the head sweep will find it if this
     * owner dies. Retries therefore cost the read path nothing at all.
     */
    private final PriorityQueue<Retry> retrySchedule = new PriorityQueue<>(Comparator.comparingLong(Retry::dueNanos));
    private final Map<Long, Integer>   attemptsBySeq = new HashMap<>();

    /** Highest sequence value read. Advances past holes rather than waiting on them. */
    private long cursor;
    /** Sequence values the cursor stepped over, mapped to when they were first missed. */
    private final TreeMap<Long, Long> pendingHoles = new TreeMap<>();
    /** Delivered but not yet acknowledged, so a sweep does not deliver them twice. */
    private final TreeSet<Long> inFlight = new TreeSet<>();
    /** Handled and awaiting the next ack flush. */
    private final TreeSet<Long> pendingAcks = new TreeSet<>();

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

    ShardOwner(ShardOwnedStorage storage,
                      int shard,
                      long fence,
                      ShardOwnerSettings settings,
                      PayloadHandler handler,
                      ShardOwnerMetrics metrics,
                      RedeliveryPolicy redeliveryPolicy,
                      ShardWakeup wakeup,
                      String instanceId,
                      HandlerDispatch dispatch) {
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
    }

    /** See {@link LeasedOwner#deliveryPermitted()}. Set once, by the queue that built this owner. */
    void setDeliveryGate(BooleanSupplier deliveryGate) {
        this.deliveryGate = deliveryGate;
    }

    @Override
    public boolean deliveryPermitted() {
        var gate = deliveryGate;
        return gate == null || gate.getAsBoolean();
    }

    @Override
    public void onLeaseLost() {
        if (leaseHeld.compareAndSet(true, false)) {
            log.warn("Shard {}: lease lost under fence {}, owner stopping", shard, fence);
            wakeup.signal();
        }
    }

    @Override
    public boolean leaseHeld() {
        return leaseHeld.get();
    }

    @Override
    public int shard() {
        return shard;
    }

    @Override
    public boolean needsAttention() {
        if (wakeup.consume()) {
            metrics.wakeupsHonoured.increment();
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
        metrics.takeoverAttemptBumps.add(storage.bumpAttemptsOnTakeover(connection, shard));
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
        var deadline = Math.max(0L, untilSweep);
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
    public int pumpOnce(Connection connection) throws SQLException {
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
        var rows = storage.readFromCursor(connection, shard, readFrom, settings.readBatchSize(), fence);
        metrics.cursorReads.increment();

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
        if (delivered > 0) {
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
            if (inFlight.contains(row.seq()) || pendingAcks.contains(row.seq())) {
                // Already ours. Hand the permit back — it was taken speculatively by the caller.
                dispatch.release();
                return false;
            }
            inFlight.add(row.seq());
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
        var handled = false;
        try {
            try {
                handler.handle(row.payload(), row.payloadType());
                // An interrupted handler has NOT necessarily finished its work. A handler that catches
                // InterruptedException and returns normally — which is what well-behaved code does on
                // shutdown — would otherwise be indistinguishable from success, and the message would
                // be acknowledged without having been processed. That is silent message loss, and it
                // showed up the first time the crash-recovery test ran: the outgoing owner acked
                // everything it had dispatched, leaving the incoming owner nothing to redeliver.
                if (Thread.currentThread().isInterrupted()) {
                    metrics.abandonedOnInterrupt.increment();
                } else {
                    handled = true;
                }
            } catch (RuntimeException e) {
                metrics.handlerFailures.increment();
                // Inside the in-flight slot, deliberately. Releasing the slot first let the pump
                // re-read and re-deliver the row before its failure had been recorded, so the message
                // burned two attempts for one failure and was dead-lettered where the policy said
                // retry. It cost exactly one message of forty, every run.
                onFailure(row, e);
            }
        } finally {
            // In the finally, not in the branches: a slot that is never released is a shard that
            // never reads again, because the pump refuses to read while this one is at capacity.
            // Anything the handler can throw — including an Error a catch of RuntimeException would
            // miss — has to leave the slot free.
            synchronized (stateLock) {
                inFlight.remove(row.seq());
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
     * Failure path. Rare, so it is allowed to be expensive — one HOT update, or a dead-letter move.
     */
    private void onFailure(ShardOwnedStorage.Row row, RuntimeException cause) {
        int attempts;
        synchronized (stateLock) {
            attempts = attemptsBySeq.merge(row.seq(), 1, Integer::sum);
        }
        // Emitted here rather than at the call site, because this is the only place that knows which
        // attempt this was. The SPI's `attempt` argument used to be hardcoded to zero.
        metrics.observer().deliveryFailed(null, attempts, cause);
        try (var connection = storage.connection()) {
            if (redeliveryPolicy.isExhausted(attempts)) {
                storage.moveToDeadLetter(connection, ShardOwnedSchema.UNORDERED_TABLE, "unordered", shard, row.seq(),
                                         cause.getClass().getName() + ": " + cause.getMessage());
                synchronized (stateLock) {
                    attemptsBySeq.remove(row.seq());
                    seenAcked(row.seq());
                }
                metrics.observer().deadLettered(new MessageId(MessageId.Lane.UNORDERED, shard, row.seq()),
                                                attempts, cause);
                metrics.deadLettered.increment();
                return;
            }
            var delay = redeliveryPolicy.delayAfter(attempts);
            storage.scheduleRetry(connection, ShardOwnedSchema.UNORDERED_TABLE, shard, row.seq(), attempts, delay.toMillis());
            synchronized (stateLock) {
                retrySchedule.add(new Retry(System.nanoTime() + delay.toNanos(), row));
            }
            metrics.retriesScheduled.increment();
            metrics.observer().retryScheduled(null, attempts, delay.toMillis());
        } catch (SQLException e) {
            log.error("Shard {}: failed to record failure for seq {}", shard, row.seq(), e);
        }
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
        long contiguousThrough;
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
            var inFlightFloor = inFlight.isEmpty() ? Long.MAX_VALUE : inFlight.first();
            var holeFloor = pendingHoles.isEmpty() ? Long.MAX_VALUE : pendingHoles.firstKey();
            var safeFloor = Math.min(inFlightFloor, holeFloor);

            contiguousThrough = 0L;
            var expected = pendingAcks.first();
            for (var seq : pendingAcks) {
                if (seq != expected || seq >= safeFloor) {
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
                if (seq > contiguousThrough) {
                    stragglers.add(seq);
                }
            }
            if (contiguousThrough == 0 && stragglers.isEmpty()) {
                return;
            }
        }

        // The round trip happens outside the monitor, so handlers finishing are not blocked on it.
        var deleted = storage.acknowledge(connection, shard, contiguousThrough, stragglers, instanceId, fence);
        metrics.ackFlushes.increment();
        if (deleted == 0 && !storage.stillOwns("unordered", shard, instanceId, fence)) {
            // Zero rows deleted is ambiguous — the fence clause rejected this owner, or the rows had
            // already gone. Only the first means the shard has moved, and treating the second as if
            // it had cost a lease that was still held: the owner stopped, the shard was re-acquired,
            // and everything in flight was redelivered.
            metrics.fencedOutAcks.increment();
            leaseHeld.set(false);
            log.warn("Shard {}: acknowledgement rejected under fence {} — lease lost, stopping", shard, fence);
            return;
        }

        var finalContiguous = contiguousThrough;
        var finalStragglers = stragglers;
        synchronized (stateLock) {
            pendingAcks.removeIf(seq -> seq <= finalContiguous || finalStragglers.contains(seq));
        }
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
