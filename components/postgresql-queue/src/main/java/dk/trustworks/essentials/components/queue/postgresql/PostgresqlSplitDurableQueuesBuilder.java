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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;

import java.time.Duration;

/**
 * Builder for {@link PostgresqlSplitDurableQueues}. Its defaults are
 * {@link PostgresqlSplitDurableQueuesSettings#defaults()}, which are in turn
 * {@link PostgresqlDurableQueuesBuilder}'s.
 */
public final class PostgresqlSplitDurableQueuesBuilder {
    private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private JSONSerializer                                               jsonSerializer;
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
                                                                   settings.batchedAcknowledgementSettings()));
    }

    public PostgresqlSplitDurableQueuesBuilder setTransactionalMode(TransactionalMode transactionalMode) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   transactionalMode,
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings()));
    }

    public PostgresqlSplitDurableQueuesBuilder setMessageHandlingTimeout(Duration messageHandlingTimeout) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   messageHandlingTimeout,
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings()));
    }

    public PostgresqlSplitDurableQueuesBuilder setOrderedMessageDuplicateStrategy(OrderedMessageDuplicateStrategy orderedMessageDuplicateStrategy) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   orderedMessageDuplicateStrategy,
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings()));
    }

    public PostgresqlSplitDurableQueuesBuilder setPollingInterval(Duration pollingInterval) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   pollingInterval,
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings()));
    }

    public PostgresqlSplitDurableQueuesBuilder setUseBatchedFetch(boolean useBatchedFetch) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   useBatchedFetch,
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   settings.batchedAcknowledgementSettings()));
    }

    public PostgresqlSplitDurableQueuesBuilder setBatchedFetchSwitchThreshold(int batchedFetchSwitchThreshold) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   batchedFetchSwitchThreshold,
                                                                   settings.batchedAcknowledgementSettings()));
    }

    public PostgresqlSplitDurableQueuesBuilder setBatchedAcknowledgementSettings(BatchedAcknowledgementSettings batchedAcknowledgementSettings) {
        return setSettings(new PostgresqlSplitDurableQueuesSettings(settings.baseQueueTableName(),
                                                                   settings.transactionalMode(),
                                                                   settings.messageHandlingTimeout(),
                                                                   settings.orderedMessageDuplicateStrategy(),
                                                                   settings.pollingInterval(),
                                                                   settings.useBatchedFetch(),
                                                                   settings.batchedFetchSwitchThreshold(),
                                                                   batchedAcknowledgementSettings));
    }

    public PostgresqlSplitDurableQueues build() {
        return new PostgresqlSplitDurableQueues(unitOfWorkFactory, jsonSerializer, settings);
    }
}
