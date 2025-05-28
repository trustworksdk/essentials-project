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

package dk.trustworks.essentials.components.foundation.messaging.queue.stats;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueuedMessage.DeliveryMode;

import java.time.OffsetDateTime;

/**
 * Represents a message in a queue along with its associated statistics.
 * This interface provides various methods to obtain metadata and statistical
 * information for a message, including details such as the queue name, timestamps
 * for important operations, delivery mode, and attempts metadata.
 */
public interface QueuedStatisticsMessage {

    /**
     * Retrieves the unique identifier of the message in the queue.
     *
     * @return the unique {@link QueueEntryId} associated with the message
     */
    QueueEntryId getId();

    /**
     * Name of the Queue that the message is enqueued on
     *
     * @return name of the Queue that the message is enqueued on
     */
    QueueName getQueueName();

    /**
     * When was this message first enqueued (or directly added as a Dead-letter-message)
     *
     * @return when was this message first enqueued
     */
    OffsetDateTime getAddedTimestamp();

    /**
     * Timestamp for when the message was delivered.
     *
     * @return timestamp for when the message was delivered.
     */
    OffsetDateTime getDeliveryTimestamp();

    /**
     * Timestamp for when the message was deleted from durable_queues.
     *
     * @return timestamp for when the message was deleted from durable_queues.
     */
    OffsetDateTime getDeletionTimestamp();

    /**
     * The total delivery attempts
     *
     * @return total delivery attempts
     */
    Integer getTotalAttempts();

    /**
     * The total redelivery attempts
     *
     * @return total redelivery attempts
     */
    Integer getRedeliveryAttempts();

    /**
     * Get the message's {@link DeliveryMode}
     *
     * @return the message's {@link DeliveryMode}
     */
    DeliveryMode getDeliveryMode();

    /**
     * Get the {@link MessageMetaData}
     *
     * @return the {@link MessageMetaData}
     */
    MessageMetaData getMetaData();

}
