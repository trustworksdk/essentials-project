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

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.reactive.command.*;
import dk.trustworks.essentials.shared.Exceptions;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A {@code sendAndDontWait} command handler that writes in its {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}
 * and then throws must leave nothing behind, whatever the {@link SendAndDontWaitErrorHandler} does with the exception.
 * Before 0.60.1 the bus called the error handler inside the interceptor chain and, when it did not rethrow, returned normally,
 * so {@link UnitOfWorkControllingCommandBusInterceptor} committed the handler's partial writes.
 */
@Testcontainers
class SendAndDontWaitHandlerFailureRollbackIT {
    private static final String   TABLE = "send_and_dont_wait_writes";
    private static final Duration WAIT  = Duration.ofSeconds(5);

    @Container
    private static final PostgreSQLContainer postgresContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    private static Jdbi                  jdbi;
    private static JdbiUnitOfWorkFactory unitOfWorkFactory;

    private final RecordingErrorHandler swallowingErrorHandler = new RecordingErrorHandler(false);
    private final RecordingErrorHandler rethrowingErrorHandler = new RecordingErrorHandler(true);
    private final IllegalStateException handlerException       = new IllegalStateException("On purpose, after writing");

    @BeforeAll
    static void setUpDatabase() {
        jdbi = Jdbi.create(postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword());
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE " + TABLE + " (command TEXT NOT NULL)"));
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
    }

    @BeforeEach
    void clearTable() {
        jdbi.useHandle(handle -> handle.execute("TRUNCATE TABLE " + TABLE));
    }

    @Test
    void local_bus_rolls_back_a_failed_sendAndDontWait_handlers_writes_when_the_error_handler_swallows_the_exception() {
        var commandBus = new LocalCommandBus(swallowingErrorHandler, new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory));
        var handler    = new WriteThenThrowCommandHandler();
        commandBus.addCommandHandler(handler);

        commandBus.sendAndDontWait("Hello World");

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(swallowingErrorHandler.calls).hasSize(1));
        assertThat(handler.wrote).isTrue();
        assertThat(rowCount()).isZero();
        var call = swallowingErrorHandler.calls.get(0);
        assertThat(call.exception()).isSameAs(handlerException);
        assertThat(call.command()).isEqualTo("Hello World");
        assertThat(call.commandHandler()).isSameAs(handler);
    }

    @Test
    void durable_bus_rolls_back_a_failed_handlers_writes_and_acknowledges_the_message_when_the_error_handler_swallows_the_exception() {
        var queueMessageHandler = startDurableBus(swallowingErrorHandler);
        var queuedMessage       = queuedMessage("Hello World");

        // On PostgreSQL the queue consumer runs the handler inside its own UnitOfWork (SingleOperationTransactionDurableQueuesInterceptor)
        assertThatCode(() -> unitOfWorkFactory.usingUnitOfWork(() -> queueMessageHandler.handle(queuedMessage)))
                .as("returning normally is what makes the queue acknowledge the message instead of redelivering it")
                .doesNotThrowAnyException();

        assertThat(rowCount()).isZero();
        assertThat(swallowingErrorHandler.calls).hasSize(1);
        var call = swallowingErrorHandler.calls.get(0);
        assertThat(call.exception()).isSameAs(handlerException);
        assertThat(call.command()).isSameAs(queuedMessage);
    }

    @Test
    void durable_bus_rolls_back_a_failed_handlers_writes_when_the_error_handler_swallows_the_exception_and_no_unit_of_work_surrounds_the_delivery() {
        var queueMessageHandler = startDurableBus(swallowingErrorHandler);
        var queuedMessage       = queuedMessage("Hello World");

        assertThatCode(() -> queueMessageHandler.handle(queuedMessage)).doesNotThrowAnyException();

        assertThat(rowCount()).isZero();
        assertThat(swallowingErrorHandler.calls).hasSize(1);
        assertThat(swallowingErrorHandler.calls.get(0).exception()).isSameAs(handlerException);
    }

    @Test
    void durable_bus_still_hands_the_failure_to_the_queue_for_redelivery_when_the_error_handler_rethrows() {
        var queueMessageHandler = startDurableBus(rethrowingErrorHandler);
        var queuedMessage       = queuedMessage("Hello World");

        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(() -> queueMessageHandler.handle(queuedMessage)))
                .as("a throwing delivery is what makes the queue redeliver the message")
                .satisfies(thrown -> assertThat(Exceptions.getRootCause(thrown)).isSameAs(handlerException));

        assertThat(rowCount()).isZero();
        assertThat(rethrowingErrorHandler.calls).hasSize(1);
        assertThat(rethrowingErrorHandler.calls.get(0).exception()).isSameAs(handlerException);
    }

    @Test
    void durable_bus_with_the_default_rethrowing_error_handler_still_hands_the_failure_to_the_queue() {
        var durableQueues = mock(DurableQueues.class);
        var commandBus = DurableLocalCommandBus.builder()
                                               .setDurableQueues(durableQueues)
                                               .addInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                               .build();
        var queueMessageHandler = start(commandBus, durableQueues);

        assertThatThrownBy(() -> queueMessageHandler.handle(queuedMessage("Hello World")))
                .satisfies(thrown -> assertThat(Exceptions.getRootCause(thrown)).isSameAs(handlerException));
        assertThat(rowCount()).isZero();
    }

    private QueuedMessageHandler startDurableBus(SendAndDontWaitErrorHandler errorHandler) {
        var durableQueues = mock(DurableQueues.class);
        var commandBus = DurableLocalCommandBus.builder()
                                               .setDurableQueues(durableQueues)
                                               .setSendAndDontWaitErrorHandler(errorHandler)
                                               .addInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                               .build();
        return start(commandBus, durableQueues);
    }

    private QueuedMessageHandler start(DurableLocalCommandBus commandBus, DurableQueues durableQueues) {
        commandBus.addCommandHandler(new WriteThenThrowCommandHandler());
        commandBus.start();
        var handlerCaptor = ArgumentCaptor.forClass(QueuedMessageHandler.class);
        verify(durableQueues).consumeFromQueue(any(QueueName.class), any(RedeliveryPolicy.class), anyInt(), handlerCaptor.capture());
        return handlerCaptor.getValue();
    }

    private static QueuedMessage queuedMessage(Object command) {
        var queuedMessage = mock(QueuedMessage.class);
        when(queuedMessage.getMessage()).thenReturn(Message.of(command));
        when(queuedMessage.getQueueName()).thenReturn(DurableLocalCommandBus.DEFAULT_COMMAND_QUEUE_NAME);
        return queuedMessage;
    }

    private static int rowCount() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM " + TABLE).mapTo(Integer.class).one());
    }

    private record ErrorHandlerCall(Throwable exception, Object command, CommandHandler commandHandler) {
    }

    private static class RecordingErrorHandler implements SendAndDontWaitErrorHandler {
        private final List<ErrorHandlerCall> calls = new CopyOnWriteArrayList<>();
        private final boolean                rethrow;

        private RecordingErrorHandler(boolean rethrow) {
            this.rethrow = rethrow;
        }

        @Override
        public void handleError(Throwable exception, Object commandMessage, CommandHandler commandHandler) {
            calls.add(new ErrorHandlerCall(exception, commandMessage, commandHandler));
            if (rethrow) {
                Exceptions.sneakyThrow(exception);
            }
        }
    }

    private class WriteThenThrowCommandHandler implements CommandHandler {
        private volatile boolean wrote;

        @Override
        public boolean canHandle(Class<?> commandType) {
            return String.class.equals(commandType);
        }

        @Override
        public Object handle(Object command) {
            unitOfWorkFactory.getRequiredUnitOfWork()
                             .handle()
                             .createUpdate("INSERT INTO " + TABLE + " (command) VALUES (:command)")
                             .bind("command", (String) command)
                             .execute();
            wrote = true;
            throw handlerException;
        }
    }
}
