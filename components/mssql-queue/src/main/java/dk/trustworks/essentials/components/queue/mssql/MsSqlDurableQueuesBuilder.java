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

package dk.trustworks.essentials.components.queue.mssql;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.queue.jdbc.JdbcDurableQueuesBuilder;

import java.time.Duration;
import java.util.function.Function;

import static dk.trustworks.essentials.components.queue.mssql.MsSqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME;

public final class MsSqlDurableQueuesBuilder extends JdbcDurableQueuesBuilder<MsSqlDurableQueues, MsSqlDurableQueuesBuilder> {
    public MsSqlDurableQueuesBuilder() {
        super(DEFAULT_DURABLE_QUEUES_TABLE_NAME);
    }

    @Override
    protected MsSqlDurableQueuesBuilder self() {
        return this;
    }

    @Override
    protected MsSqlDurableQueues buildDurableQueues(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                    JSONSerializer jsonSerializer,
                                                    String sharedQueueTableName,
                                                    MultiTableChangeListener<TableChangeNotification> multiTableChangeListener,
                                                    Function<ConsumeFromQueue, QueuePollingOptimizer> queuePollingOptimizerFactory,
                                                    TransactionalMode transactionalMode,
                                                    Duration messageHandlingTimeout,
                                                    boolean useCentralizedMessageFetcher,
                                                    Duration centralizedMessageFetcherPollingInterval,
                                                    Function<QueueName, QueuePollingOptimizer> centralizedQueuePollingOptimizerFactory,
                                                    boolean useOrderedUnorderedQuery) {
        return new MsSqlDurableQueues(unitOfWorkFactory,
                                      jsonSerializer,
                                      sharedQueueTableName,
                                      multiTableChangeListener,
                                      queuePollingOptimizerFactory,
                                      transactionalMode,
                                      messageHandlingTimeout,
                                      useCentralizedMessageFetcher,
                                      centralizedMessageFetcherPollingInterval,
                                      centralizedQueuePollingOptimizerFactory,
                                      useOrderedUnorderedQuery);
    }
}
