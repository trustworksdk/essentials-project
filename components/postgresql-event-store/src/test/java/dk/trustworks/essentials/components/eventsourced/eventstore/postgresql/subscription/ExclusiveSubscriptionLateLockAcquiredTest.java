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
import dk.trustworks.essentials.components.foundation.fencedlock.LockCallback;
import dk.trustworks.essentials.components.foundation.test.fencedlock.ShutdownOverlapFencedLockManager;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.reactive.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;

import java.time.*;
import java.util.function.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * During a shutdown the lock manager can release a subscription's lock locally, and call <code>lockReleased</code>, before
 * the async acquiring tick's <code>lockAcquired</code> has run (see {@link LockCallback}). A subscription stopped in that
 * window must not start subscribing when the late <code>lockAcquired</code> arrives: it would deliver events without the
 * lock, after its own <code>stop()</code>.
 * <p>
 * {@link ShutdownOverlapFencedLockManager} replays the overlap without timing; the controls show an ordinary
 * <code>lockAcquired</code> still starts the subscription. Mocks throughout, so no database is involved.
 */
class ExclusiveSubscriptionLateLockAcquiredTest {
    private static final AggregateType ORDERS     = AggregateType.of("Orders");
    private static final SubscriberId  SUBSCRIBER = SubscriberId.of("Subscriber");

    private EventStore                       eventStore;
    private EventBus                         localEventBus;
    private DurableSubscriptionRepository    durableSubscriptionRepository;
    private ShutdownOverlapFencedLockManager fencedLockManager;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        eventStore = mock(EventStore.class);
        localEventBus = mock(EventBus.class);
        when(eventStore.localEventBus()).thenReturn(localEventBus);
        when(eventStore.pollEvents(any(AggregateType.class), any(GlobalEventOrder.class), any(), any(), any(), any(), any(), any(SubscriberAcknowledgement.class)))
                .thenReturn(Flux.never());
        durableSubscriptionRepository = mock(DurableSubscriptionRepository.class);
        when(durableSubscriptionRepository.getOrCreateResumePoint(any(SubscriberId.class), any(AggregateType.class), any(Function.class)))
                .thenAnswer(invocation -> new SubscriptionResumePoint(SUBSCRIBER, ORDERS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, OffsetDateTime.now()));
        fencedLockManager = new ShutdownOverlapFencedLockManager();
        fencedLockManager.start();
    }

    // ------------------------------------------------ ExclusiveAsynchronousSubscription ------------------------------------------------

    @Test
    void exclusive_async_subscription_does_not_subscribe_when_lockAcquired_arrives_after_stop_released_the_lock() {
        var subscription = exclusiveAsynchronousSubscription();
        subscription.start();

        subscription.stop();
        fencedLockManager.deliverLateLockAcquired(subscription.lockName());

        assertThat(fencedLockManager.wasCancelled(subscription.lockName())).isTrue();
        assertThat(subscription.isActive()).isFalse();
        verify(durableSubscriptionRepository, never()).getOrCreateResumePoint(any(SubscriberId.class), any(AggregateType.class), any(Function.class));
        verifyNoPolling();
    }

    @Test
    void exclusive_async_subscription_subscribes_on_an_ordinary_lockAcquired() {
        var subscription = exclusiveAsynchronousSubscription();
        subscription.start();

        fencedLockManager.deliverLockAcquired(subscription.lockName());

        assertThat(subscription.isActive()).isTrue();
        verify(durableSubscriptionRepository).getOrCreateResumePoint(eq(SUBSCRIBER), eq(ORDERS), any(Function.class));
        verifyPolled();
    }

    // ------------------------------------------------ ExclusiveBatchedAsynchronousSubscription ------------------------------------------------

    @Test
    void exclusive_batched_subscription_does_not_subscribe_when_lockAcquired_arrives_after_stop_released_the_lock() {
        var subscription = exclusiveBatchedAsynchronousSubscription();
        subscription.start();

        subscription.stop();
        fencedLockManager.deliverLateLockAcquired(subscription.lockName());

        assertThat(fencedLockManager.wasCancelled(subscription.lockName())).isTrue();
        assertThat(subscription.isActive()).isFalse();
        verify(durableSubscriptionRepository, never()).getOrCreateResumePoint(any(SubscriberId.class), any(AggregateType.class), any(Function.class));
        verifyNoPolling();
    }

    @Test
    void exclusive_batched_subscription_subscribes_on_an_ordinary_lockAcquired() {
        var subscription = exclusiveBatchedAsynchronousSubscription();
        subscription.start();

        fencedLockManager.deliverLockAcquired(subscription.lockName());

        assertThat(subscription.isActive()).isTrue();
        verify(durableSubscriptionRepository).getOrCreateResumePoint(eq(SUBSCRIBER), eq(ORDERS), any(Function.class));
        verifyPolled();
    }

    // ------------------------------------------------ ExclusiveInTransactionSubscription ------------------------------------------------

    @Test
    void exclusive_in_transaction_subscription_does_not_add_its_handler_when_lockAcquired_arrives_after_stop_released_the_lock() {
        var subscription = exclusiveInTransactionSubscription();
        subscription.start();

        subscription.stop();
        fencedLockManager.deliverLateLockAcquired(subscription.lockName());

        assertThat(fencedLockManager.wasCancelled(subscription.lockName())).isTrue();
        assertThat(subscription.isActive()).isFalse();
        verify(localEventBus, never()).addSyncSubscriber(any(EventHandler.class));
    }

    @Test
    void exclusive_in_transaction_subscription_adds_its_handler_on_an_ordinary_lockAcquired() {
        var subscription = exclusiveInTransactionSubscription();
        subscription.start();

        fencedLockManager.deliverLockAcquired(subscription.lockName());

        assertThat(subscription.isActive()).isTrue();
        verify(localEventBus).addSyncSubscriber(any(EventHandler.class));
    }

    // -------------------------------------------------------------------------------------------------------------------

    private void verifyNoPolling() {
        verify(eventStore, never()).pollEvents(any(AggregateType.class), any(GlobalEventOrder.class), any(), any(), any(), any(), any(), any(SubscriberAcknowledgement.class));
    }

    private void verifyPolled() {
        verify(eventStore).pollEvents(eq(ORDERS), any(GlobalEventOrder.class), any(), any(), any(), any(), any(), any(SubscriberAcknowledgement.class));
    }

    @SuppressWarnings("unchecked")
    private EventStoreSubscriptionContext context() {
        return new EventStoreSubscriptionContext(eventStore,
                                                 ORDERS,
                                                 SUBSCRIBER,
                                                 null,
                                                 mock(EventStoreSubscriptionObserver.class),
                                                 mock(Consumer.class),
                                                 eventStreamLogName -> mock(EventStorePollingOptimizer.class));
    }

    private DurableSubscriptionContext durableContext() {
        return DurableSubscriptionContext.fromFixedGlobalOrder(durableSubscriptionRepository,
                                                               GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                               new EventStoreSubscriptionManagerSettings(10, Duration.ofMillis(100), Duration.ofSeconds(1)));
    }

    private ExclusiveAsynchronousSubscription exclusiveAsynchronousSubscription() {
        return new ExclusiveAsynchronousSubscription(context(),
                                                     durableContext(),
                                                     fencedLockManager,
                                                     mock(FencedLockAwareSubscriber.class),
                                                     mock(PersistedEventHandler.class));
    }

    private ExclusiveBatchedAsynchronousSubscription exclusiveBatchedAsynchronousSubscription() {
        return new ExclusiveBatchedAsynchronousSubscription(context(),
                                                            durableContext(),
                                                            fencedLockManager,
                                                            mock(FencedLockAwareSubscriber.class),
                                                            new ExclusiveBatchedAsynchronousSubscription.BatchedDelivery(10,
                                                                                                                        Duration.ofMillis(100),
                                                                                                                        mock(BatchedPersistedEventHandler.class)));
    }

    private ExclusiveInTransactionSubscription exclusiveInTransactionSubscription() {
        return new ExclusiveInTransactionSubscription(context(),
                                                      fencedLockManager,
                                                      mock(TransactionalPersistedEventHandler.class));
    }
}
