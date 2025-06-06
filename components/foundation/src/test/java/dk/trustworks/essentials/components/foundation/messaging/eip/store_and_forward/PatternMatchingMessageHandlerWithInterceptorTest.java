/*
 * Copyright 2021-2025 the original author or authors.
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

import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.operation.InvokeMessageHandlerMethod;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.test_data.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatternMatchingMessageHandlerWithInterceptorTest {
    @Test
    void test_queued_message_pattern_matching_with_1_parameter() {
        // Given
        var messageHandler = new TestPatternMatchingMessageHandler();
        var someCommand    = new SomeCommand("Test");

        // When
        messageHandler.accept(Message.of(someCommand));

        // Then
        assertThat(messageHandler.interceptor.operation).isNotNull();
        assertThat(messageHandler.interceptor.operation.methodToInvoke.getName()).isEqualTo("handleSomeCommand");
        assertThat(messageHandler.someCommand).isEqualTo(someCommand);
        assertThat(messageHandler.someOtherCommand).isNull();
        assertThat(messageHandler.messageForSomeOtherCommand).isNull();
        assertThat(messageHandler.someThirdCommand).isNull();
        assertThat(messageHandler.messageForSomeThirdCommand).isNull();
        assertThat(messageHandler.unmatchedMessage).isNull();


        assertThat(messageHandler.handlesMessageWithPayload(someCommand.getClass())).isTrue();
    }

    @Test
    void test_queued_message_pattern_matching_with_2_parameters() {
        // Given
        var messageHandler   = new TestPatternMatchingMessageHandler();
        var someOtherCommand = new SomeOtherCommand("Test");

        // When
        var message = Message.of(someOtherCommand);
        messageHandler.accept(message);

        // Then
        assertThat(messageHandler.interceptor.operation).isNotNull();
        assertThat(messageHandler.interceptor.operation.methodToInvoke.getName()).isEqualTo("handleSomeOtherCommand");

        assertThat(messageHandler.someCommand).isNull();
        assertThat(messageHandler.someOtherCommand).isEqualTo(someOtherCommand);
        assertThat(messageHandler.messageForSomeOtherCommand).isEqualTo(message);
        assertThat(messageHandler.someThirdCommand).isNull();
        assertThat(messageHandler.messageForSomeThirdCommand).isNull();

        assertThat(messageHandler.unmatchedMessage).isNull();

        assertThat(messageHandler.handlesMessageWithPayload(someOtherCommand.getClass())).isTrue();
    }

    @Test
    void test_queued_message_pattern_matching_with_OrderedMessage() {
        // Given
        var messageHandler   = new TestPatternMatchingMessageHandler();
        var someThirdCommand = new SomeThirdCommand("Test");

        // When
        var message = OrderedMessage.of(someThirdCommand, "key1", 10);
        messageHandler.accept(message);

        // Then
        assertThat(messageHandler.interceptor.operation).isNotNull();
        assertThat(messageHandler.interceptor.operation.methodToInvoke.getName()).isEqualTo("handleSomeThirdCommand");

        assertThat(messageHandler.someCommand).isNull();
        assertThat(messageHandler.someOtherCommand).isNull();
        assertThat(messageHandler.someThirdCommand).isEqualTo(someThirdCommand);
        assertThat(messageHandler.messageForSomeOtherCommand).isNull();
        assertThat(messageHandler.messageForSomeThirdCommand).isEqualTo(message);
        assertThat(messageHandler.unmatchedMessage).isNull();

        assertThat(messageHandler.handlesMessageWithPayload(someThirdCommand.getClass())).isTrue();
    }

    @Test
    void test_queued_message_pattern_matching_with_unmatched_message() {
        // Given
        var messageHandler       = new TestPatternMatchingMessageHandler();
        var someUnmatchedCommand = new SomeUnmatchedCommand("Test");

        // When
        var message = Message.of(someUnmatchedCommand);
        messageHandler.accept(message);

        // Then
        assertThat(messageHandler.interceptor.operation).isNull();

        assertThat(messageHandler.someCommand).isNull();
        assertThat(messageHandler.someOtherCommand).isNull();
        assertThat(messageHandler.messageForSomeOtherCommand).isNull();
        assertThat(messageHandler.someThirdCommand).isNull();
        assertThat(messageHandler.messageForSomeThirdCommand).isNull();
        assertThat(messageHandler.unmatchedMessage).isEqualTo(message);

        assertThat(messageHandler.handlesMessageWithPayload(someUnmatchedCommand.getClass())).isFalse();
    }

    private static class TestPatternMatchingMessageHandler extends PatternMatchingMessageHandler {
        private TestMessageHandlerInterceptor interceptor;
        private SomeCommand      someCommand;
        private SomeOtherCommand someOtherCommand;
        private Message          messageForSomeOtherCommand;
        private Message          unmatchedMessage;
        private SomeThirdCommand someThirdCommand;
        private OrderedMessage   messageForSomeThirdCommand;

        public TestPatternMatchingMessageHandler() {
            super();
            interceptor = new TestMessageHandlerInterceptor();
            addInterceptor(interceptor);
        }

        @MessageHandler
        void handleSomeCommand(SomeCommand someCommand) {
            this.someCommand = someCommand;
        }

        @MessageHandler
        void handleSomeOtherCommand(SomeOtherCommand someOtherCommand, Message messageForSomeOtherCommand) {
            this.someOtherCommand = someOtherCommand;
            this.messageForSomeOtherCommand = messageForSomeOtherCommand;
        }

        @MessageHandler
        void handleSomeThirdCommand(SomeThirdCommand someThirdCommand, OrderedMessage messageForSomeThirdCommand) {
            this.someThirdCommand = someThirdCommand;
            this.messageForSomeThirdCommand = messageForSomeThirdCommand;
        }

        @Override
        protected void handleUnmatchedMessage(Message message) {
            this.unmatchedMessage = message;
        }
    }

    private static class TestMessageHandlerInterceptor implements MessageHandlerInterceptor {
        private InvokeMessageHandlerMethod operation;

        @Override
        public void intercept(InvokeMessageHandlerMethod operation, InterceptorChain<InvokeMessageHandlerMethod, Void, MessageHandlerInterceptor> interceptorChain) {
            this.operation = operation;
            interceptorChain.proceed();
        }
    }
}