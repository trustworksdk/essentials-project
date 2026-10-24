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
 * The rate limit of {@code ShardOwnedQueue.releaseLeaseRows}' WARN that it left an owner out of a release because a
 * dead-letter move of the owner's was still owed ({@link LeasedOwner#owesDeadLetterMove()}): one per owner, kept on the
 * owner ({@link LeasedOwner#owedMoveReleaseRefusals()}) for as long as it lives. A release site that misses the move
 * misses it on every attempt, and the heartbeat's sites - the sheds and the dead-pump hand-back - attempt it on every
 * tick, so the WARN was logged on every tick for as long as the unit was held. Now at most one WARN a minute per owner
 * ({@link ErrorWindow}), which says how many releases left the owner out since the last one; the rest at DEBUG.
 * <p>
 * Asked from whichever thread releases - a heartbeat tick, {@code stop()}, a release on request - which the
 * {@link RateLimitedCount} it counts on allows: its counter and window are atomics.
 */
final class OwedMoveReleaseRefusals {
    private final RateLimitedCount leftOut = new RateLimitedCount();

    /**
     * Record one more release that left the owner out, at {@code now} ({@code System.nanoTime()}, or an injected clock).
     *
     * @return how many releases left the owner out since the last WARN of this owner, this one included, when this one is
     * to be logged at WARN; {@code 0} when it is to be logged at DEBUG
     */
    int leftOut(long now) {
        return leftOut.record(now);
    }
}
