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
package dk.trustworks.essentials.components.eventlog;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * How one subscription handles failures (design phase 8 §2.2, §6; phase 4 §4): an event that fails alone is retried
 * {@code retryAttempts} times, waiting {@code backoffStart} doubled per retry and capped at {@code backoffMax}, then
 * parked; an I/O failure waits on the same backoff and retries indefinitely. A page of an in-transaction subscription
 * holds at most {@code pageSize} events and shrinks on a handler failure.
 *
 * @param retryAttempts how many times an event that fails alone is retried before it is parked; at least 0
 * @param backoffStart  the first wait
 * @param backoffMax    the longest wait, at least {@code backoffStart}
 * @param pageSize      the most events in one page, 1 to 10 000
 */
public record ErrorSettings(int retryAttempts, Duration backoffStart, Duration backoffMax, int pageSize) {
    /** The largest page size (design phase 8 §6) */
    public static final int MAX_PAGE_SIZE = 10_000;

    public ErrorSettings {
        requireNonNull(backoffStart, "No backoffStart provided");
        requireNonNull(backoffMax, "No backoffMax provided");
        if (retryAttempts < 0) {
            throw new IllegalArgumentException("retryAttempts must be >= 0");
        }
        if (backoffStart.isNegative() || backoffStart.isZero()) {
            throw new IllegalArgumentException("backoffStart must be > 0");
        }
        if (backoffMax.compareTo(backoffStart) < 0) {
            throw new IllegalArgumentException("backoffMax must be >= backoffStart");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be 1 to " + MAX_PAGE_SIZE + ", not " + pageSize);
        }
    }

    /** @return the engine-wide defaults of {@code settings}: its retry attempts, backoff and page size */
    public static ErrorSettings defaults(EventLogSettings settings) {
        requireNonNull(settings, "No settings provided");
        return new ErrorSettings(settings.retryAttempts(), settings.retryBackoffInitial(), settings.retryBackoffMax(), Math.min(settings.pageSize(), MAX_PAGE_SIZE));
    }

    /** @return the wait before retry {@code retry} (0-based): {@code backoffStart} doubled {@code retry} times, at most {@code backoffMax} */
    Duration backoff(int retry) {
        var shift = Math.min(Math.max(0, retry), 30);
        var nanos = backoffStart.toNanos();
        var max   = backoffMax.toNanos();
        return Duration.ofNanos(nanos > (max >> shift) ? max : Math.min(max, nanos << shift));
    }
}
