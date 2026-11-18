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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * Real time plus an offset the test moves forward: the clock a test hands to {@code ShardOwnedQueue.useNanoTime} or
 * {@code PostgresqlMessageQueue.useNanoTime}, so the stuck-handler and kept-unit bounds ({@code stuckHandlerTimeout},
 * {@code ShardOwnedQueue.stopFlushGiveUpAfter}) can be passed with {@link #advance} instead of waited out.
 * <p>
 * It keeps running in real time, so everything the engine times by it still ages between two advances, and it never
 * goes back: a key that enters its handler after an advance is younger than one that entered before by at least that
 * advance, whatever the build's load. Nothing else in the engine reads it - lease TTLs, heartbeats, the shed grace and
 * the shutdown budget stay on real time - so a test advancing it still waits for those.
 */
final class TestNanoClock implements LongSupplier {
    private final AtomicLong offsetNanos = new AtomicLong();

    @Override
    public long getAsLong() {
        return System.nanoTime() + offsetNanos.get();
    }

    /**
     * Move the clock forward by {@code by}, on top of the real time that passes.
     */
    void advance(Duration by) {
        requireNonNull(by, "No duration provided");
        requireTrue(!by.isNegative(), "A clock that never goes back cannot be advanced by a negative duration");
        offsetNanos.addAndGet(by.toNanos());
    }

    /**
     * How long has passed by this clock since {@code sinceNanos}, a reading of {@link #getAsLong()}.
     */
    Duration since(long sinceNanos) {
        return Duration.ofNanos(getAsLong() - sinceNanos);
    }
}
