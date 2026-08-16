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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.foundation.types.*;
import org.slf4j.*;

import java.time.Duration;
import java.util.Optional;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Abstract base class for EventStoreSubscription implementations.
 * Provides common functionality and fields used by all subscription types.
 */
public abstract class AbstractEventStoreSubscription implements EventStoreSubscription {
    protected final Logger log;

    protected final EventStore                                   eventStore;
    protected final AggregateType                                aggregateType;
    protected final SubscriberId                                 subscriberId;
    /**
     * Held nullable rather than as an {@code Optional} field: this is read on every poll, {@code Optional} is not
     * {@link java.io.Serializable} and costs an allocation per access. {@link #onlyIncludeEventsForTenant()} still
     * returns {@code Optional}, so {@link EventStoreSubscription} is unchanged.
     */
    protected final Tenant                                       onlyIncludeEventsForTenant;
    protected final EventStoreSubscriptionObserver               eventStoreSubscriptionObserver;
    protected final Consumer<EventStoreSubscription>             unsubscribeCallback;
    protected final Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory;

    protected volatile boolean started;

    /**
     * Constructor with common parameters for all subscription types
     *
     * @param eventStore                        The event store
     * @param aggregateType                     The aggregate type to subscribe to
     * @param subscriberId                      The subscriber ID
     * @param onlyIncludeEventsForTenant        Optional tenant filter
     * @param eventStoreSubscriptionObserver    The subscription observer
     * @param unsubscribeCallback               Callback to execute when unsubscribing
     * @param eventStorePollingOptimizerFactory Factory to create EventStorePollingOptimizers - input String parameter is the {@code eventStreamLogName} that is used label for logs (e.g., subscriberId+aggregateType)
     */
    /**
     * @param context the seven arguments every subscription needs — see {@link EventStoreSubscriptionContext#builder()}
     */
    protected AbstractEventStoreSubscription(EventStoreSubscriptionContext context) {
        requireNonNull(context, "No context provided - see EventStoreSubscriptionContext.builder()");
        this.log = LoggerFactory.getLogger(this.getClass());
        this.eventStore = context.eventStore();
        this.aggregateType = context.aggregateType();
        this.subscriberId = context.subscriberId();
        this.onlyIncludeEventsForTenant = context.onlyIncludeEventsForTenant();
        this.eventStoreSubscriptionObserver = context.eventStoreSubscriptionObserver();
        this.unsubscribeCallback = context.unsubscribeCallback();
        this.eventStorePollingOptimizerFactory = context.eventStorePollingOptimizerFactory();
    }

    /**
     * @param eventStore                        the event store to subscribe to
     * @param aggregateType                     the aggregate type whose event stream is subscribed to
     * @param subscriberId                      the durable identity of this subscriber
     * @param onlyIncludeEventsForTenant        restrict the subscription to one tenant, or {@link Optional#empty()} for all
     * @param eventStoreSubscriptionObserver    observability hook for the subscription lifecycle
     * @param unsubscribeCallback               invoked when the subscription unsubscribes
     * @param eventStorePollingOptimizerFactory creates the polling optimizer for a given subscription
     * @deprecated Use {@link #AbstractEventStoreSubscription(EventStoreSubscriptionContext)}. These seven arguments
     *         were repeated positionally by all five subscription subclasses, which is what pushed the widest of them
     *         to thirteen parameters. This constructor delegates and behaves identically.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    protected AbstractEventStoreSubscription(EventStore eventStore,
                                             AggregateType aggregateType,
                                             SubscriberId subscriberId,
                                             Optional<Tenant> onlyIncludeEventsForTenant,
                                             EventStoreSubscriptionObserver eventStoreSubscriptionObserver,
                                             Consumer<EventStoreSubscription> unsubscribeCallback,
                                             Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory) {
        this(EventStoreSubscriptionContext.builder()
                                          .setEventStore(eventStore)
                                          .setAggregateType(aggregateType)
                                          .setSubscriberId(subscriberId)
                                          .setOnlyIncludeEventsForTenant(requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant provided"))
                                          .setEventStoreSubscriptionObserver(eventStoreSubscriptionObserver)
                                          .setUnsubscribeCallback(unsubscribeCallback)
                                          .setEventStorePollingOptimizerFactory(eventStorePollingOptimizerFactory)
                                          .build());
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
    public boolean isStarted() {
        return started;
    }

    @Override
    public void unsubscribe() {
        log.info("[{}-{}] Initiating unsubscription",
                 subscriberId,
                 aggregateType);
        eventStoreSubscriptionObserver.unsubscribing(this);
        unsubscribeCallback.accept(this);
    }

    @Override
    public Optional<Tenant> onlyIncludeEventsForTenant() {
        return Optional.ofNullable(onlyIncludeEventsForTenant);
    }

    /**
     * Common error handling for persisted events
     *
     * @param e     The persisted event that caused the error
     * @param cause The cause of the error
     */
    protected void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
        log.error("[{}-{}] (#{}) Skipping {} event because of error",
                  subscriberId,
                  aggregateType,
                  e.globalEventOrder(),
                  e.event().getEventTypeOrName().getValue(), cause);
    }

    /** How long {@link #persistResumePointUntilSettled} keeps re-saving before giving up. */
    protected static final Duration RESUME_POINT_SETTLE_TIMEOUT    = Duration.ofSeconds(5);
    /** Delay between the settle re-checks - short enough to see in-flight work land, long enough not to spin. */
    protected static final long     RESUME_POINT_SETTLE_DELAY_MILL = 100L;

    /**
     * Persist the resume point on the way to inactive, retrying until it is durably in sync.
     * <p>
     * {@code dispose()} stops new work being pulled but batches already in flight keep completing, and
     * each one advances the resume point - including <i>after</i> a save has bound its value. Saving once
     * therefore leaves the durable resume point behind the work actually performed, and nothing corrects
     * it afterwards: the periodic snapshotter in {@code DefaultEventStoreSubscriptionManager} only saves
     * <i>active</i> subscriptions, and this one is on its way to inactive. Those events would then be
     * redelivered on the next start.
     * <p>
     * So rather than guessing when the in-flight work has drained, save and re-check
     * {@link SubscriptionResumePoint#isChanged()} - which is value-based and stays true when the resume
     * point advanced past what was written - until it reports clean or the timeout expires.
     *
     * @param durableSubscriptionRepository the repository to persist to
     * @param resumePoint                   the resume point to persist
     */
    protected void persistResumePointUntilSettled(DurableSubscriptionRepository durableSubscriptionRepository,
                                                  SubscriptionResumePoint resumePoint) {
        requireNonNull(durableSubscriptionRepository, "No durableSubscriptionRepository provided");
        requireNonNull(resumePoint, "No resumePoint provided");

        var deadline = System.nanoTime() + RESUME_POINT_SETTLE_TIMEOUT.toNanos();
        while (true) {
            log.debug("[{}-{}] Storing ResumePoint with resumeFromAndIncluding {}",
                      subscriberId,
                      aggregateType,
                      resumePoint.getResumeFromAndIncluding());
            durableSubscriptionRepository.saveResumePoint(resumePoint);

            if (!resumePoint.isChanged()) {
                // What we wrote is what the resume point holds - nothing advanced past it mid-save
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                log.warn("[{}-{}] Gave up after {} waiting for the ResumePoint to settle - it is still advancing (currently {}). " +
                                 "Events up to that point may be redelivered on the next start",
                         subscriberId,
                         aggregateType,
                         RESUME_POINT_SETTLE_TIMEOUT,
                         resumePoint.getResumeFromAndIncluding());
                return;
            }
            try {
                Thread.sleep(RESUME_POINT_SETTLE_DELAY_MILL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Retrieves the factory function for creating instances of {@link EventStorePollingOptimizer}.
     *
     * @return a function that takes a string parameter and returns an {@link EventStorePollingOptimizer} instance.
     */
    public Function<String, EventStorePollingOptimizer> getEventStorePollingOptimizerFactory() {
        return eventStorePollingOptimizerFactory;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "aggregateType=" + aggregateType +
                ", subscriberId=" + subscriberId +
                ", onlyIncludeEventsForTenant=" + onlyIncludeEventsForTenant +
                ", started=" + started +
                '}';
    }
}