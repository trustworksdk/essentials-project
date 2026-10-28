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
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The {@link dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer} supports extending the polling sleep time (i.e. the time between calls to
 * {@link DurableQueues#getNextMessageReadyForDelivery(GetNextMessageReadyForDelivery)}) up until a given threshold for a given queue consumer
 * <p>
 * <b>Subclassing:</b> the framework reports an empty poll through {@link #queuePollingReturnedNoMessages(Instant)},
 * which carries the claim's time, and never through {@link #queuePollingReturnedNoMessages()}. A subclass that reacts
 * to an empty poll must override the {@code Instant} overload; an override of only the form without a claim time is
 * never called by the framework. That form is therefore deprecated on this class. A poll that claimed messages is
 * reported through {@link #queuePollingReturnedMessages(java.util.List, Instant)}, which this class does not override,
 * so the {@link QueuePollingOptimizer} default still forwards it to {@link #queuePollingReturnedMessages(java.util.List)}.
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
     * {@link DueTimes#MAX_TRACKED_DUE_TIMES} the latest are dropped, and those messages wait at most {@code maxDelayMs}
     */
    private final  DueTimes         dueTimes                = new DueTimes();

    /**
     * Create a new {@link dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer}
     *
     * @param consumeFromQueue the {@link ConsumeFromQueue} command
     * @param delayIncrementMs when ever {@link QueuePollingOptimizer#queuePollingReturnedNoMessages(Instant)} is called
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
        queuePollingReturnedNoMessages(DueTimes.assumedClaimTime());
    }

    @Override
    public void queuePollingReturnedNoMessages(Instant claimedAt) {
        dueTimes.forgetSeenBy(claimedAt);
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
        dueTimes.forgetSeenByNowMinusGrace();
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
        var now = Instant.now();
        if (dueTimes.hasDueBy(now.toEpochMilli())) {
            log.trace("[{}] {} - shouldSkipPolling: false - a message became deliverable at or before {}",
                      consumeFromQueue.queueName,
                      consumeFromQueue.consumerName,
                      now);
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
     * PostgreSQL and MongoDB {@code DurableQueues} report the due time of each retry, delayed enqueue and delayed
     * resurrection made by this node here; without it, a retry on a queue that had backed off was delivered up to
     * {@code maxDelayMs} late. The consumer polls on its polling interval and asks {@link #shouldSkipPolling()} each
     * time, so the message is claimed within one polling interval of its due time.
     */
    @Override
    public void messageScheduledFor(Instant dueAt) {
        dueTimes.schedule(dueAt);
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
