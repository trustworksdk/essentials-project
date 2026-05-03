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

import dk.trustworks.essentials.components.eventsourced.aggregates.decider.AggregateIdResolver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Utility class providing methods for resolving aggregate IDs in the context of closing books.
 * This class is final and cannot be instantiated.
 */
public final class ClosingBooksAggregateIdResolvers {
    private ClosingBooksAggregateIdResolvers() {
    }

    /**
     * Resolves the current stream aggregate ID for a specified aggregate type by combining a logical aggregate ID resolver
     * and a generation resolver. This method ensures that the provided aggregate type, logical ID resolver, and generation
     * resolver are not null and uses them to compute the stream aggregate ID.
     *
     * @param <SOURCE>                The type of the source object from which the logical aggregate ID is resolved.
     * @param <LOGICAL_ID>            The type of the logical aggregate ID.
     * @param aggregateType           The type of the aggregate to resolve the stream ID for. Must not be null.
     * @param logicalAggregateIdResolver A resolver for determining the logical aggregate ID based on a source object. Must not be null.
     * @param generationResolver      A resolver for determining the current generation of the aggregate based on the aggregate type
     *                                and logical aggregate ID. Must not be null.
     * @return A resolver that takes a source of type {@code SOURCE} and returns an optional string representing the stream
     *         aggregate ID.
     */
    public static <SOURCE, LOGICAL_ID> AggregateIdResolver<SOURCE, String> resolveCurrentStreamAggregateId(AggregateType aggregateType,
                                                                                                            AggregateIdResolver<SOURCE, LOGICAL_ID> logicalAggregateIdResolver,
                                                                                                            ClosingBooksGenerationResolver<LOGICAL_ID> generationResolver) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateIdResolver, "No logicalAggregateIdResolver provided");
        requireNonNull(generationResolver, "No generationResolver provided");

        return source -> logicalAggregateIdResolver.resolveFrom(source)
                                                   .flatMap(logicalAggregateId -> generationResolver.resolveCurrentGeneration(aggregateType,
                                                                                                                             new LogicalAggregateId<>(logicalAggregateId)))
                                                   .map(AggregateGeneration::streamAggregateId);
    }
}
