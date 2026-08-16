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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import dk.trustworks.essentials.shared.functional.CheckedRunnable;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.shared.time.StopWatch;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Default implementation of the {@link EventStoreSubscriptionManager} interface that uses the {@link EventStore#getEventStoreSubscriptionObserver()}
 * to track {@link EventStoreSubscription} statistics
 */
public class DefaultEventStoreSubscriptionManager implements EventStoreSubscriptionManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultEventStoreSubscriptionManager.class);

    private final EventStore                    eventStore;
    private final FencedLockManager             fencedLockManager;
    private final DurableSubscriptionRepository durableSubscriptionRepository;
    private final Duration                      snapshotResumePointsEvery;

    private final    ConcurrentMap<Pair<SubscriberId, AggregateType>, EventStoreSubscription> subscribers = new ConcurrentHashMap<>();
    private volatile boolean                                                                  started;
    private          ScheduledFuture<?>                                                       saveResumePointsFuture;
    private final    boolean                                                                  startLifeCycles;
    private          ScheduledExecutorService                                                 resumePointsScheduledExecutorService;
    private final    EventStoreSubscriptionObserver                                           eventStoreSubscriptionObserver;
    private final    EventStoreSubscriptionManagerSettings                                    eventStoreSubscriptionManagerSettings;
    private final    Function<String, EventStorePollingOptimizer>                             eventStorePollingOptimizerFactory;

    /**
     * Constructs an instance of {@link DefaultEventStoreSubscriptionManager} that manages
     * subscriptions to an {@link EventStore}. This subscription manager handles event polling,
     * snapshot management, and lifecycle controls for event subscriptions.<br>
     * Uses the {@link JitteredEventStorePollingOptimizer} strategy.
     *
     * @param eventStore                        the {@link EventStore} to subscribe to; must not be {@code null}.
     * @param eventStorePollingBatchSize        the batch size for event polling; must be {@code >= 1}.
     * @param eventStorePollingInterval         the interval for polling the event store; must not be {@code null}.
     * @param fencedLockManager                 the {@link FencedLockManager} that handles locking mechanisms; must not be {@code null}.
     * @param snapshotResumePointsEvery         the interval for persisting snapshot resume points; must not be {@code null}.
     * @param durableSubscriptionRepository     the repository for managing durable subscriptions; must not be {@code null}.
     * @param startLifeCycles                   whether to immediately start the lifecycle of managed subscriptions.
     *
     *                                          <p>Example usage:</p>
     *                                          <pre>
     *                                          {@code
     *                                          EventStore eventStore = ...
     *                                          FencedLockManager lockManager = ...
     *                                          DurableSubscriptionRepository subscriptionRepository = ...
     *
     *                                          DefaultEventStoreSubscriptionManager manager = new DefaultEventStoreSubscriptionManager(
     *                                              eventStore,
     *                                              100,
     *                                              Duration.ofSeconds(10),
     *                                              lockManager,
     *                                              Duration.ofMinutes(10),
     *                                              subscriptionRepository,
     *                                              true
     *                                          );
     *                                          }
     *                                          </pre>
     */
    public DefaultEventStoreSubscriptionManager(EventStore eventStore,
                                                int eventStorePollingBatchSize,
                                                Duration eventStorePollingInterval,
                                                FencedLockManager fencedLockManager,
                                                Duration snapshotResumePointsEvery,
                                                DurableSubscriptionRepository durableSubscriptionRepository,
                                                boolean startLifeCycles) {
        this(eventStore,
             eventStorePollingBatchSize,
             eventStorePollingInterval,
             fencedLockManager,
             snapshotResumePointsEvery,
             durableSubscriptionRepository,
             startLifeCycles,
             null);
    }

    /**
     * Constructs an instance of {@link DefaultEventStoreSubscriptionManager} that manages
     * subscriptions to an {@link EventStore}. This subscription manager handles event polling,
     * snapshot management, and lifecycle controls for event subscriptions.
     *
     * @param eventStore                        the {@link EventStore} to subscribe to; must not be {@code null}.
     * @param eventStorePollingBatchSize        the batch size for event polling; must be {@code >= 1}.
     * @param eventStorePollingInterval         the interval for polling the event store; must not be {@code null}.
     * @param fencedLockManager                 the {@link FencedLockManager} that handles locking mechanisms; must not be {@code null}.
     * @param snapshotResumePointsEvery         the interval for persisting snapshot resume points; must not be {@code null}.
     * @param durableSubscriptionRepository     the repository for managing durable subscriptions; must not be {@code null}.
     * @param startLifeCycles                   whether to immediately start the lifecycle of managed subscriptions.
     * @param eventStorePollingOptimizerFactory a factory function to create {@link EventStorePollingOptimizer}'s<br>
     *                                          Input String parameter is the {@code eventStreamLogName} that is used label for logs (e.g., subscriberId+aggregateType).<br>
     *                                          Passing {@code null} causes the {@link DefaultEventStoreSubscriptionManager} to use the {@link JitteredEventStorePollingOptimizer} strategy.
     *
     *                                          <p>Example usage:</p>
     *                                          <pre>
     *                                          {@code
     *                                          EventStore eventStore = ...
     *                                          FencedLockManager lockManager = ...
     *                                          DurableSubscriptionRepository subscriptionRepository = ...
     *
     *                                          DefaultEventStoreSubscriptionManager manager = new DefaultEventStoreSubscriptionManager(
     *                                              eventStore,
     *                                              100,
     *                                              Duration.ofSeconds(10),
     *                                              lockManager,
     *                                              Duration.ofMinutes(10),
     *                                              subscriptionRepository,
     *                                              true,
     *                                              eventStreamLogName -> new SimpleEventStorePollingOptimizer(eventStreamLogName, ...)
     *                                          );
     *                                          }
     *                                          </pre>
     */
    public DefaultEventStoreSubscriptionManager(EventStore eventStore,
                                                int eventStorePollingBatchSize,
                                                Duration eventStorePollingInterval,
                                                FencedLockManager fencedLockManager,
                                                Duration snapshotResumePointsEvery,
                                                DurableSubscriptionRepository durableSubscriptionRepository,
                                                boolean startLifeCycles,
                                                Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory) {
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
        this.eventStorePollingOptimizerFactory = eventStorePollingOptimizerFactory != null ? eventStorePollingOptimizerFactory : this::createEventStorePollingOptimizer;

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

    /**
     * Creates a new instance of {@link EventStorePollingOptimizer} for optimizing the polling behavior
     * of the event store based on the provided event stream log name and the current subscription
     * manager settings.<br>
     * Default uses {@link JitteredEventStorePollingOptimizer}
     *
     * @param eventStreamLogName the name of the event stream log (usually a combination of subscriber ID
     *                           and aggregate type used for identification and logging purposes)
     * @return an instance of EventStorePollingOptimizer configured with jittered backoff logic
     * based on the polling interval and other settings
     */
    protected EventStorePollingOptimizer createEventStorePollingOptimizer(String eventStreamLogName) {
        return new JitteredEventStorePollingOptimizer(eventStreamLogName,
                                                      eventStoreSubscriptionManagerSettings.eventStorePollingInterval().toMillis(),
                                                      (long) (eventStoreSubscriptionManagerSettings.eventStorePollingInterval().toMillis() * 0.5d),
                                                      eventStoreSubscriptionManagerSettings.eventStorePollingInterval().toMillis() * 20,
                                                      0.1);
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
    public Set<Pair<SubscriberId, AggregateType>> getSubscriptions() {
        return Set.copyOf(this.subscribers.keySet());
    }

    @Override
    public Optional<EventStoreSubscription> getSubscription(SubscriberId subscriberId, AggregateType aggregateType) {
        requireNonNull(subscriberId, "No subscriberId provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        return Optional.ofNullable(subscribers.get(Pair.of(subscriberId, aggregateType)));
    }

    @Override
    public Optional<GlobalEventOrder> getCurrentEventOrder(SubscriberId subscriberId, AggregateType aggregateType) {
        return Optional.ofNullable(this.subscribers.get(Pair.of(subscriberId, aggregateType)))
                       .flatMap(EventStoreSubscription::currentResumePoint)
                       .map(SubscriptionResumePoint::getResumeFromAndIncluding);
    }

    private void saveResumePointsForAllSubscribers() {
        // Note (deliberate trade-off): this periodic crash-safety checkpoint persists each active
        // subscriber's CURRENT resume point verbatim. A graceful stop()/unsubscribe records a precise
        // boundary, but an ungraceful failure (node crash, or this manager dying before stop() runs to
        // completion) resumes from the last checkpoint and re-delivers exactly ONE already-processed
        // event. That is safe — delivery is at-least-once by contract, and the verbatim save is
        // conservative w.r.t. resume-point resets (it never advances past an unprocessed event).
        // Advancing the checkpoint for active subscribers (as stop() does) would trim that one-event
        // overlap, but risks skipping an in-flight event if the "is this boundary clean?" decision is
        // wrong — turning a harmless duplicate into a loss. Tracked as S4 in docs/subscription-improvements.md.
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
                                         new NonExclusiveAsynchronousSubscription(subscriptionContext(subscriberId, forAggregateType, onlyIncludeEventsForTenant),
                                                                                  DurableSubscriptionContext.fromFixedGlobalOrder(durableSubscriptionRepository,
                                                                                                                                 onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                                                                 eventStoreSubscriptionManagerSettings),
                                                                                  eventHandler));
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
                                         new NonExclusiveBatchedAsynchronousSubscription(subscriptionContext(subscriberId, forAggregateType, onlyIncludeEventsForTenant),
                                                                                         DurableSubscriptionContext.fromFixedGlobalOrder(durableSubscriptionRepository,
                                                                                                                                        onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                                                                        eventStoreSubscriptionManagerSettings),
                                                                                         maxBatchSize,
                                                                                         maxLatency,
                                                                                         eventHandler));
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
                                         new ExclusiveAsynchronousSubscription(subscriptionContext(subscriberId, forAggregateType, onlyIncludeEventsForTenant),
                                                                               new DurableSubscriptionContext(durableSubscriptionRepository,
                                                                                                              onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                                              eventStoreSubscriptionManagerSettings),
                                                                               fencedLockManager,
                                                                               fencedLockAwareSubscriber,
                                                                               eventHandler));
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
                                         new ExclusiveInTransactionSubscription(subscriptionContext(subscriberId, forAggregateType, onlyIncludeEventsForTenant),
                                                                                fencedLockManager,
                                                                                eventHandler));
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
                                         new NonExclusiveInTransactionSubscription(subscriptionContext(subscriberId, forAggregateType, onlyIncludeEventsForTenant),
                                                                                   eventHandler));
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

    /**
     * Assembles the {@link EventStoreSubscriptionContext} shared by every subscription this manager creates. This is
     * the one place the seven shared arguments are named, which is the point of the context: adding one is an edit
     * here rather than in all five subscription constructors.
     *
     * @param subscriberId               the durable identity of the subscriber
     * @param forAggregateType           the aggregate type being subscribed to
     * @param onlyIncludeEventsForTenant the tenant restriction, or empty for all tenants
     * @return the context
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private EventStoreSubscriptionContext subscriptionContext(SubscriberId subscriberId,
                                                              AggregateType forAggregateType,
                                                              Optional<Tenant> onlyIncludeEventsForTenant) {
        return EventStoreSubscriptionContext.builder()
                                            .setEventStore(eventStore)
                                            .setAggregateType(forAggregateType)
                                            .setSubscriberId(subscriberId)
                                            .setOnlyIncludeEventsForTenant(onlyIncludeEventsForTenant)
                                            .setEventStoreSubscriptionObserver(eventStoreSubscriptionObserver)
                                            .setUnsubscribeCallback(this::unsubscribe)
                                            .setEventStorePollingOptimizerFactory(eventStorePollingOptimizerFactory)
                                            .build();
    }

}
