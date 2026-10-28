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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Function;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The {@link ViewEventProcessor}'s lock-released callback must not interleave with its lock-acquired callback.
 * <p>
 * The acquired callback starts the exclusive {@link DurableQueueConsumer} first and the event store subscriptions after
 * it. A release delivered from another thread in between - a lock manager being stopped while the acquired callback is
 * still running - used to see the consumer, fail with a {@link NullPointerException} on the not yet assigned
 * subscriptions, and so neither cancel the consumer nor stop the subscriptions created right after: a processor that had
 * lost its lock kept consuming.
 * <p>
 * Mocks throughout: the interleaving is forced by blocking the subscription manager inside the acquired callback.
 */
@SuppressWarnings("unchecked")
class ViewEventProcessorLockCallbackTest {
    private static final AggregateType THINGS = AggregateType.of("Things");
    private static final AggregateType OTHERS = AggregateType.of("Others");
    private static final AggregateType MORE   = AggregateType.of("More");

    @Test
    void a_release_delivered_while_the_lock_acquired_callback_runs_cancels_the_consumer_and_stops_the_subscriptions() throws Exception {
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(mock(EventStore.class));
        var fencedLockManager = mock(FencedLockManager.class);
        var durableQueues     = mock(DurableQueues.class);
        var consumer          = mock(DurableQueueConsumer.class);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class))).thenReturn(consumer);
        var subscription = mock(EventStoreSubscription.class);

        var subscribing       = new CountDownLatch(1);
        var finishSubscribing = new CountDownLatch(1);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(THINGS), any(Function.class), any(PersistedEventHandler.class)))
                .thenAnswer(invocation -> {
                    subscribing.countDown();
                    assertThat(finishSubscribing.await(10, TimeUnit.SECONDS)).isTrue();
                    return subscription;
                });

        var processor = new ThingsViewProcessor(subscriptionManager, fencedLockManager, durableQueues);
        processor.start();
        var callbackCaptor = ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), callbackCaptor.capture());
        var callback = callbackCaptor.getValue();
        var lock     = mock(FencedLock.class);

        var acquireFailure = new AtomicReference<Throwable>();
        var acquiring      = new Thread(() -> runCapturing(() -> callback.lockAcquired(lock), acquireFailure), "lock-acquired");
        acquiring.start();
        assertThat(subscribing.await(10, TimeUnit.SECONDS)).isTrue();

        // The release arrives while the acquired callback has started the consumer but not yet the subscriptions
        var releaseFailure = new AtomicReference<Throwable>();
        var releasing      = new Thread(() -> runCapturing(() -> callback.lockReleased(lock), releaseFailure), "lock-released");
        releasing.start();
        awaitBlockedOrTerminated(releasing);

        finishSubscribing.countDown();
        acquiring.join(10_000);
        releasing.join(10_000);

        assertThat(acquireFailure.get()).isNull();
        assertThat(releaseFailure.get())
                .describedAs("lockReleased must not fail when it races lockAcquired")
                .isNull();
        verify(consumer).cancel();
        verify(subscription).unsubscribe();
    }

    @Test
    void a_release_without_a_preceding_acquire_does_nothing() {
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(mock(EventStore.class));
        var fencedLockManager = mock(FencedLockManager.class);
        var durableQueues     = mock(DurableQueues.class);

        var processor = new ThingsViewProcessor(subscriptionManager, fencedLockManager, durableQueues);
        processor.start();
        var callbackCaptor = ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), callbackCaptor.capture());

        callbackCaptor.getValue().lockReleased(mock(FencedLock.class));

        verifyNoInteractions(durableQueues);
    }

    @Test
    void a_re_acquire_after_a_release_starts_a_new_consumer_and_the_next_release_cancels_only_that_one() {
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(mock(EventStore.class));
        var fencedLockManager = mock(FencedLockManager.class);
        var durableQueues     = mock(DurableQueues.class);
        var firstConsumer     = mock(DurableQueueConsumer.class);
        var secondConsumer    = mock(DurableQueueConsumer.class);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class))).thenReturn(firstConsumer, secondConsumer);
        var firstSubscription  = mock(EventStoreSubscription.class);
        var secondSubscription = mock(EventStoreSubscription.class);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(THINGS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(firstSubscription, secondSubscription);

        var processor = new ThingsViewProcessor(subscriptionManager, fencedLockManager, durableQueues);
        processor.start();
        var callbackCaptor = ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), callbackCaptor.capture());
        var callback = callbackCaptor.getValue();
        var lock     = mock(FencedLock.class);

        callback.lockAcquired(lock);
        callback.lockReleased(lock);
        callback.lockAcquired(lock);
        callback.lockReleased(lock);

        verify(firstConsumer, times(1)).cancel();
        verify(secondConsumer, times(1)).cancel();
        verify(firstSubscription, times(1)).unsubscribe();
        verify(secondSubscription, times(1)).unsubscribe();
    }

    @Test
    void a_lock_acquired_callback_that_fails_to_subscribe_stops_the_subscriptions_it_created_and_the_release_cancels_the_consumer() {
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(mock(EventStore.class));
        var fencedLockManager = mock(FencedLockManager.class);
        var durableQueues     = mock(DurableQueues.class);
        var consumer          = mock(DurableQueueConsumer.class);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class))).thenReturn(consumer);
        var thingsSubscription = mock(EventStoreSubscription.class);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(THINGS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(thingsSubscription);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(OTHERS), any(Function.class), any(PersistedEventHandler.class)))
                .thenThrow(new IllegalStateException("Cannot subscribe"));

        var processor = new ThingsViewProcessor(subscriptionManager, fencedLockManager, durableQueues, List.of(THINGS, OTHERS));
        processor.start();
        var callbackCaptor = ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), callbackCaptor.capture());
        var callback = callbackCaptor.getValue();
        var lock     = mock(FencedLock.class);

        // A throwing lock-acquired callback makes the lock manager release the lock again
        assertThatThrownBy(() -> callback.lockAcquired(lock)).isInstanceOf(IllegalStateException.class);
        verify(thingsSubscription).unsubscribe();
        callback.lockReleased(lock);

        verify(consumer).cancel();
    }

    @Test
    void a_release_whose_first_unsubscribe_fails_still_unsubscribes_the_others_and_cancels_the_consumer() {
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(mock(EventStore.class));
        var fencedLockManager = mock(FencedLockManager.class);
        var durableQueues     = mock(DurableQueues.class);
        var consumer          = mock(DurableQueueConsumer.class);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class))).thenReturn(consumer);
        var thingsSubscription  = mock(EventStoreSubscription.class);
        var othersSubscription  = mock(EventStoreSubscription.class);
        var moreSubscription    = mock(EventStoreSubscription.class);
        var thingsFailure       = new IllegalStateException("things unsubscribe failed");
        var moreFailure         = new IllegalStateException("more unsubscribe failed");
        doThrow(thingsFailure).when(thingsSubscription).unsubscribe();
        doThrow(moreFailure).when(moreSubscription).unsubscribe();
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(THINGS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(thingsSubscription);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(OTHERS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(othersSubscription);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(MORE), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(moreSubscription);

        var processor = new ThingsViewProcessor(subscriptionManager, fencedLockManager, durableQueues, List.of(THINGS, OTHERS, MORE));
        processor.start();
        var callbackCaptor = ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), callbackCaptor.capture());
        var callback = callbackCaptor.getValue();
        var lock     = mock(FencedLock.class);
        callback.lockAcquired(lock);

        assertThatThrownBy(() -> callback.lockReleased(lock)).isSameAs(thingsFailure);
        assertThat(thingsFailure.getSuppressed()).containsExactly(moreFailure);
        verify(othersSubscription).unsubscribe();
        verify(moreSubscription).unsubscribe();
        verify(consumer).cancel();
    }

    @Test
    void a_release_whose_unsubscribes_throw_one_failure_instance_rethrows_it_without_suppressing_it_into_itself() {
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(mock(EventStore.class));
        var fencedLockManager = mock(FencedLockManager.class);
        var durableQueues     = mock(DurableQueues.class);
        var consumer          = mock(DurableQueueConsumer.class);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class))).thenReturn(consumer);
        var thingsSubscription = mock(EventStoreSubscription.class);
        var othersSubscription = mock(EventStoreSubscription.class);
        var moreSubscription   = mock(EventStoreSubscription.class);
        var sharedFailure      = new IllegalStateException("unsubscribe failed");
        doThrow(sharedFailure).when(thingsSubscription).unsubscribe();
        doThrow(sharedFailure).when(othersSubscription).unsubscribe();
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(THINGS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(thingsSubscription);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(OTHERS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(othersSubscription);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(MORE), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(moreSubscription);

        var processor = new ThingsViewProcessor(subscriptionManager, fencedLockManager, durableQueues, List.of(THINGS, OTHERS, MORE));
        processor.start();
        var callbackCaptor = ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), callbackCaptor.capture());
        var callback = callbackCaptor.getValue();
        var lock     = mock(FencedLock.class);
        callback.lockAcquired(lock);

        assertThatThrownBy(() -> callback.lockReleased(lock)).isSameAs(sharedFailure);
        assertThat(sharedFailure.getSuppressed()).isEmpty();
        verify(moreSubscription).unsubscribe();
        verify(consumer).cancel();
    }

    @Test
    void a_failed_subscribe_whose_rollback_unsubscribes_throw_one_failure_instance_adds_it_to_the_subscribe_failure_once() {
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(mock(EventStore.class));
        var fencedLockManager = mock(FencedLockManager.class);
        var durableQueues     = mock(DurableQueues.class);
        var consumer          = mock(DurableQueueConsumer.class);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class))).thenReturn(consumer);
        var thingsSubscription = mock(EventStoreSubscription.class);
        var othersSubscription = mock(EventStoreSubscription.class);
        var unsubscribeFailure = new IllegalStateException("unsubscribe failed");
        var subscribeFailure   = new IllegalStateException("Cannot subscribe");
        doThrow(unsubscribeFailure).when(thingsSubscription).unsubscribe();
        doThrow(unsubscribeFailure).when(othersSubscription).unsubscribe();
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(THINGS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(thingsSubscription);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(OTHERS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(othersSubscription);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(MORE), any(Function.class), any(PersistedEventHandler.class)))
                .thenThrow(subscribeFailure);

        var processor = new ThingsViewProcessor(subscriptionManager, fencedLockManager, durableQueues, List.of(THINGS, OTHERS, MORE));
        processor.start();
        var callbackCaptor = ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), callbackCaptor.capture());
        var callback = callbackCaptor.getValue();
        var lock     = mock(FencedLock.class);

        assertThatThrownBy(() -> callback.lockAcquired(lock)).isSameAs(subscribeFailure);
        assertThat(subscribeFailure.getSuppressed()).containsExactly(unsubscribeFailure);
        verify(othersSubscription).unsubscribe();
    }

    @Test
    void a_failed_subscribe_whose_rollback_unsubscribe_fails_rethrows_the_subscribe_failure_and_unsubscribes_the_others() {
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(mock(EventStore.class));
        var fencedLockManager = mock(FencedLockManager.class);
        var durableQueues     = mock(DurableQueues.class);
        var consumer          = mock(DurableQueueConsumer.class);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class))).thenReturn(consumer);
        var thingsSubscription = mock(EventStoreSubscription.class);
        var othersSubscription = mock(EventStoreSubscription.class);
        var unsubscribeFailure = new IllegalStateException("things unsubscribe failed");
        var subscribeFailure   = new IllegalStateException("Cannot subscribe");
        doThrow(unsubscribeFailure).when(thingsSubscription).unsubscribe();
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(THINGS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(thingsSubscription);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(OTHERS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(othersSubscription);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(), eq(MORE), any(Function.class), any(PersistedEventHandler.class)))
                .thenThrow(subscribeFailure);

        var processor = new ThingsViewProcessor(subscriptionManager, fencedLockManager, durableQueues, List.of(THINGS, OTHERS, MORE));
        processor.start();
        var callbackCaptor = ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), callbackCaptor.capture());
        var callback = callbackCaptor.getValue();
        var lock     = mock(FencedLock.class);

        assertThatThrownBy(() -> callback.lockAcquired(lock)).isSameAs(subscribeFailure);
        assertThat(subscribeFailure.getSuppressed()).containsExactly(unsubscribeFailure);
        verify(othersSubscription).unsubscribe();
    }

    private static void runCapturing(Runnable runnable, AtomicReference<Throwable> failure) {
        try {
            runnable.run();
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    /**
     * Waits until the thread either blocks on a monitor (the fixed behaviour) or has run to completion (the old one).
     */
    private static void awaitBlockedOrTerminated(Thread thread) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.TERMINATED) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Thread " + thread.getName() + " neither blocked nor terminated: " + thread.getState());
    }

    // -------------------------------------------------------------------------------------------------------------------

    static class ThingsViewProcessor extends ViewEventProcessor {
        private final List<AggregateType> aggregateTypes;

        ThingsViewProcessor(EventStoreSubscriptionManager subscriptionManager,
                            FencedLockManager fencedLockManager,
                            DurableQueues durableQueues) {
            this(subscriptionManager, fencedLockManager, durableQueues, List.of(THINGS));
        }

        ThingsViewProcessor(EventStoreSubscriptionManager subscriptionManager,
                            FencedLockManager fencedLockManager,
                            DurableQueues durableQueues,
                            List<AggregateType> aggregateTypes) {
            super(subscriptionManager, fencedLockManager, durableQueues, mock(DurableLocalCommandBus.class), List.of());
            this.aggregateTypes = aggregateTypes;
        }

        @Override
        public String getProcessorName() {
            return "ThingsViewProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return aggregateTypes;
        }
    }
}
