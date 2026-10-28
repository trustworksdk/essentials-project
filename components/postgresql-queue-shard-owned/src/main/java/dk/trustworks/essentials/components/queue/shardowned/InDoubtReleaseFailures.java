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

/**
 * The rate limit of {@code ShardOwnedQueue.rebalance}'s WARN that a release of an unordered owner already in doubt
 * ({@link LeasedOwner#releaseInDoubt()}) failed again: one per owner, kept on the owner's run
 * ({@code ShardOwnedQueue.Run.inDoubtReleaseFailures}) while the owner is shed on every heartbeat tick, and dropped once
 * the owner leaves the run's held owners - its release succeeded, or its unit was found taken. Such a release can keep
 * failing for as long as the cause lasts - a trigger refusing it, a database whose answers keep timing out - and logged
 * a WARN with the stack trace on every tick, a third of the lease TTL apart. Now at most one WARN with the stack trace a
 * minute ({@link ErrorWindow}), which says how many attempts failed since the last one; the rest at DEBUG.
 * <p>
 * Asked by the run's heartbeat ticks alone, which never overlap (one {@code scheduleAtFixedRate} task per run); the
 * {@link RateLimitedCount} it counts on is atomic for its other use, {@link OwedMoveReleaseRefusals}, not because of
 * contention here.
 */
final class InDoubtReleaseFailures {
    private final RateLimitedCount failures = new RateLimitedCount();

    /**
     * Record one more failed release attempt of the owner, at {@code now} ({@code System.nanoTime()}, or an injected
     * clock).
     *
     * @return how many attempts failed since the last WARN of this owner, this one included, when this one is to be
     * logged at WARN with its stack trace; {@code 0} when it is to be logged at DEBUG
     */
    int failed(long now) {
        return failures.record(now);
    }
}
