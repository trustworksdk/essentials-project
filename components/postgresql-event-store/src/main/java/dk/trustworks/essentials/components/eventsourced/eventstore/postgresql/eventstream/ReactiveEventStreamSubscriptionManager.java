/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamPersistenceStrategy;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.reactive.EventBus;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.*;
import reactor.core.publisher.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Manages reactive event stream subscriptions by coordinating between PostgreSQL Listen/Notify
 * and the existing polling mechanism. This class provides immediate notifications for new events
 * while maintaining backward compatibility with the existing event store infrastructure.
 *
 * <p>Key features:
 * <ul>
 *   <li>Immediate notifications via PostgreSQL Listen/Notify for sub-millisecond latency</li>
 *   <li>Automatic fallback to polling during quiet periods or connection issues</li>
 *   <li>Per-AggregateType subscription management</li>
 *   <li>Shared MultiTableChangeListener for resource efficiency</li>
 * </ul>
 */
public class ReactiveEventStreamSubscriptionManager implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(ReactiveEventStreamSubscriptionManager.class);

    private final Jdbi                                       jdbi;
    private final JSONSerializer                             jsonSerializer;
    private final EventBus                                   eventBus;
    private final Duration                                   pollingInterval;
    private final AggregateEventStreamPersistenceStrategy<?> persistenceStrategy;
    private final EventStoreSubscriptionObserver             subscriptionObserver;

    private       MultiTableChangeListener<EventStreamChangeNotification> multiTableListener;
    private final AtomicBoolean                                           started = new AtomicBoolean(false);

    /**
     * Map of AggregateType to the table name for that aggregate type.
     * This is used to map table names back to AggregateTypes when notifications are received.
     */
    private final ConcurrentHashMap<String, AggregateType> tableNameToAggregateType = new ConcurrentHashMap<>();

    /**
     * Map of AggregateType to FluxSink for pushing notifications to reactive streams
     */
    private final ConcurrentHashMap<AggregateType, FluxSink<EventStreamChangeNotification>> aggregateTypeToSink = new ConcurrentHashMap<>();

    /**
     * Map of AggregateType to backoff strategy for intelligent polling
     */
    private final ConcurrentHashMap<AggregateType, ReactivePollingBackoffStrategy> aggregateTypeToBackoffStrategy = new ConcurrentHashMap<>();

    // Observability metrics
    private final AtomicLong                                   totalNotificationsReceived    = new AtomicLong(0);
    private final AtomicLong                                   totalNotificationsForwarded   = new AtomicLong(0);
    private final ConcurrentHashMap<AggregateType, AtomicLong> perAggregateNotificationCount = new ConcurrentHashMap<>();

    /**
     * Creates a new ReactiveEventStreamSubscriptionManager
     *
     * @param jdbi                 the JDBI instance for database operations
     * @param jsonSerializer       the JSON serializer for deserializing notifications
     * @param eventBus             the event bus for publishing notifications (shared with MultiTableChangeListener)
     * @param pollingInterval      the interval for the MultiTableChangeListener polling
     * @param persistenceStrategy  the persistence strategy to get table name mappings
     * @param subscriptionObserver the observer for subscription metrics and events
     */
    public ReactiveEventStreamSubscriptionManager(Jdbi jdbi,
                                                  JSONSerializer jsonSerializer,
                                                  EventBus eventBus,
                                                  Duration pollingInterval,
                                                  AggregateEventStreamPersistenceStrategy<?> persistenceStrategy,
                                                  EventStoreSubscriptionObserver subscriptionObserver) {
        this.jdbi = requireNonNull(jdbi, "No jdbi provided");
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
        this.eventBus = requireNonNull(eventBus, "No eventBus provided");
        this.pollingInterval = requireNonNull(pollingInterval, "No pollingInterval provided");
        this.persistenceStrategy = requireNonNull(persistenceStrategy, "No persistenceStrategy provided");
        this.subscriptionObserver = requireNonNull(subscriptionObserver, "No subscriptionObserver provided");
    }

    @Override
    public void start() {
        if (started.compareAndSet(false, true)) {
            log.info("Starting ReactiveEventStreamSubscriptionManager");

            // Create and start the shared MultiTableChangeListener
            multiTableListener = new MultiTableChangeListener<>(
                    jdbi,
                    pollingInterval,
                    jsonSerializer,
                    eventBus,
                    false
            );

            // Initialize table name to aggregate type mappings
            initializeTableMappings();

            // Subscribe to EventStreamChangeNotification events from the event bus
            eventBus.addAsyncSubscriber(event -> {
                if (event instanceof EventStreamChangeNotification) {
                    handleEventStreamNotification((EventStreamChangeNotification) event);
                }
            });

            multiTableListener.start();
            log.info("Started ReactiveEventStreamSubscriptionManager");
        }
    }

    @Override
    public void stop() {
        if (started.compareAndSet(true, false)) {
            log.info("Stopping ReactiveEventStreamSubscriptionManager");

            // Close all active sinks
            aggregateTypeToSink.values().forEach(sink -> {
                if (!sink.isCancelled()) {
                    sink.complete();
                }
            });
            aggregateTypeToSink.clear();

            if (multiTableListener != null) {
                multiTableListener.stop();
                multiTableListener = null;
            }

            log.info("Stopped ReactiveEventStreamSubscriptionManager");
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Creates a reactive stream of EventStreamChangeNotification for the given AggregateType.
     * This stream will receive immediate notifications when events are inserted into the
     * corresponding event stream table.
     *
     * @param aggregateType the aggregate type to subscribe to
     * @return a Flux of EventStreamChangeNotification events
     */
    public Flux<EventStreamChangeNotification> subscribeToAggregateType(AggregateType aggregateType) {
        requireNonNull(aggregateType, "No aggregateType provided");

        if (!started.get()) {
            throw new IllegalStateException("ReactiveEventStreamSubscriptionManager is not started");
        }

        return Flux.create(sink -> {
            log.debug("Creating reactive subscription for AggregateType: {}", aggregateType);

            // Register this sink for the aggregate type
            aggregateTypeToSink.put(aggregateType, sink);

            // Initialize backoff strategy for this aggregate type
            aggregateTypeToBackoffStrategy.putIfAbsent(aggregateType, new ReactivePollingBackoffStrategy());

            // Get the table name for this aggregate type
            String tableName = getTableNameForAggregateType(aggregateType);
            if (tableName != null) {
                // Start listening to notifications for this table
                multiTableListener.listenToNotificationsFor(tableName, EventStreamChangeNotification.class);
                log.debug("Started listening to table '{}' for AggregateType '{}'", tableName, aggregateType);

                // Log new subscription
                log.info("Started reactive subscription for AggregateType '{}' on table '{}'", aggregateType, tableName);
            } else {
                log.warn("Could not find table name for AggregateType: {}", aggregateType);
                sink.error(new IllegalArgumentException("No table found for AggregateType: " + aggregateType));
                return;
            }

            // Handle cancellation
            Runnable cancelLogic = () -> {
                log.debug("Cancelling reactive subscription for AggregateType: {}", aggregateType);
                aggregateTypeToSink.remove(aggregateType);

                // If no more subscribers for this aggregate type, stop listening to the table
                if (!aggregateTypeToSink.containsKey(aggregateType)) {
                    multiTableListener.unlistenToNotificationsFor(tableName);
                    aggregateTypeToBackoffStrategy.remove(aggregateType);
                    perAggregateNotificationCount.remove(aggregateType);
                    log.debug("Stopped listening to table '{}' for AggregateType '{}'", tableName, aggregateType);

                    // Log subscription cancellation
                    log.info("Cancelled reactive subscription for AggregateType '{}' on table '{}'", aggregateType, tableName);
                }
            };
            sink.onCancel(cancelLogic::run);
            sink.onDispose(cancelLogic::run);
        });
    }

    /**
     * Handles EventStreamChangeNotification events from the event bus and forwards them
     * to the appropriate reactive streams with observability integration.
     */
    private void handleEventStreamNotification(EventStreamChangeNotification notification) {
        try {
            totalNotificationsReceived.incrementAndGet();

            // Find the correct AggregateType by matching table names since EventStreamChangeNotification.getAggregateType()
            // derives the aggregate type from table name but might have case sensitivity issues
            AggregateType aggregateType = findAggregateTypeByTableName(notification.getTableName());
            if (aggregateType == null) {
                // Fallback to the original method if table mapping fails
                aggregateType = notification.getAggregateType();
            }

            FluxSink<EventStreamChangeNotification> sink = aggregateTypeToSink.get(aggregateType);

            // Update per-aggregate metrics
            perAggregateNotificationCount.computeIfAbsent(aggregateType, k -> new AtomicLong(0))
                                         .incrementAndGet();

            // Update backoff strategy
            ReactivePollingBackoffStrategy backoffStrategy = aggregateTypeToBackoffStrategy.get(aggregateType);
            if (backoffStrategy != null) {
                backoffStrategy.recordNotificationActivity();
            }

            if (sink != null && !sink.isCancelled()) {
                log.trace("Forwarding notification to reactive stream for AggregateType '{}': {}", aggregateType, notification);
                sink.next(notification);
                totalNotificationsForwarded.incrementAndGet();

                // Log successful notification forwarding at trace level to avoid noise
                log.trace("Forwarded notification for AggregateType '{}', total forwarded: {}", aggregateType, totalNotificationsForwarded.get());
            } else {
                log.trace("No active subscription for AggregateType '{}', ignoring notification", aggregateType);
            }
        } catch (Exception e) {
            log.error("Error handling EventStreamChangeNotification: {}", notification, e);
        }
    }

    /**
     * Initializes the mapping from table names to aggregate types using the persistence strategy
     */
    private void initializeTableMappings() {
        Map<AggregateType, String> tableNames = persistenceStrategy.getSeparateTablePerAggregateEventStreamTableNames();

        tableNames.forEach((aggregateType, tableName) -> {
            String normalized = tableName == null ? null : tableName.toLowerCase(java.util.Locale.ROOT);
            tableNameToAggregateType.put(normalized, aggregateType);
            log.debug("Mapped table '{}' to AggregateType '{}'", normalized, aggregateType);
        });

        log.info("Initialized {} table name mappings", tableNames.size());
    }

    /**
     * Gets the table name for the given aggregate type
     */
    private String getTableNameForAggregateType(AggregateType aggregateType) {
        Map<AggregateType, String> tableNames = persistenceStrategy.getSeparateTablePerAggregateEventStreamTableNames();
        String                     name       = tableNames.get(aggregateType);
        return name == null ? null : name.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Finds the AggregateType by table name using the pre-built mapping
     */
    private AggregateType findAggregateTypeByTableName(String tableName) {
        if (tableName == null) return null;
        return tableNameToAggregateType.get(tableName.toLowerCase(Locale.ROOT));
    }

    /**
     * Gets the backoff strategy for the given aggregate type.
     * This can be used to integrate with gap handling logic.
     *
     * @param aggregateType the aggregate type
     * @return the backoff strategy, or null if no subscription exists
     */
    public ReactivePollingBackoffStrategy getBackoffStrategy(AggregateType aggregateType) {
        return aggregateTypeToBackoffStrategy.get(aggregateType);
    }

    /**
     * Records activity for the backoff strategy when events are found via polling
     *
     * @param aggregateType the aggregate type
     * @param eventCount    the number of events found
     */
    public void recordPollingActivity(AggregateType aggregateType, int eventCount) {
        ReactivePollingBackoffStrategy strategy = aggregateTypeToBackoffStrategy.get(aggregateType);
        if (strategy != null) {
            strategy.recordActivity(eventCount);
        }
    }

    /**
     * Records empty poll results for backoff strategy
     *
     * @param aggregateType         the aggregate type
     * @param consecutiveEmptyCount the number of consecutive empty polls
     */
    public void recordEmptyPoll(AggregateType aggregateType, int consecutiveEmptyCount) {
        ReactivePollingBackoffStrategy strategy = aggregateTypeToBackoffStrategy.get(aggregateType);
        if (strategy != null) {
            strategy.recordEmptyPoll(consecutiveEmptyCount);
        }
    }

    /**
     * Gets observability metrics for the reactive subscription manager
     *
     * @return metrics about notification handling
     */
    public ReactiveSubscriptionMetrics getMetrics() {
        return new ReactiveSubscriptionMetrics(
                totalNotificationsReceived.get(),
                totalNotificationsForwarded.get(),
                Map.copyOf(perAggregateNotificationCount),
                aggregateTypeToSink.size(),
                Map.copyOf(aggregateTypeToBackoffStrategy)
        );
    }

    /**
     * Metrics class for reactive subscription observability
     */
    public static class ReactiveSubscriptionMetrics {
        public final long                                               totalNotificationsReceived;
        public final long                                               totalNotificationsForwarded;
        public final Map<AggregateType, AtomicLong>                     perAggregateNotificationCount;
        public final int                                                activeSubscriptions;
        public final Map<AggregateType, ReactivePollingBackoffStrategy> backoffStrategies;

        private ReactiveSubscriptionMetrics(long totalNotificationsReceived,
                                            long totalNotificationsForwarded,
                                            Map<AggregateType, AtomicLong> perAggregateNotificationCount,
                                            int activeSubscriptions,
                                            Map<AggregateType, ReactivePollingBackoffStrategy> backoffStrategies) {
            this.totalNotificationsReceived = totalNotificationsReceived;
            this.totalNotificationsForwarded = totalNotificationsForwarded;
            this.perAggregateNotificationCount = perAggregateNotificationCount;
            this.activeSubscriptions = activeSubscriptions;
            this.backoffStrategies = backoffStrategies;
        }

        @Override
        public String toString() {
            return "ReactiveSubscriptionMetrics{" +
                    "totalNotificationsReceived=" + totalNotificationsReceived +
                    ", totalNotificationsForwarded=" + totalNotificationsForwarded +
                    ", activeSubscriptions=" + activeSubscriptions +
                    ", perAggregateNotificationCount=" + perAggregateNotificationCount.size() +
                    '}';
        }
    }
}