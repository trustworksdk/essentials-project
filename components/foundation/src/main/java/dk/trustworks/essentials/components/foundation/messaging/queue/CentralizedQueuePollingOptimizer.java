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

import org.slf4j.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link QueuePollingOptimizer} that backs off exponentially, with jitter, while polls come back empty and polls
 * again as soon as work is seen or a message known to this node becomes due - see {@link #messageScheduledFor(Instant)}.
 * <p>
 * <b>Subclassing:</b> the framework reports each poll through the overloads that carry the claim's time -
 * {@link #queuePollingReturnedNoMessages(Instant)} and {@link #queuePollingReturnedMessages(List, Instant)} - and never
 * through {@link #queuePollingReturnedNoMessages()} or {@link #queuePollingReturnedMessages(List)}. A subclass that
 * reacts to a poll result must override the {@code Instant} overloads; an override of only the forms without a claim
 * time is never called by the framework. Those forms are therefore deprecated on this class.
 */
public class CentralizedQueuePollingOptimizer implements QueuePollingOptimizer {
    private static final Logger log = LoggerFactory.getLogger(CentralizedQueuePollingOptimizer.class);

    private final QueueName queueName;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double backoffFactor;
    private final double jitterFraction;

    private final AtomicLong currentDelayMs = new AtomicLong();
    private final AtomicLong nextAllowedPollEpochMs = new AtomicLong();
    /**
     * When messages known to this node become deliverable - see {@link #messageScheduledFor(Instant)}. Bounded: past
     * {@link #MAX_TRACKED_DUE_TIMES} the latest are dropped, and those messages wait at most the backoff cap
     */
    private final ConcurrentSkipListSet<Long> dueAtEpochMs = new ConcurrentSkipListSet<>();
    private static final int MAX_TRACKED_DUE_TIMES = 1_000;
    /**
     * Only used when a caller reports a poll without the time its claim ran at (the {@link QueuePollingOptimizer}
     * methods without a {@code claimedAt}): due times this much older than the report are taken as looked for. With
     * the claim's time, due times are forgotten up to exactly that - a poll of many queues can reach a queue long after
     * its claim's {@code now} was read, so a fixed grace could forget a due time its claim had not yet seen
     */
    private static final long DUE_TIME_GRACE_MS = 250;

    /**
     * @param queueName       the queue this optimizer is for
     * @param initialDelayMs  base delay after seeing work (e.g. 100 ms)
     * @param maxDelayMs      maximum backoff cap (e.g. 30_000 ms)
     * @param backoffFactor   multiplier on empty polls (e.g. 2.0)
     * @param jitterFraction  ± fraction for jitter (e.g. 0.1 = ±10%)
     */
    public CentralizedQueuePollingOptimizer(QueueName queueName,
                                            long initialDelayMs,
                                            long maxDelayMs,
                                            double backoffFactor,
                                            double jitterFraction) {
        if (initialDelayMs <= 0) throw new IllegalArgumentException("initialDelayMs must be > 0");
        if (maxDelayMs < initialDelayMs) throw new IllegalArgumentException("maxDelayMs must be >= initialDelayMs");
        if (backoffFactor < 1.0) throw new IllegalArgumentException("backoffFactor must be >= 1.0");
        if (jitterFraction < 0.0 || jitterFraction >= 1.0)
            throw new IllegalArgumentException("jitterFraction must be in [0.0, 1.0)");

        this.queueName      = queueName;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs     = maxDelayMs;
        this.backoffFactor  = backoffFactor;
        this.jitterFraction = jitterFraction;

        this.currentDelayMs.set(initialDelayMs);
        this.nextAllowedPollEpochMs.set(0L);
    }

    @Override
    public boolean shouldSkipPolling() {
        long now = Instant.now().toEpochMilli();
        long next = nextAllowedPollEpochMs.get();
        // A message coming due ends the backoff early
        var earliestDue = dueAtEpochMs.ceiling(Long.MIN_VALUE);
        boolean skip = now < next && (earliestDue == null || now < earliestDue);
        log.trace("[{}] shouldSkipPolling? {} (now={}, nextAllowed={})",
                queueName, skip, now, next);
        return skip;
    }

    /**
     * Reports an empty poll without the time its claim ran at, by delegating to
     * {@link #queuePollingReturnedNoMessages(Instant)} with a claim time slightly in the past.
     *
     * @deprecated the framework only calls {@link #queuePollingReturnedNoMessages(Instant)}, so a subclass must override
     * that overload - an override of this method is never called by the framework. Callers should report the claim's
     * time through {@link #queuePollingReturnedNoMessages(Instant)} as well. Only deprecated on this class: the method
     * on {@link QueuePollingOptimizer} stays, as its {@code Instant} overload delegates to it for implementations that
     * do not track due times.
     */
    @Deprecated(since = "0.60.1")
    @Override
    public void queuePollingReturnedNoMessages() {
        queuePollingReturnedNoMessages(Instant.now().minusMillis(DUE_TIME_GRACE_MS));
    }

    @Override
    public void queuePollingReturnedNoMessages(Instant claimedAt) {
        forgetDueTimesSeenBy(claimedAt);
        long prevDelay = currentDelayMs.get();
        long rawNext = (long)(prevDelay * backoffFactor);
        long capped  = Math.min(maxDelayMs, rawNext);
        // apply jitter ±jitterFraction
        long jitter = (long)(capped * jitterFraction);
        long delta  = ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        long nextDelay = Math.max(initialDelayMs, capped + delta);

        currentDelayMs.set(nextDelay);
        long nextAllowed = Instant.now().toEpochMilli() + nextDelay;
        nextAllowedPollEpochMs.set(nextAllowed);

        log.trace("[{}] no messages → increasing delay '{}' → '{}' ms; nextAllowedPoll @ '{}'",
                queueName, prevDelay, nextDelay, nextAllowed);
    }

    @Override
    public void queuePollingReturnedMessage(QueuedMessage queuedMessage) {
        forgetDueTimesSeenBy(Instant.now().minusMillis(DUE_TIME_GRACE_MS));
        currentDelayMs.set(initialDelayMs);
        nextAllowedPollEpochMs.set(Instant.now().toEpochMilli());
        log.trace("[{}] got message '{}' → reset delay to '{}' ms",
                queueName, queuedMessage.getId(), initialDelayMs);
    }

    /**
     * Reports a poll that claimed messages without the time its claim ran at, by delegating to
     * {@link #queuePollingReturnedMessages(List, Instant)} with a claim time slightly in the past.
     *
     * @deprecated the framework only calls {@link #queuePollingReturnedMessages(List, Instant)}, so a subclass must
     * override that overload - an override of this method is never called by the framework. Callers should report the
     * claim's time through {@link #queuePollingReturnedMessages(List, Instant)} as well. Only deprecated on this class:
     * the method on {@link QueuePollingOptimizer} stays, as its {@code Instant} overload delegates to it for
     * implementations that do not track due times.
     */
    @Deprecated(since = "0.60.1")
    @Override
    public void queuePollingReturnedMessages(List<QueuedMessage> queuedMessages) {
        queuePollingReturnedMessages(queuedMessages, Instant.now().minusMillis(DUE_TIME_GRACE_MS));
    }

    @Override
    public void queuePollingReturnedMessages(List<QueuedMessage> queuedMessages, Instant claimedAt) {
        forgetDueTimesSeenBy(claimedAt);
        currentDelayMs.set(initialDelayMs);
        nextAllowedPollEpochMs.set(Instant.now().toEpochMilli());
        log.trace("[{}] got '{}' messages  → reset delay to '{}' ms",
                queueName, queuedMessages.size(), initialDelayMs);
    }

    @Override
    public void messageAdded(QueuedMessage queuedMessage) {
        currentDelayMs.set(initialDelayMs);
        nextAllowedPollEpochMs.set(Instant.now().toEpochMilli());
        log.trace("[{}] messageAdded '{}' → reset delay to '{}' ms",
                queueName, queuedMessage.getId(), initialDelayMs);
    }

    /**
     * Poll when a message on this queue becomes deliverable, rather than sleeping past it on the backoff. Retries,
     * delayed enqueues and delayed resurrections made by this node report their due time here; without it, a retry
     * on a queue that had backed off was delivered up to a whole backoff cap late.
     */
    @Override
    public void messageScheduledFor(Instant dueAt) {
        dueAtEpochMs.add(dueAt.toEpochMilli());
        while (dueAtEpochMs.size() > MAX_TRACKED_DUE_TIMES) {
            dueAtEpochMs.pollLast();
        }
    }

    /**
     * A claim run at {@code claimedAt} looked for every message due at or before it, found or not. Due times are kept
     * per millisecond, so one in the same millisecond as {@code claimedAt} may still lie after it and is kept for the
     * next poll
     */
    private void forgetDueTimesSeenBy(Instant claimedAt) {
        dueAtEpochMs.headSet(claimedAt.toEpochMilli(), false).clear();
    }

    @Override
    public String toString() {
        return "CentralizedQueuePollingOptimizer[" +
                "queue=" + queueName +
                ", currentDelay=" + currentDelayMs.get() +
                ", nextAllowed@" + nextAllowedPollEpochMs.get() + ']';
    }
}
