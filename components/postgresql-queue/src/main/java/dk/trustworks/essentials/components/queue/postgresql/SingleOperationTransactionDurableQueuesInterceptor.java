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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Interceptor that wraps each operation in a transaction.
 * This is used when the TransactionalMode is set to SingleOperationTransaction.
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
        return unitOfWorkFactory.withUnitOfWork(interceptorChain::proceed);
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