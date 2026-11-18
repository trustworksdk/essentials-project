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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.PurgeQueue;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Every reset entry point of both partitioned processors - the public ones, the protected ones a subclass can call, and
 * the inherited signatures the processors override {@code final} only to call {@code super} - refuses while the processor
 * is started on this instance, and moves no resume point, purges no queue or {@link Inbox} and calls no
 * {@link AbstractEventProcessor#onSubscriptionsReset} on the way.
 * <p>
 * {@link PartitionedProcessorResetRoutingTest} checks that these methods are {@code final}; this test checks where they
 * go. The overrides that only call {@code super} are safe only while the superclass body delegates to an overload that
 * routes onto {@link StoppedEverywhereReset}; were it given its own body (calling {@link EventStoreSubscription#resetFrom}
 * directly, say), the processor would reset live and this test fails.
 * <p>
 * Mocks throughout, so no database is involved. What happens once the processor is stopped everywhere is pinned by
 * {@link StoppedEverywhereResetTest} and {@code PartitionedProcessorsOnShardOwnedIT}.
 */
class PartitionedProcessorResetRefusedWhileStartedTest {
    private static final AggregateType                        THINGS     = AggregateType.of("Things");
    private static final Map<AggregateType, GlobalEventOrder> FROM_FIRST = Map.of(THINGS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER);

    static Stream<ResetCall> partitionedEventProcessorResets() {
        return Stream.concat(sharedResets(),
                             Stream.of(new ResetCall("doResetSubscription(EventStoreSubscription, GlobalEventOrder, boolean)",
                                                     fixture -> ((PartitionedEventProcessor) fixture.processor())
                                                             .doResetSubscription(fixture.subscription(), GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, true)),
                                       new ResetCall("resetAllSubscriptionsIgnoringDepartedInstances()",
                                                     fixture -> ((PartitionedEventProcessor) fixture.processor()).resetAllSubscriptionsIgnoringDepartedInstances())));
    }

    static Stream<ResetCall> partitionedViewEventProcessorResets() {
        return Stream.concat(sharedResets(),
                             Stream.of(new ResetCall("resetAllSubscriptionsIgnoringDepartedInstances()",
                                                     fixture -> ((PartitionedViewEventProcessor) fixture.processor()).resetAllSubscriptionsIgnoringDepartedInstances())));
    }

    /**
     * The reset entry points both processors have; the public no-arg and 2-arg ones are declared on each processor (or on
     * {@link EventProcessor}), the rest on {@link AbstractEventProcessor}, so they are called through it.
     */
    private static Stream<ResetCall> sharedResets() {
        return Stream.of(new ResetCall("resetAllSubscriptions()",
                                       fixture -> {
                                           switch (fixture.processor()) {
                                               case PartitionedEventProcessor processor -> processor.resetAllSubscriptions();
                                               case PartitionedViewEventProcessor processor -> processor.resetAllSubscriptions();
                                               default -> throw new IllegalArgumentException(fixture.processor().toString());
                                           }
                                       }),
                         new ResetCall("resetSubscriptions(Map, boolean)",
                                       fixture -> {
                                           switch (fixture.processor()) {
                                               case PartitionedEventProcessor processor -> processor.resetSubscriptions(FROM_FIRST, true);
                                               case PartitionedViewEventProcessor processor -> processor.resetSubscriptions(FROM_FIRST, true);
                                               default -> throw new IllegalArgumentException(fixture.processor().toString());
                                           }
                                       }),
                         new ResetCall("resetSubscriptions(Map)",
                                       fixture -> fixture.processor().resetSubscriptions(FROM_FIRST)),
                         new ResetCall("resetSubscriptions(Map, boolean, Consumer)",
                                       fixture -> fixture.processor().resetSubscriptions(FROM_FIRST, true, queueName -> fixture.callbackPurges().incrementAndGet())),
                         new ResetCall("resetAllSubscriptions(Consumer)",
                                       fixture -> fixture.processor().resetAllSubscriptions(queueName -> fixture.callbackPurges().incrementAndGet())),
                         new ResetCall("doResetSubscription(EventStoreSubscription, GlobalEventOrder, boolean, Consumer)",
                                       fixture -> fixture.processor().doResetSubscription(fixture.subscription(), GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, true,
                                                                                          queueName -> fixture.callbackPurges().incrementAndGet())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("partitionedEventProcessorResets")
    void a_started_partitioned_event_processor_refuses_every_reset_and_resets_nothing(ResetCall reset) {
        var fixture = Fixture.partitionedEventProcessor();
        fixture.processor().start();
        assertThat(fixture.processor().isStarted()).isTrue();

        assertRefusedAndNothingReset(fixture, reset);
        verify(fixture.inbox(), never()).deleteAllMessages();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("partitionedViewEventProcessorResets")
    void a_started_partitioned_view_event_processor_refuses_every_reset_and_resets_nothing(ResetCall reset) {
        var fixture = Fixture.partitionedViewEventProcessor();
        fixture.processor().start();
        assertThat(fixture.processor().isStarted()).isTrue();

        assertRefusedAndNothingReset(fixture, reset);
        verify(fixture.durableQueues(), never()).purgeQueue(any(QueueName.class));
        verify(fixture.durableQueues(), never()).purgeQueue(any(PurgeQueue.class));
    }

    // -------------------------------------------------------------------------------------------------------------------

    private static void assertRefusedAndNothingReset(Fixture fixture, ResetCall reset) {
        assertThatThrownBy(() -> reset.call().accept(fixture))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("started on this instance");

        verify(fixture.subscription(), never()).resetFrom(any(), any());
        verify(fixture.subscriptionManager(), never()).resetStoredResumePoint(any(), any(), any());
        // The reset takes its locks with the 1-arg tryAcquireLock only once the processor is stopped here; a start uses the 2-arg one
        verify(fixture.lockManager(), never()).tryAcquireLock(any(LockName.class));
        assertThat(fixture.callbackPurges()).as("purge callback calls").hasValue(0);
        assertThat(fixture.subscriptionsReset()).as("onSubscriptionsReset calls").hasValue(0);
    }

    record ResetCall(String name, Consumer<Fixture> call) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * A processor over mocks; every subscription the {@link EventStoreSubscriptionManager} hands out is
     * {@link #subscription()}, which a reset that bypassed the check would move with {@code resetFrom}.
     */
    record Fixture(AbstractEventProcessor processor,
                   EventStoreSubscriptionManager subscriptionManager,
                   EventStoreSubscription subscription,
                   FencedLockManager lockManager,
                   Inbox inbox,
                   DurableQueues durableQueues,
                   AtomicInteger callbackPurges,
                   AtomicInteger subscriptionsReset) {

        @SuppressWarnings("unchecked")
        static Fixture partitionedEventProcessor() {
            var subscription        = newSubscription();
            var lockManager         = newLockManager();
            var subscriptionManager = subscriptionManager(subscription, lockManager);
            var inbox               = mock(Inbox.class);
            when(inbox.name()).thenReturn(InboxName.of("ThingsPartitionedProcessor"));
            var inboxes = mock(Inboxes.class);
            when(inboxes.getOrCreateInbox(any(InboxConfig.class), any(Consumer.class))).thenReturn(inbox);
            when(inboxes.getOrCreateInbox(any(InboxConfig.class))).thenReturn(inbox);
            when(inboxes.keepsKeyOrderAcrossInstances()).thenReturn(true);
            var subscriptionsReset = new AtomicInteger();
            var processor = new ThingsPartitionedProcessor(EventProcessorDependencies.builder()
                                                                                     .setEventStoreSubscriptionManager(subscriptionManager)
                                                                                     .setInboxes(inboxes)
                                                                                     .setCommandBus(mock(DurableLocalCommandBus.class))
                                                                                     .build(),
                                                           subscriptionsReset);
            return new Fixture(processor, subscriptionManager, subscription, lockManager, inbox, null, new AtomicInteger(), subscriptionsReset);
        }

        static Fixture partitionedViewEventProcessor() {
            var subscription        = newSubscription();
            var lockManager         = newLockManager();
            var subscriptionManager = subscriptionManager(subscription, lockManager);
            var durableQueues       = mock(DurableQueues.class);
            when(durableQueues.keepsKeyOrderAcrossInstances()).thenReturn(true);
            when(durableQueues.consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), any(QueuedMessageHandler.class)))
                    .thenReturn(mock(DurableQueueConsumer.class));
            when(durableQueues.getQueueConsumers(any(QueueName.class))).thenReturn(Optional.of(new QueueConsumers(0, 0)));
            var subscriptionsReset = new AtomicInteger();
            var processor = new ThingsPartitionedViewProcessor(subscriptionManager, lockManager, durableQueues, subscriptionsReset);
            return new Fixture(processor, subscriptionManager, subscription, lockManager, null, durableQueues, new AtomicInteger(), subscriptionsReset);
        }

        private static EventStoreSubscription newSubscription() {
            var subscription = mock(EventStoreSubscription.class);
            when(subscription.aggregateType()).thenReturn(THINGS);
            return subscription;
        }

        private static FencedLockManager newLockManager() {
            // A start registers its consumer holding the reset lock (2-arg tryAcquireLock); a reset would take every lock it asks for
            var lockManager = mock(FencedLockManager.class);
            when(lockManager.tryAcquireLock(any(LockName.class), any(Duration.class))).thenAnswer(invocation -> Optional.of(mock(FencedLock.class)));
            when(lockManager.tryAcquireLock(any(LockName.class))).thenAnswer(invocation -> Optional.of(mock(FencedLock.class)));
            return lockManager;
        }

        private static EventStoreSubscriptionManager subscriptionManager(EventStoreSubscription subscription, FencedLockManager lockManager) {
            // An AwaitedGapInspector, so start() logs no unrelated WARN
            var eventStore = mock(EventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
            doReturn(mock(EventStoreUnitOfWorkFactory.class)).when(eventStore).getUnitOfWorkFactory();
            var subscriptionManager = mock(EventStoreSubscriptionManager.class,
                                           invocation -> EventStoreSubscription.class.isAssignableFrom(invocation.getMethod().getReturnType()) ?
                                                         subscription : RETURNS_DEFAULTS.answer(invocation));
            doReturn(eventStore).when(subscriptionManager).getEventStore();
            doReturn(Optional.of(lockManager)).when(subscriptionManager).getFencedLockManager();
            return subscriptionManager;
        }
    }

    record SomethingHappened(String id) {
    }

    static class ThingsPartitionedProcessor extends PartitionedEventProcessor {
        private final AtomicInteger subscriptionsReset;

        ThingsPartitionedProcessor(EventProcessorDependencies dependencies, AtomicInteger subscriptionsReset) {
            super(dependencies);
            this.subscriptionsReset = subscriptionsReset;
        }

        @Override
        public String getProcessorName() {
            return "ThingsPartitionedProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS);
        }

        @Override
        protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
            subscriptionsReset.incrementAndGet();
        }
    }

    static class ThingsPartitionedViewProcessor extends PartitionedViewEventProcessor {
        private final AtomicInteger subscriptionsReset;

        ThingsPartitionedViewProcessor(EventStoreSubscriptionManager subscriptionManager,
                                       FencedLockManager lockManager,
                                       DurableQueues durableQueues,
                                       AtomicInteger subscriptionsReset) {
            super(subscriptionManager, lockManager, durableQueues, mock(DurableLocalCommandBus.class), List.of());
            this.subscriptionsReset = subscriptionsReset;
        }

        @Override
        public String getProcessorName() {
            return "ThingsPartitionedViewProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(THINGS);
        }

        @Override
        protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
            subscriptionsReset.incrementAndGet();
        }

        @MessageHandler
        void on(SomethingHappened e) {
        }
    }
}
