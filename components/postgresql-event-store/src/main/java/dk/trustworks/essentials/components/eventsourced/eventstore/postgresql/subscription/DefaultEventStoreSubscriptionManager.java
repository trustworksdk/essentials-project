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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.components.foundation.types.Tenant;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import dk.trustworks.essentials.shared.functional.CheckedRunnable;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.shared.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Default implementation of the {@link EventStoreSubscriptionManager} interface that uses the {@link EventStore#getEventStoreSubscriptionObserver()}
 * to track {@link EventStoreSubscription} statistics
 */
public class DefaultEventStoreSubscriptionManager implements EventStoreSubscriptionManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultEventStoreSubscriptionManager.class);

    private final EventStore eventStore;
    private final FencedLockManager fencedLockManager;
    private final DurableSubscriptionRepository durableSubscriptionRepository;
    private final Duration snapshotResumePointsEvery;

    private final ConcurrentMap<Pair<SubscriberId, AggregateType>, EventStoreSubscription> subscribers = new ConcurrentHashMap<>();
    private volatile boolean started;
    private ScheduledFuture<?> saveResumePointsFuture;
    private final boolean startLifeCycles;
    private ScheduledExecutorService resumePointsScheduledExecutorService;
    private final EventStoreSubscriptionObserver eventStoreSubscriptionObserver;
    private final EventStoreSubscriptionManagerSettings eventStoreSubscriptionManagerSettings;

    public DefaultEventStoreSubscriptionManager(EventStore eventStore,
                                                int eventStorePollingBatchSize,
                                                Duration eventStorePollingInterval,
                                                FencedLockManager fencedLockManager,
                                                Duration snapshotResumePointsEvery,
                                                DurableSubscriptionRepository durableSubscriptionRepository,
                                                boolean startLifeCycles) {
        requireTrue(eventStorePollingBatchSize >= 1, "eventStorePollingBatchSize must be >= 1");
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        requireNonNull(eventStorePollingInterval, "No eventStorePollingInterval provided");
        this.fencedLockManager = requireNonNull(fencedLockManager, "No fencedLockManager provided");
        this.durableSubscriptionRepository = requireNonNull(durableSubscriptionRepository, "No durableSubscriptionRepository provided");
        this.snapshotResumePointsEvery = requireNonNull(snapshotResumePointsEvery, "No snapshotResumePointsEvery provided");
        this.eventStoreSubscriptionObserver = eventStore.getEventStoreSubscriptionObserver();
        this.startLifeCycles = startLifeCycles;
        this.eventStoreSubscriptionManagerSettings = new EventStoreSubscriptionManagerSettings(eventStorePollingBatchSize,
                eventStorePollingInterval,
                snapshotResumePointsEvery);

        log.info("[{}] Using {} using {} with snapshotResumePointsEvery: {}, eventStorePollingBatchSize: {}, eventStorePollingInterval: {}, " +
                        "eventStoreSubscriptionObserver: {}, startLifeCycles: {}",
                fencedLockManager.getLockManagerInstanceId(),
                fencedLockManager,
                durableSubscriptionRepository.getClass().getSimpleName(),
                snapshotResumePointsEvery,
                eventStorePollingBatchSize,
                eventStorePollingInterval,
                eventStoreSubscriptionObserver,
                startLifeCycles
        );
    }

    @Override
    public void start() {
        if (!startLifeCycles) {
            log.debug("Start of lifecycle beans is disabled");
            return;
        }
        if (!started) {
            log.info("[{}] Starting EventStore Subscription Manager", fencedLockManager.getLockManagerInstanceId());

            if (!fencedLockManager.isStarted()) {
                fencedLockManager.start();
            }

            resumePointsScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(ThreadFactoryBuilder.builder()
                    .nameFormat("EventStoreSubscriptionManager-SaveResumePoints-" + fencedLockManager.getLockManagerInstanceId() + "-%d")
                    .daemon(true)
                    .build());
            saveResumePointsFuture = resumePointsScheduledExecutorService
                    .scheduleAtFixedRate(this::saveResumePointsForAllSubscribers,
                            snapshotResumePointsEvery.toMillis(),
                            snapshotResumePointsEvery.toMillis(),
                            TimeUnit.MILLISECONDS);
            started = true;
            // Start any subscribers added prior to us starting
            subscribers.values().forEach(this::startEventStoreSubscriber);
        } else {
            log.debug("[{}] EventStore Subscription Manager was already started", fencedLockManager.getLockManagerInstanceId());
        }
    }

    private void startEventStoreSubscriber(EventStoreSubscription eventStoreSubscription) {
        log.debug("[{}] Starting EventStoreSubscription '{}': '{}'", fencedLockManager.getLockManagerInstanceId(), eventStoreSubscription.subscriberId(), eventStoreSubscription);
        eventStoreSubscriptionObserver.startingSubscriber(eventStoreSubscription);
        var startDuration = StopWatch.time(CheckedRunnable.safe(eventStoreSubscription::start));
        log.info("[{}] Started EventStoreSubscription '{}' in {} ms.", fencedLockManager.getLockManagerInstanceId(), eventStoreSubscription.subscriberId(), startDuration.toMillis());
        eventStoreSubscriptionObserver.startedSubscriber(eventStoreSubscription, startDuration);
    }

    private void stopEventStoreSubscriber(EventStoreSubscription eventStoreSubscription) {
        log.debug("[{}] Stopping EventStoreSubscription '{}': '{}'", fencedLockManager.getLockManagerInstanceId(), eventStoreSubscription.subscriberId(), eventStoreSubscription);
        eventStoreSubscriptionObserver.stoppingSubscriber(eventStoreSubscription);
        var stopDuration = StopWatch.time(CheckedRunnable.safe(eventStoreSubscription::stop));
        log.info("[{}] Stopped EventStoreSubscription '{}' in {} ms.", fencedLockManager.getLockManagerInstanceId(), eventStoreSubscription.subscriberId(), stopDuration.toMillis());
        eventStoreSubscriptionObserver.stoppedSubscriber(eventStoreSubscription, stopDuration);
    }

    @Override
    public void stop() {
        if (started) {
            log.info("[{}] Stopping EventStore Subscription Manager", fencedLockManager.getLockManagerInstanceId());
            subscribers.forEach((subscriberIdAggregateTypePair, eventStoreSubscription) -> stopEventStoreSubscriber(eventStoreSubscription));
            if (saveResumePointsFuture != null) {
                log.debug("[{}] Cancelling saveResumePointsFuture", fencedLockManager.getLockManagerInstanceId());
                saveResumePointsFuture.cancel(true);
                saveResumePointsFuture = null;
                log.debug("[{}] Cancelled saveResumePointsFuture", fencedLockManager.getLockManagerInstanceId());
            }
            if (resumePointsScheduledExecutorService != null) {
                log.debug("[{}] Shutting down resumePointsScheduledExecutorService", fencedLockManager.getLockManagerInstanceId());
                resumePointsScheduledExecutorService.shutdownNow();
                resumePointsScheduledExecutorService = null;
                log.debug("[{}] Shutdown resumePointsScheduledExecutorService", fencedLockManager.getLockManagerInstanceId());
            }
            if (fencedLockManager.isStarted()) {
                log.debug("[{}] Stopping fencedLockManager", fencedLockManager.getLockManagerInstanceId());
                fencedLockManager.stop();
            }

            started = false;
            log.info("[{}] Stopped EventStore Subscription Manager", fencedLockManager.getLockManagerInstanceId());
        } else {
            log.info("[{}] EventStore Subscription Manager was already stopped", fencedLockManager.getLockManagerInstanceId());
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public EventStore getEventStore() {
        return eventStore;
    }

    @Override
    public Set<Pair<SubscriberId, AggregateType>> getActiveSubscriptions() {
        return this.subscribers.entrySet().stream()
                .filter(e -> e.getValue().isActive())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    @Override
    public Optional<GlobalEventOrder> getCurrentEventOrder(SubscriberId subscriberId, AggregateType aggregateType) {
        return Optional.ofNullable(this.subscribers.get(Pair.of(subscriberId, aggregateType)))
                .flatMap(EventStoreSubscription::currentResumePoint)
                .map(SubscriptionResumePoint::getResumeFromAndIncluding);
    }

    private void saveResumePointsForAllSubscribers() {
        // TODO: Filter out active subscribers and decide if we can increment the global event order like when the subscriber stops.
        //   Current approach is safe with regards to reset of resume-points, but it will result in one overlapping event during resubscription
        //   related to a failed node or after a subscription manager failure (i.e. it doesn't run stop() at all or run to completion)
        try {
            durableSubscriptionRepository.saveResumePoints(subscribers.values()
                    .stream()
                    .filter(EventStoreSubscription::isActive)
                    .filter(eventStoreSubscription -> eventStoreSubscription.currentResumePoint().isPresent())
                    .map(eventStoreSubscription -> eventStoreSubscription.currentResumePoint().get())
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            if (IOExceptionUtil.isIOException(e)) {
                log.debug(msg("Failed to store ResumePoint's for the {} subscriber(s) - Experienced a Connection issue, this can happen during JVM or application shutdown", subscribers.size()));
            } else {
                log.error(msg("Failed to store ResumePoint's for the {} subscriber(s)", subscribers.size()), e);
            }
        }
    }

    private EventStoreSubscription addEventStoreSubscription(SubscriberId subscriberId,
                                                             AggregateType forAggregateType,
                                                             EventStoreSubscription eventStoreSubscription) {
        requireNonNull(subscriberId, "No subscriberId provided");
        requireNonNull(forAggregateType, "No forAggregateType provided");
        requireNonNull(eventStoreSubscription, "No eventStoreSubscription provided");

        var previousEventStoreSubscription = subscribers.putIfAbsent(
                Pair.of(subscriberId, forAggregateType),
                eventStoreSubscription);
        if (previousEventStoreSubscription == null) {
            log.info("[{}-{}] Added {} event store subscription",
                    subscriberId,
                    forAggregateType,
                    eventStoreSubscription.getClass().getSimpleName());
            if (started && !eventStoreSubscription.isStarted()) {
                startEventStoreSubscriber(eventStoreSubscription);
            }
            return eventStoreSubscription;
        } else {
            log.info("[{}-{}] Event Store subscription was already added",
                    subscriberId,
                    forAggregateType);
            return previousEventStoreSubscription;
        }
    }

    @Override
    public EventStoreSubscription subscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                           AggregateType forAggregateType,
                                                                           GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                           Optional<Tenant> onlyIncludeEventsForTenant,
                                                                           PersistedEventHandler eventHandler) {
        requireNonNull(onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder, "No onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder provided");
        requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant option provided");
        requireNonNull(eventHandler, "No eventHandler provided");
        return addEventStoreSubscription(subscriberId,
                forAggregateType,
                new NonExclusiveAsynchronousSubscription(eventStore,
                        durableSubscriptionRepository,
                        forAggregateType,
                        subscriberId,
                        onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                        onlyIncludeEventsForTenant,
                        eventHandler,
                        eventStoreSubscriptionObserver,
                        eventStoreSubscriptionManagerSettings,
                        this::unsubscribe));
    }

    @Override
    public EventStoreSubscription batchSubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                                AggregateType forAggregateType,
                                                                                GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                Optional<Tenant> onlyIncludeEventsForTenant,
                                                                                int maxBatchSize,
                                                                                Duration maxLatency,
                                                                                BatchedPersistedEventHandler eventHandler) {
        requireNonNull(onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder, "No onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder provided");
        requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant option provided");
        requireNonNull(eventHandler, "No eventHandler provided");
        return addEventStoreSubscription(subscriberId,
                forAggregateType,
                new NonExclusiveBatchedAsynchronousSubscription(eventStore,
                        durableSubscriptionRepository,
                        forAggregateType,
                        subscriberId,
                        onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                        onlyIncludeEventsForTenant,
                        maxBatchSize,
                        maxLatency,
                        eventHandler,
                        eventStoreSubscriptionObserver,
                        eventStoreSubscriptionManagerSettings,
                        this::unsubscribe));
    }

    @Override
    public EventStoreSubscription exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                                      AggregateType forAggregateType,
                                                                                      Function<AggregateType, GlobalEventOrder> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                      Optional<Tenant> onlyIncludeEventsForTenant,
                                                                                      FencedLockAwareSubscriber fencedLockAwareSubscriber,
                                                                                      PersistedEventHandler eventHandler) {
        requireNonNull(onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder, "No onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder provided");
        requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant option provided");
        requireNonNull(eventHandler, "No eventHandler provided");
        return addEventStoreSubscription(subscriberId,
                forAggregateType,
                new ExclusiveAsynchronousSubscription(eventStore,
                        fencedLockManager,
                        durableSubscriptionRepository,
                        forAggregateType,
                        subscriberId,
                        onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                        onlyIncludeEventsForTenant,
                        fencedLockAwareSubscriber,
                        eventHandler,
                        eventStoreSubscriptionObserver,
                        eventStoreSubscriptionManagerSettings,
                        this::unsubscribe));
    }

    @Override
    public EventStoreSubscription exclusivelySubscribeToAggregateEventsInTransaction(SubscriberId subscriberId,
                                                                                     AggregateType forAggregateType,
                                                                                     Optional<Tenant> onlyIncludeEventsForTenant,
                                                                                     TransactionalPersistedEventHandler eventHandler) {
        requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant option provided");
        requireNonNull(eventHandler, "No eventHandler provided");

        return addEventStoreSubscription(subscriberId,
                forAggregateType,
                new ExclusiveInTransactionSubscription(eventStore,
                        fencedLockManager,
                        forAggregateType,
                        subscriberId,
                        onlyIncludeEventsForTenant,
                        eventHandler,
                        eventStoreSubscriptionObserver,
                        this::unsubscribe
                ));
    }

    @Override
    public EventStoreSubscription subscribeToAggregateEventsInTransaction(SubscriberId subscriberId,
                                                                          AggregateType forAggregateType,
                                                                          Optional<Tenant> onlyIncludeEventsForTenant,
                                                                          TransactionalPersistedEventHandler eventHandler) {
        requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant option provided");
        requireNonNull(eventHandler, "No eventHandler provided");
        return addEventStoreSubscription(subscriberId,
                forAggregateType,
                new NonExclusiveInTransactionSubscription(eventStore,
                        forAggregateType,
                        subscriberId,
                        onlyIncludeEventsForTenant,
                        eventHandler,
                        eventStoreSubscriptionObserver,
                        this::unsubscribe));
    }

    /**
     * Called by {@link EventStoreSubscription#unsubscribe()}
     *
     * @param eventStoreSubscription the eventstore subscription that's being stopped
     */
    @Override
    public void unsubscribe(EventStoreSubscription eventStoreSubscription) {
        requireNonNull(eventStoreSubscription, "No eventStoreSubscription provided");
        var removedSubscription = subscribers.remove(Pair.of(eventStoreSubscription.subscriberId(), eventStoreSubscription.aggregateType()));
        if (removedSubscription != null) {
            log.info("[{}-{}] Unsubscribing", removedSubscription.subscriberId(), removedSubscription.aggregateType());
            stopEventStoreSubscriber(eventStoreSubscription);
        }
    }

    @Override
    public boolean hasSubscription(SubscriberId subscriberId, AggregateType aggregateType) {
        return subscribers.containsKey(Pair.of(subscriberId, aggregateType));
    }
}
