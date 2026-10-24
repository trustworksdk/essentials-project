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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A once-a-minute log window: the time ({@code System.nanoTime()}, or an injected clock) from which the next
 * rate-limited line may be logged (the pump's failure logs log it with the stack trace and the rest at DEBUG). Shared by
 * every rate-limited failure log of the pump: {@link TakeoverStalls}' ERROR, and {@link PumpFailures}' ERROR and batched-read WARN, each on its own field of the owner's {@link ShardOwnerMetrics}, and
 * {@link ShardPump}'s per-pump ERROR for a pass that fails outside its per-shard guards. Also by
 * {@code ShardOwnedQueue}'s WARN that an ordered unit a stop kept is held up by a handler past {@code leaseTtl}, one
 * window per kept unit ({@code KeptUnit.warningDue}), taken by {@code ShardOwnedQueue.reviewStuckKeptUnits} rather
 * than by a pump. And by {@code ShardOwnedQueue.rebalance}'s WARN that the release of an unordered owner in doubt failed
 * again, one window per owner ({@link InDoubtReleaseFailures}), taken by the heartbeat tick.
 * Reuse it for a new once-a-minute log rather than writing the CAS again.
 * <p>
 * Its own class rather than a helper on one of them, so a change to one log's semantics (the takeover window reopening
 * when no shard is stalled, say) cannot silently change the others.
 */
final class ErrorWindow {
    /**
     * How long a taken window stays closed.
     */
    static final long EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);
    /**
     * A window that is open regardless of the clock: never taken, or reopened. A sentinel rather than a time, because the
     * clock is injected and a time taken from {@code System.nanoTime()} when the window was created means nothing against
     * it.
     */
    static final long NEVER_TAKEN = Long.MIN_VALUE;

    private ErrorWindow() {
    }

    /**
     * Whether a window holding {@code next} is open at {@code now}: never taken, or a time that has passed.
     */
    static boolean isOpen(long next, long now) {
        return next == NEVER_TAKEN || now - next >= 0;
    }

    /**
     * Take a window, which may be asked by several threads at once (every pump serving a queue, say): open per
     * {@link #isOpen}, and taken by whichever caller wins the CAS, which closes it for {@link #EVERY_NANOS}.
     *
     * @return whether this caller took it, and so logs the rate-limited line
     */
    static boolean take(AtomicLong window, long now) {
        var next = window.get();
        return isOpen(next, now) && window.compareAndSet(next, now + EVERY_NANOS);
    }

    /**
     * Reopen the window unless it has been taken since {@code seen} was read from it: a pump that took it in between
     * keeps it, so the line it logged is not followed by another within the minute.
     *
     * @param seen the window's value read before whatever decided it should reopen
     */
    static void reopen(AtomicLong window, long seen) {
        window.compareAndSet(seen, NEVER_TAKEN);
    }
}
