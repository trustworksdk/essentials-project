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

import java.time.Duration;

/**
 * Tuning for a {@link ShardOwner}. A record, so being wide is its job.
 *
 * @param chaseDelay     how long before re-querying a sequence value the cursor stepped over. Phase 0
 *                       measured hole resolution latency tracking this value almost exactly, which
 *                       means the cost of a hole is set here rather than by the database — holes
 *                       resolve as fast as the owner bothers to look
 * @param holeExpiry     how long a missing value is chased before being written off as an aborted
 *                       transaction. Must exceed the longest expected enqueue transaction, or live
 *                       messages get abandoned
 * @param sweepInterval  cadence of the head sweep, the backstop that makes correctness independent of
 *                       the in-memory hole set being perfect
 * @param ackFlushInterval upper bound on how long a handled message waits before its delete is issued
 * @param idleParkMicros <b>unused</b>. It was how long an owner slept before re-reading, back when the
 *                       ordered lane had no wake-up and polled as fast as the database could answer.
 *                       Both lanes park on a {@code ShardWakeup} now. Kept so the record's shape does
 *                       not change again for a removal; delete it at the next deliberate break.
 * @param maxSweepInterval ceiling the sweep backs off to while a shard stays empty.
 *                       <p>
 *                       A fixed sweep per shard is a fixed query rate per shard whether or not the
 *                       shard has seen a message this hour: 4 queries a second each, which is 18 000
 *                       a second across 300 queues of 8 shards doing nothing at all. The sweep is a
 *                       backstop for a <em>lost</em> notification, not the delivery path — a
 *                       notification wakes the shard immediately either way — so backing it off on a
 *                       quiet shard costs recovery time for a rare failure rather than latency.
 *                       Reset to {@code sweepInterval} the moment anything arrives.
 * @param handlerConcurrency <b>ceiling</b> on handler invocations in flight across the whole process,
 *                       every queue and both lanes.
 *                       <p>
 *                       Not the knob a caller reaches for &mdash; that is
 *                       {@code ConsumerOptions.parallelConsumers}, per consumer, as in the current
 *                       implementation's {@code ConsumeFromQueue}. This is the backstop over the sum
 *                       of them, sized against whatever the handlers themselves contend for, which is
 *                       usually a connection pool and is separate from the engine's own
 *                       {@code pumpThreads + 1}. A single process-wide budget cannot be the primary
 *                       control: it lets one busy queue starve every other, and gives nobody a way to
 *                       say that this queue deserves four handlers and that one thirty-two.
 * @param pumpThreads    how many threads — and therefore how many held connections — a queue uses to
 *                       talk to the database, regardless of how many shards it owns. Each pump holds
 *                       one connection and serves the shards assigned to it. This is the knob that
 *                       decouples database contact from shard count: a thread per shard per lane cost
 *                       9 held connections per queue at four shards, which does not survive 25 queues
 * @param shedGrace      how long an ordered owner asked to give up a shard waits for its in-flight
 *                       keys to finish before abandoning the attempt. It cannot simply release: a
 *                       key still in a handler here would be started again by the new owner, which
 *                       is reordering rather than the duplicate that at-least-once permits. A shed
 *                       that does not finish inside this window is given up and counted, so the
 *                       shard stays where it is — unbalanced but correctly ordered
 */
public record ShardOwnerSettings(int readBatchSize,
                                 int ackBatchSize,
                                 Duration ackFlushInterval,
                                 Duration chaseDelay,
                                 Duration holeExpiry,
                                 Duration sweepInterval,
                                 int maxHolesPerChase,
                                 long idleParkMicros,
                                 int keyConcurrency,
                                 Duration pollBackstop,
                                 Duration maxSweepInterval,
                                 int handlerConcurrency,
                                 int pumpThreads,
                                 Duration shedGrace) {

    public static ShardOwnerSettings defaults() {
        return new ShardOwnerSettings(500,
                                      200,
                                      Duration.ofMillis(1),
                                      Duration.ofMillis(2),
                                      Duration.ofSeconds(10),
                                      Duration.ofMillis(500),
                                      1_000,
                                      200L,
                                      8,
                                      Duration.ofMillis(500),
                                      Duration.ofSeconds(30),
                                      512,
                                      2,
                                      Duration.ofSeconds(5));
    }

    /**
     * How long an idle owner parks when no wake-up arrives.
     * <p>
     * This is the correctness backstop, not the latency mechanism — a notification normally releases
     * the owner far sooner. Making it long is what removes the empty polling transactions the cost
     * decomposition found: three transactions per message, almost all of them idle reads.
     */
    public long pollBackstopMillis() {
        return pollBackstop.toMillis();
    }

    public long chaseDelayNanos() {
        return chaseDelay.toNanos();
    }

    public long holeExpiryNanos() {
        return holeExpiry.toNanos();
    }

    public long sweepIntervalNanos() {
        return sweepInterval.toNanos();
    }

    public long ackFlushIntervalNanos() {
        return ackFlushInterval.toNanos();
    }

    public long maxSweepIntervalNanos() {
        return maxSweepInterval.toNanos();
    }

    public long shedGraceNanos() {
        return shedGrace.toNanos();
    }
}
