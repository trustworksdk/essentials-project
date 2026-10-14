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
package dk.trustworks.essentials.components.foundation.messaging.queue;

import java.time.Instant;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * When messages known to this node become deliverable, as reported to {@link QueuePollingOptimizer#messageScheduledFor(Instant)}.
 * Shared by {@link CentralizedQueuePollingOptimizer} and {@link SimpleQueuePollingOptimizer}, each of which holds one
 * instance and ends its backoff early once a tracked due time has passed.
 * <p>
 * Due times are kept per millisecond. The set is bounded: past {@link #MAX_TRACKED_DUE_TIMES} the latest are dropped,
 * and those messages wait for the optimizer's own backoff instead. The bound is enforced against a counter kept next to
 * the set rather than {@link ConcurrentSkipListSet#size()}, which walks the whole set. The counter is exact once
 * concurrent calls have returned and may lag the set by the calls in flight, so the set can exceed the bound by at most
 * the number of threads scheduling at the same time.
 */
final class DueTimes {
    static final int MAX_TRACKED_DUE_TIMES = 1_000;
    /**
     * Only used when a caller reports a poll without the time its claim ran at (the {@link QueuePollingOptimizer}
     * methods without a {@code claimedAt}): due times this much older than the report are taken as looked for. With
     * the claim's time, due times are forgotten up to exactly that - a poll of many queues can reach a queue long after
     * its claim's {@code now} was read, so a fixed grace could forget a due time its claim had not yet seen
     */
    static final long DUE_TIME_GRACE_MS = 250;

    private final ConcurrentSkipListSet<Long> dueAtEpochMs = new ConcurrentSkipListSet<>();
    private final AtomicInteger              count        = new AtomicInteger();
    private final int                        maxTrackedDueTimes;

    DueTimes() {
        this(MAX_TRACKED_DUE_TIMES);
    }

    /**
     * @param maxTrackedDueTimes how many due times to keep before the latest are dropped - only differs from
     *                           {@link #MAX_TRACKED_DUE_TIMES} in tests
     */
    DueTimes(int maxTrackedDueTimes) {
        if (maxTrackedDueTimes <= 0) throw new IllegalArgumentException("maxTrackedDueTimes must be > 0");
        this.maxTrackedDueTimes = maxTrackedDueTimes;
    }

    /**
     * The claim time to assume for a poll reported without one: {@link #DUE_TIME_GRACE_MS} before now
     */
    static Instant assumedClaimTime() {
        return Instant.now().minusMillis(DUE_TIME_GRACE_MS);
    }

    /**
     * Track that a message becomes deliverable at {@code dueAt}. When more than the bound are tracked, the latest are
     * dropped
     */
    void schedule(Instant dueAt) {
        if (dueAtEpochMs.add(dueAt.toEpochMilli())) {
            count.incrementAndGet();
        }
        while (count.get() > maxTrackedDueTimes) {
            if (dueAtEpochMs.pollLast() == null) {
                // A concurrent forget emptied the set and has not yet decremented the counter
                break;
            }
            count.decrementAndGet();
        }
    }

    /**
     * A claim run at {@code claimedAt} looked for every message due at or before it, found or not. Due times are kept
     * per millisecond, so one in the same millisecond as {@code claimedAt} may still lie after it and is kept for the
     * next poll
     */
    void forgetSeenBy(Instant claimedAt) {
        var seen = dueAtEpochMs.headSet(claimedAt.toEpochMilli(), false);
        while (seen.pollFirst() != null) {
            count.decrementAndGet();
        }
    }

    /**
     * {@link #forgetSeenBy(Instant)} for a poll reported without its claim time, assuming {@link #assumedClaimTime()}
     */
    void forgetSeenByNowMinusGrace() {
        forgetSeenBy(assumedClaimTime());
    }

    /**
     * @return whether a tracked message is deliverable at {@code epochMs}, i.e. the earliest tracked due time is at or
     * before it
     */
    boolean hasDueBy(long epochMs) {
        var earliestDue = dueAtEpochMs.ceiling(Long.MIN_VALUE);
        return earliestDue != null && earliestDue <= epochMs;
    }

    /**
     * @return how many due times are tracked, by the counter the bound is enforced against
     */
    int trackedCount() {
        return count.get();
    }
}
