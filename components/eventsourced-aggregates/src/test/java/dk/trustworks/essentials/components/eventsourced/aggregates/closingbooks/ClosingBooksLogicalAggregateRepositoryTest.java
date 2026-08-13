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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventsToPersist;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregate;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClosingBooksLogicalAggregateRepositoryTest {
    @Test
    void load_or_open_creates_first_generation_and_persists_new_aggregate() {
        var aggregateType = AggregateType.of("Accounts");
        var generationRepository = new InMemoryClosingBooksGenerationResolver<String>();
        @SuppressWarnings("unchecked")
        var delegate = mock(StatefulAggregateRepository.class);
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        generationRepository,
                                                        (type, logicalId, generation) -> logicalId.value() + "#" + generation,
                                                        InlineUnitOfWorkFactories.inline());
        var repository = new ClosingBooksLogicalAggregateRepository<String, String, TestEvent, TestAggregate>(aggregateType,
                                                                                                               delegate,
                                                                                                               coordinator,
                                                                                                               ClosingBooksIdSerializer.stringBased());

        when(delegate.tryLoad("account-1#1")).thenReturn(Optional.empty());
        when(delegate.save(any(TestAggregate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var aggregate = repository.loadOrOpen(new LogicalAggregateId<>("account-1"),
                                              context -> new TestAggregate(context.streamAggregateId()));

        assertThat(aggregate.aggregateId()).isEqualTo("account-1#1");
        verify(delegate).save(any(TestAggregate.class));
    }

    @Test
    void close_and_open_next_generation_returns_new_generation_aggregate() {
        var aggregateType = AggregateType.of("Accounts");
        var generationRepository = new InMemoryClosingBooksGenerationResolver<String>();
        @SuppressWarnings("unchecked")
        var delegate = mock(StatefulAggregateRepository.class);
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        generationRepository,
                                                        (type, logicalId, generation) -> logicalId.value() + "#" + generation,
                                                        InlineUnitOfWorkFactories.inline());
        var repository = new ClosingBooksLogicalAggregateRepository<String, String, TestEvent, TestAggregate>(aggregateType,
                                                                                                               delegate,
                                                                                                               coordinator,
                                                                                                               ClosingBooksIdSerializer.stringBased());

        when(delegate.save(any(TestAggregate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        repository.open(new LogicalAggregateId<>("account-1"),
                        context -> new TestAggregate(context.streamAggregateId()));

        var nextAggregate = repository.closeAndOpenNextGeneration(new LogicalAggregateId<>("account-1"),
                                                                  context -> new TestAggregate(context.streamAggregateId()));

        assertThat(nextAggregate.aggregateId()).isEqualTo("account-1#2");
        assertThat(generationRepository.resolveCurrentGeneration(aggregateType, new LogicalAggregateId<>("account-1")))
                .get()
                .extracting(AggregateGeneration::streamAggregateId)
                .isEqualTo("account-1#2");
    }

    @Test
    void close_and_open_next_generation_can_use_current_aggregate_and_hint() {
        var aggregateType = AggregateType.of("Accounts");
        var generationRepository = new InMemoryClosingBooksGenerationResolver<String>();
        @SuppressWarnings("unchecked")
        var delegate = mock(StatefulAggregateRepository.class);
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        generationRepository,
                                                        (type, logicalId, generation) -> logicalId.value() + "#" + generation,
                                                        InlineUnitOfWorkFactories.inline());
        var repository = new ClosingBooksLogicalAggregateRepository<String, String, TestEvent, TestAggregate>(aggregateType,
                                                                                                               delegate,
                                                                                                               coordinator,
                                                                                                               ClosingBooksIdSerializer.stringBased());

        when(delegate.save(any(TestAggregate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var currentAggregate = repository.open(new LogicalAggregateId<>("account-1"),
                                               context -> new TestAggregate(context.streamAggregateId()));

        var nextAggregate = repository.closeAndOpenNextGeneration(new LogicalAggregateId<>("account-1"),
                                                                  currentAggregate,
                                                                  "next-period",
                                                                  (aggregate, context, hint) -> new TestAggregate(context.streamAggregateId() + "-" + hint));

        assertThat(nextAggregate.aggregateId()).isEqualTo("account-1#2-next-period");
    }

    private sealed interface TestEvent permits TestAggregateOpened {
    }

    private record TestAggregateOpened(String streamAggregateId) implements TestEvent {
    }

    private static final class TestAggregate implements StatefulAggregate<String, TestEvent, TestAggregate> {
        private final String  aggregateId;
        private       boolean hasBeenRehydrated;

        private TestAggregate(String aggregateId) {
            this.aggregateId = aggregateId;
        }

        @Override
        public String aggregateId() {
            return aggregateId;
        }

        @Override
        public EventsToPersist<String, TestEvent> getUncommittedChanges() {
            return EventsToPersist.noEvents(aggregateId);
        }

        @Override
        public void markChangesAsCommitted() {
        }

        @Override
        public boolean hasBeenRehydrated() {
            return hasBeenRehydrated;
        }

        @Override
        public TestAggregate rehydrate(AggregateEventStream<String> persistedEvents) {
            hasBeenRehydrated = true;
            return this;
        }

        @Override
        public EventOrder eventOrderOfLastRehydratedEvent() {
            return EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED;
        }
    }
}
