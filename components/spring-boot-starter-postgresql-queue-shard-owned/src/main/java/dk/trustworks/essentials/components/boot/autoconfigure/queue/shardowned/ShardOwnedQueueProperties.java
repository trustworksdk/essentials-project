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

package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.ShardOwnerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.*;

/**
 * Configuration for the shard-owned queue engine, under {@code essentials.shard-owned-queue}.
 * <p>
 * The engine's own {@link ShardOwnerSettings} is a record — being wide is its job — but a record is a
 * poor fit for relaxed binding, so this mirrors it with mutable fields and converts once.
 */
@ConfigurationProperties(prefix = "essentials.shard-owned-queue")
public class ShardOwnedQueueProperties {

    /**
     * Create the engine's tables, sequences and indexes at start-up if they are absent.
     * <p>
     * Non-destructive: it never drops anything. Turn it off where schema changes are owned by a
     * migration tool rather than by the application.
     */
    private boolean initializeSchema = true;

    /**
     * Identifies this process among the instances competing for a queue's shards. Defaults to the
     * hostname, matching how the fenced lock manager and the scheduler identify an instance.
     * <p>
     * Set it where the hostname is <em>not</em> unique per process — several instances on one host,
     * or a container platform that does not give each one its own. Two processes sharing an id look
     * like one to the fair-share rebalance, which halves the shards each of them is allowed to hold.
     */
    /**
     * Back the application's {@code DurableQueues} with this engine instead of
     * {@code PostgresqlDurableQueues} — so {@code Inbox}, {@code Outbox},
     * {@code DurableLocalCommandBus} and every {@code EventProcessor}'s projections are delivered by
     * it. Default {@code false}: the engine is experimental, and a starter on the classpath must not
     * silently move the delivery path of an application that only wanted the {@code MessageQueue}
     * contract.
     * <p>
     * Both engines implement the same interface, so this is an A/B rather than a one-way door: turn
     * it off and the default implementation comes back with no other change.
     * <p>
     * The adapter serves the subset of {@code DurableQueues} that Inbox, Outbox and the command bus
     * actually use. Operations outside it throw with the reason rather than returning something
     * approximate, so an application using the wider surface directly finds out at the call rather
     * than from a wrong answer.
     */
    private boolean durableQueuesEnabled = false;

    /**
     * Unordered shard count for a queue the adapter is asked for but nobody registered. Zero — the
     * default — fails instead.
     * <p>
     * Required in practice when {@link #isDurableQueuesEnabled()} is on, and the reason is structural:
     * {@code DurableQueues} invents a queue on first use, and an {@code Inbox} is named by the
     * processor that owns it, so the queues an application actually needs cannot all be listed in
     * {@link #getQueues()} ahead of time. This engine will not invent a shard count, because the count
     * caps how many instances can consume the unordered lane and can be raised but never lowered.
     * <p>
     * Applies to the unordered lane only — the ordered lane routes over a fixed space recorded on the
     * queue's registry row, so an auto-registered queue's ordered lane is already correct.
     */
    private int autoRegisterShardCount = 0;

    private String instanceId;

    /**
     * Queues to register at start-up, as {@code name: shardCount}.
     * <p>
     * <b>The count is the UNORDERED lane's alone.</b> It caps how many instances can consume that
     * lane and is the unit of its parallelism; the ordered lane routes over its own space, set by
     * {@link #getOrderedUnits()} and defaulting to {@code ShardOwnedSchema.ORDERED_UNITS}. This
     * javadoc used to call it "the unit of ordering", which was true only while both lanes sized
     * themselves from it.
     * <p>
     * Registration is idempotent and shared: whichever instance gets there first interns the name,
     * and the rest resolve it. Re-declaring a queue with a different shard count fails the context
     * rather than being accepted — two processes disagreeing about it write to shards nobody leases.
     * <p>
     * It may be grown later with {@code growShardCount}, which running consumers pick up on their
     * next heartbeat; it may never shrink.
     */
    private Map<String, Integer> queues = new LinkedHashMap<>();

    /**
     * Per-queue ordered routing space, as {@code name: units}, for queues that need more than the
     * default {@code ShardOwnedSchema.ORDERED_UNITS}. A name absent here gets the default.
     * <p>
     * Separate from {@link #getQueues()} rather than folded into it, because the common case is one
     * number and relaxed binding cannot map {@code orders: 4} onto an object. Raise it for the one
     * queue that needs more than 64 instances consuming its ordered lane — not across a process
     * running hundreds of queues, where the per-unit state (a lease row and an owner object each) is
     * what grows.
     * <p>
     * <b>Fixed at registration and never changed afterwards.</b> Routing reads the value recorded on
     * the queue's registry row, so raising it here affects queues created afterwards and leaves
     * existing ones routing exactly as before — which is what stops a version upgrade from moving
     * every key.
     */
    private Map<String, Integer> orderedUnits = new LinkedHashMap<>();

    /** Threads, and therefore held connections, this process uses to talk to the database. */
    private int pumpThreads = 2;
    /** Concurrent keys per ordered shard. */
    private int keyConcurrency = 8;
    private int readBatchSize = 500;
    private int ackBatchSize = 200;
    private int maxHolesPerChase = 1_000;
    private Duration ackFlushInterval = Duration.ofMillis(1);
    private Duration chaseDelay = Duration.ofMillis(2);
    /** Must exceed the longest enqueue transaction, or live messages are written off as aborted. */
    private Duration holeExpiry = Duration.ofSeconds(10);
    private Duration sweepInterval = Duration.ofMillis(500);
    private Duration maxSweepInterval = Duration.ofSeconds(30);
    private Duration pollBackstop = Duration.ofMillis(500);
    private Duration shedGrace = Duration.ofSeconds(5);
    /** How long a shard stays unserved if its owner dies without releasing it. */
    private Duration leaseTtl = Duration.ofSeconds(30);
    /**
     * Ordered lane only: how long the safe watermark waits for a write transaction to end before
     * advancing past it. Deliberately far above {@code holeExpiry} — see {@link ShardOwnerSettings}.
     */
    private Duration watermarkCap = Duration.ofSeconds(60);

    public ShardOwnerSettings toSettings() {
        return new ShardOwnerSettings(readBatchSize, ackBatchSize, ackFlushInterval, chaseDelay,
                                      holeExpiry, sweepInterval, maxHolesPerChase, keyConcurrency,
                                      pollBackstop, maxSweepInterval, pumpThreads,
                                      shedGrace, leaseTtl, watermarkCap);
    }

    public Duration getWatermarkCap() {
        return watermarkCap;
    }

    public void setWatermarkCap(Duration watermarkCap) {
        this.watermarkCap = watermarkCap;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Map<String, Integer> getQueues() {
        return queues;
    }

    public void setQueues(Map<String, Integer> queues) {
        this.queues = queues;
    }

    public int getPumpThreads() {
        return pumpThreads;
    }

    public void setPumpThreads(int pumpThreads) {
        this.pumpThreads = pumpThreads;
    }

    public int getKeyConcurrency() {
        return keyConcurrency;
    }

    public void setKeyConcurrency(int keyConcurrency) {
        this.keyConcurrency = keyConcurrency;
    }

    public int getReadBatchSize() {
        return readBatchSize;
    }

    public void setReadBatchSize(int readBatchSize) {
        this.readBatchSize = readBatchSize;
    }

    public int getAckBatchSize() {
        return ackBatchSize;
    }

    public void setAckBatchSize(int ackBatchSize) {
        this.ackBatchSize = ackBatchSize;
    }

    public int getMaxHolesPerChase() {
        return maxHolesPerChase;
    }

    public void setMaxHolesPerChase(int maxHolesPerChase) {
        this.maxHolesPerChase = maxHolesPerChase;
    }

    public Duration getAckFlushInterval() {
        return ackFlushInterval;
    }

    public void setAckFlushInterval(Duration ackFlushInterval) {
        this.ackFlushInterval = ackFlushInterval;
    }

    public Duration getChaseDelay() {
        return chaseDelay;
    }

    public void setChaseDelay(Duration chaseDelay) {
        this.chaseDelay = chaseDelay;
    }

    public Duration getHoleExpiry() {
        return holeExpiry;
    }

    public void setHoleExpiry(Duration holeExpiry) {
        this.holeExpiry = holeExpiry;
    }

    public Duration getSweepInterval() {
        return sweepInterval;
    }

    public void setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval;
    }

    public Duration getMaxSweepInterval() {
        return maxSweepInterval;
    }

    public void setMaxSweepInterval(Duration maxSweepInterval) {
        this.maxSweepInterval = maxSweepInterval;
    }

    public Duration getPollBackstop() {
        return pollBackstop;
    }

    public void setPollBackstop(Duration pollBackstop) {
        this.pollBackstop = pollBackstop;
    }

    public Duration getShedGrace() {
        return shedGrace;
    }

    public void setShedGrace(Duration shedGrace) {
        this.shedGrace = shedGrace;
    }

    public Duration getLeaseTtl() {
        return leaseTtl;
    }

    public void setLeaseTtl(Duration leaseTtl) {
        this.leaseTtl = leaseTtl;
    }

    public boolean isDurableQueuesEnabled() {
        return durableQueuesEnabled;
    }

    public void setDurableQueuesEnabled(boolean durableQueuesEnabled) {
        this.durableQueuesEnabled = durableQueuesEnabled;
    }

    public int getAutoRegisterShardCount() {
        return autoRegisterShardCount;
    }

    public void setAutoRegisterShardCount(int autoRegisterShardCount) {
        this.autoRegisterShardCount = autoRegisterShardCount;
    }

    public Map<String, Integer> getOrderedUnits() {
        return orderedUnits;
    }

    public void setOrderedUnits(Map<String, Integer> orderedUnits) {
        this.orderedUnits = orderedUnits;
    }
}
