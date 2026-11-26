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

package dk.trustworks.essentials.components.eventlog.migration;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * How the copy runs.
 *
 * @param batchSize          the {@code global_order} values per batch transaction (default 10 000); a batch over
 *                           mostly holes copies few rows
 * @param epochWaitTimeout   how long an online epoch waits for transactions older than its start (default 10 min);
 *                           on timeout the epoch copies nothing and reports the blockers
 * @param epochWaitPoll      how often the epoch wait polls {@code pg_stat_activity} (default 20 ms)
 * @param lockTimeout        the cut-over's {@code lock_timeout} for the {@code SHARE} locks on the old tables (default
 * 10 s); on timeout nothing is copied and the cut-over reports writers still running
 * @param maxEventsPerSecond the online copy's throttle, applied between batches (default 25 000, at that rate the old
 * engine's append p99 during an epoch was 0.84-0.92x of its p99 without a copy in the gate); 0 is unthrottled. The
 * cut-over is never throttled
 * @param verifyWindow       the {@code global_order} values per verification window (default 100 000): each window
 *                           is hashed on both sides, and a differing one is bisected down to its rows
 */
public record CopySettings(int batchSize, Duration epochWaitTimeout, Duration epochWaitPoll, Duration lockTimeout, long maxEventsPerSecond, int verifyWindow) {
    public CopySettings {
        requireTrue(batchSize > 0, "batchSize must be > 0");
        requireNonNull(epochWaitTimeout, "No epochWaitTimeout provided");
        requireNonNull(epochWaitPoll, "No epochWaitPoll provided");
        requireNonNull(lockTimeout, "No lockTimeout provided");
        requireTrue(!epochWaitTimeout.isNegative(), "epochWaitTimeout must be >= 0");
        requireTrue(!epochWaitPoll.isNegative() && !epochWaitPoll.isZero(), "epochWaitPoll must be > 0");
        requireTrue(lockTimeout.toMillis() > 0, "lockTimeout must be at least 1 ms");
        requireTrue(maxEventsPerSecond >= 0, "maxEventsPerSecond must be >= 0 (0 is unthrottled)");
        requireTrue(verifyWindow > 0, "verifyWindow must be > 0");
    }

    public static CopySettings defaults() {
        return new CopySettings(10_000, Duration.ofMinutes(10), Duration.ofMillis(20), Duration.ofSeconds(10), 25_000, 100_000);
    }

    public CopySettings withBatchSize(int batchSize) {
        return new CopySettings(batchSize, epochWaitTimeout, epochWaitPoll, lockTimeout, maxEventsPerSecond, verifyWindow);
    }

    public CopySettings withEpochWaitTimeout(Duration epochWaitTimeout) {
        return new CopySettings(batchSize, epochWaitTimeout, epochWaitPoll, lockTimeout, maxEventsPerSecond, verifyWindow);
    }

    public CopySettings withLockTimeout(Duration lockTimeout) {
        return new CopySettings(batchSize, epochWaitTimeout, epochWaitPoll, lockTimeout, maxEventsPerSecond, verifyWindow);
    }

    public CopySettings withMaxEventsPerSecond(long maxEventsPerSecond) {
        return new CopySettings(batchSize, epochWaitTimeout, epochWaitPoll, lockTimeout, maxEventsPerSecond, verifyWindow);
    }

    public CopySettings withVerifyWindow(int verifyWindow) {
        return new CopySettings(batchSize, epochWaitTimeout, epochWaitPoll, lockTimeout, maxEventsPerSecond, verifyWindow);
    }
}
