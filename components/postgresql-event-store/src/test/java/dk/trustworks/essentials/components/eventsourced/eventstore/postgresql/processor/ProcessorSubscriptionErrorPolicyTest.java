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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.function.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A {@link ViewEventProcessor} or {@link EventProcessor} that overrides {@link AbstractEventProcessor#getSubscriptionErrorPolicy()}
 * subscribes with an event handler that reports that policy, so its subscriptions apply it instead of the
 * {@link EventStoreSubscriptionManager}'s; one that does not override it leaves the manager's policy in place.
 * <p>
 * Mocks throughout: the event handler a processor subscribes with is captured from the subscription manager, so no
 * database is involved. That the subscriptions honour the handler's policy is pinned by
 * {@code EventStoreSubscriptionManager_SubscriptionErrorPolicy_IT}.
 */
class ProcessorSubscriptionErrorPolicyTest {
    private static final AggregateType          THINGS = AggregateType.of("Things");
    private static final SubscriptionErrorPolicy POLICY = SubscriptionErrorPolicy.retryThenStop(3);

    @Test
    void an_EventProcessor_subscribes_with_the_policy_it_overrides() {
        var subscriptionManager = subscriptionManager();

        new PolicyEventProcessor(subscriptionManager, inboxes(), Optional.of(POLICY)).start();

        assertThat(eventProcessorHandler(subscriptionManager).subscriptionErrorPolicy()).contains(POLICY);
    }

    @Test
    void an_EventProcessor_without_a_policy_of_its_own_leaves_the_managers_in_place() {
        var subscriptionManager = subscriptionManager();

        new PolicyEventProcessor(subscriptionManager, inboxes(), Optional.empty()).start();

        assertThat(eventProcessorHandler(subscriptionManager).subscriptionErrorPolicy()).isEmpty();
    }

    @Test
    void a_ViewEventProcessor_subscribes_with_the_policy_it_overrides() {
        var subscriptionManager = subscriptionManager();
        var fencedLockManager   = mock(FencedLockManager.class);

        new PolicyViewProcessor(subscriptionManager, fencedLockManager, Optional.of(POLICY)).start();

        assertThat(viewProcessorHandler(subscriptionManager, fencedLockManager).subscriptionErrorPolicy()).contains(POLICY);
    }

    @Test
    void a_ViewEventProcessor_without_a_policy_of_its_own_leaves_the_managers_in_place() {
        var subscriptionManager = subscriptionManager();
        var fencedLockManager   = mock(FencedLockManager.class);

        new PolicyViewProcessor(subscriptionManager, fencedLockManager, Optional.empty()).start();

        assertThat(viewProcessorHandler(subscriptionManager, fencedLockManager).subscriptionErrorPolicy()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static PersistedEventHandler eventProcessorHandler(EventStoreSubscriptionManager subscriptionManager) {
        var handler = ArgumentCaptor.forClass(PersistedEventHandler.class);
        verify(subscriptionManager).exclusivelySubscribeToAggregateEventsAsynchronously(any(SubscriberId.class),
                                                                                        eq(THINGS),
                                                                                        any(Function.class),
                                                                                        eq(Optional.empty()),
                                                                                        any(FencedLockAwareSubscriber.class),
                                                                                        handler.capture());
        return handler.getValue();
    }

    @SuppressWarnings("unchecked")
    private static PersistedEventHandler viewProcessorHandler(EventStoreSubscriptionManager subscriptionManager, FencedLockManager fencedLockManager) {
        // A ViewEventProcessor subscribes once it holds its fenced lock
        var lockCallback = ArgumentCaptor.forClass(LockCallback.class);
        verify(fencedLockManager).acquireLockAsync(any(LockName.class), lockCallback.capture());
        lockCallback.getValue().lockAcquired(mock(FencedLock.class));

        var handler = ArgumentCaptor.forClass(PersistedEventHandler.class);
        verify(subscriptionManager).subscribeToAggregateEventsAsynchronously(any(SubscriberId.class),
                                                                             eq(THINGS),
                                                                             any(Function.class),
                                                                             handler.capture());
        return handler.getValue();
    }

    private static EventStoreSubscriptionManager subscriptionManager() {
        var eventStore = mock(EventStore.class);
        doReturn(mock(EventStoreUnitOfWorkFactory.class)).when(eventStore).getUnitOfWorkFactory();
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        return subscriptionManager;
    }

    @SuppressWarnings("unchecked")
    private static Inboxes inboxes() {
        var inbox = mock(Inbox.class);
        when(inbox.name()).thenReturn(InboxName.of("PolicyEventProcessor"));
        var inboxes = mock(Inboxes.class);
        when(inboxes.getOrCreateInbox(any(InboxConfig.class), any(Consumer.class))).thenReturn(inbox);
        return inboxes;
    }

    // -------------------------------------------------------------------------------------------------------------------

    static class PolicyEventProcessor extends EventProcessor {
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private final Optional<SubscriptionErrorPolicy> subscriptionErrorPolicy;

        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        PolicyEventProcessor(EventStoreSubscriptionManager subscriptionManager, Inboxes inboxes, Optional<SubscriptionErrorPolicy> subscriptionErrorPolicy) {
            super(subscriptionManager, inboxes, mock(DurableLocalCommandBus.class));
            this.subscriptionErrorPolicy = subscriptionErrorPolicy;
        }

        @Override
        public String getProcessorName() {
            return "PolicyEventProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS);
        }

        @Override
        protected Optional<SubscriptionErrorPolicy> getSubscriptionErrorPolicy() {
            return subscriptionErrorPolicy;
        }
    }

    static class PolicyViewProcessor extends ViewEventProcessor {
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private final Optional<SubscriptionErrorPolicy> subscriptionErrorPolicy;

        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        PolicyViewProcessor(EventStoreSubscriptionManager subscriptionManager, FencedLockManager fencedLockManager, Optional<SubscriptionErrorPolicy> subscriptionErrorPolicy) {
            super(subscriptionManager, fencedLockManager, mock(DurableQueues.class), mock(DurableLocalCommandBus.class), List.of());
            this.subscriptionErrorPolicy = subscriptionErrorPolicy;
        }

        @Override
        public String getProcessorName() {
            return "PolicyViewProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS);
        }

        @Override
        protected Optional<SubscriptionErrorPolicy> getSubscriptionErrorPolicy() {
            return subscriptionErrorPolicy;
        }
    }
}
