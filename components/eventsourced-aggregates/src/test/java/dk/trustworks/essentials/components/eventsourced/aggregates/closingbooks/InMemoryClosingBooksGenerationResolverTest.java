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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryClosingBooksGenerationResolverTest {
    @Test
    void can_open_close_and_reopen_generations_for_a_logical_aggregate() {
        var resolver = new InMemoryClosingBooksGenerationResolver<String>();
        var aggregateType = AggregateType.of("Accounts");
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");

        var firstGeneration = resolver.openNextGeneration(aggregateType,
                                                          logicalAggregateId,
                                                          (type, id, generation) -> "Account-123#" + generation);

        assertThat(firstGeneration.generation()).isEqualTo(1);
        assertThat(firstGeneration.streamAggregateId()).isEqualTo("Account-123#1");
        assertThat(firstGeneration.isOpen()).isTrue();
        assertThat(resolver.resolveCurrentGeneration(aggregateType, logicalAggregateId))
                .contains(firstGeneration);

        var closedGeneration = resolver.closeCurrentGeneration(aggregateType, logicalAggregateId);

        assertThat(closedGeneration.generation()).isEqualTo(1);
        assertThat(closedGeneration.isClosed()).isTrue();
        assertThat(closedGeneration.closedAt()).isPresent();
        assertThat(resolver.resolveCurrentGeneration(aggregateType, logicalAggregateId)).isEmpty();

        var secondGeneration = resolver.openNextGeneration(aggregateType,
                                                           logicalAggregateId,
                                                           (type, id, generation) -> "Account-123#" + generation);

        assertThat(secondGeneration.generation()).isEqualTo(2);
        assertThat(secondGeneration.isOpen()).isTrue();
        assertThat(resolver.loadGenerations(aggregateType, logicalAggregateId)).hasSize(2);
    }

    @Test
    void cannot_open_a_new_generation_while_one_is_still_open() {
        var resolver = new InMemoryClosingBooksGenerationResolver<String>();
        var aggregateType = AggregateType.of("Accounts");
        var logicalAggregateId = new LogicalAggregateId<>("Account-123");

        resolver.openNextGeneration(aggregateType,
                                    logicalAggregateId,
                                    (type, id, generation) -> "Account-123#" + generation);

        assertThatThrownBy(() -> resolver.openNextGeneration(aggregateType,
                                                             logicalAggregateId,
                                                             (type, id, generation) -> "Account-123#" + generation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an open generation");
    }
}
