/*
 *  Copyright 2021-2025 the original author or authors.
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

import org.slf4j.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@link JitteredEventStorePollingOptimizer} implements the {@link EventStorePollingOptimizer} interface
 * and provides a polling optimization mechanism with backoff and jitter capabilities. This class is used
 * to dynamically adjust polling intervals for an event store, reducing load while maintaining responsiveness.
 *
 * <p>The optimization establishes a base polling interval and increases the delay incrementally upon
 * receiving no events. A jitter is applied to introduce randomness into the sleep duration,
 * avoiding synchronized polling patterns across distributed systems.
 */
public class JitteredEventStorePollingOptimizer implements EventStorePollingOptimizer {
    private static final Logger log = LoggerFactory.getLogger(JitteredEventStorePollingOptimizer.class);

    private final long   pollingIntervalMs;
    private final long   incrementMs;
    private final long   maxBackoffMs;
    private final double jitterRatio;       // e.g. 0.20 = ±20%
    private final String eventStreamLogName;

    private final AtomicLong backoffMs = new AtomicLong(0L);

    /**
     * @param eventStreamLogName label for logs (e.g., subscriberId+aggregateType)
     * @param pollingIntervalMs  base polling interval in ms (>0)
     * @param incrementMs        linear backoff increment in ms (>0)
     * @param maxBackoffMs       max backoff in ms (>= pollingIntervalMs)
     * @param jitterRatio        jitter fraction in [0.0..0.5] (e.g., 0.20 for ±20%)
     */
    public JitteredEventStorePollingOptimizer(String eventStreamLogName,
                                              long pollingIntervalMs,
                                              long incrementMs,
                                              long maxBackoffMs,
                                              double jitterRatio) {
        if (pollingIntervalMs <= 0) throw new IllegalArgumentException("pollingIntervalMs must be > 0");
        if (incrementMs <= 0) throw new IllegalArgumentException("incrementMs must be > 0");
        if (maxBackoffMs < pollingIntervalMs) throw new IllegalArgumentException("maxBackoffMs must be >= pollingIntervalMs");
        if (jitterRatio < 0.0 || jitterRatio > 0.5) throw new IllegalArgumentException("jitterRatio must be in [0.0..0.5]");

        this.eventStreamLogName = requireNonNull(eventStreamLogName, "eventStreamLogName must be non-null");
        this.pollingIntervalMs = pollingIntervalMs;
        this.incrementMs = incrementMs;
        this.maxBackoffMs = maxBackoffMs;
        this.jitterRatio = jitterRatio;
    }

    @Override
    public void eventStorePollingReturnedNoEvents() {
        long next = backoffMs.updateAndGet(curr -> Math.min(maxBackoffMs, curr + incrementMs));
        log.trace("[{}] No events - backoff increased to {} ms", eventStreamLogName, next);
    }

    @Override
    public void eventStorePollingReturnedEvents() {
        backoffMs.set(0L);
        log.trace("[{}] Events returned - backoff reset to 0", eventStreamLogName);
    }

    /**
     * Keep compatibility with existing call sites: after an empty poll,
     * we always want to sleep, so this returns true.
     */
    @Override
    public boolean shouldSkipPolling() {
        return true;
    }

    /**
     * Returns the sleep duration (ms) to use after an empty poll:
     * max(pollingInterval, backoff) with ±jitter.
     * Jitter is applied per call; it is not stored.
     */
    @Override
    public long currentDelayMs() {
        long base = Math.max(pollingIntervalMs, backoffMs.get());
        if (base <= 0) return 0L;

        if (jitterRatio == 0.0) {
            log.trace("[{}] Sleep after empty poll: base={} ms, jitterRatio=0.0", eventStreamLogName, base);
            return base;
        }

        double delta = ThreadLocalRandom.current().nextDouble(-jitterRatio, jitterRatio);
        long   sleep = Math.max(0L, Math.round(base * (1.0 + delta)));

        log.trace("[{}] Sleep after empty poll: base={} ms, jitterRatio={}, sleep={} ms",
                  eventStreamLogName, base, jitterRatio, sleep);
        return sleep;
    }

    @Override
    public String toString() {
        return "JitteredEventStorePollingOptimizer{" +
                "backoffMs=" + backoffMs.get() +
                ", pollingIntervalMs=" + pollingIntervalMs +
                ", incrementMs=" + incrementMs +
                ", maxBackoffMs=" + maxBackoffMs +
                ", jitterRatio=" + jitterRatio +
                ", logName='" + eventStreamLogName + '\'' +
                '}';
    }
}
