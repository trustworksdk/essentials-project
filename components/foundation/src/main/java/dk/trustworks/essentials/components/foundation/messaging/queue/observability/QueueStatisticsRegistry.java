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

package dk.trustworks.essentials.components.foundation.messaging.queue.observability;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.slf4j.*;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * In-memory registry of per-{@link QueueName} delivery statistics, written by
 * {@link StatisticsCollectingDurableQueueMessageObserver} on the delivery hot path and read by
 * {@link dk.trustworks.essentials.components.foundation.messaging.queue.api.DurableQueuesApi}.
 * <p>
 * <b>Scope is this JVM only.</b> Unlike the queued and dead-letter message counts, which live in the queue table
 * and are therefore shared by every instance of the application, these statistics cover the deliveries performed
 * by this instance. A caller that mixes the two must say which is which — see {@link QueueStatistics}.
 * <p>
 * Entries are created when a queue is first delivered from, and removed by {@link #remove(QueueName)} when a
 * consumer is cancelled. As a backstop against unbounded queue-name spaces the registry stops tracking new queues
 * once {@link #maxTrackedQueues()} is reached, logging a single warning; already tracked queues keep recording.
 * <p>
 * All recording is done with {@link java.util.concurrent.atomic.LongAdder}s and plain volatile writes: no locks,
 * no allocation per delivery beyond the snapshot taken when a reader asks for it.
 * <p>
 * Deliberately shaped like the event store's {@code SubscriptionStatisticsRegistry}, which the admin surface
 * presents alongside this one.
 */
public class QueueStatisticsRegistry {
    /** Default upper bound on the number of concurrently tracked queues. */
    public static final int DEFAULT_MAX_TRACKED_QUEUES = 1000;

    private static final Logger log = LoggerFactory.getLogger(QueueStatisticsRegistry.class);

    private final Clock                                          clock;
    private final int                                            maxTrackedQueues;
    private final ConcurrentMap<QueueName, MutableQueueStatistics> statistics            = new ConcurrentHashMap<>();
    private final AtomicBoolean                                  capacityWarningLogged = new AtomicBoolean();

    /**
     * Create a registry using {@link #DEFAULT_MAX_TRACKED_QUEUES} and the system UTC clock
     */
    public QueueStatisticsRegistry() {
        this(DEFAULT_MAX_TRACKED_QUEUES, Clock.systemUTC());
    }

    /**
     * @param maxTrackedQueues the maximum number of concurrently tracked queues - must be &gt; 0
     * @param clock            the clock used for all timestamps recorded
     */
    public QueueStatisticsRegistry(int maxTrackedQueues, Clock clock) {
        requireTrue(maxTrackedQueues > 0, "maxTrackedQueues must be greater than 0");
        this.maxTrackedQueues = maxTrackedQueues;
        this.clock = requireNonNull(clock, "No clock provided");
    }

    /**
     * Find the statistics collected in this JVM for the given queue
     *
     * @param queueName the queue
     * @return the statistics, or {@link Optional#empty()} if no message has been delivered from that queue in this JVM
     */
    public Optional<QueueStatistics> findStatistics(QueueName queueName) {
        requireNonNull(queueName, "No queueName provided");
        return Optional.ofNullable(statistics.get(queueName)).map(MutableQueueStatistics::snapshot);
    }

    /**
     * Snapshot the statistics of every queue delivered from in this JVM
     *
     * @return the statistics, in no particular order
     */
    public List<QueueStatistics> allStatistics() {
        return statistics.values().stream()
                         .map(MutableQueueStatistics::snapshot)
                         .toList();
    }

    /**
     * Stop tracking the given queue and discard its statistics
     *
     * @param queueName the queue
     */
    public void remove(QueueName queueName) {
        statistics.remove(requireNonNull(queueName, "No queueName provided"));
    }

    /**
     * Discard all collected statistics
     */
    public void clear() {
        statistics.clear();
    }

    /**
     * @return the number of queues currently tracked
     */
    public int trackedQueues() {
        return statistics.size();
    }

    /**
     * @return the maximum number of queues this registry tracks concurrently
     */
    public int maxTrackedQueues() {
        return maxTrackedQueues;
    }

    /**
     * Resolve the mutable statistics to record into, creating them on first delivery.
     *
     * @param queueName the queue to record for
     * @return the mutable statistics, or {@link Optional#empty()} if the registry is at capacity and the queue is
     * not already tracked
     */
    Optional<MutableQueueStatistics> statisticsFor(QueueName queueName) {
        var existing = statistics.get(queueName);
        if (existing != null) {
            return Optional.of(existing);
        }
        if (statistics.size() >= maxTrackedQueues) {
            if (capacityWarningLogged.compareAndSet(false, true)) {
                log.warn("Tracking statistics for {} queues, which is the configured maximum - statistics for " +
                                 "further queues are not collected. This usually means queue names are generated dynamically",
                         maxTrackedQueues);
            }
            return Optional.empty();
        }
        return Optional.of(statistics.computeIfAbsent(queueName, name -> new MutableQueueStatistics(name, clock)));
    }
}
