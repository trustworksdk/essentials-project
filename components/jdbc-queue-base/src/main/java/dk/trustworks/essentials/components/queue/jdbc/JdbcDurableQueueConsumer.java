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

package dk.trustworks.essentials.components.queue.jdbc;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;

import java.util.List;
import java.util.function.Consumer;

@SuppressWarnings("unchecked")
public class JdbcDurableQueueConsumer<DURABLE_QUEUES extends DurableQueues>
        extends DefaultDurableQueueConsumer<DURABLE_QUEUES, HandleAwareUnitOfWork, UnitOfWorkFactory<HandleAwareUnitOfWork>> {

    @SuppressWarnings("rawtypes")
    public JdbcDurableQueueConsumer(ConsumeFromQueue consumeFromQueue,
                                    HandleAwareUnitOfWorkFactory unitOfWorkFactory,
                                    DURABLE_QUEUES durableQueues,
                                    Consumer<DurableQueueConsumer> removeDurableQueueConsumer,
                                    long pollingIntervalMs,
                                    QueuePollingOptimizer queuePollingOptimizer,
                                    List<DurableQueuesInterceptor> interceptors) {
        super(consumeFromQueue, unitOfWorkFactory, durableQueues, removeDurableQueueConsumer, pollingIntervalMs, queuePollingOptimizer, interceptors);
    }
}
