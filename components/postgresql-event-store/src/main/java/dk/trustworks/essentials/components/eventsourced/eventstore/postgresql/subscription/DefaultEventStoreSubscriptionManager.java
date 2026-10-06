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
import dk.trustworks.essentials.components.foundation.lifecycle.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import dk.trustworks.essentials.shared.functional.CheckedRunnable;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.shared.time.StopWatch;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Default implementation of the {@link EventStoreSubscriptionManager} interface that uses the {@link EventStore#getEventStoreSubscriptionObserver()}
 * to track {@link EventStoreSubscription} statistics
 */
public class DefaultEventStoreSubscriptionManager implements EventStoreSubscriptionManager, ShutdownAware {
    private static final Logger   log                                       = LoggerFactory.getLogger(DefaultEventStoreSubscriptionManager.class);
    private static final Duration MIN_ADVANCED_RESUME_POINTS_CHECK_INTERVAL = Duration.ofMillis(50);
    private static final Duration MAX_ADVANCED_RESUME_POINTS_CHECK_INTERVAL = Duration.ofSeconds(1);

    private final EventStore                    eventStore;
    private final FencedLockManager             fencedLockManager;
    private final DurableSubscriptionRepository durableSubscriptionRepository;
    private final Duration                      snapshotResumePointsEvery;
    private final int                           snapshotResumePointsAfterEvents;

    private final    ConcurrentMap<Pair<SubscriberId, AggregateType>, EventStoreSubscription> subscribers = new ConcurrentHashMap<>();
    private volatile boolean                                                                  started;
    private          ScheduledFuture<?>                                                       saveResumePointsFuture;
    private          ScheduledFuture<?>                                                       saveAdvancedResumePointsFuture;
    private final    boolean                                                                  startLifeCycles;
    private          ScheduledExecutorService                                                 resumePointsScheduledExecutorService;
    private final    EventStoreSubscriptionObserver                                           eventStoreSubscriptionObserver;
    private final    EventStoreSubscriptionManagerSettings                                    eventStoreSubscriptionManagerSettings;
    private final    Function<String, EventStorePollingOptimizer>                             eventStorePollingOptimizerFactory;

    /**
     * Create an {@link EventStoreSubscriptionManagerBuilder} that names every argument.
     * <p>
     * Declared here as well as on {@link EventStoreSubscriptionManager} because a static interface method is not
     * inherited by the implementing class, so a caller holding this type would not otherwise find it.
     *
     * @return the builder
     */
    public static EventStoreSubscriptionManagerBuilder builder() {
        return EventStoreSubscriptionManager.builder();
    }

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
    DefaultEventStoreSubscriptionManager(EventStore eventStore,
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
             null,
             0);
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
     * @param snapshotResumePointsAfterEvents   save an active subscriber's resume point ahead of the next {@code snapshotResumePointsEvery} tick once it
     *                                          has advanced this many {@link GlobalEventOrder} positions since it was last saved; {@code 0} disables it.
     *                                          See {@link EventStoreSubscriptionManagerBuilder#setSnapshotResumePointsAfterEvents(int)}
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
    DefaultEventStoreSubscriptionManager(EventStore eventStore,
                                                int eventStorePollingBatchSize,
                                                Duration eventStorePollingInterval,
                                                FencedLockManager fencedLockManager,
                                                Duration snapshotResumePointsEvery,
                                                DurableSubscriptionRepository durableSubscriptionRepository,
                                                boolean startLifeCycles,
                                                Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory,
                                                int snapshotResumePointsAfterEvents) {
        this(eventStore,
             eventStorePollingBatchSize,
             eventStorePollingInterval,
             fencedLockManager,
             snapshotResumePointsEvery,
             durableSubscriptionRepository,
             startLifeCycles,
             eventStorePollingOptimizerFactory,
             snapshotResumePointsAfterEvents,
             SubscriptionErrorPolicy.defaultPolicy());
    }

    /**
     * Target of {@link EventStoreSubscriptionManagerBuilder#build()}. The other parameters are described on
     * {@link #DefaultEventStoreSubscriptionManager(EventStore, int, Duration, FencedLockManager, Duration, DurableSubscriptionRepository, boolean, Function, int)}
     *
     * @param subscriptionErrorPolicy what the asynchronous subscriptions do when their handler throws a non-I/O exception; must not be {@code null}
     */
    DefaultEventStoreSubscriptionManager(EventStore eventStore,
                                         int eventStorePollingBatchSize,
                                         Duration eventStorePollingInterval,
                                         FencedLockManager fencedLockManager,
                                         Duration snapshotResumePointsEvery,
                                         DurableSubscriptionRepository durableSubscriptionRepository,
                                         boolean startLifeCycles,
                                         Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory,
                                         int snapshotResumePointsAfterEvents,
                                         SubscriptionErrorPolicy subscriptionErrorPolicy) {
        requireNonNull(subscriptionErrorPolicy, "No subscriptionErrorPolicy provided");
        requireTrue(eventStorePollingBatchSize >= 1, "eventStorePollingBatchSize must be >= 1");
        requireTrue(snapshotResumePointsAfterEvents >= 0, "snapshotResumePointsAfterEvents must be >= 0");
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        requireNonNull(eventStorePollingInterval, "No eventStorePollingInterval provided");
        this.fencedLockManager = requireNonNull(fencedLockManager, "No fencedLockManager provided");
        this.durableSubscriptionRepository = requireNonNull(durableSubscriptionRepository, "No durableSubscriptionRepository provided");
        this.snapshotResumePointsEvery = requireNonNull(snapshotResumePointsEvery, "No snapshotResumePointsEvery provided");
        this.snapshotResumePointsAfterEvents = snapshotResumePointsAfterEvents;
        this.eventStoreSubscriptionObserver = eventStore.getEventStoreSubscriptionObserver();
        this.startLifeCycles = startLifeCycles;
        this.eventStoreSubscriptionManagerSettings = new EventStoreSubscriptionManagerSettings(eventStorePollingBatchSize,
                                                                                               eventStorePollingInterval,
                                                                                               snapshotResumePointsEvery,
                                                                                               subscriptionErrorPolicy);
        this.eventStorePollingOptimizerFactory = eventStorePollingOptimizerFactory != null ? eventStorePollingOptimizerFactory : this::createEventStorePollingOptimizer;

        log.info("[{}] Using {} using {} with snapshotResumePointsEvery: {}, snapshotResumePointsAfterEvents: {}, eventStorePollingBatchSize: {}, eventStorePollingInterval: {}, " +
                         "eventStoreSubscriptionObserver: {}, startLifeCycles: {}, subscriptionErrorPolicy: {}",
                 fencedLockManager.getLockManagerInstanceId(),
                 fencedLockManager,
                 durableSubscriptionRepository.getClass().getSimpleName(),
                 snapshotResumePointsEvery,
                 snapshotResumePointsAfterEvents,
                 eventStorePollingBatchSize,
                 eventStorePollingInterval,
                 eventStoreSubscriptionObserver,
                 startLifeCycles,
                 subscriptionErrorPolicy
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
            if (snapshotResumePointsAfterEvents > 0) {
                // Scheduled on the same single thread as the periodic save, so the two never write concurrently
                var checkEvery = advancedResumePointsCheckInterval(snapshotResumePointsEvery);
                saveAdvancedResumePointsFuture = resumePointsScheduledExecutorService
                        .scheduleAtFixedRate(this::saveResumePointsThatAdvancedPastThreshold,
                                             checkEvery.toMillis(),
                                             checkEvery.toMillis(),
                                             TimeUnit.MILLISECONDS);
            }
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

    /**
     * Passed on to every subscription, which are not beans themselves: they are stopped from several places - an event
     * processor's own {@code stop()}, a fenced lock's release callback, this manager's {@link #stop()} - usually before
     * this manager, and each saves its resume point on the way out
     */
    @Override
    public void shutdownStarting(ShutdownContext shutdown) {
        subscribers.values().forEach(subscription -> {
            if (subscription instanceof ShutdownAware shutdownAware) {
                shutdownAware.shutdownStarting(shutdown);
            }
        });
    }

    @Override
    public void stop() {
        if (started) {
            log.info("[{}] Stopping EventStore Subscription Manager", fencedLockManager.getLockManagerInstanceId());
            subscribers.forEach((subscriberIdAggregateTypePair, eventStoreSubscription) -> stopEventStoreSubscriber(eventStoreSubscription));
            if (saveAdvancedResumePointsFuture != null) {
                saveAdvancedResumePointsFuture.cancel(true);
                saveAdvancedResumePointsFuture = null;
            }
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

    /**
     * @return what the asynchronous subscriptions created by this manager do when their handler throws a non-I/O exception
     * @see EventStoreSubscriptionManagerBuilder#setSubscriptionErrorPolicy(SubscriptionErrorPolicy)
     */
    public SubscriptionErrorPolicy getSubscriptionErrorPolicy() {
        return eventStoreSubscriptionManagerSettings.subscriptionErrorPolicy();
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
        saveResumePointsOfActiveSubscribers(resumePoint -> true);
    }

    /**
     * Early save enabled by {@code snapshotResumePointsAfterEvents}: an in-memory check of every active subscriber that
     * writes only the resume points that advanced at least that many {@link GlobalEventOrder} positions since their last
     * save. A subscriber below the threshold - including every idle one - costs no database round-trip.
     */
    private void saveResumePointsThatAdvancedPastThreshold() {
        saveResumePointsOfActiveSubscribers(resumePoint -> resumePoint.unpersistedAdvance() >= snapshotResumePointsAfterEvents);
    }

    /**
     * Only ever called on the single resume-point scheduler thread. A save in flight here cannot overwrite a reset that
     * commits concurrently on another thread: the repository refuses a write from an older reposition epoch - see
     * {@link SubscriptionResumePoint} and S6 in docs/subscription-improvements.md
     */
    private void saveResumePointsOfActiveSubscribers(Predicate<SubscriptionResumePoint> filter) {
        try {
            var resumePoints = subscribers.values()
                                          .stream()
                                          .filter(EventStoreSubscription::isActive)
                                          .map(EventStoreSubscription::currentResumePoint)
                                          .flatMap(Optional::stream)
                                          .filter(filter)
                                          .collect(Collectors.toList());
            if (!resumePoints.isEmpty()) {
                durableSubscriptionRepository.saveResumePoints(resumePoints);
            }
        } catch (Exception e) {
            if (IOExceptionUtil.isIOException(e)) {
                log.debug(msg("Failed to store ResumePoint's for the {} subscriber(s) - Experienced a Connection issue, this can happen during JVM or application shutdown", subscribers.size()));
            } else {
                log.error(msg("Failed to store ResumePoint's for the {} subscriber(s)", subscribers.size()), e);
            }
        }
    }

    /**
     * How often the {@code snapshotResumePointsAfterEvents} threshold is checked: a tenth of {@code snapshotResumePointsEvery},
     * kept between 50 ms and 1 second - a long periodic interval must not make the threshold slow to act - and never less often
     * than the periodic save itself. The check is in memory, so its cadence costs no database round-trips
     */
    static Duration advancedResumePointsCheckInterval(Duration snapshotResumePointsEvery) {
        var checkEvery = snapshotResumePointsEvery.dividedBy(10);
        if (checkEvery.compareTo(MIN_ADVANCED_RESUME_POINTS_CHECK_INTERVAL) < 0) {
            checkEvery = MIN_ADVANCED_RESUME_POINTS_CHECK_INTERVAL;
        }
        if (checkEvery.compareTo(MAX_ADVANCED_RESUME_POINTS_CHECK_INTERVAL) > 0) {
            checkEvery = MAX_ADVANCED_RESUME_POINTS_CHECK_INTERVAL;
        }
        return checkEvery.compareTo(snapshotResumePointsEvery) > 0 ? snapshotResumePointsEvery : checkEvery;
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
            // Nothing is owed to a subscription that is gone - see EventStore#forgetGapMiddlesAwaitedInMemory
            eventStore.forgetGapMiddlesAwaitedInMemory(removedSubscription.subscriberId(), removedSubscription.aggregateType());
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
