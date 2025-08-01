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
package dk.trustworks.essentials.components.foundation.messaging.queue;

import java.util.*;

/**
 * Extension interface for {@link DurableQueues} implementations that support batch fetching of messages
 * across multiple queues in a single operation.
 *
 * @see CentralizedMessageFetcher
 */
public interface BatchMessageFetchingCapableDurableQueues extends DurableQueues {
    /**
     * Fetch the next batch of messages ready for delivery across multiple queues.
     * The implementation must respect the same ordering rules as {@link DurableQueues#getNextMessageReadyForDelivery}
     * but do it in a way that minimizes database queries.
     *
     * @param queueNames                   the queue names to fetch messages for
     * @param excludeKeysPerQueue          map of queue name to set of keys to exclude (for ordered messages)
     * @param availableWorkerSlotsPerQueue map of queue name to number of available worker slots
     * @return list of queued messages ready for delivery
     */
    List<QueuedMessage> fetchNextBatchOfMessages(Collection<QueueName> queueNames,
                                                 Map<QueueName, Set<String>> excludeKeysPerQueue,
                                                 Map<QueueName, Integer> availableWorkerSlotsPerQueue);

    /**
     * Fetches the next batch of messages across multiple queues in one round-trip, applying per-queue backoff/skip logic and updating each queue’s optimizer.<br>
     * It filters out messages based on excluded keys and the number of available worker slots in each queue.
     *
     * @param queueNames                   a collection of {@link QueueName} objects representing the queues to fetch messages from
     * @param excludeKeysPerQueue          a map associating each {@link QueueName} with a set of keys to exclude from the batch (required for in-order delivery scenarios)
     * @param availableWorkerSlotsPerQueue a map associating each {@link QueueName} with the count of available worker slots
     *                                      indicating how many messages can be fetched for that queue
     * @return a list of {@link QueuedMessage} objects representing the messages ready for delivery, respecting the
     *         constraints defined by the input parameters
     */
    List<QueuedMessage> fetchNextBatchOfMessagesBatched(Collection<QueueName> queueNames,
                                                        Map<QueueName, Set<String>> excludeKeysPerQueue,
                                                        Map<QueueName, Integer> availableWorkerSlotsPerQueue);
}