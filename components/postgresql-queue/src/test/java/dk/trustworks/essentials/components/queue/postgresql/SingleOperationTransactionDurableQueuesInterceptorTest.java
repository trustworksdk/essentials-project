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

import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

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
    void a_handler_that_returns_while_interrupted_by_its_consumer_stopping_has_its_writes_rolled_back_and_leaves_the_message_to_the_consumer() {
        var consumerStarted = new AtomicBoolean(true);
        var operation       = handleQueuedMessage(consumerStarted::get);

        // Like a handler that stop() interrupted, which caught it, re-set the flag and returned without finishing its work.
        // stop() marks the consumer as no longer started before it interrupts the delivery threads
        interceptor.intercept(operation, handlerChain(() -> {
            consumerStarted.set(false);
            Thread.currentThread().interrupt();
        }));

        assertThat(operation.getAcknowledgedWithHandler()).as("the consumer hands the message back").isNull();
        verify(durableQueues, never()).acknowledgeMessageAsHandled(any(QueueEntryId.class));
        verify(unitOfWork).markAsRollbackOnly(any(InterruptedException.class));
        assertThat(Thread.currentThread().isInterrupted()).as("the consumer still sees the stop's interrupt").isTrue();
    }

    @Test
    void a_stop_beginning_after_the_started_check_still_lands_in_the_started_branch() {
        // The consumer is started when the interceptor first asks, and stopping from then on. Started is asked first, so
        // the answer is "started": acknowledged. Asked the other way round, the operation would read "not stopping", then
        // "not started", and fall through to the unknown-state branch for a consumer whose state is known
        var asked     = new AtomicInteger();
        var operation = handleQueuedMessage(() -> asked.getAndIncrement() == 0);

        interceptor.intercept(operation, handlerChain(() -> Thread.currentThread().interrupt()));

        assertThat(operation.getAcknowledgedWithHandler()).as("acknowledged on the first answer, started").isTrue();
        verify(durableQueues).acknowledgeMessageAsHandled(MESSAGE_ID);
        verify(unitOfWork, never()).markAsRollbackOnly(any(Throwable.class));
        assertThat(asked.get()).as("the consumer state is asked once").isEqualTo(1);
    }

    @Test
    void a_handler_that_marked_its_unit_of_work_rollback_only_and_returns_while_interrupted_by_its_consumer_stopping_is_left_as_it_is() {
        when(unitOfWork.status()).thenReturn(UnitOfWorkStatus.MarkedForRollbackOnly);
        var operation = handleQueuedMessage(() -> false);

        interceptor.intercept(operation, handlerChain(() -> Thread.currentThread().interrupt()));

        assertThat(operation.getAcknowledgedWithHandler()).isNull();
        verify(durableQueues, never()).acknowledgeMessageAsHandled(any(QueueEntryId.class));
        verify(unitOfWork, never()).markAsRollbackOnly(any(Throwable.class));
    }

    @Test
    void a_handler_that_returns_while_interrupted_while_its_consumer_is_running_is_acknowledged_inside_its_transaction() {
        var operation         = handleQueuedMessage(() -> true);
        var flagDuringAckRef  = new AtomicReference<Boolean>();
        when(durableQueues.acknowledgeMessageAsHandled(MESSAGE_ID)).thenAnswer(invocation -> {
            flagDuringAckRef.set(Thread.currentThread().isInterrupted());
            return true;
        });

        // A handler that re-set the flag for a reason of its own; nothing is stopping
        interceptor.intercept(operation, handlerChain(() -> Thread.currentThread().interrupt()));

        assertThat(operation.getAcknowledgedWithHandler()).as("acknowledged in the handler's transaction, committing with its writes").isTrue();
        verify(durableQueues).acknowledgeMessageAsHandled(MESSAGE_ID);
        assertThat(flagDuringAckRef.get()).as("the acknowledgement is written with the interrupt flag cleared").isFalse();
        verify(unitOfWork, never()).markAsRollbackOnly(any(Throwable.class));
        assertThat(Thread.currentThread().isInterrupted()).as("the handler's interrupt flag is restored afterwards").isTrue();
    }

    @Test
    void a_handler_that_returns_while_interrupted_when_the_consumer_state_is_unknown_leaves_the_acknowledgement_to_the_consumer() {
        // Built without a consumer state, e.g. by hand
        var operation = handleQueuedMessage(null);

        interceptor.intercept(operation, handlerChain(() -> Thread.currentThread().interrupt()));

        assertThat(operation.getAcknowledgedWithHandler()).as("cannot tell a stop's interrupt from any other: the consumer decides").isNull();
        verify(durableQueues, never()).acknowledgeMessageAsHandled(any(QueueEntryId.class));
        verify(unitOfWork, never()).markAsRollbackOnly(any(Throwable.class));
    }

    @Test
    void a_handler_that_returns_with_the_flag_clear_while_its_consumer_is_stopping_is_acknowledged_inside_its_transaction() {
        var operation = handleQueuedMessage(() -> false);

        interceptor.intercept(operation, handlerChain(() -> {
        }));

        assertThat(operation.getAcknowledgedWithHandler()).isTrue();
        verify(durableQueues).acknowledgeMessageAsHandled(MESSAGE_ID);
    }

    private static HandleQueuedMessage handleQueuedMessage() {
        return handleQueuedMessage(() -> true);
    }

    private static HandleQueuedMessage handleQueuedMessage(BooleanSupplier consumerStarted) {
        var message = mock(QueuedMessage.class);
        when(message.getId()).thenReturn(MESSAGE_ID);
        return HandleQueuedMessage.builder()
                                  .setMessage(message)
                                  .setMessageHandler(mock(QueuedMessageHandler.class))
                                  .setConsumerStarted(consumerStarted)
                                  .build();
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
