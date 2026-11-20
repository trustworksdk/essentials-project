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
import dk.trustworks.essentials.shared.interceptor.InterceptorOrder;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract test for replacing the command in a {@link CommandBusInterceptor} ({@link CommandBusInterceptorChain#setCommand(Object)}),
 * for every way {@link LocalCommandBus} sends a command: later interceptors and the matched {@link CommandHandler} receive the
 * replacement, the {@link CommandHandler} is never re-resolved, and only the command that reaches it has to fit it.
 */
class CommandBusInterceptorCommandReplacementTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    enum SendMode {SEND, SEND_ASYNC, SEND_AND_DONT_WAIT}

    record PlaceOrder(String value) {
    }

    private final RecordingCommandHandler commandHandler = new RecordingCommandHandler();
    private final OutcomeRecorder         outcome        = new OutcomeRecorder();
    private final List<Throwable>         errorHandlerCalls = new CopyOnWriteArrayList<>();

    private LocalCommandBus newCommandBus(CommandBusInterceptor... interceptors) {
        var all = new ArrayList<CommandBusInterceptor>();
        all.add(outcome);
        all.addAll(List.of(interceptors));
        var commandBus = new LocalCommandBus((exception, command, handler) -> errorHandlerCalls.add(exception), all);
        commandBus.addCommandHandler(commandHandler);
        return commandBus;
    }

    @ParameterizedTest
    @EnumSource(SendMode.class)
    void an_interceptor_that_replaces_the_command_hands_the_replacement_to_the_handler(SendMode sendMode) {
        var commandBus = newCommandBus(new Order1Interceptor(command -> new PlaceOrder("decrypted:" + ((PlaceOrder) command).value())));

        dispatch(commandBus, new PlaceOrder("secret"), sendMode);

        assertThat(commandHandler.received).containsExactly(new PlaceOrder("decrypted:secret"));
        assertThat(outcome.failures).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SendMode.class)
    void two_interceptors_replace_in_InterceptorOrder_and_the_second_sees_the_first_ones_replacement(SendMode sendMode) {
        var second = new Order2Interceptor(command -> new PlaceOrder(((PlaceOrder) command).value() + "-second"));
        var first  = new Order1Interceptor(command -> new PlaceOrder(((PlaceOrder) command).value() + "-first"));
        // Added in reverse: @InterceptorOrder decides
        var commandBus = newCommandBus(second, first);

        dispatch(commandBus, new PlaceOrder("x"), sendMode);

        assertThat(first.receivedCommandParameter).containsExactly(new PlaceOrder("x"));
        assertThat(second.receivedCommandParameter).containsExactly(new PlaceOrder("x-first"));
        assertThat(second.receivedChainCommand).containsExactly(new PlaceOrder("x-first"));
        assertThat(commandHandler.received).containsExactly(new PlaceOrder("x-first-second"));
    }

    @ParameterizedTest
    @EnumSource(SendMode.class)
    void an_intermediate_command_of_another_type_is_fine_when_a_later_interceptor_restores_one_the_handler_accepts(SendMode sendMode) {
        var first  = new Order1Interceptor(command -> ("raw:" + ((PlaceOrder) command).value()).getBytes());
        var second = new Order2Interceptor(command -> new PlaceOrder(new String((byte[]) command)));
        var commandBus = newCommandBus(first, second);

        dispatch(commandBus, new PlaceOrder("x"), sendMode);

        assertThat(second.receivedCommandParameter).singleElement().isInstanceOf(byte[].class);
        assertThat(commandHandler.received).containsExactly(new PlaceOrder("raw:x"));
        assertThat(outcome.failures).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(SendMode.class)
    void a_command_the_matched_handler_cannot_handle_fails_with_ClassCastException_and_the_handler_is_not_re_resolved(SendMode sendMode) {
        var otherHandler = new CommandHandler() {
            @Override
            public boolean canHandle(Class<?> commandType) {
                return String.class.equals(commandType);
            }

            @Override
            public Object handle(Object command) {
                throw new AssertionError("The handler must not be re-resolved");
            }
        };
        var commandBus = newCommandBus(new Order1Interceptor(command -> "not a PlaceOrder"));
        commandBus.addCommandHandler(otherHandler);

        dispatch(commandBus, new PlaceOrder("x"), sendMode);

        assertThat(commandHandler.received).isEmpty();
        assertThat(outcome.failures).hasSize(1);
        assertThat(outcome.failures.get(0)).isInstanceOf(ClassCastException.class)
                                    .hasMessageContaining(String.class.getName())
                                    .hasMessageContaining(commandHandler.toString())
                                    .hasMessageContaining(PlaceOrder.class.getName());
        // A replacement that doesn't fit is not a command handler failure (for sendAndDontWait the error handler would run after the chain unwound)
        Awaitility.await().during(Duration.ofMillis(200)).atMost(WAIT).untilAsserted(() -> assertThat(errorHandlerCalls).isEmpty());
    }

    @Test
    void the_default_chain_rejects_a_null_command() {
        var chain = CommandBusInterceptorChain.newInterceptorChain(new PlaceOrder("x"), commandHandler, List.of(), (interceptor, c) -> null, command -> command);

        assertThatThrownBy(() -> chain.setCommand(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_default_chain_hands_the_replaced_command_to_the_default_behaviour() {
        var chain = CommandBusInterceptorChain.newInterceptorChain(new PlaceOrder("x"), commandHandler, List.of(), (interceptor, c) -> null, command -> command);

        chain.setCommand(new PlaceOrder("y"));

        assertThat(chain.command()).isEqualTo(new PlaceOrder("y"));
        assertThat(chain.proceed()).isEqualTo(new PlaceOrder("y"));
    }

    @Test
    void a_third_party_chain_without_setCommand_still_compiles_and_setCommand_throws_UnsupportedOperationException() {
        // Written against the interface as it was before setCommand existed
        CommandBusInterceptorChain thirdPartyChain = new CommandBusInterceptorChain() {
            @Override
            public Object proceed() {
                return null;
            }

            @Override
            public Object command() {
                return new PlaceOrder("x");
            }

            @Override
            public CommandHandler matchedCommandHandler() {
                return commandHandler;
            }
        };

        assertThatThrownBy(() -> thirdPartyChain.setCommand(new PlaceOrder("y"))).isInstanceOf(UnsupportedOperationException.class);
    }

    private void dispatch(LocalCommandBus commandBus, Object command, SendMode sendMode) {
        try {
            switch (sendMode) {
                case SEND -> commandBus.send(command);
                case SEND_ASYNC -> commandBus.sendAsync(command).block(WAIT);
                case SEND_AND_DONT_WAIT -> commandBus.sendAndDontWait(command);
            }
        } catch (RuntimeException e) {
            // The OutcomeRecorder saw it too
        }
        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(commandHandler.received.size() + outcome.failures.size()).isEqualTo(1));
    }

    private static class RecordingCommandHandler implements CommandHandler {
        private final List<Object> received = new CopyOnWriteArrayList<>();

        @Override
        public boolean canHandle(Class<?> commandType) {
            return PlaceOrder.class.isAssignableFrom(commandType);
        }

        @Override
        public Object handle(Object command) {
            received.add((PlaceOrder) command);
            return command;
        }

        @Override
        public String toString() {
            return "PlaceOrderCommandHandler";
        }
    }

    /**
     * Outermost interceptor: records what failed through the chain, for every send mode
     */
    @InterceptorOrder(0)
    private static class OutcomeRecorder implements CommandBusInterceptor {
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();

        @Override
        public Object interceptSend(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
            return recordFailure(commandBusInterceptorChain);
        }

        @Override
        public Object interceptSendAsync(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
            return recordFailure(commandBusInterceptorChain);
        }

        @Override
        public void interceptSendAndDontWait(Object commandMessage, CommandBusInterceptorChain commandBusInterceptorChain) {
            recordFailure(commandBusInterceptorChain);
        }

        private Object recordFailure(CommandBusInterceptorChain commandBusInterceptorChain) {
            try {
                return commandBusInterceptorChain.proceed();
            } catch (RuntimeException e) {
                failures.add(e);
                throw e;
            }
        }
    }

    private static abstract class ReplacingInterceptor implements CommandBusInterceptor {
        private final Function<Object, Object> replacement;
        final         List<Object>             receivedCommandParameter = new CopyOnWriteArrayList<>();
        final         List<Object>             receivedChainCommand     = new CopyOnWriteArrayList<>();

        ReplacingInterceptor(Function<Object, Object> replacement) {
            this.replacement = replacement;
        }

        @Override
        public Object interceptSend(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
            return replaceAndProceed(command, commandBusInterceptorChain);
        }

        @Override
        public Object interceptSendAsync(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
            return replaceAndProceed(command, commandBusInterceptorChain);
        }

        @Override
        public void interceptSendAndDontWait(Object commandMessage, CommandBusInterceptorChain commandBusInterceptorChain) {
            replaceAndProceed(commandMessage, commandBusInterceptorChain);
        }

        private Object replaceAndProceed(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
            receivedCommandParameter.add(command);
            receivedChainCommand.add(commandBusInterceptorChain.command());
            commandBusInterceptorChain.setCommand(replacement.apply(commandBusInterceptorChain.command()));
            return commandBusInterceptorChain.proceed();
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
