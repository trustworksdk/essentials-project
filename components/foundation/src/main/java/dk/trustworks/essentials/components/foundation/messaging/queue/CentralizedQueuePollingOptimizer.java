/*
 * Copyright 2021-2025 the original author or authors.
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

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
        boolean skip = now < next;
        log.trace("[{}] shouldSkipPolling? {} (now={}, nextAllowed={})",
                queueName, skip, now, next);
        return skip;
    }

    @Override
    public void queuePollingReturnedNoMessages() {
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
        currentDelayMs.set(initialDelayMs);
        nextAllowedPollEpochMs.set(Instant.now().toEpochMilli());
        log.trace("[{}] got message '{}' → reset delay to '{}' ms",
                queueName, queuedMessage.getId(), initialDelayMs);
    }

    @Override
    public void queuePollingReturnedMessages(List<QueuedMessage> queuedMessages) {
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

    @Override
    public String toString() {
        return "CentralizedQueuePollingOptimizer[" +
                "queue=" + queueName +
                ", currentDelay=" + currentDelayMs.get() +
                ", nextAllowed@" + nextAllowedPollEpochMs.get() + ']';
    }
}
