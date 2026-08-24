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

package dk.trustworks.essentials.components.foundation.messaging.queue.stats;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * In-memory delivery statistics, fed by a {@link DurableQueueMessageObserver} and read by
 * {@link InMemoryDurableQueuesStatistics}.
 *
 * <h2>Per JVM, and that has to be said out loud</h2>
 * These counters cover the deliveries <b>this instance</b> performed. On a multi-instance deployment every
 * instance reports its own share, so a low number is not evidence of a slow queue and a zero is not evidence of a
 * stall — the same honesty problem {@code SubscriptionStatisticsRegistry} has on the event-store side. Anything
 * needing a cluster-wide figure should aggregate the Micrometer meters instead, which is what the durable
 * statistics table was really being used to approximate.
 *
 * <h2>Bounded on purpose</h2>
 * Two caps, because both keys are attacker- or accident-controlled in the sense that matters: a queue name comes
 * from application code and a {@link QueueEntryId} is unbounded by construction.
 * <ul>
 *     <li><b>Tracked queues</b> are capped ({@value #DEFAULT_MAX_TRACKED_QUEUES} by default). On reaching the cap
 *     further queues are not tracked and a single warning is logged — statistics must not be able to exhaust the
 *     heap of the process whose deliveries they are describing.</li>
 *     <li><b>Recent terminal records</b> are a bounded LRU ({@value #DEFAULT_MAX_RECENT_MESSAGES} by default) that
 *     serves {@link DurableQueuesStatistics#getQueueStatisticsMessage(QueueEntryId)}. That read is best-effort by
 *     construction: it can only answer for a message this instance recently finished with.</li>
 * </ul>
 */
public final class DurableQueuesStatisticsRegistry {
    private static final Logger log = LoggerFactory.getLogger(DurableQueuesStatisticsRegistry.class);

    public static final int DEFAULT_MAX_TRACKED_QUEUES = 500;
    public static final int DEFAULT_MAX_RECENT_MESSAGES = 1000;

    private final int                                            maxTrackedQueues;
    private final int                                            maxRecentMessages;
    private final ConcurrentMap<QueueName, MutableQueueStatistics> statisticsPerQueue = new ConcurrentHashMap<>();
    private final Map<QueueEntryId, QueuedStatisticsMessage>      recentMessages;
    private final AtomicBoolean                                  hasLoggedQueueCapReached = new AtomicBoolean();
    private final Instant                                        collectingSince;

    public DurableQueuesStatisticsRegistry() {
        this(DEFAULT_MAX_TRACKED_QUEUES, DEFAULT_MAX_RECENT_MESSAGES);
    }

    /**
     * @param maxTrackedQueues  how many distinct queues to track before refusing to track more
     * @param maxRecentMessages how many recently-finished messages to keep answerable by id
     */
    public DurableQueuesStatisticsRegistry(int maxTrackedQueues, int maxRecentMessages) {
        requireTrue(maxTrackedQueues > 0, "maxTrackedQueues must be > 0");
        requireTrue(maxRecentMessages >= 0, "maxRecentMessages must be >= 0");
        this.maxTrackedQueues = maxTrackedQueues;
        this.maxRecentMessages = maxRecentMessages;
        this.collectingSince = Instant.now();
        // Access-ordered so a repeatedly-read id stays resident, and synchronized because the delivery threads
        // write to it concurrently - a LinkedHashMap is not thread-safe even for reads while another thread
        // reorders it.
        this.recentMessages = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<QueueEntryId, QueuedStatisticsMessage> eldest) {
                return size() > DurableQueuesStatisticsRegistry.this.maxRecentMessages;
            }
        });
    }

    /**
     * @return when this registry started collecting, which is what bounds every figure it reports
     */
    public Instant collectingSince() {
        return collectingSince;
    }

    /**
     * An observer that records into this registry. Register it with the {@link DurableQueues} implementation.
     */
    public DurableQueueMessageObserver asObserver() {
        return DurableQueueMessageObserver.safe(new RegistryRecordingObserver());
    }

    public Optional<QueueStatistics> statisticsFor(QueueName queueName) {
        requireNonNull(queueName, "No queueName provided");
        return Optional.ofNullable(statisticsPerQueue.get(queueName)).map(statistics -> statistics.snapshot(queueName));
    }

    public Optional<QueuedStatisticsMessage> statisticsForMessage(QueueEntryId queueEntryId) {
        requireNonNull(queueEntryId, "No queueEntryId provided");
        return Optional.ofNullable(recentMessages.get(queueEntryId));
    }

    public Set<QueueName> trackedQueueNames() {
        return Set.copyOf(statisticsPerQueue.keySet());
    }

    /**
     * Drops everything collected so far. Intended for tests.
     */
    public void reset() {
        statisticsPerQueue.clear();
        recentMessages.clear();
    }

    private MutableQueueStatistics trackedStatisticsFor(QueueName queueName) {
        var existing = statisticsPerQueue.get(queueName);
        if (existing != null) {
            return existing;
        }
        if (statisticsPerQueue.size() >= maxTrackedQueues) {
            if (hasLoggedQueueCapReached.compareAndSet(false, true)) {
                log.warn("Tracking statistics for {} queues, which is the configured maximum - statistics for "
                                 + "further queues will not be collected. Deliveries are unaffected.", maxTrackedQueues);
            }
            return null;
        }
        return statisticsPerQueue.computeIfAbsent(queueName, ignored -> new MutableQueueStatistics());
    }

    private final class RegistryRecordingObserver implements DurableQueueMessageObserver {
        @Override
        public void messageHandled(QueuedMessage message, Duration handlerDuration) {
            var statistics = trackedStatisticsFor(message.getQueueName());
            if (statistics == null) {
                return;
            }
            // Latency measured from enqueue to delivery, which is the figure the durable table reported and the
            // one an operator asks for: how long a message waited, not how long the handler ran.
            var deliveredAt = message.getDeliveryTimestamp() != null
                              ? message.getDeliveryTimestamp().toInstant()
                              : Instant.now();
            var latencyMs = Math.max(0L, Duration.between(message.getAddedTimestamp().toInstant(), deliveredAt).toMillis());
            statistics.recordHandled(latencyMs, deliveredAt);
            rememberRecent(message, handlerDuration, latencyMs);
        }

        @Override
        public void messageRedeliveryRequested(QueuedMessage message) {
            var statistics = trackedStatisticsFor(message.getQueueName());
            if (statistics != null) {
                statistics.redeliveriesRequested.increment();
            }
        }

        @Override
        public void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {
            var statistics = trackedStatisticsFor(message.getQueueName());
            if (statistics != null) {
                statistics.retried.increment();
            }
        }

        @Override
        public void messageDeadLettered(QueuedMessage message, Throwable cause) {
            var statistics = trackedStatisticsFor(message.getQueueName());
            if (statistics != null) {
                statistics.deadLettered.increment();
            }
            rememberRecent(message, Duration.ZERO, 0L);
        }

        private void rememberRecent(QueuedMessage message, Duration handlerDuration, long latencyMs) {
            if (maxRecentMessages > 0) {
                recentMessages.put(message.getId(), new RecordedQueuedStatisticsMessage(message, handlerDuration, latencyMs));
            }
        }
    }

    /**
     * Hot-path counters. {@link LongAdder} rather than {@link AtomicLong} because every delivery thread writes and
     * only a reader ever sums.
     */
    private static final class MutableQueueStatistics {
        private final LongAdder handled               = new LongAdder();
        private final LongAdder deadLettered          = new LongAdder();
        private final LongAdder retried               = new LongAdder();
        private final LongAdder redeliveriesRequested = new LongAdder();
        private final LongAdder latencySumMs          = new LongAdder();

        private volatile Instant firstDelivery;
        private volatile Instant lastDelivery;

        void recordHandled(long latencyMs, Instant deliveredAt) {
            handled.increment();
            latencySumMs.add(latencyMs);
            if (firstDelivery == null) {
                // A benign race: two threads can both see null and write, and either value is a correct "first
                // delivery this instance saw" to within the resolution anyone reads it at. Not worth a CAS on the
                // delivery path.
                firstDelivery = deliveredAt;
            }
            lastDelivery = deliveredAt;
        }

        QueueStatistics snapshot(QueueName queueName) {
            var handledCount = handled.sum();
            var averageMs    = handledCount == 0 ? 0 : (int) (latencySumMs.sum() / handledCount);
            var first        = firstDelivery;
            var last         = lastDelivery;
            return new QueueStatistics(queueName,
                                       toOffsetDateTime(first),
                                       handledCount,
                                       averageMs,
                                       toOffsetDateTime(first),
                                       toOffsetDateTime(last));
        }
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * A finished delivery, kept only so {@link DurableQueuesStatistics#getQueueStatisticsMessage(QueueEntryId)} can
     * answer for a recent one. Copies the fields out of the {@link QueuedMessage} rather than holding it, so the
     * ring does not pin deserialized payloads in the heap.
     */
    private record RecordedQueuedStatisticsMessage(QueueEntryId id,
                                                   QueueName queueName,
                                                   OffsetDateTime addedTimestamp,
                                                   OffsetDateTime deliveryTimestamp,
                                                   OffsetDateTime deletionTimestamp,
                                                   Integer totalAttempts,
                                                   Integer redeliveryAttempts,
                                                   QueuedMessage.DeliveryMode deliveryMode,
                                                   MessageMetaData metaData,
                                                   long deliveryLatencyMs,
                                                   long handlerDurationMs) implements QueuedStatisticsMessage {

        RecordedQueuedStatisticsMessage(QueuedMessage message, Duration handlerDuration, long deliveryLatencyMs) {
            this(message.getId(),
                 message.getQueueName(),
                 message.getAddedTimestamp(),
                 message.getDeliveryTimestamp(),
                 // The row is removed from the queue as this is recorded, so "now" is the deletion timestamp. The
                 // trigger-based version read it from the deleted row for the same reason.
                 OffsetDateTime.now(ZoneOffset.UTC),
                 message.getTotalDeliveryAttempts(),
                 message.getRedeliveryAttempts(),
                 message.getDeliveryMode(),
                 message.getMetaData(),
                 deliveryLatencyMs,
                 handlerDuration.toMillis());
        }

        @Override
        public QueueEntryId getId() {
            return id;
        }

        @Override
        public QueueName getQueueName() {
            return queueName;
        }

        @Override
        public OffsetDateTime getAddedTimestamp() {
            return addedTimestamp;
        }

        @Override
        public OffsetDateTime getDeliveryTimestamp() {
            return deliveryTimestamp;
        }

        @Override
        public OffsetDateTime getDeletionTimestamp() {
            return deletionTimestamp;
        }

        @Override
        public Integer getTotalAttempts() {
            return totalAttempts;
        }

        @Override
        public Integer getRedeliveryAttempts() {
            return redeliveryAttempts;
        }

        @Override
        public QueuedMessage.DeliveryMode getDeliveryMode() {
            return deliveryMode;
        }

        @Override
        public MessageMetaData getMetaData() {
            return metaData;
        }
    }
}
