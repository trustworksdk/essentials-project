/*
 *
 *  * Copyright 2021-2025 the original author or authors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.manager;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.time.StopWatch;
import org.slf4j.*;
import reactor.core.publisher.BaseSubscriber;
import reactor.util.retry.RetryBackoffSpec;

import java.util.Optional;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

public class ExclusiveAsynchronousSubscription implements EventStoreSubscription {
    private static final Logger log = LoggerFactory.getLogger(ExclusiveAsynchronousSubscription.class);

    private final EventStore                                eventStore;
    private final FencedLockManager                         fencedLockManager;
    private final DurableSubscriptionRepository             durableSubscriptionRepository;
    private final AggregateType                             aggregateType;
    private final SubscriberId                              subscriberId;
    private final Function<AggregateType, GlobalEventOrder> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder;
    private final Optional<Tenant>                          onlyIncludeEventsForTenant;
    private final FencedLockAwareSubscriber                 fencedLockAwareSubscriber;
    private final PersistedEventHandler                     eventHandler;
    private final LockName                                  lockName;
    private final EventStoreSubscriptionObserver            eventStoreSubscriptionObserver;
    private final EventStoreSubscriptionSettings            eventStoreSubscriptionSettings;

    private SubscriptionResumePoint        resumePoint;
    private BaseSubscriber<PersistedEvent> subscription;

    private volatile boolean started;
    private volatile boolean active;

    public ExclusiveAsynchronousSubscription(EventStore eventStore,
                                             FencedLockManager fencedLockManager,
                                             DurableSubscriptionRepository durableSubscriptionRepository,
                                             AggregateType aggregateType,
                                             SubscriberId subscriberId,
                                             Function<AggregateType, GlobalEventOrder> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                             Optional<Tenant> onlyIncludeEventsForTenant,
                                             FencedLockAwareSubscriber fencedLockAwareSubscriber,
                                             PersistedEventHandler eventHandler,
                                             EventStoreSubscriptionObserver eventStoreSubscriptionObserver, EventStoreSubscriptionSettings eventStoreSubscriptionSettings) {
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.fencedLockManager = requireNonNull(fencedLockManager, "No fencedLockManager provided");
        this.durableSubscriptionRepository = requireNonNull(durableSubscriptionRepository, "No durableSubscriptionRepository provided");
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.subscriberId = requireNonNull(subscriberId, "No subscriberId provided");
        this.onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder = requireNonNull(onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                      "No onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder provided");
        this.onlyIncludeEventsForTenant = requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant provided");
        this.fencedLockAwareSubscriber = requireNonNull(fencedLockAwareSubscriber, "No fencedLockAwareSubscriber provided");
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");
        this.eventStoreSubscriptionObserver = requireNonNull(eventStoreSubscriptionObserver, "No eventStoreSubscriptionObserver provided");
        this.eventStoreSubscriptionSettings = requireNonNull(eventStoreSubscriptionSettings, "No eventStoreSubscriptionSettings provided");
        lockName = LockName.of(msg("[{}-{}]", subscriberId, aggregateType));
    }

    @Override
    public SubscriberId subscriberId() {
        return subscriberId;
    }

    @Override
    public AggregateType aggregateType() {
        return aggregateType;
    }

    @Override
    public void start() {
        if (!started) {
            started = true;
            log.info("[{}-{}] Started subscriber",
                     subscriberId,
                     aggregateType);

            fencedLockManager.acquireLockAsync(lockName, new LockCallback() {
                @Override
                public void lockAcquired(FencedLock lock) {
                    log.info("[{}-{}] Acquired lock. Looking up subscription resumePoint",
                             subscriberId,
                             aggregateType);
                    active = true;
                    eventStoreSubscriptionObserver.lockAcquired(lock, EventStoreSubscriptionManager.DefaultEventStoreSubscriptionManager.ExclusiveAsynchronousSubscription.this);


                    var resolveResumePointTiming = StopWatch.start("resolveResumePoint (" + subscriberId + ", " + aggregateType + ")");
                    resumePoint = durableSubscriptionRepository.getOrCreateResumePoint(subscriberId,
                                                                                       aggregateType,
                                                                                       onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder);
                    log.info("[{}-{}] Starting subscription from globalEventOrder: {}",
                             subscriberId,
                             aggregateType,
                             resumePoint.getResumeFromAndIncluding());
                    eventStoreSubscriptionObserver.resolveResumePoint(resumePoint,
                                                                      onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder.apply(aggregateType),
                                                                      ExclusiveAsynchronousSubscription.this,
                                                                      resolveResumePointTiming.stop().getDuration());

                    try {
                        fencedLockAwareSubscriber.onLockAcquired(lock, resumePoint);
                    } catch (Exception e) {
                        log.error(msg("FencedLockAwareSubscriber#onLockAcquired failed for lock {} and resumePoint {}", lock.getName(), resumePoint), e);
                    }

                    subscription = new PersistedEventSubscriber(eventHandler,
                                                                                                                                   ExclusiveAsynchronousSubscription.this,
                                                                                                                                   ExclusiveAsynchronousSubscription.this::onErrorHandlingEvent,
                                                                                                                                   eventStoreSubscriptionSettings.eventStorePollingBatchSize(),
                                                                                                                                   eventStore);

                    eventStore.pollEvents(aggregateType,
                                          resumePoint.getResumeFromAndIncluding(),
                                          Optional.of(eventStoreSubscriptionSettings.eventStorePollingBatchSize()),
                                          Optional.of(eventStoreSubscriptionSettings.eventStorePollingInterval()),
                                          onlyIncludeEventsForTenant,
                                          Optional.of(subscriberId))
                              .limitRate(eventStoreSubscriptionSettings.eventStorePollingBatchSize())
                              .subscribe(subscription);
                }

                @Override
                public void lockReleased(FencedLock lock) {
                    if (!active) {
                        return;
                    }
                    log.info("[{}-{}] Lock Released. Stopping subscription",
                             subscriberId,
                             aggregateType);
                    try {
                        eventStoreSubscriptionObserver.lockReleased(lock, ExclusiveAsynchronousSubscription.this);
                        if (subscription != null) {
                            log.debug("[{}-{}] Stopping subscription flux",
                                      subscriberId,
                                      aggregateType);
                            subscription.dispose();
                            subscription = null;
                        } else {
                            log.debug("[{}-{}] Didn't find a subscription flux to dispose",
                                      subscriberId,
                                      aggregateType);
                        }
                    } catch (Exception e) {
                        log.error(msg("[{}-{}] Failed to dispose subscription flux",
                                      subscriberId,
                                      aggregateType), e);
                    }

                    try {
                        fencedLockAwareSubscriber.onLockReleased(lock);
                    } catch (Exception e) {
                        log.error(msg("FencedLockAwareSubscriber#onLockReleased failed for lock {}", lock.getName()), e);
                    }

                    try {
                        // Allow the reactive components to complete
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        // Ignore
                        Thread.currentThread().interrupt();
                    }

                    // Save resume point to be the next global order event AFTER the one we know we just handled
                    log.info("[{}-{}] Storing ResumePoint with resumeFromAndIncluding {}",
                             subscriberId,
                             aggregateType,
                             resumePoint.getResumeFromAndIncluding());

                    durableSubscriptionRepository.saveResumePoint(resumePoint);
                    active = false;
                    log.info("[{}-{}] Stopped subscription",
                             subscriberId,
                             aggregateType);
                }

            });
        } else {
            log.debug("[{}-{}] Subscription was already started",
                      subscriberId,
                      aggregateType);
        }
    }

    @Override
    public void request(long n) {
        if (!started) {
            log.warn("[{}-{}] Cannot request {} event(s) as the subscriber isn't active",
                     subscriberId,
                     aggregateType,
                     n);
            return;
        }
        if (!fencedLockManager.isLockedByThisLockManagerInstance(lockName)) {
            log.warn("[{}-{}] Cannot request {} event(s) as the subscriber hasn't acquired the lock",
                     subscriberId,
                     aggregateType,
                     n);
            return;
        }
        if (subscription == null) {
            log.info("[{}-{}] Cannot request {} event(s) as the subscriber is null - the exclusive subscription is shutting down",
                     subscriberId,
                     aggregateType,
                     n);
            return;
        }

        log.trace("[{}-{}] Requesting {} event(s)",
                  subscriberId,
                  aggregateType,
                  n);
        eventStoreSubscriptionObserver.requestingEvents(n, this);
        subscription.request(n);
    }

    /**
     * The error handler called for any non-retryable Exceptions (as specified by the {@link RetryBackoffSpec})<br><br>
     * <b>Note: Default behaviour needs to at least request one more event</b><br>
     * Similar to:
     * <pre>{@code
     * void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
     *      log.error(msg("[{}-{}] (#{}) Skipping {} event because of error",
     *                      subscriberId,
     *                      aggregateType,
     *                      e.globalEventOrder(),
     *                      e.event().getEventTypeOrName().getValue()), cause);
     *      log.trace("[{}-{}] (#{}) Requesting 1 event from the EventStore",
     *                  subscriberId(),
     *                  aggregateType(),
     *                  e.globalEventOrder()
     *                  );
     *      eventStoreSubscription.request(1);
     * }
     * }</pre>
     *
     * @param e     the event that failed
     * @param cause the cause of the failure
     */
    protected void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
        log.error(msg("[{}-{}] (#{}) Skipping {} event because of error",
                      subscriberId,
                      aggregateType,
                      e.globalEventOrder(),
                      e.event().getEventTypeOrName().getValue()), cause);
        log.trace("[{}-{}] (#{}) Requesting 1 event from the EventStore",
                  subscriberId(),
                  aggregateType(),
                  e.globalEventOrder()
                 );
        request(1);
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public void stop() {
        if (started) {
            fencedLockManager.cancelAsyncLockAcquiring(lockName);
            started = false;
        }
    }

    @Override
    public void unsubscribe() {
        log.info("[{}-{}] Initiating unsubscription",
                 subscriberId,
                 aggregateType);
        eventStoreSubscriptionObserver.unsubscribing(this);
        unsubscribe(this);
    }

    @Override
    public boolean isExclusive() {
        return true;
    }

    @Override
    public boolean isInTransaction() {
        return false;
    }

    @Override
    public void resetFrom(GlobalEventOrder subscribeFromAndIncludingGlobalOrder, Consumer<GlobalEventOrder> resetProcessor) {
        requireNonNull(subscribeFromAndIncludingGlobalOrder, "subscribeFromAndIncludingGlobalOrder must not be null");
        requireNonNull(resetProcessor, "resetProcessor must not be null");

        eventStoreSubscriptionObserver.resettingFrom(subscribeFromAndIncludingGlobalOrder, this);
        if (isStarted() && isActive()) {
            log.info("[{}-{}] Resetting resume point and re-starts the subscriber from and including globalOrder {}",
                     subscriberId,
                     aggregateType,
                     subscribeFromAndIncludingGlobalOrder);
            stop();
            overrideResumePoint(subscribeFromAndIncludingGlobalOrder);
            resetProcessor.accept(subscribeFromAndIncludingGlobalOrder);
            start();
        } else {
            log.info("[{}-{}] Cannot reset resume point to fromAndIncluding {} because the underlying lock hasn't been acquired. isStarted: {}, isActive (is-lock-acquired): {}",
                     subscriberId,
                     aggregateType,
                     subscribeFromAndIncludingGlobalOrder,
                     isStarted(),
                     isActive());

        }
    }

    private void overrideResumePoint(GlobalEventOrder subscribeFromAndIncludingGlobalOrder) {
        requireNonNull(subscribeFromAndIncludingGlobalOrder, "No subscribeFromAndIncludingGlobalOrder value provided");
        // Override resume point
        log.info("[{}-{}] Overriding resume point to start from-and-including-globalOrder {}",
                 subscriberId,
                 aggregateType,
                 subscribeFromAndIncludingGlobalOrder);
        resumePoint.setResumeFromAndIncluding(subscribeFromAndIncludingGlobalOrder);
        durableSubscriptionRepository.saveResumePoint(resumePoint);
        try {
            eventHandler.onResetFrom(this, subscribeFromAndIncludingGlobalOrder);
        } catch (Exception e) {
            log.info(msg("[{}-{}] Failed to reset eventHandler '{}' to use start from-and-including-globalOrder {}",
                         subscriberId,
                         aggregateType,
                         eventHandler,
                         subscribeFromAndIncludingGlobalOrder),
                     e);
        }
    }

    @Override
    public Optional<SubscriptionResumePoint> currentResumePoint() {
        if (resumePoint != null && !active) {
            // We've had a lock released, so the resume point is no longer valid - refresh the resume point
            log.trace("[{}-{}] Resume point is no longer valid - refreshing", subscriberId, aggregateType);
            resumePoint = durableSubscriptionRepository.getResumePoint(subscriberId,
                                                                       aggregateType).orElse(null);

        }
        return Optional.ofNullable(resumePoint);
    }

    @Override
    public Optional<Tenant> onlyIncludeEventsForTenant() {
        return onlyIncludeEventsForTenant;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return "ExclusiveAsynchronousSubscription{" +
                "aggregateType=" + aggregateType +
                ", subscriberId=" + subscriberId +
                ", onlyIncludeEventsForTenant=" + onlyIncludeEventsForTenant +
                ", lockName=" + lockName +
                ", resumePoint=" + resumePoint +
                ", started=" + started +
                ", active=" + active +
                '}';
    }
}