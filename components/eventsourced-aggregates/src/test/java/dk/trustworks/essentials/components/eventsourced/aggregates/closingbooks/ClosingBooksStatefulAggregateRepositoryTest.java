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

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClosingBooksStatefulAggregateRepositoryTest {
    @Test
    void loads_using_the_current_open_generation_stream_id() {
        var aggregateType = AggregateType.of("Accounts");
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        var generationResolver = new InMemoryClosingBooksGenerationResolver<String>();
        generationResolver.openNextGeneration(aggregateType,
                                             logicalAggregateId,
                                             (type, id, generation) -> "Account-123#" + generation);

        @SuppressWarnings("unchecked")
        var delegate = mock(StatefulAggregateRepository.class);
        var aggregate = mock(TestAggregate.class);
        when(delegate.tryLoad("Account-123#1")).thenReturn(Optional.of(aggregate));

        var repository = new ClosingBooksStatefulAggregateRepository<String, TestEvent, TestAggregate>(aggregateType,
                                                                                                        delegate,
                                                                                                        generationResolver);

        assertThat(repository.tryLoad(logicalAggregateId)).contains(aggregate);
        verify(delegate).tryLoad("Account-123#1");
    }

    @Test
    void returns_empty_when_no_open_generation_exists() {
        var aggregateType = AggregateType.of("Accounts");
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        @SuppressWarnings("unchecked")
        var delegate = mock(StatefulAggregateRepository.class);

        var repository = new ClosingBooksStatefulAggregateRepository<String, TestEvent, TestAggregate>(aggregateType,
                                                                                                        delegate,
                                                                                                        new InMemoryClosingBooksGenerationResolver<>());

        assertThat(repository.tryLoad(logicalAggregateId)).isEmpty();
        verifyNoInteractions(delegate);
    }

    static class TestAggregate extends AggregateRoot<String, TestEvent, TestAggregate> {
    }

    record TestEvent() {
    }
}
