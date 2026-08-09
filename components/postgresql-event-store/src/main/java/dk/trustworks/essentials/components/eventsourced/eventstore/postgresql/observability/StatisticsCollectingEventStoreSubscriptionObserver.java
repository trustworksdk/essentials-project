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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.SubscriptionStatisticsRegistry.SubscriptionKey;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.types.LongRange;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An {@link EventStoreSubscriptionObserver} that records per-subscription runtime statistics into a
 * {@link SubscriptionStatisticsRegistry} - the data behind
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.EventStoreApi#findSubscriptionStatistics(Object, SubscriberId, AggregateType)}
 * - and then forwards every callback to a delegate observer.
 * <p>
 * It is a decorator on purpose: the {@link EventStoreSubscriptionObserver} is a single-slot SPI, so collecting
 * statistics must not cost the application its metrics observer (by default
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver}).
 * An application that supplies its own observer can wrap it the same way.
 * <p>
 * Recording never breaks the subscription: the delegate is always called first, and a failure while recording is
 * logged (once) and swallowed.
 * <p>
 * Note that the polling callbacks are only invoked by the polling path in
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore} - a subscription
 * that is served by CDC records event handling but no polls.
 */
public class StatisticsCollectingEventStoreSubscriptionObserver implements EventStoreSubscriptionObserver {
    private static final Logger log = LoggerFactory.getLogger(StatisticsCollectingEventStoreSubscriptionObserver.class);

    private final EventStoreSubscriptionObserver  delegate;
    private final SubscriptionStatisticsRegistry  statisticsRegistry;
    private final AtomicBoolean                   recordingFailureLogged = new AtomicBoolean();

    /**
     * @param delegate           the observer to forward every callback to
     * @param statisticsRegistry the registry to record the statistics into
     */
    public StatisticsCollectingEventStoreSubscriptionObserver(EventStoreSubscriptionObserver delegate,
                                                              SubscriptionStatisticsRegistry statisticsRegistry) {
        this.delegate = requireNonNull(delegate, "No delegate provided");
        this.statisticsRegistry = requireNonNull(statisticsRegistry, "No statisticsRegistry provided");
    }

    /**
     * @return the registry the statistics are recorded into
     */
    public SubscriptionStatisticsRegistry getStatisticsRegistry() {
        return statisticsRegistry;
    }

    /**
     * @return the observer every callback is forwarded to
     */
    public EventStoreSubscriptionObserver getDelegate() {
        return delegate;
    }

    @Override
    public void startingSubscriber(EventStoreSubscription eventStoreSubscription) {
        delegate.startingSubscriber(eventStoreSubscription);
    }

    @Override
    public void startedSubscriber(EventStoreSubscription eventStoreSubscription, Duration startDuration) {
        delegate.startedSubscriber(eventStoreSubscription, startDuration);
        record(eventStoreSubscription, MutableSubscriptionStatistics::recordStarted);
    }

    @Override
    public void stoppingSubscriber(EventStoreSubscription eventStoreSubscription) {
        delegate.stoppingSubscriber(eventStoreSubscription);
    }

    @Override
    public void stoppedSubscriber(EventStoreSubscription eventStoreSubscription, Duration stopDuration) {
        delegate.stoppedSubscriber(eventStoreSubscription, stopDuration);
        record(eventStoreSubscription, MutableSubscriptionStatistics::recordStopped);
    }

    @Override
    public void resolvedBatchSizeForEventStorePoll(SubscriberId subscriberId,
                                                  AggregateType aggregateType,
                                                  long defaultBatchFetchSize,
                                                  long remainingDemandForEvents,
                                                  long lastBatchSizeForEventStorePoll,
                                                  int consecutiveNoPersistedEventsReturned,
                                                  long nextFromInclusiveGlobalOrder,
                                                  long batchSizeForThisEventStorePoll,
                                                  Duration resolveBatchSizeDuration) {
        delegate.resolvedBatchSizeForEventStorePoll(subscriberId,
                                                    aggregateType,
                                                    defaultBatchFetchSize,
                                                    remainingDemandForEvents,
                                                    lastBatchSizeForEventStorePoll,
                                                    consecutiveNoPersistedEventsReturned,
                                                    nextFromInclusiveGlobalOrder,
                                                    batchSizeForThisEventStorePoll,
                                                    resolveBatchSizeDuration);
        record(subscriberId,
               aggregateType,
               statistics -> statistics.recordConsecutiveNoPersistedEventsReturned(consecutiveNoPersistedEventsReturned));
    }

    @Override
    public void skippingPollingDueToNoNewEventsPersisted(SubscriberId subscriberId,
                                                         AggregateType aggregateType,
                                                         long defaultBatchFetchSize,
                                                         long remainingDemandForEvents,
                                                         long lastBatchSizeForEventStorePoll,
                                                         int consecutiveNoPersistedEventsReturned,
                                                         long nextFromInclusiveGlobalOrder,
                                                         long batchSizeForThisEventStorePoll) {
        delegate.skippingPollingDueToNoNewEventsPersisted(subscriberId,
                                                          aggregateType,
                                                          defaultBatchFetchSize,
                                                          remainingDemandForEvents,
                                                          lastBatchSizeForEventStorePoll,
                                                          consecutiveNoPersistedEventsReturned,
                                                          nextFromInclusiveGlobalOrder,
                                                          batchSizeForThisEventStorePoll);
        record(subscriberId,
               aggregateType,
               statistics -> statistics.recordSkippedPoll(consecutiveNoPersistedEventsReturned));
    }

    @Override
    public void eventStorePolled(SubscriberId subscriberId,
                                 AggregateType aggregateType,
                                 LongRange globalOrderRange,
                                 List<GlobalEventOrder> transientGapsToInclude,
                                 Optional<Tenant> onlyIncludeEventIfItBelongsToTenant,
                                 List<PersistedEvent> persistedEventsReturnedFromPoll,
                                 Duration pollDuration) {
        delegate.eventStorePolled(subscriberId,
                                  aggregateType,
                                  globalOrderRange,
                                  transientGapsToInclude,
                                  onlyIncludeEventIfItBelongsToTenant,
                                  persistedEventsReturnedFromPoll,
                                  pollDuration);
        var returnedEvents = persistedEventsReturnedFromPoll != null && !persistedEventsReturnedFromPoll.isEmpty();
        record(subscriberId,
               aggregateType,
               statistics -> statistics.recordEventStorePolled(returnedEvents, pollDuration));
    }

    @Override
    public void reconciledGaps(SubscriberId subscriberId,
                               AggregateType aggregateType,
                               LongRange globalOrderRange,
                               List<GlobalEventOrder> transientGapsToInclude,
                               List<PersistedEvent> persistedEvents,
                               Duration reconcileGapsDuration) {
        delegate.reconciledGaps(subscriberId,
                                aggregateType,
                                globalOrderRange,
                                transientGapsToInclude,
                                persistedEvents,
                                reconcileGapsDuration);
        record(subscriberId, aggregateType, MutableSubscriptionStatistics::recordGapsReconciled);
    }

    @Override
    public void publishEvent(SubscriberId subscriberId,
                             AggregateType aggregateType,
                             PersistedEvent persistedEvent,
                             Duration publishEventDuration) {
        delegate.publishEvent(subscriberId, aggregateType, persistedEvent, publishEventDuration);
        record(subscriberId, aggregateType, MutableSubscriptionStatistics::recordEventPublishedToSubscriber);
    }

    @Override
    public void requestingEvents(long numberOfEventsRequested, EventStoreSubscription eventStoreSubscription) {
        delegate.requestingEvents(numberOfEventsRequested, eventStoreSubscription);
        record(eventStoreSubscription, statistics -> statistics.recordEventsRequested(numberOfEventsRequested));
    }

    @Override
    public void lockAcquired(FencedLock lock, EventStoreSubscription eventStoreSubscription) {
        delegate.lockAcquired(lock, eventStoreSubscription);
        record(eventStoreSubscription, MutableSubscriptionStatistics::recordLockAcquired);
    }

    @Override
    public void lockReleased(FencedLock lock, EventStoreSubscription eventStoreSubscription) {
        delegate.lockReleased(lock, eventStoreSubscription);
        record(eventStoreSubscription, MutableSubscriptionStatistics::recordLockReleased);
    }

    @Override
    public void handleEvent(PersistedEvent event,
                            TransactionalPersistedEventHandler eventHandler,
                            EventStoreSubscription eventStoreSubscription,
                            Duration handleEventDuration) {
        delegate.handleEvent(event, eventHandler, eventStoreSubscription, handleEventDuration);
        recordEventHandled(event, eventStoreSubscription, handleEventDuration);
    }

    @Override
    public void handleEvent(PersistedEvent event,
                            PersistedEventHandler eventHandler,
                            EventStoreSubscription eventStoreSubscription,
                            Duration handleEventDuration) {
        delegate.handleEvent(event, eventHandler, eventStoreSubscription, handleEventDuration);
        recordEventHandled(event, eventStoreSubscription, handleEventDuration);
    }

    @Override
    public void handleEventFailed(PersistedEvent event,
                                  TransactionalPersistedEventHandler eventHandler,
                                  Throwable cause,
                                  EventStoreSubscription eventStoreSubscription) {
        delegate.handleEventFailed(event, eventHandler, cause, eventStoreSubscription);
        record(eventStoreSubscription, statistics -> statistics.recordEventHandlingFailed(cause));
    }

    @Override
    public void handleEventFailed(PersistedEvent event,
                                  PersistedEventHandler eventHandler,
                                  Throwable cause,
                                  EventStoreSubscription eventStoreSubscription) {
        delegate.handleEventFailed(event, eventHandler, cause, eventStoreSubscription);
        record(eventStoreSubscription, statistics -> statistics.recordEventHandlingFailed(cause));
    }

    @Override
    public void resolveResumePoint(SubscriptionResumePoint resumePoint,
                                   GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                   EventStoreSubscription eventStoreSubscription,
                                   Duration resolveResumePointDuration) {
        delegate.resolveResumePoint(resumePoint,
                                    onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                    eventStoreSubscription,
                                    resolveResumePointDuration);
    }

    @Override
    public void unsubscribing(EventStoreSubscription eventStoreSubscription) {
        delegate.unsubscribing(eventStoreSubscription);
        try {
            if (eventStoreSubscription != null) {
                statisticsRegistry.remove(eventStoreSubscription.subscriberId(), eventStoreSubscription.aggregateType());
            }
        } catch (Exception e) {
            logRecordingFailure(e);
        }
    }

    @Override
    public void resettingFrom(GlobalEventOrder subscribeFromAndIncludingGlobalOrder,
                              EventStoreSubscription eventStoreSubscription) {
        delegate.resettingFrom(subscribeFromAndIncludingGlobalOrder, eventStoreSubscription);
        record(eventStoreSubscription, statistics -> statistics.recordReset(subscribeFromAndIncludingGlobalOrder));
    }

    private void recordEventHandled(PersistedEvent event,
                                    EventStoreSubscription eventStoreSubscription,
                                    Duration handleEventDuration) {
        record(eventStoreSubscription,
               statistics -> statistics.recordEventHandled(event != null ? event.globalEventOrder() : null,
                                                           handleEventDuration));
    }

    private void record(EventStoreSubscription eventStoreSubscription,
                        Consumer<MutableSubscriptionStatistics> recorder) {
        if (eventStoreSubscription == null) {
            return;
        }
        try {
            statisticsRegistry.statisticsFor(SubscriptionKey.of(eventStoreSubscription)).ifPresent(recorder);
        } catch (Exception e) {
            logRecordingFailure(e);
        }
    }

    private void record(SubscriberId subscriberId,
                        AggregateType aggregateType,
                        Consumer<MutableSubscriptionStatistics> recorder) {
        if (subscriberId == null || aggregateType == null) {
            return;
        }
        try {
            statisticsRegistry.statisticsFor(new SubscriptionKey(subscriberId, aggregateType)).ifPresent(recorder);
        } catch (Exception e) {
            logRecordingFailure(e);
        }
    }

    private void logRecordingFailure(Exception e) {
        if (recordingFailureLogged.compareAndSet(false, true)) {
            log.warn("Failed to record subscription statistics - statistics may be incomplete. This is only logged once", e);
        }
    }
}
