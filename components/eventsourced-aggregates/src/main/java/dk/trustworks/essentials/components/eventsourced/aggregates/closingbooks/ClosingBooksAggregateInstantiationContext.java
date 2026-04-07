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
 * Context passed to a {@link ClosingBooksAggregateFactory} when a new generation is opened.
 *
 * @param logicalAggregateId the stable business id that spans generations
 * @param streamAggregateId  the internal generation-specific stream id
 * @param generation         the generation number
 * @param <ID>               the logical aggregate id type
 * @param <STREAM_ID>        the stream aggregate id type
 */
public record ClosingBooksAggregateInstantiationContext<ID, STREAM_ID>(LogicalAggregateId<ID> logicalAggregateId,
                                                                       STREAM_ID streamAggregateId,
                                                                       long generation) {
}
