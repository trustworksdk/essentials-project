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

package dk.trustworks.essentials.components.foundation.messaging.queue.health;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.slf4j.*;
import org.springframework.boot.health.contributor.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Reports how many dead-letter messages are sitting in each queue, so that an operator looking at
 * {@code /actuator/health} can see them without going to the database or to the admin UI.
 *
 * <h2>Why this reports UP by default</h2>
 * A {@link HealthIndicator} bean is not only a signal. It contributes to the composite {@code /actuator/health}
 * status, and plenty of deployments point a Kubernetes readiness or liveness probe straight at that endpoint. An
 * indicator that went {@link Status#DOWN} on the first dead letter would therefore take working pods out of
 * service — or restart them — because one message could not be handled, leaving fewer consumers to drain the
 * queue behind it. That is a worse outcome than the dead letter.
 * <p>
 * So this indicator is registered by default but reports {@link Status#UP} regardless of the counts, until an
 * operator sets {@code essentials.durable-queues.health.dead-letter-threshold} to a positive number. Only then
 * can it report {@link Status#DOWN}, and only for a queue that has reached the threshold the operator chose.
 * This mirrors {@code CdcHealthIndicator}, which reports {@code DOWN} for a failed CDC subscription only when
 * the operator declared CDC mandatory with {@code CdcMode.REQUIRE}.
 * <p>
 * Alerting on dead letters as they happen does not need any of this and should not wait for a threshold: that is
 * what the {@code essentials.messaging.durable_queues.dead_lettered} counter published by
 * {@link dk.trustworks.essentials.components.foundation.messaging.queue.micrometer.MicrometerDurableQueueMessageObserver}
 * is for. A counter cannot actuate anything, so it is on unconditionally. This indicator answers the different
 * question of how many dead letters are sitting unresolved right now.
 *
 * <h2>Why the result is cached</h2>
 * Producing the answer costs one query for the queue names plus one count per queue, against the shared queue
 * table. Probes poll {@code /actuator/health} on a timer — every few seconds, from every pod — so computing it
 * per request would add database load proportional to probe frequency times pod count, for a number that does
 * not change meaningfully between probes. The result is therefore reused for
 * {@code essentials.durable-queues.health.cache-time-to-live}.
 *
 * <h2>When the counts cannot be read</h2>
 * Reports {@link Status#UNKNOWN} rather than {@code DOWN}. An unreachable database is not a statement about dead
 * letters, and it is already the subject of Spring Boot's own {@code DataSource} health indicator; reporting
 * {@code DOWN} here would fail probes twice for one fault. {@code UNKNOWN} does not affect the aggregated status
 * while any other contributor reports something definite.
 */
public final class DurableQueuesHealthIndicator implements HealthIndicator {
    /** The number of dead letters at or above which a queue makes this indicator report {@code DOWN}; {@code 0} disables that. */
    public static final String DETAIL_DEAD_LETTER_THRESHOLD = "deadLetterThreshold";
    /** Dead letters across every queue. */
    public static final String DETAIL_TOTAL_DEAD_LETTER_MESSAGES = "totalDeadLetterMessages";
    /** Per-queue dead-letter counts, queues with none omitted. */
    public static final String DETAIL_DEAD_LETTER_MESSAGES_PER_QUEUE = "deadLetterMessagesPerQueue";
    /** The queues that have reached the threshold, i.e. the reason the status is {@code DOWN}. */
    public static final String DETAIL_QUEUES_AT_OR_ABOVE_THRESHOLD = "queuesAtOrAboveThreshold";
    /** Why the counts could not be read, present only when the status is {@code UNKNOWN}. */
    public static final String DETAIL_ERROR = "error";

    private static final Logger log = LoggerFactory.getLogger(DurableQueuesHealthIndicator.class);

    private final DurableQueues durableQueues;
    private final long          deadLetterThreshold;
    private final long          cacheTimeToLiveNanos;

    private final AtomicReference<CachedHealth> cachedHealth = new AtomicReference<>();

    /**
     * @param durableQueues       the queues to read dead-letter counts from
     * @param deadLetterThreshold the number of dead letters on a single queue at which this indicator reports
     *                            {@link Status#DOWN}. {@code 0} or negative means it never reports {@code DOWN},
     *                            which is the default — see the class javadoc for why
     * @param cacheTimeToLive     how long a computed result is reused before the counts are read again. Must not
     *                            be negative; {@link Duration#ZERO} reads on every call
     */
    public DurableQueuesHealthIndicator(DurableQueues durableQueues,
                                                  long deadLetterThreshold,
                                                  Duration cacheTimeToLive) {
        this.durableQueues = requireNonNull(durableQueues, "No durableQueues instance provided");
        requireNonNull(cacheTimeToLive, "No cacheTimeToLive provided");
        requireTrue(!cacheTimeToLive.isNegative(), "cacheTimeToLive must not be negative");
        this.deadLetterThreshold = deadLetterThreshold;
        this.cacheTimeToLiveNanos = cacheTimeToLive.toNanos();
    }

    @Override
    public Health health() {
        var now    = System.nanoTime();
        var cached = cachedHealth.get();
        if (cached != null && now - cached.computedAtNanos() < cacheTimeToLiveNanos) {
            return cached.health();
        }
        var health = computeHealth();
        cachedHealth.set(new CachedHealth(health, now));
        return health;
    }

    private Health computeHealth() {
        var deadLettersPerQueue = new TreeMap<String, Long>();
        var total               = 0L;
        try {
            for (var queueName : durableQueues.getQueueNames()) {
                var deadLetters = durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName);
                total += deadLetters;
                if (deadLetters > 0) {
                    deadLettersPerQueue.put(queueName.toString(), deadLetters);
                }
            }
        } catch (Exception e) {
            log.debug("Could not read dead-letter counts for the health indicator", e);
            return Health.status(Status.UNKNOWN)
                         .withDetail(DETAIL_DEAD_LETTER_THRESHOLD, deadLetterThreshold)
                         .withDetail(DETAIL_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage())
                         .build();
        }

        var queuesAtOrAboveThreshold = deadLetterThreshold <= 0
                                       ? List.<String>of()
                                       : deadLettersPerQueue.entrySet()
                                                            .stream()
                                                            .filter(entry -> entry.getValue() >= deadLetterThreshold)
                                                            .map(Map.Entry::getKey)
                                                            .toList();

        return Health.status(queuesAtOrAboveThreshold.isEmpty() ? Status.UP : Status.DOWN)
                     .withDetail(DETAIL_DEAD_LETTER_THRESHOLD, deadLetterThreshold)
                     .withDetail(DETAIL_TOTAL_DEAD_LETTER_MESSAGES, total)
                     .withDetail(DETAIL_DEAD_LETTER_MESSAGES_PER_QUEUE, deadLettersPerQueue)
                     .withDetail(DETAIL_QUEUES_AT_OR_ABOVE_THRESHOLD, queuesAtOrAboveThreshold)
                     .build();
    }

    @Override
    public String toString() {
        return "DurableQueuesHealthIndicator{deadLetterThreshold=" + deadLetterThreshold + "}";
    }

    private record CachedHealth(Health health, long computedAtNanos) {
    }
}
