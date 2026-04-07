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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A record representing the context required for closing books in a domain model.
 * It encapsulates the aggregate type, the logical aggregate identifier,
 * the current aggregate generation, and the aggregate itself.
 *
 * @param <ID>        The type of the identifier used in the logical aggregate.
 * @param <AGGREGATE> The type of the aggregate.
 *
 * @param aggregateType       The type of the aggregate being handled,
 *                            used to differentiate between different types of aggregates.
 * @param logicalAggregateId  The logical identifier for the aggregate,
 *                            ensuring a unique aggregate identity within the current context.
 * @param currentGeneration   The current generation of the aggregate,
 *                            providing context for versioning and concurrency handling.
 * @param aggregate           The actual aggregate instance being handled in the current context.
 *
 */
public record ClosingBooksContext<ID, AGGREGATE>(AggregateType aggregateType,
                                                 LogicalAggregateId<ID> logicalAggregateId,
                                                 AggregateGeneration<ID> currentGeneration,
                                                 AGGREGATE aggregate) {
    public ClosingBooksContext {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(currentGeneration, "No currentGeneration provided");
        requireNonNull(aggregate, "No aggregate provided");
    }
}
