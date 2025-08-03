/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;

import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Configuration for integrating {@link EventStreamDecider} implementations with the event store infrastructure.
 * <p>
 * This configuration class provides the necessary metadata and functions to wire up
 * {@link EventStreamDecider} implementations with the underlying event store, command handling,
 * and aggregate management infrastructure.
 * <p>
 * The configuration defines how commands and events are processed, how aggregate IDs
 * are extracted and serialized, and how deciders are matched to aggregate types.
 * 
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * @Bean
 * public EventStreamAggregateTypeConfiguration orderConfiguration() {
 *     return new EventStreamAggregateTypeConfiguration(
 *         AggregateType.of("Orders"),
 *         OrderId.class,
 *         AggregateIdSerializer.serializerFor(OrderId.class),
 *         new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
 *         command -> ((OrderCommand) command).orderId(),
 *         event -> ((OrderEvent) event).orderId()
 *     );
 * }
 * }</pre>
 * 
 * @param aggregateType The type of aggregate this configuration applies to
 * @param aggregateIdType The Java class representing the aggregate ID type
 * @param aggregateIdSerializer The serializer for aggregate IDs
 * @param deciderSupportsAggregateTypeChecker Function to determine if a decider supports this aggregate type
 * @param commandAggregateIdResolver Function to extract aggregate ID from commands
 * @param eventAggregateIdResolver Function to extract aggregate ID from events
 * 
 * @see EventStreamDecider
 * @see EventStreamDeciderSupportsAggregateTypeChecker
 * @see dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderCommandHandlerAdapter
 */
public record EventStreamAggregateTypeConfiguration(
        AggregateType aggregateType,
        Class<?> aggregateIdType,
        AggregateIdSerializer aggregateIdSerializer,
        EventStreamDeciderSupportsAggregateTypeChecker deciderSupportsAggregateTypeChecker,
        Function<Object, Object> commandAggregateIdResolver,
        Function<Object, Object> eventAggregateIdResolver
) {
    
    /**
     * Creates a new {@link EventStreamAggregateTypeConfiguration}
     * 
     * @param aggregateType The type of aggregate this configuration applies to. Must not be null.
     * @param aggregateIdType The Java class representing the aggregate ID type. Must not be null.
     * @param aggregateIdSerializer The serializer for aggregate IDs. Must not be null.
     * @param deciderSupportsAggregateTypeChecker Function to determine if a decider supports this aggregate type. Must not be null.
     * @param commandAggregateIdResolver Function to extract aggregate ID from commands. Must not be null.
     * @param eventAggregateIdResolver Function to extract aggregate ID from events. Must not be null.
     * @throws IllegalArgumentException if any parameter is null
     */
    public EventStreamAggregateTypeConfiguration {
        requireNonNull(aggregateType, "aggregateType cannot be null");
        requireNonNull(aggregateIdType, "aggregateIdType cannot be null");
        requireNonNull(aggregateIdSerializer, "aggregateIdSerializer cannot be null");
        requireNonNull(deciderSupportsAggregateTypeChecker, "deciderSupportsAggregateTypeChecker cannot be null");
        requireNonNull(commandAggregateIdResolver, "commandAggregateIdResolver cannot be null");
        requireNonNull(eventAggregateIdResolver, "eventAggregateIdResolver cannot be null");
    }
}