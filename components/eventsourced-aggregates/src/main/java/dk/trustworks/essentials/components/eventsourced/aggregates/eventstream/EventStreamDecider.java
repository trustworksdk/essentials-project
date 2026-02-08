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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream;

import dk.trustworks.essentials.components.eventsourced.aggregates.decider.Decider;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderCommandHandlerAdapter;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.shared.FailFast;

import java.util.*;

/**
 * <b>NOTE: API is experimental and subject to change!</b>
 * <p>
 * A simplified event sourcing decider interface that focuses on processing commands
 * against event streams to produce new events.
 * <p>
 * This interface provides a more streamlined approach to event sourcing compared to
 * the comprehensive {@link Decider} interface.
 * <p>
 * The {@link EventStreamDecider} operates directly on event streams rather than managing explicit state,
 * making it more functional and easier to reason about.<br>
 * Note: If you need more advanced state management then you can combine the {@link EventStreamDecider} with the {@link EventStreamEvolver}
 * <p>
 * The {@link EventStreamDecider} pattern is inspired by the functional event sourcing approach
 * where commands are processed against an ordered list of events to determine what
 * new events should be produced.
 * 
 * <h3>Event Modeling and Slicing Implementation</h3>
 * <p>
 * The {@code EventStreamDecider} pattern is perfect for <strong>Event Modeling</strong> and its <strong>slicing implementation approach</strong>.
 * This pattern naturally supports the <strong>Open/Closed Principle</strong> by ensuring that <strong>only events are shared across slices</strong> -
 * each slice can independently evolve its command handling logic without affecting other slices, as long as the event contracts remain stable.
 * <p>
 * <strong>What makes this pattern ideal for slicing:</strong>
 * <ul>
 *   <li><strong>Localized Changes:</strong> Modifications are typically confined to a single slice at a time.
 *       When you need to add new behavior or modify existing business rules, you create a new {@code EventStreamDecider} implementation or modify an existing one without touching other slices.</li>
 *   <li><strong>Independent Evolution:</strong> Each command slice (represented by an {@code EventStreamDecider}) can evolve independently.
 *       A slice that handles order creation doesn't need to know about order shipping logic - they only share the events that represent state changes.</li>
 *   <li><strong>Contract Stability:</strong> The event contracts act as the stable interface between slices.
 *       As long as events maintain backward compatibility, different slices can be developed, deployed, and modified by different teams at different cadences.</li>
 *   <li><strong>Single Responsibility:</strong> Each {@code EventStreamDecider} has a focused responsibility - it handles one specific command type against the event stream. This makes the code easier to understand, test, and maintain.</li>
 * </ul>
 * <p>
 * This approach is particularly powerful for teams adopting Event Modeling where business processes are broken down into slices that can be implemented incrementally.<br>
 * Each command handling slice becomes a self-contained {@code EventStreamDecider} that processes its specific commands while participating in the larger event-driven system through shared events.
 * <p>
 * For developers new to this concept, think of it as microservices within a single aggregate boundary - each slice handles its own concerns but coordinates through events rather than direct calls.
 * 
 * <h3>Key Characteristics:</h3>
 * <ul>
 *   <li><strong>Event-Centric:</strong> Works directly with event streams rather than maintaining state</li>
 *   <li><strong>Functional:</strong> Pure functions that given the same input always produce the same output</li>
 *   <li><strong>Idempotent:</strong> Can safely be called multiple times with the same input</li>
 *   <li><strong>Stateless:</strong> No internal state management - state is derived from events</li>
 *   <li><strong>Simple:</strong> Focused on the core decider pattern without complex infrastructure</li>
 * </ul>
 * 
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * public class CreateOrderDecider implements EventStreamDecider<CreateOrder, OrderEvent> {
 *     @Override
 *     public Optional<OrderEvent> handle(CreateOrder command, List<OrderEvent> events) {
 *         // Check if order already exists by looking at events
 *         boolean orderExists = events.stream()
 *             .anyMatch(event -> event instanceof OrderCreated);
 *         
 *         if (orderExists) {
 *             return Optional.empty(); // Idempotent - order already created
 *         }
 *         
 *         return Optional.of(new OrderCreated(command.orderId(), command.customerId()));
 *     }
 *     
 *     @Override
 *     public boolean canHandle(Class<?> command) {
 *         return CreateOrder.class.isAssignableFrom(command);
 *     }
 * }
 * }</pre>
 * 
 * <h3>Integration with Event Store:</h3>
 * <p>
 * EventStreamDeciders can be integrated with the event store infrastructure using
 * {@link EventStreamDeciderCommandHandlerAdapter}
 * which handles the loading of event streams, command routing, and event persistence.<br>
 * Also see the {@link EventStreamAggregateTypeConfiguration}
 * 
 * <h3>Testing:</h3>
 * <p>
 * {@link EventStreamDecider}'s can be easily tested using the 
 * {@link GivenWhenThenScenario} testing framework which provides a fluent API for behavior-driven testing.
 * 
 * @param <COMMAND> The type of commands this decider can handle
 * @param <EVENT> The type of events this decider can produce
 * 
 * @see EventStreamEvolver
 * @see EventStreamDeciderCommandHandlerAdapter
 * @see EventStreamAggregateTypeConfiguration
 * @see GivenWhenThenScenario
 */
public interface EventStreamDecider<COMMAND, EVENT> {

    /**
     * Handles a command by processing it against the current event stream and
     * optionally producing a new event.
     * <p>
     * This method should be implemented as a pure function that:
     * <ul>
     *   <li>Examines the current event stream to understand the current state</li>
     *   <li>Validates the command against business rules</li>
     *   <li>Returns an event if the command should result in a state change</li>
     *   <li>Returns {@code Optional.empty()} if no state change is needed (idempotent behavior)</li>
     *   <li>Throws an exception if the command is invalid</li>
     * </ul>
     * 
     * <h3>Implementation Guidelines:</h3>
     * <ul>
     *   <li><strong>Pure Function:</strong> Same input should always produce the same output</li>
     *   <li><strong>No Side Effects:</strong> Should not modify external state or perform I/O (use {@link EventProcessor}'s for side effects)</li>
     *   <li><strong>Event Stream Order:</strong> Events are ordered chronologically from oldest to newest</li>
     *   <li><strong>Idempotent:</strong> Handle duplicate commands gracefully</li>
     *   <li><strong>Fail Fast:</strong> Use {@link FailFast#requireNonNull} for validation</li>
     * </ul>
     * 
     * <h3>Example Implementation:</h3>
     * <pre>{@code
     * @Override
     * public Optional<OrderEvent> handle(UpdateOrderStatus command, List<OrderEvent> events) {
     *     requireNonNull(command, "command cannot be null");
     *     requireNonNull(events, "events cannot be null");
     *     
     *     // Build current state from events (e.g., using the EventStreamEvolver)
     *     UpdateOrderStatusState currentState = UpdateOrderStatusState.fromEvents(events);
     *     
     *     // Validate command
     *     if (currentState.status() == command.newStatus()) {
     *         return Optional.empty(); // No change needed
     *     }
     *     
     *     // Business rule validation
     *     if (!currentState.canTransitionTo(command.newStatus())) {
     *         throw new IllegalStateException("Cannot transition from " + 
     *             currentState.status() + " to " + command.newStatus());
     *     }
     *     
     *     // Produce new event
     *     return Optional.of(new OrderStatusChanged(command.orderId(), command.newStatus()));
     * }
     * }</pre>
     * 
     * @param command The command to handle. Must not be null.
     * @param events The current event stream for the aggregate, ordered from oldest to newest. 
     *               Must not be null, but can be empty for new aggregates.
     * @return An optional event to be persisted if the command results in a state change.
     *         Returns {@code Optional.empty()} if no state change is needed.
     * @throws IllegalArgumentException if the command is invalid or cannot be processed
     * @throws IllegalStateException if the command cannot be executed in the current state
     */
    Optional<EVENT> handle(COMMAND command, List<EVENT> events);

    /**
     * Determines whether this decider can handle the given command.
     * <p>
     * This method is used for command routing and decider selection. It should
     * return {@code true} if this decider is capable of processing the given
     * command type.
     * <p>
     * The implementation should be fast and lightweight as it may be called
     * frequently during command routing.
     * 
     * <h3>Implementation Guidelines:</h3>
     * <ul>
     *   <li><strong>Type Check:</strong> Typically implemented as an instanceof check</li>
     *   <li><strong>Fast:</strong> Should be O(1) operation</li>
     *   <li><strong>Null Safe:</strong> Should handle null commands gracefully</li>
     *   <li><strong>Immutable:</strong> Should not modify the command object</li>
     * </ul>
     * 
     * <h3>Example Implementation:</h3>
     * <pre>{@code
     * @Override
     * public boolean canHandle(Class<?> command) {
     *     return CreateOrder.class == command;
     * }
     * }</pre>
     * 
     * @param command The command class to check. May be null.
     * @return {@code true} if this decider can handle the command, {@code false} otherwise.
     */
    boolean canHandle(Class<?> command);
}