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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import java.time.OffsetDateTime;

/**
 * Builder for {@link DefaultQueuedMessage}, obtained from {@link DefaultQueuedMessage#builder()}.
 * <p>
 * Only {@code id}, {@code queueName}, {@code message} and {@code addedTimestamp} are required; the rest describe a
 * delivery history that a freshly queued message does not have yet, and default to "never delivered, no error, not a
 * dead letter, not currently being delivered".
 */
public final class DefaultQueuedMessageBuilder {
    private QueueEntryId   id;
    private QueueName      queueName;
    private Message        message;
    private OffsetDateTime addedTimestamp;
    private OffsetDateTime nextDeliveryTimestamp;
    private OffsetDateTime deliveryTimestamp;
    private String         lastDeliveryError;
    private int            totalDeliveryAttempts;
    private int            redeliveryAttempts;
    private boolean        isDeadLetterMessage;
    private boolean        isBeingDelivered;

    /**
     * @param id the queue entry id. Required
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setId(QueueEntryId id) {
        this.id = id;
        return this;
    }

    /**
     * @param queueName the queue this message belongs to. Required
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setQueueName(QueueName queueName) {
        this.queueName = queueName;
        return this;
    }

    /**
     * @param message the deserialized message payload. Required
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setMessage(Message message) {
        this.message = message;
        return this;
    }

    /**
     * @param addedTimestamp when the message was added to the queue. Required
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setAddedTimestamp(OffsetDateTime addedTimestamp) {
        this.addedTimestamp = addedTimestamp;
        return this;
    }

    /**
     * @param nextDeliveryTimestamp when the message is next eligible for delivery, or {@code null} if not scheduled
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setNextDeliveryTimestamp(OffsetDateTime nextDeliveryTimestamp) {
        this.nextDeliveryTimestamp = nextDeliveryTimestamp;
        return this;
    }

    /**
     * @param deliveryTimestamp when the message was last delivered, or {@code null} if never
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setDeliveryTimestamp(OffsetDateTime deliveryTimestamp) {
        this.deliveryTimestamp = deliveryTimestamp;
        return this;
    }

    /**
     * @param lastDeliveryError the error from the last failed delivery, or {@code null} if none
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setLastDeliveryError(String lastDeliveryError) {
        this.lastDeliveryError = lastDeliveryError;
        return this;
    }

    /**
     * @param totalDeliveryAttempts total number of delivery attempts so far. Defaults to 0
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setTotalDeliveryAttempts(int totalDeliveryAttempts) {
        this.totalDeliveryAttempts = totalDeliveryAttempts;
        return this;
    }

    /**
     * @param redeliveryAttempts number of redelivery attempts so far. Defaults to 0
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setRedeliveryAttempts(int redeliveryAttempts) {
        this.redeliveryAttempts = redeliveryAttempts;
        return this;
    }

    /**
     * @param isDeadLetterMessage whether the message has been marked as a dead-letter message. Defaults to {@code false}
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setDeadLetterMessage(boolean isDeadLetterMessage) {
        this.isDeadLetterMessage = isDeadLetterMessage;
        return this;
    }

    /**
     * @param isBeingDelivered whether the message is currently being delivered. Defaults to {@code false}
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedMessageBuilder setBeingDelivered(boolean isBeingDelivered) {
        this.isBeingDelivered = isBeingDelivered;
        return this;
    }

    /**
     * @return the new {@link DefaultQueuedMessage}
     */
    @SuppressWarnings("removal")
    public DefaultQueuedMessage build() {
        return new DefaultQueuedMessage(id,
                                        queueName,
                                        message,
                                        addedTimestamp,
                                        nextDeliveryTimestamp,
                                        deliveryTimestamp,
                                        lastDeliveryError,
                                        totalDeliveryAttempts,
                                        redeliveryAttempts,
                                        isDeadLetterMessage,
                                        isBeingDelivered);
    }
}
