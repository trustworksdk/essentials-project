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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link UnitOfWorkMode#NONE} only means something for a processor that hands the {@link UnitOfWork} boundary to its
 * {@link dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.PatternMatchingMessageHandler}.
 * The two processors that deliberately do not - because each handles a message inside one shared {@link UnitOfWork} -
 * must say so at start-up rather than run the handler's blocking call inside a database transaction, which is what
 * accepting the annotation and ignoring it would amount to.
 *
 * <p>Mocks throughout: every rejection happens before a processor touches the event store, the queues or the lock
 * manager, so no database is involved.
 *
 * @see UnitOfWorkMode#NONE
 */
class ProcessorNonTransactionalMessageHandlerGuardTest {

    @Test
    void an_InTransactionEventProcessor_with_a_NONE_handler_is_rejected_at_start() {
        var processor = new NonTransactionalInTransactionProcessor(subscriptionManager(), commandBus());

        assertThatThrownBy(processor::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnitOfWorkMode.NONE")
                .hasMessageContaining("InTransactionEventProcessor")
                .hasMessageContaining("EventProcessor");
    }

    @Test
    void a_rejected_InTransactionEventProcessor_is_not_left_looking_started() {
        var processor = new NonTransactionalInTransactionProcessor(subscriptionManager(), commandBus());

        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class);

        assertThat(processor.isStarted())
                .describedAs("A processor that refused to start must not report itself as started")
                .isFalse();
        // ... and the second attempt must fail the same way instead of being swallowed by the already-started check
        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_ViewEventProcessor_with_a_NONE_handler_is_rejected_at_start() {
        var processor = new NonTransactionalViewProcessor(subscriptionManager(),
                                                          mock(FencedLockManager.class),
                                                          mock(DurableQueues.class),
                                                          commandBus());

        assertThatThrownBy(processor::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnitOfWorkMode.NONE")
                .hasMessageContaining("ViewEventProcessor")
                .hasMessageContaining("EventProcessor");
    }

    private static EventStoreSubscriptionManager subscriptionManager() {
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(mock(EventStore.class));
        return subscriptionManager;
    }

    private static DurableLocalCommandBus commandBus() {
        return mock(DurableLocalCommandBus.class);
    }

    // -------------------------------------------------------------------------------------------------------------------

    record SomethingHappened(String id) {
    }

    static class NonTransactionalInTransactionProcessor extends InTransactionEventProcessor {
        NonTransactionalInTransactionProcessor(EventStoreSubscriptionManager subscriptionManager,
                                               DurableLocalCommandBus commandBus) {
            super(subscriptionManager, commandBus, List.of(), true);
        }

        @Override
        public String getProcessorName() {
            return "NonTransactionalInTransactionProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(AggregateType.of("Things"));
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(SomethingHappened e) {
        }
    }

    static class NonTransactionalViewProcessor extends ViewEventProcessor {
        NonTransactionalViewProcessor(EventStoreSubscriptionManager subscriptionManager,
                                      FencedLockManager fencedLockManager,
                                      DurableQueues durableQueues,
                                      DurableLocalCommandBus commandBus) {
            super(subscriptionManager, fencedLockManager, durableQueues, commandBus, List.of());
        }

        @Override
        public String getProcessorName() {
            return "NonTransactionalViewProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(AggregateType.of("Things"));
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(SomethingHappened e) {
        }
    }
}
