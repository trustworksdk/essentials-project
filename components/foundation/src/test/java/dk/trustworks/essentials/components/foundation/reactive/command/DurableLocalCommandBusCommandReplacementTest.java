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

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.reactive.command.CommandHandler;
import dk.trustworks.essentials.reactive.command.interceptor.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorOrder;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Contract test for replacing the command in a {@link CommandBusInterceptor} on the consumer side of
 * {@link DurableLocalCommandBus#sendAndDontWait(Object)}: there the chain carries the {@link QueuedMessage}, so
 * {@link CommandBusInterceptorChain#setCommand(Object)} replaces the command message, and the matched
 * {@link CommandHandler} receives the command the replacement carries.<br>
 * Drives the bus's queue consumer directly, with the {@link QueuedMessageHandler} it registers on a mocked {@link DurableQueues}.
 */
class DurableLocalCommandBusCommandReplacementTest {
    record PlaceOrder(String value) {
    }

    private final RecordingCommandHandler commandHandler    = new RecordingCommandHandler();
    private final List<Throwable>         errorHandlerCalls = new CopyOnWriteArrayList<>();
    private       DurableLocalCommandBus  commandBus;

    @AfterEach
    void stopCommandBus() {
        if (commandBus != null) {
            commandBus.stop();
        }
    }

    private QueuedMessageHandler startCommandBus(CommandBusInterceptor... interceptors) {
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(), anyInt(), any())).thenReturn(mock(DurableQueueConsumer.class));
        commandBus = DurableLocalCommandBus.builder()
                                           .setDurableQueues(durableQueues)
                                           .setSendAndDontWaitErrorHandler((exception, command, handler) -> errorHandlerCalls.add(exception))
                                           .setInterceptors(interceptors)
                                           .build();
        commandBus.addCommandHandler(commandHandler);
        commandBus.start();
        var queuedMessageHandler = ArgumentCaptor.forClass(QueuedMessageHandler.class);
        verify(durableQueues).consumeFromQueue(any(QueueName.class), any(), anyInt(), queuedMessageHandler.capture());
        return queuedMessageHandler.getValue();
    }

    private static QueuedMessage queuedCommand(Object command) {
        return DefaultQueuedMessage.builder()
                                   .setId(QueueEntryId.random())
                                   .setQueueName(DurableLocalCommandBus.DEFAULT_COMMAND_QUEUE_NAME)
                                   .setMessage(Message.of(command))
                                   .setAddedTimestamp(OffsetDateTime.now())
                                   .build();
    }

    private static QueuedMessage withCommand(Object commandMessage, Function<PlaceOrder, Object> change) {
        var queuedMessage = (QueuedMessage) commandMessage;
        return queuedMessage.withMessage(queuedMessage.getMessage().withPayload(change.apply((PlaceOrder) queuedMessage.getMessage().getPayload())));
    }

    @Test
    void an_interceptor_that_replaces_the_queued_command_message_hands_the_command_it_carries_to_the_handler() {
        var consumer = startCommandBus(new Order1Interceptor(commandMessage -> withCommand(commandMessage, command -> new PlaceOrder("decrypted:" + command.value()))));

        consumer.handle(queuedCommand(new PlaceOrder("secret")));

        assertThat(commandHandler.received).containsExactly(new PlaceOrder("decrypted:secret"));
    }

    @Test
    void two_interceptors_replace_in_InterceptorOrder_and_the_second_sees_the_first_ones_replacement() {
        var second = new Order2Interceptor(commandMessage -> withCommand(commandMessage, command -> new PlaceOrder(command.value() + "-second")));
        var first  = new Order1Interceptor(commandMessage -> withCommand(commandMessage, command -> new PlaceOrder(command.value() + "-first")));
        var queuedMessage = queuedCommand(new PlaceOrder("x"));
        // Added in reverse: @InterceptorOrder decides
        var consumer = startCommandBus(second, first);

        consumer.handle(queuedMessage);

        assertThat(first.receivedCommandMessage).containsExactly(queuedMessage);
        assertThat(second.receivedCommandMessage).singleElement()
                                                 .isInstanceOfSatisfying(QueuedMessage.class,
                                                                         replaced -> {
                                                                             assertThat((Object) replaced.getMessage().getPayload()).isEqualTo(new PlaceOrder("x-first"));
                                                                             assertThat((Object) replaced.getId()).isEqualTo(queuedMessage.getId());
                                                                         });
        assertThat(commandHandler.received).containsExactly(new PlaceOrder("x-first-second"));
    }

    @Test
    void an_intermediate_command_message_of_another_type_is_fine_when_a_later_interceptor_restores_one_the_handler_accepts() {
        var first = new Order1Interceptor(commandMessage -> "raw:" + ((PlaceOrder) ((QueuedMessage) commandMessage).getMessage().getPayload()).value());
        // Restores a plain Message, which the bus accepts as well as a QueuedMessage
        var second   = new Order2Interceptor(commandMessage -> Message.of(new PlaceOrder((String) commandMessage)));
        var consumer = startCommandBus(first, second);

        consumer.handle(queuedCommand(new PlaceOrder("x")));

        assertThat(second.receivedCommandMessage).containsExactly("raw:x");
        assertThat(commandHandler.received).containsExactly(new PlaceOrder("raw:x"));
    }

    @Test
    void a_command_the_matched_handler_cannot_handle_fails_with_ClassCastException_outside_the_error_handler() {
        var consumer = startCommandBus(new Order1Interceptor(commandMessage -> withCommand(commandMessage, command -> "not a PlaceOrder")));

        // Thrown to the queue consumer, whose RedeliveryPolicy dead-letters a ClassCastException by default
        assertThatThrownBy(() -> consumer.handle(queuedCommand(new PlaceOrder("x"))))
                .isInstanceOf(ClassCastException.class)
                .hasMessageContaining(String.class.getName())
                .hasMessageContaining(commandHandler.toString())
                .hasMessageContaining(PlaceOrder.class.getName());
        assertThat(commandHandler.received).isEmpty();
        assertThat(errorHandlerCalls).isEmpty();
    }

    @Test
    void a_replacement_that_is_not_a_command_message_fails_with_ClassCastException() {
        var consumer = startCommandBus(new Order1Interceptor(commandMessage -> new PlaceOrder("a bare command")));

        assertThatThrownBy(() -> consumer.handle(queuedCommand(new PlaceOrder("x"))))
                .isInstanceOf(ClassCastException.class)
                .hasMessageContaining(QueuedMessage.class.getSimpleName())
                .hasMessageContaining(PlaceOrder.class.getName());
        assertThat(commandHandler.received).isEmpty();
        assertThat(errorHandlerCalls).isEmpty();
    }

    @Test
    void a_handler_failure_after_a_replacement_reaches_the_error_handler() {
        commandHandler.failWith = new IllegalStateException("On purpose");
        var consumer = startCommandBus(new Order1Interceptor(commandMessage -> withCommand(commandMessage, command -> new PlaceOrder("decrypted"))));

        consumer.handle(queuedCommand(new PlaceOrder("x")));

        assertThat(errorHandlerCalls).containsExactly(commandHandler.failWith);
    }

    private static class RecordingCommandHandler implements CommandHandler {
        private final    List<Object>     received = new CopyOnWriteArrayList<>();
        private volatile RuntimeException failWith;

        @Override
        public boolean canHandle(Class<?> commandType) {
            return PlaceOrder.class.isAssignableFrom(commandType);
        }

        @Override
        public Object handle(Object command) {
            received.add((PlaceOrder) command);
            if (failWith != null) {
                throw failWith;
            }
            return null;
        }

        @Override
        public String toString() {
            return "PlaceOrderCommandHandler";
        }
    }

    private static abstract class ReplacingInterceptor implements CommandBusInterceptor {
        private final Function<Object, Object> replacement;
        final         List<Object>             receivedCommandMessage = new CopyOnWriteArrayList<>();

        ReplacingInterceptor(Function<Object, Object> replacement) {
            this.replacement = replacement;
        }

        @Override
        public void interceptSendAndDontWait(Object commandMessage, CommandBusInterceptorChain commandBusInterceptorChain) {
            receivedCommandMessage.add(commandMessage);
            commandBusInterceptorChain.setCommand(replacement.apply(commandBusInterceptorChain.command()));
            commandBusInterceptorChain.proceed();
        }
    }

    @InterceptorOrder(1)
    private static class Order1Interceptor extends ReplacingInterceptor {
        Order1Interceptor(Function<Object, Object> replacement) {
            super(replacement);
        }
    }

    @InterceptorOrder(2)
    private static class Order2Interceptor extends ReplacingInterceptor {
        Order2Interceptor(Function<Object, Object> replacement) {
            super(replacement);
        }
    }
}
