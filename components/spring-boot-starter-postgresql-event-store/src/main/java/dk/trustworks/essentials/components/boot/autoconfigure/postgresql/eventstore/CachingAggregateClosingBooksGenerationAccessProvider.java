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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class CachingAggregateClosingBooksGenerationAccessProvider implements AggregateClosingBooksGenerationAccessProvider {
    private final List<TypedAggregateClosingBooksGenerationAccess<?>> accessors;
    private final Map<String, Optional<AggregateClosingBooksGenerationAccess>> cache = new ConcurrentHashMap<>();

    public CachingAggregateClosingBooksGenerationAccessProvider(List<TypedAggregateClosingBooksGenerationAccess<?>> accessors) {
        this.accessors = List.copyOf(requireNonNull(accessors, "No accessors provided"));
    }

    @Override
    public Optional<AggregateClosingBooksGenerationAccess> resolve(AggregateType aggregateType) {
        requireNonNull(aggregateType, "No aggregateType provided");
        return cache.computeIfAbsent(aggregateType + "::<any>",
                                     ignored -> {
                                         var matches = accessors.stream()
                                                                .filter(accessor -> accessor.aggregateType().equals(aggregateType))
                                                                .map(accessor -> (AggregateClosingBooksGenerationAccess) accessor)
                                                                .toList();
                                         if (matches.size() == 1) {
                                             return Optional.of(matches.get(0));
                                         }
                                         return Optional.empty();
                                     });
    }

    @Override
    public Optional<AggregateClosingBooksGenerationAccess> resolve(AggregateType aggregateType,
                                                                   Class<?> aggregateImplementationType) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        return cache.computeIfAbsent(aggregateType + "::" + aggregateImplementationType.getName(),
                                     ignored -> accessors.stream()
                                                         .filter(accessor -> accessor.aggregateType().equals(aggregateType))
                                                         .filter(accessor -> accessor.aggregateImplementationType().equals(aggregateImplementationType))
                                                         .map(accessor -> (AggregateClosingBooksGenerationAccess) accessor)
                                                         .findFirst());
    }
}
