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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A failing {@code sendAndDontWait} handler's exception must travel back out through the interceptor chain - that is what
 * lets a transaction-controlling interceptor roll back what the handler wrote - and only then reach the
 * {@link SendAndDontWaitErrorHandler}, still as the exception the handler threw.
 */
class LocalCommandBusSendAndDontWaitErrorTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final RecordingErrorHandler errorHandler = new RecordingErrorHandler();

    @Test
    void an_interceptor_sees_the_exception_a_sendAndDontWait_handler_throws() {
        var handlerException = new IllegalStateException("On purpose");
        var interceptor      = new ExceptionObservingInterceptor();
        var commandBus       = new LocalCommandBus(errorHandler, interceptor);
        commandBus.addCommandHandler(new ThrowingCommandHandler(handlerException));

        commandBus.sendAndDontWait("Hello World");

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(errorHandler.calls).hasSize(1));
        assertThat(interceptor.observed).containsExactly(handlerException);
    }

    @Test
    void the_error_handler_is_called_after_the_interceptors_have_unwound() {
        var handlerException = new IllegalStateException("On purpose");
        var interceptor      = new ExceptionObservingInterceptor();
        var commandBus       = new LocalCommandBus((exception, command, commandHandler) -> {
            // By the time the error handler runs, the interceptor's finally block (where a transaction would be resolved) has already run
            errorHandler.handleError(exception, command, commandHandler);
            errorHandler.interceptorHadUnwound.add(interceptor.unwound);
        }, interceptor);
        commandBus.addCommandHandler(new ThrowingCommandHandler(handlerException));

        commandBus.sendAndDontWait("Hello World");

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(errorHandler.calls).hasSize(1));
        assertThat(errorHandler.interceptorHadUnwound).containsExactly(true);
    }

    @Test
    void the_error_handler_gets_the_handlers_own_exception_once_even_when_an_interceptor_wraps_it() {
        var handlerException = new IllegalStateException("On purpose");
        // Mirrors UnitOfWorkFactory.withUnitOfWork, which rethrows a handler failure wrapped in a UnitOfWorkException
        CommandBusInterceptor wrappingInterceptor = new CommandBusInterceptor() {
            @Override
            public void interceptSendAndDontWait(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
                try {
                    commandBusInterceptorChain.proceed();
                } catch (RuntimeException e) {
                    throw new RuntimeException("Wrapped", e);
                }
            }
        };
        var commandBus     = new LocalCommandBus(errorHandler, wrappingInterceptor);
        var commandHandler = new ThrowingCommandHandler(handlerException);
        commandBus.addCommandHandler(commandHandler);

        commandBus.sendAndDontWait("Hello World");

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(errorHandler.calls).hasSize(1));
        var call = errorHandler.calls.get(0);
        assertThat(call.exception()).isSameAs(handlerException);
        assertThat(call.command()).isEqualTo("Hello World");
        assertThat(call.commandHandler()).isSameAs(commandHandler);
    }

    @Test
    void the_error_handler_is_called_once_with_the_handlers_exception_when_there_are_no_interceptors() {
        var handlerException = new IllegalStateException("On purpose");
        var commandBus       = new LocalCommandBus(errorHandler);
        commandBus.addCommandHandler(new ThrowingCommandHandler(handlerException));

        commandBus.sendAndDontWait("Hello World");

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(errorHandler.calls).hasSize(1));
        assertThat(errorHandler.calls.get(0).exception()).isSameAs(handlerException);
    }

    @Test
    void an_interceptor_that_throws_before_the_handler_runs_does_not_reach_the_error_handler() {
        CommandBusInterceptor failingInterceptor = new CommandBusInterceptor() {
            @Override
            public void interceptSendAndDontWait(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
                throw new IllegalStateException("Interceptor failure");
            }
        };
        var handlerCalled = new CountDownLatch(1);
        var commandBus    = new LocalCommandBus(errorHandler, failingInterceptor);
        commandBus.addCommandHandler(new CommandHandler() {
            @Override
            public boolean canHandle(Class<?> commandType) {
                return true;
            }

            @Override
            public Object handle(Object command) {
                handlerCalled.countDown();
                return null;
            }
        });

        commandBus.sendAndDontWait("Hello World");

        // Unchanged since before 0.60.1: the error handler handles command handler failures, not interceptor failures
        Awaitility.await().during(Duration.ofMillis(300)).atMost(WAIT).untilAsserted(() -> assertThat(errorHandler.calls).isEmpty());
        assertThat(handlerCalled.getCount()).isEqualTo(1);
    }

    private record ErrorHandlerCall(Throwable exception, Object command, CommandHandler commandHandler) {
    }

    private static class RecordingErrorHandler implements SendAndDontWaitErrorHandler {
        private final List<ErrorHandlerCall> calls                 = new CopyOnWriteArrayList<>();
        private final List<Boolean>          interceptorHadUnwound = new CopyOnWriteArrayList<>();

        @Override
        public void handleError(Throwable exception, Object command, CommandHandler commandHandler) {
            calls.add(new ErrorHandlerCall(exception, command, commandHandler));
        }
    }

    private static class ExceptionObservingInterceptor implements CommandBusInterceptor {
        private final    List<Throwable> observed = new CopyOnWriteArrayList<>();
        private volatile boolean         unwound;

        @Override
        public void interceptSendAndDontWait(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
            try {
                commandBusInterceptorChain.proceed();
            } catch (RuntimeException e) {
                observed.add(e);
                throw e;
            } finally {
                unwound = true;
            }
        }
    }

    private static class ThrowingCommandHandler implements CommandHandler {
        private final RuntimeException exception;

        private ThrowingCommandHandler(RuntimeException exception) {
            this.exception = exception;
        }

        @Override
        public boolean canHandle(Class<?> commandType) {
            return true;
        }

        @Override
        public Object handle(Object command) {
            throw exception;
        }
    }
}
