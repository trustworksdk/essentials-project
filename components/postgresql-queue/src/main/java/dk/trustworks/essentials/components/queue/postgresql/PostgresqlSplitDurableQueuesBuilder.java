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

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;

import java.time.Duration;
import java.util.function.Function;

/**
 * Builder for {@link PostgresqlSplitDurableQueues}. Its defaults are
 * {@link PostgresqlSplitDurableQueuesSettings#defaults()}, which are in turn
 * {@link PostgresqlDurableQueuesBuilder}'s.
 */
public final class PostgresqlSplitDurableQueuesBuilder {
    private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private JSONSerializer                                               jsonSerializer;
    private MultiTableChangeListener<TableChangeNotification>             multiTableChangeListener;
    private Function<QueueName, QueuePollingOptimizer>                    centralizedQueuePollingOptimizerFactory;
    private PostgresqlSplitDurableQueuesSettings                         settings = PostgresqlSplitDurableQueuesSettings.defaults();

    public PostgresqlSplitDurableQueuesBuilder setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return this;
    }

    public PostgresqlSplitDurableQueuesBuilder setJsonSerializer(JSONSerializer jsonSerializer) {
        this.jsonSerializer = jsonSerializer;
        return this;
    }

    /**
     * The LISTEN/NOTIFY bridge. Without it the split polls at its fixed interval and every queue gets
     * {@link QueuePollingOptimizer#None()}, since backoff with no wake-up is only slower.
     */
    public PostgresqlSplitDurableQueuesBuilder setMultiTableChangeListener(MultiTableChangeListener<TableChangeNotification> multiTableChangeListener) {
        this.multiTableChangeListener = multiTableChangeListener;
        return this;
    }

    public PostgresqlSplitDurableQueuesBuilder setCentralizedQueuePollingOptimizerFactory(Function<QueueName, QueuePollingOptimizer> centralizedQueuePollingOptimizerFactory) {
        this.centralizedQueuePollingOptimizerFactory = centralizedQueuePollingOptimizerFactory;
        return this;
    }

    /**
     * Replaces every setting at once. The individual setters below start from whatever is set here, so call this
     * first if you use both.
     */
    public PostgresqlSplitDurableQueuesBuilder setSettings(PostgresqlSplitDurableQueuesSettings settings) {
        this.settings = settings;
        return this;
    }

    /**
     * @param baseQueueTableName the base name the two tables are derived from - see
     *                           {@link PostgresqlSplitDurableQueuesSettings#baseQueueTableName()} for the
     *                           SQL-injection caveat
     */
    public PostgresqlSplitDurableQueuesBuilder setBaseQueueTableName(String baseQueueTableName) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(baseQueueTableName,
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings(),
                                                                   settings.messageObserver()));
    }

    public PostgresqlSplitDurableQueuesBuilder setTransactionalMode(TransactionalMode transactionalMode) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   transactionalMode,
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings(),
                                                                   settings.messageObserver()));
    }

    public PostgresqlSplitDurableQueuesBuilder setMessageHandlingTimeout(Duration messageHandlingTimeout) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   messageHandlingTimeout,
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings(),
                                                                   settings.messageObserver()));
    }

    public PostgresqlSplitDurableQueuesBuilder setOrderedMessageDuplicateStrategy(OrderedMessageDuplicateStrategy orderedMessageDuplicateStrategy) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   orderedMessageDuplicateStrategy,
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings(),
                                                                   settings.messageObserver()));
    }

    public PostgresqlSplitDurableQueuesBuilder setPollingInterval(Duration pollingInterval) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   pollingInterval,
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings(),
                                                                   settings.messageObserver()));
    }

    public PostgresqlSplitDurableQueuesBuilder setUseBatchedFetch(boolean useBatchedFetch) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   useBatchedFetch,
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings(),
                                                                   settings.messageObserver()));
    }

    public PostgresqlSplitDurableQueuesBuilder setBatchedFetchSwitchThreshold(int batchedFetchSwitchThreshold) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   batchedFetchSwitchThreshold,
                                                                   settings.batchedAcknowledgementSettings(),
                                                                   settings.messageObserver()));
    }

    public PostgresqlSplitDurableQueuesBuilder setBatchedAcknowledgementSettings(BatchedAcknowledgementSettings batchedAcknowledgementSettings) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   batchedAcknowledgementSettings,
                                                                   settings.messageObserver()));
    }

    /**
     * Observes how each delivery ended, for delivery statistics - see
     * {@link PostgresqlDurableQueuesBuilder#setMessageObserver}. Reported once per delivery by the composite, not
     * once per table.
     */
    public PostgresqlSplitDurableQueuesBuilder setMessageObserver(DurableQueueMessageObserver messageObserver) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings(),
                                                                   messageObserver != null ? messageObserver : DurableQueueMessageObserver.none()));
    }

    public PostgresqlSplitDurableQueues build() {
        return new PostgresqlSplitDurableQueues(unitOfWorkFactory,
                                               jsonSerializer,
                                               multiTableChangeListener,
                                               centralizedQueuePollingOptimizerFactory,
                                               settings);
    }
}
