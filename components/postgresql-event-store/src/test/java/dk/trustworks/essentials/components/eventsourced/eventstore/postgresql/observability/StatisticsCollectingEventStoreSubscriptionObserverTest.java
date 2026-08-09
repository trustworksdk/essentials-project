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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.SubscriptionStatisticsRegistry.SubscriptionKey;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PersistedEventHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

class StatisticsCollectingEventStoreSubscriptionObserverTest {
    private static final SubscriberId  SUBSCRIBER_ID  = SubscriberId.of("OrderProcessor");
    private static final AggregateType AGGREGATE_TYPE = AggregateType.of("Orders");
    private static final Instant       NOW            = Instant.parse("2026-08-08T10:15:30Z");

    private SubscriptionStatisticsRegistry                    registry;
    private StatisticsCollectingEventStoreSubscriptionObserver observer;
    private EventStoreSubscriptionObserver                     delegate;

    @BeforeEach
    void setUp() {
        registry = new SubscriptionStatisticsRegistry(10, Clock.fixed(NOW, ZoneOffset.UTC));
        delegate = mock(EventStoreSubscriptionObserver.class);
        observer = new StatisticsCollectingEventStoreSubscriptionObserver(delegate, registry);
    }

    @Test
    void no_statistics_are_collected_until_the_subscription_is_observed() {
        assertThat(registry.findStatistics(SUBSCRIBER_ID, AGGREGATE_TYPE)).isEmpty();
        assertThat(registry.trackedSubscriptions()).isZero();
    }

    @Test
    void the_lifecycle_callbacks_are_counted_and_timestamped() {
        var subscription = subscription();

        observer.startedSubscriber(subscription, Duration.ofMillis(12));
        observer.stoppedSubscriber(subscription, Duration.ofMillis(3));
        observer.startedSubscriber(subscription, Duration.ofMillis(9));

        var lifecycle = statistics().lifecycle();
        assertThat(lifecycle.starts()).isEqualTo(2);
        assertThat(lifecycle.stops()).isEqualTo(1);
        assertThat(lifecycle.lastStartedAt()).isEqualTo(NOW);
        assertThat(lifecycle.lastStoppedAt()).isEqualTo(NOW);
        assertThat(statistics().statisticsSince()).isEqualTo(NOW);
    }

    @Test
    void event_handling_records_throughput_timing_and_the_last_handled_event() {
        var subscription = subscription();

        observer.publishEvent(SUBSCRIBER_ID, AGGREGATE_TYPE, event(41), Duration.ofMillis(1));
        observer.handleEvent(event(41), eventHandler(), subscription, Duration.ofMillis(10));
        observer.handleEvent(event(42), eventHandler(), subscription, Duration.ofMillis(30));
        observer.requestingEvents(64, subscription);

        var eventHandling = statistics().eventHandling();
        assertThat(eventHandling.eventsPublishedToSubscriber()).isEqualTo(1);
        assertThat(eventHandling.eventsHandled()).isEqualTo(2);
        assertThat(eventHandling.failures()).isZero();
        assertThat(eventHandling.lastEventHandledAt()).isEqualTo(NOW);
        assertThat(eventHandling.lastEventHandledGlobalOrder()).isEqualTo(GlobalEventOrder.of(42));
        assertThat(eventHandling.averageEventHandlingTime()).isEqualTo(Duration.ofMillis(20));
        assertThat(eventHandling.maxEventHandlingTime()).isEqualTo(Duration.ofMillis(30));
        assertThat(eventHandling.lastNumberOfEventsRequested()).isEqualTo(64);
        assertThat(eventHandling.lastFailureAt()).isNull();
        assertThat(eventHandling.lastFailureReason()).isNull();
    }

    /** The failure text is rendered on the spot: retaining the {@link Throwable} would pin its whole stack. */
    @Test
    void a_handler_failure_is_counted_and_rendered_without_retaining_the_exception() {
        var subscription = subscription();

        observer.handleEventFailed(event(43), eventHandler(), new IllegalStateException("projection is stale"), subscription);

        var eventHandling = statistics().eventHandling();
        assertThat(eventHandling.failures()).isEqualTo(1);
        assertThat(eventHandling.lastFailureAt()).isEqualTo(NOW);
        assertThat(eventHandling.lastFailureReason()).isEqualTo("IllegalStateException: projection is stale");
    }

    @Test
    void polling_separates_polls_that_returned_events_from_the_ones_that_did_not() {
        observer.eventStorePolled(SUBSCRIBER_ID, AGGREGATE_TYPE, LongRange.from(1, 10), List.of(),
                                  Optional.empty(), List.of(event(1)), Duration.ofMillis(4));
        observer.eventStorePolled(SUBSCRIBER_ID, AGGREGATE_TYPE, LongRange.from(11, 20), List.of(),
                                  Optional.empty(), List.of(), Duration.ofMillis(7));
        observer.skippingPollingDueToNoNewEventsPersisted(SUBSCRIBER_ID, AGGREGATE_TYPE, 10, 10, 10, 3, 21, 0);
        observer.reconciledGaps(SUBSCRIBER_ID, AGGREGATE_TYPE, LongRange.from(1, 10), List.of(),
                                List.of(event(1)), Duration.ofMillis(2));

        var polling = statistics().polling();
        assertThat(polling.polls()).isEqualTo(2);
        assertThat(polling.pollsWithoutEvents()).isEqualTo(1);
        assertThat(polling.skippedPolls()).isEqualTo(1);
        assertThat(polling.lastPollAt()).isEqualTo(NOW);
        assertThat(polling.lastPollDuration()).isEqualTo(Duration.ofMillis(7));
        assertThat(polling.consecutiveNoPersistedEventsReturned()).isEqualTo(3);
        assertThat(polling.gapReconciliations()).isEqualTo(1);
    }

    @Test
    void lock_ownership_is_tracked_so_that_flapping_is_visible() {
        var subscription = subscription();
        var lock         = mock(FencedLock.class);

        observer.lockAcquired(lock, subscription);
        observer.lockReleased(lock, subscription);
        observer.lockAcquired(lock, subscription);

        var lockStatistics = statistics().lock();
        assertThat(lockStatistics.acquisitions()).isEqualTo(2);
        assertThat(lockStatistics.releases()).isEqualTo(1);
        assertThat(lockStatistics.currentlyHeld()).isTrue();
        assertThat(lockStatistics.lastAcquiredAt()).isEqualTo(NOW);
        assertThat(lockStatistics.lastReleasedAt()).isEqualTo(NOW);
    }

    /** A replay must be reported rather than hidden - throughput and lag read very differently during one. */
    @Test
    void a_reset_is_recorded_and_leaves_the_existing_counters_alone() {
        var subscription = subscription();
        observer.handleEvent(event(41), eventHandler(), subscription, Duration.ofMillis(10));

        observer.resettingFrom(GlobalEventOrder.of(7), subscription);

        assertThat(statistics().reset().resets()).isEqualTo(1);
        assertThat(statistics().reset().lastResetAt()).isEqualTo(NOW);
        assertThat(statistics().reset().lastResetToGlobalOrder()).isEqualTo(GlobalEventOrder.of(7));
        assertThat(statistics().eventHandling().eventsHandled()).isEqualTo(1);
    }

    @Test
    void unsubscribing_discards_the_statistics_while_stopping_keeps_them() {
        var subscription = subscription();
        observer.startedSubscriber(subscription, Duration.ofMillis(1));

        observer.stoppedSubscriber(subscription, Duration.ofMillis(1));
        assertThat(registry.findStatistics(SUBSCRIBER_ID, AGGREGATE_TYPE)).isPresent();

        observer.unsubscribing(subscription);
        assertThat(registry.findStatistics(SUBSCRIBER_ID, AGGREGATE_TYPE)).isEmpty();
        assertThat(registry.trackedSubscriptions()).isZero();
    }

    @Test
    void statistics_are_kept_per_subscriber_and_aggregate_type() {
        var orders   = subscription();
        var payments = subscription(SUBSCRIBER_ID, AggregateType.of("Payments"));

        observer.handleEvent(event(1), eventHandler(), orders, Duration.ofMillis(5));
        observer.handleEvent(event(2), eventHandler(), payments, Duration.ofMillis(5));
        observer.handleEvent(event(3), eventHandler(), payments, Duration.ofMillis(5));

        assertThat(statistics().eventHandling().eventsHandled()).isEqualTo(1);
        assertThat(registry.findStatistics(SUBSCRIBER_ID, AggregateType.of("Payments")).orElseThrow()
                           .eventHandling().eventsHandled()).isEqualTo(2);
        assertThat(registry.allStatistics()).hasSize(2);
    }

    /** Every callback must reach the delegate: the SPI has a single slot, so the decorator is the only path to it. */
    @Test
    void every_callback_is_forwarded_to_the_delegate() {
        var subscription = subscription();
        var lock         = mock(FencedLock.class);
        var handler      = eventHandler();
        var cause        = new IllegalStateException("boom");

        observer.startingSubscriber(subscription);
        observer.startedSubscriber(subscription, Duration.ofMillis(1));
        observer.stoppingSubscriber(subscription);
        observer.stoppedSubscriber(subscription, Duration.ofMillis(1));
        observer.resolvedBatchSizeForEventStorePoll(SUBSCRIBER_ID, AGGREGATE_TYPE, 10, 10, 10, 2, 1, 10, Duration.ofMillis(1));
        observer.skippingPollingDueToNoNewEventsPersisted(SUBSCRIBER_ID, AGGREGATE_TYPE, 10, 10, 10, 2, 1, 0);
        observer.eventStorePolled(SUBSCRIBER_ID, AGGREGATE_TYPE, LongRange.from(1, 10), List.of(), Optional.empty(),
                                  List.of(), Duration.ofMillis(1));
        observer.reconciledGaps(SUBSCRIBER_ID, AGGREGATE_TYPE, LongRange.from(1, 10), List.of(), List.of(), Duration.ofMillis(1));
        observer.publishEvent(SUBSCRIBER_ID, AGGREGATE_TYPE, event(1), Duration.ofMillis(1));
        observer.requestingEvents(10, subscription);
        observer.lockAcquired(lock, subscription);
        observer.lockReleased(lock, subscription);
        observer.handleEvent(event(1), handler, subscription, Duration.ofMillis(1));
        observer.handleEventFailed(event(1), handler, cause, subscription);
        observer.resolveResumePoint(null, GlobalEventOrder.of(1), subscription, Duration.ofMillis(1));
        observer.unsubscribing(subscription);
        observer.resettingFrom(GlobalEventOrder.of(1), subscription);

        verify(delegate).startingSubscriber(subscription);
        verify(delegate).startedSubscriber(subscription, Duration.ofMillis(1));
        verify(delegate).stoppingSubscriber(subscription);
        verify(delegate).stoppedSubscriber(subscription, Duration.ofMillis(1));
        verify(delegate).resolvedBatchSizeForEventStorePoll(SUBSCRIBER_ID, AGGREGATE_TYPE, 10, 10, 10, 2, 1, 10, Duration.ofMillis(1));
        verify(delegate).skippingPollingDueToNoNewEventsPersisted(SUBSCRIBER_ID, AGGREGATE_TYPE, 10, 10, 10, 2, 1, 0);
        verify(delegate).eventStorePolled(eq(SUBSCRIBER_ID), eq(AGGREGATE_TYPE), any(), any(), any(), any(), any());
        verify(delegate).reconciledGaps(eq(SUBSCRIBER_ID), eq(AGGREGATE_TYPE), any(), any(), any(), any());
        verify(delegate).publishEvent(eq(SUBSCRIBER_ID), eq(AGGREGATE_TYPE), any(), any());
        verify(delegate).requestingEvents(10, subscription);
        verify(delegate).lockAcquired(lock, subscription);
        verify(delegate).lockReleased(lock, subscription);
        verify(delegate).handleEvent(any(PersistedEvent.class), eq(handler), eq(subscription), any());
        verify(delegate).handleEventFailed(any(PersistedEvent.class), eq(handler), eq(cause), eq(subscription));
        verify(delegate).resolveResumePoint(isNull(), eq(GlobalEventOrder.of(1)), eq(subscription), any());
        verify(delegate).unsubscribing(subscription);
        verify(delegate).resettingFrom(GlobalEventOrder.of(1), subscription);
        verifyNoMoreInteractions(delegate);
    }

    /** Observability must never be the thing that breaks a subscription. */
    @Test
    void a_failure_while_recording_is_swallowed_and_the_delegate_still_runs() {
        var failingRegistry = new SubscriptionStatisticsRegistry(10, Clock.fixed(NOW, ZoneOffset.UTC)) {
            @Override
            Optional<MutableSubscriptionStatistics> statisticsFor(SubscriptionKey key) {
                throw new IllegalStateException("registry is broken");
            }
        };
        var countingDelegate = new CountingObserver();
        var brittleObserver  = new StatisticsCollectingEventStoreSubscriptionObserver(countingDelegate, failingRegistry);
        var subscription     = subscription();

        assertThatNoException().isThrownBy(() -> brittleObserver.startedSubscriber(subscription, Duration.ofMillis(1)));
        assertThatNoException().isThrownBy(() -> brittleObserver.handleEvent(event(1), eventHandler(), subscription, Duration.ofMillis(1)));
        assertThat(countingDelegate.callbacks).isEqualTo(2);
    }

    @Test
    void the_registry_stops_tracking_new_subscriptions_at_its_configured_maximum() {
        var boundedRegistry = new SubscriptionStatisticsRegistry(2, Clock.fixed(NOW, ZoneOffset.UTC));
        var boundedObserver = new StatisticsCollectingEventStoreSubscriptionObserver(new NoOpEventStoreSubscriptionObserver(),
                                                                                    boundedRegistry);

        for (int i = 0; i < 5; i++) {
            boundedObserver.startedSubscriber(subscription(SubscriberId.of("subscriber-" + i), AGGREGATE_TYPE),
                                              Duration.ofMillis(1));
        }

        assertThat(boundedRegistry.trackedSubscriptions()).isEqualTo(2);
        assertThat(boundedRegistry.maxTrackedSubscriptions()).isEqualTo(2);
        assertThat(boundedRegistry.findStatistics(SubscriberId.of("subscriber-0"), AGGREGATE_TYPE)).isPresent();
        assertThat(boundedRegistry.findStatistics(SubscriberId.of("subscriber-4"), AGGREGATE_TYPE)).isEmpty();
    }

    private SubscriptionStatistics statistics() {
        return registry.findStatistics(SUBSCRIBER_ID, AGGREGATE_TYPE).orElseThrow();
    }

    private EventStoreSubscription subscription() {
        return subscription(SUBSCRIBER_ID, AGGREGATE_TYPE);
    }

    private EventStoreSubscription subscription(SubscriberId subscriberId, AggregateType aggregateType) {
        var subscription = mock(EventStoreSubscription.class);
        when(subscription.subscriberId()).thenReturn(subscriberId);
        when(subscription.aggregateType()).thenReturn(aggregateType);
        return subscription;
    }

    private PersistedEvent event(long globalEventOrder) {
        var event = mock(PersistedEvent.class);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalEventOrder));
        return event;
    }

    private PersistedEventHandler eventHandler() {
        return mock(PersistedEventHandler.class);
    }

    /** Counts forwarded callbacks without Mockito, so that the swallowing test cannot pass on a stubbing detail. */
    private static class CountingObserver extends NoOpEventStoreSubscriptionObserver {
        private int callbacks;

        @Override
        public void startedSubscriber(EventStoreSubscription eventStoreSubscription, Duration startDuration) {
            callbacks++;
        }

        @Override
        public void handleEvent(PersistedEvent event,
                                PersistedEventHandler eventHandler,
                                EventStoreSubscription eventStoreSubscription,
                                Duration handleEventDuration) {
            callbacks++;
        }
    }
}
