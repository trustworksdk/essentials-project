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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * The {@link UnitOfWork} accessors and helpers {@link AbstractEventProcessor} gives every processor subclass all go to
 * the {@link EventStore}'s own {@link UnitOfWorkFactory} - the one the processor handles its messages with - so a
 * projection never needs that factory injected a second time.
 *
 * <p>Joining an active {@link UnitOfWork} versus opening one is {@link UnitOfWorkFactory}'s behaviour, covered there;
 * this test pins only that they reach the event store's factory.
 */
class ProcessorUnitOfWorkHelpersTest {

    private final EventStoreUnitOfWork                              unitOfWork        = mock(EventStoreUnitOfWork.class);
    private final EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory = new SingleUnitOfWorkFactory(unitOfWork);
    private final TestProcessor                                     processor         = new TestProcessor(subscriptionManager(unitOfWorkFactory));

    @Test
    void getUnitOfWorkFactory_returns_the_event_stores_factory() {
        assertThat(processor.getUnitOfWorkFactory()).isSameAs(unitOfWorkFactory);
    }

    @Test
    void usingUnitOfWork_runs_the_action_in_the_event_stores_unit_of_work() {
        var received = new AtomicReference<EventStoreUnitOfWork>();

        processor.usingUnitOfWork(() -> received.set(processor.getRequiredUnitOfWork()));

        assertThat(received.get()).isSameAs(unitOfWork);
        verify(unitOfWork).commit();
    }

    @Test
    void getCurrentUnitOfWork_is_empty_outside_a_unit_of_work_and_present_inside_one() {
        assertThat(processor.getCurrentUnitOfWork()).isEmpty();

        var inside = processor.withUnitOfWork(() -> processor.getCurrentUnitOfWork());

        assertThat(inside).containsSame(unitOfWork);
    }

    @Test
    void getRequiredUnitOfWork_throws_outside_a_unit_of_work_and_returns_it_inside_one() {
        assertThatThrownBy(processor::getRequiredUnitOfWork)
                .isInstanceOf(NoActiveUnitOfWorkException.class)
                .hasMessageContaining("TestProcessor")
                .hasMessageContaining("UnitOfWorkMode.NONE");

        var inside = processor.withUnitOfWork(() -> processor.getRequiredUnitOfWork());

        assertThat(inside).isSameAs(unitOfWork);
    }

    private static EventStoreSubscriptionManager subscriptionManager(EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory) {
        var eventStore = mock(EventStore.class);
        when(eventStore.getUnitOfWorkFactory()).thenReturn(unitOfWorkFactory);
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        return subscriptionManager;
    }

    // -------------------------------------------------------------------------------------------------------------------

    /**
     * Hands out one {@link UnitOfWork}, current from {@link #getOrCreateNewUnitOfWork()} until it is committed or
     * rolled back - enough of a factory for {@link UnitOfWorkFactory}'s join-or-open defaults to run against.
     */
    private static class SingleUnitOfWorkFactory implements EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> {
        private final EventStoreUnitOfWork unitOfWork;
        private       boolean              active;

        SingleUnitOfWorkFactory(EventStoreUnitOfWork unitOfWork) {
            this.unitOfWork = unitOfWork;
            doAnswer(invocation -> active = false).when(unitOfWork).commit();
            doAnswer(invocation -> active = false).when(unitOfWork).rollback(any());
        }

        @Override
        public EventStoreUnitOfWork getRequiredUnitOfWork() {
            return getCurrentUnitOfWork().orElseThrow(NoActiveUnitOfWorkException::new);
        }

        @Override
        public EventStoreUnitOfWork getOrCreateNewUnitOfWork() {
            active = true;
            return unitOfWork;
        }

        @Override
        public Optional<EventStoreUnitOfWork> getCurrentUnitOfWork() {
            return active ? Optional.of(unitOfWork) : Optional.empty();
        }

        @Override
        public EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> registerPersistedEventsCommitLifeCycleCallback(PersistedEventsCommitLifecycleCallback callback) {
            return this;
        }
    }

    static class TestProcessor extends ViewEventProcessor {
        TestProcessor(EventStoreSubscriptionManager subscriptionManager) {
            super(subscriptionManager, mock(FencedLockManager.class), mock(DurableQueues.class), mock(DurableLocalCommandBus.class), List.of());
        }

        @Override
        public String getProcessorName() {
            return "TestProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(AggregateType.of("Things"));
        }
    }
}
