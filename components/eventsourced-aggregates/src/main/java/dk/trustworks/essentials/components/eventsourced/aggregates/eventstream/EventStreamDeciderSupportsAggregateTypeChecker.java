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

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderCommandTypes;
import org.slf4j.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Functional interface for determining if an {@link EventStreamDecider} supports a specific aggregate type.
 * <p>
 * This interface is used during command routing to determine which decider should handle
 * a particular command based on the aggregate type configuration. It provides a way to
 * associate deciders with specific aggregate types in a flexible manner.
 * 
 * <h3>Implementation Strategies:</h3>
 * <ul>
 *   <li><strong>Command Type Based:</strong> Check if decider handles commands of specific types - see {@link HandlesCommandsThatInheritFromCommandType}</li>
 *   <li><strong>Interface Based:</strong> Check if decider implements specific interfaces</li>
 *   <li><strong>Annotation Based:</strong> Check for specific annotations on decider classes</li>
 *   <li><strong>Convention Based:</strong> Use naming conventions to determine support</li>
 * </ul>
 * 
 * @see EventStreamAggregateTypeConfiguration
 * @see HandlesCommandsThatInheritFromCommandType
 */
@FunctionalInterface
public interface EventStreamDeciderSupportsAggregateTypeChecker {

    /**
     * Determines if the given decider supports the aggregate type this checker is configured for.
     * 
     * @param decider The decider to check. Must not be null.
     * @return {@code true} if the decider supports the aggregate type, {@code false} otherwise
     * @throws IllegalArgumentException if decider is null
     */
    boolean supports(EventStreamDecider<?, ?> decider);

    /**
     * Implementation that determines support based on whether the decider can handle
     * commands that inherit from a specific command type.
     * <p>
     * This is useful when you have a base command type (e.g., OrderCommand) and
     * want to route all commands that inherit from it to deciders that can handle
     * any command of that type hierarchy.
     * 
     * <h3>Example Usage:</h3>
     * <pre>{@code
     * // Base command type
     * public interface OrderCommand {
     *     OrderId orderId();
     * }
     * 
     * // Specific command types
     * public record CreateOrder(OrderId orderId, CustomerId customerId) implements OrderCommand {}
     * public record UpdateOrder(OrderId orderId, String details) implements OrderCommand {}
     * 
     * // Configuration
     * var checker = new HandlesCommandsThatInheritFromCommandType(OrderCommand.class);
     * 
     * // Usage
     * var decider = new CreateOrderDecider(); // handles CreateOrder commands
     * boolean supports = checker.supports(decider); // true if decider.canHandle(someOrderCommand)
     * }</pre>
     */
    final class HandlesCommandsThatInheritFromCommandType implements EventStreamDeciderSupportsAggregateTypeChecker {
        private static final Logger log = LoggerFactory.getLogger(HandlesCommandsThatInheritFromCommandType.class);
        private final Class<?> commandType;

        /**
         * Creates a new checker for the specified command type.
         * 
         * @param commandType The base command type to check for. Must not be null.
         * @throws IllegalArgumentException if commandType is null
         */
        public HandlesCommandsThatInheritFromCommandType(Class<?> commandType) {
            this.commandType = requireNonNull(commandType, "commandType cannot be null");
        }

        /**
         * Determines if the decider supports the aggregate type by checking if the {@code COMMAND} type argument the
         * decider's class gives {@link EventStreamDecider} inherits from the configured command type.
         * <p>
         * The type argument is found through the decider's superclasses and (sub-)interfaces, so a decider declared as
         * {@code implements EventStreamDecider<CreateOrder, OrderEvent>}, as {@code implements OrderDecider} (where
         * {@code interface OrderDecider extends EventStreamDecider<OrderCommand, OrderEvent>}) or as
         * {@code extends BaseDecider<CreateOrder>} (where {@code abstract class BaseDecider<C> implements EventStreamDecider<C, OrderEvent>})
         * is resolved alike.
         * <p>
         * A decider whose command type cannot be resolved - a class implementing the raw {@link EventStreamDecider}, a
         * JDK dynamic proxy, or a generic class whose {@code COMMAND} type variable no subclass binds - is not supported: this returns {@code false} and logs a WARN, so another
         * aggregate type's checker may still claim it, and a decider no checker claims fails the configuration.
         *
         * @param decider The decider to check. Must not be null.
         * @return {@code true} if the decider's command type is, or inherits from, the configured command type
         * @throws IllegalArgumentException if decider is null
         */
        @Override
        public boolean supports(EventStreamDecider<?, ?> decider) {
            requireNonNull(decider, "decider cannot be null");

            var resolvedCommandType = EventStreamDeciderCommandTypes.resolveCommandTypeArgument(decider.getClass());
            if (resolvedCommandType.isEmpty()) {
                log.warn("Couldn't resolve the command type of EventStreamDecider '{}' - so it isn't supported by '{}'. " +
                                 "Declare the decider as a class that gives EventStreamDecider a concrete COMMAND type argument " +
                                 "(directly, through a sub-interface or through a generic base class) - not a raw type, an unbound type variable or a JDK dynamic proxy",
                         decider.getClass().getName(),
                         this);
                return false;
            }
            var commandTypeTheDeciderHandles = resolvedCommandType.get();
            log.debug(
                    "Resolved command type '{}' from '{}' - checking if it inherits from '{}'",
                    commandTypeTheDeciderHandles.getName(),
                    decider.getClass().getName(),
                    commandType.getName()
                     );

            return commandType.isAssignableFrom(commandTypeTheDeciderHandles);
        }

        /**
         * Returns the base command type this checker is configured for.
         * 
         * @return The command type
         */
        public Class<?> getCommandType() {
            return commandType;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            var that = (HandlesCommandsThatInheritFromCommandType) obj;
            return commandType.equals(that.commandType);
        }

        @Override
        public int hashCode() {
            return commandType.hashCode();
        }

        @Override
        public String toString() {
            return "HandlesCommandsThatInheritFromCommandType{" +
                   "commandType=" + commandType +
                   '}';
        }
    }
}