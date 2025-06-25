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

package dk.trustworks.essentials.components.foundation.messaging.queue.api;

import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues.QueueingSortOrder;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;

import java.time.Duration;
import java.util.*;

/**
 * The DurableQueuesApi provides an interface for interacting with durable queues, enabling
 * message management, retrieval, and queue-specific operations. It offers methods to handle
 * messages, retrieve queue statistics, and manage dead-letter queues.
 */
public interface DurableQueuesApi {

    /**
     * Retrieves the set of queue names that the given principal has access to.
     *
     * @param principal the principal whose accessible queues are to be retrieved;
     *                  this could represent a user or a system entity.
     * @return a set of {@code QueueName} objects representing the names of the queues
     *         the specified principal can access.
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    Set<QueueName> getQueueNames(Object principal);

    /**
     * Retrieves the name of the queue associated with a specific message identified by the given {@code queueEntryId},
     * for a specified {@code principal}. This method returns an {@code Optional} containing the queue name if
     * the association exists and the principal has access to the queue.
     *
     * @param principal the principal attempting to access the queue; this could represent a user or a system entity
     * @param queueEntryId the unique identifier of the message whose associated queue name is to be retrieved
     * @return an {@code Optional} containing the {@code QueueName} if the principal has access to the associated queue,
     *         or an empty {@code Optional} if the association is not found or access is denied
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    Optional<QueueName> getQueueNameFor(Object principal, QueueEntryId queueEntryId);

    /**
     * Retrieves the queued message associated with the specified {@code QueueEntryId} for the given {@code principal}.
     * This method returns an {@code Optional} containing the {@code ApiQueuedMessage} if the message exists
     * and the principal is authorized to access it.
     *
     * @param principal the entity (user or system) attempting to retrieve the message
     * @param queueEntryId the unique identifier of the queued message
     * @return an {@code Optional} containing the {@code ApiQueuedMessage} if the message exists and access is permitted,
     *         or an empty {@code Optional} if the message is not found or access is denied
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    Optional<ApiQueuedMessage> getQueuedMessage(Object principal, QueueEntryId queueEntryId);

    /**
     * Attempts to resurrect a dead-letter message by re-queuing it for delivery with an optional delay.
     * The message is identified by its {@code queueEntryId}, and the action is performed
     * under the context of the provided {@code principal}.
     *
     * @param principal the entity (user or system) requesting the operation; used for authorization and context
     * @param queueEntryId the unique identifier of the message in the queue that should be resurrected
     * @param deliveryDelay the delay duration after which the message should be re-delivered;
     *                      a value of zero indicates immediate re-delivery
     * @return an {@code Optional} containing the re-queued {@code ApiQueuedMessage} if successful,
     *         or an empty {@code Optional} if the message could not be resurrected
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    Optional<ApiQueuedMessage> resurrectDeadLetterMessage(Object principal, QueueEntryId queueEntryId,
                                                       Duration deliveryDelay);

    /**
     * Marks a queued message as a dead-letter message. The message is identified by its {@code queueEntryId},
     * and the action is performed under the context of the provided {@code principal}.
     * If the operation is successful, the functionName returns an {@code Optional} containing the updated
     * {@code ApiQueuedMessage}.
     *
     * @param principal the entity (user or system) requesting the operation, used for authorization and context
     * @param queueEntryId the unique identifier of the message in the queue to be marked as a dead-letter message
     * @return an {@code Optional} containing the {@code ApiQueuedMessage} if the operation is successful,
     *         or an empty {@code Optional} if the message could not be marked as a dead-letter message
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    Optional<ApiQueuedMessage> markAsDeadLetterMessage(Object principal, QueueEntryId queueEntryId);

    /**
     * Deletes the specified message from the queue. The action is performed in the context of the given principal.
     * If the principal is authorized and the message is successfully deleted, the method returns true.
     * Otherwise, it returns false.
     *
     * @param principal the entity (user or system) attempting to perform the deletion; used for authorization and context
     * @param queueEntryId the unique identifier of the message to be deleted from the queue
     * @return {@code true} if the message was successfully deleted, {@code false} otherwise
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    boolean deleteMessage(Object principal, QueueEntryId queueEntryId);

    /**
     * Retrieves the total number of messages currently queued for the specified {@code queueName}
     * that the given {@code principal} has access to.
     *
     * @param principal the entity (user or system) requesting the information; used for authorization and context
     * @param queueName the name of the queue for which the total number of queued messages is to be retrieved
     * @return the total number of messages queued for the specified {@code queueName}
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    long getTotalMessagesQueuedFor(Object principal, QueueName queueName);

    /**
     * Retrieves the total number of dead-letter messages currently queued for the specified {@code queueName}
     * that the given {@code principal} has access to.
     *
     * @param principal the entity (user or system) requesting the information; used for authorization and context
     * @param queueName the name of the queue for which the total number of dead-letter messages is to be retrieved
     * @return the total number of dead-letter messages queued for the specified {@code queueName}
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    long getTotalDeadLetterMessagesQueuedFor(Object principal, QueueName queueName);

    /**
     * Retrieves a paginated list of messages queued for the specified {@code queueName},
     * accessible by the given {@code principal}. The messages can be sorted based on the
     * provided {@code queueingSortOrder}.
     *
     * @param principal the entity (user or system) requesting the messages; used for authorization and context
     * @param queueName the name of the queue from which messages are to be retrieved
     * @param queueingSortOrder the sorting order to be applied to the queued messages
     * @param startIndex the starting index from which messages should be fetched
     * @param pageSize the maximum number of messages to retrieve in the result set
     * @return a list of {@code ApiQueuedMessage} objects representing the messages in the queue
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    List<ApiQueuedMessage> getQueuedMessages(Object principal,
                                          QueueName queueName,
                                          QueueingSortOrder queueingSortOrder,
                                          long startIndex,
                                          long pageSize);

    /**
     * Retrieves a paginated list of dead-letter messages for the specified {@code queueName},
     * accessible by the given {@code principal}. The messages can be sorted based on the
     * provided {@code queueingSortOrder}.
     *
     * @param principal the entity (user or system) requesting the dead-letter messages; used for authorization and context
     * @param queueName the name of the queue from which dead-letter messages are to be retrieved
     * @param queueingSortOrder the sorting order to be applied to the dead-letter messages
     * @param startIndex the starting index from which dead-letter messages should be fetched
     * @param pageSize the maximum number of dead-letter messages to retrieve in the result set
     * @return a list of {@code ApiQueuedMessage} objects representing the dead-letter messages in the queue
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    List<ApiQueuedMessage> getDeadLetterMessages(Object principal,
                                              QueueName queueName,
                                              QueueingSortOrder queueingSortOrder,
                                              long startIndex,
                                              long pageSize);

    /**
     * Purges all messages from the specified queue that the given principal has access to.
     * This operation will remove all messages from the queue, including regular and dead-letter messages.
     *
     * @param principal the entity (user or system) requesting the operation; used for authorization and context
     * @param queueName the name of the queue to be purged
     * @return the total number of messages that were removed from the queue
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    int purgeQueue(Object principal, QueueName queueName);

    /**
     * Retrieves statistics about the messages queued in a specific durable queue.
     * The statistics include details such as the total messages delivered, average
     * delivery latency, and the timestamps of the first and last deliveries.
     *
     * @param principal the entity (user or system) making the request; used for
     *                  authorization and context
     * @param queueName the name of the queue for which statistics are to be retrieved
     * @return an {@code Optional} containing the {@code ApiQueuedStatistics} for the
     *         specified queue if the principal has access and data is available, or
     *         an empty {@code Optional} if access is denied or statistics cannot be found
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    Optional<ApiQueuedStatistics> getQueuedStatistics(Object principal, QueueName queueName);
}
