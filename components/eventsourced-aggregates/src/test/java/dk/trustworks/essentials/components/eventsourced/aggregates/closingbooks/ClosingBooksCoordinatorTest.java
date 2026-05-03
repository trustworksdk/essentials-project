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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ClosingBooksCoordinatorTest {
    @Test
    void resolve_or_open_current_generation_opens_the_first_generation_when_missing() {
        var aggregateType = AggregateType.of("Accounts");
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        var repository = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline());

        var generation = coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);

        assertThat(generation.generation()).isEqualTo(1);
        assertThat(generation.streamAggregateId()).isEqualTo("Account-123#1");
        assertThat(generation.isOpen()).isTrue();
    }

    @Test
    void close_and_open_next_generation_rolls_the_generation_forward() {
        var aggregateType = AggregateType.of("Accounts");
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        var repository = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline());

        coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
        var nextGeneration = coordinator.closeAndOpenNextGeneration(logicalAggregateId);

        assertThat(nextGeneration.generation()).isEqualTo(2);
        assertThat(nextGeneration.streamAggregateId()).isEqualTo("Account-123#2");
        assertThat(nextGeneration.isOpen()).isTrue();
        assertThat(repository.loadGenerations(aggregateType, logicalAggregateId)).hasSize(2);
    }

    @Test
    void evaluate_policy_keeps_the_current_generation_open_when_policy_says_keep_open() {
        var aggregateType = AggregateType.of("Accounts");
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        var repository = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC));

        var generation = coordinator.evaluatePolicy(logicalAggregateId,
                                                    "aggregate",
                                                    ClosingBooksTriggerMode.ON_ACCESS,
                                                    ClosingBooksDecisionPolicies.keepOpen());

        assertThat(generation.generation()).isEqualTo(1);
        assertThat(generation.streamAggregateId()).isEqualTo("Account-123#1");
        assertThat(generation.isOpen()).isTrue();
    }

    @Test
    void evaluate_policy_can_close_only_without_opening_the_next_generation() {
        var aggregateType = AggregateType.of("Accounts");
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        var repository = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC));

        coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
        var generation = coordinator.evaluatePolicy(logicalAggregateId,
                                                    "aggregate",
                                                    ClosingBooksTriggerMode.EXPLICIT_COMMAND,
                                                    ClosingBooksDecisionPolicies.closeOnly());

        assertThat(generation.generation()).isEqualTo(1);
        assertThat(generation.isClosed()).isTrue();
        assertThat(repository.resolveCurrentGeneration(aggregateType, logicalAggregateId)).isEmpty();
    }

    @Test
    void evaluate_policy_can_roll_the_generation_forward() {
        var aggregateType = AggregateType.of("Accounts");
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");
        var repository = new InMemoryClosingBooksGenerationResolver<String>();
        var coordinator = new ClosingBooksCoordinator<>(aggregateType,
                                                        repository,
                                                        (type, id, nextGeneration) -> id + "#" + nextGeneration,
                                                        InlineUnitOfWorkFactories.inline(),
                                                        Clock.fixed(Instant.parse("2026-03-29T10:15:30Z"), ZoneOffset.UTC));

        coordinator.resolveOrOpenCurrentGeneration(logicalAggregateId);
        var generation = coordinator.evaluatePolicy(logicalAggregateId,
                                                    "aggregate",
                                                    ClosingBooksTriggerMode.EXPLICIT_COMMAND,
                                                    ClosingBooksDecisionPolicies.closeAndOpenNext());

        assertThat(generation.generation()).isEqualTo(2);
        assertThat(generation.isOpen()).isTrue();
        assertThat(generation.streamAggregateId()).isEqualTo("Account-123#2");
        assertThat(repository.loadGenerations(aggregateType, logicalAggregateId)).hasSize(2);
    }
}
