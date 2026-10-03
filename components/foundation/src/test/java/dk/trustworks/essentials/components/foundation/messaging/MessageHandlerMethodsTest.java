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

package dk.trustworks.essentials.components.foundation.messaging;

import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UnitOfWorkMode#NONE} is declared on the {@link MessageHandler} annotated methods, so whether a message
 * consumer needs a {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}-free window is
 * introspected rather than reported by hand - the only case that has to be written out is a consumer whose handler
 * methods live on another object.
 */
class MessageHandlerMethodsTest {

    @Test
    void declaresNonTransactionalMessageHandlers_introspects_the_MessageHandler_annotated_methods() {
        assertThat(MessageHandlerMethods.declaresNonTransactionalMessageHandlers(new HandlersWithABlockingHandler())).isTrue();
        assertThat(MessageHandlerMethods.declaresNonTransactionalMessageHandlers(new OnlyTransactionalHandlers())).isFalse();
        assertThat(MessageHandlerMethods.declaresNonTransactionalMessageHandlers(new NoHandlersAtAll())).isFalse();
    }

    @Test
    void a_boundary_owning_consumer_carrying_its_own_handlers_needs_no_override() {
        assertThat(new SelfHostingConsumer().hasNonTransactionalMessageHandlers()).isTrue();
        assertThat(new SelfHostingTransactionalOnlyConsumer().hasNonTransactionalMessageHandlers()).isFalse();
    }

    @Test
    void a_boundary_owning_consumer_that_delegates_answers_for_its_delegate() {
        // The wrapper declares no @MessageHandler methods of its own, so introspecting it would answer 'false' - which
        // is why a delegating consumer has to override the method
        assertThat(new DelegatingConsumer(new PatternMatchingMessageHandler(new HandlersWithABlockingHandler())).hasNonTransactionalMessageHandlers()).isTrue();
        assertThat(new DelegatingConsumer(new PatternMatchingMessageHandler(new OnlyTransactionalHandlers())).hasNonTransactionalMessageHandlers()).isFalse();
    }

    @Test
    void hasNonTransactionalMessageHandlers_resolves_any_kind_of_message_consumer() {
        // Asks the consumer, since it may be a wrapper around the object carrying the handler methods
        assertThat(MessageHandlerMethods.hasNonTransactionalMessageHandlers(new DelegatingConsumer(new PatternMatchingMessageHandler(new HandlersWithABlockingHandler())))).isTrue();
        assertThat(MessageHandlerMethods.hasNonTransactionalMessageHandlers(new SelfHostingConsumer())).isTrue();
        // A PatternMatchingMessageHandler answers for its handler target
        assertThat(MessageHandlerMethods.hasNonTransactionalMessageHandlers(new PatternMatchingMessageHandler(new HandlersWithABlockingHandler()))).isTrue();
        assertThat(MessageHandlerMethods.hasNonTransactionalMessageHandlers(new PatternMatchingMessageHandler(new OnlyTransactionalHandlers()))).isFalse();
        // Anything else is introspected directly
        assertThat(MessageHandlerMethods.hasNonTransactionalMessageHandlers(new PlainConsumerWithABlockingHandler())).isTrue();
        assertThat(MessageHandlerMethods.hasNonTransactionalMessageHandlers(message -> {
        })).isFalse();
    }

    @Test
    void resolveUnitOfWorkMode_defaults_to_REQUIRED() throws NoSuchMethodException {
        assertThat(MessageHandlerMethods.resolveUnitOfWorkMode(HandlersWithABlockingHandler.class.getDeclaredMethod("on", BlockingIOEvent.class)))
                .isEqualTo(UnitOfWorkMode.NONE);
        assertThat(MessageHandlerMethods.resolveUnitOfWorkMode(HandlersWithABlockingHandler.class.getDeclaredMethod("on", TransactionalEvent.class)))
                .isEqualTo(UnitOfWorkMode.REQUIRED);
        // Not a @MessageHandler annotated method at all
        assertThat(MessageHandlerMethods.resolveUnitOfWorkMode(NoHandlersAtAll.class.getDeclaredMethod("notAHandler", BlockingIOEvent.class)))
                .isEqualTo(UnitOfWorkMode.REQUIRED);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Test payloads and handler targets
    // ------------------------------------------------------------------------------------------------------------

    record TransactionalEvent() {
    }

    record BlockingIOEvent() {
    }

    static class HandlersWithABlockingHandler {
        @MessageHandler
        void on(TransactionalEvent e) {
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(BlockingIOEvent e) {
        }
    }

    static class OnlyTransactionalHandlers {
        @MessageHandler
        void on(TransactionalEvent e) {
        }
    }

    static class NoHandlersAtAll {
        void notAHandler(BlockingIOEvent e) {
        }
    }

    /**
     * A consumer that carries its {@literal @MessageHandler} methods itself - the introspecting default applies
     */
    static class SelfHostingConsumer implements UnitOfWorkBoundaryOwningMessageConsumer {
        @Override
        public void accept(Message message) {
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(BlockingIOEvent e) {
        }
    }

    static class SelfHostingTransactionalOnlyConsumer implements UnitOfWorkBoundaryOwningMessageConsumer {
        @Override
        public void accept(Message message) {
        }

        @MessageHandler
        void on(TransactionalEvent e) {
        }
    }

    /**
     * Stand-in for the consumer an {@code EventProcessor} hands to its {@link Inbox}: the handler methods live on
     * another object, so the introspecting default has to be overridden
     */
    record DelegatingConsumer(PatternMatchingMessageHandler delegate) implements UnitOfWorkBoundaryOwningMessageConsumer {
        @Override
        public void accept(Message message) {
            delegate.accept(message);
        }

        @Override
        public boolean hasNonTransactionalMessageHandlers() {
            return delegate.hasNonTransactionalMessageHandlers();
        }
    }

    /**
     * Neither a {@link UnitOfWorkBoundaryOwningMessageConsumer} nor a {@link PatternMatchingMessageHandler}
     */
    static class PlainConsumerWithABlockingHandler implements java.util.function.Consumer<Message> {
        @Override
        public void accept(Message message) {
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(BlockingIOEvent e) {
        }
    }
}
