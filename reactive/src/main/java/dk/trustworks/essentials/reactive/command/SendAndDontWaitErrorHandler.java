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

import dk.trustworks.essentials.shared.Exceptions;
import org.slf4j.*;

import java.time.Duration;

import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Exception handler that will handle errors that occur during {@link CommandBus#sendAndDontWait(Object)}/{@link CommandBus#sendAndDontWait(Object, Duration)}
 */
public interface SendAndDontWaitErrorHandler {
    /**
     * Handle an exception that occurred while processing a Command sent using {@link CommandBus#sendAndDontWait(Object)}/{@link CommandBus#sendAndDontWait(Object, Duration)}<br>
     * Since 0.60.1 this is called <b>after</b> the command handler's exception has unwound through every
     * {@link dk.trustworks.essentials.reactive.command.interceptor.CommandBusInterceptor}, so a transaction an interceptor opened around the handler
     * (e.g. a UnitOfWork) has already been rolled back - or marked rollback-only, if it joined an outer one - whether or not this
     * handler rethrows. Exceptions thrown by an interceptor itself, rather than by the command handler, are not passed to this handler.
     *
     * @param exception      the exception the command handler threw (never a wrapper an interceptor added on the way out) during {@link CommandBus#sendAndDontWait(Object)}/{@link CommandBus#sendAndDontWait(Object, Duration)}
     * @param commandMessage the command message that caused the exception (can be wrapped in an infrastructure wrapper, such as a Message/QueuedMessage, depending on which transport channel is used)
     * @param commandHandler the command handler that can handle the command
     */
    void handleError(Throwable exception, Object commandMessage, CommandHandler commandHandler);

    /**
     * Fallback {@link SendAndDontWaitErrorHandler} that only error logs any issues.<br>
     * Note: If the {@link FallbackSendAndDontWaitErrorHandler} is used with a Durable Command Bus (e.g. using DurableQueues),
     * then any failing command will not be retried.<br>
     * Instead use {@link RethrowingSendAndDontWaitErrorHandler}
     */
    class FallbackSendAndDontWaitErrorHandler implements SendAndDontWaitErrorHandler {
        private static final Logger log = LoggerFactory.getLogger(FallbackSendAndDontWaitErrorHandler.class);

        @Override
        public void handleError(Throwable exception, Object command, CommandHandler commandHandler) {
            log.error(msg("SendAndDontWait ERROR: {} '{}' failed to handle command: {}",
                          CommandHandler.class.getSimpleName(),
                          commandHandler.getClass().getName(),
                          command), exception);
        }
    }

    /**
     * Fallback {@link SendAndDontWaitErrorHandler} that error logs any issues and rethrows the exception.<br>
     * The {@link RethrowingSendAndDontWaitErrorHandler} is compatible with a Durable Command Bus (e.g. using DurableQueues),
     * as rethrowing the exceptions allows the command to be retried
     */
    class RethrowingSendAndDontWaitErrorHandler implements SendAndDontWaitErrorHandler {
        private static final Logger log = LoggerFactory.getLogger(FallbackSendAndDontWaitErrorHandler.class);

        @Override
        public void handleError(Throwable exception, Object command, CommandHandler commandHandler) {
            log.error(msg("SendAndDontWait ERROR: {} '{}' failed to handle command: {}",
                          CommandHandler.class.getSimpleName(),
                          commandHandler.getClass().getName(),
                          command), exception);
            Exceptions.sneakyThrow(exception);
        }
    }
}
