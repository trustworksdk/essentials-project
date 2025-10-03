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

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;

import java.time.OffsetDateTime;

/**
 * Notification for changes to the queue table.
 * This class is used to receive notifications when a new message is added to the queue.
 */
public class QueueTableNotification extends TableChangeNotification {
    @JsonProperty("id")
    public String id;

    @JsonProperty("queue_name")
    public String queueName;

    @JsonProperty("added_ts")
    public OffsetDateTime addedTimestamp;

    @JsonProperty("next_delivery_ts")
    public OffsetDateTime nextDeliveryTimestamp;

    @JsonProperty("delivery_ts")
    public OffsetDateTime deliveryTimestamp;

    @JsonProperty("is_dead_letter_message")
    public boolean isDeadLetterMessage;

    @JsonProperty("is_being_delivered")
    public boolean isBeingDelivered;

    /**
     * Default constructor.
     */
    public QueueTableNotification() {
    }

    /**
     * Gets the ID of the queue entry.
     *
     * @return the ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the queue name.
     *
     * @return the queue name
     */
    public String getQueueName() {
        return queueName;
    }

    /**
     * Gets the timestamp when the message was added to the queue.
     *
     * @return the added timestamp
     */
    public OffsetDateTime getAddedTimestamp() {
        return addedTimestamp;
    }

    /**
     * Gets the timestamp when the message is next scheduled for delivery.
     *
     * @return the next delivery timestamp
     */
    public OffsetDateTime getNextDeliveryTimestamp() {
        return nextDeliveryTimestamp;
    }

    /**
     * Gets the timestamp when the message was last delivered.
     *
     * @return the delivery timestamp
     */
    public OffsetDateTime getDeliveryTimestamp() {
        return deliveryTimestamp;
    }

    /**
     * Checks if the message is a dead letter message.
     *
     * @return true if the message is a dead letter message, false otherwise
     */
    public boolean isDeadLetterMessage() {
        return isDeadLetterMessage;
    }

    /**
     * Checks if the message is currently being delivered.
     *
     * @return true if the message is being delivered, false otherwise
     */
    public boolean isBeingDelivered() {
        return isBeingDelivered;
    }
}