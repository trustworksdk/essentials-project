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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.Test;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.retry.Retry;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link BatchedPersistedEventSubscriber} hands a failed batch to
 * {@link BatchedPersistedEventHandler#handOffFailedBatch(List, Throwable, SubscriptionErrorPolicy)} together with the
 * policy it was built with, so a handler never has to work that policy out for itself.
 * <p>
 * Mocks only: no event store, no database.
 */
class BatchedPersistedEventSubscriberHandOffTest {
    private static final SubscriberId  SUBSCRIBER_ID  = SubscriberId.of("HandOffSubscriber");
    private static final AggregateType AGGREGATE_TYPE = AggregateType.of("Orders");

    @Test
    void the_hand_off_is_given_the_policy_the_subscriber_was_built_with() {
        var policy         = SubscriptionErrorPolicy.retryThenSkip(2);
        var policyHandedIn = new AtomicReference<SubscriptionErrorPolicy>();
        var handler = new BatchedPersistedEventHandler() {
            @Override
            public int handleBatch(List<PersistedEvent> events) {
                return 1;
            }

            @Override
            public boolean handOffFailedBatch(List<PersistedEvent> events, Throwable failure, SubscriptionErrorPolicy subscriptionErrorPolicy) {
                policyHandedIn.set(subscriptionErrorPolicy);
                return true;
            }
        };
        var subscriber = subscriber(handler, policy);
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event()), new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.TAKEN_OVER);
            assertThat(policyHandedIn.get()).isSameAs(policy);
        } finally {
            subscriber.dispose();
        }
    }

    @Test
    void a_handler_overriding_only_the_two_argument_hand_off_is_still_called() {
        var called = new AtomicBoolean();
        var handler = new BatchedPersistedEventHandler() {
            @Override
            public int handleBatch(List<PersistedEvent> events) {
                return 1;
            }

            @Override
            public boolean handOffFailedBatch(List<PersistedEvent> events, Throwable failure) {
                called.set(true);
                return true;
            }
        };
        var subscriber = subscriber(handler, SubscriptionErrorPolicy.skip());
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event()), new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.TAKEN_OVER);
            assertThat(called.get()).isTrue();
        } finally {
            subscriber.dispose();
        }
    }

    @Test
    void a_hand_off_asking_for_redelivery_holds_the_resume_point_at_the_first_event_to_redeliver_and_is_resumed_there() {
        var events = List.of(event(1), event(2), event(3));
        var handler = new BatchedPersistedEventHandler() {
            @Override
            public int handleBatch(List<PersistedEvent> batch) {
                return 1;
            }

            @Override
            public boolean handOffFailedBatch(List<PersistedEvent> batch, Throwable failure, SubscriptionErrorPolicy subscriptionErrorPolicy) {
                // Event 1 forwarded, then the replay ran out of time: events 2 and 3 are to be delivered again
                throw new FailedBatchToBeRedeliveredException("budget spent", GlobalEventOrder.of(2), null);
            }
        };
        var acknowledged = new ArrayList<GlobalEventOrder>();
        var subscription = subscription();
        var resumePoint  = subscription.currentResumePoint().get();
        var subscriber   = subscriber(handler, SubscriptionErrorPolicy.skip(), subscription, acknowledged);
        var scheduler    = VirtualTimeScheduler.create();
        subscriber.autoResumer = new SubscriptionAutoResumer(subscription, () -> false, scheduler);
        try {
            assertThat(subscriber.handedOffToEventHandler(events, new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.TO_BE_REDELIVERED);

            // Not skipped: only the event before the first one to deliver again is acknowledged, and the resume point stays at #2
            assertThat(acknowledged).containsExactly(GlobalEventOrder.of(1));
            assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(2));
            assertThat(subscriber.isStoppedByErrorPolicy()).isTrue();
            assertThat(subscriber.autoResumer.isAwaitingRecovery()).isTrue();

            // The subscription is resumed at #2 after the auto-resume delay, which delivers events 2 and 3 again
            verify(subscription, never()).resumeIfStoppedByErrorPolicy();
            scheduler.advanceTimeBy(SubscriptionErrorPolicy.AutoResume.DEFAULT_INITIAL_DELAY);
            verify(subscription).resumeIfStoppedByErrorPolicy();
        } finally {
            subscriber.dispose();
            scheduler.dispose();
        }
    }

    @Test
    void a_hand_off_asking_for_redelivery_is_skipped_by_the_policy_when_the_subscriber_cannot_be_resumed() {
        var handler = new BatchedPersistedEventHandler() {
            @Override
            public int handleBatch(List<PersistedEvent> batch) {
                return 1;
            }

            @Override
            public boolean handOffFailedBatch(List<PersistedEvent> batch, Throwable failure, SubscriptionErrorPolicy subscriptionErrorPolicy) {
                throw new FailedBatchToBeRedeliveredException("budget spent", GlobalEventOrder.of(2), null);
            }
        };
        var acknowledged = new ArrayList<GlobalEventOrder>();
        // No auto-resumer: built outside an EventStoreSubscriptionManager
        var subscriber = subscriber(handler, SubscriptionErrorPolicy.skip(), subscription(), acknowledged);
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event(1), event(2)), new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.NOT_TAKEN_OVER);
            assertThat(acknowledged).isEmpty();
            assertThat(subscriber.isStoppedByErrorPolicy()).isFalse();
        } finally {
            subscriber.dispose();
        }
    }

    @Test
    void a_redelivery_reports_the_stop_and_the_events_lost_before_the_redelivery_point() {
        var events    = List.of(event(1), event(2), event(3));
        var lost      = new RuntimeException("event 2 could not be forwarded");
        var handler   = throwing(new FailedBatchToBeRedeliveredException("budget spent", GlobalEventOrder.of(3), lost));
        var observer  = mock(EventStoreSubscriptionObserver.class);
        var errors    = new ArrayList<GlobalEventOrder>();
        var subscription = subscription();
        var subscriber   = subscriber(handler, SubscriptionErrorPolicy.skip(), subscription, new ArrayList<>(), observer, (event, failure) -> {
            assertThat(failure).isSameAs(lost);
            errors.add(event.globalEventOrder());
        });
        var scheduler = VirtualTimeScheduler.create();
        subscriber.autoResumer = new SubscriptionAutoResumer(subscription, () -> false, scheduler);
        try {
            assertThat(subscriber.handedOffToEventHandler(events, new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.TO_BE_REDELIVERED);

            verify(observer).handleEventBatchFailed(eq(events.subList(0, 2)), eq(handler), eq(lost), eq(subscription));
            assertThat(errors).containsExactly(GlobalEventOrder.of(2));
            verify(observer).subscriptionStoppedByErrorPolicy(eq(GlobalEventOrder.of(3)), any(FailedBatchToBeRedeliveredException.class), eq(subscription));
        } finally {
            subscriber.dispose();
            scheduler.dispose();
        }
    }

    @Test
    void a_redelivery_still_holds_the_resume_point_when_the_observer_and_the_error_handler_throw() {
        var events       = List.of(event(1), event(2), event(3));
        var lost         = new RuntimeException("event 2 could not be forwarded");
        var observer     = mock(EventStoreSubscriptionObserver.class);
        doThrow(new IllegalStateException("observer failed")).when(observer).handleEventBatchFailed(any(), any(), any(), any());
        var acknowledged = new ArrayList<GlobalEventOrder>();
        var subscription = subscription();
        var resumePoint  = subscription.currentResumePoint().get();
        var subscriber   = subscriber(throwing(new FailedBatchToBeRedeliveredException("budget spent", GlobalEventOrder.of(3), lost)),
                                      SubscriptionErrorPolicy.skip(), subscription, acknowledged, observer, (event, failure) -> { throw new IllegalStateException("error handler failed"); });
        var scheduler    = VirtualTimeScheduler.create();
        subscriber.autoResumer = new SubscriptionAutoResumer(subscription, () -> false, scheduler);
        try {
            assertThat(subscriber.handedOffToEventHandler(events, new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.TO_BE_REDELIVERED);

            verify(observer).handleEventBatchFailed(eq(events.subList(0, 2)), any(), eq(lost), eq(subscription));
            assertThat(acknowledged).containsExactly(GlobalEventOrder.of(1), GlobalEventOrder.of(2));
            assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(3));
            assertThat(subscriber.isStoppedByErrorPolicy()).isTrue();
        } finally {
            subscriber.dispose();
            scheduler.dispose();
        }
    }

    @Test
    void a_redelivery_without_a_cause_reports_no_failed_batch() {
        var observer     = mock(EventStoreSubscriptionObserver.class);
        var subscription = subscription();
        var subscriber   = subscriber(throwing(new FailedBatchToBeRedeliveredException("budget spent", GlobalEventOrder.of(2), null)),
                                      SubscriptionErrorPolicy.skip(), subscription, new ArrayList<>(), observer, (event, failure) -> { throw new AssertionError("not lost"); });
        var scheduler    = VirtualTimeScheduler.create();
        subscriber.autoResumer = new SubscriptionAutoResumer(subscription, () -> false, scheduler);
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event(1), event(2)), new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.TO_BE_REDELIVERED);
            verify(observer, never()).handleEventBatchFailed(any(), any(), any(), any());
        } finally {
            subscriber.dispose();
            scheduler.dispose();
        }
    }

    @Test
    void a_redelivery_under_a_disabled_auto_resume_stops_and_holds_without_resuming() {
        var acknowledged = new ArrayList<GlobalEventOrder>();
        var subscription = subscription();
        var resumePoint  = subscription.currentResumePoint().get();
        var subscriber   = subscriber(throwing(new FailedBatchToBeRedeliveredException("budget spent", GlobalEventOrder.of(2), null)),
                                      SubscriptionErrorPolicy.skip().withoutAutoResume(), subscription, acknowledged);
        var scheduler    = VirtualTimeScheduler.create();
        subscriber.autoResumer = new SubscriptionAutoResumer(subscription, () -> false, scheduler);
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event(1), event(2), event(3)), new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.TO_BE_REDELIVERED);

            assertThat(acknowledged).containsExactly(GlobalEventOrder.of(1));
            assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(2));
            assertThat(subscriber.isStoppedByErrorPolicy()).isTrue();
            scheduler.advanceTimeBy(SubscriptionErrorPolicy.AutoResume.DEFAULT_MAX_DELAY.multipliedBy(2));
            verify(subscription, never()).resumeIfStoppedByErrorPolicy();
        } finally {
            subscriber.dispose();
            scheduler.dispose();
        }
    }

    @Test
    void a_redelivery_whose_resumes_at_the_event_are_used_up_is_left_to_the_policy() {
        var policy       = SubscriptionErrorPolicy.skip().withAutoResume(SubscriptionErrorPolicy.AutoResume.skippingAfter(1, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        var acknowledged = new ArrayList<GlobalEventOrder>();
        var subscription = subscription();
        var subscriber   = subscriber(throwing(new FailedBatchToBeRedeliveredException("not owned", GlobalEventOrder.of(2), null)), policy, subscription, acknowledged);
        var scheduler    = VirtualTimeScheduler.create();
        subscriber.autoResumer = new SubscriptionAutoResumer(subscription, () -> false, scheduler);
        try {
            // Resumed once at #2 already
            subscriber.autoResumer.stoppedForRedeliveryAt(GlobalEventOrder.of(2), policy);
            scheduler.advanceTimeBy(Duration.ofSeconds(1));

            assertThat(subscriber.handedOffToEventHandler(List.of(event(1), event(2)), new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.NOT_TAKEN_OVER);
            assertThat(acknowledged).isEmpty();
            assertThat(subscriber.isStoppedByErrorPolicy()).isFalse();
        } finally {
            subscriber.dispose();
            scheduler.dispose();
        }
    }

    @Test
    void a_redelivery_after_the_subscriber_was_stopped_only_holds_the_resume_point() {
        var acknowledged = new ArrayList<GlobalEventOrder>();
        var subscription = subscription();
        var resumePoint  = subscription.currentResumePoint().get();
        var subscriberRef = new AtomicReference<BatchedPersistedEventSubscriber>();
        var handler = new BatchedPersistedEventHandler() {
            @Override
            public int handleBatch(List<PersistedEvent> batch) {
                return 1;
            }

            @Override
            public boolean handOffFailedBatch(List<PersistedEvent> batch, Throwable failure, SubscriptionErrorPolicy subscriptionErrorPolicy) {
                // The lock release or a stop disposed the subscriber while the replay ran
                subscriberRef.get().dispose();
                throw new FailedBatchToBeRedeliveredException("not owned", GlobalEventOrder.of(2), null);
            }
        };
        var subscriber = subscriber(handler, SubscriptionErrorPolicy.skip(), subscription, acknowledged);
        subscriberRef.set(subscriber);
        var scheduler = VirtualTimeScheduler.create();
        subscriber.autoResumer = new SubscriptionAutoResumer(subscription, () -> false, scheduler);
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event(1), event(2), event(3)), new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.TO_BE_REDELIVERED);

            assertThat(acknowledged).containsExactly(GlobalEventOrder.of(1));
            assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(2));
            assertThat(subscriber.isStoppedByErrorPolicy()).isFalse();
            scheduler.advanceTimeBy(SubscriptionErrorPolicy.AutoResume.DEFAULT_MAX_DELAY.multipliedBy(2));
            verify(subscription, never()).resumeIfStoppedByErrorPolicy();
        } finally {
            subscriber.dispose();
            scheduler.dispose();
        }
    }

    @Test
    void a_redelivery_signal_wrapped_as_a_cause_is_recognised() {
        var subscription = subscription();
        var subscriber   = subscriber(throwing(new RuntimeException("wrapped", new FailedBatchToBeRedeliveredException("budget spent", GlobalEventOrder.of(2), null))),
                                      SubscriptionErrorPolicy.skip(), subscription, new ArrayList<>());
        var scheduler    = VirtualTimeScheduler.create();
        subscriber.autoResumer = new SubscriptionAutoResumer(subscription, () -> false, scheduler);
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event(1), event(2)), new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.TO_BE_REDELIVERED);
        } finally {
            subscriber.dispose();
            scheduler.dispose();
        }
    }

    @Test
    void a_redelivery_point_past_the_batch_is_left_to_the_policy() {
        var acknowledged = new ArrayList<GlobalEventOrder>();
        var subscription = subscription();
        var subscriber   = subscriber(throwing(new FailedBatchToBeRedeliveredException("past", GlobalEventOrder.of(99), null)),
                                      SubscriptionErrorPolicy.skip(), subscription, acknowledged);
        var scheduler    = VirtualTimeScheduler.create();
        subscriber.autoResumer = new SubscriptionAutoResumer(subscription, () -> false, scheduler);
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event(1), event(2)), new RuntimeException("batch failed"))).isEqualTo(BatchedPersistedEventSubscriber.HandOff.NOT_TAKEN_OVER);
            assertThat(acknowledged).isEmpty();
            assertThat(subscriber.isStoppedByErrorPolicy()).isFalse();
        } finally {
            subscriber.dispose();
            scheduler.dispose();
        }
    }

    // -------------------------------------------------------------------------------------------------------------------

    private static BatchedPersistedEventHandler throwing(RuntimeException handOffFailure) {
        return new BatchedPersistedEventHandler() {
            @Override
            public int handleBatch(List<PersistedEvent> batch) {
                return 1;
            }

            @Override
            public boolean handOffFailedBatch(List<PersistedEvent> batch, Throwable failure, SubscriptionErrorPolicy subscriptionErrorPolicy) {
                throw handOffFailure;
            }
        };
    }

    private static EventStoreSubscription subscription() {
        var subscription = mock(EventStoreSubscription.class);
        when(subscription.subscriberId()).thenReturn(SUBSCRIBER_ID);
        when(subscription.aggregateType()).thenReturn(AGGREGATE_TYPE);
        when(subscription.currentResumePoint()).thenReturn(Optional.of(new SubscriptionResumePoint(SUBSCRIBER_ID,
                                                                                                    AGGREGATE_TYPE,
                                                                                                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                    OffsetDateTime.now())));
        when(subscription.resumeIfStoppedByErrorPolicy()).thenReturn(true);
        return subscription;
    }

    private static BatchedPersistedEventSubscriber subscriber(BatchedPersistedEventHandler handler,
                                                              SubscriptionErrorPolicy policy,
                                                              EventStoreSubscription subscription,
                                                              List<GlobalEventOrder> acknowledged) {
        return subscriber(handler, policy, subscription, acknowledged, mock(EventStoreSubscriptionObserver.class), (event, failure) -> { });
    }

    private static BatchedPersistedEventSubscriber subscriber(BatchedPersistedEventHandler handler,
                                                              SubscriptionErrorPolicy policy,
                                                              EventStoreSubscription subscription,
                                                              List<GlobalEventOrder> acknowledged,
                                                              EventStoreSubscriptionObserver observer,
                                                              BiConsumer<PersistedEvent, Throwable> onErrorHandler) {
        var eventStore = mock(EventStore.class);
        when(eventStore.getEventStoreSubscriptionObserver()).thenReturn(observer);
        return new BatchedPersistedEventSubscriberBuilder()
                .setEventHandler(handler)
                .setEventStoreSubscription(subscription)
                .setOnErrorHandler(onErrorHandler)
                .setForwardToEventHandlerRetryBackoffSpec(Retry.backoff(0, Duration.ofMillis(1)))
                .setEventStorePollingBatchSize(10)
                .setEventStore(eventStore)
                .setMaxBatchSize(10)
                .setMaxLatency(Duration.ofHours(1))
                .setSubscriptionErrorPolicy(policy)
                .setSubscriberAcknowledgement(recording(acknowledged))
                .build();
    }

    private static SubscriberAcknowledgement recording(List<GlobalEventOrder> acknowledged) {
        var acknowledgement = SubscriberAcknowledgement.create();
        acknowledgement.onAcknowledge(events -> events.forEach(event -> acknowledged.add(event.globalEventOrder())));
        return acknowledgement;
    }

    private static BatchedPersistedEventSubscriber subscriber(BatchedPersistedEventHandler handler, SubscriptionErrorPolicy policy) {
        var subscription = mock(EventStoreSubscription.class);
        when(subscription.subscriberId()).thenReturn(SUBSCRIBER_ID);
        when(subscription.aggregateType()).thenReturn(AGGREGATE_TYPE);
        when(subscription.currentResumePoint()).thenReturn(Optional.of(new SubscriptionResumePoint(SUBSCRIBER_ID,
                                                                                                    AGGREGATE_TYPE,
                                                                                                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                    OffsetDateTime.now())));
        return new BatchedPersistedEventSubscriber(handler,
                                                   subscription,
                                                   (event, failure) -> { },
                                                   Retry.backoff(0, Duration.ofMillis(1)),
                                                   10,
                                                   mock(EventStore.class),
                                                   10,
                                                   Duration.ofHours(1),
                                                   policy);
    }

    private static PersistedEvent event() {
        return event(1);
    }

    private static PersistedEvent event(long globalEventOrder) {
        var event = mock(PersistedEvent.class);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalEventOrder));
        return event;
    }
}
