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
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import org.slf4j.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Provides a JVM local and <b>non-durable</b>,
 * in regard to {@link #sendAndDontWait(Object)}/{@link #sendAndDontWait(Object, Duration)}),
 * variant of the {@link CommandBus} concept
 *
 * @see AnnotatedCommandHandler
 */
public final class LocalCommandBus extends AbstractCommandBus {
    private static final Logger log = LoggerFactory.getLogger(LocalCommandBus.class);

    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(ThreadFactoryBuilder.builder()
                                                                                                                                     .nameFormat(this.getClass().getSimpleName() + "Delayed-Send-%d")
                                                                                                                                     .daemon(true)
                                                                                                                                     .build());

    public LocalCommandBus() {
        super(List.of());
    }

    public LocalCommandBus(SendAndDontWaitErrorHandler sendAndDontWaitErrorHandler) {
        super(sendAndDontWaitErrorHandler,
              List.of());
    }

    public LocalCommandBus(List<CommandBusInterceptor> interceptors) {
        super(interceptors);
    }

    public LocalCommandBus(SendAndDontWaitErrorHandler sendAndDontWaitErrorHandler,
                           List<CommandBusInterceptor> interceptors) {
        super(sendAndDontWaitErrorHandler,
              interceptors);
    }

    public LocalCommandBus(CommandBusInterceptor... interceptors) {
        this(List.of(interceptors));
    }

    public LocalCommandBus(SendAndDontWaitErrorHandler sendAndDontWaitErrorHandler,
                           CommandBusInterceptor... interceptors) {
        this(sendAndDontWaitErrorHandler, List.of(interceptors));
    }


    @Override
    @SuppressWarnings("unchecked")
    public <C> void sendAndDontWait(C command) {
        var commandHandler = findCommandHandlerCapableOfHandling(command);
        log.debug("sendAndDontWait command of type '{}' to {} '{}'", command.getClass().getName(), CommandHandler.class.getSimpleName(), commandHandler.toString());
        // The handler runs on a Reactor worker (publishOn fuses with fromCallable), so context is captured here, on the sending thread
        Mono.fromCallable(propagateContext(() -> {
                var handlerFailure = new AtomicReference<Exception>();
                try {
                    CommandBusInterceptorChain.newInterceptorChain(command,
                                                                   commandHandler,
                                                                   interceptors,
                                                                   (interceptor, commandBusInterceptorChain) -> {
                                                                       interceptor.interceptSendAndDontWait(command, commandBusInterceptorChain);
                                                                       return null;
                                                                   },
                                                                   _cmd -> {
                                                                       try {
                                                                           return commandHandler.handle(_cmd);
                                                                       } catch (Exception e) {
                                                                           // Let the failure unwind through the interceptors, so e.g. a UnitOfWork interceptor rolls back
                                                                           // what the handler wrote, and only then hand it to the error handler (below)
                                                                           handlerFailure.set(e);
                                                                           throw e;
                                                                       }
                                                                   })
                                              .proceed();
                } catch (Exception e) {
                    var failure = handlerFailure.get();
                    if (failure == null) {
                        // Not a command handler failure (e.g. an interceptor failed) - not the error handler's to handle
                        throw e;
                    }
                    // The handler's own exception, not whatever an interceptor wrapped it in on the way out
                    sendAndDontWaitErrorHandler.handleError(failure,
                                                            command,
                                                            commandHandler);
                }
                return null;
            }))
            .publishOn(Schedulers.boundedElastic())
            .subscribe();
    }

    @Override
    public <C> void sendAndDontWait(C command, Duration delayMessageDelivery) {
        var commandHandler = findCommandHandlerCapableOfHandling(command);
        requireNonNull(delayMessageDelivery, "You must provide a delayMessageDelivery value");
        log.debug("Delayed {} sendAndDontWait for command of type '{}' to {} '{}'",
                  delayMessageDelivery,
                  command.getClass().getName(),
                  CommandHandler.class.getSimpleName(),
                  commandHandler.toString());
        // Captured now, on the sending thread; the delayed send then runs inside it on the scheduler thread
        var delayedSend = propagateContext(() -> {
            sendAndDontWait(command);
            return null;
        });
        scheduledExecutorService.schedule(delayedSend,
                                          delayMessageDelivery.toMillis(),
                                          TimeUnit.MILLISECONDS);
    }
}
