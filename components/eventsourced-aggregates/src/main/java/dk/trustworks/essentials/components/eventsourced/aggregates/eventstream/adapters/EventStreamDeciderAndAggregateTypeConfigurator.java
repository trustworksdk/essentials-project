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
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
import dk.trustworks.essentials.reactive.command.*;
import org.slf4j.*;

import java.lang.reflect.*;
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
    /**
     * The adapters every configurator registered, per {@link CommandBus} (by identity, weakly held) - so a configurator
     * can tell that a command type it registers is already handled by an adapter another configurator registered. The
     * {@link CommandBus} API cannot list its handlers
     */
    private static final Map<CommandBus, List<EventStreamDeciderCommandHandlerAdapter>> ADAPTERS_REGISTERED_PER_COMMAND_BUS =
            Collections.synchronizedMap(new WeakHashMap<>());

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

        // Create a command handler adapter for each aggregate type
        var adapters = new ArrayList<EventStreamDeciderCommandHandlerAdapter>();
        for (var entry : decidersByAggregateType.entrySet()) {
            var configuration = entry.getKey();
            var typeDeciders  = entry.getValue();

            log.info("Creating EventStreamDeciderCommandHandlerAdapter for aggregate type '{}' with {} decider(s): {}",
                     configuration.aggregateType(),
                     typeDeciders.size(),
                     typeDeciders.stream().map(d -> d.getClass().getSimpleName()).collect(Collectors.joining(", ")));

            adapters.add(new EventStreamDeciderCommandHandlerAdapter(eventStore, configuration, typeDeciders));
        }

        // Before registering: a command type handled twice otherwise surfaces only at its first send
        warnAboutCommandTypesHandledMoreThanOnce(adapters);
        // Likewise: a permitted command of a sealed command type that no decider handles fails only at its first send
        warnAboutCommandsOfSealedCommandTypesThatNoDeciderHandles(adapters);

        // Register the command handler adapters
        for (var adapter : adapters) {
            createdAdapters.add(adapter);
            commandBus.addCommandHandler(adapter);
            ADAPTERS_REGISTERED_PER_COMMAND_BUS.computeIfAbsent(commandBus, bus -> new ArrayList<>()).add(adapter);

            log.info("Successfully registered EventStreamDeciderCommandHandlerAdapter for aggregate type '{}'",
                     adapter.getConfiguration().aggregateType());
        }

        log.info("============================================================================================================================================================================================");
    }

    /**
     * WARN for every command type one of the new adapters' deciders declares that more than one command handler would
     * handle: two adapters (of this configurator, or of another configurator on the same {@link CommandBus}, still
     * registered there), or two deciders within one adapter. Every send of such a command fails - with a
     * {@link MultipleCommandHandlersFoundException} from the {@link CommandBus}, or the adapter's "Multiple deciders found".
     * <p>
     * A WARN, not a failure: an overlap that is never sent works today, and rejecting it at startup would break such a
     * setup in a patch release.
     * <p>
     * The command types checked are those a decider declares as its {@code COMMAND} type argument
     * ({@code implements EventStreamDecider<CreateOrder, OrderEvent>}), or - for a sealed one - its concrete permitted
     * subtypes, as far as the decider {@link EventStreamDecider#canHandle(Class) can handle} them. A decider whose type
     * argument cannot be resolved (a lambda, a generic base class) is not checked; neither are command handlers other
     * than {@link EventStreamDeciderCommandHandlerAdapter}s, which the {@link CommandBus} API cannot list.
     *
     * @param newAdapters the adapters about to be registered
     */
    private void warnAboutCommandTypesHandledMoreThanOnce(List<EventStreamDeciderCommandHandlerAdapter> newAdapters) {
        try {
            var allAdapters = adaptersOnTheCommandBusIncluding(newAdapters);

            var commandTypesChecked = new HashSet<Class<?>>();
            for (var adapter : newAdapters) {
                for (var decider : adapter.getDeciders()) {
                    for (var commandType : declaredCommandTypes(decider)) {
                        if (commandTypesChecked.add(commandType)) {
                            warnIfHandledMoreThanOnce(commandType, adapter, allAdapters);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // A diagnostic must never fail the configuration
            log.debug("Could not check the command types of the EventStreamDeciders for duplicate registrations", e);
        }
    }

    /**
     * @return the {@link EventStreamDeciderCommandHandlerAdapter}s any configurator registered on this configurator's
     * {@link CommandBus} that are still registered there, plus the <code>newAdapters</code>
     */
    private List<CommandHandler> adaptersOnTheCommandBusIncluding(List<EventStreamDeciderCommandHandlerAdapter> newAdapters) {
        var allAdapters = new ArrayList<CommandHandler>();
        synchronized (ADAPTERS_REGISTERED_PER_COMMAND_BUS) {
            var earlier = ADAPTERS_REGISTERED_PER_COMMAND_BUS.get(commandBus);
            if (earlier != null) {
                // An adapter removed from the bus since no longer handles anything
                earlier.removeIf(adapter -> !commandBus.hasCommandHandler(adapter));
                allAdapters.addAll(earlier);
            }
        }
        allAdapters.addAll(newAdapters);
        return allAdapters;
    }

    /**
     * WARN, per sealed command type, for the concrete commands it permits that no {@link EventStreamDecider} on the
     * {@link CommandBus} handles (CB-4). Sending one fails with a {@link NoCommandHandlerFoundException} - at its first
     * send, which may be long after startup.
     * <p>
     * The sealed command types checked are the command root type of an {@link EventStreamAggregateTypeConfiguration}
     * whose checker is a {@link EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType}
     * (e.g. {@code sealed interface OrderCommand}), and a decider's own {@code COMMAND} type argument when it is sealed.
     * Their permitted subtypes are walked recursively ({@link Class#getPermittedSubclasses()}); a command counts as handled
     * when one of the {@link EventStreamDeciderCommandHandlerAdapter}s registered on the {@link CommandBus} - by this or
     * an earlier configurator - {@link CommandHandler#canHandle(Class) can handle} it.
     * <p>
     * A WARN, not a failure: the {@link CommandBus} API cannot list other command handlers, so a command handled by e.g.
     * an {@code @CmdHandler} method elsewhere is reported too, as is one handled by a configurator created later.
     *
     * @param newAdapters the adapters about to be registered
     */
    private void warnAboutCommandsOfSealedCommandTypesThatNoDeciderHandles(List<EventStreamDeciderCommandHandlerAdapter> newAdapters) {
        try {
            var sealedCommandTypes = new LinkedHashMap<Class<?>, String>();
            for (var adapter : newAdapters) {
                if (adapter.getConfiguration().deciderSupportsAggregateTypeChecker() instanceof EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType checker &&
                        checker.getCommandType().isSealed()) {
                    sealedCommandTypes.putIfAbsent(checker.getCommandType(), "the command type of AggregateType '" + adapter.getConfiguration().aggregateType() + "'");
                }
                for (var decider : adapter.getDeciders()) {
                    resolveCommandTypeArgument(decider.getClass()).filter(Class::isSealed)
                                                                  .ifPresent(commandType -> sealedCommandTypes.putIfAbsent(commandType, "the command type of decider '" + decider.getClass().getName() + "'"));
                }
            }
            if (sealedCommandTypes.isEmpty()) {
                return;
            }

            var allAdapters = adaptersOnTheCommandBusIncluding(newAdapters);
            sealedCommandTypes.forEach((sealedCommandType, declaredAs) -> {
                var permittedCommands = new LinkedHashSet<Class<?>>();
                collectConcreteTypes(sealedCommandType, permittedCommands, new HashSet<>());
                var unhandled = permittedCommands.stream()
                                                 .filter(commandType -> allAdapters.stream().noneMatch(adapter -> adapter.canHandle(commandType)))
                                                 .map(Class::getName)
                                                 .toList();
                if (!unhandled.isEmpty()) {
                    log.warn("Sealed command type '{}' ({}) permits {} command type(s) that no EventStreamDecider handles: {}. The {} fails every send of them with a {} - " +
                                     "add an EventStreamDecider for each, unless another command handler on the CommandBus handles it (only EventStreamDecider adapters can be checked)",
                             sealedCommandType.getName(),
                             declaredAs,
                             unhandled.size(),
                             unhandled,
                             commandBus.getClass().getSimpleName(),
                             NoCommandHandlerFoundException.class.getSimpleName());
                    EssentialsStartupReport.addToCurrent(EssentialsStartupReport.Finding.warn(
                            "sealed command type '" + sealedCommandType.getName() + "'",
                            "permits " + unhandled.size() + " command type(s) that no EventStreamDecider handles (" + declaredAs + "): " + unhandled
                                    + " - every send of them fails with a " + NoCommandHandlerFoundException.class.getSimpleName(),
                            "Add an EventStreamDecider for each, unless another command handler on the CommandBus handles it (only EventStreamDecider adapters can be checked)"));
                } else {
                    log.debug("Every command type that sealed command type '{}' permits is handled by an EventStreamDecider", sealedCommandType.getName());
                }
            });
        } catch (RuntimeException e) {
            // A diagnostic must never fail the configuration
            log.debug("Could not check the sealed command types of the EventStreamDeciders for commands no decider handles", e);
        }
    }

    private void warnIfHandledMoreThanOnce(Class<?> commandType, EventStreamDeciderCommandHandlerAdapter adapter, List<CommandHandler> allAdapters) {
        var handlers = allAdapters.stream().filter(handler -> handler.canHandle(commandType)).toList();
        if (handlers.size() > 1) {
            log.warn("Command type '{}' would be handled by {} command handlers: {}. The {} fails every send of it with a {} - " +
                             "register each command type with exactly one EventStreamDecider (check the deciders' canHandle and the " +
                             "EventStreamDeciderAndAggregateTypeConfigurators sharing this CommandBus)",
                     commandType.getName(),
                     handlers.size(),
                     handlers,
                     commandBus.getClass().getSimpleName(),
                     MultipleCommandHandlersFoundException.class.getSimpleName());
            EssentialsStartupReport.addToCurrent(EssentialsStartupReport.Finding.warn(
                    "command type '" + commandType.getName() + "'",
                    "would be handled by " + handlers.size() + " command handlers: " + handlers + " - every send of it fails with a "
                            + MultipleCommandHandlersFoundException.class.getSimpleName(),
                    "Register each command type with exactly one EventStreamDecider (check the deciders' canHandle and the EventStreamDeciderAndAggregateTypeConfigurators sharing this CommandBus)"));
            return;
        }
        var deciders = adapter.getDeciders().stream().filter(candidate -> candidate.canHandle(commandType)).toList();
        if (deciders.size() > 1) {
            log.warn("Command type '{}' would be handled by {} deciders of {}: {}. Every send of it fails with 'Multiple deciders found' - " +
                             "register each command type with exactly one EventStreamDecider",
                     commandType.getName(),
                     deciders.size(),
                     adapter,
                     deciders.stream().map(candidate -> candidate.getClass().getName()).toList());
            EssentialsStartupReport.addToCurrent(EssentialsStartupReport.Finding.warn(
                    "command type '" + commandType.getName() + "'",
                    "would be handled by " + deciders.size() + " deciders of " + adapter + ": " + deciders.stream().map(candidate -> candidate.getClass().getName()).toList()
                            + " - every send of it fails with 'Multiple deciders found'",
                    "Register each command type with exactly one EventStreamDecider"));
        }
    }

    /**
     * @return the concrete command types the decider declares and {@link EventStreamDecider#canHandle(Class) can handle} -
     * see {@link #warnAboutCommandTypesHandledMoreThanOnce(List)}; empty when its {@code COMMAND} type argument cannot be resolved
     */
    static Set<Class<?>> declaredCommandTypes(EventStreamDecider<?, ?> decider) {
        var commandTypes = new LinkedHashSet<Class<?>>();
        resolveCommandTypeArgument(decider.getClass()).ifPresent(declared -> collectConcreteTypes(declared, commandTypes, new HashSet<>()));
        commandTypes.removeIf(commandType -> !decider.canHandle(commandType));
        return commandTypes;
    }

    private static Optional<Class<?>> resolveCommandTypeArgument(Class<?> deciderType) {
        for (Class<?> type = deciderType; type != null && type != Object.class; type = type.getSuperclass()) {
            for (var genericInterface : type.getGenericInterfaces()) {
                if (genericInterface instanceof ParameterizedType parameterized && parameterized.getRawType() == EventStreamDecider.class) {
                    var commandTypeArgument = parameterized.getActualTypeArguments()[0];
                    if (commandTypeArgument instanceof Class<?> commandType) {
                        return Optional.of(commandType);
                    }
                    if (commandTypeArgument instanceof ParameterizedType parameterizedCommandType && parameterizedCommandType.getRawType() instanceof Class<?> commandType) {
                        return Optional.of(commandType);
                    }
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static void collectConcreteTypes(Class<?> type, Set<Class<?>> concreteTypes, Set<Class<?>> visited) {
        if (type == Object.class || !visited.add(type)) {
            return;
        }
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            concreteTypes.add(type);
        }
        if (type.isSealed()) {
            for (var permittedSubclass : type.getPermittedSubclasses()) {
                collectConcreteTypes(permittedSubclass, concreteTypes, visited);
            }
        }
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