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
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Lets one WARN through the first time it is asked, and then at most once per {@code interval}: the {@code LeaseOverrun}
 * and {@code BlockedKeyReport} pattern, for a condition reported on every heartbeat for as long as it lasts. One per
 * thing reported - {@code ShardOwnedQueue} keeps one per ordered unit a stop kept - and safe to ask from several threads
 * at once: of two callers asking together when a WARN is due, one gets it.
 */
final class WarningRateLimit {
    private final long                  intervalNanos;
    /**
     * When the next WARN may be let through, by {@link System#nanoTime()}; {@code null} until the first.
     */
    private final AtomicReference<Long> nextNanos = new AtomicReference<>();

    WarningRateLimit(Duration interval) {
        requireNonNull(interval, "No interval provided");
        requireTrue(interval.isPositive(), "interval must be positive");
        this.intervalNanos = interval.toNanos();
    }

    /**
     * Whether a WARN may be logged now: the first time, or {@code interval} or more after the last one let through. A
     * {@code true} claims it, so the next is due an {@code interval} from {@code nowNanos}.
     *
     * @param nowNanos the time now, by {@link System#nanoTime()}
     */
    boolean due(long nowNanos) {
        var next = nextNanos.get();
        if (next != null && nowNanos - next < 0) {
            return false;
        }
        return nextNanos.compareAndSet(next, nowNanos + intervalNanos);
    }
}
