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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An {@link EventProcessor} or {@link InTransactionEventProcessor} whose {@code start()} fails to subscribe to one of its
 * aggregate types unsubscribes the subscriptions that start already created, and is left not started. Otherwise those
 * subscriptions would be live and referenced by nothing, while the processor's list still held an earlier run's
 * (unsubscribed) subscriptions - which a later {@code stop()} would unsubscribe once more. A start that fails before it
 * subscribes (its {@code Inbox} cannot be created or consumed from) is left not started too, and a subscribe that throws
 * a non-critical {@link Error} is rolled back like one that throws a {@link RuntimeException}. A
 * {@link ViewEventProcessor} subscribes when it acquires its lock, and unsubscribes what it created there in the same way;
 * one whose lock acquiring cannot be started is left not started.
 * <p>
 * Mocks throughout: the subscriptions are handed out by a mocked subscription manager, so no database is involved.
 */
class ProcessorStartRollbackTest {
    private static final AggregateType THINGS = AggregateType.of("Things");
    private static final AggregateType OTHERS = AggregateType.of("Others");

    @Test
    void an_EventProcessor_whose_second_subscribe_fails_unsubscribes_the_first_and_is_not_started() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var failure             = new IllegalStateException("Others cannot be subscribed to");
        stubAsynchronousSubscribe(subscriptionManager, THINGS).thenReturn(things);
        stubAsynchronousSubscribe(subscriptionManager, OTHERS).thenThrow(failure);
        var inbox     = mock(Inbox.class);
        var processor = new TwoTypesEventProcessor(subscriptionManager, inboxes(inbox));

        assertThatThrownBy(processor::start).isSameAs(failure);

        verify(things).unsubscribe();
        verify(inbox).stopConsuming();
        assertThat(processor.isStarted()).isFalse();
    }

    @Test
    void an_EventProcessor_whose_restart_fails_does_not_unsubscribe_the_earlier_runs_subscriptions_again_on_stop() {
        var subscriptionManager = subscriptionManager();
        var firstThings         = mock(EventStoreSubscription.class);
        var firstOthers         = mock(EventStoreSubscription.class);
        var secondThings        = mock(EventStoreSubscription.class);
        var failure             = new IllegalStateException("Others cannot be subscribed to");
        stubAsynchronousSubscribe(subscriptionManager, THINGS).thenReturn(firstThings, secondThings);
        stubAsynchronousSubscribe(subscriptionManager, OTHERS).thenReturn(firstOthers).thenThrow(failure);
        var processor = new TwoTypesEventProcessor(subscriptionManager, inboxes(mock(Inbox.class)));
        processor.start();
        processor.stop();

        assertThatThrownBy(processor::start).isSameAs(failure);
        processor.stop();

        verify(firstThings, times(1)).unsubscribe();
        verify(firstOthers, times(1)).unsubscribe();
        verify(secondThings, times(1)).unsubscribe();
    }

    @Test
    void an_EventProcessor_whose_start_failed_can_be_started_again() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var others              = mock(EventStoreSubscription.class);
        stubAsynchronousSubscribe(subscriptionManager, THINGS).thenReturn(things);
        stubAsynchronousSubscribe(subscriptionManager, OTHERS).thenThrow(new IllegalStateException("Others cannot be subscribed to"))
                                                              .thenReturn(others);
        var processor = new TwoTypesEventProcessor(subscriptionManager, inboxes(mock(Inbox.class)));
        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class);

        processor.start();

        assertThat(processor.isStarted()).isTrue();
        assertThat(processor.eventStoreSubscriptions).containsExactly(things, others);
    }

    @Test
    void an_InTransactionEventProcessor_whose_second_subscribe_fails_unsubscribes_the_first_and_is_not_started() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var failure             = new IllegalStateException("Others cannot be subscribed to");
        when(subscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(any(SubscriberId.class), eq(THINGS), eq(Optional.empty()), any(TransactionalPersistedEventHandler.class)))
                .thenReturn(things);
        when(subscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(any(SubscriberId.class), eq(OTHERS), eq(Optional.empty()), any(TransactionalPersistedEventHandler.class)))
                .thenThrow(failure);
        var processor = new TwoTypesInTransactionEventProcessor(subscriptionManager);

        assertThatThrownBy(processor::start).isSameAs(failure);

        verify(things).unsubscribe();
        assertThat(processor.isStarted()).isFalse();
        // Nothing is left for a stop() to unsubscribe again
        processor.stop();
        verify(things, times(1)).unsubscribe();
    }

    @Test
    void a_failing_unsubscribe_during_the_rollback_is_suppressed_into_the_subscribe_failure() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var subscribeFailure    = new IllegalStateException("Others cannot be subscribed to");
        var unsubscribeFailure  = new IllegalStateException("Things cannot be unsubscribed");
        doThrow(unsubscribeFailure).when(things).unsubscribe();
        stubAsynchronousSubscribe(subscriptionManager, THINGS).thenReturn(things);
        stubAsynchronousSubscribe(subscriptionManager, OTHERS).thenThrow(subscribeFailure);
        var processor = new TwoTypesEventProcessor(subscriptionManager, inboxes(mock(Inbox.class)));

        assertThatThrownBy(processor::start).isSameAs(subscribeFailure)
                                            .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(unsubscribeFailure));
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_EventProcessor_whose_Inbox_cannot_be_created_is_not_started_and_starts_again_on_the_next_start() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var others              = mock(EventStoreSubscription.class);
        stubAsynchronousSubscribe(subscriptionManager, THINGS).thenReturn(things);
        stubAsynchronousSubscribe(subscriptionManager, OTHERS).thenReturn(others);
        var inbox = mock(Inbox.class);
        when(inbox.name()).thenReturn(InboxName.of("TwoTypesEventProcessor"));
        var failure = new IllegalStateException("The database is down");
        var inboxes = mock(Inboxes.class);
        when(inboxes.getOrCreateInbox(any(InboxConfig.class), any(Consumer.class))).thenThrow(failure)
                                                                                    .thenReturn(inbox);
        var processor = new TwoTypesEventProcessor(subscriptionManager, inboxes);

        assertThatThrownBy(processor::start).isSameAs(failure);

        assertThat(processor.isStarted()).isFalse();
        verifyNoInteractions(things, others);

        processor.start();

        verify(inboxes, times(2)).getOrCreateInbox(any(InboxConfig.class), any(Consumer.class));
        assertThat(processor.isStarted()).isTrue();
        assertThat(processor.eventStoreSubscriptions).containsExactly(things, others);
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_EventProcessor_whose_Inbox_fails_to_consume_is_not_started_and_does_not_stop_a_consumption_it_never_began() {
        var subscriptionManager = subscriptionManager();
        var inbox               = mock(Inbox.class);
        var failure             = new IllegalStateException("The Inbox cannot consume");
        when(inbox.consume(any(Consumer.class))).thenThrow(failure);
        var processor = new TwoTypesEventProcessor(subscriptionManager, inboxes(inbox));

        assertThatThrownBy(processor::start).isSameAs(failure);

        assertThat(processor.isStarted()).isFalse();
        verify(inbox, never()).stopConsuming();
        verify(subscriptionManager, never()).exclusivelySubscribeToAggregateEventsAsynchronously(any(SubscriberId.class),
                                                                                                 any(AggregateType.class),
                                                                                                 any(Function.class),
                                                                                                 any(),
                                                                                                 any(FencedLockAwareSubscriber.class),
                                                                                                 any(PersistedEventHandler.class));
    }

    @Test
    void an_EventProcessor_whose_second_subscribe_throws_a_non_critical_Error_unsubscribes_the_first_and_is_not_started() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var failure             = new AssertionError("Others cannot be subscribed to");
        stubAsynchronousSubscribe(subscriptionManager, THINGS).thenReturn(things);
        stubAsynchronousSubscribe(subscriptionManager, OTHERS).thenThrow(failure);
        var inbox     = mock(Inbox.class);
        var processor = new TwoTypesEventProcessor(subscriptionManager, inboxes(inbox));

        assertThatThrownBy(processor::start).isSameAs(failure);

        verify(things).unsubscribe();
        verify(inbox).stopConsuming();
        assertThat(processor.isStarted()).isFalse();
    }

    @Test
    void an_InTransactionEventProcessor_whose_second_subscribe_throws_a_non_critical_Error_unsubscribes_the_first_and_is_not_started() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var failure             = new AssertionError("Others cannot be subscribed to");
        when(subscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(any(SubscriberId.class), eq(THINGS), eq(Optional.empty()), any(TransactionalPersistedEventHandler.class)))
                .thenReturn(things);
        when(subscriptionManager.exclusivelySubscribeToAggregateEventsInTransaction(any(SubscriberId.class), eq(OTHERS), eq(Optional.empty()), any(TransactionalPersistedEventHandler.class)))
                .thenThrow(failure);
        var processor = new TwoTypesInTransactionEventProcessor(subscriptionManager);

        assertThatThrownBy(processor::start).isSameAs(failure);

        verify(things).unsubscribe();
        assertThat(processor.isStarted()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_ViewEventProcessor_whose_second_subscribe_fails_on_lock_acquired_unsubscribes_the_first() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var failure             = new AssertionError("Others cannot be subscribed to");
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(SubscriberId.class), eq(THINGS), any(Function.class), any(PersistedEventHandler.class)))
                .thenReturn(things);
        when(subscriptionManager.subscribeToAggregateEventsAsynchronously(any(SubscriberId.class), eq(OTHERS), any(Function.class), any(PersistedEventHandler.class)))
                .thenThrow(failure);
        var fencedLockManager = mock(FencedLockManager.class);
        var processor         = new TwoTypesViewEventProcessor(subscriptionManager, fencedLockManager);
        processor.start();
        var lockCallback = org.mockito.ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), lockCallback.capture());
        var lock = mock(FencedLock.class);
        when(lock.getCurrentToken()).thenReturn(1L);

        assertThatThrownBy(() -> lockCallback.getValue().lockAcquired(lock)).isSameAs(failure);

        verify(things).unsubscribe();
        assertThat(processor.eventStoreSubscriptions).isNull();
    }

    @Test
    void a_ViewEventProcessor_whose_lock_acquiring_cannot_be_started_is_not_started_and_starts_again_on_the_next_start() {
        var fencedLockManager = mock(FencedLockManager.class);
        var failure           = new IllegalStateException("The lock acquiring cannot be started");
        doThrow(failure).doNothing().when(fencedLockManager).acquireLockAsync(any(LockName.class), any(LockCallback.class));
        var processor = new TwoTypesViewEventProcessor(subscriptionManager(), fencedLockManager);

        assertThatThrownBy(processor::start).isSameAs(failure);

        assertThat(processor.isStarted()).isFalse();

        processor.start();

        assertThat(processor.isStarted()).isTrue();
        verify(fencedLockManager, times(2)).acquireLockAsync(any(LockName.class), any(LockCallback.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_EventProcessor_whose_Inbox_already_had_a_consumer_stops_that_consumption_when_a_subscribe_fails() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var failure             = new IllegalStateException("Others cannot be subscribed to");
        stubAsynchronousSubscribe(subscriptionManager, THINGS).thenReturn(things);
        stubAsynchronousSubscribe(subscriptionManager, OTHERS).thenThrow(failure);
        var inbox = mock(Inbox.class);
        when(inbox.hasAMessageConsumer()).thenReturn(true);
        var processor = new TwoTypesEventProcessor(subscriptionManager, inboxes(inbox));

        assertThatThrownBy(processor::start).isSameAs(failure);

        verify(inbox, never()).consume(any(Consumer.class));
        verify(inbox).stopConsuming();
        verify(things).unsubscribe();
        assertThat(processor.isStarted()).isFalse();
    }

    @Test
    void an_EventProcessor_that_subscribed_everything_is_started_when_its_undelivered_message_count_cannot_be_read() {
        var subscriptionManager = subscriptionManager();
        var things              = mock(EventStoreSubscription.class);
        var others              = mock(EventStoreSubscription.class);
        stubAsynchronousSubscribe(subscriptionManager, THINGS).thenReturn(things);
        stubAsynchronousSubscribe(subscriptionManager, OTHERS).thenReturn(others);
        var inbox = mock(Inbox.class);
        when(inbox.getNumberOfUndeliveredMessages()).thenThrow(new IllegalStateException("The database is down"));
        var processor = new TwoTypesEventProcessor(subscriptionManager, inboxes(inbox));

        processor.start();

        assertThat(processor.isStarted()).isTrue();
        assertThat(processor.eventStoreSubscriptions).containsExactly(things, others);
        verify(inbox, never()).stopConsuming();
        verifyNoInteractions(things, others);
    }

    @SuppressWarnings("unchecked")
    private static org.mockito.stubbing.OngoingStubbing<EventStoreSubscription> stubAsynchronousSubscribe(EventStoreSubscriptionManager subscriptionManager,
                                                                                                         AggregateType aggregateType) {
        return when(subscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(any(SubscriberId.class),
                                                                                             eq(aggregateType),
                                                                                             any(Function.class),
                                                                                             eq(Optional.empty()),
                                                                                             any(FencedLockAwareSubscriber.class),
                                                                                             any(PersistedEventHandler.class)));
    }

    private static EventStoreSubscriptionManager subscriptionManager() {
        var eventStore = mock(EventStore.class);
        doReturn(mock(EventStoreUnitOfWorkFactory.class)).when(eventStore).getUnitOfWorkFactory();
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        return subscriptionManager;
    }

    @SuppressWarnings("unchecked")
    private static Inboxes inboxes(Inbox inbox) {
        when(inbox.name()).thenReturn(InboxName.of("TwoTypesEventProcessor"));
        var inboxes = mock(Inboxes.class);
        when(inboxes.getOrCreateInbox(any(InboxConfig.class), any(Consumer.class))).thenReturn(inbox);
        return inboxes;
    }

    // -------------------------------------------------------------------------------------------------------------------

    static class TwoTypesEventProcessor extends EventProcessor {
        TwoTypesEventProcessor(EventStoreSubscriptionManager subscriptionManager, Inboxes inboxes) {
            super(subscriptionManager, inboxes, mock(DurableLocalCommandBus.class));
        }

        @Override
        public String getProcessorName() {
            return "TwoTypesEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS, OTHERS);
        }
    }

    static class TwoTypesViewEventProcessor extends ViewEventProcessor {
        TwoTypesViewEventProcessor(EventStoreSubscriptionManager subscriptionManager, FencedLockManager fencedLockManager) {
            super(subscriptionManager, fencedLockManager, mock(DurableQueues.class), mock(DurableLocalCommandBus.class), List.of());
        }

        @Override
        public String getProcessorName() {
            return "TwoTypesViewEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS, OTHERS);
        }
    }

    static class TwoTypesInTransactionEventProcessor extends InTransactionEventProcessor {
        TwoTypesInTransactionEventProcessor(EventStoreSubscriptionManager subscriptionManager) {
            super(subscriptionManager, mock(DurableLocalCommandBus.class), List.of(), true);
        }

        @Override
        public String getProcessorName() {
            return "TwoTypesInTransactionEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS, OTHERS);
        }
    }
}
