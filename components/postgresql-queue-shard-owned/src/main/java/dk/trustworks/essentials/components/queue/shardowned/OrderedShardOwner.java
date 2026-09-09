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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Owns one shard of the ordered lane, enforcing per-key FIFO in memory.
 * <p>
 * This is the design's largest claim, and it is worth stating exactly what disappears. A
 * conventional ordered queue asks the database, on every poll, for the head of each key group that
 * is not already being delivered — a correlated anti-join, and the most expensive query shape in
 * such a system. Here the query is a plain forward scan on {@code seq}, and ordering is a
 * consequence of two in-memory facts: a key's messages can only be in one shard, and one owner has
 * all of that shard.
 * <p>
 * Concurrency is per key, not per shard. Many keys progress at once; a key with a slow or stuck
 * handler blocks only itself. That is the correct semantic — head-of-line blocking should be scoped
 * to the thing that actually demands ordering.
 * <p>
 * <b>On strictness.</b> A key advances through the {@code key_order} values that are present, one at
 * a time. It does not wait for a missing {@code key_order} to arrive, because those values are
 * producer-assigned and a gap may never be filled — waiting would stall the key forever on a
 * producer's bookkeeping error. The consequence is honest rather than hidden: a message that commits
 * late, after a higher {@code key_order} for the same key has already been delivered, is an ordering
 * violation, and {@link ShardOwnerMetrics#orderViolations} counts it rather than the design claiming
 * it cannot happen. Whether that count is zero under realistic producers is a measurement, not an
 * assumption.
 */
final class OrderedShardOwner implements LeasedOwner {
    private static final Logger log = LoggerFactory.getLogger(OrderedShardOwner.class);

    private final ShardOwnedStorage             storage;
    private final int                        shard;
    private final long                       fence;
    private final ShardOwnerSettings         settings;
    private final OrderedPayloadHandler      handler;
    private final ShardOwnerMetrics          metrics;
    private final RedeliveryPolicy           redeliveryPolicy;

    /**
     * Per-key retry schedule. A failing message must keep its key blocked until it is retried or
     * dead-lettered — releasing the key early would let a later message overtake the one that
     * failed, which is precisely the ordering guarantee being sold.
     */
    private final PriorityQueue<OrderedRetry> retrySchedule = new PriorityQueue<>(Comparator.comparingLong(OrderedRetry::dueNanos));
    private final Map<Long, Integer>          attemptsBySeq = new HashMap<>();
    private final Set<String>                 keysAwaitingRetry = new HashSet<>();

    /**
     * Where the next cursor read starts. Unlike the unordered lane's cursor this is a <em>safe
     * watermark</em>, not a high-water mark: it only passes a sequence value once no transaction that
     * could still commit that value is running. See {@link #advanceWatermark}.
     */
    private long safeCursor;
    /** Highest sequence value observed. The watermark trails this; the gap is the re-read window. */
    private long maxSeen;
    /**
     * Observations waiting for the horizon to retire them, oldest first. Each says "at this moment the
     * highest value seen was {@code maxSeen}, and these write transactions were running". Once none of
     * them is running, everything at or below that {@code maxSeen} has resolved.
     */
    private final ArrayDeque<WatermarkCandidate> watermarkCandidates = new ArrayDeque<>();

    private record WatermarkCandidate(long maxSeen, Set<Long> runningXids, long observedAtNanos) {
    }

    /** Everything known but not yet delivered, grouped by key and ordered within it. */
    private final Map<String, TreeMap<Long, ShardOwnedStorage.OrderedRow>> readyByKey = new HashMap<>();
    /** Keys with a message currently in a handler. At most one per key, which is what enforces FIFO. */
    private final Set<String> keysInFlight = new HashSet<>();
    /** Highest key_order handed to a handler per key, used to detect ordering violations. */
    private final Map<String, Long> highestDeliveredOrder = new HashMap<>();
    /** Sequence values handled and awaiting the next ack flush. */
    private final Set<Long> pendingAcks = new HashSet<>();
    /** Sequence values already seen, so a sweep or a chase cannot re-queue them. */
    private final Set<Long> seen = new HashSet<>();

    /**
     * Handlers run here, not on the pump thread.
     * <p>
     * This is what §4.7's "cross-key parallelism inside a shard" actually costs. Dispatching inline
     * was simpler and kept every field single-threaded — but it also meant one key with a slow
     * handler stalled the whole shard, making the parallelism claim false while the code looked
     * correct. Handing work to an executor buys the claim back and takes the price: {@link #stateLock}
     * now guards the state the owner and the workers share.
     * <p>
     * <b>Shared, and virtual.</b> This used to be a fixed platform-thread pool of
     * {@code keyConcurrency} <em>per owner</em>, so handler threads multiplied by shard count: 64 for
     * one consumer at eight shards, and about 1 350 across twenty-five queues. Handlers are user code
     * that mostly waits on something, which is exactly what virtual threads are for, and the executor
     * is now one per queue rather than one per shard. Concurrency is still bounded — by
     * {@code activeKeys} against {@code keyConcurrency}, per shard, as before — but the bound no
     * longer costs a platform thread whether or not it is being used.
     */
    private final HandlerDispatch  dispatch;
    private final Object           stateLock = new Object();
    private final AtomicInteger    activeKeys = new AtomicInteger();
    private final String           instanceId;
    /**
     * Tier 1, which this lane went without.
     * <p>
     * It used to sleep 200 microseconds and re-query.
     * That park paces nothing: the loop runs as fast as the database can answer, so twenty idle
     * ordered owners measured 52 000 queries a second between them. The mechanism to avoid that was
     * already built, tested and measured; it was simply never wired to this lane, and every headline
     * latency figure in the design came from the unordered one.
     */
    private final ShardWakeup      wakeup;
    /** Cleared when the lease is lost, so this owner stops rather than racing its successor. */
    private final AtomicBoolean    leaseHeld = new AtomicBoolean(true);
    /** Set when this owner has been asked to give the shard up. See {@link #beginShedding()}. */
    private final AtomicBoolean    shedding = new AtomicBoolean();
    /** Set once the shard has quiesced and its acknowledgements are flushed — safe to release. */
    private final AtomicBoolean    shedComplete = new AtomicBoolean();
    private volatile long          shedDeadlineNanos;

    private long lastHorizonProbeNanos;
    private long lastSweepNanos;
    /**
     * When this shard's earliest delayed row becomes visible, in {@link System#nanoTime()} terms, or
     * {@link Long#MAX_VALUE} when nothing is waiting. See {@link ShardOwner} — the ordered lane needs
     * it for the same reason and would otherwise deliver a delayed message whenever the backed-off
     * sweep next happened to run.
     */
    private long nextVisibleAtNanos = Long.MAX_VALUE;
    /**
     * Current sweep interval, doubling while the shard stays empty and reset the moment anything
     * arrives. The sweep is the backstop for a lost notification, not the delivery path, so a quiet
     * shard sweeping every thirty seconds instead of twice a second costs recovery time for a rare
     * failure rather than latency for a normal message — and it is the difference between 4 queries a
     * second per idle shard and 0.07.
     */
    private long sweepIntervalNanos;
    private long lastAckFlushNanos;

    OrderedShardOwner(ShardOwnedStorage storage,
                             int shard,
                             long fence,
                             ShardOwnerSettings settings,
                             OrderedPayloadHandler handler,
                             ShardOwnerMetrics metrics,
                             RedeliveryPolicy redeliveryPolicy,
                             String instanceId,
                             ShardWakeup wakeup,
                             HandlerDispatch dispatch) {
        this.storage = requireNonNull(storage, "No storage provided");
        this.shard = shard;
        this.fence = fence;
        this.settings = requireNonNull(settings, "No settings provided");
        this.sweepIntervalNanos = settings.sweepIntervalNanos();
        this.handler = requireNonNull(handler, "No handler provided");
        this.metrics = requireNonNull(metrics, "No metrics provided");
        this.redeliveryPolicy = requireNonNull(redeliveryPolicy, "No redeliveryPolicy provided");
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.wakeup = requireNonNull(wakeup, "No wakeup provided");
        this.dispatch = requireNonNull(dispatch, "No dispatch provided");
    }

    @Override
    public boolean needsAttention() {
        if (wakeup.consume()) {
            metrics.wakeupsHonoured.increment();
            return true;
        }
        var now = System.nanoTime();
        if (now - lastSweepNanos >= sweepIntervalNanos) {
            return true;
        }
        if (nextVisibleAtNanos != Long.MAX_VALUE && now >= nextVisibleAtNanos) {
            return true;
        }
        if (shedding.get()) {
            // A shed only progresses when this owner is pumped, so it must not be skipped for being
            // quiet — a shard nobody is enqueueing to is exactly the one that drains fastest.
            return true;
        }
        synchronized (stateLock) {
            if (!retrySchedule.isEmpty() && retrySchedule.peek().dueNanos() <= now) {
                return true;
            }
            if (!readyByKey.isEmpty() && activeKeys.get() < settings.keyConcurrency()) {
                return true;
            }
            if (!watermarkCandidates.isEmpty() && now - lastHorizonProbeNanos >= settings.chaseDelayNanos()) {
                // A value the watermark has not passed yet may have committed since. Paced by
                // chaseDelay, as the hole chase this replaced was, so re-checking cannot spin.
                return true;
            }
            return !pendingAcks.isEmpty() && now - lastAckFlushNanos >= settings.ackFlushIntervalNanos();
        }
    }

    @Override
    public void onTakeover(Connection connection) throws SQLException {
        metrics.takeoverAttemptBumps.add(storage.bumpOrderedAttemptsOnTakeover(connection, shard));
    }

    @Override
    public void flushOnStop(Connection connection) throws SQLException {
        flushAcks(connection);
    }

    @Override
    public long parkDeadlineMillis() {
        var now = System.nanoTime();
        var untilSweep = (sweepIntervalNanos - (now - lastSweepNanos)) / 1_000_000L;
        if (nextVisibleAtNanos != Long.MAX_VALUE) {
            untilSweep = Math.min(untilSweep, Math.max(0L, (nextVisibleAtNanos - now) / 1_000_000L));
        }
        var deadline = Math.max(0L, untilSweep);
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


    @Override
    public int pumpOnce(Connection connection) throws SQLException {
        if (shedComplete.get()) {
            return 0;
        }
        // Read from the WATERMARK, not from the highest value seen, so a value whose transaction had
        // not committed when an earlier pass went by is read again rather than chased. The window
        // between the two is bounded by the horizon, not by the backlog, and `seen` deduplicates it.
        var rows = storage.readOrderedFromCursor(connection, shard, safeCursor, settings.readBatchSize());
        metrics.cursorReads.increment();
        for (var row : rows) {
            maxSeen = Math.max(maxSeen, row.seq());
            accept(row);
        }
        // AFTER the read, never before: a transaction that allocates a value after this probe is not
        // in the set, and treating it as retired would step over it. See ShardOwnedStorage.
        advanceWatermark(connection);

        var now = System.nanoTime();
        var sweptThisPass = false;
        if (now - lastSweepNanos >= sweepIntervalNanos) {
            sweep(connection);
            lastSweepNanos = now;
            sweptThisPass = true;
        }

        dispatchDueRetries(now);
        var delivered = dispatchReadyKeys();
        if (shedding.get()) {
            // The drain used to be driven by the owner's own loop; with the loop gone it belongs
            // here, which is the only place that still runs on every iteration.
            advanceShed(connection);
        }

        if (!pendingAcks.isEmpty()
            && (pendingAcks.size() >= settings.ackBatchSize() || now - lastAckFlushNanos >= settings.ackFlushIntervalNanos())) {
            flushAcks(connection);
            lastAckFlushNanos = now;
        }
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

    private void accept(ShardOwnedStorage.OrderedRow row) {
        synchronized (stateLock) {
            if (!seen.add(row.seq())) {
                return;
            }
            readyByKey.computeIfAbsent(row.key(), key -> new TreeMap<>()).put(row.keyOrder(), row);
        }
    }

    /**
     * Hand each idle key its lowest known {@code key_order}. Keys are independent, so this is where
     * cross-key parallelism comes from — and where per-key serialisation is enforced, by refusing to
     * dispatch a key that already has something in flight.
     */
    private int dispatchReadyKeys() {
        if (shedding.get()) {
            // The whole point of the drain: no new key may start here once the shard is on its way
            // out, or the set this owner is waiting to empty never empties.
            return 0;
        }
        var submitted = 0;
        synchronized (stateLock) {
            var exhaustedKeys = new ArrayList<String>();
            for (var entry : readyByKey.entrySet()) {
                var key = entry.getKey();
                var byOrder = entry.getValue();
                if (byOrder.isEmpty()) {
                    exhaustedKeys.add(key);
                    continue;
                }
                // At most one message per key in flight. This one refusal is the entire FIFO
                // guarantee — no query, no lock, no exclusion list.
                // In flight, or waiting out a backoff: either way the key is not allowed to move
                // on. A key whose message failed must retry THAT message before any later one.
                if (keysInFlight.contains(key) || keysAwaitingRetry.contains(key)) {
                    metrics.keyHeadOfLineBlocks.increment();
                    continue;
                }
                if (activeKeys.get() >= settings.keyConcurrency()) {
                    break;
                }
                // Per-shard cap first, then the process-wide budget the unordered lane also draws on.
                if (!dispatch.tryAcquire()) {
                    break;
                }
                var next = byOrder.pollFirstEntry();
                var previous = highestDeliveredOrder.get(key);
                if (previous != null && next.getKey() < previous) {
                    metrics.orderViolations.increment();
                }
                highestDeliveredOrder.merge(key, next.getKey(), Math::max);

                keysInFlight.add(key);
                activeKeys.incrementAndGet();
                metrics.maxConcurrentKeys.accumulateAndGet(activeKeys.get(), Math::max);
                submitted++;
                dispatch.execute(() -> runHandler(key, next.getKey(), next.getValue()));
            }
            exhaustedKeys.forEach(readyByKey::remove);
        }
        return submitted;
    }

    private void runHandler(String key, long keyOrder, ShardOwnedStorage.OrderedRow row) {
        try {
            handler.handle(key, row.payload(), row.payloadType());
            synchronized (stateLock) {
                if (Thread.currentThread().isInterrupted()) {
                    metrics.abandonedOnInterrupt.increment();
                    requeue(key, keyOrder, row);
                } else {
                    pendingAcks.add(row.seq());
                    metrics.delivered.increment();
                }
            }
        } catch (RuntimeException e) {
            metrics.handlerFailures.increment();
            // Deliberately NOT under stateLock. onFailure performs a database round trip, and holding
            // a monitor across one serialises the whole shard's failure path behind a network call —
            // and, on a virtual thread before JDK 24, pins the carrier for its duration.
            onFailure(key, keyOrder, row, e);
        } finally {
            synchronized (stateLock) {
                keysInFlight.remove(key);
            }
            activeKeys.decrementAndGet();
            dispatch.release();
            // Capacity may have freed up, and the pump may be parked on its backstop.
            wakeup.signal();
        }
    }

    /**
     * Failure path for a key. Either schedule a retry that keeps the key blocked, or park the
     * message and let the key move on — a dead letter must not stall its key forever.
     */
    private void onFailure(String key, long keyOrder, ShardOwnedStorage.OrderedRow row, RuntimeException cause) {
        int attempts;
        synchronized (stateLock) {
            attempts = attemptsBySeq.merge(row.seq(), 1, Integer::sum);
        }
        metrics.observer().deliveryFailed(key, attempts, cause);
        try (var connection = storage.connection()) {
            if (redeliveryPolicy.isExhausted(attempts)) {
                storage.moveToDeadLetter(connection, ShardOwnedSchema.ORDERED_TABLE, "ordered", shard, row.seq(),
                                         cause.getClass().getName() + ": " + cause.getMessage());
                synchronized (stateLock) {
                    attemptsBySeq.remove(row.seq());
                    seen.remove(row.seq());
                }
                metrics.observer().deadLettered(new MessageId(MessageId.Lane.ORDERED, shard, row.seq()),
                                                attempts, cause);
                metrics.deadLettered.increment();
                return;
            }
            var delay = redeliveryPolicy.delayAfter(attempts);
            storage.scheduleRetry(connection, ShardOwnedSchema.ORDERED_TABLE, shard, row.seq(), attempts, delay.toMillis());
            synchronized (stateLock) {
                keysAwaitingRetry.add(key);
                retrySchedule.add(new OrderedRetry(System.nanoTime() + delay.toNanos(), key, keyOrder, row));
            }
            metrics.retriesScheduled.increment();
            metrics.observer().retryScheduled(key, attempts, delay.toMillis());
        } catch (SQLException e) {
            log.error("Ordered shard {}: failed to record failure for seq {}", shard, row.seq(), e);
            synchronized (stateLock) {
                requeue(key, keyOrder, row);
            }
        }
    }

    private void dispatchDueRetries(long now) {
        synchronized (stateLock) {
            while (!retrySchedule.isEmpty() && retrySchedule.peek().dueNanos() <= now) {
                var retry = retrySchedule.poll();
                metrics.retriesDispatched.increment();
                keysAwaitingRetry.remove(retry.key());
                requeue(retry.key(), retry.keyOrder(), retry.row());
            }
        }
    }

    private record OrderedRetry(long dueNanos, String key, long keyOrder, ShardOwnedStorage.OrderedRow row) {
    }

    /**
     * Put a message back at its key's head so the key retries it before anything later — otherwise a
     * failure would silently reorder the key.
     */
    private void requeue(String key, long keyOrder, ShardOwnedStorage.OrderedRow row) {
        seen.remove(row.seq());
        readyByKey.computeIfAbsent(key, ignored -> new TreeMap<>()).put(keyOrder, row);
    }

    /**
     * Move the watermark up to the newest value the transaction horizon has retired.
     * <p>
     * <b>The argument.</b> Sequence values are handed out in increasing order over time — one sequence
     * object per {@code (queue, shard)}, {@code CACHE 1} — and a transaction is assigned its xid no
     * later than the value it allocates. So every value at or below {@code maxSeen} was allocated by a
     * transaction that already held an xid when this owner observed it, and any such transaction still
     * running is in the set recorded alongside. Once none of that set is running, every value at or
     * below that {@code maxSeen} has resolved: committed and visible, or aborted and never coming.
     * <p>
     * This is what replaces hole detection on this lane, and it is exact where hole detection was a
     * guess. A gap is not chased, not timed, and not written off after {@code holeExpiry} — it is
     * simply read again on the next pass, and the cursor does not step over it until the database says
     * nothing can still fill it.
     * <p>
     * <b>The cap.</b> The horizon is database-global, so a long-running <em>writing</em> transaction
     * anywhere holds it back. Read-only transactions do not, however long they run — only a writer is
     * assigned an xid. Rather than stall the lane behind an unrelated batch writer, a candidate older
     * than {@code watermarkCap} is taken anyway and counted. That is the same exposure an abandoned
     * hole carried, but on its own setting and a far more generous one: nothing about the watermark
     * pushes the bound down, where {@code holeExpiry} was held low by the cost of the map and the
     * chase query per unresolved value. Reusing {@code holeExpiry} here would reproduce the mechanism
     * this replaces rather than replacing it.
     */
    private void advanceWatermark(Connection connection) throws SQLException {
        if (watermarkCandidates.isEmpty() && maxSeen <= safeCursor) {
            // Fully caught up: nothing to retire and nothing worth recording. This is the idle poll,
            // which is the overwhelming majority of them, and it must not pay for the horizon probe.
            return;
        }
        var now = System.nanoTime();
        var running = storage.runningWriteTransactionIds(connection);
        lastHorizonProbeNanos = now;
        metrics.horizonProbes.increment();

        var advanceTo = -1L;
        var capped = false;
        while (!watermarkCandidates.isEmpty()) {
            var candidate = watermarkCandidates.peekFirst();
            var retired = Collections.disjoint(candidate.runningXids(), running);
            var expired = now - candidate.observedAtNanos() > settings.watermarkCapNanos();
            if (!retired && !expired) {
                break;
            }
            capped |= !retired;
            advanceTo = Math.max(advanceTo, candidate.maxSeen());
            watermarkCandidates.removeFirst();
        }
        if (advanceTo > safeCursor) {
            safeCursor = advanceTo;
            metrics.watermarkAdvances.increment();
            if (capped) {
                metrics.watermarkCapped.increment();
                log.debug("Ordered shard {}: watermark advanced to {} on the wall-clock cap — a write "
                          + "transaction outlived holeExpiry", shard, safeCursor);
            }
        }
        if (maxSeen > safeCursor) {
            // Recorded even when nothing moved this pass: this is the observation a LATER poll
            // retires. Only recorded while the watermark is actually behind, or the deque would never
            // empty and the owner would probe the horizon forever on a queue with nothing left to do.
            watermarkCandidates.addLast(new WatermarkCandidate(maxSeen, running, now));
            metrics.maxWatermarkLagSeq.accumulateAndGet((int) Math.min(Integer.MAX_VALUE, maxSeen - safeCursor),
                                                         Math::max);
        }
    }

    private void sweep(Connection connection) throws SQLException {
        var rows = storage.sweepOrderedFromHead(connection, shard, settings.readBatchSize());
        metrics.headSweeps.increment();
        var untilNext = storage.millisUntilNextVisible(connection, ShardOwnedSchema.ORDERED_TABLE, shard);
        nextVisibleAtNanos = untilNext.isPresent()
                             ? System.nanoTime() + untilNext.getAsLong() * 1_000_000L
                             : Long.MAX_VALUE;
        for (var row : rows) {
            var known = false;
            synchronized (stateLock) {
                known = seen.contains(row.seq()) || pendingAcks.contains(row.seq());
            }
            if (!known) {
                metrics.sweepRecoveries.increment();
                accept(row);
            }
        }
    }

    private void flushAcks(Connection connection) throws SQLException {
        List<Long> batch;
        synchronized (stateLock) {
            if (pendingAcks.isEmpty()) {
                return;
            }
            batch = List.copyOf(pendingAcks);
        }
        var deleted = storage.acknowledgeOrdered(connection, shard, batch, instanceId, fence);
        metrics.ackFlushes.increment();
        if (deleted == 0 && !storage.stillOwns("ordered", shard, instanceId, fence)) {
            // Zero rows deleted is ambiguous: refused by the fence, or already gone. Only the first
            // means the shard has moved. Leave the work for whoever holds it — acknowledging after a
            // real fencing rejection would delete messages the new owner is about to deliver, and for
            // an ordered key that is loss AND reordering at once.
            metrics.fencedOutAcks.increment();
            leaseHeld.set(false);
            log.warn("Ordered shard {}: acknowledgement rejected under fence {} — lease lost, stopping", shard, fence);
            return;
        }
        synchronized (stateLock) {
            pendingAcks.removeAll(batch);
            // Acked rows are gone, so remembering their sequence values forever would be a leak. The
            // head sweep can no longer surface them, which is what makes forgetting them safe.
            batch.forEach(seen::remove);
        }
    }

    /**
     * Ask this owner to give the shard up. Called by the rebalancer when this instance holds more
     * ordered shards than its fair share.
     * <p>
     * <b>Why this cannot be a release.</b> The unordered lane sheds by simply dropping the lease: a
     * message still in a handler here gets redelivered by the new owner, and at-least-once permits
     * that. The ordered lane cannot, because the new owner would be starting a key this owner still
     * has in flight — two messages of one key running at once, which is reordering, and reordering is
     * the one thing an ordered queue sells. So the shed quiesces first: no new key is dispatched, the
     * ones in flight are allowed to finish, their acknowledgements are flushed under a fence this
     * owner still holds, and only then is the shard let go.
     */
    public void beginShedding() {
        if (shedding.compareAndSet(false, true)) {
            shedDeadlineNanos = System.nanoTime() + settings.shedGraceNanos();
            metrics.shedsStarted.increment();
            log.info("Ordered shard {}: shedding — draining in-flight keys before releasing", shard);
        }
    }

    /**
     * Drive one step of a shed. Returns true when the owner should stop, which happens either because
     * the shard quiesced (and is now ready to be released) or because the grace ran out.
     */
    private boolean advanceShed(Connection connection) throws SQLException {
        int inFlight;
        synchronized (stateLock) {
            inFlight = keysInFlight.size();
        }
        if (inFlight == 0) {
            // Flush while the fence is still valid. Acknowledging after the release would be refused,
            // and every handled-but-unacked message would be redelivered by the new owner for nothing.
            flushAcks(connection);
            shedComplete.set(true);
            metrics.shedsCompleted.increment();
            log.info("Ordered shard {}: quiesced, ready for another instance", shard);
            return true;
        }
        if (System.nanoTime() >= shedDeadlineNanos) {
            // A handler outlasted the grace. Keep the shard rather than release it mid-key: staying
            // unbalanced is a performance cost, releasing here would be an ordering bug.
            shedding.set(false);
            metrics.shedsAbandoned.increment();
            log.warn("Ordered shard {}: shed abandoned with {} key(s) still in a handler — keeping the shard",
                     shard, inFlight);
            return false;
        }
        return false;
    }

    /** True once the shard has quiesced and its acknowledgements are flushed. */
    public boolean shedComplete() {
        return shedComplete.get();
    }

    public boolean shedding() {
        return shedding.get();
    }

    @Override
    public int shard() {
        return shard;
    }

    @Override
    public String lane() {
        return "ordered";
    }

    @Override
    public long fence() {
        return fence;
    }

    @Override
    public boolean leaseHeld() {
        return leaseHeld.get();
    }

    @Override
    public void onLeaseLost() {
        if (leaseHeld.compareAndSet(true, false)) {
            log.warn("Ordered shard {}: lease lost under fence {}, owner stopping", shard, fence);
        }
    }
}
