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
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkStatus;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Interceptor that wraps each operation in a transaction.
 * Wired unconditionally: since 0.60 every queue operation runs in its own transaction.
 */
public class SingleOperationTransactionDurableQueuesInterceptor implements DurableQueuesInterceptor {
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private       DurableQueues                                                 durableQueues;

    /**
     * Creates a new SingleOperationTransactionDurableQueuesInterceptor.
     *
     * @param unitOfWorkFactory the unit of work factory to use for transactions
     */
    public SingleOperationTransactionDurableQueuesInterceptor(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
    }

    @Override
    public void setDurableQueues(DurableQueues durableQueues) {
        this.durableQueues = requireNonNull(durableQueues, "No durableQueues instance provided");
    }

    @Override
    public Optional<QueuedMessage> intercept(GetDeadLetterMessage operation, InterceptorChain<GetDeadLetterMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public Optional<QueuedMessage> intercept(GetQueuedMessage operation, InterceptorChain<GetQueuedMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public QueueEntryId intercept(QueueMessage operation, InterceptorChain<QueueMessage, QueueEntryId, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public QueueEntryId intercept(QueueMessageAsDeadLetterMessage operation, InterceptorChain<QueueMessageAsDeadLetterMessage, QueueEntryId, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public List<QueueEntryId> intercept(QueueMessages operation, InterceptorChain<QueueMessages, List<QueueEntryId>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public Optional<QueuedMessage> intercept(RetryMessage operation, InterceptorChain<RetryMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public Optional<QueuedMessage> intercept(ReleaseUndeliveredMessage operation, InterceptorChain<ReleaseUndeliveredMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public Optional<QueuedMessage> intercept(MarkAsDeadLetterMessage operation, InterceptorChain<MarkAsDeadLetterMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public Optional<QueuedMessage> intercept(ResurrectDeadLetterMessage operation, InterceptorChain<ResurrectDeadLetterMessage, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public boolean intercept(AcknowledgeMessageAsHandled operation, InterceptorChain<AcknowledgeMessageAsHandled, Boolean, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public Void intercept(HandleQueuedMessage operation, InterceptorChain<HandleQueuedMessage, Void, DurableQueuesInterceptor> interceptorChain) {
        if (operation.getMessageHandler() instanceof UnitOfWorkBoundaryOwningQueuedMessageHandler) {
            // The handler opens and commits its own UnitOfWork(s) per message, which is what allows part of its
            // message handling (typically a blocking call to an external system) to run with no UnitOfWork and
            // therefore no database connection held - see UnitOfWorkMode#NONE
            return interceptorChain.proceed();
        }
        return unitOfWorkFactory.withUnitOfWork(unitOfWork -> {
            interceptorChain.proceed();
            acknowledgeWithHandler(operation, unitOfWork);
            return null;
        });
    }

    /**
     * Acknowledge a successfully handled message inside the handler's own transaction, as its last statement.
     * <p>
     * The handler's writes and the acknowledgement then commit together, or not at all - before, the acknowledgement
     * was a second transaction on a second pooled connection, and a crash or a failed acknowledgement between the
     * two left the handler's writes committed and the message to be delivered again. It also saves a connection
     * checkout and a commit per message.
     * <p>
     * Left to the consumer's own, separate acknowledgement - as before - when the handler asked for redelivery, and
     * when the unit of work is marked rollback-only: the handler's writes are then discarded, and an acknowledgement
     * inside the same transaction would be discarded with them, turning a consumed message into one redelivered
     * after every {@code messageHandlingTimeout}.
     * <p>
     * A handler that returned with the thread's interrupt flag set is acknowledged here only while its consumer is
     * known to be started ({@link HandleQueuedMessage#isConsumerKnownToBeStarted()}): a consumer's {@code stop()}
     * marks it as stopping before it interrupts its delivery threads, so that handler re-set the flag for a reason of
     * its own and finished its work. The acknowledgement is then written with the flag cleared, as the consumer's own
     * would be (a connection pool may refuse an interrupted thread), and the flag is restored afterwards.
     * <p>
     * Left to the consumer when the handler returned with the flag set while its consumer is stopping: it may not
     * have finished its work, and the consumer hands the message back instead of acknowledging it. Acknowledging here
     * would delete the message before the consumer could decide. Also left to the consumer when the operation does
     * not know the consumer's state, as there a stop's interrupt cannot be told from any other.
     */
    private void acknowledgeWithHandler(HandleQueuedMessage operation, HandleAwareUnitOfWork unitOfWork) {
        var message = operation.getMessage();
        if (durableQueues == null || message.isManuallyMarkedForRedelivery() || unitOfWork.status() == UnitOfWorkStatus.MarkedForRollbackOnly) {
            return;
        }
        if (!Thread.currentThread().isInterrupted()) {
            operation.acknowledgedWithHandler(durableQueues.acknowledgeMessageAsHandled(message.getId()));
            return;
        }
        if (!operation.isConsumerKnownToBeStarted()) {
            return;
        }
        var wasInterrupted = Thread.interrupted();
        try {
            operation.acknowledgedWithHandler(durableQueues.acknowledgeMessageAsHandled(message.getId()));
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean intercept(DeleteMessage operation, InterceptorChain<DeleteMessage, Boolean, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public Optional<QueuedMessage> intercept(GetNextMessageReadyForDelivery operation, InterceptorChain<GetNextMessageReadyForDelivery, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public long intercept(GetTotalMessagesQueuedFor operation, InterceptorChain<GetTotalMessagesQueuedFor, Long, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public List<QueuedMessage> intercept(GetQueuedMessages operation, InterceptorChain<GetQueuedMessages, List<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public List<QueuedMessage> intercept(GetDeadLetterMessages operation, InterceptorChain<GetDeadLetterMessages, List<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }

    @Override
    public int intercept(PurgeQueue operation, InterceptorChain<PurgeQueue, Integer, DurableQueuesInterceptor> interceptorChain) {
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
    }
}