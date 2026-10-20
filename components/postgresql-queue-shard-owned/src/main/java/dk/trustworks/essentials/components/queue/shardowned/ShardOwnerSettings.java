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

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Tuning for a {@link ShardOwner}. A record, so being wide is its job.
 *
 * @param chaseDelay       how long before re-querying a sequence value the cursor stepped over. Phase 0
 *                         measured hole resolution latency tracking this value almost exactly, which
 *                         means the cost of a hole is set here rather than by the database — holes
 *                         resolve as fast as the owner bothers to look
 * @param holeExpiry       how long a missing value is chased before being written off as an aborted
 *                         transaction. Must exceed the longest expected enqueue transaction, or live
 *                         messages get abandoned
 * @param sweepInterval    cadence of the head sweep, the backstop that makes correctness independent of
 *                         the in-memory hole set being perfect
 * @param ackFlushInterval upper bound on how long a handled message waits before its delete is issued
 * @param maxSweepInterval ceiling the sweep backs off to while a shard stays empty.
 *                         <p>
 *                         A fixed sweep per shard is a fixed query rate per shard whether or not the
 *                         shard has seen a message this hour: 4 queries a second each, which is 18 000
 *                         a second across 300 queues of 8 shards doing nothing at all. The sweep is a
 *                         backstop for a <em>lost</em> notification, not the delivery path — a
 *                         notification wakes the shard immediately either way — so backing it off on a
 *                         quiet shard costs recovery time for a rare failure rather than latency.
 *                         Reset to {@code sweepInterval} the moment anything arrives.
 * @param pumpThreads      how many threads — and therefore how many held connections — a queue uses to
 *                         talk to the database, regardless of how many shards it owns. Each pump holds
 *                         one connection and serves the shards assigned to it. This is the knob that
 *                         decouples database contact from shard count: a thread per shard per lane cost
 *                         9 held connections per queue at four shards, which does not survive 25 queues
 * @param leaseTtl         how long a shard lease is held before it must be renewed, and therefore how
 *                         long a shard stays unserved after its owner dies without releasing it.
 *                         <p>
 *                         Its own setting, because it used to be derived as {@code holeExpiry x 3} and
 *                         the two answer unrelated questions. {@code holeExpiry} is bounded below by
 *                         the longest enqueue transaction; the lease TTL is bounded below by the
 *                         longest tolerable stop-the-world pause and above by how long a failover may
 *                         take. Deriving one from the other means raising {@code holeExpiry} for a long
 *                         outbox transaction silently triples failover time.
 * @param shedGrace        how long an ordered owner asked to give up a shard waits for its in-flight
 *                         keys to finish before abandoning the attempt. It cannot simply release: a
 *                         key still in a handler here would be started again by the new owner, which
 *                         is reordering rather than the duplicate that at-least-once permits. A shed
 *                         that does not finish inside this window is given up and counted, so the
 *                         shard stays where it is — unbalanced but correctly ordered
 * @param watermarkCap     ordered lane only: how long the safe watermark will wait for a write
 *                         transaction to end before advancing past it anyway.
 *                         <p>
 *                         <b>Not {@code holeExpiry}, and the difference is the point.</b> Both are a
 *                         "give up waiting" bound, but they are bounded by different things.
 *                         {@code holeExpiry} had to stay smallish because every unresolved value costs
 *                         a map entry and a chase query every {@code chaseDelay}; the watermark costs a
 *                         single deque entry and no query at all, so nothing pushes this down. It should
 *                         therefore be generous — the horizon normally answers in about one write
 *                         transaction, and this only exists so an unrelated stuck writer cannot stall
 *                         the lane indefinitely. Setting it as low as {@code holeExpiry} throws away the
 *                         exactness the watermark is for and reproduces the mechanism it replaced.
 *                         Advances that need it are counted as {@code watermarkCapped}
 * @param stuckHandlerTimeout how long a message may be in its handler before this queue treats the handler as stuck
 *                         and stops waiting for it. Default 15 minutes ({@link #DEFAULT_STUCK_HANDLER_TIMEOUT}); set it
 *                         above the longest time a handler of this queue really takes, as {@code messageHandlingTimeout}
 *                         is set for the classic {@code DurableQueues}. Must be positive. There is no off switch: a
 *                         value longer than the process will run gives the behaviour from before this setting existed.
 *                         <p>
 *                         Applied to the three waits for an ordered unit's handler, each of which, unbounded, lasted
 *                         as long as the handler ran - until the process restarted for one that never returned:
 *                         <ul>
 *                           <li>An ordered unit a stop kept because a key was still in its handler {@code shedGrace}
 *                           after the stop began. A later run of the same queue, and a {@code PostgresqlMessageQueue}
 *                           {@code consume()} wanting that subscription's instance id, wait for that handler to return,
 *                           leaving the unit unserved and the instance id out of use. Once the key has been in its
 *                           handler longer than this, they stop waiting: the next run takes the unit over under a new
 *                           fence, as from an owner that did not let go of it - the handler's acknowledgement refused,
 *                           its message delivered again with an attempt charged. Reported at WARN once its handler has
 *                           run longer than {@code leaseTtl}, at most once a minute per unit, and once more when the
 *                           queue stops waiting.</li>
 *                           <li>A fair-share shed of an ordered unit, which waits for the keys in its handlers before it
 *                           releases the unit, and otherwise keeps it and retries on the next heartbeat - leaving the
 *                           cluster unbalanced and the unit's other keys unserved. Once {@code shedGrace} has run out
 *                           with a key in its handler longer than this, the shed releases the unit with every key still
 *                           in a handler - that one and any other still running.</li>
 *                           <li>The hand-back of an ordered unit whose pump died, which waits for the keys in its
 *                           handlers - the unit leased to this instance and unserved meanwhile. Once a key has been in
 *                           its handler longer than this, the unit is handed back with every key still in a handler -
 *                           that one and any other still running.</li>
 *                         </ul>
 *                         Each time the queue stops waiting it logs one WARN naming the queue, the unit, the keys and
 *                         how long the longest has been in its handler. The unit's next owner delivers the message of
 *                         each key still in a handler again, possibly beside its handler, so each of those keys may run
 *                         in two handlers at once - which is the at-least-once the bound trades the stuck unit for. A kept unit with a key in its
 *                         handler, and a unit the queue stopped waiting for in any of the three while that handler runs
 *                         on, is counted in {@code ShardOwnerMetrics.keptUnitsInHandler()}
 */
public record ShardOwnerSettings(int readBatchSize,
                                 int ackBatchSize,
                                 Duration ackFlushInterval,
                                 Duration chaseDelay,
                                 Duration holeExpiry,
                                 Duration sweepInterval,
                                 int maxHolesPerChase,
                                 int keyConcurrency,
                                 Duration pollBackstop,
                                 Duration maxSweepInterval,
                                 int pumpThreads,
                                 Duration shedGrace,
                                 Duration leaseTtl,
                                 Duration watermarkCap,
                                 Duration stuckHandlerTimeout) {

    /**
     * The {@code stuckHandlerTimeout} a queue gets unless it is given another: 15 minutes.
     */
    public static final Duration DEFAULT_STUCK_HANDLER_TIMEOUT = Duration.ofMinutes(15);

    public ShardOwnerSettings {
        requireNonNull(stuckHandlerTimeout, "No stuckHandlerTimeout provided");
        requireTrue(stuckHandlerTimeout.isPositive(), "stuckHandlerTimeout must be positive");
    }

    /**
     * The settings before {@code stuckHandlerTimeout} existed, with that one at its default
     * ({@link #DEFAULT_STUCK_HANDLER_TIMEOUT}).
     *
     * @deprecated use {@link #builder()}, which starts from {@link #defaults()}, or the canonical constructor, which takes
     * {@code stuckHandlerTimeout} as well
     */
    @Deprecated(forRemoval = true, since = "0.60.1")
    public ShardOwnerSettings(int readBatchSize,
                              int ackBatchSize,
                              Duration ackFlushInterval,
                              Duration chaseDelay,
                              Duration holeExpiry,
                              Duration sweepInterval,
                              int maxHolesPerChase,
                              int keyConcurrency,
                              Duration pollBackstop,
                              Duration maxSweepInterval,
                              int pumpThreads,
                              Duration shedGrace,
                              Duration leaseTtl,
                              Duration watermarkCap) {
        this(readBatchSize, ackBatchSize, ackFlushInterval, chaseDelay, holeExpiry, sweepInterval, maxHolesPerChase,
             keyConcurrency, pollBackstop, maxSweepInterval, pumpThreads, shedGrace, leaseTtl, watermarkCap,
             DEFAULT_STUCK_HANDLER_TIMEOUT);
    }

    /**
     * A builder starting from {@link #defaults()}: set only the settings that differ.
     */
    public static ShardOwnerSettingsBuilder builder() {
        return new ShardOwnerSettingsBuilder();
    }

    public static ShardOwnerSettings defaults() {
        return new ShardOwnerSettings(500,
                                      200,
                                      Duration.ofMillis(1),
                                      Duration.ofMillis(2),
                                      Duration.ofSeconds(10),
                                      Duration.ofMillis(500),
                                      1_000,
                                      8,
                                      Duration.ofMillis(500),
                                      Duration.ofSeconds(30),
                                      2,
                                      Duration.ofSeconds(5),
                                      Duration.ofSeconds(30),
                                      Duration.ofSeconds(60),
                                      DEFAULT_STUCK_HANDLER_TIMEOUT);
    }

    /**
     * These settings with another {@code stuckHandlerTimeout}: how one queue is given its own bound while every other
     * setting stays shared, as the Spring starter does for {@code essentials.shard-owned-queue.stuck-handler-timeouts}.
     */
    public ShardOwnerSettings withStuckHandlerTimeout(Duration stuckHandlerTimeout) {
        return new ShardOwnerSettings(readBatchSize, ackBatchSize, ackFlushInterval, chaseDelay, holeExpiry, sweepInterval,
                                      maxHolesPerChase, keyConcurrency, pollBackstop, maxSweepInterval, pumpThreads,
                                      shedGrace, leaseTtl, watermarkCap, stuckHandlerTimeout);
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

    public long leaseTtlMillis() {
        return leaseTtl.toMillis();
    }

    public long watermarkCapNanos() {
        return watermarkCap.toNanos();
    }
}
