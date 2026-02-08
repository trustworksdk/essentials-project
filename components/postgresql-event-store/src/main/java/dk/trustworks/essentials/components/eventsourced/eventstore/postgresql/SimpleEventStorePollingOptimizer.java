/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import org.slf4j.*;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A simple {@link EventStorePollingOptimizer} that gradually increases the effective polling delay
 * (by skipping poll cycles) when no events are returned, up to a configured max delay.
 * When events are returned, the optimizer resets the effective delay.
 *
 * Semantics mirror SimpleQueuePollingOptimizer for {@link DurableQueues}, but adapted to the event store API.
 */
public final class SimpleEventStorePollingOptimizer implements EventStorePollingOptimizer {
    private static final Logger log = LoggerFactory.getLogger(SimpleEventStorePollingOptimizer.class);

    private final long pollingIntervalMs;
    private final long delayIncrementMs;
    private final long maxDelayMs;
    private final String logName;

    private final AtomicLong currentDelayMs = new AtomicLong(0L);
    private final AtomicLong skipCallCount  = new AtomicLong(0L);
    private final AtomicLong skippedCallCount  = new AtomicLong(0L);

    /**
     * @param logName           name used in trace logs to identify the poller (e.g. subscriberId+aggregateType)
     * @param pollingIntervalMs base polling interval in milliseconds
     * @param delayIncrementMs  how much to increase the effective delay each time no events are returned
     * @param maxDelayMs        the maximum effective delay
     */
    public SimpleEventStorePollingOptimizer(String logName,
                                            long pollingIntervalMs,
                                            long delayIncrementMs,
                                            long maxDelayMs) {
        if (pollingIntervalMs <= 0) throw new IllegalArgumentException("pollingIntervalMs must be > 0");
        if (delayIncrementMs <= 0) throw new IllegalArgumentException("delayIncrementMs must be > 0");
        if (maxDelayMs < pollingIntervalMs) throw new IllegalArgumentException("maxDelayMs must be >= pollingIntervalMs");
        this.logName = logName;
        this.pollingIntervalMs = pollingIntervalMs;
        this.delayIncrementMs = delayIncrementMs;
        this.maxDelayMs = maxDelayMs;
    }

    @Override
    public void eventStorePollingReturnedNoEvents() {
        long current = currentDelayMs.get();
        if (current < maxDelayMs) {
            long next = Math.min(maxDelayMs, current + delayIncrementMs);
            currentDelayMs.set(next);
            log.trace("[{}] no events - increasing effective delay to {} ms", logName, next);
        } else {
            log.trace("[{}] no events - keeping effective delay at {} ms", logName, current);
        }
    }

    @Override
    public void eventStorePollingReturnedEvents() {
        log.trace("[{}] events returned - resetting effective delay and counters", logName);
        currentDelayMs.set(0L);
        skipCallCount.set(0L);
        skippedCallCount.set(0L);
    }

    @Override
    public boolean shouldSkipPolling() {
        long delay = currentDelayMs.get();
        if (delay == 0L) return false;

        long count = skipCallCount.incrementAndGet();

        boolean skip;
        if (count >= Long.MAX_VALUE / pollingIntervalMs) {
            skip = true;
        } else {
            skip = (pollingIntervalMs * count) <= delay; // safe now due to guard
        }

        if (skip) {
            long s = skippedCallCount.incrementAndGet();
            log.trace("[{}] skip=true (interval={} * callCount={} <= delay={}), skipped={}",
                      logName, pollingIntervalMs, count, delay, s);
            // Consider removing any auto-reset here to avoid oscillation
        } else {
            log.trace("[{}] skip=false (interval={} * callCount={} > delay={})",
                      logName, pollingIntervalMs, count, delay);
        }
        return skip;
    }

    @Override
    public long currentDelayMs() {
        return currentDelayMs.get();
    }

    @Override
    public String toString() {
        return "SimpleEventStorePollingOptimizer{" +
                "currentDelayMs=" + currentDelayMs.get() +
               ", pollingIntervalMs=" + pollingIntervalMs +
               ", delayIncrementMs=" + delayIncrementMs +
               ", maxDelayMs=" + maxDelayMs +
               ", logName='" + logName + '\'' +
               '}';
    }
}
