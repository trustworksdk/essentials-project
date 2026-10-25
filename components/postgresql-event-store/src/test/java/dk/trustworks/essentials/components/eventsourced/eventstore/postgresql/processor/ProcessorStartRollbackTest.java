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
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
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
 * (unsubscribed) subscriptions - which a later {@code stop()} would unsubscribe once more.
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
