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
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;

import java.util.List;
import java.util.function.Consumer;

@SuppressWarnings("unchecked")
public final class PostgresqlDurableQueueConsumer extends DefaultDurableQueueConsumer<PostgresqlDurableQueues, HandleAwareUnitOfWork, UnitOfWorkFactory<HandleAwareUnitOfWork>> {

    /**
     * @param consumeFromQueue what to consume — the queue, consumer name, parallelism and redelivery policy
     * @param dependencies     what to consume it with — see {@link DurableQueueConsumerDependencies#builder()}
     */
    public PostgresqlDurableQueueConsumer(ConsumeFromQueue consumeFromQueue,
                                          DurableQueueConsumerDependencies<PostgresqlDurableQueues, HandleAwareUnitOfWork, UnitOfWorkFactory<HandleAwareUnitOfWork>> dependencies) {
        super(consumeFromQueue, dependencies);
    }

    /**
     * @deprecated Use {@link #PostgresqlDurableQueueConsumer(ConsumeFromQueue, DurableQueueConsumerDependencies)}. The five collaborator
     *         arguments are identical for every {@code DurableQueues} implementation, so they belong in one
     *         {@link DurableQueueConsumerDependencies} bundle rather than being repeated positionally here and in
     *         every sibling implementation. This constructor delegates and behaves identically.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    @SuppressWarnings("rawtypes")
    public PostgresqlDurableQueueConsumer(ConsumeFromQueue consumeFromQueue,
                                          HandleAwareUnitOfWorkFactory unitOfWorkFactory,
                                          PostgresqlDurableQueues durableQueues,
                                          Consumer<DurableQueueConsumer> removeDurableQueueConsumer,
                                          long pollingIntervalMs,
                                          QueuePollingOptimizer queuePollingOptimizer,
                                          List<DurableQueuesInterceptor> interceptors) {
        super(consumeFromQueue, unitOfWorkFactory, durableQueues, removeDurableQueueConsumer, pollingIntervalMs, queuePollingOptimizer, interceptors);
    }
}
