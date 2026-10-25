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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link AbstractEventProcessor#unsubscribeEach(List)} tries every subscription even when one fails to unsubscribe, and
 * then rethrows the first failure with the later ones added as suppressed; {@link EventProcessor#stop()} still stops
 * consuming from its {@link Inbox} when it does.
 * <p>
 * Mocks throughout: that a stopped and started processor handles events again is pinned by {@code EventProcessorIT}.
 */
class AbstractEventProcessorUnsubscribeEachTest {
    private static final AggregateType THINGS = AggregateType.of("Things");

    @Test
    void a_failed_unsubscribe_does_not_stop_the_later_ones_and_the_first_failure_is_rethrown_with_the_later_suppressed() {
        var first        = mock(EventStoreSubscription.class);
        var second       = mock(EventStoreSubscription.class);
        var third        = mock(EventStoreSubscription.class);
        var firstFailure  = new IllegalStateException("first unsubscribe failed");
        var secondFailure = new IllegalStateException("second unsubscribe failed");
        doThrow(firstFailure).when(first).unsubscribe();
        doThrow(secondFailure).when(second).unsubscribe();

        assertThatThrownBy(() -> AbstractEventProcessor.unsubscribeEach(List.of(first, second, third)))
                .isSameAs(firstFailure)
                .hasSuppressedException(secondFailure);
        assertThat(firstFailure.getSuppressed()).containsExactly(secondFailure);
        verify(second).unsubscribe();
        verify(third).unsubscribe();
    }

    @Test
    void every_subscription_is_unsubscribed_when_none_fails() {
        var first  = mock(EventStoreSubscription.class);
        var second = mock(EventStoreSubscription.class);

        assertThatCode(() -> AbstractEventProcessor.unsubscribeEach(List.of(first, second))).doesNotThrowAnyException();
        verify(first).unsubscribe();
        verify(second).unsubscribe();
    }

    @Test
    void a_null_list_is_a_no_op() {
        assertThatCode(() -> AbstractEventProcessor.unsubscribeEach(null)).doesNotThrowAnyException();
    }

    @Test
    void an_EventProcessor_whose_unsubscribe_fails_still_stops_consuming_from_its_inbox() {
        var subscription = mock(EventStoreSubscription.class);
        var failure      = new IllegalStateException("unsubscribe failed");
        doThrow(failure).when(subscription).unsubscribe();
        var subscriptionManager = subscriptionManager(subscription);
        var inbox               = mock(Inbox.class);
        when(inbox.name()).thenReturn(InboxName.of("ThingsEventProcessor"));

        var processor = new ThingsEventProcessor(subscriptionManager, inboxes(inbox));
        processor.start();

        assertThatThrownBy(processor::stop).isSameAs(failure);
        verify(subscription).unsubscribe();
        verify(inbox).stopConsuming();
        assertThat(processor.isStarted()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static EventStoreSubscriptionManager subscriptionManager(EventStoreSubscription subscription) {
        var eventStore = mock(EventStore.class);
        doReturn(mock(EventStoreUnitOfWorkFactory.class)).when(eventStore).getUnitOfWorkFactory();
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        when(subscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(any(SubscriberId.class),
                                                                                     eq(THINGS),
                                                                                     any(Function.class),
                                                                                     eq(Optional.empty()),
                                                                                     any(FencedLockAwareSubscriber.class),
                                                                                     any(PersistedEventHandler.class)))
                .thenReturn(subscription);
        return subscriptionManager;
    }

    @SuppressWarnings("unchecked")
    private static Inboxes inboxes(Inbox inbox) {
        var inboxes = mock(Inboxes.class);
        when(inboxes.getOrCreateInbox(any(InboxConfig.class), any(Consumer.class))).thenReturn(inbox);
        return inboxes;
    }

    // -------------------------------------------------------------------------------------------------------------------

    static class ThingsEventProcessor extends EventProcessor {
        ThingsEventProcessor(EventStoreSubscriptionManager subscriptionManager, Inboxes inboxes) {
            super(subscriptionManager, inboxes, mock(DurableLocalCommandBus.class));
        }

        @Override
        public String getProcessorName() {
            return "ThingsEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS);
        }
    }
}
