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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.slf4j.*;

import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Configurator that registers all {@link EventStreamAggregateTypeConfiguration}'s with the {@link EventStore}
 * and all {@link EventStreamDecider}'s with the {@link CommandBus} wrapped in {@link EventStreamDeciderCommandHandlerAdapter}'s.
 * <p>
 * This configurator automatically handles the event sourcing and {@link EventStreamDecider} configuration by:
 * <ul>
 *   <li>Define your {@link EventStreamAggregateTypeConfiguration} beans</li>
 *   <li>Define your {@link EventStreamDecider} beans (or annotate them with {@code @Component/@Service})</li>
 *   <li>Ensure you have an {@link ConfigurableEventStore} and {@link CommandBus} configured</li>
 *   <li>Create {@link EventStreamDeciderCommandHandlerAdapter} instances for each aggregate type. The {@link EventStreamDeciderCommandHandlerAdapter}
 *   handles all commands supported by the {@link EventStreamDecider} associated with the
 *   given {@link AggregateType} configured through the {@link EventStreamAggregateTypeConfiguration}</li>
 *   <li>Registers the {@link EventStreamDeciderCommandHandlerAdapter}'s with the {@link CommandBus}</li>
 * </ul>
 *
 * <h3>Example Spring Configuration:</h3>
 * <pre>{@code
 * @Configuration
 * public class DomainConfiguration {
 *
 *     @Bean
 *     public EventStreamDeciderAndAggregateTypeConfigurator eventStreamDeciderConfigurator(
 *             ConfigurableEventStore<?> eventStore,
 *             CommandBus commandBus,
 *             List<EventStreamAggregateTypeConfiguration> aggregateTypeConfigurations,
 *             List<EventStreamDecider<?, ?>> deciders) {
 *         return new EventStreamDeciderAndAggregateTypeConfigurator(
 *             eventStore,
 *             commandBus,
 *             aggregateTypeConfigurations,
 *             deciders
 *        );
 *     }
 *
 *     @Bean
 *     public EventStreamAggregateTypeConfiguration orderAggregateConfiguration() {
 *         return new EventStreamAggregateTypeConfiguration(
 *             AggregateType.of("Orders"),
 *             OrderId.class,
 *             AggregateIdSerializer.serializerFor(OrderId.class),
 *             new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
 *             command -> ((OrderCommand) command).orderId(),
 *             event -> ((OrderEvent) event).orderId()
 *         );
 *     }
 *
 *     @Bean // or annotate them with {@code @Component/@Service}
 *     public CreateOrderDecider createOrderDecider() {
 *         return new CreateOrderDecider();
 *     }
 *
 *     @Bean // or annotate them with {@code @Component/@Service}
 *     public ConfirmOrderDecider confirmOrderDecider() {
 *         return new ConfirmOrderDecider();
 *     }
 * }
 * }</pre>
 *
 * <h3>Manual Configuration Example:</h3>
 * <pre>{@code
 * var configurator = new EventStreamDeciderAndAggregateTypeConfigurator(
 *     eventStore,
 *     commandBus,
 *     List.of(orderConfiguration, customerConfiguration),
 *     List.of(
 *         new CreateOrderDecider(),
 *         new ConfirmOrderDecider(),
 *         new ShipOrderDecider(),
 *         new CreateCustomerDecider()
 *     )
 * );
 * }</pre>
 *
 * @see EventStreamDecider
 * @see EventStreamAggregateTypeConfiguration
 * @see EventStreamDeciderCommandHandlerAdapter
 * @see EventStreamDeciderAndAggregateTypeConfigurator
 */
public class EventStreamDeciderAndAggregateTypeConfigurator {

    private static final Logger log = LoggerFactory.getLogger(EventStreamDeciderAndAggregateTypeConfigurator.class);

    private final ConfigurableEventStore<?>                     eventStore;
    private final CommandBus                                    commandBus;
    private final List<EventStreamAggregateTypeConfiguration>   aggregateTypeConfigurations;
    private final List<EventStreamDecider<?, ?>>                deciders;
    private final List<EventStreamDeciderCommandHandlerAdapter> createdAdapters;

    /**
     * Creates a new configurator and immediately performs the configuration.
     * <p>
     * This constructor will:
     * <ol>
     *   <li>Register all aggregate type configurations ({@link EventStreamAggregateTypeConfiguration}) with the event store</li>
     *   <li>Group deciders ({@link EventStreamDecider}) by {@link AggregateType}</li>
     *   <li>Create command handler adapters ({@link EventStreamDeciderCommandHandlerAdapter}) for each {@link AggregateType}</li>
     *   <li>Registers the adapters ({@link EventStreamDeciderCommandHandlerAdapter}) with the {@link CommandBus}</li>
     * </ol>
     *
     * @param eventStore                  The event store that can fetch and persist events. Must not be null.
     * @param commandBus                  The command bus where all deciders will be registered as command handlers. Must not be null.
     * @param aggregateTypeConfigurations The aggregate type configurations to register with the event store. Must not be null.
     * @param deciders                    All the deciders that should be registered as command handlers on the command bus. Must not be null.
     * @throws IllegalArgumentException if any parameter is null
     * @throws IllegalStateException    if a decider cannot be matched to exactly one aggregate type
     */
    public EventStreamDeciderAndAggregateTypeConfigurator(
            ConfigurableEventStore<?> eventStore,
            CommandBus commandBus,
            List<EventStreamAggregateTypeConfiguration> aggregateTypeConfigurations,
            List<EventStreamDecider<?, ?>> deciders) {

        this.eventStore = requireNonNull(eventStore, "eventStore cannot be null");
        this.commandBus = requireNonNull(commandBus, "commandBus cannot be null");
        this.aggregateTypeConfigurations = requireNonNull(aggregateTypeConfigurations, "aggregateTypeConfigurations cannot be null");
        this.deciders = requireNonNull(deciders, "deciders cannot be null");
        this.createdAdapters = new ArrayList<>();

        log.info("Starting EventStreamDecider and AggregateType configuration with {} aggregate types and {} deciders",
                 aggregateTypeConfigurations.size(), deciders.size());

        addAggregateTypeConfigurationsToTheEventStore();
        registerDecidersAsCommandHandlersOnTheCommandBus();

        log.info("Completed EventStreamDecider and AggregateType configuration successfully");
    }

    /**
     * Registers all aggregate type configurations with the event store.
     */
    private void addAggregateTypeConfigurationsToTheEventStore() {
        log.info("Registering {} EventStreamAggregateTypeConfiguration(s) with the EventStore",
                 aggregateTypeConfigurations.size());

        for (var config : aggregateTypeConfigurations) {
            log.info("\n" +
                             "============================================================================================================================================================================================\n" +
                             "Registering EventStreamAggregateTypeConfiguration for '{}' with aggregateType '{}' to the EventStore\n" +
                             "============================================================================================================================================================================================",
                     config.aggregateType(), config.aggregateIdType().getSimpleName());

            eventStore.addAggregateEventStreamConfiguration(config.aggregateType(),
                                                            config.aggregateIdSerializer());

            log.info("============================================================================================================================================================================================");
        }
    }

    /**
     * Groups deciders by aggregate type and registers them as command handlers on the command bus.
     */
    private void registerDecidersAsCommandHandlersOnTheCommandBus() {
        log.info("\n" +
                         "============================================================================================================================================================================================\n" +
                         "Registering {} EventStreamDecider(s) as CommandHandler(s) on the {}\n" +
                         "{}\n" +
                         "============================================================================================================================================================================================",
                 deciders.size(),
                 commandBus.getClass().getSimpleName(),
                 deciders.stream().map(d -> d.getClass().getSimpleName()).collect(Collectors.joining(", ")));

        // Group deciders by aggregate type configuration
        var decidersByAggregateType = groupDecidersByAggregateType();

        // Create and register command handler adapters for each aggregate type
        for (var entry : decidersByAggregateType.entrySet()) {
            var configuration = entry.getKey();
            var typeDeciders  = entry.getValue();

            log.info("Creating EventStreamDeciderCommandHandlerAdapter for aggregate type '{}' with {} decider(s): {}",
                     configuration.aggregateType(),
                     typeDeciders.size(),
                     typeDeciders.stream().map(d -> d.getClass().getSimpleName()).collect(Collectors.joining(", ")));

            var adapter = new EventStreamDeciderCommandHandlerAdapter(eventStore, configuration, typeDeciders);
            createdAdapters.add(adapter);
            commandBus.addCommandHandler(adapter);

            log.info("Successfully registered EventStreamDeciderCommandHandlerAdapter for aggregate type '{}'",
                     configuration.aggregateType());
        }

        log.info("============================================================================================================================================================================================");
    }

    /**
     * Groups deciders by their matching aggregate type configuration.
     *
     * @return A map of aggregate type configurations to their associated deciders
     * @throws IllegalStateException if a decider cannot be matched to exactly one aggregate type
     */
    private Map<EventStreamAggregateTypeConfiguration, List<EventStreamDecider<?, ?>>> groupDecidersByAggregateType() {
        var decidersByAggregateType = new HashMap<EventStreamAggregateTypeConfiguration, List<EventStreamDecider<?, ?>>>();

        for (var decider : deciders) {
            var matchingAggregateTypes = aggregateTypeConfigurations.stream()
                                                                    .filter(config -> config.deciderSupportsAggregateTypeChecker().supports(decider))
                                                                    .toList();

            if (matchingAggregateTypes.isEmpty()) {
                throw new IllegalStateException(
                        msg("Couldn't resolve which AggregateType that EventStreamDecider '{}' works with. " +
                                              "Make sure the decider's canHandle method or the aggregate type checker is properly configured.",
                                      decider.getClass().getName()));
            }

            if (matchingAggregateTypes.size() > 1) {
                throw new IllegalStateException(
                        msg("Resolved {} AggregateType(s) that EventStreamDecider '{}' works with: {}. " +
                                              "Each decider must work with exactly one aggregate type.",
                                      matchingAggregateTypes.size(),
                                      decider.getClass().getName(),
                                      matchingAggregateTypes.stream()
                                                            .map(config -> config.aggregateType().toString())
                                                            .collect(Collectors.joining(", "))));
            }

            var aggregateTypeConfig = matchingAggregateTypes.get(0);
            decidersByAggregateType.computeIfAbsent(aggregateTypeConfig, k -> new ArrayList<>()).add(decider);

            log.debug("Mapped EventStreamDecider '{}' to aggregate type '{}'",
                      decider.getClass().getSimpleName(), aggregateTypeConfig.aggregateType());
        }

        return decidersByAggregateType;
    }

    /**
     * Returns the event store used by this configurator.
     *
     * @return The event store
     */
    public ConfigurableEventStore<?> getEventStore() {
        return eventStore;
    }

    /**
     * Returns the command bus used by this configurator.
     *
     * @return The command bus
     */
    public CommandBus getCommandBus() {
        return commandBus;
    }

    /**
     * Returns the aggregate type configurations registered with this configurator.
     *
     * @return The aggregate type configurations
     */
    public List<EventStreamAggregateTypeConfiguration> getAggregateTypeConfigurations() {
        return List.copyOf(aggregateTypeConfigurations);
    }

    /**
     * Returns the deciders registered with this configurator.
     *
     * @return The deciders
     */
    public List<EventStreamDecider<?, ?>> getDeciders() {
        return List.copyOf(deciders);
    }

    /**
     * Returns the command handler adapters created by this configurator.
     * <p>
     * This can be useful for testing or for accessing the adapters directly.
     *
     * @return The created adapters
     */
    public List<EventStreamDeciderCommandHandlerAdapter> getCreatedAdapters() {
        return List.copyOf(createdAdapters);
    }
}