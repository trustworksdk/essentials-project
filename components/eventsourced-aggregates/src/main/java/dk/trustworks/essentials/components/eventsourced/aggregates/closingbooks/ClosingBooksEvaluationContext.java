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

import java.time.OffsetDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A context object representing the evaluation state for closing books.
 *
 * @param <ID>         The type of the identifier for the aggregate.
 * @param <AGGREGATE>  The type of the aggregate being evaluated.
 * @param aggregateType       The type of the aggregate being evaluated.
 * @param logicalAggregateId  The logical identifier for the aggregate.
 * @param currentGeneration   The current generation of the aggregate.
 * @param aggregate           The aggregate instance being evaluated.
 * @param triggerMode         The mode used to trigger the evaluation process.
 * @param now                 The current date and time of this evaluation context.
 */
public record ClosingBooksEvaluationContext<ID, AGGREGATE>(AggregateType aggregateType,
                                                           LogicalAggregateId<ID> logicalAggregateId,
                                                           AggregateGeneration<ID> currentGeneration,
                                                           AGGREGATE aggregate,
                                                           ClosingBooksTriggerMode triggerMode,
                                                           OffsetDateTime now) {
    public ClosingBooksEvaluationContext {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(currentGeneration, "No currentGeneration provided");
        requireNonNull(aggregate, "No aggregate provided");
        requireNonNull(triggerMode, "No triggerMode provided");
        requireNonNull(now, "No now provided");
    }
}
