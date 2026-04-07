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

/**
 * Factory used when closing books both closes the current generation and opens the next one.
 * <p>
 * The framework orchestrates the rollover, but the domain decides what state is carried
 * forward into the newly opened generation by implementing this interface.
 *
 * @param <LOGICAL_ID> the logical/business aggregate id type
 * @param <STREAM_ID>  the internal generation stream id type
 * @param <AGGREGATE>  the aggregate implementation type
 * @param <HINT>       optional domain-specific hint needed to seed the next generation
 */
@FunctionalInterface
public interface ClosingBooksNextGenerationFactory<LOGICAL_ID, STREAM_ID, AGGREGATE, HINT> {
    /**
     * Create the aggregate instance for the next generation based on the just-closed aggregate.
     *
     * @param currentAggregate the current aggregate whose generation is being closed
     * @param context          describes the logical id, generated stream id, and new generation number
     * @param hint             domain-specific hint for the next generation, such as the next statement period
     * @return the aggregate instance that seeds the new generation
     */
    AGGREGATE createNextGeneration(AGGREGATE currentAggregate,
                                   ClosingBooksAggregateInstantiationContext<LOGICAL_ID, STREAM_ID> context,
                                   HINT hint);
}
