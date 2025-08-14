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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.components.foundation.types.Tenant;
import dk.trustworks.essentials.shared.time.StopWatch;
import reactor.core.publisher.BaseSubscriber;
import reactor.util.retry.RetryBackoffSpec;

import java.util.Optional;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * A subscription class representing a non-exclusive asynchronous subscription to an event store.
 * This subscription type ensures that events are handled asynchronously while allowing multiple
 * subscriptions to the same event set. It provides functionality for managing subscription lifecycle,
 * event consumption, and error handling.
 * <p>
 * This class extends the {@link AbstractEventStoreSubscription} and is designed to support durable
 * subscriptions using a resume point mechanism, enabling the subscription to restart from the last
 * persisted point.
 */
public class NonExclusiveAsynchronousSubscription extends AbstractEventStoreSubscription {
    private final DurableSubscriptionRepository durableSubscriptionRepository;
    private final GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder;
    private final PersistedEventHandler eventHandler;
    private SubscriptionResumePoint resumePoint;
    private BaseSubscriber<PersistedEvent> subscription;
    private final EventStoreSubscriptionManagerSettings eventStoreSubscriptionManagerSettings;

    public NonExclusiveAsynchronousSubscription(EventStore eventStore,
                                                DurableSubscriptionRepository durableSubscriptionRepository,
                                                AggregateType aggregateType,
                                                SubscriberId subscriberId,
                                                GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                Optional<Tenant> onlyIncludeEventsForTenant,
                                                PersistedEventHandler eventHandler,
                                                EventStoreSubscriptionObserver eventStoreSubscriptionObserver,
                                                EventStoreSubscriptionManagerSettings eventStoreSubscriptionManagerSettings,
                                                Consumer<EventStoreSubscription> unsubscribeCallback,
                                                Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory) {
        super(eventStore, aggregateType, subscriberId, onlyIncludeEventsForTenant, eventStoreSubscriptionObserver, unsubscribeCallback, eventStorePollingOptimizerFactory);
        this.durableSubscriptionRepository = requireNonNull(durableSubscriptionRepository, "No durableSubscriptionRepository provided");
        this.onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder = requireNonNull(onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                "No onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder provided");
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");
        this.eventStoreSubscriptionManagerSettings = requireNonNull(eventStoreSubscriptionManagerSettings, "No eventStoreSubscriptionManagerSettings provided");
    }

    @Override
    public void start() {
        if (!started) {
            started = true;
            log.info("[{}-{}] Looking up subscription resumePoint",
                    subscriberId,
                    aggregateType);
            var resolveResumePointTiming = StopWatch.start("resolveResumePoint (" + subscriberId + ", " + aggregateType + ")");
            resumePoint = durableSubscriptionRepository.getOrCreateResumePoint(subscriberId,
                    aggregateType,
                    onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder);
            log.info("[{}-{}] Starting subscription from globalEventOrder: {}",
                    subscriberId,
                    aggregateType,
                    resumePoint.getResumeFromAndIncluding());
            eventStoreSubscriptionObserver.resolveResumePoint(resumePoint,
                    onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                    NonExclusiveAsynchronousSubscription.this,
                    resolveResumePointTiming.stop().getDuration());

            subscription = new PersistedEventSubscriber(eventHandler,
                    this,
                    this::onErrorHandlingEvent,
                    eventStoreSubscriptionManagerSettings.eventStorePollingBatchSize(),
                    eventStore);
            eventStore.pollEvents(aggregateType,
                            resumePoint.getResumeFromAndIncluding(),
                            Optional.of(eventStoreSubscriptionManagerSettings.eventStorePollingBatchSize()),
                            Optional.of(eventStoreSubscriptionManagerSettings.eventStorePollingInterval()),
                            onlyIncludeEventsForTenant,
                            Optional.of(subscriberId),
                            Optional.of(eventStorePollingOptimizerFactory))
                    .limitRate(eventStoreSubscriptionManagerSettings.eventStorePollingBatchSize())
                    .subscribe(subscription);
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
        log.trace("[{}-{}] Requesting {} event(s)",
                subscriberId,
                aggregateType,
                n);
        eventStoreSubscriptionObserver.requestingEvents(n, this);
        subscription.request(n);
    }

    /**
     * The error handler called for any non-retryable Exceptions (as specified by the {@link RetryBackoffSpec})<br>
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
            log.info("[{}-{}] Stopping subscription",
                    subscriberId,
                    aggregateType);
            try {
                log.debug("[{}-{}] Stopping subscription flux",
                        subscriberId,
                        aggregateType);
                subscription.dispose();
            } catch (Exception e) {
                log.error(msg("[{}-{}] Failed to dispose subscription flux",
                        subscriberId,
                        aggregateType), e);
            }
            try {
                // Allow the reactive components to complete
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // Ignore
                Thread.currentThread().interrupt();
            }
            // Save resume point to be the next global order event
            log.debug("[{}-{}] Storing ResumePoint with resumeFromAndIncluding {}",
                    subscriberId,
                    aggregateType,
                    resumePoint.getResumeFromAndIncluding());

            durableSubscriptionRepository.saveResumePoint(resumePoint);
            started = false;
            log.info("[{}-{}] Stopped subscription",
                    subscriberId,
                    aggregateType);
        }
    }


    @Override
    public boolean isExclusive() {
        return false;
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
        if (started) {
            log.info("[{}-{}] Resetting resume point and re-starts the subscriber from and including globalOrder {}",
                    subscriberId,
                    aggregateType,
                    subscribeFromAndIncludingGlobalOrder);
            stop();
            overrideResumePoint(subscribeFromAndIncludingGlobalOrder);
            resetProcessor.accept(subscribeFromAndIncludingGlobalOrder);
            start();
        } else {
            overrideResumePoint(subscribeFromAndIncludingGlobalOrder);
            resetProcessor.accept(subscribeFromAndIncludingGlobalOrder);
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
        return Optional.ofNullable(resumePoint);
    }

    @Override
    public boolean isActive() {
        return started;
    }
}
