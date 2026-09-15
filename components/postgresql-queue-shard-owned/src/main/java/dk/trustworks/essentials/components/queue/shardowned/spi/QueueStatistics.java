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

package dk.trustworks.essentials.components.queue.shardowned.spi;

/**
 * What this queue's consumers in <b>this JVM</b> have done since they started.
 *
 * <h2>Read the scope before reading the numbers</h2>
 * These are in-memory counters owned by the consumers this process is running. They are <em>not</em>
 * cluster-wide and they are <em>not</em> persisted:
 * <ul>
 *   <li>An instance that consumes none of this queue's shards reports zeros. That is not a stalled
 *       queue, it is a queue served elsewhere — {@code QueueHealth.unownedShards} is the field that
 *       distinguishes those two, and it reads the database.</li>
 *   <li>They reset when the process restarts, so a low count may mean a recent deploy.</li>
 *   <li>Two instances report different numbers for the same queue, and both are right.</li>
 * </ul>
 * Mixing per-JVM counters with cluster-wide state without saying which is which is a trap the event
 * store's subscription statistics documented the hard way; this record exists rather than a raw map
 * so the scope can be stated once, here.
 *
 * @param delivered         handler invocations that completed
 * @param handlerFailures   handler invocations that threw
 * @param retriesScheduled  redeliveries the policy decided on
 * @param retriesDispatched redeliveries actually handed back to a handler
 * @param deadLettered      messages parked after exhausting the policy
 * @param orderViolations   ordered messages delivered out of their producer's {@code key_order}. Non-zero
 *                          means the producer numbered and committed in different orders, not that the
 *                          engine reordered them
 * @param sweepRecoveries   messages the backstop sweep found that the cursor had not reached. Not a
 *                          defect by itself — a queue filling faster than it drains legitimately
 *                          produces them — but a rising share means the fast path is not keeping up
 * @param shardsAcquired    units taken since start
 * @param shardsReleased    units handed back since start
 * @param leasesLost        units taken away by another instance, which is rebalancing when it happens
 *                          during one and fencing when it does not
 * @param watermarkCapped   times the ordered lane advanced its cursor on the wall-clock cap rather
 *                          than on proof. Each one may have skipped a message; a long write
 *                          transaction is the cause
 * @param keysBlockedByDeadLetter times a key was recorded as stopped behind a dead letter. A key never
 *                          advances past one, so non-zero means some key is waiting for a human to
 *                          resurrect or delete the message holding it. Counted per recording, not as a
 *                          current size — a takeover re-derives the blocks and legitimately counts
 *                          them again under the new owner
 * @param messagesPoisonedBehindDeadLetter messages dead-lettered without ever reaching a handler,
 *                          because their key was blocked. Read against {@code deadLettered}: a queue
 *                          dominated by these means ONE message is broken and the rest are waiting on
 *                          a decision about it, where the reverse means the handler is
 */
public record QueueStatistics(long delivered,
                              long handlerFailures,
                              long retriesScheduled,
                              long retriesDispatched,
                              long deadLettered,
                              long orderViolations,
                              long sweepRecoveries,
                              long shardsAcquired,
                              long shardsReleased,
                              long leasesLost,
                              long watermarkCapped,
                              long keysBlockedByDeadLetter,
                              long messagesPoisonedBehindDeadLetter) {

    public static final QueueStatistics NONE = new QueueStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Sums two lanes' counters, which is how a queue's figure is assembled from its per-lane consumers. */
    public QueueStatistics plus(QueueStatistics other) {
        return new QueueStatistics(delivered + other.delivered,
                                   handlerFailures + other.handlerFailures,
                                   retriesScheduled + other.retriesScheduled,
                                   retriesDispatched + other.retriesDispatched,
                                   deadLettered + other.deadLettered,
                                   orderViolations + other.orderViolations,
                                   sweepRecoveries + other.sweepRecoveries,
                                   shardsAcquired + other.shardsAcquired,
                                   shardsReleased + other.shardsReleased,
                                   leasesLost + other.leasesLost,
                                   watermarkCapped + other.watermarkCapped,
                                   keysBlockedByDeadLetter + other.keysBlockedByDeadLetter,
                                   messagesPoisonedBehindDeadLetter + other.messagesPoisonedBehindDeadLetter);
    }
}
