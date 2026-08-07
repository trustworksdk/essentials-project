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

import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Typed variant of {@link AggregateClosingBooksGenerationAccess} backed by a concrete
 * {@link ClosingBooksGenerationRepository} and logical aggregate id serializer.
 */
public interface TypedAggregateClosingBooksGenerationAccess<ID> extends AggregateClosingBooksGenerationAccess {
    ClosingBooksGenerationRepository<ID> generationRepository();

    ClosingBooksLogicalAggregateIdSerializer<ID> logicalAggregateIdSerializer();

    @Override
    default Optional<AggregateGeneration<String>> resolveCurrentGeneration(String logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return generationRepository().resolveCurrentGeneration(aggregateType(),
                                                               logicalAggregateIdSerializer().deserialize(logicalAggregateId))
                                     .map(this::toStringBasedGeneration);
    }

    @Override
    default List<AggregateGeneration<String>> loadGenerations(String logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return generationRepository().loadGenerations(aggregateType(),
                                                      logicalAggregateIdSerializer().deserialize(logicalAggregateId))
                                     .stream()
                                     .map(this::toStringBasedGeneration)
                                     .toList();
    }

    private AggregateGeneration<String> toStringBasedGeneration(AggregateGeneration<ID> generation) {
        return new AggregateGeneration<>(generation.aggregateType(),
                                         new LogicalAggregateId<>(generation.logicalAggregateId().toString()),
                                         generation.generation(),
                                         generation.streamAggregateId(),
                                         generation.state(),
                                         generation.openedAt(),
                                         generation.closedAt());
    }
}
