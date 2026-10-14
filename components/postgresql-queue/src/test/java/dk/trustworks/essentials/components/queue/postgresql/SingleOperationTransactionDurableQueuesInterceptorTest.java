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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.HandleQueuedMessage;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkStatus;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.functional.CheckedFunction;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The in-transaction acknowledgement {@link SingleOperationTransactionDurableQueuesInterceptor} makes after the handler
 * returns, and when it leaves the acknowledgement to the consumer instead
 */
class SingleOperationTransactionDurableQueuesInterceptorTest {
    private static final QueueEntryId MESSAGE_ID = QueueEntryId.of("message-1");

    private DurableQueues                                      durableQueues;
    private HandleAwareUnitOfWork                              unitOfWork;
    private SingleOperationTransactionDurableQueuesInterceptor interceptor;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        durableQueues = mock(DurableQueues.class);
        when(durableQueues.acknowledgeMessageAsHandled(MESSAGE_ID)).thenReturn(true);
        unitOfWork = mock(HandleAwareUnitOfWork.class);
        when(unitOfWork.status()).thenReturn(UnitOfWorkStatus.Started);
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> unitOfWorkFactory = mock(HandleAwareUnitOfWorkFactory.class);
        when(unitOfWorkFactory.withUnitOfWork(any(CheckedFunction.class)))
                .thenAnswer(invocation -> ((CheckedFunction<HandleAwareUnitOfWork, Object>) invocation.getArgument(0)).apply(unitOfWork));
        interceptor = new SingleOperationTransactionDurableQueuesInterceptor(unitOfWorkFactory);
        interceptor.setDurableQueues(durableQueues);
    }

    @AfterEach
    void clearInterruptFlag() {
        // The handler under test may leave the flag set on the JUnit thread
        Thread.interrupted();
    }

    @Test
    void a_handler_that_returns_normally_is_acknowledged_inside_its_transaction() {
        var operation = handleQueuedMessage();

        interceptor.intercept(operation, handlerChain(() -> {
        }));

        assertThat(operation.getAcknowledgedWithHandler()).isTrue();
        verify(durableQueues).acknowledgeMessageAsHandled(MESSAGE_ID);
    }

    @Test
    void a_handler_that_returns_while_interrupted_leaves_the_acknowledgement_to_the_consumer() {
        var operation = handleQueuedMessage();

        // Like a handler that caught a stop()'s interrupt, re-set the flag and returned without finishing its work
        interceptor.intercept(operation, handlerChain(() -> Thread.currentThread().interrupt()));

        assertThat(operation.getAcknowledgedWithHandler()).as("the consumer decides: acknowledge, or hand back when it is stopping").isNull();
        verify(durableQueues, never()).acknowledgeMessageAsHandled(any(QueueEntryId.class));
    }

    private static HandleQueuedMessage handleQueuedMessage() {
        var message = mock(QueuedMessage.class);
        when(message.getId()).thenReturn(MESSAGE_ID);
        return new HandleQueuedMessage(message, mock(QueuedMessageHandler.class));
    }

    @SuppressWarnings("unchecked")
    private static InterceptorChain<HandleQueuedMessage, Void, DurableQueuesInterceptor> handlerChain(Runnable handler) {
        InterceptorChain<HandleQueuedMessage, Void, DurableQueuesInterceptor> chain = mock(InterceptorChain.class);
        when(chain.proceed()).thenAnswer(invocation -> {
            handler.run();
            return null;
        });
        return chain;
    }
}
