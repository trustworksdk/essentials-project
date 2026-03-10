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

import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;

import java.time.Duration;
import java.util.function.Function;

public abstract class JdbcDurableQueuesBuilder<DURABLE_QUEUES extends DurableQueues, SELF extends JdbcDurableQueuesBuilder<DURABLE_QUEUES, SELF>> {
    protected HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    protected JSONSerializer                                                  jsonSerializer;
    protected String                                                          sharedQueueTableName;
    protected MultiTableChangeListener<TableChangeNotification>               multiTableChangeListener     = null;
    protected Function<ConsumeFromQueue, QueuePollingOptimizer>               queuePollingOptimizerFactory = null;
    protected TransactionalMode                                               transactionalMode            = TransactionalMode.SingleOperationTransaction;
    protected Duration                                                        messageHandlingTimeout       = Duration.ofSeconds(30);
    protected boolean                                                         useCentralizedMessageFetcher = true;
    protected Duration                                                        centralizedMessageFetcherPollingInterval = Duration.ofMillis(20);
    protected Function<QueueName, QueuePollingOptimizer>                      centralizedQueuePollingOptimizerFactory = null;
    protected boolean                                                         useOrderedUnorderedQuery;

    protected JdbcDurableQueuesBuilder(String defaultDurableQueuesTableName) {
        this.sharedQueueTableName = defaultDurableQueuesTableName;
    }

    protected abstract SELF self();

    protected abstract DURABLE_QUEUES buildDurableQueues(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                         JSONSerializer jsonSerializer,
                                                         String sharedQueueTableName,
                                                         MultiTableChangeListener<TableChangeNotification> multiTableChangeListener,
                                                         Function<ConsumeFromQueue, QueuePollingOptimizer> queuePollingOptimizerFactory,
                                                         TransactionalMode transactionalMode,
                                                         Duration messageHandlingTimeout,
                                                         boolean useCentralizedMessageFetcher,
                                                         Duration centralizedMessageFetcherPollingInterval,
                                                         Function<QueueName, QueuePollingOptimizer> centralizedQueuePollingOptimizerFactory,
                                                         boolean useOrderedUnorderedQuery);

    public SELF setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return self();
    }

    public SELF setJsonSerializer(JSONSerializer jsonSerializer) {
        this.jsonSerializer = jsonSerializer;
        return self();
    }

    public SELF setSharedQueueTableName(String sharedQueueTableName) {
        this.sharedQueueTableName = sharedQueueTableName;
        return self();
    }

    public SELF setMultiTableChangeListener(MultiTableChangeListener<TableChangeNotification> multiTableChangeListener) {
        this.multiTableChangeListener = multiTableChangeListener;
        return self();
    }

    public SELF setQueuePollingOptimizerFactory(Function<ConsumeFromQueue, QueuePollingOptimizer> queuePollingOptimizerFactory) {
        this.queuePollingOptimizerFactory = queuePollingOptimizerFactory;
        return self();
    }

    public SELF setMessageHandlingTimeout(Duration messageHandlingTimeout) {
        this.messageHandlingTimeout = messageHandlingTimeout;
        return self();
    }

    public SELF setTransactionalMode(TransactionalMode transactionalMode) {
        this.transactionalMode = transactionalMode;
        return self();
    }

    public SELF setUseCentralizedMessageFetcher(boolean useCentralizedMessageFetcher) {
        this.useCentralizedMessageFetcher = useCentralizedMessageFetcher;
        return self();
    }

    public SELF setCentralizedMessageFetcherPollingInterval(Duration centralizedMessageFetcherPollingInterval) {
        this.centralizedMessageFetcherPollingInterval = centralizedMessageFetcherPollingInterval;
        return self();
    }

    public SELF setCentralizedQueuePollingOptimizerFactory(Function<QueueName, QueuePollingOptimizer> centralizedQueuePollingOptimizerFactory) {
        this.centralizedQueuePollingOptimizerFactory = centralizedQueuePollingOptimizerFactory;
        return self();
    }

    public SELF setUseOrderedUnorderedQuery(boolean useOrderedUnorderedQuery) {
        this.useOrderedUnorderedQuery = useOrderedUnorderedQuery;
        return self();
    }

    public DURABLE_QUEUES build() {
        return buildDurableQueues(unitOfWorkFactory,
                                  jsonSerializer != null ? jsonSerializer : new JacksonJSONSerializer(DurableQueuesSerialization.createDefaultObjectMapper()),
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
