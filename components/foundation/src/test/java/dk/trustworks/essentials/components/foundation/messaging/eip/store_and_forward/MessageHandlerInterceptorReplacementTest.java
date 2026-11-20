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

package dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward;

import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.operation.InvokeMessageHandlerMethod;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.MessageDeliveryDecision.MessageDeliveryRule;
import dk.trustworks.essentials.components.foundation.messaging.test_data.*;
import dk.trustworks.essentials.shared.interceptor.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract test for replacing the {@link Message} a {@link MessageHandlerInterceptor} intercepts, through
 * {@link InvokeMessageHandlerMethod#setMessage(Message)}: what reaches the handler method is what the last interceptor
 * set, and a value that does not fit the (already resolved) handler method fails with a {@link ClassCastException}.
 */
class MessageHandlerInterceptorReplacementTest {

    @Test
    void the_handler_method_receives_the_payload_an_interceptor_replaced() {
        // Given
        var replacement = new SomeCommand("replaced");
        var handler     = new RecordingMessageHandler();
        handler.addInterceptor(new OrderOne(operation -> operation.getMessage().withPayload(replacement)));

        // When
        handler.accept(Message.of(new SomeCommand("original")));

        // Then
        assertThat(handler.someCommand).isSameAs(replacement);
    }

    @Test
    void the_handler_method_receives_the_message_an_interceptor_replaced() {
        // Given
        var replacementPayload = new SomeOtherCommand("replaced");
        var handler            = new RecordingMessageHandler();
        handler.addInterceptor(new OrderOne(operation -> operation.getMessage().withPayload(replacementPayload)));
        var dispatched = Message.of(new SomeOtherCommand("original"), MessageMetaData.of("traceId", "trace-1"));

        // When
        handler.accept(dispatched);

        // Then
        assertThat(handler.someOtherCommand).isSameAs(replacementPayload);
        assertThat(handler.messageForSomeOtherCommand).isNotSameAs(dispatched);
        assertThat(handler.messageForSomeOtherCommand.getPayload()).isSameAs(replacementPayload);
        assertThat(handler.messageForSomeOtherCommand.getMetaData()).isEqualTo(dispatched.getMetaData());
    }

    @Test
    void an_ordered_message_stays_ordered_when_an_interceptor_replaces_its_payload() {
        // Given
        var replacementPayload = new SomeThirdCommand("replaced");
        var handler            = new RecordingMessageHandler();
        handler.addInterceptor(new OrderOne(operation -> operation.getMessage().withPayload(replacementPayload)));

        // When
        handler.accept(OrderedMessage.of(new SomeThirdCommand("original"), "key-1", 7));

        // Then
        assertThat(handler.someThirdCommand).isSameAs(replacementPayload);
        assertThat(handler.messageForSomeThirdCommand.getPayload()).isSameAs(replacementPayload);
        assertThat(handler.messageForSomeThirdCommand.getKey()).isEqualTo("key-1");
        assertThat(handler.messageForSomeThirdCommand.getOrder()).isEqualTo(7);
    }

    @Test
    void two_replacing_interceptors_run_in_interceptor_order_and_the_handler_sees_the_second_replacement() {
        // Given
        var firstReplacement  = new SomeCommand("first");
        var secondReplacement = new SomeCommand("second");
        var first             = new OrderOne(operation -> operation.getMessage().withPayload(firstReplacement));
        var second            = new OrderTwo(operation -> operation.getMessage().withPayload(secondReplacement));
        var handler           = new RecordingMessageHandler();
        // Added in reverse order: @InterceptorOrder decides, not the order of registration
        handler.addInterceptor(second);
        handler.addInterceptor(first);

        // When
        var dispatched = new SomeCommand("original");
        handler.accept(Message.of(dispatched));

        // Then
        assertThat(first.payloadSeen).isSameAs(dispatched);
        assertThat(second.payloadSeen).isSameAs(firstReplacement);
        assertThat(handler.someCommand).isSameAs(secondReplacement);
    }

    @Test
    void interceptors_passed_to_the_constructor_also_run_in_interceptor_order() {
        // Given
        var firstReplacement  = new SomeCommand("first");
        var secondReplacement = new SomeCommand("second");
        var first             = new OrderOne(operation -> operation.getMessage().withPayload(firstReplacement));
        var second            = new OrderTwo(operation -> operation.getMessage().withPayload(secondReplacement));
        var handlerMethods    = new HandlerMethods();
        var handler           = new PatternMatchingMessageHandler(handlerMethods, List.of(second, first));

        // When
        handler.accept(Message.of(new SomeCommand("original")));

        // Then
        assertThat(second.payloadSeen).isSameAs(firstReplacement);
        assertThat(handlerMethods.someCommand).isSameAs(secondReplacement);
    }

    @Test
    void an_intermediate_interceptor_may_change_the_payload_type_when_a_later_one_restores_the_dispatched_type() {
        // Given
        var toBytes   = new OrderOne(operation -> operation.getMessage().withPayload(((SomeCommand) operation.getPayload()).someValue.getBytes(StandardCharsets.UTF_8)));
        var observer  = new OrderTwo(InvokeMessageHandlerMethod::getMessage);
        var fromBytes = new OrderThree(operation -> operation.getMessage().withPayload(new SomeCommand(new String((byte[]) operation.getPayload(), StandardCharsets.UTF_8) + "-restored")));
        var handler   = new RecordingMessageHandler();
        handler.addInterceptor(fromBytes);
        handler.addInterceptor(observer);
        handler.addInterceptor(toBytes);

        // When
        handler.accept(Message.of(new SomeCommand("value")));

        // Then
        assertThat(observer.payloadSeen).isInstanceOf(byte[].class);
        assertThat(handler.someCommand).isNotNull();
        assertThat(handler.someCommand.someValue).isEqualTo("value-restored");
    }

    @Test
    void a_payload_of_the_wrong_type_reaching_the_handler_method_fails_with_a_ClassCastException_naming_both_types_and_the_method() {
        // Given
        var handler = new RecordingMessageHandler();
        handler.addInterceptor(new OrderOne(operation -> operation.getMessage().withPayload("not-a-command".getBytes(StandardCharsets.UTF_8))));

        // When / Then
        assertThatThrownBy(() -> handler.accept(Message.of(new SomeCommand("original"))))
                .isInstanceOf(ClassCastException.class)
                .hasMessageContaining(byte[].class.getName())
                .hasMessageContaining(SomeCommand.class.getName())
                .hasMessageContaining("handleSomeCommand");
        assertThat(handler.someCommand).isNull();
    }

    @Test
    void a_plain_message_replacing_an_ordered_message_for_a_handler_that_takes_an_OrderedMessage_fails_with_a_ClassCastException() {
        // Given
        var handler = new RecordingMessageHandler();
        handler.addInterceptor(new OrderOne(operation -> Message.of(operation.getPayload(), operation.getMessage().getMetaData())));

        // When / Then
        assertThatThrownBy(() -> handler.accept(OrderedMessage.of(new SomeThirdCommand("original"), "key-1", 7)))
                .isInstanceOf(ClassCastException.class)
                .hasMessageContaining(Message.class.getName())
                .hasMessageContaining(OrderedMessage.class.getName())
                .hasMessageContaining("handleSomeThirdCommand");
        assertThat(handler.someThirdCommand).isNull();
    }

    @Test
    void the_ClassCastException_dead_letters_the_message_on_its_first_delivery_attempt() {
        // Given
        var handler = new RecordingMessageHandler();
        handler.addInterceptor(new OrderOne(operation -> operation.getMessage().withPayload("not-a-command".getBytes(StandardCharsets.UTF_8))));
        var error = catchThrowable(() -> handler.accept(Message.of(new SomeCommand("original"))));
        assertThat(error).isInstanceOf(ClassCastException.class);
        // A queue consumer usually sees the handler's exception wrapped (e.g. by the UnitOfWork); the whole cause chain is classified
        var wrapped = new RuntimeException("Handler failed", error);

        // When
        var decision        = MessageDeliveryClassifier.classify(queuedMessageOnFirstDeliveryAttempt(), error, RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), 5));
        var wrappedDecision = MessageDeliveryClassifier.classify(queuedMessageOnFirstDeliveryAttempt(), wrapped, RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), 5));

        // Then
        assertThat(decision.isDeadLetter()).isTrue();
        assertThat(decision.outcome()).isEqualTo(MessageDeliveryOutcome.PERMANENT_ERROR);
        assertThat(decision.rule()).isEqualTo(MessageDeliveryRule.BUILT_IN_PERMANENT_LIST);
        assertThat(wrappedDecision.outcome()).isEqualTo(MessageDeliveryOutcome.PERMANENT_ERROR);
        assertThat(wrappedDecision.rule()).isEqualTo(MessageDeliveryRule.BUILT_IN_PERMANENT_LIST);
    }

    @Test
    void the_ClassCastException_is_retried_when_the_policy_always_retries_on_ClassCastException() {
        // Given
        var handler = new RecordingMessageHandler();
        handler.addInterceptor(new OrderOne(operation -> operation.getMessage().withPayload("not-a-command".getBytes(StandardCharsets.UTF_8))));
        var error = catchThrowable(() -> handler.accept(Message.of(new SomeCommand("original"))));
        var policy = RedeliveryPolicy.builder()
                                     .setInitialRedeliveryDelay(Duration.ofMillis(10))
                                     .setFollowupRedeliveryDelay(Duration.ofMillis(10))
                                     .setFollowupRedeliveryDelayMultiplier(1.0d)
                                     .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofMillis(10))
                                     .setMaximumNumberOfRedeliveries(5)
                                     .setDeliveryErrorHandler(MessageDeliveryErrorHandler.builder().alwaysRetryOn(ClassCastException.class).build())
                                     .build();

        // When
        var decision = MessageDeliveryClassifier.classify(queuedMessageOnFirstDeliveryAttempt(), error, policy);

        // Then
        assertThat(decision.outcome()).isEqualTo(MessageDeliveryOutcome.RETRY);
        assertThat(decision.rule()).isEqualTo(MessageDeliveryRule.POLICY_RETRY_OVERRIDE);
    }

    @Test
    void setMessage_sets_message_and_payload_together_and_rejects_null() {
        // Given
        var original  = Message.of(new SomeCommand("original"));
        var operation = new InvokeMessageHandlerMethod(RecordingMessageHandler.handleSomeCommandMethod(), original, original.getPayload(), new Object(), SomeCommand.class);
        var replaced  = original.withPayload(42);

        // When
        operation.setMessage(replaced);

        // Then
        assertThat(operation.getMessage()).isSameAs(replaced);
        assertThat(operation.getPayload()).isEqualTo(42);
        assertThat(operation.message).isSameAs(replaced);
        assertThat(operation.messagPayload).isEqualTo(42);
        assertThatThrownBy(() -> operation.setMessage(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(operation.getMessage()).isSameAs(replaced);
    }

    private static QueuedMessage queuedMessageOnFirstDeliveryAttempt() {
        return DefaultQueuedMessage.builder()
                                   .setId(QueueEntryId.random())
                                   .setQueueName(QueueName.of("TestQueue"))
                                   .setMessage(Message.of(new SomeCommand("original")))
                                   .setAddedTimestamp(OffsetDateTime.now())
                                   .setNextDeliveryTimestamp(OffsetDateTime.now())
                                   .setDeliveryTimestamp(OffsetDateTime.now())
                                   .setTotalDeliveryAttempts(1)
                                   .setRedeliveryAttempts(0)
                                   .build();
    }

    // ------------------------------------------------------------------------------------------------

    private static class RecordingMessageHandler extends PatternMatchingMessageHandler {
        private SomeCommand      someCommand;
        private SomeOtherCommand someOtherCommand;
        private Message          messageForSomeOtherCommand;
        private SomeThirdCommand someThirdCommand;
        private OrderedMessage   messageForSomeThirdCommand;

        static Method handleSomeCommandMethod() {
            try {
                return RecordingMessageHandler.class.getDeclaredMethod("handleSomeCommand", SomeCommand.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        @MessageHandler
        void handleSomeCommand(SomeCommand someCommand) {
            this.someCommand = someCommand;
        }

        @MessageHandler
        void handleSomeOtherCommand(SomeOtherCommand someOtherCommand, Message message) {
            this.someOtherCommand = someOtherCommand;
            this.messageForSomeOtherCommand = message;
        }

        @MessageHandler
        void handleSomeThirdCommand(SomeThirdCommand someThirdCommand, OrderedMessage message) {
            this.someThirdCommand = someThirdCommand;
            this.messageForSomeThirdCommand = message;
        }
    }

    private static class HandlerMethods {
        private SomeCommand someCommand;

        @MessageHandler
        void handleSomeCommand(SomeCommand someCommand) {
            this.someCommand = someCommand;
        }
    }

    /**
     * Records the payload it was handed, then replaces the message with what {@code replacer} returns and proceeds
     */
    private abstract static class ReplacingInterceptor implements MessageHandlerInterceptor {
        private final Function<InvokeMessageHandlerMethod, Message> replacer;
        Object payloadSeen;

        ReplacingInterceptor(Function<InvokeMessageHandlerMethod, Message> replacer) {
            this.replacer = replacer;
        }

        @Override
        public void intercept(InvokeMessageHandlerMethod operation, InterceptorChain<InvokeMessageHandlerMethod, Void, MessageHandlerInterceptor> interceptorChain) {
            payloadSeen = operation.getPayload();
            operation.setMessage(replacer.apply(operation));
            interceptorChain.proceed();
        }
    }

    @InterceptorOrder(1)
    private static class OrderOne extends ReplacingInterceptor {
        OrderOne(Function<InvokeMessageHandlerMethod, Message> replacer) {
            super(replacer);
        }
    }

    @InterceptorOrder(2)
    private static class OrderTwo extends ReplacingInterceptor {
        OrderTwo(Function<InvokeMessageHandlerMethod, Message> replacer) {
            super(replacer);
        }
    }

    @InterceptorOrder(3)
    private static class OrderThree extends ReplacingInterceptor {
        OrderThree(Function<InvokeMessageHandlerMethod, Message> replacer) {
            super(replacer);
        }
    }
}
