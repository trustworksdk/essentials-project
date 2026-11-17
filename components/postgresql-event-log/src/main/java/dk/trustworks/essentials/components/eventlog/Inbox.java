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

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * A subscription's bounded buffer of {@link Batch}es (design phase 3 §2.3): one producer, the reader loop's thread, and
 * one consumer, the subscription's delivery thread. {@link #offer(Batch)} never blocks.
 * <ul>
 *     <li>The bound counts <b>events</b>, not batches.</li>
 *     <li>An inbox that holds no events accepts any batch, however large, so a batch above the bound can never make a
 *     subscription overflow forever.</li>
 *     <li>A watermark-only batch is always accepted and merged into a trailing watermark-only batch, so it costs no
 *     capacity and the watermark still arrives after the rows it covers.</li>
 *     <li>Otherwise a batch that would exceed the bound is refused: an overflow, after which the loop closes the inbox.</li>
 * </ul>
 * Once closed, an inbox accepts nothing and hands out nothing; what it held is discarded.
 * <p>
 * Phase 4 §2.3: an inbox created <b>counting until released</b> (for a {@code CONTIGUOUS} subscription) keeps counting
 * an event after {@link #take(long)} hands its batch out, until the delivery thread calls {@link #release(int)} - when
 * the row is applied, parked or dropped. Held-back rows thereby count against the bound, and a subscription that cannot
 * release (behind a long writer) overflows and detaches instead of growing without bound.
 */
final class Inbox {
    /** Why an inbox was closed: the detach causes of §3.6 */
    enum CloseReason {
        /** A batch did not fit (§2.3) */
        OVERFLOW,
        /** The reader's connection or a read failed; every attached subscription detaches (§2.4) */
        READER_RESTART,
        /** A superseding reset repositions the subscription (§3.6) */
        RESET,
        /** The subscription stopped */
        STOPPED,
        /** A catch-up or bridge statement failed; the subscription catches up again after the backstop interval */
        ERROR,
        /**
         * {@code FRESH} attached under an unknown watermark found more gaps than {@link EventLogSettings#holeRangeThreshold()}
         * to hand off: it waits for the watermark instead (design phase 5 §6.3)
         */
        TOO_MANY_GAPS
    }

    /** What {@link #offer(Batch)} did */
    enum Offer {
        ACCEPTED,
        /** The batch did not fit: an overflow */
        FULL,
        /** The inbox was already closed */
        CLOSED
    }

    private final int                    capacity;
    private final boolean                countUntilReleased;
    /** Phase 5 §2.4: only rows that pass count; a batch with none is stored as watermark-only */
    private final EventTypeFilter        filter;
    private final String                 tenant;
    /** Mutant {@link WatermarkMutant#INBOX_COUNTS_UNMATCHED}: every row counts, as without a filter */
    private final boolean                countUnmatched;
    private final ReentrantLock          lock     = new ReentrantLock();
    private final Condition              changed  = lock.newCondition();
    private final ArrayDeque<Batch>      batches  = new ArrayDeque<>();
    private int                          events;
    private CloseReason                  closed;
    private boolean                      consumerWaiting;

    /**
     * @param capacity the bound in events ({@link EventLogSettings#subscriptionBufferSize()})
     */
    Inbox(int capacity) {
        this(capacity, false);
    }

    /**
     * @param capacity           the bound in events ({@link EventLogSettings#subscriptionBufferSize()})
     * @param countUntilReleased {@code true}: an event counts from {@link #offer(Batch)} until {@link #release(int)}
     *                           (phase 4 §2.3); {@code false}: until its batch is taken (phase 3)
     */
    Inbox(int capacity, boolean countUntilReleased) {
        this(capacity, countUntilReleased, EventTypeFilter.all(), false);
    }

    /**
     * @param filter         the subscription's filter (phase 5 §2.4): only matching rows count against the bound, and a
     *                       batch without one is stored as watermark-only
     * @param countUnmatched mutant {@link WatermarkMutant#INBOX_COUNTS_UNMATCHED}
     */
    Inbox(int capacity, boolean countUntilReleased, EventTypeFilter filter, boolean countUnmatched) {
        this(capacity, countUntilReleased, filter, null, countUnmatched);
    }

    /**
     * @param tenant the subscription's tenant filter (phase 8 §15 question 7), {@code null} for every tenant: a row of
     *               another tenant does not match
     */
    Inbox(int capacity, boolean countUntilReleased, EventTypeFilter filter, String tenant, boolean countUnmatched) {
        requireTrue(capacity > 0, "capacity must be > 0");
        this.capacity = capacity;
        this.countUntilReleased = countUntilReleased;
        this.filter = requireNonNull(filter, "No filter provided");
        this.countUnmatched = countUnmatched;
        this.tenant = tenant;
    }

    /** @return the rows of {@code batch} that count against the bound */
    int counted(Batch batch) {
        if ((filter.matchesAll() && tenant == null) || countUnmatched) {
            return batch.size();
        }
        var n = 0;
        for (var row : batch.rows()) {
            if (filter.matches(row.eventType()) && (tenant == null || tenant.equals(row.tenant()))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Counting until released only: {@code n} events taken earlier are applied, parked or dropped and stop counting.
     * A no-op once closed.
     */
    void release(int n) {
        requireTrue(n >= 0, "n must be >= 0");
        if (!countUntilReleased || n == 0) {
            return;
        }
        lock.lock();
        try {
            if (closed == null) {
                events = Math.max(0, events - n);
            }
        } finally {
            lock.unlock();
        }
    }

    /** @return whether this inbox counts an event until {@link #release(int)} */
    boolean countsUntilReleased() {
        return countUntilReleased;
    }

    /**
     * Never blocks. On {@link Offer#FULL} the caller closes the inbox with {@link CloseReason#OVERFLOW}.
     */
    Offer offer(Batch batch) {
        requireNonNull(batch, "No batch provided");
        var counted = counted(batch);
        if (counted == 0 && !batch.watermarkOnly()) {
            // §2.4: nothing in it matches - a watermark-only batch, always accepted, merged into a trailing one
            batch = new Batch(java.util.List.of(), batch.watermark());
        }
        lock.lock();
        try {
            if (closed != null) {
                return Offer.CLOSED;
            }
            if (batch.watermarkOnly()) {
                var last = batches.peekLast();
                if (last != null && last.watermarkOnly()) {
                    batches.pollLast();
                    batch = new Batch(batch.rows(), Math.max(last.watermark(), batch.watermark()));
                }
            } else if (events > 0 && events + counted > capacity) {
                return Offer.FULL;
            }
            batches.addLast(batch);
            events += counted;
            changed.signalAll();
            return Offer.ACCEPTED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Take the oldest batch, waiting at most {@code timeoutNanos} for one.
     *
     * @return the batch, or {@code null} on timeout or once the inbox is closed
     */
    Batch take(long timeoutNanos) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            var remaining = timeoutNanos;
            while (closed == null && batches.isEmpty()) {
                if (remaining <= 0) {
                    return null;
                }
                consumerWaiting = true;
                try {
                    remaining = changed.awaitNanos(remaining);
                } finally {
                    consumerWaiting = false;
                }
            }
            if (closed != null) {
                return null;
            }
            var batch = batches.pollFirst();
            if (!countUntilReleased) {
                events -= counted(batch);
            }
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Close and discard what is held. Idempotent: the first reason stays.
     *
     * @return {@code true} if this call closed the inbox
     */
    boolean close(CloseReason reason) {
        requireNonNull(reason, "No reason provided");
        lock.lock();
        try {
            if (closed != null) {
                return false;
            }
            closed = reason;
            batches.clear();
            events = 0;
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Wake a consumer waiting in {@link #take(long)} without closing (it re-checks its own state) */
    void nudge() {
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** @return why the inbox was closed, or {@code null} while open */
    CloseReason closeReason() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** @return the events held (counting until released: and taken but not yet released), for the inbox-depth metric */
    int events() {
        lock.lock();
        try {
            return events;
        } finally {
            lock.unlock();
        }
    }

    /** @return the batches held */
    int batches() {
        lock.lock();
        try {
            return batches.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return whether the consumer waits in {@link #take(long)} on an empty open inbox: every batch offered so far
     * has been taken and worked through
     */
    boolean consumerIdle() {
        lock.lock();
        try {
            return consumerWaiting && batches.isEmpty() && closed == null;
        } finally {
            lock.unlock();
        }
    }
}
