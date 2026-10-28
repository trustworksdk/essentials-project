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
import dk.trustworks.essentials.components.foundation.startup.EssentialsRunReport;
import dk.trustworks.essentials.reactive.command.*;
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

        // Register the command handler adapters - a CommandBus extending AbstractCommandBus WARNs as each one lands
        // if it makes a command type handled by more than one command handler
        for (var adapter : adapters) {
            createdAdapters.add(adapter);
            commandBus.addCommandHandler(adapter);

            log.info("Successfully registered EventStreamDeciderCommandHandlerAdapter for aggregate type '{}'",
                     adapter.getConfiguration().aggregateType());
        }

        // After registering, so the CommandBus lists the new adapters next to every command handler registered before them.
        // An overlap or a gap otherwise surfaces only at the first send of the command, which may be long after startup
        var commandTypesHandledMoreThanOnce = reportCommandTypesHandledByMoreThanOneCommandHandler(adapters);
        warnAboutCommandTypesHandledByMoreThanOneDeciderOfAnAdapter(adapters, commandTypesHandledMoreThanOnce);
        warnAboutCommandsOfSealedCommandTypesThatNoDeciderHandles(adapters);

        log.info("============================================================================================================================================================================================");
    }

    /**
     * Report every command type that one of the new adapters and another command handler registered on the
     * {@link CommandBus} can both handle - another adapter (of this or an earlier configurator), an
     * {@link AnnotatedCommandHandler} or any other {@link CommandHandler} registered before. Every send of such a command
     * fails with a {@link MultipleCommandHandlersFoundException}.
     * <p>
     * The overlaps come from {@link CommandBus#findCommandTypesHandledByMoreThanOneCommandHandler(CommandHandler)}, so the
     * command types checked are those the handlers declare in {@link CommandHandler#handledCommandTypes()} - for an
     * adapter, its deciders' {@code COMMAND} type arguments ({@link EventStreamDeciderCommandHandlerAdapter#handledCommandTypes()}).
     * Each one is added to the {@link EssentialsRunReport} bound to the thread. It is logged at WARN here only when the
     * {@link CommandBus} is not an {@link AbstractCommandBus}, which has already logged it when the adapter was added.
     * A command handler registered after this configurator is checked - and warned about - by the {@link CommandBus} alone.
     * <p>
     * A WARN, not a failure: an overlap that is never sent works today, and rejecting it at startup would break such a
     * setup in a patch release. A {@link CommandBus} that does not list its handlers ({@link CommandBus#getCommandHandlers()}'s
     * default) cannot be checked; that is logged at INFO.
     *
     * @param newAdapters the adapters just registered
     * @return the command types found handled by more than one command handler
     */
    private Set<Class<?>> reportCommandTypesHandledByMoreThanOneCommandHandler(List<EventStreamDeciderCommandHandlerAdapter> newAdapters) {
        var commandTypesHandledMoreThanOnce = new LinkedHashMap<Class<?>, List<CommandHandler>>();
        try {
            if (!commandBus.getCommandHandlers().containsAll(newAdapters)) {
                log.info("The {} does not list its command handlers, so the command types of the EventStreamDeciders cannot be checked for being handled by more than one command handler",
                         commandBus.getClass().getSimpleName());
                return commandTypesHandledMoreThanOnce.keySet();
            }
            for (var adapter : newAdapters) {
                commandBus.findCommandTypesHandledByMoreThanOneCommandHandler(adapter).forEach(commandTypesHandledMoreThanOnce::putIfAbsent);
            }
            var commandBusHasWarned = commandBus instanceof AbstractCommandBus;
            commandTypesHandledMoreThanOnce.forEach((commandType, handlers) -> {
                if (!commandBusHasWarned) {
                    log.warn("Command type '{}' would be handled by {} command handlers: {}. The {} fails every send of it with a {} - " +
                                     "register each command type with exactly one command handler (check the EventStreamDeciders' canHandle, the " +
                                     "@CmdHandler methods and the EventStreamDeciderAndAggregateTypeConfigurators sharing this CommandBus)",
                             commandType.getName(),
                             handlers.size(),
                             handlers,
                             commandBus.getClass().getSimpleName(),
                             MultipleCommandHandlersFoundException.class.getSimpleName());
                }
                EssentialsRunReport.addToCurrent(EssentialsRunReport.Finding.warn(
                        "command type '" + commandType.getName() + "'",
                        "would be handled by " + handlers.size() + " command handlers: " + handlers + " - every send of it fails with a "
                                + MultipleCommandHandlersFoundException.class.getSimpleName(),
                        "Register each command type with exactly one command handler (check the EventStreamDeciders' canHandle, the @CmdHandler methods " +
                                "and the EventStreamDeciderAndAggregateTypeConfigurators sharing this CommandBus)"));
            });
        } catch (RuntimeException e) {
            // A diagnostic must never fail the configuration - but its own failure is logged at ERROR, as DEBUG is
            // usually filtered out and would hide that the check did not run
            log.error("Could not check the command types of the EventStreamDeciders for duplicate registrations", e);
        }
        return commandTypesHandledMoreThanOnce.keySet();
    }

    /**
     * WARN for every command type that two deciders inside one of the new adapters can handle - the {@link CommandBus}
     * sees one command handler, the adapter, so this overlap is invisible to it. Every send of such a command fails with
     * the adapter's "Multiple deciders found". The command types checked are the adapter's
     * {@link EventStreamDeciderCommandHandlerAdapter#handledCommandTypes()}, less those already reported as handled by
     * more than one command handler.
     * <p>
     * A WARN, not a failure - see {@link #reportCommandTypesHandledByMoreThanOneCommandHandler(List)}.
     *
     * @param newAdapters                 the adapters just registered
     * @param commandTypesReportedAlready the command types already reported as handled by more than one command handler
     */
    private void warnAboutCommandTypesHandledByMoreThanOneDeciderOfAnAdapter(List<EventStreamDeciderCommandHandlerAdapter> newAdapters,
                                                                             Set<Class<?>> commandTypesReportedAlready) {
        try {
            for (var adapter : newAdapters) {
                for (var commandType : adapter.handledCommandTypes()) {
                    if (commandTypesReportedAlready.contains(commandType)) {
                        continue;
                    }
                    var deciders = adapter.getDeciders().stream().filter(candidate -> candidate.canHandle(commandType)).toList();
                    if (deciders.size() > 1) {
                        log.warn("Command type '{}' would be handled by {} deciders of {}: {}. Every send of it fails with 'Multiple deciders found' - " +
                                         "register each command type with exactly one EventStreamDecider",
                                 commandType.getName(),
                                 deciders.size(),
                                 adapter,
                                 deciders.stream().map(candidate -> candidate.getClass().getName()).toList());
                        EssentialsRunReport.addToCurrent(EssentialsRunReport.Finding.warn(
                                "command type '" + commandType.getName() + "'",
                                "would be handled by " + deciders.size() + " deciders of " + adapter + ": " + deciders.stream().map(candidate -> candidate.getClass().getName()).toList()
                                        + " - every send of it fails with 'Multiple deciders found'",
                                "Register each command type with exactly one EventStreamDecider"));
                    }
                }
            }
        } catch (RuntimeException e) {
            // A diagnostic must never fail the configuration - but its own failure is logged at ERROR (see above)
            log.error("Could not check the command types of the EventStreamDeciders for deciders that handle the same command type", e);
        }
    }

    /**
     * WARN, per sealed command type, for the concrete commands it permits that no command handler on the
     * {@link CommandBus} handles (CB-4). Sending one fails with a {@link NoCommandHandlerFoundException} - at its first
     * send, which may be long after startup.
     * <p>
     * The sealed command types checked are the command root type of an {@link EventStreamAggregateTypeConfiguration}
     * whose checker is a {@link EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType}
     * (e.g. {@code sealed interface OrderCommand}), and a decider's own {@code COMMAND} type argument when it is sealed.
     * Their permitted subtypes are walked recursively ({@link Class#getPermittedSubclasses()}); a command counts as handled
     * when a command handler registered on the {@link CommandBus} - an adapter of this or an earlier configurator, an
     * {@link AnnotatedCommandHandler}, any other {@link CommandHandler} - {@link CommandHandler#canHandle(Class) can handle} it.
     * <p>
     * A WARN, not a failure: a command handler registered after this configurator may still handle it. Nor can it see
     * other command handlers on a {@link CommandBus} that does not list them ({@link CommandBus#getCommandHandlers()}'s default).
     *
     * @param newAdapters the adapters just registered
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
                    EventStreamDeciderCommandTypes.resolveCommandTypeArgument(decider.getClass())
                                                  .filter(Class::isSealed)
                                                  .ifPresent(commandType -> sealedCommandTypes.putIfAbsent(commandType, "the command type of decider '" + decider.getClass().getName() + "'"));
                }
            }
            if (sealedCommandTypes.isEmpty()) {
                return;
            }

            // The new adapters too, should the CommandBus not list its handlers
            var allCommandHandlers = new LinkedHashSet<CommandHandler>(commandBus.getCommandHandlers());
            allCommandHandlers.addAll(newAdapters);
            sealedCommandTypes.forEach((sealedCommandType, declaredAs) -> {
                var permittedCommands = new LinkedHashSet<Class<?>>();
                EventStreamDeciderCommandTypes.collectConcreteTypes(sealedCommandType, permittedCommands, new HashSet<>());
                var unhandled = permittedCommands.stream()
                                                 .filter(commandType -> allCommandHandlers.stream().noneMatch(commandHandler -> commandHandler.canHandle(commandType)))
                                                 .map(Class::getName)
                                                 .toList();
                if (!unhandled.isEmpty()) {
                    log.warn("Sealed command type '{}' ({}) permits {} command type(s) that no command handler on the {} handles: {}. The {} fails every send of them with a {} - " +
                                     "add an EventStreamDecider for each, unless a command handler registered later handles it",
                             sealedCommandType.getName(),
                             declaredAs,
                             unhandled.size(),
                             commandBus.getClass().getSimpleName(),
                             unhandled,
                             commandBus.getClass().getSimpleName(),
                             NoCommandHandlerFoundException.class.getSimpleName());
                    EssentialsRunReport.addToCurrent(EssentialsRunReport.Finding.warn(
                            "sealed command type '" + sealedCommandType.getName() + "'",
                            "permits " + unhandled.size() + " command type(s) that no command handler on the CommandBus handles (" + declaredAs + "): " + unhandled
                                    + " - every send of them fails with a " + NoCommandHandlerFoundException.class.getSimpleName(),
                            "Add an EventStreamDecider for each, unless a command handler registered later handles it"));
                } else {
                    log.debug("Every command type that sealed command type '{}' permits is handled by a command handler", sealedCommandType.getName());
                }
            });
        } catch (RuntimeException e) {
            // A diagnostic must never fail the configuration - but its own failure is logged at ERROR (see above)
            log.error("Could not check the sealed command types of the EventStreamDeciders for commands no decider handles", e);
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
                // The checkers don't WARN about a decider whose command type they cannot resolve, since they are asked once per aggregate type
                // and per check - so the guidance for that case is given here, once
                var unresolvableCommandTypeHint = EventStreamDeciderCommandTypes.resolveCommandTypeArgument(decider.getClass()).isEmpty()
                                                  && anyCheckerResolvesCommandTypes()
                                                  ? " The decider's COMMAND type argument couldn't be resolved, so a HandlesCommandsThatInheritFromCommandType checker can't claim it: " +
                                                    "declare the decider as a class that gives EventStreamDecider a concrete COMMAND type argument " +
                                                    "(directly, through a sub-interface or through a generic base class) - not a raw type, an unbound type variable or a JDK dynamic proxy."
                                                  : "";
                throw new IllegalStateException(
                        msg("Couldn't resolve which AggregateType that EventStreamDecider '{}' works with. " +
                                              "Make sure the decider's canHandle method or the aggregate type checker is properly configured.{}",
                                      decider.getClass().getName(),
                                      unresolvableCommandTypeHint));
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
     * Whether any aggregate type configuration claims deciders by their resolved COMMAND type argument - the condition for
     * giving the "COMMAND type argument couldn't be resolved" guidance when no checker claims a decider.<br>
     * Only a checker that <b>is</b> a {@link EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType}
     * is detected: a lambda or composite checker that wraps or delegates to one is opaque here, so such a configuration gets no
     * guidance. This is the one place to widen the test if composite checkers become a supported pattern.
     *
     * @return true if at least one configuration's checker is a {@link EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType}
     */
    private boolean anyCheckerResolvesCommandTypes() {
        return aggregateTypeConfigurations.stream()
                                          .anyMatch(config -> config.deciderSupportsAggregateTypeChecker()
                                                  instanceof EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType);
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