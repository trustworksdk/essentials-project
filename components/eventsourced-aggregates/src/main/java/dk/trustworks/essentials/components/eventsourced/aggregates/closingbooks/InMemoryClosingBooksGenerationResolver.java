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
import dk.trustworks.essentials.shared.collections.Lists;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * In-memory {@link ClosingBooksOpenGenerationRepository} implementation used primarily for tests and local coordination.
 */
public class InMemoryClosingBooksGenerationResolver<ID> implements ClosingBooksOpenGenerationRepository<ID> {
    private final Map<GenerationKey<ID>, List<AggregateGeneration<ID>>> generations = new ConcurrentHashMap<>();

    @Override
    public Optional<AggregateGeneration<ID>> resolveCurrentGeneration(AggregateType aggregateType,
                                                                      LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");

        return generationsFor(aggregateType, logicalAggregateId).stream()
                                                                .filter(AggregateGeneration::isOpen)
                                                                .findFirst();
    }

    @Override
    public List<AggregateGeneration<ID>> loadGenerations(AggregateType aggregateType,
                                                         LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");

        return List.copyOf(generationsFor(aggregateType, logicalAggregateId));
    }

    @Override
    public List<AggregateGeneration<ID>> loadOpenGenerations(AggregateType aggregateType,
                                                             int limit) {
        requireNonNull(aggregateType, "No aggregateType provided");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }

        return generations.entrySet()
                          .stream()
                          .filter(entry -> entry.getKey().aggregateType().equals(aggregateType))
                          .flatMap(entry -> entry.getValue().stream())
                          .filter(AggregateGeneration::isOpen)
                          .sorted(Comparator.comparing(AggregateGeneration<ID>::openedAt)
                                            .thenComparingLong(AggregateGeneration::generation))
                          .limit(limit)
                          .toList();
    }

    @Override
    public AggregateGeneration<ID> openNextGeneration(AggregateType aggregateType,
                                                      LogicalAggregateId<ID> logicalAggregateId,
                                                      String streamAggregateId) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(streamAggregateId, "No streamAggregateId provided");

        var key = new GenerationKey<>(aggregateType, logicalAggregateId);
        var updatedGenerations = generations.compute(key, (ignored, existingGenerations) -> {
            var mutableGenerations = existingGenerations != null ? new ArrayList<>(existingGenerations) : new ArrayList<AggregateGeneration<ID>>();
            var currentGeneration = mutableGenerations.stream()
                                                      .filter(AggregateGeneration::isOpen)
                                                      .findFirst();
            if (currentGeneration.isPresent()) {
                throw new IllegalStateException(msg("AggregateType '{}' with logicalAggregateId '{}' already has an open generation '{}'",
                                                    aggregateType,
                                                    logicalAggregateId,
                                                    currentGeneration.get().generation()));
            }

            var nextGeneration = new AggregateGeneration<>(aggregateType,
                                                           logicalAggregateId,
                                                           mutableGenerations.size() + 1L,
                                                           streamAggregateId,
                                                           GenerationState.OPEN,
                                                           OffsetDateTime.now(),
                                                           Optional.empty());
            mutableGenerations.add(nextGeneration);
            return mutableGenerations;
        });
        return lastGenerationOrThrow(updatedGenerations,
                                     aggregateType,
                                     logicalAggregateId,
                                     "open next");
    }

    @Override
    public AggregateGeneration<ID> closeCurrentGeneration(AggregateType aggregateType,
                                                          LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");

        var key = new GenerationKey<>(aggregateType, logicalAggregateId);
        var updatedGenerations = generations.compute(key, (ignored, existingGenerations) -> {
            if (existingGenerations == null || existingGenerations.isEmpty()) {
                throw new IllegalStateException(msg("AggregateType '{}' with logicalAggregateId '{}' doesn't have any generations to close",
                                                    aggregateType,
                                                    logicalAggregateId));
            }

            var mutableGenerations = new ArrayList<>(existingGenerations);
            for (int index = 0; index < mutableGenerations.size(); index++) {
                var generation = mutableGenerations.get(index);
                if (generation.isOpen()) {
                    mutableGenerations.set(index, generation.close(OffsetDateTime.now()));
                    return mutableGenerations;
                }
            }

            throw new IllegalStateException(msg("AggregateType '{}' with logicalAggregateId '{}' doesn't have an open generation to close",
                                                aggregateType,
                                                logicalAggregateId));
        });
        return lastGenerationOrThrow(updatedGenerations,
                                     aggregateType,
                                     logicalAggregateId,
                                     "close current");
    }

    private List<AggregateGeneration<ID>> generationsFor(AggregateType aggregateType,
                                                         LogicalAggregateId<ID> logicalAggregateId) {
        return generations.getOrDefault(new GenerationKey<>(aggregateType, logicalAggregateId), List.of());
    }

    private AggregateGeneration<ID> lastGenerationOrThrow(List<AggregateGeneration<ID>> generations,
                                                          AggregateType aggregateType,
                                                          LogicalAggregateId<ID> logicalAggregateId,
                                                          String operation) {
        return Lists.last(generations)
                    .orElseThrow(() -> new IllegalStateException(msg("Failed to {} generation for AggregateType '{}' and logicalAggregateId '{}'",
                                                                     operation,
                                                                     aggregateType,
                                                                     logicalAggregateId)));
    }

    private record GenerationKey<ID>(AggregateType aggregateType,
                                     LogicalAggregateId<ID> logicalAggregateId) {
        private GenerationKey {
            requireNonNull(aggregateType, "No aggregateType provided");
            requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        }
    }
}
