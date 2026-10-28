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
 *                           queue stops waiting. Not while the stop that kept the unit is still flushing the
 *                           acknowledgements of the owners it ended, on a thread the stop waits for no longer than 2 s:
 *                           no run of this queue hands the unit back or takes it over beside that flush, and no other
 *                           instance while this one is live. That flush's writes would then be refused. A unit still
 *                           waiting for that flush is given up, whatever its handler does, once it has been kept longer
 *                           than this setting, or longer than 2 s plus the network timeout that thread sets on its
 *                           connections ({@code leaseTtl}, or 2 s when that is longer) when that is longer: this
 *                           setting bounds how long a handler may run, not how long a flush may take, so a short one
 *                           still gives a slow flush at least the 2 s plus one full network-bound wait. Reported at
 *                           WARN once it has waited longer than that network timeout, at most once a minute per
 *                           unit.</li>
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
 *                         how long the longest has been in its handler. Every one of the three counts the delivery in
 *                         the handler past this bound as an attempt: the takeover of the first charges it, and the shed
 *                         and the hand-back, which release the unit, so its next owner charges nothing, charge it
 *                         themselves before the release - dead-lettering a message the charge brings to its
 *                         {@code maxAttempts}. The shed and the hand-back charge each message in a handler longer than
 *                         this, and release one in a handler for less uncharged, its handler taken to be slow, not hung,
 *                         as the unordered lane does (below): either fires once one key has been in its handler this
 *                         long, and the unit's other keys may have entered theirs moments before.
 *                         {@code MessageQueue.releaseKeptOrderedUnit} charges every message in a handler, whatever its
 *                         age. A message whose handler hangs on every
 *                         delivery therefore reaches the dead-letter table once the queue has stopped waiting for it
 *                         {@code maxAttempts} times; a hang in a handler of an owner that goes on serving its unit is not
 *                         bounded by this. Released uncharged, it was handed from owner to owner for good. The unit's
 *                         next owner delivers the message of each key still in a handler again, unless it was
 *                         dead-lettered, possibly beside its handler, so each of those keys may run in two handlers at
 *                         once - which is the at-least-once the bound trades the stuck unit for. A kept unit with a key
 *                         in its handler, and a unit the queue stopped waiting for in any of the three while that handler runs
 *                         on, is counted in {@code ShardOwnerMetrics.keptUnitsInHandler()}
 *                         <p>
 *                         The unordered lane waits for no handler: a fair-share shed and the hand-back from a dead pump
 *                         release a shard at once, and a stop once {@code shedGrace} has run out, with whatever is in
 *                         its handlers. This bound decides what such a release charges: each message in a handler
 *                         longer than this is charged an attempt before the release, and dead-lettered if that brings
 *                         it to its {@code maxAttempts}, as the shard's next owner, finding it released, charges
 *                         nothing; a message in a handler for less is released uncharged, its handler taken to be slow,
 *                         not hung. A message whose unordered handler hangs on every delivery therefore reaches the
 *                         dead-letter table once it has been released past this bound {@code maxAttempts} times. Each
 *                         release that charged logs one WARN naming the shard, how many it charged and which it
 *                         dead-lettered; such a shard is not counted in {@code keptUnitsInHandler()}
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
     *
     * @see #toBuilder()
     */
    public static ShardOwnerSettingsBuilder builder() {
        return new ShardOwnerSettingsBuilder();
    }

    /**
     * A builder starting from these settings: set only the settings that differ from them. These settings are left as
     * they are.
     *
     * @see #builder()
     */
    public ShardOwnerSettingsBuilder toBuilder() {
        return new ShardOwnerSettingsBuilder(this);
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
     * <p>
     * The same as {@code toBuilder().setStuckHandlerTimeout(stuckHandlerTimeout).build()}. It is kept as a method of its
     * own because {@code stuckHandlerTimeout} is the one setting the Spring starter gives each queue separately - it
     * shares every other one across its queues - and this method is how the starter and the module's docs give a queue
     * its own bound. Derive any other setting with {@link #toBuilder()}.
     */
    public ShardOwnerSettings withStuckHandlerTimeout(Duration stuckHandlerTimeout) {
        return toBuilder().setStuckHandlerTimeout(stuckHandlerTimeout).build();
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

    /**
     * The values a {@link ShardRuntime} reads from these settings: {@code pumpThreads} (how many pumps it starts and the
     * pool connections it reserves for them) and the {@code readBatchSize}, {@code pollBackstop} and
     * {@code maxSweepInterval} its pumps read with and park by. A {@code ShardPump} is given only these, so its type holds
     * it to them; the runtime is built with the whole record and keeps only these, and {@code RuntimeScopedSettingsTest}
     * fails when it reads any other value. A value either needs is added here - and so to what a shared runtime's WARN
     * compares ({@code ShardRuntime.acquireShared}). Every other value is read only by the queue the settings are given to - its
     * owners are built with them - so a queue sharing a runtime with other queues still gets them as given.
     */
    RuntimeScoped runtimeScoped() {
        return new RuntimeScoped(pumpThreads, readBatchSize, pollBackstop, maxSweepInterval);
    }

    /**
     * See {@link #runtimeScoped()}.
     */
    record RuntimeScoped(int pumpThreads, int readBatchSize, Duration pollBackstop, Duration maxSweepInterval) {
    }
}
