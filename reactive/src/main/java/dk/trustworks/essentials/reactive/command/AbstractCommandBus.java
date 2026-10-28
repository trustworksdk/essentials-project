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

package dk.trustworks.essentials.reactive.command;

import dk.trustworks.essentials.reactive.command.interceptor.*;
import org.slf4j.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static dk.trustworks.essentials.shared.interceptor.DefaultInterceptorChain.sortInterceptorsByOrder;

/**
 * Base implementation of the {@link CommandBus} - provides default implementation for all
 * operations except for {@link #sendAndDontWait(Object)}/{@link #sendAndDontWait(Object, Duration)}
 * <p>
 * Adding and removing {@link CommandHandler}s is safe from any number of threads, also while commands are sent. When an
 * added {@link CommandHandler} makes a command type handled by more than one {@link CommandHandler}, the bus logs a WARN
 * naming the command type and the handlers (see {@link #findCommandTypesHandledByMoreThanOneCommandHandler(CommandHandler)}) -
 * it does not fail the registration, as an overlap that is never sent keeps working.
 */
public abstract class AbstractCommandBus implements CommandBus {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    protected final List<CommandBusInterceptor>    interceptors       = new CopyOnWriteArrayList<>();
    protected final List<CommandContextPropagator> contextPropagators = new CopyOnWriteArrayList<>();
    /**
     * The registered {@link CommandHandler}s, in registration order. Modified only under {@link #commandHandlerRegistrationLock}'s
     * write lock, together with clearing {@link #commandTypeToCommandHandlerCache}; read freely (a copy-on-write snapshot)
     */
    protected final Set<CommandHandler>            commandHandlers    = new CopyOnWriteArraySet<>();
    /**
     * Write lock: changing {@link #commandHandlers} and clearing {@link #commandTypeToCommandHandlerCache}.<br>
     * Read lock: resolving a command type that is not yet in {@link #commandTypeToCommandHandlerCache} - so a resolution
     * made from the handlers as they were before a registration can never be cached after that registration cleared the cache
     */
    protected final ReadWriteLock                  commandHandlerRegistrationLock = new ReentrantReadWriteLock();
    protected final SendAndDontWaitErrorHandler sendAndDontWaitErrorHandler;

    protected AbstractCommandBus(List<CommandBusInterceptor> interceptors) {
        this(new SendAndDontWaitErrorHandler.FallbackSendAndDontWaitErrorHandler(),
             interceptors);
    }

    protected AbstractCommandBus(SendAndDontWaitErrorHandler sendAndDontWaitErrorHandler,
                                 List<CommandBusInterceptor> interceptors) {
        requireNonNull(interceptors, "No interceptors list provided");
        this.sendAndDontWaitErrorHandler = requireNonNull(sendAndDontWaitErrorHandler, "No sendAndDontWaitErrorHandler provided");
        interceptors.forEach(this::addInterceptor);
    }

    /**
     * Key: Concrete Command type<br>
     * Value: {@link CommandHandler} that can handle the command type
     */
    protected final ConcurrentMap<Class<?>, CommandHandler> commandTypeToCommandHandlerCache = new ConcurrentHashMap<>();

    @Override
    public List<CommandBusInterceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }

    @Override
    public CommandBus addInterceptor(CommandBusInterceptor interceptor) {
        if (!interceptors.contains(interceptor)) {
            log.info("Adding CommandBusInterceptor: {}", interceptor);
            interceptors.add(requireNonNull(interceptor, "No interceptor provided"));
        }
        sortInterceptorsByOrder(this.interceptors);
        return this;
    }

    /**
     * Add a {@link CommandContextPropagator}, which carries context from the sending thread to the thread that runs the
     * handler for {@link #sendAsync(Object)} and {@link #sendAndDontWait(Object)}. Propagators added first wrap outermost.
     *
     * @param contextPropagator the propagator
     * @return this bus
     */
    public CommandBus addContextPropagator(CommandContextPropagator contextPropagator) {
        requireNonNull(contextPropagator, "No contextPropagator provided");
        if (!contextPropagators.contains(contextPropagator)) {
            log.info("Adding CommandContextPropagator: {}", contextPropagator);
            contextPropagators.add(contextPropagator);
        }
        return this;
    }

    /**
     * @return the registered {@link CommandContextPropagator}s, outermost first
     */
    public List<CommandContextPropagator> getContextPropagators() {
        return Collections.unmodifiableList(contextPropagators);
    }

    /**
     * Wrap a handler invocation with every registered {@link CommandContextPropagator}. <b>Must be called on the sending
     * thread</b>, since that is where the propagators capture their context.
     *
     * @param handlerInvocation the invocation to run on the handling thread
     * @param <R>               the result type
     * @return the wrapped invocation
     */
    protected <R> Callable<R> propagateContext(Callable<R> handlerInvocation) {
        var invocation = requireNonNull(handlerInvocation, "No handlerInvocation provided");
        for (var index = contextPropagators.size() - 1; index >= 0; index--) {
            invocation = contextPropagators.get(index).propagate(invocation);
        }
        return invocation;
    }

    @Override
    public boolean hasInterceptor(CommandBusInterceptor interceptor) {
        return interceptors.contains(requireNonNull(interceptor, "No interceptor provided"));
    }

    @Override
    public CommandBus removeInterceptor(CommandBusInterceptor interceptor) {
        log.info("Removing CommandBusInterceptor: {}", interceptor);
        interceptors.remove(requireNonNull(interceptor, "No interceptor provided"));
        sortInterceptorsByOrder(this.interceptors);
        return this;
    }

    @Override
    public CommandBus addCommandHandler(CommandHandler commandHandler) {
        requireNonNull(commandHandler, "No commandHandler provided");
        boolean added;
        commandHandlerRegistrationLock.writeLock().lock();
        try {
            added = commandHandlers.add(commandHandler);
            if (added) {
                log.info("Adding CommandHandler: {}", commandHandler);
                commandTypeToCommandHandlerCache.clear();
            }
        } finally {
            commandHandlerRegistrationLock.writeLock().unlock();
        }
        if (added) {
            // Outside the lock: the check calls the handlers' canHandle
            warnAboutCommandTypesHandledByMoreThanOneCommandHandler(commandHandler);
        }
        return this;
    }

    /**
     * WARN for every command type that the just added {@link CommandHandler} and another registered {@link CommandHandler}
     * can both handle - every send of it fails with a {@link MultipleCommandHandlersFoundException}, which would otherwise
     * surface only at the first send.
     * <p>
     * A WARN, not a failure: an overlap that is never sent works today, and rejecting it would break such a setup in a
     * patch release. The check never fails the registration either - its own failure is logged at ERROR, as DEBUG is
     * usually filtered out and would hide that the check did not run.
     *
     * @param addedCommandHandler the {@link CommandHandler} that was just added
     */
    private void warnAboutCommandTypesHandledByMoreThanOneCommandHandler(CommandHandler addedCommandHandler) {
        try {
            findCommandTypesHandledByMoreThanOneCommandHandler(addedCommandHandler).forEach((commandType, handlers) ->
                    log.warn("Command type '{}' would be handled by {} command handlers: {}. The {} fails every send of it with a {} - " +
                                     "register each command type with exactly one {}",
                             commandType.getName(),
                             handlers.size(),
                             handlers,
                             this.getClass().getSimpleName(),
                             MultipleCommandHandlersFoundException.class.getSimpleName(),
                             CommandHandler.class.getSimpleName()));
        } catch (RuntimeException e) {
            log.error(msg("Could not check whether the command types of {} '{}' are handled by more than one {}",
                          CommandHandler.class.getSimpleName(),
                          addedCommandHandler,
                          CommandHandler.class.getSimpleName()),
                      e);
        }
    }

    @Override
    public CommandBus removeCommandHandler(CommandHandler commandHandler) {
        requireNonNull(commandHandler, "No commandHandler provided");
        commandHandlerRegistrationLock.writeLock().lock();
        try {
            if (commandHandlers.remove(commandHandler)) {
                log.info("Removing CommandHandler: {}", commandHandler);
                commandTypeToCommandHandlerCache.clear();
            }
        } finally {
            commandHandlerRegistrationLock.writeLock().unlock();
        }
        return this;
    }

    /**
     * @return an immutable snapshot of the registered {@link CommandHandler}s, in registration order
     */
    @Override
    public List<CommandHandler> getCommandHandlers() {
        return List.copyOf(commandHandlers);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R, C> R send(C command) {
        var commandHandler = findCommandHandlerCapableOfHandling(command);
        log.debug("Synchronously sending command of type '{}' to {} '{}'", command.getClass().getName(), CommandHandler.class.getSimpleName(), commandHandler.toString());
        return (R) CommandBusInterceptorChain.newInterceptorChain(command,
                                                                  commandHandler,
                                                                  interceptors,
                                                                  (interceptor, commandBusInterceptorChain) -> interceptor.interceptSend(command, commandBusInterceptorChain),
                                                                  commandHandler::handle)
                                             .proceed();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R, C> Mono<R> sendAsync(C command) {
        var commandHandler = findCommandHandlerCapableOfHandling(command);
        log.debug("Asynchronously sending command of type '{}' to {} '{}'", command.getClass().getName(), CommandHandler.class.getSimpleName(), commandHandler.toString());
        // The handler runs on a Reactor worker (publishOn fuses with fromCallable), so context is captured here, on the sending thread
        return Mono.fromCallable(propagateContext(() -> (R) CommandBusInterceptorChain.newInterceptorChain(command,
                                                                                                           commandHandler,
                                                                                                           interceptors,
                                                                                                           (interceptor, commandBusInterceptorChain) -> interceptor.interceptSendAsync(command, commandBusInterceptorChain),
                                                                                                           commandHandler::handle)
                                                                                      .proceed())).publishOn(Schedulers.boundedElastic());
    }

    @Override
    public CommandHandler findCommandHandlerCapableOfHandling(Object command) {
        requireNonNull(command, "No command provided");
        var cachedCommandHandler = commandTypeToCommandHandlerCache.get(command.getClass());
        if (cachedCommandHandler != null) {
            return cachedCommandHandler;
        }
        commandHandlerRegistrationLock.readLock().lock();
        try {
            return resolveAndCacheCommandHandlerCapableOfHandling(command.getClass());
        } finally {
            commandHandlerRegistrationLock.readLock().unlock();
        }
    }

    private CommandHandler resolveAndCacheCommandHandlerCapableOfHandling(Class<?> commandType_) {
        return commandTypeToCommandHandlerCache.computeIfAbsent(commandType_, commandType -> {
            var commandHandlersThatCanHandleCommand = commandHandlers.stream().filter(commandHandler -> commandHandler.canHandle(commandType)).toList();
            if (commandHandlersThatCanHandleCommand.isEmpty()) {
                throw new NoCommandHandlerFoundException(commandType, msg("Couldn't find a {} that can handle a command of type '{}'", CommandHandler.class.getSimpleName(), commandType.getName()));
            } else if (commandHandlersThatCanHandleCommand.size() > 1) {
                throw new MultipleCommandHandlersFoundException(commandType,
                                                                msg("There should only be one {} that can handle a given command. Found {} {}'s that all can handle a command of type '{}': {}",
                                                                    CommandHandler.class.getSimpleName(),
                                                                    commandHandlersThatCanHandleCommand.size(),
                                                                    CommandHandler.class.getSimpleName(),
                                                                    commandType.getName(),
                                                                    commandHandlersThatCanHandleCommand.stream().map(Object::toString).toList()));

            } else {
                return commandHandlersThatCanHandleCommand.get(0);
            }
        });
    }

    @Override
    public boolean hasCommandHandler(CommandHandler commandHandler) {
        return commandHandlers.contains(requireNonNull(commandHandler, "No commandHandler provided"));
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "{" +
                "interceptors=" + interceptors +
                ", commandHandlers=" + commandHandlers +
                '}';
    }
}
