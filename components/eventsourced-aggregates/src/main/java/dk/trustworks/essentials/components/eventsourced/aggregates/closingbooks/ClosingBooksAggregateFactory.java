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
 * Factory used by {@link ClosingBooksLogicalAggregateRepository} to create a new aggregate
 * instance for a newly opened generation.
 *
 * @param <LOGICAL_ID> the logical/business aggregate id type
 * @param <STREAM_ID>  the internal stream id type
 * @param <AGGREGATE>  the aggregate implementation type
 */
@FunctionalInterface
public interface ClosingBooksAggregateFactory<LOGICAL_ID, STREAM_ID, AGGREGATE> {
    /**
     * Create the initial aggregate instance for a newly opened generation.
     *
     * @param context describes the logical id, generated stream id, and generation number
     * @return the new aggregate instance to persist
     */
    AGGREGATE create(ClosingBooksAggregateInstantiationContext<LOGICAL_ID, STREAM_ID> context);
}
