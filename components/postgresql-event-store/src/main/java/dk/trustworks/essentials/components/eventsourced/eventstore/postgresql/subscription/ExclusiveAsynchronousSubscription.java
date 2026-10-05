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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.time.StopWatch;
import reactor.util.retry.RetryBackoffSpec;

import java.util.Optional;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Represents an exclusive asynchronous subscription for consuming events from an event store.
 * This subscription ensures that only one active instance of the subscriber can process events
 * by utilizing a distributed lock mechanism.
 *
 * The class extends {@link AbstractEventStoreSubscription} and provides mechanisms to acquire
 * a distributed lock, resolve subscription resume points, and consume events in an exclusive manner.
 */
public class ExclusiveAsynchronousSubscription extends AbstractEventStoreSubscription implements ExclusiveSubscription {
    private final FencedLockManager fencedLockManager;
    private final DurableSubscriptionRepository durableSubscriptionRepository;
    private final Function<AggregateType, GlobalEventOrder> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder;
    private final FencedLockAwareSubscriber fencedLockAwareSubscriber;
    private final PersistedEventHandler eventHandler;
    private final LockName lockName;
    private final EventStoreSubscriptionManagerSettings eventStoreSubscriptionManagerSettings;

    private SubscriptionResumePoint resumePoint;
    private volatile PersistedEventSubscriber subscription;

    private volatile boolean active;
    /**
     * Serializes the fenced-lock callbacks with {@link #resumeIfStoppedByErrorPolicy()}: a resume racing a lock release
     * would otherwise subscribe a new subscriber after the release disposed the old one - delivering without the lock
     */
    private final Object     subscriberLifecycleLock = new Object();

    /**
     * @param context                 the arguments shared by every subscription — see {@link EventStoreSubscriptionContext#builder()}
     * @param durableContext            the resume-point arguments shared by the asynchronous subscriptions
     * @param fencedLockManager         the lock manager that decides which node owns this subscription
     * @param fencedLockAwareSubscriber callback notified when this node acquires or loses the subscription's lock
     * @param eventHandler              the handler invoked for each persisted event
     */
    public ExclusiveAsynchronousSubscription(EventStoreSubscriptionContext context,
                                             DurableSubscriptionContext durableContext,
                                             FencedLockManager fencedLockManager,
                                             FencedLockAwareSubscriber fencedLockAwareSubscriber,
                                             PersistedEventHandler eventHandler) {
        super(context);
        requireNonNull(durableContext, "No durableContext provided");
        this.fencedLockManager = requireNonNull(fencedLockManager, "No fencedLockManager provided");
        this.durableSubscriptionRepository = durableContext.durableSubscriptionRepository();
        this.onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder = durableContext.onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder();
        this.fencedLockAwareSubscriber = requireNonNull(fencedLockAwareSubscriber, "No fencedLockAwareSubscriber provided");
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");
        this.eventStoreSubscriptionManagerSettings = durableContext.eventStoreSubscriptionManagerSettings();
        this.lockName = LockName.of(msg("[{}-{}]", context.subscriberId(), context.aggregateType()));
    }

    @Override
    public void start() {
        if (started) {
            log.debug("[{}-{}] Subscription was already started",
                    subscriberId,
                    aggregateType);
            return;
        }
        if (!fencedLockManager.isStarted()) {
            // resetFrom() does stop()/start() around the reset, so a concurrent shutdown of the subscription manager
            // (which stops the lock manager) can land in between. Leaving started == false means a later manager
            // start() genuinely restarts this subscription instead of finding it "already started" and skipping it,
            // which would leave the subscription permanently dead
            log.info("[{}-{}] Not starting subscriber - the FencedLockManager isn't started",
                    subscriberId,
                    aggregateType);
            return;
        }

        started = true;
        log.info("[{}-{}] Started subscriber",
                subscriberId,
                aggregateType);

        fencedLockManager.acquireLockAsync(lockName,
                LockCallback.builder()
                        .onLockAcquired(this::onLockAcquired)
                        .onLockReleased(this::onLockReleased)
                        .build());
    }

    private void onLockAcquired(FencedLock fencedLock) {
        synchronized (subscriberLifecycleLock) {
            subscribeOnLockAcquired(fencedLock);
        }
    }

    private void subscribeOnLockAcquired(FencedLock fencedLock) {
        log.info("[{}-{}] 🎉 Acquired lock. Looking up subscription resumePoint",
                subscriberId,
                aggregateType);
        active = true;
        eventStoreSubscriptionObserver.lockAcquired(fencedLock, ExclusiveAsynchronousSubscription.this);


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
            fencedLockAwareSubscriber.onLockAcquired(fencedLock, resumePoint);
        } catch (Exception e) {
            log.error(msg("FencedLockAwareSubscriber#onLockAcquired failed for lock {} and resumePoint {}", fencedLock.getName(), resumePoint), e);
        }

        subscribeFromResumePoint();
    }

    /**
     * Subscribe a new {@link PersistedEventSubscriber} to the event store from {@link #resumePoint}. Called with the
     * fenced lock held, under {@link #subscriberLifecycleLock}
     */
    private void subscribeFromResumePoint() {
        // The subscriber reports what it handled, and the event store resolves a gap fill's gap only then
        var acknowledgement = SubscriberAcknowledgement.create();
        subscription = PersistedEventSubscriber.builder()
                                               .setEventHandler(eventHandler)
                                               .setEventStoreSubscription(ExclusiveAsynchronousSubscription.this)
                                               .setOnErrorHandler(ExclusiveAsynchronousSubscription.this::onErrorHandlingEvent)
                                               .setEventStorePollingBatchSize(eventStoreSubscriptionManagerSettings.eventStorePollingBatchSize())
                                               .setEventStore(eventStore)
                                               .setSubscriptionErrorPolicy(eventStoreSubscriptionManagerSettings.subscriptionErrorPolicy())
                                               .setSubscriberAcknowledgement(acknowledgement)
                                               .build();

        eventStore.pollEvents(aggregateType,
                        resumePoint.getResumeFromAndIncluding(),
                        Optional.of(eventStoreSubscriptionManagerSettings.eventStorePollingBatchSize()),
                        Optional.of(eventStoreSubscriptionManagerSettings.eventStorePollingInterval()),
                        onlyIncludeEventsForTenant(),
                        Optional.of(subscriberId),
                        Optional.of(eventStorePollingOptimizerFactory),
                        acknowledgement)
                .limitRate(eventStoreSubscriptionManagerSettings.eventStorePollingBatchSize())
                .subscribe(subscription);
    }

    private void onLockReleased(FencedLock fencedLock) {
        synchronized (subscriberLifecycleLock) {
            unsubscribeOnLockReleased(fencedLock);
        }
    }

    /**
     * Resumes this subscription without letting go of its fenced lock: the stopped subscriber is disposed, the resume point
     * the {@link SubscriptionErrorPolicy} held at the failed event is saved, and a new subscriber is subscribed from it -
     * the same steps a lock release followed by a re-acquire would take, minus the release, so the subscription cannot flap
     * to another node in between. On an instance that does not hold the lock this returns false: only the lock holder
     * runs (and so can stop) the subscription. See {@link EventStoreSubscription#resumeIfStoppedByErrorPolicy()}
     */
    @Override
    public boolean resumeIfStoppedByErrorPolicy() {
        synchronized (subscriberLifecycleLock) {
            var stoppedSubscriber = subscription;
            if (!started || !active || stoppedSubscriber == null || !stoppedSubscriber.isStoppedByErrorPolicy()) {
                log.debug("[{}-{}] Not resuming - the subscription is not stopped by its SubscriptionErrorPolicy in this instance (started: {}, active (is-lock-acquired): {})",
                          subscriberId,
                          aggregateType,
                          started,
                          active);
                return false;
            }
            log.info("[{}-{}] Resuming the subscription stopped by its SubscriptionErrorPolicy from and including globalOrder {} - keeping the fenced lock",
                     subscriberId,
                     aggregateType,
                     resumePoint.getResumeFromAndIncluding());
            // Already disposed by the stop itself (asynchronously) - disposing again makes sure it is before we subscribe anew
            stoppedSubscriber.dispose();
            try {
                // Allow the reactive components to complete, as on a lock release
                if (!isShutdownCleanupAbandoned()) {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            persistResumePointUntilSettled(durableSubscriptionRepository, resumePoint);
            subscribeFromResumePoint();
            return true;
        }
    }

    private void unsubscribeOnLockReleased(FencedLock fencedLock) {
        if (!active) {
            return;
        }
        log.info("[{}-{}] 🚨 Lock Released. Stopping subscription",
                subscriberId,
                aggregateType);
        try {
            eventStoreSubscriptionObserver.lockReleased(fencedLock, ExclusiveAsynchronousSubscription.this);
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
            fencedLockAwareSubscriber.onLockReleased(fencedLock);
        } catch (Exception e) {
            log.error(msg("FencedLockAwareSubscriber#onLockReleased failed for lock {}", fencedLock.getName()), e);
        }

        try {
            // Allow the reactive components to complete
            if (!isShutdownCleanupAbandoned()) {
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            // Ignore
            Thread.currentThread().interrupt();
        }

        // Save resume point to be the next global order event AFTER the one we know we just handled
        persistResumePointUntilSettled(durableSubscriptionRepository, resumePoint);
        active = false;
        log.info("[{}-{}] Stopped subscription",
                subscriberId,
                aggregateType);

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
        // Read once: onLockReleased nulls the field from the fenced-lock thread while the delivery thread is in here
        var subscriber = subscription;
        if (subscriber == null) {
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
        subscriber.request(n);
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
    @Override
    protected void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
        super.onErrorHandlingEvent(e, cause);
        log.trace("[{}-{}] (#{}) Requesting 1 event from the EventStore",
                subscriberId(),
                aggregateType(),
                e.globalEventOrder()
        );
        request(1);
    }

    @Override
    public void stop() {
        if (started) {
            fencedLockManager.cancelAsyncLockAcquiring(lockName);
            started = false;
        }
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

    /**
     * @return true if the {@link SubscriptionErrorPolicy} stopped the current subscriber - see {@link EventStoreSubscription#isStoppedByErrorPolicy()}
     */
    @Override
    public boolean isStoppedByErrorPolicy() {
        var subscriber = subscription;
        return subscriber != null && subscriber.isStoppedByErrorPolicy();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public LockName lockName() {
        return lockName;
    }
}
