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
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Configuration for {@link PostgresqlSplitDurableQueues}.
 * <p>
 * The defaults match {@link PostgresqlDurableQueuesBuilder}'s, so moving a deployment onto the split changes the
 * storage layout and nothing else.
 *
 * @param baseQueueTableName              the base name the two tables are derived from, by appending
 *                                        {@link PostgresqlSplitDurableQueues#UNORDERED_TABLE_SUFFIX} and
 *                                        {@link PostgresqlSplitDurableQueues#ORDERED_TABLE_SUFFIX}. Both derived
 *                                        names are string-concatenated into SQL, so this must be a hardcoded or
 *                                        otherwise trusted value - see {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)},
 *                                        which is a first line of defence and not a sanitizer.
 * @param transactionalMode               see {@link DurableQueues#getTransactionalMode()}.
 *                                        {@link TransactionalMode#SingleOperationTransaction} is the default and
 *                                        the only mode in which retries and dead-lettering behave, because
 *                                        {@link TransactionalMode#FullyTransactional} rolls back the attempt count
 *                                        along with the failure
 * @param messageHandlingTimeout          how long a claimed message may stay claimed before
 *                                        {@code resetMessagesStuckBeingDelivered} releases it again.
 *                                        Required under {@link TransactionalMode#SingleOperationTransaction}
 * @param orderedMessageDuplicateStrategy whether two {@link OrderedMessage}s sharing a key <em>and</em> an order are
 *                                        rejected. {@link OrderedMessageDuplicateStrategy#REJECT} is the default and
 *                                        makes the ordered table's per-key index unique
 * @param pollingInterval                 the interval at which the single {@link CentralizedMessageFetcher} polls
 * @param useBatchedFetch                 opt in to claiming across all active queues in one statement instead of one
 *                                        statement per queue per poll
 * @param batchedFetchSwitchThreshold     the number of active queues <em>above</em> which batched fetch is used, when
 *                                        {@code useBatchedFetch} is on
 * @param batchedAcknowledgementSettings  whether acknowledgements are coalesced into one {@code DELETE} per batch.
 *                                        {@link OrderedMessage}s are never buffered regardless
 * @param messageObserver                 notified of how each delivery ended, for delivery statistics. Reported by
 *                                        the composite rather than per table, so it is keyed by {@link QueueName}
 *                                        and needs no knowledge of the split
 */
public record PostgresqlSplitDurableQueuesSettings(String baseQueueTableName,
                                                   TransactionalMode transactionalMode,
                                                   Duration messageHandlingTimeout,
                                                   OrderedMessageDuplicateStrategy orderedMessageDuplicateStrategy,
                                                   Duration pollingInterval,
                                                   boolean useBatchedFetch,
                                                   int batchedFetchSwitchThreshold,
                                                   BatchedAcknowledgementSettings batchedAcknowledgementSettings,
                                                   DurableQueueMessageObserver messageObserver) {

    /**
     * The base name the two split tables are derived from when none is given.
     */
    public static final String   DEFAULT_BASE_QUEUE_TABLE_NAME = "durable_queues";
    public static final Duration DEFAULT_POLLING_INTERVAL      = Duration.ofMillis(20);
    /**
     * Matches {@link PostgresqlDurableQueuesBuilder}'s default: a 4-queue deployment stays on per-queue fetch.
     */
    public static final int      DEFAULT_BATCHED_FETCH_SWITCH_THRESHOLD = 4;

    public PostgresqlSplitDurableQueuesSettings {
        requireNonNull(baseQueueTableName, "No baseQueueTableName provided");
        PostgresqlUtil.checkIsValidTableOrColumnName(baseQueueTableName);
        requireNonNull(transactionalMode, "No transactionalMode provided");
        requireNonNull(orderedMessageDuplicateStrategy, "No orderedMessageDuplicateStrategy provided");
        requireNonNull(pollingInterval, "No pollingInterval provided");
        requireNonNull(batchedAcknowledgementSettings, "No batchedAcknowledgementSettings provided");
        requireNonNull(messageObserver, "No messageObserver provided");
        if (transactionalMode == TransactionalMode.SingleOperationTransaction) {
            requireNonNull(messageHandlingTimeout, "No messageHandlingTimeout provided - it is required by TransactionalMode.SingleOperationTransaction");
        }
        requireTrue(batchedFetchSwitchThreshold >= 0, "batchedFetchSwitchThreshold must be >= 0");
    }

    /**
     * @return settings matching {@link PostgresqlDurableQueuesBuilder}'s defaults, over the
     * {@value #DEFAULT_BASE_QUEUE_TABLE_NAME} base table name
     */
    public static PostgresqlSplitDurableQueuesSettings defaults() {
        return defaultsFor(DEFAULT_BASE_QUEUE_TABLE_NAME);
    }

    /**
     * @param baseQueueTableName the base name the two tables are derived from
     * @return settings matching {@link PostgresqlDurableQueuesBuilder}'s defaults, over the given base table name
     */
    public static PostgresqlSplitDurableQueuesSettings defaultsFor(String baseQueueTableName) {
        return new PostgresqlSplitDurableQueuesSettings(baseQueueTableName,
                                                        TransactionalMode.SingleOperationTransaction,
                                                        PostgresqlDurableQueues.DEFAULT_MESSAGE_HANDLING_TIMEOUT,
                                                        OrderedMessageDuplicateStrategy.REJECT,
                                                        DEFAULT_POLLING_INTERVAL,
                                                        false,
                                                        DEFAULT_BATCHED_FETCH_SWITCH_THRESHOLD,
                                                        BatchedAcknowledgementSettings.disabled(),
                                                        DurableQueueMessageObserver.none());
    }
}
