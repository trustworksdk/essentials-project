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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.slf4j.*;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * In-memory registry of per-{@link EventStoreSubscription} runtime statistics, written by
 * {@link StatisticsCollectingEventStoreSubscriptionObserver} on the subscription hot path and read by
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.EventStoreApi}.
 * <p>
 * <b>Scope is this JVM only.</b> Unlike the resume points in
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.DurableSubscriptionRepository},
 * which are shared by every instance of the application through the database, these statistics cover the
 * subscriptions running in this instance. A caller that mixes the two must say which is which - see
 * {@link SubscriptionStatistics}.
 * <p>
 * Entries are created when a subscription is first observed and removed when it unsubscribes, so a subscriber id that
 * is created per request/per view rebuild does not leak. As a backstop against unbounded id spaces the registry stops
 * tracking new subscriptions once {@link #maxTrackedSubscriptions()} is reached, logging a single warning; already
 * tracked subscriptions keep recording.
 * <p>
 * All recording is done with {@link java.util.concurrent.atomic.LongAdder}s and plain volatile writes: no locks, no
 * allocation per event beyond the snapshot taken when a reader asks for it.
 */
public class SubscriptionStatisticsRegistry {
    /** Default upper bound on the number of concurrently tracked subscriptions. */
    public static final int DEFAULT_MAX_TRACKED_SUBSCRIPTIONS = 1000;

    private static final Logger log = LoggerFactory.getLogger(SubscriptionStatisticsRegistry.class);

    private final Clock                                                clock;
    private final int                                                  maxTrackedSubscriptions;
    private final ConcurrentMap<SubscriptionKey, MutableSubscriptionStatistics> statistics            = new ConcurrentHashMap<>();
    private final AtomicBoolean                                        capacityWarningLogged = new AtomicBoolean();

    /**
     * Create a registry using {@link #DEFAULT_MAX_TRACKED_SUBSCRIPTIONS} and the system UTC clock
     */
    public SubscriptionStatisticsRegistry() {
        this(DEFAULT_MAX_TRACKED_SUBSCRIPTIONS, Clock.systemUTC());
    }

    /**
     * @param maxTrackedSubscriptions the maximum number of concurrently tracked subscriptions - must be &gt; 0
     * @param clock                   the clock used for all timestamps recorded
     */
    public SubscriptionStatisticsRegistry(int maxTrackedSubscriptions, Clock clock) {
        requireTrue(maxTrackedSubscriptions > 0, "maxTrackedSubscriptions must be greater than 0");
        this.maxTrackedSubscriptions = maxTrackedSubscriptions;
        this.clock = requireNonNull(clock, "No clock provided");
    }

    /**
     * Identifies the statistics of a single subscription. A {@link SubscriberId} may subscribe to more than one
     * {@link AggregateType}, so both are part of the key - matching
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager#getSubscription(SubscriberId, AggregateType)}
     *
     * @param subscriberId  the subscriber
     * @param aggregateType the aggregate type subscribed to
     */
    public record SubscriptionKey(SubscriberId subscriberId, AggregateType aggregateType) {
        public SubscriptionKey {
            requireNonNull(subscriberId, "No subscriberId provided");
            requireNonNull(aggregateType, "No aggregateType provided");
        }

        public static SubscriptionKey of(EventStoreSubscription subscription) {
            requireNonNull(subscription, "No subscription provided");
            return new SubscriptionKey(subscription.subscriberId(), subscription.aggregateType());
        }
    }

    /**
     * Find the statistics collected in this JVM for the given subscription
     *
     * @param subscriberId  the subscriber
     * @param aggregateType the aggregate type subscribed to
     * @return the statistics, or {@link Optional#empty()} if the subscription has not been observed in this JVM
     */
    public Optional<SubscriptionStatistics> findStatistics(SubscriberId subscriberId, AggregateType aggregateType) {
        return Optional.ofNullable(statistics.get(new SubscriptionKey(subscriberId, aggregateType)))
                       .map(MutableSubscriptionStatistics::snapshot);
    }

    /**
     * Snapshot the statistics of every subscription observed in this JVM
     *
     * @return the statistics, in no particular order
     */
    public List<SubscriptionStatistics> allStatistics() {
        return statistics.values().stream()
                         .map(MutableSubscriptionStatistics::snapshot)
                         .toList();
    }

    /**
     * Stop tracking the given subscription and discard its statistics.<br>
     * Called when a subscription unsubscribes - a subscription that is only stopped keeps its statistics, since it can
     * be started again.
     *
     * @param subscriberId  the subscriber
     * @param aggregateType the aggregate type subscribed to
     */
    public void remove(SubscriberId subscriberId, AggregateType aggregateType) {
        statistics.remove(new SubscriptionKey(subscriberId, aggregateType));
    }

    /**
     * Discard all collected statistics
     */
    public void clear() {
        statistics.clear();
    }

    /**
     * @return the number of subscriptions currently tracked
     */
    public int trackedSubscriptions() {
        return statistics.size();
    }

    /**
     * @return the maximum number of subscriptions this registry tracks concurrently
     */
    public int maxTrackedSubscriptions() {
        return maxTrackedSubscriptions;
    }

    /**
     * Resolve the mutable statistics to record into, creating them on first observation.
     *
     * @param key the subscription to record for
     * @return the mutable statistics, or {@link Optional#empty()} if the registry is at capacity and the subscription
     * is not already tracked
     */
    Optional<MutableSubscriptionStatistics> statisticsFor(SubscriptionKey key) {
        var existing = statistics.get(key);
        if (existing != null) {
            return Optional.of(existing);
        }
        if (statistics.size() >= maxTrackedSubscriptions) {
            if (capacityWarningLogged.compareAndSet(false, true)) {
                log.warn("Tracking statistics for {} subscriptions, which is the configured maximum - statistics for further subscriptions are not collected. " +
                                 "This usually means subscriber ids are generated dynamically",
                         maxTrackedSubscriptions);
            }
            return Optional.empty();
        }
        return Optional.of(statistics.computeIfAbsent(key,
                                                      subscriptionKey -> new MutableSubscriptionStatistics(subscriptionKey, clock)));
    }
}
