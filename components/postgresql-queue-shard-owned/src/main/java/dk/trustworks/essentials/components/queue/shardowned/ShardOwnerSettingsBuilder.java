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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link ShardOwnerSettings}: set only what differs. {@link ShardOwnerSettings#builder()} starts from
 * {@link ShardOwnerSettings#defaults()}, {@link ShardOwnerSettings#toBuilder()} from the settings it is called on.
 * The canonical constructor takes every setting in a row, and a caller naming two of them had to repeat thirteen
 * others - and change every such call when a setting was added. Each setting is documented on the record.
 */
public final class ShardOwnerSettingsBuilder {
    private int      readBatchSize;
    private int      ackBatchSize;
    private Duration ackFlushInterval;
    private Duration chaseDelay;
    private Duration holeExpiry;
    private Duration sweepInterval;
    private int      maxHolesPerChase;
    private int      keyConcurrency;
    private Duration pollBackstop;
    private Duration maxSweepInterval;
    private int      pumpThreads;
    private Duration shedGrace;
    private Duration leaseTtl;
    private Duration watermarkCap;
    private Duration stuckHandlerTimeout;

    ShardOwnerSettingsBuilder() {
        this(ShardOwnerSettings.defaults());
    }

    ShardOwnerSettingsBuilder(ShardOwnerSettings from) {
        requireNonNull(from, "No settings to start from provided");
        readBatchSize = from.readBatchSize();
        ackBatchSize = from.ackBatchSize();
        ackFlushInterval = from.ackFlushInterval();
        chaseDelay = from.chaseDelay();
        holeExpiry = from.holeExpiry();
        sweepInterval = from.sweepInterval();
        maxHolesPerChase = from.maxHolesPerChase();
        keyConcurrency = from.keyConcurrency();
        pollBackstop = from.pollBackstop();
        maxSweepInterval = from.maxSweepInterval();
        pumpThreads = from.pumpThreads();
        shedGrace = from.shedGrace();
        leaseTtl = from.leaseTtl();
        watermarkCap = from.watermarkCap();
        stuckHandlerTimeout = from.stuckHandlerTimeout();
    }

    public ShardOwnerSettingsBuilder setReadBatchSize(int readBatchSize) {
        this.readBatchSize = readBatchSize;
        return this;
    }

    public ShardOwnerSettingsBuilder setAckBatchSize(int ackBatchSize) {
        this.ackBatchSize = ackBatchSize;
        return this;
    }

    public ShardOwnerSettingsBuilder setAckFlushInterval(Duration ackFlushInterval) {
        this.ackFlushInterval = ackFlushInterval;
        return this;
    }

    public ShardOwnerSettingsBuilder setChaseDelay(Duration chaseDelay) {
        this.chaseDelay = chaseDelay;
        return this;
    }

    public ShardOwnerSettingsBuilder setHoleExpiry(Duration holeExpiry) {
        this.holeExpiry = holeExpiry;
        return this;
    }

    public ShardOwnerSettingsBuilder setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval;
        return this;
    }

    public ShardOwnerSettingsBuilder setMaxHolesPerChase(int maxHolesPerChase) {
        this.maxHolesPerChase = maxHolesPerChase;
        return this;
    }

    public ShardOwnerSettingsBuilder setKeyConcurrency(int keyConcurrency) {
        this.keyConcurrency = keyConcurrency;
        return this;
    }

    public ShardOwnerSettingsBuilder setPollBackstop(Duration pollBackstop) {
        this.pollBackstop = pollBackstop;
        return this;
    }

    public ShardOwnerSettingsBuilder setMaxSweepInterval(Duration maxSweepInterval) {
        this.maxSweepInterval = maxSweepInterval;
        return this;
    }

    public ShardOwnerSettingsBuilder setPumpThreads(int pumpThreads) {
        this.pumpThreads = pumpThreads;
        return this;
    }

    public ShardOwnerSettingsBuilder setShedGrace(Duration shedGrace) {
        this.shedGrace = shedGrace;
        return this;
    }

    public ShardOwnerSettingsBuilder setLeaseTtl(Duration leaseTtl) {
        this.leaseTtl = leaseTtl;
        return this;
    }

    public ShardOwnerSettingsBuilder setWatermarkCap(Duration watermarkCap) {
        this.watermarkCap = watermarkCap;
        return this;
    }

    public ShardOwnerSettingsBuilder setStuckHandlerTimeout(Duration stuckHandlerTimeout) {
        this.stuckHandlerTimeout = stuckHandlerTimeout;
        return this;
    }

    public ShardOwnerSettings build() {
        return new ShardOwnerSettings(readBatchSize, ackBatchSize, ackFlushInterval, chaseDelay, holeExpiry, sweepInterval,
                                      maxHolesPerChase, keyConcurrency, pollBackstop, maxSweepInterval, pumpThreads,
                                      shedGrace, leaseTtl, watermarkCap, stuckHandlerTimeout);
    }
}
