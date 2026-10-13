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

import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import org.slf4j.*;

import java.time.Instant;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The {@link dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer} supports extending the polling sleep time (i.e. the time between calls to
 * {@link DurableQueues#getNextMessageReadyForDelivery(GetNextMessageReadyForDelivery)}) up until a given threshold for a given queue consumer
 */
public class SimpleQueuePollingOptimizer implements QueuePollingOptimizer {
    private static Logger           log                     = LoggerFactory.getLogger(dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer.class);
    private final  AtomicLong       pollingThreadDelay      = new AtomicLong();
    private final  AtomicLong       shouldSkipPollCallCount = new AtomicLong();
    private final  long             delayIncrementMs;
    private final  long             maxDelayMs;
    private final  ConsumeFromQueue consumeFromQueue;
    private final  long             pollingIntervalMs;
    /**
     * When messages known to this node become deliverable - see {@link #messageScheduledFor(Instant)}. Bounded: past
     * {@link #MAX_TRACKED_DUE_TIMES} the latest are dropped, and those messages wait at most {@code maxDelayMs}
     */
    private final  ConcurrentSkipListSet<Long> dueAtEpochMs = new ConcurrentSkipListSet<>();
    private static final int        MAX_TRACKED_DUE_TIMES = 1_000;
    /**
     * Only used when a caller reports a poll without the time its claim ran at (the {@link QueuePollingOptimizer}
     * methods without a {@code claimedAt}): due times this much older than the report are taken as looked for
     */
    private static final long       DUE_TIME_GRACE_MS     = 250;

    /**
     * Create a new {@link dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer}
     *
     * @param consumeFromQueue the {@link ConsumeFromQueue} command
     * @param delayIncrementMs when ever {@link QueuePollingOptimizer#queuePollingReturnedNoMessages()} is called
     *                         the delay between calls to {@link DurableQueues#getNextMessageReadyForDelivery(GetNextMessageReadyForDelivery)}
     *                         will be increased by <code>delayIncrementMs</code><br>
     *                         If {@link QueuePollingOptimizer#messageAdded(QueuedMessage)} or {@link QueuePollingOptimizer#queuePollingReturnedMessage(QueuedMessage)}
     *                         all called, then the delay between calls to {@link DurableQueues#getNextMessageReadyForDelivery(GetNextMessageReadyForDelivery)}
     *                         is reset back to {@link ConsumeFromQueue#getPollingInterval()}
     * @param maxDelayMs       The overall delay between calls to {@link DurableQueues#getNextMessageReadyForDelivery(GetNextMessageReadyForDelivery)}
     *                         cannot exceed this value
     */
    public SimpleQueuePollingOptimizer(ConsumeFromQueue consumeFromQueue,
                                       long delayIncrementMs,
                                       long maxDelayMs) {
        this.consumeFromQueue = consumeFromQueue;
        this.pollingIntervalMs = consumeFromQueue.getPollingInterval().toMillis();
        this.delayIncrementMs = delayIncrementMs;
        this.maxDelayMs = maxDelayMs;

        if (pollingIntervalMs == 0) {
            throw new IllegalArgumentException(msg("pollingIntervalMs {} is zero", pollingIntervalMs));
        }

        if (pollingIntervalMs > maxDelayMs) {
            throw new IllegalArgumentException(msg("pollingIntervalMs {} > maxDelayMs {}", pollingIntervalMs, maxDelayMs));
        }
        if (delayIncrementMs > maxDelayMs) {
            throw new IllegalArgumentException(msg("delayIncrementMs {} > maxDelayMs {}", delayIncrementMs, maxDelayMs));
        }
    }

    @Override
    public void queuePollingReturnedNoMessages() {
        queuePollingReturnedNoMessages(Instant.now().minusMillis(DUE_TIME_GRACE_MS));
    }

    @Override
    public void queuePollingReturnedNoMessages(Instant claimedAt) {
        forgetDueTimesSeenBy(claimedAt);
        var currentDelay = pollingThreadDelay.get();
        if (currentDelay < maxDelayMs) {
            var newDelayMs = currentDelay + delayIncrementMs;
            pollingThreadDelay.set(newDelayMs);
            log.trace("[{}] {} - queuePollingReturnedNoMessages - increasing pollingThreadDelay to {} ms",
                      consumeFromQueue.queueName,
                      consumeFromQueue.consumerName,
                      newDelayMs);
        } else {
            log.trace("[{}] {} - queuePollingReturnedNoMessages - retaining pollingThreadDelay at {} ms",
                      consumeFromQueue.queueName,
                      consumeFromQueue.consumerName,
                      currentDelay);
        }
        shouldSkipPollCallCount.set(0L);
    }

    @Override
    public void queuePollingReturnedMessage(QueuedMessage queuedMessage) {
        log.trace("[{}] {} - queuePollingReturnedMessage - resetting pollingThreadDelay: 0 ms",
                  consumeFromQueue.queueName,
                  consumeFromQueue.consumerName);
        // The delay is reset, so the next poll runs whatever is due by then
        forgetDueTimesSeenBy(Instant.now().minusMillis(DUE_TIME_GRACE_MS));
        pollingThreadDelay.set(0L);
        shouldSkipPollCallCount.set(0L);
    }

    @Override
    public boolean shouldSkipPolling() {
        var pollingDelay = pollingThreadDelay.get();
        if (pollingDelay == 0L) {
            log.trace("[{}] {} - shouldSkipPolling: false - pollingDelay: 0 ms",
                      consumeFromQueue.queueName,
                      consumeFromQueue.consumerName);
            return false;
        }
        // A message coming due ends the backoff early
        var earliestDue = dueAtEpochMs.ceiling(Long.MIN_VALUE);
        if (earliestDue != null && earliestDue <= Instant.now().toEpochMilli()) {
            log.trace("[{}] {} - shouldSkipPolling: false - a message became deliverable at {}",
                      consumeFromQueue.queueName,
                      consumeFromQueue.consumerName,
                      Instant.ofEpochMilli(earliestDue));
            return false;
        }
        var callCount = shouldSkipPollCallCount.get();
        callCount++;
        shouldSkipPollCallCount.set(callCount);
        if (pollingIntervalMs * callCount > pollingDelay) {
            log.trace("[{}] {} - shouldSkipPolling: false - pollingInterval: {} ms * callCount: {} > pollingDelay: {} ms",
                      consumeFromQueue.queueName,
                      consumeFromQueue.consumerName,
                      pollingIntervalMs,
                      callCount,
                      pollingDelay);
            return false;
        } else {
            log.trace("[{}] {} - shouldSkipPolling: true - pollingInterval: {} ms * callCount: {} <= pollingDelay: {} ms",
                      consumeFromQueue.queueName,
                      consumeFromQueue.consumerName,
                      pollingIntervalMs,
                      callCount,
                      pollingDelay);
            return true;
        }

    }

    @Override
    public void messageAdded(QueuedMessage queuedMessage) {
        log.trace("[{}] {} - messageAdded - resetting pollingThreadDelay: 0 ms and shouldSkipPollCallCount: 0. Message: {}",
                  consumeFromQueue.queueName,
                  consumeFromQueue.consumerName,
                  queuedMessage);
        pollingThreadDelay.set(0L);
        shouldSkipPollCallCount.set(0L);
    }

    /**
     * Poll when a message on this queue becomes deliverable, rather than sleeping past it on the backoff. The
     * PostgreSQL {@code DurableQueues} reports the due time of each retry, delayed enqueue and delayed resurrection made
     * by this node here; without it, a retry on a queue that had backed off was delivered up to {@code maxDelayMs}
     * late. The consumer polls on its
     * polling interval and asks {@link #shouldSkipPolling()} each time, so the message is claimed within one polling
     * interval of its due time.
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
        return "SimpleQueuePollingOptimizer{" +
                "pollingIntervalMs=" + pollingIntervalMs +
                ", delayIncrementMs=" + delayIncrementMs +
                ", maxDelayMs=" + maxDelayMs +
                ", consumeFromQueue=" + consumeFromQueue +
                '}';
    }
}
