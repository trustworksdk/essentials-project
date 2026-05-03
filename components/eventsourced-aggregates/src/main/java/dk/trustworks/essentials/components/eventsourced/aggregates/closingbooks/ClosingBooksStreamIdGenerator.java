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

/**
 * Functional interface that defines a strategy for generating a stream ID for closing books processes.
 * Implementations of this interface should provide a mechanism to generate a unique identifier
 * for streams based on the provided parameters.
 *
 * @param <ID> the type of the identifier used in the logical aggregate ID.
 */
@FunctionalInterface
public interface ClosingBooksStreamIdGenerator<ID> {
    String generate(AggregateType aggregateType,
                    LogicalAggregateId<ID> logicalAggregateId,
                    long nextGeneration);
}
