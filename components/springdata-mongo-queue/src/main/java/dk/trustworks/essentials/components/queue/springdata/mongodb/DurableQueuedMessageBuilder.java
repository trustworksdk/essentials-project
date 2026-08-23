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

package dk.trustworks.essentials.components.queue.springdata.mongodb;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueuedMessage.DeliveryMode;
import dk.trustworks.essentials.components.queue.springdata.mongodb.MongoDurableQueues.DurableQueuedMessage;

import java.time.Instant;

/**
 * Builder for {@link DurableQueuedMessage}, obtained from {@link DurableQueuedMessage#builder()}.
 * <p>
 * Sixteen positional arguments — with {@code isBeingDelivered}/{@code isDeadLetterMessage} adjacent to each other and
 * {@code totalDeliveryAttempts}/{@code redeliveryAttempts} likewise — is the shape the parameter ceiling exists to
 * prevent: every one of those four transposes silently. The defaults here mirror the field initialisers on
 * {@link DurableQueuedMessage} itself, so an unset value produces the same message the no-arg constructor would.
 */
public final class DurableQueuedMessageBuilder {
    private QueueEntryId    id;
    private QueueName       queueName;
    private boolean         isBeingDelivered;
    private byte[]          messagePayload;
    private String          messagePayloadType;
    private Instant         addedTimestamp;
    private Instant         nextDeliveryTimestamp;
    private Instant         deliveryTimestamp;
    private int             totalDeliveryAttempts;
    private int             redeliveryAttempts;
    private String          lastDeliveryError;
    private boolean         isDeadLetterMessage;
    private MessageMetaData metaData;
    private DeliveryMode    deliveryMode = DeliveryMode.NORMAL;
    private String          key;
    private long            keyOrder     = -1L;

    /**
     * @param id the queue entry id
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setId(QueueEntryId id) {
        this.id = id;
        return this;
    }

    /**
     * @param queueName the queue the message belongs to
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setQueueName(QueueName queueName) {
        this.queueName = queueName;
        return this;
    }

    /**
     * @param isBeingDelivered whether the message is currently being delivered. Defaults to {@code false}
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setBeingDelivered(boolean isBeingDelivered) {
        this.isBeingDelivered = isBeingDelivered;
        return this;
    }

    /**
     * @param messagePayload the serialized payload
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setMessagePayload(byte[] messagePayload) {
        this.messagePayload = messagePayload;
        return this;
    }

    /**
     * @param messagePayloadType the payload's type name
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setMessagePayloadType(String messagePayloadType) {
        this.messagePayloadType = messagePayloadType;
        return this;
    }

    /**
     * @param addedTimestamp when the message was enqueued
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setAddedTimestamp(Instant addedTimestamp) {
        this.addedTimestamp = addedTimestamp;
        return this;
    }

    /**
     * @param nextDeliveryTimestamp when the message next becomes a delivery candidate
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setNextDeliveryTimestamp(Instant nextDeliveryTimestamp) {
        this.nextDeliveryTimestamp = nextDeliveryTimestamp;
        return this;
    }

    /**
     * @param deliveryTimestamp when the message was last delivered
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setDeliveryTimestamp(Instant deliveryTimestamp) {
        this.deliveryTimestamp = deliveryTimestamp;
        return this;
    }

    /**
     * @param totalDeliveryAttempts total number of delivery attempts so far. Defaults to {@code 0}
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setTotalDeliveryAttempts(int totalDeliveryAttempts) {
        this.totalDeliveryAttempts = totalDeliveryAttempts;
        return this;
    }

    /**
     * @param redeliveryAttempts number of redelivery attempts so far. Defaults to {@code 0}
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setRedeliveryAttempts(int redeliveryAttempts) {
        this.redeliveryAttempts = redeliveryAttempts;
        return this;
    }

    /**
     * @param lastDeliveryError the last delivery error, or {@code null}
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setLastDeliveryError(String lastDeliveryError) {
        this.lastDeliveryError = lastDeliveryError;
        return this;
    }

    /**
     * @param isDeadLetterMessage whether the message has been dead-lettered. Defaults to {@code false}
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setDeadLetterMessage(boolean isDeadLetterMessage) {
        this.isDeadLetterMessage = isDeadLetterMessage;
        return this;
    }

    /**
     * @param metaData the message meta-data
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setMetaData(MessageMetaData metaData) {
        this.metaData = metaData;
        return this;
    }

    /**
     * @param deliveryMode {@code NORMAL} or {@code IN_ORDER}. Defaults to {@code NORMAL}
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setDeliveryMode(DeliveryMode deliveryMode) {
        this.deliveryMode = deliveryMode;
        return this;
    }

    /**
     * @param key the ordering key, or {@code null} for an unordered message
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setKey(String key) {
        this.key = key;
        return this;
    }

    /**
     * @param keyOrder the order within {@code key}. Defaults to {@code -1}, the unordered-message value
     * @return this builder instance for fluent chaining
     */
    public DurableQueuedMessageBuilder setKeyOrder(long keyOrder) {
        this.keyOrder = keyOrder;
        return this;
    }

    /**
     * Builds the message.
     *
     * @return the message
     */
    @SuppressWarnings("removal")
    public DurableQueuedMessage build() {
        return new DurableQueuedMessage(id,
                                        queueName,
                                        isBeingDelivered,
                                        messagePayload,
                                        messagePayloadType,
                                        addedTimestamp,
                                        nextDeliveryTimestamp,
                                        deliveryTimestamp,
                                        totalDeliveryAttempts,
                                        redeliveryAttempts,
                                        lastDeliveryError,
                                        isDeadLetterMessage,
                                        metaData,
                                        deliveryMode,
                                        key,
                                        keyOrder);
    }
}
