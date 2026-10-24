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
import org.slf4j.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
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
final class OrderedShardOwner implements BatchReadableOwner {
    private static final Logger log = LoggerFactory.getLogger(OrderedShardOwner.class);

    /**
     * {@link #advanceWatermark}'s {@code readReachedSeq} for a pass whose cursor read returned fewer rows than its
     * limit: the read reached the end of what was visible, so it does not cap the advance.
     */
    private static final long READ_NOT_CAPPED = Long.MAX_VALUE;

    private final ShardOwnedStorage     storage;
    private final int                   shard;
    private final long                  fence;
    private final ShardOwnerSettings    settings;
    private final OrderedPayloadHandler handler;
    private final ShardOwnerMetrics     metrics;
    private final RedeliveryPolicy      redeliveryPolicy;

    /**
     * Per-key retry schedule. A failing message must keep its key blocked until it is retried or
     * dead-lettered — releasing the key early would let a later message overtake the one that
     * failed, which is precisely the ordering guarantee being sold.
     * <p>
     * <b>Rebuilt on takeover, not only remembered.</b> The schedule lives in this owner's memory, so on
     * its own it would end with the owner: after a rebalance, a graceful stop or a crash the successor
     * read only visible rows and delivered the key's next message while the failed one was still in its
     * backoff (N-16). {@link #onTakeover} reloads every row a retry has pushed into the future
     * ({@link ShardOwnedStorage#orderedRetriesInBackoff}) into this schedule, so the block outlives the
     * owner the way {@link #deadLetterBlocks} does.
     */
    private final PriorityQueue<OrderedRetry> retrySchedule     = new PriorityQueue<>(Comparator.comparingLong(OrderedRetry::dueNanos));
    private final Map<Long, Integer>          attemptsBySeq     = new HashMap<>();
    private final Set<String>                 keysAwaitingRetry = new HashSet<>();

    /**
     * Where the next cursor read starts. Unlike the unordered lane's cursor this is a <em>safe
     * watermark</em>, not a high-water mark: it only passes a sequence value once no transaction that
     * could still commit that value is running. See {@link #advanceWatermark}.
     */
    private       long                           safeCursor;
    /**
     * Highest sequence value observed. The watermark trails this; the gap is the re-read window.
     */
    private       long                           maxSeen;
    /**
     * Observations waiting for the horizon to retire them, oldest first. Each says "at this moment the
     * highest value seen was {@code maxSeen}, and these write transactions were running". Once none of
     * them is running, everything at or below that {@code maxSeen} has resolved.
     */
    private final ArrayDeque<WatermarkCandidate> watermarkCandidates = new ArrayDeque<>();

    private record WatermarkCandidate(long maxSeen, Set<Long> runningXids, long observedAtNanos) {
    }

    /**
     * The candidate at the head of {@link #watermarkCandidates} whose advance on the wall-clock cap has already been
     * counted in {@code watermarkCapped}, or {@code null}. A capped candidate taken while the read was full advances the
     * watermark only as far as that read reached and stays at the head, so it can move the watermark again on every pass
     * while a backlog keeps the reads full; it is counted on the first of those passes only. Compared by identity: two
     * candidates can be equal as records. Pump thread only.
     */
    private WatermarkCandidate cappedCandidateCounted;

    /**
     * The running write transactions at the most recent horizon probe, which by construction was taken BEFORE the
     * current pass's cursor read. Candidates are retired against this, never against the probe taken after the read:
     * a writer that commits between a pass's read and its probe is absent from that probe, yet its rows were invisible
     * to that read, and retiring on the later probe stepped the watermark over them. {@code null} until the first probe.
     */
    private Set<Long> runningAtLastProbe;

    /**
     * Rows the pump read on this owner's behalf, consumed by the next pass. Pump thread only.
     */
    private BatchRead pendingBatch;

    /**
     * @param cursorLimit the row limit the pump's cursor read used - the runtime's {@code readBatchSize}, not
     *                    necessarily this owner's. See {@link BatchReadableOwner#applyBatchRead}.
     */
    private record BatchRead(List<ShardOwnedStorage.OrderedRow> cursorRows,
                             int cursorLimit,
                             List<ShardOwnedStorage.OrderedRow> sweptRows,
                             OptionalLong nextVisibleMillis) {
    }

    /**
     * Everything known but not yet delivered, grouped by key and ordered within it.
     */
    private final Map<String, TreeMap<Long, ShardOwnedStorage.OrderedRow>> readyByKey            = new HashMap<>();
    /**
     * Keys with a message currently in a handler, each with that message's sequence value, its {@code key_order}, and when
     * it was handed to the handler executor, by {@link System#nanoTime()} - which {@link #keysInHandler()} times the longest
     * of from. At most one per key, which is what enforces FIFO.
     */
    private final Map<String, InHandler>                                   keysInFlight          = new LinkedHashMap<>();
    /**
     * The sequence values of the messages in {@link #keysInFlight} whose handler has returned or thrown, while the owner
     * records the outcome: left out of the candidates of {@link #chargeKeysInHandlerBeforeRelease}. Marked before the handler
     * thread asks {@link ReleaseChargeLedger#deadLetteredWhileInHandler}, so a charge that takes its candidates after that
     * leaves the message to the outcome its handler records, and one that took them before is seen running and waited for.
     * Charged regardless, a message whose handler had completed was acknowledged and dead-lettered both.
     * <p>
     * A handler that failed is left out too while it records its failure, and that record is a fenced write: when the
     * release commits before it, the write is refused (the lease is no longer this owner's), and the next owner, finding
     * the unit released, charges nothing - that delivery's attempt is not counted. Accepted: the window runs only from the
     * mark until that write commits, and charged in it, the delivery would be counted twice (by the charge and by its own
     * failure write) or dead-lettered by the charge while its handler schedules a retry of it.
     */
    private final Set<Long>                                                returnedFromHandler   = new HashSet<>();
    /**
     * Highest key_order handed to a handler per key, used to detect ordering violations.
     */
    private final Map<String, Long>                                        highestDeliveredOrder = new HashMap<>();
    /**
     * Sequence values handled and awaiting the next ack flush.
     */
    private final Set<Long>                                                pendingAcks           = new HashSet<>();
    /**
     * Sequence values already seen, so a sweep or a chase cannot re-queue them.
     */
    private final Set<Long>                                                seen                  = new HashSet<>();
    /**
     * Keys with a dead letter, and the lowest {@code key_order} it was recorded at. A key never
     * advances past a dead letter: nothing above that value is delivered, and anything that arrives
     * for it is dead-lettered too.
     * <p>
     * <b>Derived, not remembered.</b> Loaded from the dead-letter table on takeover and refreshed
     * while it is non-empty, so the block survives a rebalance and a restart, and so a resurrect or a
     * delete performed by an administrator in another process clears it. Holding it only in memory
     * would mean every ownership change silently resumed a key past the message nobody handled —
     * which is the failure the rule exists to prevent, reintroduced by its own enforcement.
     */
    private final Map<String, Long>                                        deadLetterBlocks      = new HashMap<>();
    /**
     * Sequence values to move to the dead-letter table on the next pass, because their key is blocked.
     * <p>
     * Decided where the row is met — the cursor read, the sweep, or the moment a block appears under
     * rows already held — and applied by the pump, which is the thread with a connection. The rows
     * leave the ordered table rather than accumulating in {@link #readyByKey}: that is what keeps a
     * stalled key from growing without bound in front of the cursor, and what spares the head sweep
     * from spending its batch re-reading rows it can never deliver.
     */
    private final List<PoisonCandidate>                                    pendingPoison         = new ArrayList<>();

    /**
     * Carries the key and order, not just the sequence value, because a candidate is re-tested against
     * the blocks before it is moved. Between being queued and being written, a refresh can find that
     * the key is no longer blocked, and a message dead-lettered on a block that has since been lifted
     * is a message thrown away for nothing.
     */
    private record PoisonCandidate(ShardOwnedStorage.OrderedRow row, String key, long keyOrder) {
        long seq() {
            return row.seq();
        }
    }

    /**
     * Set once a key has been handed to {@link BlockedKeyReport}, so a blocked key is reported on transition only.
     */
    private final Set<String> reportedBlocks = new HashSet<>();
    /**
     * The in-flight keys an abandoned shed has already been WARNed about. The rebalancer re-attempts
     * the shed every heartbeat tick, so a handler that hangs would otherwise produce the same WARN on
     * every tick for as long as it hangs. Only an abandon held up by a key not reported yet is logged
     * at WARN; a repeat for the same keys goes to DEBUG.
     * <p>
     * Guarded by {@link #stateLock}.
     */
    private final Set<String> reportedStuckKeys = new HashSet<>();
    /**
     * When the wait {@link #stuckOnDeadPump()} reports began, by {@link System#nanoTime()}: when this owner began draining
     * ({@link #drainingSinceNanos}), which on a dead pump is the pump's death, or, if it had not, when the queue's heartbeat
     * first asked. Null until the heartbeat first asks. Guarded by {@link #stateLock}.
     */
    private       Long        deadPumpWaitSinceNanos;
    /**
     * Set once the keys holding up this owner's hand-back from a dead pump have been WARNed about
     * ({@link #stuckOnDeadPump()}). Guarded by {@link #stateLock}.
     */
    private       boolean     deadPumpWaitReported;
    /**
     * Set when a row arrives at exactly a key's blocked {@code key_order} — which can only be the
     * dead letter itself, resurrected, since {@code (key, key_order)} is unique and the original row
     * was deleted when it was parked.
     * <p>
     * It requests a re-derivation rather than clearing the block directly, because clearing would be
     * wrong whenever several of a key's messages are dead-lettered: lifting the block at 5 while 6, 7
     * and 8 are still parked would let 9 through. The table knows the new threshold; memory does not.
     * Without this the block would only lift on the sweep cadence, which backs off to
     * {@code maxSweepInterval} on a shard that has stopped delivering — so a resurrect would appear to
     * do nothing for up to thirty seconds.
     */
    private       boolean     blockRefreshRequested;

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
    private final    HandlerDispatch                    dispatch;
    private final    Object                             stateLock    = new Object();
    private final    AtomicInteger                      activeKeys   = new AtomicInteger();
    private final    String                             instanceId;
    /**
     * Tier 1, which this lane went without.
     * <p>
     * It used to sleep 200 microseconds and re-query.
     * That park paces nothing: the loop runs as fast as the database can answer, so twenty idle
     * ordered owners measured 52 000 queries a second between them. The mechanism to avoid that was
     * already built, tested and measured; it was simply never wired to this lane, and every headline
     * latency figure in the design came from the unordered one.
     */
    private final    ShardWakeup                        wakeup;
    /**
     * Where this owner's lease row stands ({@link LeasedOwner#leaseRow()}). Anything but {@code HELD} stops this owner,
     * rather than racing its successor. Every transition goes through {@link LeaseRowState#advanceTo}, one
     * compare-and-set, not {@link #stateLock}: no field the lock guards changes with it, and the pump's selection check
     * on every pass reads it without taking the lock the handler threads contend for.
     */
    private final    LeaseRowState                      leaseRow     = new LeaseRowState();
    /**
     * Set when this owner has been asked to give the shard up. See {@link #beginShedding()}.
     */
    private final    AtomicBoolean                      shedding     = new AtomicBoolean();
    /**
     * Set once the shard has quiesced and its acknowledgements are flushed — safe to release. Or once its shed has been
     * completed with a key still in a handler longer than {@code stuckHandlerTimeout} ({@link #shedPastStuckHandler}),
     * its acknowledgements flushed all the same.
     */
    private final    AtomicBoolean                      shedComplete = new AtomicBoolean();
    /**
     * The keys still in a handler when this owner's shed was completed without them, because the longest had been in its
     * handler longer than {@code stuckHandlerTimeout} ({@link #advanceShed}); {@code null} for a shed that quiesced, and
     * while none has completed. Written by the pump before it sets {@link #shedComplete}, read by the queue's heartbeat
     * after it has read that set, to say in its WARN what the release left running.
     */
    private volatile KeysInHandler                      shedPastStuckHandler;
    /**
     * Set by {@link #beginDraining()}. Unlike {@link #shedding}, never cleared: the owner is going away
     * whether or not its keys finish in time.
     */
    private final    AtomicBoolean                      draining     = new AtomicBoolean();
    /**
     * When {@link #beginDraining()} set {@link #draining}, by {@link System#nanoTime()}; null until then. A dying pump drains
     * its ordered owners as it dies, so on a dead pump this is the death (for an owner added to the pump after it died, the
     * add), which {@link #stuckOnDeadPump()} times its wait from. Volatile: written by the thread that ends the owner (its
     * pump, the thread adding it to a dead pump, or {@code stop()}), read by the queue's heartbeat.
     */
    private volatile Long                               drainingSinceNanos;
    private volatile long                               shedDeadlineNanos;
    /**
     * Whether this owner's instance has confirmed its own liveness recently enough to dispatch —
     * supplied by {@link ShardOwnedQueue} after construction. Null means always, which is what an
     * owner built without a queue behind it gets.
     */
    private volatile java.util.function.BooleanSupplier deliveryGate;

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

    /**
     * Whether the takeover charge has been made, or is not owed - see {@code ShardOwner#takeoverCharged}. Set as
     * soon as the charge has committed.
     */
    private boolean takeoverCharged;

    /**
     * Whether the exhaustion dead-letter pass that follows the takeover charge has yet to complete - see
     * {@code ShardOwner#exhaustionPending}.
     */
    private boolean exhaustionPending;

    /**
     * The charge {@link #chargeKeysInHandlerBeforeRelease} makes, shared with the unordered lane: the rows charged before a
     * release past their handlers, each with its key and {@code key_order}, and their dead-letter moves; a move that moved
     * the row blocks its key here ({@link #blockKeyChargedBeforeRelease}). The handler of a message it dead-lettered
     * that completes or throws later records nothing against the row, which is gone: no acknowledgement, no attempt, no
     * retry - a WARN instead (an interrupted one is counted in {@code abandonedOnInterrupt} only)
     * ({@link #returnedAfterItsDeadLetter}, {@link #failedAfterItsDeadLetter}).
     */
    private final ReleaseChargeLedger releaseChargeLedger;
    /**
     * The rate limit of the WARN that a release left this owner out, a dead-letter move of it still owed
     * ({@link #owedMoveReleaseRefusals()}).
     */
    private final OwedMoveReleaseRefusals owedMoveReleaseRefusals = new OwedMoveReleaseRefusals();

    /**
     * What to run once no key is in a handler ({@link #whenNoKeyInHandler}); {@code null} unless this owner's queue kept
     * it on stop.
     */
    private volatile Runnable whenNoKeyInHandler;

    OrderedShardOwner(ShardOwnedStorage storage,
                      int shard,
                      long fence,
                      ShardOwnerSettings settings,
                      OrderedPayloadHandler handler,
                      ShardOwnerMetrics metrics,
                      RedeliveryPolicy redeliveryPolicy,
                      String instanceId,
                      ShardWakeup wakeup,
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
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.wakeup = requireNonNull(wakeup, "No wakeup provided");
        this.dispatch = requireNonNull(dispatch, "No dispatch provided");
        this.takeoverCharged = previousOwnerReleased;
        this.releaseChargeLedger = new ReleaseChargeLedger(MessageId.Lane.ORDERED, shard, new ReleaseChargeLedger.Writes() {
            @Override
            public Map<Long, Integer> chargeRowsInHandler(Connection connection, Collection<Long> seqs) throws SQLException {
                return storage.chargeRowsInHandler(connection, ShardOwnedSchema.ORDERED_TABLE, shard, seqs, instanceId, fence);
            }

            @Override
            public boolean moveToDeadLetterIfOwned(Connection connection, long seq) throws SQLException {
                return storage.moveToDeadLetterIfOwned(connection, ShardOwnedSchema.ORDERED_TABLE, "ordered", shard, seq,
                                                       ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER, instanceId, fence);
            }

            @Override
            public boolean isDeadLetteredOnRelease(Connection connection, long seq, int attempts) throws SQLException {
                return storage.isDeadLetteredWith(connection, "ordered", shard, seq, attempts, ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER);
            }

            @Override
            public boolean stillOwns(Connection connection) throws SQLException {
                return storage.stillOwns(connection, "ordered", shard, instanceId, fence);
            }
        }, redeliveryPolicy, metrics, this::recordChargedAttempts, this::blockKeyChargedBeforeRelease);
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
        if (shedding.get() || draining.get()) {
            // A shed only progresses when this owner is pumped, so it must not be skipped for being
            // quiet — a shard nobody is enqueueing to is exactly the one that drains fastest. The
            // same holds for a drain's acknowledgements.
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
        if (!takeoverCharged) {
            var charged = storage.bumpOrderedAttemptsOnTakeover(connection, shard, settings.ackBatchSize(), instanceId, fence);
            if (charged == 0 && !storage.stillOwns("ordered", shard, instanceId, fence)) {
                // Fenced out - see ShardOwner#onTakeover. Loading blocks for a unit this owner no longer holds is pointless.
                leaseLostBeforeTakeover();
                return;
            }
            metrics.takeoverAttemptBumps.add(charged);
            // The bump has committed: record it before anything else can fail, or the next call charges again
            takeoverCharged = true;
            exhaustionPending = true;
        }
        if (exhaustionPending) {
            // A message whose handler took the process down every time never fails - see ShardOwner#onTakeover.
            // Parked before the blocks are read below, so its key is blocked from the first dispatch
            for (var seq : storage.exhaustedAfterTakeover(connection, ShardOwnedSchema.ORDERED_TABLE, shard,
                                                           redeliveryPolicy.maxAttempts())) {
                if (storage.moveToDeadLetterIfOwned(connection, ShardOwnedSchema.ORDERED_TABLE, "ordered", shard, seq,
                                                    ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS, instanceId, fence)) {
                    // Counted before the observer is told: the move has committed, so an observer that throws must not
                    // take the count with it - no later pass finds the row to move again.
                    metrics.deadLettered.increment();
                    metrics.observer().deadLettered(new MessageId(MessageId.Lane.ORDERED, shard, seq),
                                                    redeliveryPolicy.maxAttempts(),
                                                    new IllegalStateException(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS));
                }
            }
            exhaustionPending = false;
        }
        // Before the first pumpOnce, so the first dispatch already knows which keys are blocked. A
        // successor that learned this later would deliver past a dead letter in the window, which is
        // exactly what the previous owner refused to do.
        refreshDeadLetterBlocks(connection);
        // The same reasoning for a key whose message is waiting out a retry backoff: the previous owner
        // held it in memory, and the cursor and the sweep below read only visible rows.
        restoreRetriesInBackoff(connection);
    }

    /**
     * The takeover charge was refused by its fence: the unit's lease row no longer names this instance under this
     * owner's fence. As on the acknowledgement path, that is the lease lost; the pump drops the owner at the end of its
     * pass, before it has dispatched anything. Counted in {@code leasesLost} only when this call moved the owner out of
     * {@code HELD}, so a loss the queue's lease check also finds is counted once - see ShardOwner#leaseLostBeforeTakeover -
     * and only when no release of the row is in doubt ({@link #markReleaseInDoubt()}).
     */
    void leaseLostBeforeTakeover() {
        var was = leaseRow.advanceTo(LeaseRow.RELEASED);
        if (was == LeaseRow.HELD && !leaseRow.releaseInDoubt()) {
            metrics.leasesLost.increment();
            log.warn("Ordered shard {}: takeover charge rejected under fence {} — lease lost, stopping", shard, fence);
        } else if (was == LeaseRow.HELD) {
            log.debug("Ordered shard {}: takeover charge rejected under fence {} after a release of the row whose answer was not "
                              + "received; taken as given up by that release, stopping", shard, fence);
        } else {
            log.debug("Ordered shard {}: takeover charge rejected under fence {}; the owner had already stopped", shard, fence);
        }
    }

    /**
     * Re-create the previous owner's retry blocks from the table (N-16).
     * <p>
     * Each row a retry has pushed into the future goes back into {@link #retrySchedule}, due when the
     * server says it becomes visible, and its key into {@link #keysAwaitingRetry} — exactly the state the
     * owner that scheduled the retry was in. From there the ordinary path takes over: the key is refused
     * by {@link #dispatchReadyKeys} until {@link #dispatchDueRetries} puts the message back at its head.
     * Only the schedule and the block are restored, not the attempt count: that stays on the row, and the
     * message's next failure under this owner continues from it ({@link #countAttempt} reads
     * {@code storedAttempts} when {@link #attemptsBySeq} has no entry), so the hand-over neither resets nor
     * charges the redelivery policy's budget.
     */
    private void restoreRetriesInBackoff(Connection connection) throws SQLException {
        var retries = storage.orderedRetriesInBackoff(connection, shard);
        if (retries.isEmpty()) {
            return;
        }
        var now = System.nanoTime();
        synchronized (stateLock) {
            for (var retry : retries) {
                var row = retry.row();
                keysAwaitingRetry.add(row.key());
                retrySchedule.add(new OrderedRetry(now + retry.delayMillis() * 1_000_000L, row.key(), row.keyOrder(), row));
            }
        }
        log.debug("Ordered unit {}: took over {} message(s) waiting out a retry backoff; their keys stay blocked until each is due",
                  shard, retries.size());
    }

    /**
     * Re-read the blocked keys from the dead-letter table.
     * <p>
     * Called on takeover, and thereafter only while a block exists — a shard with no dead letter pays
     * nothing, and a shard with one pays this on the sweep cadence. It is the only way a resurrect or
     * a delete becomes visible here: both happen through the administrative API, in some other
     * process, against rows this owner never reads.
     */
    private void refreshDeadLetterBlocks(Connection connection) throws SQLException {
        var blocks = storage.orderedDeadLetterBlocks(connection, shard);
        synchronized (stateLock) {
            deadLetterBlocks.clear();
            deadLetterBlocks.putAll(blocks);
            reportedBlocks.retainAll(blocks.keySet());
        }
        metrics.deadLetterBlockReads.increment();
    }

    @Override
    public void flushOnStop(Connection connection) throws SQLException {
        flushAcks(connection);
    }

    @Override
    public long parkDeadlineMillis() {
        var now        = System.nanoTime();
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
    public short queueId() {
        return storage.queueId();
    }

    @Override
    public ShardOwnerMetrics ownerMetrics() {
        return metrics;
    }

    @Override
    public long batchReadCursor() {
        return safeCursor;
    }

    @Override
    public boolean batchSweepDue() {
        return System.nanoTime() - lastSweepNanos >= sweepIntervalNanos;
    }

    @Override
    public void applyBatchRead(List<ShardOwnedStorage.OrderedRow> cursorRows,
                               int cursorLimit,
                               List<ShardOwnedStorage.OrderedRow> sweptRows,
                               OptionalLong nextVisibleMillis) {
        pendingBatch = new BatchRead(cursorRows, cursorLimit, sweptRows, nextVisibleMillis);
    }

    @Override
    public int pumpOnce(Connection connection) throws SQLException {
        if (shedComplete.get()) {
            return 0;
        }
        // Taken once, so a batch cannot be applied twice if a pass repeats for any reason.
        var batch = pendingBatch;
        pendingBatch = null;
        // Read from the WATERMARK, not from the highest value seen, so a value whose transaction had
        // not committed when an earlier pass went by is read again rather than chased. The window
        // between the two is bounded by the horizon, not by the backlog, and `seen` deduplicates it.
        List<ShardOwnedStorage.OrderedRow> rows;
        // The limit the read was ISSUED with, which decides whether it may have stopped short of the backlog. The
        // pump reads with the runtime's readBatchSize, which need not be this owner's: compared against a larger
        // one, a truncated batch looked complete and the watermark stepped over rows it never read.
        int readLimit;
        if (batch != null) {
            rows = batch.cursorRows();
            readLimit = batch.cursorLimit();
        } else {
            readLimit = settings.readBatchSize();
            rows = storage.readOrderedFromCursor(connection, shard, safeCursor, readLimit);
            metrics.orderedReadStatements.increment();
        }
        // Counts owners SERVED, not statements issued — the two stopped being the same when the pump
        // began batching. orderedReadStatements is the one to compare against.
        metrics.cursorReads.increment();
        var lastSeqRead = -1L;
        for (var row : rows) {
            maxSeen = Math.max(maxSeen, row.seq());
            lastSeqRead = Math.max(lastSeqRead, row.seq());
            accept(row);
        }
        // The new candidate's probe comes AFTER the read, never before: a transaction that allocates a value after
        // the probe is not in the set, and treating it as retired would step over it. See ShardOwnedStorage. Retiring
        // uses the PREVIOUS probe instead - see runningAtLastProbe.
        advanceWatermark(connection, rows.size() >= readLimit ? lastSeqRead : READ_NOT_CAPPED);

        var now           = System.nanoTime();
        var sweptThisPass = false;
        if (batch != null) {
            // The pump decided, via batchSweepDue, before issuing the statement. A null list means it
            // did not ask; an empty one means it asked and there was nothing. Recording a sweep that
            // did not happen suppresses the real one AND doubles the backoff — that pairing is what
            // made a mis-bound batched sweep look like starvation rather than an empty result.
            if (batch.sweptRows() != null) {
                applySweptRows(batch.sweptRows(), batch.nextVisibleMillis());
                lastSweepNanos = now;
                sweptThisPass = true;
            }
        } else if (now - lastSweepNanos >= sweepIntervalNanos) {
            sweep(connection);
            lastSweepNanos = now;
            sweptThisPass = true;
        }

        if (takeBlockRefreshRequest() || (sweptThisPass && !deadLetterBlocksEmpty())) {
            // Only while a block stands, and only on the sweep cadence. A shard with no dead letter
            // never issues this; a shard with one is already degraded, and this is how it learns that
            // an administrator resurrected or deleted the message holding its key.
            refreshDeadLetterBlocks(connection);
        }

        dispatchDueRetries(now);
        var delivered = dispatchReadyKeys();
        // After dispatch, because dispatchReadyKeys is what discovers rows held from before a block
        // appeared. Before the acknowledgement flush, so a pass never ends with rows queued for a
        // move that the next read would meet again.
        poisonBlocked(connection);
        if (shedding.get()) {
            // The drain used to be driven by the owner's own loop; with the loop gone it belongs
            // here, which is the only place that still runs on every iteration.
            advanceShed(connection);
        }

        // Not once the shed is complete: a shed completed past stuckHandlerTimeout has named the keys whose acknowledgements
        // it left unflushed (shedPastStuckHandler), and the release's WARN says their messages are delivered again.
        if (!shedComplete.get() && !pendingAcks.isEmpty()
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
            var blockedAt = deadLetterBlocks.get(row.key());
            if (blockedAt != null && row.keyOrder() == blockedAt) {
                // The dead letter came back. Re-derive rather than assume this lifts the block.
                blockRefreshRequested = true;
            } else if (blockedAt != null && row.keyOrder() > blockedAt) {
                // Straight past readyByKey. Holding it would grow the map for as long as the block
                // stands, and it can never be dispatched from there anyway.
                pendingPoison.add(new PoisonCandidate(row, row.key(), row.keyOrder()));
                return;
            }
            readyByKey.computeIfAbsent(row.key(), key -> new TreeMap<>()).put(row.keyOrder(), row);
        }
    }

    /**
     * Whether this {@code key_order} sits behind a dead letter on its key.
     * <p>
     * Strictly above, so a value <em>below</em> the dead letter is still delivered — a message that
     * commits late under a lower {@code key_order} is not behind the failure, it is in front of it,
     * and refusing it would discard a message the key is still entitled to.
     * <p>
     * Caller holds {@link #stateLock}.
     */
    private boolean takeBlockRefreshRequest() {
        synchronized (stateLock) {
            var requested = blockRefreshRequested;
            blockRefreshRequested = false;
            return requested;
        }
    }

    private boolean deadLetterBlocksEmpty() {
        synchronized (stateLock) {
            return deadLetterBlocks.isEmpty();
        }
    }

    private boolean blockedAbove(String key, long keyOrder) {
        var blockedAt = deadLetterBlocks.get(key);
        return blockedAt != null && keyOrder > blockedAt;
    }

    /**
     * Hand each idle key its lowest known {@code key_order}. Keys are independent, so this is where
     * cross-key parallelism comes from — and where per-key serialisation is enforced, by refusing to
     * dispatch a key that already has something in flight.
     */
    private int dispatchReadyKeys() {
        if (shedding.get() || draining.get()) {
            // The whole point of the drain: no new key may start here once the shard is on its way
            // out, or the set this owner is waiting to empty never empties.
            return 0;
        }
        var submitted = 0;
        synchronized (stateLock) {
            var exhaustedKeys = new ArrayList<String>();
            for (var entry : readyByKey.entrySet()) {
                var key     = entry.getKey();
                var byOrder = entry.getValue();
                if (byOrder.isEmpty()) {
                    exhaustedKeys.add(key);
                    continue;
                }
                // At most one message per key in flight. This one refusal is the entire FIFO
                // guarantee — no query, no lock, no exclusion list.
                // In flight, or waiting out a backoff: either way the key is not allowed to move
                // on. A key whose message failed must retry THAT message before any later one.
                if (keysInFlight.containsKey(key) || keysAwaitingRetry.contains(key)) {
                    metrics.keyHeadOfLineBlocks.increment();
                    continue;
                }
                // The guarantee itself, not an optimisation: a key never advances past a dead letter.
                // accept() keeps these out of readyByKey, so reaching this is either a row held from
                // before the block appeared or one the refresh has just learned about — both of which
                // must be moved, not delivered.
                //
                // Tested BEFORE a permit is taken. It used to come after tryAcquire and `continue`
                // without releasing, so every pass through here cost the consumer one of its
                // parallelConsumers permits for good, and enough of them stopped it dispatching at all.
                if (blockedAbove(key, byOrder.firstKey())) {
                    // The lowest value held is already above the block, so everything here is.
                    byOrder.values().forEach(row -> pendingPoison.add(
                            new PoisonCandidate(row, key, row.keyOrder())));
                    byOrder.clear();
                    exhaustedKeys.add(key);
                    continue;
                }
                if (activeKeys.get() >= settings.keyConcurrency()) {
                    break;
                }
                // Per-shard cap first, then the process-wide budget the unordered lane also draws on.
                if (!dispatch.tryAcquire()) {
                    break;
                }
                var next     = byOrder.pollFirstEntry();
                var previous = highestDeliveredOrder.get(key);
                if (previous != null && next.getKey() < previous) {
                    metrics.orderViolations.increment();
                    // Logged, not only counted: a bare counter cannot be traced to a key, and a violation
                    // is either a producer committing out of order or a defect here - both worth finding.
                    log.warn("Ordered unit {}: key '{}' is being delivered key_order {} after {} was already "
                                     + "delivered. Either the producer committed them out of order, or the row was "
                                     + "stepped over and recovered late by the head sweep", shard, key, next.getKey(), previous);
                }
                highestDeliveredOrder.merge(key, next.getKey(), Math::max);

                keysInFlight.put(key, new InHandler(next.getValue().seq(), next.getKey(), System.nanoTime()));
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
        var startedNanos = System.nanoTime();
        try {
            handler.handle(new MessageId(MessageId.Lane.ORDERED, shard, row.seq()),
                           key, keyOrder, row.payload(), row.payloadType());
            markReturnedFromHandler(row.seq());
            // Asked before stateLock is taken: it may wait for a running charge, which takes stateLock itself
            var deadLettered = releaseChargeLedger.deadLetteredWhileInHandler(row.seq());
            if (deadLettered.isPresent()) {
                returnedAfterItsDeadLetter(key, deadLettered.get(), Thread.currentThread().isInterrupted());
                return;
            }
            synchronized (stateLock) {
                if (Thread.currentThread().isInterrupted()) {
                    metrics.abandonedOnInterrupt.increment();
                    requeue(key, keyOrder, row);
                } else {
                    pendingAcks.add(row.seq());
                    metrics.delivered.increment();
                }
            }
        } catch (RuntimeException | Error e) {
            // A non-critical Error is a failed delivery like any other - see ShardOwner.runHandler
            Exceptions.rethrowIfCriticalError(e);
            markReturnedFromHandler(row.seq());
            if (!(e instanceof RedeliveryRequested)) {
                metrics.handlerFailures.increment();
            }
            // Deliberately NOT under stateLock. onFailure performs a database round trip, and holding
            // a monitor across one serialises the whole shard's failure path behind a network call —
            // and, on a virtual thread before JDK 24, pins the carrier for its duration.
            onFailure(key, keyOrder, row, e);
        } finally {
            LeaseOverrun.check(metrics, settings, "ordered", shard, startedNanos);
            boolean noKeyInHandler;
            synchronized (stateLock) {
                keysInFlight.remove(key);
                returnedFromHandler.remove(row.seq());
                noKeyInHandler = keysInFlight.isEmpty();
            }
            activeKeys.decrementAndGet();
            dispatch.release();
            // Capacity may have freed up, and the pump may be parked on its backstop.
            wakeup.signal();
            if (noKeyInHandler) {
                var action = whenNoKeyInHandler;
                if (action != null) {
                    action.run();
                }
            }
        }
    }

    /**
     * Mark the message of {@code seq} as one whose handler has returned ({@link #returnedFromHandler}), before its handler
     * thread asks {@link ReleaseChargeLedger#deadLetteredWhileInHandler}.
     */
    private void markReturnedFromHandler(long seq) {
        synchronized (stateLock) {
            returnedFromHandler.add(seq);
        }
    }

    /**
     * Run {@code action} once no key of this owner is in a handler: at once when none is, and otherwise on the handler
     * thread of the last key to leave, after it has left ({@link #inFlight()} reads 0 by then). Set by
     * {@code ShardOwnedQueue} on an owner its stop kept, because a key was still in a handler past {@code shedGrace}, to
     * hand the unit back once that key has returned, whether or not the queue runs by then. A later key leaving runs it
     * again, so it must be safe to run more than once, and run concurrently; it must not throw.
     */
    void whenNoKeyInHandler(Runnable action) {
        whenNoKeyInHandler = requireNonNull(action, "No action provided");
        if (inFlight() == 0) {
            action.run();
        }
    }

    /**
     * Count this attempt, continuing from the count stored on the row the first time it fails under this owner -
     * see {@code ShardOwner#countAttempt}
     */
    private int countAttempt(Connection connection, long seq) throws SQLException {
        Integer counted;
        synchronized (stateLock) {
            counted = attemptsBySeq.get(seq);
        }
        var attempts = (counted != null ? counted : storage.storedAttempts(connection, ShardOwnedSchema.ORDERED_TABLE, shard, seq)) + 1;
        synchronized (stateLock) {
            attemptsBySeq.put(seq, attempts);
        }
        return attempts;
    }

    /**
     * Failure path for a key. Either schedule a retry that keeps the key blocked, or park the
     * message as a dead letter, which keeps the key blocked behind it until the dead letter is
     * resurrected or deleted.
     */
    private void onFailure(String key, long keyOrder, ShardOwnedStorage.OrderedRow row, Throwable cause) {
        var deadLettered = releaseChargeLedger.deadLetteredWhileInHandler(row.seq());
        if (deadLettered.isPresent()) {
            // Dead-lettered while this handler ran, by the charge before a release (chargeKeysInHandlerBeforeRelease): the row
            // is gone, so there is no attempt to count and nothing to retry - whether the release then succeeded, or failed
            // and left this owner holding the unit. Its key was blocked when the move was made (blockKeyChargedBeforeRelease).
            failedAfterItsDeadLetter(key, deadLettered.get(), cause);
            return;
        }
        try (var connection = storage.connection()) {
            if (cause instanceof RedeliveryRequested request) {
                onRedeliveryRequested(connection, key, keyOrder, row, request);
                return;
            }
            var attempts = countAttempt(connection, row.seq());
            metrics.observer().deliveryFailed(key, attempts, cause);
            if (redeliveryPolicy.isExhausted(attempts)) {
                var moved = storage.moveToDeadLetterIfOwned(connection, ShardOwnedSchema.ORDERED_TABLE, "ordered", shard,
                                                            row.seq(), DeliveryErrorText.describe(cause),
                                                            instanceId, fence);
                if (!moved && leaseLost(key, row, "dead-letter")) {
                    return;
                }
                if (!moved) {
                    // Nothing was parked, so there is no dead letter for the key to wait behind: blocking
                    // it here would hold the key on a block no later owner can see or clear.
                    synchronized (stateLock) {
                        attemptsBySeq.remove(row.seq());
                        seen.remove(row.seq());
                    }
                    log.warn("Ordered shard {}: seq {} of key '{}' exhausted its attempts but was no longer in the "
                             + "table, so it was not dead-lettered", shard, row.seq(), key);
                    return;
                }
                synchronized (stateLock) {
                    attemptsBySeq.remove(row.seq());
                    seen.remove(row.seq());
                    // The key stops here. Recorded in memory now and readable from the dead-letter
                    // table for every owner after this one — the row committed above is what makes
                    // the block outlive this process.
                    blockKey(key, keyOrder);
                }
                // Counted before the observer is told: the move has committed, and an observer that throws must not take
                // the count with it
                metrics.deadLettered.increment();
                metrics.observer().deadLettered(new MessageId(MessageId.Lane.ORDERED, shard, row.seq()),
                                                attempts, cause);
                return;
            }
            var delay = redeliveryPolicy.delayAfter(attempts);
            if (!storage.scheduleRetryIfOwned(connection, ShardOwnedSchema.ORDERED_TABLE, shard, row.seq(), attempts,
                                              delay.toMillis(), instanceId, fence)
                    && leaseLost(key, row, "retry")) {
                return;
            }
            scheduleInMemory(key, keyOrder, row, attempts, delay);
        } catch (SQLException e) {
            log.error("Ordered shard {}: failed to record failure for seq {}", shard, row.seq(), e);
            synchronized (stateLock) {
                requeue(key, keyOrder, row);
            }
        }
    }

    /**
     * The handler of a message the charge before a release dead-lettered while it ran
     * ({@link ReleaseChargeLedger#deadLetteredWhileInHandler}) has returned: its row is gone, so there is nothing to
     * acknowledge, and it is not counted delivered. Completed, the message has been handled and is dead-lettered all the
     * same, so it is logged at WARN: resurrecting that dead letter delivers it again. Interrupted, it was not finished, and
     * its dead letter is where it belongs.
     */
    private void returnedAfterItsDeadLetter(String key, ReleaseChargeLedger.ChargedRow deadLettered, boolean interrupted) {
        forgetDeadLettered(deadLettered.seq());
        if (interrupted) {
            metrics.abandonedOnInterrupt.increment();
            return;
        }
        log.warn("Ordered unit {} of queue {}: the handler of seq {} of key '{}' completed after the charge before a release of "
                 + "the unit had dead-lettered its message at {} attempts. The message is not acknowledged and stays dead-lettered: "
                 + "resurrecting that dead letter delivers it again. To move on, delete it, then resurrect the key's other "
                 + "dead letters, if any (resurrectKey): the key stays blocked until every dead letter of it is resurrected "
                 + "or deleted", shard,
                 storage.queueId(), deadLettered.seq(), key, deadLettered.attemptsAfterCharge());
    }

    /**
     * The handler of a message the charge before a release dead-lettered while it ran
     * ({@link ReleaseChargeLedger#deadLetteredWhileInHandler}) has thrown: its row is gone, so there is no attempt to count
     * and nothing to retry. The dead letter carries {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}, not what the
     * handler threw - often why it hung - so that is logged at WARN with the throwable, and a failure (not a requested
     * redelivery) is told to the {@code QueueObserver} as the attempt the charge counted.
     */
    private void failedAfterItsDeadLetter(String key, ReleaseChargeLedger.ChargedRow deadLettered, Throwable cause) {
        forgetDeadLettered(deadLettered.seq());
        log.warn("Ordered unit {} of queue {}: the handler of seq {} of key '{}' threw after the charge before a release of the "
                 + "unit had dead-lettered its message at {} attempts; that dead letter does not carry this exception",
                 shard, storage.queueId(), deadLettered.seq(), key, deadLettered.attemptsAfterCharge(), cause);
        if (cause instanceof RedeliveryRequested) {
            return;
        }
        try {
            metrics.observer().deliveryFailed(key, deadLettered.attemptsAfterCharge(), cause);
        } catch (RuntimeException | Error e) {
            // On the handler thread, after the outcome is settled: nothing is left for a throwing observer to fail
            Exceptions.rethrowIfCriticalError(e);
            log.warn("Ordered unit {} of queue {}: the QueueObserver told of the failure of seq {} of key '{}' threw", shard,
                     storage.queueId(), deadLettered.seq(), key, e);
        }
    }

    private void forgetDeadLettered(long seq) {
        synchronized (stateLock) {
            attemptsBySeq.remove(seq);
            seen.remove(seq);
        }
    }

    /**
     * A requested redelivery is not a failure, and not an attempt either - see {@code ShardOwner#onRedeliveryRequested}.
     * The key is held exactly as for a retry, and the row is marked so a successor holds it too.
     */
    private void onRedeliveryRequested(Connection connection, String key, long keyOrder, ShardOwnedStorage.OrderedRow row,
                                       RedeliveryRequested request) throws SQLException {
        var delay = request.delay();
        if (!storage.scheduleRequestedRedeliveryIfOwned(connection, ShardOwnedSchema.ORDERED_TABLE, shard, row.seq(),
                                                        delay.toMillis(), instanceId, fence)
                && leaseLost(key, row, "requested redelivery")) {
            return;
        }
        Integer counted;
        synchronized (stateLock) {
            counted = attemptsBySeq.get(row.seq());
        }
        var attemptsSoFar = counted != null ? counted : storage.storedAttempts(connection, ShardOwnedSchema.ORDERED_TABLE, shard, row.seq());
        scheduleInMemory(key, keyOrder, row, attemptsSoFar, delay);
    }

    private void scheduleInMemory(String key, long keyOrder, ShardOwnedStorage.OrderedRow row, int attempts, Duration delay) {
        synchronized (stateLock) {
            keysAwaitingRetry.add(key);
            retrySchedule.add(new OrderedRetry(System.nanoTime() + delay.toNanos(), key, keyOrder, row));
        }
        metrics.retriesScheduled.increment();
        metrics.observer().retryScheduled(key, attempts, delay.toMillis());
    }

    /**
     * Whether a fenced failure write changed nothing because this owner no longer holds the lease, rather than
     * because the row was gone. The failure is then the successor's to record. The key stays blocked here,
     * with nothing scheduled, so this owner never dispatches it again: a later message for the key delivered
     * by a superseded owner would race the successor's redelivery of this one, and that is reordering.
     */
    private boolean leaseLost(String key, ShardOwnedStorage.OrderedRow row, String write) throws SQLException {
        if (storage.stillOwns("ordered", shard, instanceId, fence)) {
            return false;
        }
        synchronized (stateLock) {
            attemptsBySeq.remove(row.seq());
            keysAwaitingRetry.add(key);
        }
        if (leaseRow.releaseInDoubt()) {
            log.info("Ordered shard {}: {} for seq {} of key '{}' not recorded under fence {} after a release of the row whose "
                     + "answer was not received - the next owner redelivers the message", shard, write, row.seq(), key, fence);
        } else {
            log.info("Ordered shard {}: {} for seq {} of key '{}' not recorded under fence {} - lease lost, the next "
                     + "owner redelivers the message", shard, write, row.seq(), key, fence);
        }
        return true;
    }

    /**
     * Record that a key is blocked, and queue everything already held above the block for the
     * dead-letter table.
     * <p>
     * Caller holds {@link #stateLock}.
     */
    private void blockKey(String key, long keyOrder) {
        var previous = recordBlock(key, keyOrder);
        if (reportedBlocks.add(key)) {
            // Once per key, and further limited per consumer by BlockedKeyReport: a handler failing for
            // everything blocks every key with traffic, which was a WARN per key without end.
            BlockedKeyReport.keyBlocked(metrics, storage.queueId(), shard, key, previous);
        }
        metrics.keysBlockedByDeadLetter.increment();
    }

    /**
     * Block {@code key} at {@code keyOrder} - or at a lower block it already has - and queue everything already held above
     * the block for the dead-letter table.
     * <p>
     * Caller holds {@link #stateLock}.
     *
     * @return the {@code key_order} the key is now blocked at
     */
    private long recordBlock(String key, long keyOrder) {
        var blockedAt = deadLetterBlocks.merge(key, keyOrder, Math::min);
        var held      = readyByKey.get(key);
        if (held != null) {
            var above = held.tailMap(blockedAt, false);
            above.values().forEach(row -> pendingPoison.add(new PoisonCandidate(row, key, row.keyOrder())));
            above.clear();
        }
        return blockedAt;
    }

    /**
     * The ledger has moved a message {@link #chargeKeysInHandlerBeforeRelease} charged to the dead-letter table
     * ({@code deadLettered}; it is never told of a move that found the row gone): block its key here at its
     * {@code key_order}, as {@link #onFailure} does for a message it dead-letters, so this owner never dispatches a later
     * {@code key_order} of that key past the dead letter - not even before its next pass re-reads the blocks from the table,
     * which already holds the dead letter ({@link #refreshDeadLetterBlocks}). The key and {@code key_order} are the ledger's
     * own record of the message: the move may be made by a later call, after its handler has returned and its key has left
     * {@link #keysInFlight}. Not reported to {@link BlockedKeyReport} nor counted in {@code keysBlockedByDeadLetter}, as
     * before this block was recorded: the dead-lettered keys reach the queue through {@link ReleaseCharge#deadLetteredKeys()}.
     * On the thread making the charge, under the ledger's monitor, as {@link #recordChargedAttempts} is.
     */
    private void blockKeyChargedBeforeRelease(ReleaseChargeLedger.ChargedRow deadLettered) {
        synchronized (stateLock) {
            recordBlock(deadLettered.key(), deadLettered.keyOrder());
        }
    }

    /**
     * Move the rows queued by {@link #blockKey} and {@link #accept} into the dead-letter table.
     * <p>
     * On the pump thread, which is the one holding a connection, and after the reads rather than
     * during them: a row met by the cursor and a row met by the sweep go to the same place, and doing
     * it in one statement per pass keeps a backlog behind a blocked key from becoming a transaction
     * per message.
     */
    private void poisonBlocked(Connection connection) throws SQLException {
        List<PoisonCandidate> batch;
        synchronized (stateLock) {
            if (pendingPoison.isEmpty()) {
                return;
            }
            // Re-tested against the blocks as they stand NOW, which a refresh earlier in this pass may
            // have changed. A candidate whose key is no longer blocked is put back where it would have
            // gone in the first place — it was queued for a move on a block that has since been
            // lifted, and dead-lettering it would throw away a message for nothing.
            //
            // Back into readyByKey rather than merely forgotten: the candidate still carries its row,
            // so the key's TreeMap orders it against everything else held and it is dispatched on this
            // pass. Dropping it and letting the head sweep find it again would be correct too, and up
            // to a sweep interval later — which is precisely the wait a bulk resurrect exists to avoid.
            batch = new ArrayList<>(pendingPoison.size());
            var released = new ArrayList<PoisonCandidate>();
            for (var candidate : pendingPoison) {
                if (blockedAbove(candidate.key(), candidate.keyOrder())) {
                    batch.add(candidate);
                } else {
                    released.add(candidate);
                }
            }
            pendingPoison.removeAll(released);
            released.forEach(candidate -> readyByKey.computeIfAbsent(candidate.key(), key -> new TreeMap<>())
                                                    .put(candidate.keyOrder(), candidate.row()));
            if (batch.isEmpty()) {
                return;
            }
        }
        var seqs = batch.stream().map(PoisonCandidate::seq).toList();
        var moved = storage.poisonOrderedBehindDeadLetter(connection, shard, seqs,
                                                          DeadLetter.BLOCKED_BEHIND_DEAD_LETTER);
        metrics.messagesPoisonedBehindDeadLetter.add(moved.size());
        var notMoved = batch.stream().filter(candidate -> !moved.contains(candidate.seq())).toList();
        if (!notMoved.isEmpty()) {
            // The table had no dead letter left below them: the block in memory is stale, typically
            // because an operator deleted the dead letter since the last refresh. Re-read it now, so
            // the rows handed back below are dispatched on this block's absence rather than queued
            // for a move again.
            refreshDeadLetterBlocks(connection);
        }
        synchronized (stateLock) {
            pendingPoison.removeAll(batch);
            // The rows are gone from the lane, so remembering their sequence values would leak for as
            // long as this owner lives — the same reason flushAcks forgets what it acknowledged.
            moved.forEach(seen::remove);
            // Still in the lane, so back where accept() would have put them had the block been known
            // to be gone.
            notMoved.forEach(candidate -> readyByKey.computeIfAbsent(candidate.key(), key -> new TreeMap<>())
                                                    .put(candidate.keyOrder(), candidate.row()));
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
     * object per queue for the ordered lane, {@code CACHE 1} — and a transaction is assigned its xid no
     * later than the value it allocates. PostgreSQL does not make that last part true on its own: an xid
     * is assigned lazily, and {@code nextval()} assigns one only on the one call in 32 that WAL-logs, so
     * every enqueue path forces it first ({@code ShardOwnedStorage.assignTransactionIdBeforeAllocating}).
     * Without that a writer held a value while invisible here, and the watermark stepped over it. So
     * every value at or below {@code maxSeen} was allocated by a
     * transaction that already held an xid when this owner observed it, and any such transaction still
     * running is in the set recorded alongside. Once none of that set is running, every value at or
     * below that {@code maxSeen} has resolved: committed and visible, or aborted and never coming.
     * <p>
     * Resolved is not yet read, and the cursor may only pass what a read has covered. So the argument has a second
     * premise: <b>the writers must have ended before a read that bounds the advance.</b> Two mechanisms carry it.
     * A candidate is recorded with the probe taken after this pass's read, but retired only against the probe taken
     * before it ({@link #runningAtLastProbe}, the previous pass's): a writer missing from that probe ended before the
     * read ran, so the read saw its rows. Retired on the probe taken after the read, a writer committing between the
     * read and that probe was missing from it while its rows were invisible to the read, and the watermark stepped over
     * them - the head sweep then delivered them behind later {@code key_order}s of the same key. And when that read
     * returned as many rows as its limit, rows above the last one it returned were not read by it, so the advance stops at
     * {@code readReachedSeq} and the candidate stays for a later pass: advancing to the candidate's {@code maxSeen}
     * stepped over a late commit the truncated read did not reach.
     * <p>
     * This is what replaces hole detection on this lane, and it is exact where hole detection was a
     * guess. A gap is not chased, not timed, and not written off after {@code holeExpiry} — it is
     * simply read again on the next pass, and the cursor does not step over it until the database says
     * nothing can still fill it.
     * <p>
     * <b>The cap.</b> The horizon is database-global, so a long-running <em>writing</em> transaction
     * anywhere holds it back. Read-only transactions do not, however long they run — only a writer is
     * assigned an xid. Rather than stall the lane behind an unrelated batch writer, a candidate older
     * than {@code watermarkCap} is taken anyway and counted - once, even when a full read makes the
     * advance past it take several passes. That is the same exposure an abandoned
     * hole carried, but on its own setting and a far more generous one: nothing about the watermark
     * pushes the bound down, where {@code holeExpiry} was held low by the cost of the map and the
     * chase query per unresolved value. Reusing {@code holeExpiry} here would reproduce the mechanism
     * this replaces rather than replacing it.
     *
     * @param readReachedSeq the last seq this pass's cursor read returned when the read returned as many rows as
     *                       the limit it was issued with (it may have stopped short of the backlog, so no candidate
     *                       moves the watermark past it), or {@link #READ_NOT_CAPPED} when it returned fewer
     */
    // cappedCandidateCounted names one entry of watermarkCandidates, so it is compared by identity: WatermarkCandidate
    // is a record, and equals() would also match a different candidate holding the same values.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private void advanceWatermark(Connection connection, long readReachedSeq) throws SQLException {
        if (watermarkCandidates.isEmpty() && maxSeen <= safeCursor) {
            // Fully caught up: nothing to retire and nothing worth recording. This is the idle poll,
            // which is the overwhelming majority of them, and it must not pay for the horizon probe.
            return;
        }
        // Taken before this pass's read, so a writer missing from it had committed (or aborted) before the read ran,
        // and its rows were visible to it. The probe below, taken after the read, cannot say that: a writer that
        // committed between the read and that probe is missing from it although the read never saw its rows.
        var runningBeforeRead = runningAtLastProbe;
        var now               = System.nanoTime();
        var running           = storage.runningWriteTransactionIds(connection);
        runningAtLastProbe = running;
        lastHorizonProbeNanos = now;
        metrics.horizonProbes.increment();

        var advanceTo = -1L;
        // The cap moved the watermark for a candidate not counted yet. A capped candidate this pass advances only part
        // of the way stays at the head, and is counted once however many passes it takes - see cappedCandidateCounted.
        var                capped        = false;
        WatermarkCandidate cappedAndKept = null;
        while (!watermarkCandidates.isEmpty()) {
            var candidate = watermarkCandidates.peekFirst();
            var retired   = runningBeforeRead != null && Collections.disjoint(candidate.runningXids(), runningBeforeRead);
            var expired   = now - candidate.observedAtNanos() > settings.watermarkCapNanos();
            if (!retired && !expired) {
                break;
            }
            capped |= !retired && candidate != cappedCandidateCounted;
            if (candidate.maxSeen() > readReachedSeq) {
                // This pass's read stopped at its batch limit, so rows above readReachedSeq were not read since the
                // writers retired - possibly including a late commit. Advance only as far as the read reached; the
                // candidate stays for the next pass to finish.
                cappedAndKept = retired ? null : candidate;
                advanceTo = Math.max(advanceTo, readReachedSeq);
                break;
            }
            advanceTo = Math.max(advanceTo, candidate.maxSeen());
            watermarkCandidates.removeFirst();
            if (candidate == cappedCandidateCounted) {
                cappedCandidateCounted = null;
            }
        }
        if (advanceTo > safeCursor) {
            safeCursor = advanceTo;
            metrics.watermarkAdvances.increment();
            if (cappedAndKept != null) {
                cappedCandidateCounted = cappedAndKept;
            }
            if (capped) {
                metrics.watermarkCapped.increment();
                log.debug("Ordered shard {}: watermark advanced to {} on the wall-clock cap — a write "
                                  + "transaction outlived watermarkCap ({}s). Messages from that transaction "
                                  + "may have been skipped; find the long transaction rather than raising the "
                                  + "cap. Note this lane does not use holeExpiry, which an earlier version of "
                                  + "this message named.", shard, safeCursor, settings.watermarkCap().toSeconds());
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
        var rows      = storage.sweepOrderedFromHead(connection, shard, settings.readBatchSize());
        var untilNext = storage.millisUntilNextVisible(connection, ShardOwnedSchema.ORDERED_TABLE, shard);
        metrics.orderedReadStatements.add(2);
        applySweptRows(rows, untilNext);
    }

    /**
     * The half of a sweep that is not a query, so it can be driven either by this owner's own two
     * statements or by the pump's batched ones.
     */
    private void applySweptRows(List<ShardOwnedStorage.OrderedRow> rows, OptionalLong untilNext) {
        metrics.headSweeps.increment();
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
                // Applied after this pass's advanceWatermark, so this is the watermark the next cursor read starts
                // from: a row at or below it is one no cursor read will return. A row above it is one the next cursor
                // read would still return - most often one committed after the cursor read and before the sweep, or one
                // put back for redelivery - and is no sign of anything stepped over.
                if (row.seq() <= safeCursor) {
                    metrics.sweepRecoveriesBehindCursor.increment();
                }
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
        flushAckBatch(connection, batch);
    }

    /**
     * Flush the acknowledgements queued now, and name the keys in a handler as they stood when those were taken: the two
     * are read in one hold of {@link #stateLock} ({@link #acksAndKeysInHandler()}), so they agree exactly. A handler queues
     * its acknowledgement before its key leaves {@link #keysInFlight} ({@link #runHandler}), so a key not named had its
     * acknowledgement in the batch flushed here, and a named key's acknowledgement was not in it: queued once this call has
     * taken the batch, it waits for a later flush. Asked by {@code ShardOwnedQueue.releaseKeptOrderedUnit}, which releases
     * the row next without another flush, so the keys named are exactly those whose acknowledgements the release drops.
     * Read as two steps - flushed, then asked - a handler returning while the flush ran went unnamed though its
     * acknowledgement was never flushed.
     *
     * @return the keys in a handler when the batch was taken; empty when none was
     * @throws SQLException when the flush fails; nothing is acknowledged then, and the acknowledgements stay queued
     */
    Optional<KeysInHandler> flushAcksNamingKeysInHandler(Connection connection) throws SQLException {
        var taken = acksAndKeysInHandler();
        flushAckBatch(connection, taken.acks());
        return taken.inHandler();
    }

    /**
     * The acknowledgements queued now and the keys in a handler now, read in one hold of {@link #stateLock}. See
     * {@link #flushAcksNamingKeysInHandler}.
     */
    private AcksAndKeysInHandler acksAndKeysInHandler() {
        synchronized (stateLock) {
            return new AcksAndKeysInHandler(List.copyOf(pendingAcks), keysInHandler());
        }
    }

    /**
     * What {@link #acksAndKeysInHandler()} read.
     *
     * @param acks      the sequence values whose acknowledgements were queued
     * @param inHandler the keys in a handler; empty when none was
     */
    private record AcksAndKeysInHandler(List<Long> acks, Optional<KeysInHandler> inHandler) {
    }

    /**
     * Delete {@code batch}, fenced, and drop it from {@link #pendingAcks}; nothing for an empty batch. A refusal by the
     * fence moves the owner to {@code RELEASED} and leaves the batch queued.
     */
    private void flushAckBatch(Connection connection, List<Long> batch) throws SQLException {
        if (batch.isEmpty()) {
            return;
        }
        var deleted = storage.acknowledgeOrdered(connection, shard, batch, instanceId, fence);
        metrics.ackFlushes.increment();
        if (deleted == 0 && !storage.stillOwns("ordered", shard, instanceId, fence)) {
            // Zero rows deleted is ambiguous: refused by the fence, or already gone. Only the first
            // means the shard has moved. Leave the work for whoever holds it — acknowledging after a
            // real fencing rejection would delete messages the new owner is about to deliver, and for
            // an ordered key that is loss AND reordering at once.
            // Counted and logged only by the refusal that moves the row to RELEASED. One already there was released by
            // this instance (releaseLeaseRows: a stop, a shed, a hand-back or release on request of a kept unit) or found
            // taken by an earlier check or write: a flush returning after that - a stop's, still running once its kept
            // unit was given up and released - is refused by what already ended this owner, not by a lease lost now.
            if (leaseRow.advanceTo(LeaseRow.RELEASED) == LeaseRow.RELEASED) {
                log.debug("Ordered shard {}: acknowledgement refused under fence {} for a lease row already given up or found taken",
                          shard, fence);
                return;
            }
            // Nor by one refused after a release of the row failed with no error answer from the database (a lost
            // connection or a timed-out read, LeaseRowState#mayHaveGoneThrough; ShardOwnedQueue.releaseLeaseRows marked
            // it): that release may have gone through on the server after all - a kept unit's hand-back or release on
            // request whose answer the network wait bound stopped waiting for - the row given up by this instance itself.
            if (leaseRow.releaseInDoubt()) {
                log.debug("Ordered shard {}: acknowledgement refused under fence {} after a release of the row whose answer was not "
                                  + "received; taken as given up by that release, stopping", shard, fence);
                return;
            }
            metrics.fencedOutAcks.increment();
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
     * owner still holds, and only then is the shard let go. With one exception: a key whose handler has
     * run longer than {@code stuckHandlerTimeout} is not waited for ({@link #advanceShed}).
     */
    /**
     * @return true if THIS call started the shed, false if one was already under way. The caller
     * re-attempts every heartbeat tick by design, and reports only what it actually started.
     */
    public boolean beginShedding() {
        if (shedding.compareAndSet(false, true)) {
            shedDeadlineNanos = System.nanoTime() + settings.shedGraceNanos();
            metrics.shedsStarted.increment();
            log.debug("Ordered unit {}: shedding — draining in-flight keys before releasing", shard);
            return true;
        }
        return false;
    }

    /**
     * Drive one step of a shed. Returns true when the shard is ready to be released: it quiesced, or, once the grace ran
     * out, the key handed to its handler first had been there longer than {@code stuckHandlerTimeout}
     * ({@link ShardOwnerSettings}), which counts that handler as stuck: the shed is then completed with the keys still in
     * their handlers ({@link #shedPastStuckHandler()}), its acknowledgements flushed as on a quiesced shed, and the queue
     * releases the unit. The keys named are read with the batch that flush takes ({@link #acksAndKeysInHandler()}), and
     * nothing on the way to the shed's release flushes it after that, so they are exactly the keys whose acknowledgements
     * the release drops.
     * Otherwise, when the grace runs out the shed is abandoned and the shard kept, and the queue retries it on its next
     * heartbeat.
     */
    private boolean advanceShed(Connection connection) throws SQLException {
        int inFlight;
        synchronized (stateLock) {
            inFlight = keysInFlight.size();
            if (inFlight == 0) {
                reportedStuckKeys.clear();
            }
        }
        if (inFlight == 0) {
            // Flush while the fence is still valid. Acknowledging after the release would be refused,
            // and every handled-but-unacked message would be redelivered by the new owner for nothing.
            flushAcks(connection);
            shedComplete.set(true);
            metrics.shedsCompleted.increment();
            // DEBUG rather than INFO: the release this leads to is reported by the queue, one pass
            // later and once for the whole batch. Announcing readiness per unit as well said the same
            // thing 32 times and then said it again as a summary.
            log.debug("Ordered unit {}: quiesced, ready for another instance", shard);
            return true;
        }
        if (System.nanoTime() >= shedDeadlineNanos) {
            // Past stuckHandlerTimeout the handler counts as stuck, and the shed stops waiting for it: kept here, the unit
            // stayed unbalanced, and its other keys unserved, for as long as the handler hung - until a restart if it never
            // returned. Asked only once the grace has run out, so a key that is merely slow has had the grace to finish.
            // Released with every key still in a handler - the stuck one and any other still running - so the next owner
            // delivers each of those messages again, possibly beside its handler: the at-least-once the bound trades the
            // stuck unit for.
            // The keys and the acknowledgements to flush are read together (acksAndKeysInHandler), so the keys named are
            // exactly those whose acknowledgements this shed does not flush. Read as two steps - the keys, then the batch -
            // a handler returning between them was named though its acknowledgement was flushed.
            var taken     = acksAndKeysInHandler();
            var inHandler = taken.inHandler();
            if (inHandler.isPresent() && inHandler.get().longest().compareTo(settings.stuckHandlerTimeout()) >= 0) {
                // Flushed while the fence is still valid, as for a quiesced shed: what finished is not delivered again. A
                // named handler returning from here on queues an acknowledgement nothing flushes: the pump flushes nothing
                // more for a complete shed (pumpOnce), and the queue releases the row without a flush.
                flushAckBatch(connection, taken.acks());
                shedPastStuckHandler = inHandler.get();
                shedComplete.set(true);
                metrics.shedsCompleted.increment();
                // The queue's heartbeat releases the unit and WARNs, naming the queue as this owner cannot.
                log.debug("Ordered unit {}: shed completed with {} key(s) still in a handler {}, the longest there {} ms, past "
                                  + "stuckHandlerTimeout ({})", shard, inHandler.get().keys().size(), inHandler.get().describeKeys(),
                          inHandler.get().longest().toMillis(), settings.stuckHandlerTimeout());
                return true;
            }
            // A handler outlasted the grace. Keep the shard rather than release it mid-key: staying
            // unbalanced is a performance cost, releasing here would be an ordering bug.
            shedding.set(false);
            metrics.shedsAbandoned.increment();
            List<String> stuckKeys;
            boolean      notReportedYet;
            synchronized (stateLock) {
                stuckKeys = List.copyOf(keysInFlight.keySet());
                // Forget keys that have since finished, so one that gets stuck again later is reported again.
                reportedStuckKeys.retainAll(keysInFlight.keySet());
                notReportedYet = reportedStuckKeys.addAll(keysInFlight.keySet());
            }
            if (notReportedYet) {
                log.warn("Ordered shard {}: shed abandoned with {} key(s) still in a handler {} — keeping the shard. "
                                 + "The shed is retried every heartbeat, and once a key has been in its handler longer than "
                                 + "stuckHandlerTimeout ({}) a retry releases the shard with every key still in a handler; this is logged "
                                 + "again only if another key holds it up",
                         shard, stuckKeys.size(), describeKeys(stuckKeys), settings.stuckHandlerTimeout());
            } else {
                log.debug("Ordered shard {}: shed abandoned again with {} key(s) still in a handler {}",
                          shard, stuckKeys.size(), describeKeys(stuckKeys));
            }
            return false;
        }
        return false;
    }

    /**
     * The keys named in a log line, capped: they are what an operator looks up, but a shard can hold
     * up to {@code keyConcurrency} of them.
     */
    static String describeKeys(List<String> keys) {
        var max = 10;
        return keys.size() <= max ? keys.toString() : keys.subList(0, max) + " and " + (keys.size() - max) + " more";
    }

    @Override
    public void beginDraining() {
        if (draining.compareAndSet(false, true)) {
            drainingSinceNanos = System.nanoTime();
            wakeup.signal();
        }
    }

    @Override
    public int inFlight() {
        synchronized (stateLock) {
            return keysInFlight.size();
        }
    }

    /**
     * The keys holding up this owner's hand-back from a dead pump, once they have held it up for longer than
     * {@code shedGrace} and have not been reported yet.
     * <p>
     * Asked by {@code ShardOwnedQueue.handBackUnitsOnDeadPumps} on every heartbeat that finds this owner on a dead pump
     * with a key still in a handler. The unit is not handed back while one is - a successor would start that key beside it
     * - so a hung handler keeps it leased and unserved until it returns, or until the key handed to its handler first has
     * been there longer than {@code stuckHandlerTimeout}, when the queue hands the unit back anyway ({@link #keysInHandler()}).
     * This is the same failure an abandoned shed reports ({@link #advanceShed}), and it gets the same treatment: past the
     * grace, one WARN naming the keys. The wait is timed from when this owner began draining ({@link #drainingSinceNanos}): the dying
     * pump ends it with {@link #beginDraining()}, so on a dead pump that is the death (for an owner added to the pump
     * after it died, the add, which ends it). The WARN therefore comes {@code shedGrace} after the pump's death ERROR,
     * and its wait matches the time since that ERROR. An owner that never began draining is timed from the first
     * heartbeat that asks.
     * <p>
     * Reported once per owner. The owner drains from the moment its pump dies ({@link #beginDraining()}), and its pump,
     * the only thread that dispatches, is gone, so no key enters {@link #keysInFlight} after the first report: the keys it
     * names are every key that will hold this hand-back up, and a repeat could only name fewer of them.
     *
     * @return the keys to name, and how long they have held the hand-back up; empty while within the grace, once reported,
     * or when no key is in a handler
     */
    Optional<StuckOnDeadPump> stuckOnDeadPump() {
        var now = System.nanoTime();
        synchronized (stateLock) {
            if (keysInFlight.isEmpty()) {
                return Optional.empty();
            }
            if (deadPumpWaitSinceNanos == null) {
                var drainingSince = drainingSinceNanos;
                deadPumpWaitSinceNanos = drainingSince != null ? drainingSince : now;
            }
            var waited = now - deadPumpWaitSinceNanos;
            if (deadPumpWaitReported || waited < settings.shedGraceNanos()) {
                return Optional.empty();
            }
            deadPumpWaitReported = true;
            return Optional.of(new StuckOnDeadPump(List.copyOf(keysInFlight.keySet()), Duration.ofNanos(waited)));
        }
    }

    /**
     * The keys an ordered unit on a dead pump is waiting for, and how long it has waited. See {@link #stuckOnDeadPump()}.
     *
     * @param keys   the keys still in a handler
     * @param waited how long they have held up the hand-back, counted from the pump's death, or, for an owner that
     *               never began draining, from the first heartbeat that asked (see
     *               {@link OrderedShardOwner#stuckOnDeadPump()})
     */
    record StuckOnDeadPump(List<String> keys, Duration waited) {
        /**
         * The keys as a log line names them: capped, as an abandoned shed's are.
         */
        String describeKeys() {
            return OrderedShardOwner.describeKeys(keys);
        }
    }

    /**
     * The keys in a handler now, and how long the one handed to its handler first has been there: timed from when this
     * owner handed the message to the handler executor, so a handler still waiting for a thread counts too. Asked, to tell
     * when that handler has outlasted {@code stuckHandlerTimeout} and to name the keys in a WARN, by {@code ShardOwnedQueue}
     * of an owner its stop kept and of one on a dead pump, and by this owner's shed once its grace has run out
     * ({@link #advanceShed}).
     *
     * @return empty when no key is in a handler
     */
    Optional<KeysInHandler> keysInHandler() {
        var now = System.nanoTime();
        synchronized (stateLock) {
            if (keysInFlight.isEmpty()) {
                return Optional.empty();
            }
            var earliest = keysInFlight.values().stream().mapToLong(InHandler::sinceNanos).min().getAsLong();
            return Optional.of(new KeysInHandler(List.copyOf(keysInFlight.keySet()), Duration.ofNanos(now - earliest)));
        }
    }

    /**
     * The message of a key in a handler ({@link #keysInFlight}).
     *
     * @param seq        the message's sequence value
     * @param keyOrder   the message's {@code key_order}: where its key is blocked if the charge before a release
     *                   dead-letters it ({@link #chargeKeysInHandlerBeforeRelease})
     * @param sinceNanos when it was handed to the handler executor, by {@link System#nanoTime()}
     */
    record InHandler(long seq, long keyOrder, long sinceNanos) {
    }

    /**
     * Which messages in a handler {@link #chargeKeysInHandlerBeforeRelease} charges, by what is releasing the unit.
     */
    enum ReleaseChargeScope {
        /**
         * Only those in a handler longer than {@code stuckHandlerTimeout} ({@link ShardOwnerSettings}): for a release past
         * a stuck handler, by a fair-share shed ({@link #shedPastStuckHandler()}) or by the hand-back from a dead pump
         * ({@code ShardOwnedQueue.handBackUnitsOnDeadPumps}). Either fires once one key has been in its handler that long, so
         * the other keys of the unit may have entered theirs moments before; a handler that has run for less is taken to be
         * slow, not hung, and its message is released uncharged, as the unordered lane releases one
         * ({@code ShardOwner.chargeInHandlerBeforeRelease}).
         */
        PAST_STUCK_HANDLER_TIMEOUT,
        /**
         * Every one in a handler, however long it has been there: for a release on request
         * ({@code ShardOwnedQueue.releaseKeptOrderedUnit}), which an operator asks for, and which no handler's age decides.
         * Also asked by the hand-back of a kept unit ({@code ShardOwnedQueue.handBackKeptUnit}), with no key in a handler:
         * it charges nothing new there, and only makes a dead-letter move an earlier release left owed.
         */
        EVERY_KEY_IN_HANDLER
    }

    /**
     * Charge one attempt to the message of each key in a handler that {@code scope} names, and dead-letter each one the
     * charge brings to the redelivery limit, before the queue releases this owner's lease row without waiting for those
     * handlers: past {@code stuckHandlerTimeout}, by a fair-share shed ({@link #shedPastStuckHandler()}) or by the hand-back
     * from a dead pump ({@code ShardOwnedQueue.handBackUnitsOnDeadPumps}), which charge only the messages in a handler
     * longer than {@code stuckHandlerTimeout} ({@link ReleaseChargeScope#PAST_STUCK_HANDLER_TIMEOUT}); or on request
     * ({@code ShardOwnedQueue.releaseKeptOrderedUnit}), which charges every message in a handler
     * ({@link ReleaseChargeScope#EVERY_KEY_IN_HANDLER}).
     * <p>
     * Whoever takes a released unit next charges nothing ({@link ShardOwnedStorage.LeaseGrant#previousOwnerReleased()}),
     * and a handler that hangs never returns to record its attempt, so without this a message whose handler hung on every
     * delivery was handed from owner to owner with its count unchanged, and never reached the dead-letter table. It is the
     * charge an unclean takeover makes for a delivery whose outcome was never recorded ({@link #onTakeover}), made by the
     * owner that knows which rows those are: messages in a handler, nothing queued behind them. A shed or a dead-pump
     * hand-back fires as soon as one key has been in its handler longer than {@code stuckHandlerTimeout}, so another key of
     * the unit may have entered its handler moments before; a handler that has run for less is taken to be slow, not hung,
     * and its message is released uncharged - the next owner delivers it again - as the unordered lane does
     * ({@code ShardOwner.chargeInHandlerBeforeRelease}). Charged, such a message at its last attempt but one was
     * dead-lettered though its handler was healthy, and its key blocked behind the dead letter until an operator
     * resurrected it. A release on request charges every message in a handler, whatever its age.
     * <p>
     * A row the charge brings to the limit is dead-lettered here with
     * {@link ShardOwnedStorage#EXHAUSTED_IN_A_RELEASED_HANDLER}, as the next owner runs no exhaustion pass for a released
     * unit; that owner then finds the key blocked behind the dead letter ({@link #refreshDeadLetterBlocks}). The handler may
     * still be running the message when it is dead-lettered.
     * <p>
     * This owner blocks the key at the dead letter's {@code key_order} too, as soon as the move is made
     * ({@link #blockKeyChargedBeforeRelease}), as it does for a message its own failure dead-letters. No key enters a
     * handler of an owner this runs on - a completed shed's pump pass returns at once, and a dead pump's owner or one a
     * stop kept is draining - and a release that fails leaves it so; the block is recorded all the same, so a change that
     * lets such an owner dispatch again cannot deliver a later {@code key_order} of the key past its dead letter.
     * <p>
     * Fenced, as the release after it is: the row must still name this instance under this owner's fence, which releasing
     * does not bump. Each message is charged once per owner: recorded as soon as the charge has committed, so a release that
     * failed and is retried does not charge it again, and a message that has gone past the bound since - or a release on
     * request after a shed's or a hand-back's - is charged by the later call; the dead-letter moves are made until a call
     * completes them, as the takeover's exhaustion pass is. Callers do not run it for one owner at the same time (the
     * queue's heartbeat, or a release on request under its claim); the ledger's monitor makes it safe if they do.
     * <p>
     * The {@code QueueObserver} told of a message dead-lettered here is told after the move, and what it throws is held
     * until every move is made, then thrown: by then nothing is owed ({@link #owesReleaseCharge}), so the queue tells that
     * failure from a failed charge and releases the unit all the same.
     * <p>
     * The charge itself is {@link ReleaseChargeLedger}'s, which the unordered lane composes too
     * ({@code ShardOwner.chargeInHandlerBeforeRelease}); this owner supplies only its candidates - the message of each key
     * in a handler that {@code scope} names, with its key and {@code key_order}, which the ledger keeps with what it
     * charged, taken once the ledger's charge is running and leaving out a message whose handler has returned - its table,
     * and the block it records once a move is made. The dead-lettered keys it reports are in sequence order.
     *
     * @param scope which messages in a handler to charge
     * @return what this owner has charged and dead-lettered so far, this call included; nothing when no message in a
     *         handler is in {@code scope}, or when the row no longer names this owner
     * @throws SQLException     when the charge, the lease lookup after a charge that left a row out, or a dead-letter move
     *                          fails; {@link #owesReleaseCharge} then answers
     *                          {@code true}
     * @throws RuntimeException what the {@code QueueObserver} threw when told of a dead letter (or a non-critical
     *                          {@link Error}), thrown once every move is made, so {@link #owesReleaseCharge} then answers
     *                          as after a call that returned, and {@link #releaseCharge()} gives the answer
     */
    ReleaseCharge chargeKeysInHandlerBeforeRelease(Connection connection, ReleaseChargeScope scope) throws SQLException {
        requireNonNull(scope, "No scope provided");
        return toReleaseCharge(releaseChargeLedger.charge(connection, () -> releaseChargeCandidates(scope)));
    }

    /**
     * Whether {@link #chargeKeysInHandlerBeforeRelease} has anything to write for {@code scope}: a message in a handler
     * that {@code scope} names and this owner has not charged yet, nor found gone from the table (deleted while in its
     * handler), or a charged one at the redelivery limit whose dead-letter move is still to be made. {@code false} after a call whose {@code QueueObserver} threw when told of a dead
     * letter, as it throws only after those writes, unless another message has come into {@code scope} since. Lets the
     * queue tell a failed charge, after which the unit is held and the release retried, from a failure that came after the
     * writes, after which it is released; and skip the connection that call needs when there is nothing to write.
     */
    boolean owesReleaseCharge(ReleaseChargeScope scope) {
        requireNonNull(scope, "No scope provided");
        return releaseChargeLedger.owes(releaseChargeCandidates(scope));
    }

    @Override
    public boolean owesDeadLetterMove() {
        return releaseChargeLedger.owesDeadLetterMove();
    }

    @Override
    public OwedMoveReleaseRefusals owedMoveReleaseRefusals() {
        return owedMoveReleaseRefusals;
    }

    /**
     * What {@link #chargeKeysInHandlerBeforeRelease} has charged and dead-lettered so far, without writing anything.
     */
    ReleaseCharge releaseCharge() {
        return toReleaseCharge(releaseChargeLedger.answer());
    }

    private static ReleaseCharge toReleaseCharge(ReleaseChargeLedger.Answer answer) {
        return answer.charged() == 0 ? ReleaseCharge.NONE
                                     : new ReleaseCharge(answer.charged(), answer.deadLettered().stream().map(ReleaseChargeLedger.ChargedRow::key).toList());
    }

    /**
     * The count after the charge of each row {@link #chargeKeysInHandlerBeforeRelease} charged, as the count this owner has
     * for it, so a failure its handler reports later - the release having failed, and this owner holding the unit on -
     * counts on from the charge: {@link #countAttempt} prefers this map to the row, and {@code scheduleRetryIfOwned} writes
     * the count it is given, which would undo the charge.
     */
    private void recordChargedAttempts(Map<Long, Integer> charged) {
        synchronized (stateLock) {
            attemptsBySeq.putAll(charged);
        }
    }

    /**
     * The message (by seq) of each key in a handler that {@code scope} names, with its key and {@code key_order}, in the
     * order the keys entered their handlers: the candidates of {@link #chargeKeysInHandlerBeforeRelease}. A message whose
     * handler has returned ({@link #returnedFromHandler}) is not one: its handler records its outcome.
     */
    private Map<Long, ReleaseChargeLedger.Candidate> releaseChargeCandidates(ReleaseChargeScope scope) {
        var now        = System.nanoTime();
        var bound      = settings.stuckHandlerTimeout().toNanos();
        var candidates = new LinkedHashMap<Long, ReleaseChargeLedger.Candidate>();
        synchronized (stateLock) {
            keysInFlight.forEach((key, inHandler) -> {
                if (!returnedFromHandler.contains(inHandler.seq())
                        && (scope == ReleaseChargeScope.EVERY_KEY_IN_HANDLER || now - inHandler.sinceNanos() >= bound)) {
                    candidates.put(inHandler.seq(), new ReleaseChargeLedger.Candidate(key, inHandler.keyOrder()));
                }
            });
        }
        return candidates;
    }

    /**
     * What {@link #chargeKeysInHandlerBeforeRelease} has done for this owner.
     *
     * @param charged          how many messages in a handler were charged an attempt: those past {@code stuckHandlerTimeout}
     *                         for a shed or a dead-pump hand-back, every one for a release on request
     * @param deadLetteredKeys the keys whose message the charge brought to the redelivery limit, and which were
     *                         dead-lettered
     */
    record ReleaseCharge(int charged, List<String> deadLetteredKeys) {
        static final ReleaseCharge NONE = new ReleaseCharge(0, List.of());

        /**
         * The dead-lettered keys as a log line names them: capped, as an abandoned shed's are.
         */
        String describeDeadLetteredKeys() {
            return OrderedShardOwner.describeKeys(deadLetteredKeys);
        }
    }

    /**
     * The keys of an owner in a handler, and how long the longest-running has been there. See {@link #keysInHandler()}.
     *
     * @param keys    the keys in a handler
     * @param longest how long the key handed to its handler first has been there
     */
    record KeysInHandler(List<String> keys, Duration longest) {
        /**
         * The keys as a log line names them: capped, as an abandoned shed's are.
         */
        String describeKeys() {
            return OrderedShardOwner.describeKeys(keys);
        }
    }

    /**
     * True once the shed is ready for the release: the shard has quiesced, or its shed was completed with a key still in a
     * handler longer than {@code stuckHandlerTimeout} ({@link #shedPastStuckHandler()}); either way its acknowledgements
     * are flushed.
     */
    public boolean shedComplete() {
        return shedComplete.get();
    }

    /**
     * The keys still in a handler when this owner's shed was completed without them, because the longest had been there
     * longer than {@code stuckHandlerTimeout}, and how long it had been there then: read with the batch of
     * acknowledgements the shed flushed, so exactly the keys whose acknowledgements it did not flush ({@link #advanceShed}).
     * Asked by the queue as it releases a unit whose shed is complete ({@link #shedComplete()}), to WARN about what the
     * release leaves running.
     *
     * @return empty for a shed that quiesced, and while no shed has completed
     */
    Optional<KeysInHandler> shedPastStuckHandler() {
        return Optional.ofNullable(shedPastStuckHandler);
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

    /**
     * See {@link LeasedOwner#deliveryPermitted()}. Set once, by the queue that built this owner.
     */
    void setDeliveryGate(java.util.function.BooleanSupplier deliveryGate) {
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
        log.debug("Ordered unit {}: owner stopping under fence {}; its lease row is still to be released", shard, fence);
        // As on the unordered lane: so the pump drops it on its next pass rather than at its backstop.
        wakeup.signal();
    }

    @Override
    public void onLeaseEnded(LeaseEnd reason) {
        var was = leaseRow.advanceTo(LeaseRow.RELEASED);
        if (was == LeaseRow.RELEASED) {
            return;
        }
        // As in ShardOwner: found gone after a release of its row failed with no error answer from the database
        // (LeaseRowState#mayHaveGoneThrough), the unit is read as given up by that release.
        var lost = reason == LeaseEnd.TAKEN && !leaseRow.releaseInDoubt();
        if (lost) {
            // Counted by the call that moved the row, as in ShardOwner
            metrics.leasesLost.increment();
        }
        // See ShardOwner: per unit and therefore DEBUG, because the caller reports the pass.
        log.debug("Ordered unit {}: lease under fence {} {}{}",
                  shard, fence, lost ? "taken" : reason == LeaseEnd.TAKEN ? "found given up by a release whose answer was not received" : "released",
                  was == LeaseRow.HELD ? ", owner stopping" : "");
        if (was == LeaseRow.HELD) {
            wakeup.signal();
        }
    }
}
