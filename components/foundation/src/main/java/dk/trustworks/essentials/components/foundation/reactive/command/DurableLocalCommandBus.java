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

package dk.trustworks.essentials.components.foundation.reactive.command;

import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.reactive.command.*;
import dk.trustworks.essentials.reactive.command.SendAndDontWaitErrorHandler.RethrowingSendAndDontWaitErrorHandler;
import dk.trustworks.essentials.reactive.command.interceptor.*;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Provides a JVM local and <b>durable</b>,
 * in regard to {@link #sendAndDontWait(Object)}/{@link #sendAndDontWait(Object, Duration)}),
 * variant of the {@link CommandBus} concept<br>
 * Durability for {@link #sendAndDontWait(Object)}/{@link #sendAndDontWait(Object, Duration)}) is delegated
 * to {@link DurableQueues}<br>
 * Which {@link QueueName} that is used will be determined by the specified {@link #getCommandQueueName()}<br>
 * The {@link RedeliveryPolicy} is determined by the specified {@link #getCommandQueueRedeliveryPolicy()}<br>
 * <br>
 * Note: If the {@link SendAndDontWaitErrorHandler} provided doesn't rethrow the exception, then the underlying {@link DurableQueues} will not be able to retry the command.<br>
 * Due to this the {@link DurableLocalCommandBus} defaults to using the {@link RethrowingSendAndDontWaitErrorHandler}
 *
 * @see AnnotatedCommandHandler
 */
public final class DurableLocalCommandBus extends AbstractCommandBus implements Lifecycle {
    private static final Logger           log                        = LoggerFactory.getLogger(DurableLocalCommandBus.class);
    public static final  QueueName        DEFAULT_COMMAND_QUEUE_NAME = QueueName.of("DefaultCommandQueue");
    public static final  RedeliveryPolicy DEFAULT_REDELIVERY_POLICY  = RedeliveryPolicy.linearBackoff(Duration.ofMillis(150),
                                                                                                      Duration.ofMillis(1000),
                                                                                                      20);
    
    private DurableQueues    durableQueues;
    private int              parallelSendAndDontWaitConsumers = Runtime.getRuntime().availableProcessors();
    private QueueName        commandQueueName                 = DEFAULT_COMMAND_QUEUE_NAME;
    private RedeliveryPolicy commandQueueRedeliveryPolicy     = DEFAULT_REDELIVERY_POLICY;

    private boolean              started;
    private DurableQueueConsumer durableQueueConsumer;
    private boolean              missingUnitOfWorkInterceptorWarned;

    /**
     * Builder for a {@link DurableLocalCommandBusBuilder}
     *
     * @return builder for a {@link DurableLocalCommandBusBuilder}
     */
    public static DurableLocalCommandBusBuilder builder() {
        return new DurableLocalCommandBusBuilder();
    }

    /**
     * Create a new {@link DurableLocalCommandBus} using defaults:
     * <ul>
     *     <li>{@link #getCommandQueueName()}: {@link #DEFAULT_COMMAND_QUEUE_NAME}</li>
     *     <li>{@link #getCommandQueueRedeliveryPolicy()}: {@link #DEFAULT_REDELIVERY_POLICY}</li>
     *     <li>{@link SendAndDontWaitErrorHandler}: {@link RethrowingSendAndDontWaitErrorHandler}</li>
     * </ul>
     *
     * @param durableQueues the underlying Durable Queues provider
     */
    public DurableLocalCommandBus(DurableQueues durableQueues) {
        super(new RethrowingSendAndDontWaitErrorHandler(), List.of());
        this.durableQueues = requireNonNull(durableQueues, "No durableQueues instance provided");
    }

    /**
     * Create a new {@link DurableLocalCommandBus}
     *
     * @param durableQueues                    the underlying Durable Queues provider
     * @param parallelSendAndDontWaitConsumers How many parallel {@link DurableQueues} consumers should listen for messages added using {@link #sendAndDontWait(Object)}/{@link #sendAndDontWait(Object, Duration)}
     * @param commandQueueName                 Set the name of the {@link DurableQueues} that will be used queuing commands sent using {@link DurableLocalCommandBus#sendAndDontWait(Object)}
     * @param commandQueueRedeliveryPolicy     Set the {@link RedeliveryPolicy} used when handling queued commnds sent using{@link DurableLocalCommandBus#sendAndDontWait(Object)}
     * @param sendAndDontWaitErrorHandler      Exception handler that will handle errors that occur during {@link CommandBus#sendAndDontWait(Object)}/{@link CommandBus#sendAndDontWait(Object, Duration)}. If this handler doesn't rethrow the exeption,
     *                                         then the message will not be retried by the underlying {@link DurableQueues}
     * @param interceptors                     all the {@link CommandBusInterceptor}'s
     */
    DurableLocalCommandBus(DurableQueues durableQueues,
                           int parallelSendAndDontWaitConsumers,
                           QueueName commandQueueName,
                           RedeliveryPolicy commandQueueRedeliveryPolicy,
                           SendAndDontWaitErrorHandler sendAndDontWaitErrorHandler,
                           List<CommandBusInterceptor> interceptors) {
        super(sendAndDontWaitErrorHandler,
              interceptors);
        requireTrue(parallelSendAndDontWaitConsumers >= 1, "parallelSendAndDontWaitConsumers is < 1");
        this.durableQueues = requireNonNull(durableQueues, "No durableQueues instance provided");
        this.parallelSendAndDontWaitConsumers = parallelSendAndDontWaitConsumers;
        this.commandQueueName = requireNonNull(commandQueueName, "No commandQueueName provided");
        this.commandQueueRedeliveryPolicy = requireNonNull(commandQueueRedeliveryPolicy, "No commandQueueRedeliveryPolicy provided");

    }

    @Override
    public void start() {
        if (!started) {
            log.info("Starting...");
            started = true;
            warnIfAFailedHandlersWritesCanBeCommitted();
            durableQueueConsumer = durableQueues.consumeFromQueue(commandQueueName,
                                                                  commandQueueRedeliveryPolicy,
                                                                  parallelSendAndDontWaitConsumers,
                                                                  this::processSendAndDontWaitMessage);
            log.info("Started");
        }
    }

    /**
     * A {@link SendAndDontWaitErrorHandler} that does not rethrow lets the queued message count as handled. Without a
     * {@link UnitOfWorkControllingCommandBusInterceptor} nothing rolls back what the failed command handler wrote before it
     * threw: on PostgreSQL those writes join the queue consumer's own {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork},
     * which then commits them together with the acknowledgement. Both Spring Boot starters always add the interceptor,
     * so only a hand-built bus can end up like this. Checked at {@link #start()}, because interceptors can still be
     * added with {@link #addInterceptor(CommandBusInterceptor)} after construction. WARN only (a patch release must not
     * reject a setup that starts today); logged once per bus.
     */
    private void warnIfAFailedHandlersWritesCanBeCommitted() {
        if (missingUnitOfWorkInterceptorWarned ||
                sendAndDontWaitErrorHandler instanceof RethrowingSendAndDontWaitErrorHandler ||
                interceptors.stream().anyMatch(interceptor -> interceptor instanceof UnitOfWorkControllingCommandBusInterceptor)) {
            return;
        }
        missingUnitOfWorkInterceptorWarned = true;
        log.warn("[{}] {} uses the non-rethrowing {} '{}' but has no {}: when a sendAndDontWait command handler throws, the message is acknowledged " +
                         "and the writes the handler made before it threw are committed (on PostgreSQL they join the queue consumer's UnitOfWork), " +
                         "instead of being rolled back. Add the interceptor: commandBus.addInterceptor(new {}(unitOfWorkFactory)) - " +
                         "or DurableLocalCommandBus.builder().addInterceptors(...) - as the Spring Boot starters do",
                 commandQueueName,
                 DurableLocalCommandBus.class.getSimpleName(),
                 SendAndDontWaitErrorHandler.class.getSimpleName(),
                 sendAndDontWaitErrorHandler.getClass().getSimpleName(),
                 UnitOfWorkControllingCommandBusInterceptor.class.getSimpleName(),
                 UnitOfWorkControllingCommandBusInterceptor.class.getSimpleName());
    }

    /**
     * Stops the bus and cancels its {@link DurableQueueConsumer}, deregistering it from the {@link DurableQueues}, because
     * the next {@link #start()} consumes from the command queue anew. {@link DurableQueueConsumer#stop()} alone is not
     * enough on every backend: a {@code ShardOwnedDurableQueues} consumer stopped that way stays registered - so the
     * {@code DurableQueues}' next {@code start()} starts it again - which made the bus's next {@link #start()} fail with
     * "There is already a consumer on queue". On the other backends {@code cancel()} is {@code stop()}.
     */
    @Override
    public void stop() {
        if (started) {
            log.info("Stopping...");
            started = false;
            if (durableQueueConsumer != null) {
                log.debug("Cancelling durableQueueConsumer");
                durableQueueConsumer.cancel();
                durableQueueConsumer = null;
                log.debug("Cancelled durableQueueConsumer");
            }
            log.info("Stopped");
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> void sendAndDontWait(C command) {
        _sendAndDontWait(command, Optional.empty());
    }

    @Override
    public <C> void sendAndDontWait(C command, Duration delayMessageDelivery) {
        _sendAndDontWait(command, Optional.ofNullable(delayMessageDelivery));

    }

    private <C> void _sendAndDontWait(C command, Optional<Duration> messageDeliveryDelay) {
        var commandHandler = findCommandHandlerCapableOfHandling(command);
        requireNonNull(messageDeliveryDelay, "You must provide a messageDeliveryDelay value");


        if (messageDeliveryDelay.isPresent()) {
            log.debug("[{}] Queuing Durable delayed {} sendAndDontWait for command of type '{}' to {} '{}'",
                      commandQueueName,
                      messageDeliveryDelay,
                      command.getClass().getName(),
                      CommandHandler.class.getSimpleName(),
                      commandHandler.toString());
        } else {
            log.debug("[{}] Queuing Durable sendAndDontWait command of type '{}' to {} '{}'",
                      commandQueueName,
                      command.getClass().getName(),
                      CommandHandler.class.getSimpleName(),
                      commandHandler.toString());
        }

        durableQueues.queueMessage(commandQueueName,
                                   Message.of(command),
                                   messageDeliveryDelay);
    }

    private void processSendAndDontWaitMessage(QueuedMessage queuedMessage) {
        var command        = queuedMessage.getMessage().getPayload();
        var commandHandler = findCommandHandlerCapableOfHandling(command);
        log.debug("[{}] Handling Durable sendAndDontWait command of type '{}' to {} '{}'",
                  queuedMessage.getQueueName(),
                  command.getClass().getName(),
                  CommandHandler.class.getSimpleName(),
                  commandHandler.toString());

        var handlerFailure = new AtomicReference<Throwable>();
        try {
            CommandBusInterceptorChain.newInterceptorChain(queuedMessage,
                                                           commandHandler,
                                                           interceptors,
                                                           (interceptor, commandBusInterceptorChain) -> {
                                                               interceptor.interceptSendAndDontWait(queuedMessage, commandBusInterceptorChain);
                                                               return null;
                                                           },
                                                           msg -> {
                                                               try {
                                                                   return commandHandler.handle(command);
                                                               } catch (Throwable e) {
                                                                   rethrowIfCriticalError(e);
                                                                   // Let the failure unwind through the interceptors, so e.g. a UnitOfWork interceptor rolls back
                                                                   // what the handler wrote, and only then hand it to the error handler (below)
                                                                   handlerFailure.set(e);
                                                                   throw e;
                                                               }
                                                           })
                                      .proceed();
        } catch (Throwable e) {
            var failure = handlerFailure.get();
            if (failure == null) {
                // Not a command handler failure (e.g. an interceptor or the commit failed): the queue's RedeliveryPolicy decides
                throw e;
            }
            // The handler's own exception, not whatever an interceptor wrapped it in on the way out.
            // If the error handler rethrows, the DurableQueues redelivers the message; if it doesn't, the message counts as handled
            sendAndDontWaitErrorHandler.handleError(failure,
                                                    queuedMessage,
                                                    commandHandler);
        }
    }

    public int getParallelSendAndDontWaitConsumers() {
        return parallelSendAndDontWaitConsumers;
    }

    public QueueName getCommandQueueName() {
        return commandQueueName;
    }

    public RedeliveryPolicy getCommandQueueRedeliveryPolicy() {
        return commandQueueRedeliveryPolicy;
    }
}