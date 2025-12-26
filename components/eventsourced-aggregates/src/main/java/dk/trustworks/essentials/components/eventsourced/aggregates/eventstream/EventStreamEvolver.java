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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream;

import java.util.*;
import java.util.stream.Stream;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A functional interface for applying events to evolve state in event sourcing systems.
 * <p>
 * The {@link EventStreamEvolver} is responsible for taking a current state and applying an event
 * to produce a new state. This follows the functional event sourcing pattern where state
 * is derived by applying a sequence of events through a left-fold operation.
 * <p>
 * This interface is designed to be used with {@link EventStreamDecider} implementations
 * to reconstruct state from event streams for command processing.
 *
 * <h3>Key Characteristics:</h3>
 * <ul>
 *   <li><strong>Functional:</strong> Pure function that applies events to state</li>
 *   <li><strong>Immutable:</strong> Creates new state instances rather than modifying existing ones</li>
 *   <li><strong>Composable:</strong> Can be chained to apply multiple events</li>
 *   <li><strong>Null-Safe:</strong> Handles null states gracefully for aggregate initialization</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * public class OrderEvolver implements EventStreamEvolver<OrderEvent, OrderState> {
 *     @Override
 *     public Optional<OrderState> applyEvent(OrderEvent event, Optional<OrderState> currentState) {
 *         requireNonNull(event, "event cannot be null");
 *         requireNonNull(currentState, "currentState cannot be null");
 *
 *         return switch (event) {
 *             case OrderCreated created -> Optional.of(new OrderState(
 *                 created.orderId(),
 *                 created.customerId(),
 *                 OrderStatus.PENDING,
 *                 created.createdAt()
 *             ));
 *
 *             case OrderStatusChanged statusChanged -> currentState.map(state ->
 *                 state.withStatus(statusChanged.newStatus())
 *             );
 *
 *             case OrderCancelled cancelled -> currentState.map(state ->
 *                 state.withStatus(OrderStatus.CANCELLED)
 *                     .withCancelledAt(cancelled.cancelledAt())
 *             );
 *
 *             default -> currentState; // Unknown event types are ignored
 *         };
 *     }
 * }
 * }</pre>
 *
 * @param <EVENT> The type of events this evolver can process
 * @param <STATE> The type of state this evolver produces
 * @see EventStreamDecider
 * @see #applyEvents(EventStreamEvolver, Optional, List)
 */
@FunctionalInterface
public interface EventStreamEvolver<EVENT, STATE> {
    /**
     * Applies a single event to the current state to produce a new state.
     * <p>
     * This method should be implemented as a pure function that:
     * <ul>
     *   <li>Takes the current state (which may be empty for new aggregates)</li>
     *   <li>Applies the event to transform the state</li>
     *   <li>Returns the new state wrapped in Optional</li>
     *   <li>Returns {@code Optional.empty()} if the event should result in no state</li>
     * </ul>
     *
     * <h3>Implementation Guidelines:</h3>
     * <ul>
     *   <li><strong>Pure Function:</strong> Same input should always produce the same output</li>
     *   <li><strong>Immutable:</strong> Create new state instances, don't modify existing ones</li>
     *   <li><strong>Null Safe:</strong> Handle null states gracefully</li>
     *   <li><strong>Fail Fast:</strong> Use {@link dk.trustworks.essentials.shared.FailFast#requireNonNull} for validation</li>
     *   <li><strong>Pattern Matching:</strong> Use switch expressions or pattern matching for event types</li>
     * </ul>
     *
     * @param event        The event to apply to the state. Must not be null.
     * @param currentState The current state, which may be empty for new aggregates. Must not be null.
     * @return The new state after applying the event, or empty if the event results in no state.
     * @throws IllegalArgumentException if the event is invalid or cannot be processed
     */
    Optional<STATE> applyEvent(EVENT event, Optional<STATE> currentState);

    /**
     * Applies a sequence of events to an initial state using a left-fold operation.
     * <p>
     * This utility method applies events in order, passing the result of each
     * event application as input to the next event application. This is the
     * fundamental operation for reconstructing state from event streams.
     *
     * <h3>Example Usage:</h3>
     * <pre>{@code
     * var evolver = new OrderEvolver();
     * var initialState = Optional.<OrderState>empty();
     * var events = List.of(
     *     new OrderCreated(orderId, customerId),
     *     new OrderStatusChanged(orderId, OrderStatus.CONFIRMED),
     *     new OrderShipped(orderId, shippingInfo)
     * );
     *
     * Optional<OrderState> finalState = EventStreamEvolver.applyEvents(evolver, initialState, events);
     * }</pre>
     *
     * @param <EVENT>      The type of events in the event stream
     * @param <STATE>      The type of state being evolved
     * @param evolver      The evolver to use for applying events. Must not be null.
     * @param initialState The initial state to start with. Must not be null.
     * @param events       The events to apply in order. Must not be null.
     * @return The final state after applying all events
     * @throws IllegalArgumentException if any parameter is null
     */
    static <EVENT, STATE> Optional<STATE> applyEvents(
            EventStreamEvolver<EVENT, STATE> evolver,
            Optional<STATE> initialState,
            List<EVENT> events) {

        requireNonNull(evolver, "evolver cannot be null");
        requireNonNull(initialState, "initialState cannot be null");
        requireNonNull(events, "events cannot be null");

        var currentState = initialState;
        for (var event : events) {
            currentState = evolver.applyEvent(event, currentState);
        }

        return currentState;
    }

    /**
     * Convenience method for applying events starting with no initial state.
     * <p>
     * This is equivalent to calling {@link #applyEvents(EventStreamEvolver, Optional, List)}
     * with {@code Optional.empty()} as the initial state:
     *
     * <h3>Example Usage:</h3>
     * <pre>{@code
     * var evolver = new OrderEvolver();
     * var events = List.of(
     *     new OrderCreated(orderId, customerId),
     *     new OrderStatusChanged(orderId, OrderStatus.CONFIRMED),
     *     new OrderShipped(orderId, shippingInfo)
     * );
     *
     * Optional<OrderState> finalState = EventStreamEvolver.applyEvents(evolver, events);
     * }</pre>
     *
     * @param <EVENT> The type of events in the event stream
     * @param <STATE> The type of state being evolved
     * @param evolver The evolver to use for applying events. Must not be null.
     * @param events  The events to apply in order. Must not be null.
     * @return The final state after applying all events
     * @throws IllegalArgumentException if any parameter is null
     * @see #applyEvents(EventStreamEvolver, Optional, List)
     */
    static <EVENT, STATE> Optional<STATE> applyEvents(
            EventStreamEvolver<EVENT, STATE> evolver,
            List<EVENT> events) {

        return applyEvents(evolver, Optional.empty(), events);
    }

    /**
     * Extracts and filters events from an {@link AggregateEventStream} of a specified type.
     * <p>
     * This method processes the event stream by:
     * <ul>
     *     <li>Deserializing each persisted event into its runtime type representation.</li>
     *     <li>Filtering events that match the specified {@code eventType}.</li>
     *     <li>Casting them to the specified type.</li>
     * </ul>
     *
     * <h3>Usage Example:</h3>
     * <pre>{@code
     * AggregateEventStream<?> persistedEventsStream = ... // Obtain or build an event stream
     * // Deserializes from PersistedEvents.EventJSON to OrderEvent
     * Stream<OrderEvent> eventsStream = EventStreamEvolver.extractEvents(persistedEventsStream, OrderEvent.class);
     * Optional<OrderState> state = EventStreamEvolver.applyEvents(evolver, eventsStream.toList());
     * }</pre>
     *
     * @param <E>       The specific type of events to extract.
     * @param stream    The {@link AggregateEventStream} containing serialized events. Must not be null.
     * @param eventType The class representing the type of events to extract. Must not be null.
     * @return A {@link Stream} of events matching the specified type after deserialization.
     * @throws IllegalArgumentException if {@code stream} or {@code eventType} is null.
     * @see #applyEvents(EventStreamEvolver, List)
     */
    static <E> Stream<E> extractEvents(AggregateEventStream<?> stream, Class<E> eventType) {
        requireNonNull(stream, "stream cannot be null");
        requireNonNull(eventType, "eventType cannot be null");
        return stream.eventList().stream()
                     .map(pe -> pe.event().deserialize())
                     .filter(eventType::isInstance)
                     .map(eventType::cast);
    }

    /**
     * Extracts and filters events from an {@link AggregateEventStream} of a specified type.
     * <p>
     * This method processes the event stream by:
     * <ul>
     *     <li>Deserializing each persisted event into its runtime type representation.</li>
     *     <li>Filtering events that match the specified {@code eventType}.</li>
     *     <li>Casting them to the specified type.</li>
     *     <li>Returning them as a List</li>
     * </ul>
     *
     * <h3>Usage Example:</h3>
     * <pre>{@code
     * AggregateEventStream<?> persistedEventsStream = ... // Obtain or build an event stream
     * // Deserializes from PersistedEvents.EventJSON to OrderEvent
     * List<OrderEvent> eventsStream = EventStreamEvolver.extractEventsAsList(persistedEventsStream, OrderEvent.class);
     * Optional<OrderState> state = EventStreamEvolver.applyEvents(evolver, eventsStream);
     * }</pre>
     *
     * @param <E>       The specific type of events to extract.
     * @param stream    The {@link AggregateEventStream} containing serialized events. Must not be null.
     * @param eventType The class representing the type of events to extract. Must not be null.
     * @return A {@link List} of events matching the specified type after deserialization.
     * @throws IllegalArgumentException if {@code stream} or {@code eventType} is null.
     * @see #applyEvents(EventStreamEvolver, List)
     * @see #extractEvents(AggregateEventStream, Class)
     */
    static <E> List<E> extractEventsAsList(AggregateEventStream<?> stream, Class<E> eventType) {
        return extractEvents(stream, eventType).toList();
    }
}