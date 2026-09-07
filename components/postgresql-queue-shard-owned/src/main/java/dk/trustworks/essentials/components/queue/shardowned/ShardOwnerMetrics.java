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

    /** The consumer's observer. Never null — an engine with nobody watching gets a no-op. */
    public QueueObserver observer() {
        return observer;
    }

    public final LongAdder     delivered            = new LongAdder();
    public final LongAdder     cursorReads          = new LongAdder();
    public final LongAdder     holesObserved        = new LongAdder();
    public final LongAdder     holesResolved        = new LongAdder();
    public final LongAdder     holesAbandoned       = new LongAdder();
    public final LongAdder     holeChaseQueries     = new LongAdder();
    public final LongAdder     holeResolutionNanos  = new LongAdder();
    public final LongAdder     headSweeps           = new LongAdder();
    public final LongAdder     sweepRecoveries      = new LongAdder();
    public final LongAdder     ackFlushes           = new LongAdder();
    public final LongAdder     handlerFailures      = new LongAdder();
    /**
     * Rows whose attempt count a new owner bumped on takeover. The fast path never writes an
     * attempt count, so this is the only thing standing between a JVM-killing handler and an
     * infinite redelivery loop — which makes it worth counting rather than only logging.
     */
    public final LongAdder     takeoverAttemptBumps = new LongAdder();
    /**
     * Messages whose handler returned while the thread was interrupted. Not acknowledged, because an
     * interrupted handler cannot be assumed to have finished — see {@code ShardOwner.deliver}.
     */
    public final LongAdder     abandonedOnInterrupt = new LongAdder();
    /**
     * Messages delivered for a key after a HIGHER key_order for that key had already been delivered.
     * The ordered lane advances a key through the values that are present rather than waiting for a
     * producer-assigned gap that may never be filled, so this is possible — and counted, so the
     * exposure is a measurement rather than a claim.
     */
    public final LongAdder     orderViolations      = new LongAdder();
    /** Ordered shards this instance was asked to give up because it held more than its fair share. */
    public final LongAdder     shedsStarted         = new LongAdder();
    /** Sheds that quiesced and released, so another instance could take the shard. */
    public final LongAdder     shedsCompleted       = new LongAdder();
    /**
     * Sheds abandoned because a key was still in a handler when the grace ran out. The shard stays
     * here: unbalanced beats reordered. A non-zero count means handlers run longer than
     * {@link ShardOwnerSettings#shedGrace}, not that anything is broken.
     */
    public final LongAdder     shedsAbandoned       = new LongAdder();
    public final LongAdder     retriesScheduled     = new LongAdder();
    public final LongAdder     retriesDispatched    = new LongAdder();
    public final LongAdder     deadLettered         = new LongAdder();
    /** Idle waits released by a notification rather than by the backstop timeout. */
    public final LongAdder     wakeupsHonoured      = new LongAdder();
    public final LongAdder     backstopPolls        = new LongAdder();
    /** Messages delivered without ever being read back, because the enqueuing JVM owned the shard. */
    public final LongAdder     localHandoffs        = new LongAdder();
    /** Cursor reads avoided because the owner had locally handed-off work to process instead. */
    public final LongAdder     readsSkippedByHandoff = new LongAdder();
    /** Acknowledgements the fence clause rejected because the lease had moved on. */
    public final LongAdder     fencedOutAcks         = new LongAdder();
    public final LongAdder     leaseRenewals         = new LongAdder();
    public final LongAdder     leasesLost            = new LongAdder();
    /** Connections lost underneath an owner and reconnected. Routine in production; not fatal. */
    public final LongAdder     connectionFailures    = new LongAdder();
    public final LongAdder     shardsAcquired        = new LongAdder();
    public final LongAdder     shardsReleased        = new LongAdder();
    /** Dispatch attempts skipped because that key already had a message in flight. */
    public final LongAdder     keyHeadOfLineBlocks  = new LongAdder();
    public final AtomicInteger maxPendingHoles      = new AtomicInteger();
    /** Peak number of distinct keys handled concurrently — the evidence for cross-key parallelism. */
    public final AtomicInteger maxConcurrentKeys    = new AtomicInteger();

    public Map<String, Object> snapshot() {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("delivered", delivered.sum());
        snapshot.put("cursorReads", cursorReads.sum());
        snapshot.put("holesObserved", holesObserved.sum());
        snapshot.put("holesResolved", holesResolved.sum());
        snapshot.put("holesAbandoned", holesAbandoned.sum());
        snapshot.put("holeChaseQueries", holeChaseQueries.sum());
        snapshot.put("headSweeps", headSweeps.sum());
        snapshot.put("sweepRecoveries", sweepRecoveries.sum());
        snapshot.put("ackFlushes", ackFlushes.sum());
        snapshot.put("handlerFailures", handlerFailures.sum());
        snapshot.put("takeoverAttemptBumps", takeoverAttemptBumps.sum());
        snapshot.put("abandonedOnInterrupt", abandonedOnInterrupt.sum());
        snapshot.put("orderViolations", orderViolations.sum());
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
