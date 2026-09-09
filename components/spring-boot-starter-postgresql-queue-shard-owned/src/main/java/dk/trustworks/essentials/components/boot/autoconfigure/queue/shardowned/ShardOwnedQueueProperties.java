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
     * Identifies this process among the instances competing for a queue's shards. Defaults to a
     * random id per boot.
     * <p>
     * A <em>stable</em> id is worth setting where the platform provides one — a pod name, a task
     * arn — because it is what a log line or the membership table shows when shards move.
     */
    private String instanceId;

    /**
     * Queues to register at start-up, as {@code name: shardCount}.
     * <p>
     * Registration is idempotent and shared: whichever instance gets there first interns the name,
     * and the rest resolve it. Re-declaring a queue with a different shard count fails the context
     * rather than being accepted — the count is the unit of ordering and two processes disagreeing
     * about it strands messages.
     */
    private Map<String, Integer> queues = new LinkedHashMap<>();

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

    public ShardOwnerSettings toSettings() {
        return new ShardOwnerSettings(readBatchSize, ackBatchSize, ackFlushInterval, chaseDelay,
                                      holeExpiry, sweepInterval, maxHolesPerChase, keyConcurrency,
                                      pollBackstop, maxSweepInterval, pumpThreads,
                                      shedGrace, leaseTtl);
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
}
