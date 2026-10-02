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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.functional.CheckedFunction;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.reactivestreams.Subscription;
import reactor.util.retry.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Container-free tests of how {@link PersistedEventSubscriber} and {@link BatchedPersistedEventSubscriber} keep the
 * resume point when a {@link SubscriptionErrorPolicy} stops, or when the subscriber is stopped in the middle of a retry.
 * The subscribers are fed directly, so the concurrent interleavings the ITs cannot force are deterministic here.
 */
class PersistedEventSubscriberResumePointTest {
    private static final AggregateType ORDERS        = AggregateType.of("Orders");
    private static final SubscriberId  SUBSCRIBER_ID = SubscriberId.of("ResumePointSubscriber");

    private SubscriptionResumePoint                           resumePoint;
    private EventStoreSubscription                            eventStoreSubscription;
    private EventStoreSubscriptionObserver                    observer;
    private EventStore                                        eventStore;
    private BiConsumer<PersistedEvent, Throwable>             onErrorHandler;
    private final List<Thread>                                threadsToStop = new CopyOnWriteArrayList<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() throws Exception {
        resumePoint = new SubscriptionResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(2), OffsetDateTime.now());
        eventStoreSubscription = mock(EventStoreSubscription.class);
        when(eventStoreSubscription.subscriberId()).thenReturn(SUBSCRIBER_ID);
        when(eventStoreSubscription.aggregateType()).thenReturn(ORDERS);
        when(eventStoreSubscription.currentResumePoint()).thenReturn(Optional.of(resumePoint));

        EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        when(unitOfWorkFactory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(invocation -> ((CheckedFunction<Object, Object>) invocation.getArgument(0)).apply(null));
        observer = mock(EventStoreSubscriptionObserver.class);
        eventStore = mock(EventStore.class);
        when(eventStore.getUnitOfWorkFactory()).thenReturn(unitOfWorkFactory);
        when(eventStore.getEventStoreSubscriptionObserver()).thenReturn(observer);
        onErrorHandler = mock(BiConsumer.class);
    }

    @AfterEach
    void cleanup() {
        threadsToStop.forEach(Thread::interrupt);
    }

    @Test
    void stop_rewinds_a_resume_point_a_later_event_already_advanced() throws InterruptedException {
        var mayFailTheRetry = new CountDownLatch(1);
        var attemptsAtTwo   = new AtomicInteger();
        var subscriber = new PersistedEventSubscriber(event -> {
            if (event.globalEventOrder().longValue() == 2) {
                if (attemptsAtTwo.incrementAndGet() == 1) {
                    // Retried asynchronously by the I/O RetryBackoffSpec, which frees the delivery thread for #3
                    throw new UncheckedIOException(new IOException("Intentional I/O failure handling #2"));
                }
                awaitQuietly(mayFailTheRetry);
                throw new IllegalStateException("Intentional failure handling #2");
            }
        },
                                                      eventStoreSubscription,
                                                      onErrorHandler,
                                                      ioRetrySpec(),
                                                      10,
                                                      eventStore,
                                                      SubscriptionErrorPolicy.stop());
        subscriber.onSubscribe(mock(Subscription.class));

        subscriber.onNext(event(2));
        subscriber.onNext(event(3));
        // #3 completed while #2 waits for its I/O retry, so the resume point is past the event that is about to fail
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(4));
        mayFailTheRetry.countDown();

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscriber::isStoppedByErrorPolicy);
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(2));
        verify(observer, timeout(5000)).subscriptionStoppedByErrorPolicy(eq(GlobalEventOrder.of(2)), any(), same(eventStoreSubscription));
        verifyNoInteractions(onErrorHandler);
        // A later completion must not advance it again
        subscriber.onNext(event(4));
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(2));
    }

    @Test
    void batched_stop_rewinds_a_resume_point_a_later_batch_already_advanced() {
        var mayFailTheRetry     = new CountDownLatch(1);
        var attemptsAtTwo       = new AtomicInteger();
        var subscriber = new BatchedPersistedEventSubscriber(events -> {
            if (events.getFirst().globalEventOrder().longValue() == 2) {
                if (attemptsAtTwo.incrementAndGet() == 1) {
                    throw new UncheckedIOException(new IOException("Intentional I/O failure handling the batch [#2]"));
                }
                awaitQuietly(mayFailTheRetry);
                throw new IllegalStateException("Intentional failure handling the batch [#2]");
            }
            return events.size();
        },
                                                             eventStoreSubscription,
                                                             onErrorHandler,
                                                             ioRetrySpec(),
                                                             10,
                                                             eventStore,
                                                             1,
                                                             Duration.ofMinutes(1),
                                                             SubscriptionErrorPolicy.stop());
        subscriber.onSubscribe(mock(Subscription.class));

        subscriber.onNext(event(2));
        subscriber.onNext(event(3));
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(4)));
        mayFailTheRetry.countDown();

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscriber::isStoppedByErrorPolicy);
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(2));
        verify(observer, timeout(5000)).subscriptionStoppedByErrorPolicy(eq(GlobalEventOrder.of(2)), any(), same(eventStoreSubscription));
        verifyNoInteractions(onErrorHandler);
    }

    @Test
    void stopping_the_subscriber_during_a_retry_backoff_neither_skips_nor_reports_the_event() {
        var attempts = new AtomicInteger();
        var subscriber = new PersistedEventSubscriber(event -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Intentional failure handling #" + event.globalEventOrder());
        },
                                                      eventStoreSubscription,
                                                      onErrorHandler,
                                                      ioRetrySpec(),
                                                      10,
                                                      eventStore,
                                                      SubscriptionErrorPolicy.retryThenSkip(5, Duration.ofSeconds(5), Duration.ofSeconds(5)));
        subscriber.onSubscribe(mock(Subscription.class));
        // The delivery thread - what the polling flux's Publish-thread is in production
        var deliveryThread = Thread.ofPlatform().start(() -> subscriber.onNext(event(2)));
        threadsToStop.add(deliveryThread);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> attempts.get() == 1);

        // What stop() does: dispose the subscriber, which shuts the delivery thread down and so interrupts the backoff
        subscriber.dispose();
        deliveryThread.interrupt();

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> !deliveryThread.isAlive());
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(2));
        assertThat(subscriber.isStoppedByErrorPolicy()).isFalse();
        verifyNoInteractions(onErrorHandler);
        verify(observer, never()).handleEventFailed(any(), any(PersistedEventHandler.class), any(), any());
    }

    /**
     * CdcEventStore's adaptive live source switching a subscription between polling and the CDC bus disposes the thread
     * the handler runs on, which interrupts its backoff - but nobody stopped the subscriber
     */
    @Test
    void an_interrupted_retry_backoff_without_a_stop_neither_halts_the_subscriber_nor_skips_the_event() {
        var attemptsAtTwo = new AtomicInteger();
        var handled       = new CopyOnWriteArrayList<Long>();
        var subscriber = new PersistedEventSubscriber(event -> {
            if (event.globalEventOrder().longValue() == 2 && attemptsAtTwo.incrementAndGet() == 1) {
                throw new IllegalStateException("Intentional failure handling #2");
            }
            handled.add(event.globalEventOrder().longValue());
        },
                                                      eventStoreSubscription,
                                                      onErrorHandler,
                                                      ioRetrySpec(),
                                                      10,
                                                      eventStore,
                                                      SubscriptionErrorPolicy.retryThenSkip(3, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        subscriber.onSubscribe(mock(Subscription.class));
        var deliveryThread = Thread.ofPlatform().start(() -> subscriber.onNext(event(2)));
        threadsToStop.add(deliveryThread);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> attemptsAtTwo.get() == 1);

        // The source switch: the old source's delivery thread is disposed, the subscriber is not
        deliveryThread.interrupt();

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> !deliveryThread.isAlive());
        assertThat(attemptsAtTwo.get()).isEqualTo(2);
        assertThat(handled).containsExactly(2L);
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(3));
        // The new source's next event is handled, not ignored as by a halted subscriber
        subscriber.onNext(event(3));
        assertThat(handled).containsExactly(2L, 3L);
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(4));
        assertThat(subscriber.isStoppedByErrorPolicy()).isFalse();
        verifyNoInteractions(onErrorHandler);
        verify(observer, never()).handleEventFailed(any(), any(PersistedEventHandler.class), any(), any());
    }

    @Test
    void a_handler_failing_after_the_subscription_restarted_leaves_the_restarted_resume_point_alone() {
        var mayFail = new CountDownLatch(1);
        var subscriber = new PersistedEventSubscriber(event -> {
            awaitQuietly(mayFail);
            throw new IllegalStateException("Intentional failure handling #" + event.globalEventOrder());
        },
                                                      eventStoreSubscription,
                                                      onErrorHandler,
                                                      ioRetrySpec(),
                                                      10,
                                                      eventStore,
                                                      SubscriptionErrorPolicy.skip());
        subscriber.onSubscribe(mock(Subscription.class));
        // A handler still blocked (say in a slow SQL call) when the subscription is stopped
        var deliveryThread = Thread.ofPlatform().start(() -> subscriber.onNext(event(2)));
        threadsToStop.add(deliveryThread);
        subscriber.dispose();
        // The restarted subscription (fenced-lock re-acquire, resetFrom) has its own resume point, here reset forward to #10
        var restartedResumePoint = new SubscriptionResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(10), OffsetDateTime.now());
        when(eventStoreSubscription.currentResumePoint()).thenReturn(Optional.of(restartedResumePoint));

        mayFail.countDown();

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> !deliveryThread.isAlive());
        assertThat(restartedResumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(10));
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(2));
        verifyNoInteractions(onErrorHandler);
    }

    @Test
    void batched_final_batch_handled_on_completion_gets_the_error_policy() {
        var attempts = new AtomicInteger();
        var subscriber = new BatchedPersistedEventSubscriber(events -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Intentional failure handling the final batch");
        },
                                                             eventStoreSubscription,
                                                             onErrorHandler,
                                                             ioRetrySpec(),
                                                             10,
                                                             eventStore,
                                                             10,
                                                             Duration.ofMinutes(1),
                                                             SubscriptionErrorPolicy.retryThenSkip(2, Duration.ofMillis(50), Duration.ofMillis(50)));
        subscriber.onSubscribe(mock(Subscription.class));
        subscriber.onNext(event(2));
        subscriber.onNext(event(3));

        // Completion marks the subscriber disposed before hookOnComplete handles what is left - that is not a stop
        subscriber.onComplete();

        verify(onErrorHandler, timeout(10_000)).accept(argThat(e -> e.globalEventOrder().equals(GlobalEventOrder.of(3))), any());
        assertThat(attempts.get()).isEqualTo(3);
        verify(observer).handleEventBatchFailed(any(), any(), any(), same(eventStoreSubscription));
    }

    @Test
    void a_failed_event_the_handler_takes_over_after_the_retries_is_neither_skipped_nor_reported() {
        var attempts        = new AtomicInteger();
        var handedOff       = new CopyOnWriteArrayList<PersistedEvent>();
        var subscriber = new PersistedEventSubscriber(new PersistedEventHandler() {
            @Override
            public void handle(PersistedEvent event) {
                attempts.incrementAndGet();
                throw new IllegalStateException("Intentional failure handling #" + event.globalEventOrder());
            }

            @Override
            public boolean handOffFailedEvent(PersistedEvent event, Throwable failure) {
                handedOff.add(event);
                return true;
            }
        },
                                                      eventStoreSubscription,
                                                      onErrorHandler,
                                                      ioRetrySpec(),
                                                      10,
                                                      eventStore,
                                                      SubscriptionErrorPolicy.retryThenSkip(2, Duration.ofMillis(10), Duration.ofMillis(10)));
        subscriber.onSubscribe(mock(Subscription.class));

        subscriber.onNext(event(2));

        // Offered only once the policy has used up its retries, in place of skipping
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(handedOff).extracting(PersistedEvent::globalEventOrder).containsExactly(GlobalEventOrder.of(2));
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(3));
        verify(eventStoreSubscription).request(1);
        verifyNoInteractions(onErrorHandler);
        verify(observer, never()).handleEventFailed(any(), any(PersistedEventHandler.class), any(), any());
    }

    @Test
    void a_failed_event_the_handler_cannot_take_over_gets_the_error_policy() {
        var subscriber = new PersistedEventSubscriber(new PersistedEventHandler() {
            @Override
            public void handle(PersistedEvent event) {
                throw new IllegalStateException("Intentional failure handling #" + event.globalEventOrder());
            }

            @Override
            public boolean handOffFailedEvent(PersistedEvent event, Throwable failure) {
                throw new IllegalStateException("Intentional failure taking over #" + event.globalEventOrder());
            }
        },
                                                      eventStoreSubscription,
                                                      onErrorHandler,
                                                      ioRetrySpec(),
                                                      10,
                                                      eventStore,
                                                      SubscriptionErrorPolicy.stop());
        subscriber.onSubscribe(mock(Subscription.class));

        subscriber.onNext(event(2));

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscriber::isStoppedByErrorPolicy);
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(2));
        verify(observer).handleEventFailed(any(), any(PersistedEventHandler.class), argThat(failure -> failure.getSuppressed().length == 1), any());
    }

    private static RetryBackoffSpec ioRetrySpec() {
        return Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(500))
                    .filter(IOExceptionUtil::isIOException);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static PersistedEvent event(long globalOrder) {
        return PersistedEvent.from(EventId.random(),
                                   ORDERS,
                                   OrderId.random(),
                                   new EventJSON(EssentialsJSONEventSerializers.create(), EventType.of("TestEvent"), "{\"globalOrder\":" + globalOrder + "}"),
                                   EventOrder.of(1L),
                                   EventRevision.of(1),
                                   GlobalEventOrder.of(globalOrder),
                                   new EventMetaDataJSON(EssentialsJSONEventSerializers.create(), "", ""),
                                   OffsetDateTime.now(),
                                   Optional.empty(),
                                   Optional.empty(),
                                   Optional.empty());
    }
}
