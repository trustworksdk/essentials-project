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

import dk.trustworks.essentials.components.queue.shardowned.spi.QueueObserver;

import java.util.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Counters shared across every {@link ShardOwner} in an engine.
 * <p>
 * These are the numbers that say whether the design's mechanisms are behaving as designed, as
 * distinct from whether the engine is fast.
 * <p>
 * It also carries the consumer-supplied {@link QueueObserver}, because the two are the same wiring
 * reaching two audiences: these counters exist to debug the engine, the observer exists to feed the
 * consumer's metrics and tracing. Threading one object rather than two keeps the owners' already-wide
 * constructors from growing again. {@code sweepRecoveries} is the one to watch: it counts
 * messages the cursor and the hole chase both missed and the backstop caught. A healthy engine
 * shows a small number; a large one means the fast path is losing track of work and the design's
 * claim to need no claim write is in trouble.
 */
public final class ShardOwnerMetrics {
    private final QueueObserver observer;

    public ShardOwnerMetrics() {
        this(new QueueObserver() {
        });
    }

    public ShardOwnerMetrics(QueueObserver observer) {
        this.observer = requireNonNull(observer, "No observer provided");
    }

    /**
     * The consumer's observer. Never null — an engine with nobody watching gets a no-op.
     */
    public QueueObserver observer() {
        return observer;
    }

    public final LongAdder     delivered                        = new LongAdder();
    public final LongAdder     cursorReads                      = new LongAdder();
    /**
     * Heartbeats that found the membership row last refreshed by another process: two live processes share this
     * instance id. See {@code ShardOwnedStorage.heartbeat}
     */
    public final LongAdder     sharedInstanceIdHeartbeats       = new LongAdder();
    /**
     * Rows the unordered lane's cursor reads returned. Divided by {@code delivered} this is the read amplification:
     * rows carried over the wire, payloads included, per message dispatched
     */
    public final LongAdder     cursorRowsRead                   = new LongAdder();
    public final LongAdder     holesObserved                    = new LongAdder();
    public final LongAdder     holesResolved                    = new LongAdder();
    public final LongAdder     holesAbandoned                   = new LongAdder();
    public final LongAdder     holeChaseQueries                 = new LongAdder();
    public final LongAdder     holeResolutionNanos              = new LongAdder();
    public final LongAdder     headSweeps                       = new LongAdder();
    public final LongAdder     sweepRecoveries                  = new LongAdder();
    /**
     * Ordered lane only. The hole counters above describe the unordered lane's mechanism; the ordered
     * lane replaced it with a safe watermark and these describe that one.
     * <p>
     * {@code horizonProbes} counts the extra round trip a poll pays to ask which write transactions
     * are running. {@code watermarkAdvances} counts how often that answer let the cursor move.
     * {@code watermarkCapped} counts advances forced by the wall-clock cap instead — an advance the
     * horizon had not authorised, which is the ordered lane's remaining exposure to a late commit and
     * therefore the number to watch. A non-zero value means some transaction outlived
     * {@code watermarkCap}, exactly as an abandoned hole outlived {@code holeExpiry}.
     */
    /**
     * Statements actually issued for the ordered lane's reads, as against {@code cursorReads}, which
     * counts how many owners were served. An owner that reads for itself issues three — cursor read,
     * head sweep, next-visible — so before batching the two moved together. It is now the measure of
     * whether the pump's batching is working, and the only honest way to state the idle cost of a
     * given shard count.
     */
    public final LongAdder     orderedReadStatements            = new LongAdder();
    public final LongAdder     horizonProbes                    = new LongAdder();
    public final LongAdder     watermarkAdvances                = new LongAdder();
    public final LongAdder     watermarkCapped                  = new LongAdder();
    /**
     * Sequence values — NOT rows — between the safe watermark and the newest value this owner has
     * seen. An upper bound on the re-read window, and a loose one: the ordered lane draws from one
     * sequence per queue, so an owner's span covers values that belong to other shards entirely. A
     * 500-message batch over four shards shows a span of 500 while each owner re-reads about 125
     * rows. Use {@code cursorReadsPerMessage} to size the actual cost; use this to see whether the
     * watermark is keeping up at all.
     */
    public final AtomicInteger maxWatermarkLagSeq               = new AtomicInteger();
    public final LongAdder     ackFlushes                       = new LongAdder();
    public final LongAdder     handlerFailures                  = new LongAdder();
    /**
     * Rows whose attempt count a new owner bumped on takeover. The fast path never writes an
     * attempt count, so this is the only thing standing between a JVM-killing handler and an
     * infinite redelivery loop — which makes it worth counting rather than only logging.
     */
    public final LongAdder     takeoverAttemptBumps             = new LongAdder();
    /**
     * Takeovers ({@code onTakeover}) that failed with the connection still healthy: a statement the
     * database refused, or an unchecked exception. Each is retried {@code ShardPump.TAKEOVER_RETRY_NANOS}
     * (200 ms) later, with no limit, so a takeover that keeps failing raises this by about five a second. See
     * {@link #shardsAwaitingTakeover} for how many shards are stuck now.
     */
    public final LongAdder     takeoverFailures                 = new LongAdder();
    /**
     * Shards this instance holds a lease on but does not serve, because their takeover has failed and
     * has not yet completed. Alert on it being above zero, next to {@code QueueHealth.unownedShards()}.
     * <p>
     * {@code unownedShards} cannot see these shards: it reads the lease table, and a shard here is
     * leased. The heartbeat keeps renewing the lease, so no other instance takes the shard either, and
     * it delivers nothing until the cause clears. A failure caused by data (a trigger or constraint
     * refusing the takeover's dead-letter move) does not clear on its own. The ERROR log, at most one a
     * minute per queue, names one shard and the cause and counts the others; every shard is logged at
     * DEBUG, and this count is exact. Rises on a shard's first failed takeover and falls when the takeover
     * completes, or when the lease ends once the pump next reaches the database. A lease that ends while
     * the pump is reconnecting stays counted here until then. Meanwhile {@code unownedShards} shows the
     * shard only if the lease was released and nobody has taken it yet; if another instance took it over,
     * the shard counts as owned there, and this count is one too high until the pump reconnects. It falls
     * when the pump stops with its runtime, but not as soon as the pump thread dies (on a critical error) while
     * the runtime runs: the death is logged at ERROR, every shard on the pump is counted in
     * {@link #shardsOnDeadPumps}, and its queue's heartbeat hands the lease back. A dead pump's shard stops
     * counting in both when its lease is handed back, or when that runtime stops.
     * <p>
     * Readable through {@link ShardOwnedQueue#metrics()}. A {@code PostgresqlMessageQueue} keeps its
     * metrics private; for a queue used through {@code MessageQueue} the same transitions reach
     * {@link QueueObserver#takeoverStalled}, which {@code MicrometerQueueObserver} publishes as
     * {@code essentials.queue.shards.awaiting.takeover}.
     */
    public final AtomicInteger shardsAwaitingTakeover           = new AtomicInteger();
    /**
     * Delivery passes ({@code pumpOnce}) for a shard that failed with the connection still healthy: a statement the
     * database refused, or an unchecked exception or non-critical {@code Error} from the engine. Not a handler failing, which is
     * {@link #handlerFailures}. The checks that decide whether the shard is pumped at all ({@code leaseHeld},
     * {@code deliveryPermitted}, {@code needsAttention}) count here too when they throw, paced the same way. The shard
     * stays leased and its pass is tried again 200 ms later at the earliest
     * ({@code ShardPump.PUMP_RETRY_NANOS}), with no limit, while the pump's other shards keep their own rate. So a shard
     * whose reads or acknowledgements are permanently refused delivers nothing while this rises by up to about five a
     * second per failing shard, whatever the pump's pass rate - and,
     * like a stalled takeover, {@code QueueHealth.unownedShards()} cannot see it because the shard is leased. Alert on
     * its rate. The ERROR log, at most one a minute per queue, names one shard and the cause; every failure is logged at
     * DEBUG, and a shard whose pass succeeds again after failing gets an INFO. See {@code PumpFailures}.
     * <p>
     * Readable only through {@link ShardOwnedQueue#metrics()}. A {@code PostgresqlMessageQueue} keeps its metrics private
     * and no {@code QueueObserver} callback reports a failing pass (unlike {@link #shardsAwaitingTakeover}, which reaches
     * {@code QueueObserver.takeoverStalled}), so for a queue used through {@code MessageQueue} (the Spring starter, the
     * {@code DurableQueues} adapter) the {@code ShardPump} ERROR log is the only signal: at most one a minute per queue,
     * naming one shard.
     */
    public final LongAdder     pumpFailures                     = new LongAdder();
    /**
     * Ordered-lane batched reads (one statement per queue per pass, for two or more attentive shards) that failed. Each
     * shard then reads for itself in its own pass, so nothing stops delivering because of this alone, but the read is tried
     * again on the next pass: a read the database keeps refusing (a revoked grant) rises with every pass, and the shards'
     * own reads usually fail too, under {@link #pumpFailures}. Logged at WARN with the stack trace at most once a minute per
     * subscription (these metrics: one per queue unless a queue has several), naming the queue and the shards read for,
     * every failure at DEBUG, and an INFO when the subscription's batched read succeeds again. See {@code PumpFailures}.
     * <p>
     * Readable only through {@link ShardOwnedQueue#metrics()}. A {@code PostgresqlMessageQueue} keeps its metrics private
     * and no {@code QueueObserver} callback reports this, so for a queue used through {@code MessageQueue} (the Spring
     * starter, the {@code DurableQueues} adapter) the {@code ShardPump} WARN log is the only signal: at most one a minute
     * per subscription.
     */
    public final LongAdder     batchedReadFailures              = new LongAdder();
    /**
     * Pump passes that ended because something outside the pump's per-shard guards threw: an unchecked exception or a
     * non-critical {@code Error} from the connection pool, from the pump's own bookkeeping, or from an owner's
     * {@code parkDeadlineMillis}. One owner's {@code pumpOnce}, {@code onTakeover} or selection checks
     * ({@code leaseHeld}, {@code deliveryPermitted}, {@code needsAttention}) throwing is not counted here: that is the
     * owner's alone ({@link #pumpFailures}, {@link #takeoverFailures}). The pump treats it like a lost connection: it
     * reconnects 200 ms later, runs every shard's takeover again (safe, it runs on every reconnect) and goes on serving.
     * Counted on the metrics of every queue the pump serves, since a pass is not one queue's, and on the runtime's own. A
     * pump that is stopping counts nothing here: it logs the throw at WARN and exits.
     * <p>
     * What a steadily rising count costs depends on where it is thrown. A pool that keeps failing ends every pass before
     * any shard on the pump delivers: while it persists, the pump delivers nothing, for every shard it serves. An owner's
     * {@code parkDeadlineMillis} is asked only on a pass that delivered nothing, after every attentive shard was pumped:
     * while it keeps throwing, every idle pass ends in a reconnect that reruns every shard's takeover, about five times a
     * second, and a message arriving meanwhile waits up to 200 ms for the reconnect instead of being woken for. The ERROR
     * log is at most once a minute per pump, every failure at DEBUG, an INFO when a pass completes again. See
     * {@code ShardPump.serve}.
     * <p>
     * Readable only through {@link ShardOwnedQueue#metrics()}. A {@code PostgresqlMessageQueue} keeps its metrics private
     * and no {@code QueueObserver} callback reports this, so for a queue used through {@code MessageQueue} (the Spring
     * starter, the {@code DurableQueues} adapter) the ERROR log is the only signal.
     */
    public final LongAdder     pumpLoopFailures                 = new LongAdder();
    /**
     * Shards of this queue that sit on a pump thread that has died and whose lease this instance has not handed back yet:
     * counted from the death (or from being handed to the dead pump after it, when no live one was left or the pump died
     * between placing and registering the shard) until the queue's heartbeat releases the lease, or the queue or the
     * runtime stops. A pump dies only on a critical error ({@code VirtualMachineError}, {@code LinkageError}): everything
     * else it reconnects from ({@link #pumpLoopFailures}). Nothing restarts it, so it ends the owners on it, and the
     * heartbeat hands their leases back to the cluster - an unordered shard on the next tick, an ordered unit once no key
     * is in one of its handlers, which a handler that never returns can hold off indefinitely (once that wait outlasts
     * {@code shedGrace}, a WARN names the unit and its keys still in a handler, once per unit). Another instance, or a live
     * pump here, then takes the shard over under a new fence; what was handled and not acknowledged is redelivered. So
     * this is normally above zero for one heartbeat after a death; alert on it staying there, next to
     * {@link #shardsAwaitingTakeover}. {@code QueueHealth.unownedShards()} cannot see these shards while they are counted,
     * because they are still leased. Shards acquired after the death go to the runtime's live pumps and are not counted;
     * with no live pump left this instance acquires nothing, so its shards stay unowned once handed back, where
     * {@code unownedShards} does see them.
     * <p>
     * Readable only through {@link ShardOwnedQueue#metrics()}. A {@code PostgresqlMessageQueue} keeps its metrics private
     * and no {@code QueueObserver} callback reports a dead pump (unlike {@link #shardsAwaitingTakeover}, which reaches
     * {@code QueueObserver.takeoverStalled}), so for a queue used through {@code MessageQueue} (the Spring starter, the
     * {@code DurableQueues} adapter) the pump's ERROR log is the only signal of the death; the hand-back that follows
     * reaches the observer as ownership changes ({@code QueueObserver.shardOwnershipChanged}). Whether to add one is open:
     * {@code docs/durable-queue-shard-owned.md} §18.3.
     */
    public final AtomicInteger shardsOnDeadPumps                = new AtomicInteger();
    /**
     * Messages whose handler returned while the thread was interrupted. Not acknowledged, because an
     * interrupted handler cannot be assumed to have finished — see {@code ShardOwner.deliver}.
     */
    public final LongAdder     abandonedOnInterrupt             = new LongAdder();
    /**
     * Messages delivered for a key after a HIGHER key_order for that key had already been delivered.
     * The ordered lane advances a key through the values that are present rather than waiting for a
     * producer-assigned gap that may never be filled, so this is possible — and counted, so the
     * exposure is a measurement rather than a claim.
     */
    public final LongAdder     orderViolations                  = new LongAdder();
    /**
     * Times a key was recorded as blocked by a dead letter. A key never advances past one, so a
     * non-zero value means some key stopped and is waiting for the dead letter to be resurrected or
     * deleted. Counted per recording rather than held as a current size: a takeover re-derives the
     * blocks from the dead-letter table, so the same key legitimately registers again under its new
     * owner.
     */
    public final LongAdder     keysBlockedByDeadLetter          = new LongAdder();
    /**
     * Messages moved to the dead-letter table without ever being handed to a handler, because their
     * key was blocked. These are the {@link dk.trustworks.essentials.components.queue.shardowned.spi.DeadLetter#neverDelivered()}
     * rows, and their count against {@code deadLettered} is what separates one broken message from a
     * broken handler.
     */
    public final LongAdder     messagesPoisonedBehindDeadLetter = new LongAdder();
    /**
     * Reads of the blocked-key state. Issued on takeover, and per sweep only while a block stands.
     */
    public final LongAdder     deadLetterBlockReads             = new LongAdder();
    /**
     * Ordered shards this instance was asked to give up because it held more than its fair share.
     */
    public final LongAdder     shedsStarted                     = new LongAdder();
    /**
     * Sheds that quiesced and released, so another instance could take the shard.
     */
    public final LongAdder     shedsCompleted                   = new LongAdder();
    /**
     * Sheds abandoned because a key was still in a handler when the grace ran out. The shard stays
     * here: unbalanced beats reordered. A non-zero count means handlers run longer than
     * {@link ShardOwnerSettings#shedGrace}, not that anything is broken.
     */
    public final LongAdder     shedsAbandoned                   = new LongAdder();
    public final LongAdder     retriesScheduled                 = new LongAdder();
    public final LongAdder     retriesDispatched                = new LongAdder();
    public final LongAdder     deadLettered                     = new LongAdder();
    /**
     * Idle waits released by a notification rather than by the backstop timeout.
     */
    public final LongAdder     wakeupsHonoured                  = new LongAdder();
    public final LongAdder     backstopPolls                    = new LongAdder();
    /**
     * Messages delivered without ever being read back, because the enqueuing JVM owned the shard.
     */
    public final LongAdder     localHandoffs                    = new LongAdder();
    /**
     * Cursor reads avoided because the owner had locally handed-off work to process instead.
     */
    public final LongAdder     readsSkippedByHandoff            = new LongAdder();
    /**
     * Acknowledgements the fence clause rejected because the lease had moved on.
     */
    public final LongAdder     fencedOutAcks                    = new LongAdder();
    public final LongAdder     leaseRenewals                    = new LongAdder();
    public final LongAdder     leasesLost                       = new LongAdder();
    /**
     * Connections lost underneath an owner and reconnected. Routine in production; not fatal.
     */
    public final LongAdder     connectionFailures               = new LongAdder();
    /**
     * Units taken, <b>including the first acquisition at start-up</b>. It counted only rebalance
     * acquisitions until the admin API began publishing it, so a single instance holding every unit
     * of a queue reported zero — which reads as "this instance has taken nothing" rather than "this
     * instance never had to rebalance".
     */
    public final LongAdder     shardsAcquired                   = new LongAdder();
    public final LongAdder     shardsReleased                   = new LongAdder();
    /**
     * Dispatch attempts skipped because that key already had a message in flight.
     */
    public final LongAdder     keyHeadOfLineBlocks              = new LongAdder();
    public final AtomicInteger maxPendingHoles                  = new AtomicInteger();
    /**
     * Peak number of distinct keys handled concurrently — the evidence for cross-key parallelism.
     */
    public final AtomicInteger maxConcurrentKeys                = new AtomicInteger();
    /**
     * Live instances beyond the lane's routing space, or zero — the consumers that can hold nothing
     * because there are no units left to give them.
     * <p>
     * Not a fault. The space caps concurrency and exceeding it degrades rather than fails: surplus
     * instances hold nothing, nothing is lost or reordered, and it recovers on its own when the
     * instance count drops. It is recorded because it is the one condition under which a queue's
     * ordered routing space is genuinely too small for its deployment, and that space is fixed for
     * the life of the queue — so this is the number that decides whether growing it would have been
     * worth building. Without it the question can only be guessed at.
     */
    public final AtomicInteger surplusInstances                 = new AtomicInteger();
    /**
     * Times this instance stopped dispatching because it had not been able to confirm its own
     * liveness within the lease — see {@code ShardOwnedQueue.deliveryPermitted}.
     * <p>
     * Non-zero means the database was unreachable or too slow for longer than the window the rest of
     * the cluster waits before taking this instance's units. Nothing was lost; what it says is that
     * this instance spent time holding units it could no longer vouch for.
     */
    public final LongAdder     deliveryPauses                   = new LongAdder();

    /**
     * Handler invocations that ran longer than {@code leaseTtl}.
     * <p>
     * Non-zero means the lease is shorter than the work: should this instance lose its liveness while
     * such a handler runs, a successor starts the same work alongside it - for the ordered lane, a key
     * in two handlers at once. See {@code LeaseOverrun}.
     */
    public final LongAdder     handlersOutlastingLease          = new LongAdder();

    /** Rate limit for the WARN that accompanies {@link #handlersOutlastingLease}. */
    final java.util.concurrent.atomic.AtomicLong nextLeaseOverrunWarningNanos =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    /** Rate limit for the WARN that reports a key blocked by a dead letter. See {@code BlockedKeyReport}. */
    final java.util.concurrent.atomic.AtomicLong nextBlockedKeyWarningNanos =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    /** Keys blocked since that WARN was last logged, which the next one reports as a count. */
    final LongAdder blockedKeysSinceLastWarning = new LongAdder();

    /**
     * Rate limit for the ERROR that reports a stalled takeover, per queue rather than per shard: what makes one shard's
     * takeover fail usually makes them all fail. {@link ErrorWindow#NEVER_TAKEN} until the first, and again
     * once no shard of the queue is awaiting its takeover. See {@code TakeoverStalls}.
     */
    final java.util.concurrent.atomic.AtomicLong nextTakeoverStallErrorNanos =
            new java.util.concurrent.atomic.AtomicLong(ErrorWindow.NEVER_TAKEN);

    /**
     * Rate limit for the ERROR that reports a failing delivery pass, per queue for the same reason as
     * {@link #nextTakeoverStallErrorNanos}. {@link ErrorWindow#NEVER_TAKEN} until the first. See
     * {@code PumpFailures}.
     */
    final java.util.concurrent.atomic.AtomicLong nextPumpFailureErrorNanos =
            new java.util.concurrent.atomic.AtomicLong(ErrorWindow.NEVER_TAKEN);

    /**
     * Rate limit for the WARN that reports a failing ordered-lane batched read ({@link #batchedReadFailures}), per
     * subscription, since these metrics are one subscription's (one per queue unless a queue has several): every pump
     * serving the subscription issues its own batched read, and they share this window. {@link ErrorWindow#NEVER_TAKEN} until the first. See
     * {@code PumpFailures}.
     */
    final java.util.concurrent.atomic.AtomicLong nextBatchedReadWarningNanos =
            new java.util.concurrent.atomic.AtomicLong(ErrorWindow.NEVER_TAKEN);

    /**
     * The operator-facing subset, as a stable shape.
     * <p>
     * {@link #snapshot()} stays the full map for tests and debugging; this is what the admin API
     * publishes, so the two can move independently — an internal counter renamed must not be a
     * breaking change to a published contract.
     */
    public dk.trustworks.essentials.components.queue.shardowned.spi.QueueStatistics statistics() {
        return new dk.trustworks.essentials.components.queue.shardowned.spi.QueueStatistics(
                delivered.sum(),
                handlerFailures.sum(),
                retriesScheduled.sum(),
                retriesDispatched.sum(),
                deadLettered.sum(),
                orderViolations.sum(),
                sweepRecoveries.sum(),
                shardsAcquired.sum(),
                shardsReleased.sum(),
                leasesLost.sum(),
                watermarkCapped.sum(),
                keysBlockedByDeadLetter.sum(),
                messagesPoisonedBehindDeadLetter.sum());
    }

    public Map<String, Object> snapshot() {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("delivered", delivered.sum());
        snapshot.put("cursorReads", cursorReads.sum());
        snapshot.put("sharedInstanceIdHeartbeats", sharedInstanceIdHeartbeats.sum());
        snapshot.put("cursorRowsRead", cursorRowsRead.sum());
        snapshot.put("holesObserved", holesObserved.sum());
        snapshot.put("holesResolved", holesResolved.sum());
        snapshot.put("holesAbandoned", holesAbandoned.sum());
        snapshot.put("holeChaseQueries", holeChaseQueries.sum());
        snapshot.put("headSweeps", headSweeps.sum());
        snapshot.put("sweepRecoveries", sweepRecoveries.sum());
        snapshot.put("ackFlushes", ackFlushes.sum());
        snapshot.put("handlerFailures", handlerFailures.sum());
        snapshot.put("takeoverAttemptBumps", takeoverAttemptBumps.sum());
        snapshot.put("takeoverFailures", takeoverFailures.sum());
        snapshot.put("shardsAwaitingTakeover", shardsAwaitingTakeover.get());
        snapshot.put("pumpFailures", pumpFailures.sum());
        snapshot.put("batchedReadFailures", batchedReadFailures.sum());
        snapshot.put("pumpLoopFailures", pumpLoopFailures.sum());
        snapshot.put("shardsOnDeadPumps", shardsOnDeadPumps.get());
        snapshot.put("abandonedOnInterrupt", abandonedOnInterrupt.sum());
        snapshot.put("orderViolations", orderViolations.sum());
        snapshot.put("keysBlockedByDeadLetter", keysBlockedByDeadLetter.sum());
        snapshot.put("handlersOutlastingLease", handlersOutlastingLease.sum());
        snapshot.put("messagesPoisonedBehindDeadLetter", messagesPoisonedBehindDeadLetter.sum());
        snapshot.put("deadLetterBlockReads", deadLetterBlockReads.sum());
        snapshot.put("retriesScheduled", retriesScheduled.sum());
        snapshot.put("retriesDispatched", retriesDispatched.sum());
        snapshot.put("deadLettered", deadLettered.sum());
        snapshot.put("wakeupsHonoured", wakeupsHonoured.sum());
        snapshot.put("backstopPolls", backstopPolls.sum());
        snapshot.put("localHandoffs", localHandoffs.sum());
        snapshot.put("readsSkippedByHandoff", readsSkippedByHandoff.sum());
        snapshot.put("fencedOutAcks", fencedOutAcks.sum());
        snapshot.put("leaseRenewals", leaseRenewals.sum());
        snapshot.put("leasesLost", leasesLost.sum());
        snapshot.put("connectionFailures", connectionFailures.sum());
        snapshot.put("shardsAcquired", shardsAcquired.sum());
        snapshot.put("shardsReleased", shardsReleased.sum());
        snapshot.put("keyHeadOfLineBlocks", keyHeadOfLineBlocks.sum());
        snapshot.put("maxPendingHoles", maxPendingHoles.get());
        snapshot.put("maxConcurrentKeys", maxConcurrentKeys.get());
        snapshot.put("surplusInstances", surplusInstances.get());
        snapshot.put("deliveryPauses", deliveryPauses.sum());
        snapshot.put("orderedReadStatements", orderedReadStatements.sum());
        snapshot.put("horizonProbes", horizonProbes.sum());
        snapshot.put("watermarkAdvances", watermarkAdvances.sum());
        snapshot.put("watermarkCapped", watermarkCapped.sum());
        snapshot.put("maxWatermarkLagSeq", maxWatermarkLagSeq.get());
        var resolved = holesResolved.sum();
        snapshot.put("meanHoleResolutionMicros", resolved == 0 ? 0L : holeResolutionNanos.sum() / resolved / 1_000L);
        // Cursor reads per delivered message: the design's claim is that read cost amortises across
        // a batch, so this should be well under 1.
        var deliveredCount = delivered.sum();
        snapshot.put("cursorReadsPerMessage", deliveredCount == 0 ? 0.0d : (double) cursorReads.sum() / deliveredCount);
        snapshot.put("ackFlushesPerMessage", deliveredCount == 0 ? 0.0d : (double) ackFlushes.sum() / deliveredCount);
        return snapshot;
    }
}
