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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.reactive.command.CommandHandler;
import org.slf4j.*;

import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Adapter that integrates {@link EventStreamDecider} implementations with the command handling infrastructure.
 * <p>
 * This adapter bridges the gap between the simplified {@link EventStreamDecider} pattern and the
 * comprehensive event store infrastructure. It handles:
 * <ul>
 *   <li>Loading event streams from the event store</li>
 *   <li>Routing commands to appropriate deciders</li>
 *   <li>Persisting events produced by deciders</li>
 *   <li>Transaction management</li>
 *   <li>Error handling and logging</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * @Component
 * public class OrderCommandHandler extends EventStreamDeciderCommandHandlerAdapter {
 *
 *     public OrderCommandHandler(
 *             ConfigurableEventStore<?> eventStore,
 *             EventStreamAggregateTypeConfiguration configuration,
 *             List<EventStreamDecider<?, ?>> deciders) {
 *         super(eventStore, configuration, deciders);
 *     }
 *
 *     // Commands are automatically routed to appropriate deciders
 *     // No additional @CmdHandler methods needed
 * }
 * }</pre>
 *
 * @see EventStreamDecider
 * @see EventStreamAggregateTypeConfiguration
 * @see EventStreamDeciderAndAggregateTypeConfigurator
 */
public class EventStreamDeciderCommandHandlerAdapter implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(EventStreamDeciderCommandHandlerAdapter.class);

    private final ConfigurableEventStore<?>             eventStore;
    private final EventStreamAggregateTypeConfiguration configuration;
    private final List<EventStreamDecider<?, ?>>        deciders;

    /**
     * Creates a new adapter with the specified configuration and deciders.
     *
     * @param eventStore    The event store to use for loading and persisting events. Must not be null.
     * @param configuration The aggregate type configuration. Must not be null.
     * @param deciders      The list of deciders to use for command handling. Must not be null.
     * @throws IllegalArgumentException if any parameter is null
     */
    public EventStreamDeciderCommandHandlerAdapter(
            ConfigurableEventStore<?> eventStore,
            EventStreamAggregateTypeConfiguration configuration,
            List<EventStreamDecider<?, ?>> deciders) {

        this.eventStore = requireNonNull(eventStore, "eventStore cannot be null");
        this.configuration = requireNonNull(configuration, "configuration cannot be null");
        this.deciders = requireNonNull(deciders, "deciders cannot be null");

        deciders.forEach(decider -> {
            if (!configuration.deciderSupportsAggregateTypeChecker().supports(decider)) {
                throw new IllegalStateException(msg("Decider '{}' isn't supported by the Configuration: {}", decider.getClass().getName(), configuration));
            }
        });


        log.info("Created EventStreamDeciderCommandHandlerAdapter for aggregate type '{}' with {} deciders",
                 configuration.aggregateType(), deciders.size());
    }

    @Override
    public boolean canHandle(Class<?> commandType) {
        List<EventStreamDecider<?, ?>> applicableEventStreamDeciders = findApplicableEventStreamDeciders(commandType);
        return !applicableEventStreamDeciders.isEmpty();
    }

    /**
     * Generic command handler that routes commands to appropriate deciders.
     * <p>
     * This method is automatically called by the command handling infrastructure
     * for any command that matches the aggregate type configuration.
     *
     * @param command The command to handle. Must not be null.
     * @return The result of command processing, if any
     * @throws IllegalArgumentException if command is null or cannot be processed
     * @throws IllegalStateException    if no suitable decider is found or multiple deciders claim support
     */
    @Override
    public Object handle(Object command) {
        requireNonNull(command, "command cannot be null");

        var commandTypeName = command.getClass().getSimpleName();
        log.debug("Handling command: {}", commandTypeName);

        var decider = getEventStreamDecider(command);

        // Extract aggregate ID from command
        var          aggregateId = configuration.commandAggregateIdResolver().apply(command);
        List<Object> events      = new ArrayList<>();
        if (aggregateId != null) {
            // Load current event stream
            events = loadEventStream(aggregateId);
        }

        // Process command with decider
        @SuppressWarnings("unchecked")
        var typedDecider = (EventStreamDecider<Object, Object>) decider;
        var optionalEvent = typedDecider.handle(command, events);

        if (optionalEvent == null || optionalEvent.isEmpty()) {
            log.debug(
                    "[{}:{}] Handling handling command '{}' resulted in no events",
                    configuration.aggregateType(),
                    aggregateId != null ? aggregateId : "?",
                    commandTypeName
                     );
            return null;
        }

        var event = optionalEvent.get();
        if (aggregateId == null) {
            aggregateId = configuration.eventAggregateIdResolver().apply(event);
            log.debug(
                    "[{}:{}] Resolved aggregate id from event '{}' resulting from handling command '{}'",
                    configuration.aggregateType(),
                    aggregateId,
                    event.getClass().getSimpleName(),
                    commandTypeName
                     );
        }

        log.debug(
                "[{}:{}] Handling handling command '{}' resulted in event '{}' that will be persisted in the {}",
                configuration.aggregateType(),
                aggregateId,
                commandTypeName,
                event.getClass().getSimpleName(),
                EventStore.class.getSimpleName()
                 );
        persistEvent(aggregateId, event);
        return event;

    }

    private EventStreamDecider<?, ?> getEventStreamDecider(Object command) {
        var applicableDeciders = findApplicableEventStreamDeciders(command.getClass());

        if (applicableDeciders.isEmpty()) {
            var message = msg("No decider found for command type '{}' and aggregate type '{}'",
                                        command.getClass().getName(), configuration.aggregateType());
            log.error(message);
            throw new IllegalStateException(message);
        }

        if (applicableDeciders.size() > 1) {
            var message = msg("Multiple deciders found for command type '{}' and aggregate type '{}': {}",
                                        command.getClass().getName(),
                                        configuration.aggregateType(),
                                        applicableDeciders.stream()
                                                          .map(d -> d.getClass().getSimpleName())
                                                          .collect(Collectors.joining(", ")));
            log.error(message);
            throw new IllegalStateException(message);
        }

        var decider = applicableDeciders.get(0);
        log.debug("Using decider: {}", decider.getClass().getSimpleName());
        return decider;
    }

    private List<EventStreamDecider<?, ?>> findApplicableEventStreamDeciders(Class<?> commandType) {
        return deciders.stream()
                       .filter(decider -> decider.canHandle(commandType))
                       .toList();
    }

    /**
     * Loads the event stream for the specified aggregate ID and extract event payloads from the persisted event stream.
     *
     * @param aggregateId The aggregate ID to load events for
     * @return The aggregate event stream
     */
    private List<Object> loadEventStream(Object aggregateId) {
        var potentialEventStream = eventStore.fetchStream(configuration.aggregateType(), aggregateId);
        if (potentialEventStream.isPresent()) {
            var deserializedEvents = potentialEventStream.get()
                                                         .eventList()
                                                         .stream()
                                                         .map(persistedEvent -> persistedEvent.event().deserialize())
                                                         .toList();
            log.debug(
                    "[{}:{}] Resolved EventStream with {} event(s)",
                    configuration.aggregateType(),
                    aggregateId,
                    deserializedEvents.size()
                     );
            return deserializedEvents;
        } else {
            log.debug(
                    "[{}:{}] Didn't find an existing EventStream",
                    configuration.aggregateType(),
                    aggregateId
                     );
            return Collections.emptyList();
        }
    }

    /**
     * Persists a new event to the event store.
     *
     * @param aggregateId The aggregate ID to persist the event for
     * @param event       The event to persist
     */
    private void persistEvent(Object aggregateId, Object event) {
        var persistedEventStream = eventStore.appendToStream(
                configuration.aggregateType(),
                aggregateId,
                List.of(event));
        var persistedEvent = persistedEventStream.firstEvent();
        log.debug(
                "[{}:{}] Persisted Event '{}' with Event-Order '{}' and Global-Event-Order '{}'",
                configuration.aggregateType(),
                aggregateId,
                event.getClass().getSimpleName(),
                persistedEvent.eventOrder(),
                persistedEvent.globalEventOrder()
                 );
    }

    /**
     * Returns the aggregate type configuration for this adapter.
     *
     * @return The configuration
     */
    public EventStreamAggregateTypeConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Returns the list of deciders managed by this adapter.
     *
     * @return The deciders
     */
    public List<EventStreamDecider<?, ?>> getDeciders() {
        return Collections.unmodifiableList(deciders);
    }
}