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

package dk.trustworks.essentials.components.foundation.messaging.queue.api;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;

import java.time.OffsetDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a message that has been queued and its associated metadata.
 * This record provides details about the enqueued message,
 * such as its identifiers, payload, timestamps, delivery attempts, and its current state.
 *
 * @param orderedMessageKey       the key of an {@link OrderedMessage}; null for an unordered message
 * @param orderedMessageOrder     the order of an {@link OrderedMessage}; null for an unordered message
 * @param referencedAggregateType set when the message refers to a persisted event rather than carrying one - as the
 *                                inbox messages of an {@code EventProcessor} do: the aggregate type, with
 *                                {@code orderedMessageKey} the aggregate id and {@code orderedMessageOrder} the event
 *                                order. Routing information, not payload, so it is present whatever the caller's
 *                                payload rights. Null for every other message
 */
public record ApiQueuedMessage(
        QueueEntryId id,
        QueueName queueName,
        String payload,
        OffsetDateTime addedTimestamp,
        OffsetDateTime nextDeliveryTimestamp,
        OffsetDateTime deliveryTimestamp,
        String lastDeliveryError,
        int totalDeliveryAttempts,
        int redeliveryAttempts,
        boolean isDeadLetterMessage,
        boolean isBeingDelivered,
        String orderedMessageKey,
        Long orderedMessageOrder,
        String referencedAggregateType
) {

    /**
     * Constructs an {@link ApiQueuedMessage} instance from a {@link QueuedMessage} and a provided payload.
     * This method extracts relevant metadata from the {@link QueuedMessage} and assigns the provided payload.
     *
     * @param message the {@link QueuedMessage} containing metadata and identifiers about the message
     * @param payload the payload to be associated with the constructed {@link ApiQueuedMessage}
     * @return an instance of {@link ApiQueuedMessage} containing the provided payload and metadata from the {@link QueuedMessage}
     */
    public static ApiQueuedMessage from(QueuedMessage message, String payload) {
        requireNonNull(message, "No message provided");
        return new ApiQueuedMessage(
                message.getId(),
                message.getQueueName(),
                payload,
                message.getAddedTimestamp(),
                message.getNextDeliveryTimestamp(),
                message.getDeliveryTimestamp(),
                message.getLastDeliveryError(),
                message.getTotalDeliveryAttempts(),
                message.getRedeliveryAttempts(),
                message.isDeadLetterMessage(),
                message.isBeingDelivered(),
                message.getMessage() instanceof OrderedMessage ordered ? ordered.getKey() : null,
                message.getMessage() instanceof OrderedMessage ordered ? ordered.getOrder() : null,
                referencedAggregateType(message.getMessage())
        );
    }

    /**
     * The metadata key an {@code EventProcessor} marks its event-reference messages with - the value of
     * {@code AbstractEventProcessor.EventReferenceOrderedMessage.EVENT_REFERENCE_METADATA_KEY}. Repeated rather than
     * referenced: that class, and the {@code AggregateType} such a message carries, live in packages that depend on
     * this one, and naming either here is a package cycle {@code EssentialsArchitectureTest} refuses.
     */
    public static final String EVENT_REFERENCE_METADATA_KEY = "EVENT_REFERENCE";

    /**
     * An {@code EventProcessor}'s inbox message refers to the persisted event at its payload's aggregate type, its key
     * (aggregate id) and its order, rather than carrying the event. Recognised the way the processor recognises it - by
     * its metadata marker, not by the payload's type
     */
    private static String referencedAggregateType(Message message) {
        return message instanceof OrderedMessage
                       && message.getMetaData() != null
                       && "true".equals(message.getMetaData().get(EVENT_REFERENCE_METADATA_KEY))
                       && message.getPayload() != null
               ? message.getPayload().toString()
               : null;
    }

    /**
     * Constructs an {@link ApiQueuedMessage} instance from a {@link QueuedMessage}
     * using metadata from the {@link QueuedMessage} and sets the payload to null.
     *
     * @param message the {@link QueuedMessage} containing metadata and identifiers about the message
     * @return an instance of {@link ApiQueuedMessage} with metadata from the {@link QueuedMessage}
     * and a null payload
     */
    public static ApiQueuedMessage from(QueuedMessage message) {
        return from(message, null);
    }

    @Override
    public String toString() {
        return "ApiQueuedMessage{" +
                "id=" + id +
                ", queueName=" + queueName +
                ", addedTimestamp=" + addedTimestamp +
                ", nextDeliveryTimestamp=" + nextDeliveryTimestamp +
                ", deliveryTimestamp=" + deliveryTimestamp +
                ", lastDeliveryError='" + lastDeliveryError + '\'' +
                ", totalDeliveryAttempts=" + totalDeliveryAttempts +
                ", redeliveryAttempts=" + redeliveryAttempts +
                ", isDeadLetterMessage=" + isDeadLetterMessage +
                ", isBeingDelivered=" + isBeingDelivered +
                ", orderedMessageKey='" + orderedMessageKey + '\'' +
                ", orderedMessageOrder=" + orderedMessageOrder +
                ", referencedAggregateType='" + referencedAggregateType + '\'' +
                '}';
    }
}
