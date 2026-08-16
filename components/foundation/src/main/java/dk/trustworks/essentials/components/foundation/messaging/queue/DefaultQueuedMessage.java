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

import java.time.*;
import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a message queued onto a Durable Queue
 */
public final class DefaultQueuedMessage implements QueuedMessage {
    public final QueueEntryId   id;
    public final QueueName      queueName;
    /**
     * The deserialized message payload
     */
    public final Message        message;
    public final OffsetDateTime addedTimestamp;
    public final OffsetDateTime nextDeliveryTimestamp;
    public final OffsetDateTime deliveryTimestamp;
    public final String         lastDeliveryError;
    public final int            totalDeliveryAttempts;
    public final int            redeliveryAttempts;
    public final boolean        isDeadLetterMessage;
    public final boolean        isBeingDelivered;

    private Duration manuallyRequestedRedeliveryDelay;

    /**
     * Creates a builder for a {@link DefaultQueuedMessage}.
     * <p>
     * This type is a row-mapper output — {@code QueuedMessageRowMapper} and the {@code DurableQueues} implementations
     * are effectively its only producers — and eleven positional arguments, six of which are nullable timestamps,
     * strings and flags, is exactly the shape where a transposed pair goes unnoticed. The builder names them.
     *
     * @return a new builder
     */
    public static DefaultQueuedMessageBuilder builder() {
        return new DefaultQueuedMessageBuilder();
    }

    /**
     * @param id                    the queue entry id
     * @param queueName             the queue this message belongs to
     * @param message               the deserialized message payload
     * @param addedTimestamp        when the message was added to the queue
     * @param nextDeliveryTimestamp when the message is next eligible for delivery. May be {@code null}
     * @param deliveryTimestamp     when the message was last delivered. May be {@code null}
     * @param lastDeliveryError     the error from the last failed delivery. May be {@code null}
     * @param totalDeliveryAttempts total number of delivery attempts so far
     * @param redeliveryAttempts    number of redelivery attempts so far
     * @param isDeadLetterMessage   whether the message has been marked as a dead-letter message
     * @param isBeingDelivered      whether the message is currently being delivered
     * @deprecated Use {@link #builder()}. Eleven positional arguments — six of them nullable or primitive — cannot be
     *         read at a call site, and two adjacent {@code OffsetDateTime}s or two adjacent {@code int}s swap silently.
     *         This constructor delegates to the same implementation and behaves identically.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public DefaultQueuedMessage(QueueEntryId id,
                                QueueName queueName,
                                Message message,
                                OffsetDateTime addedTimestamp,
                                OffsetDateTime nextDeliveryTimestamp,
                                OffsetDateTime deliveryTimestamp,
                                String lastDeliveryError,
                                int totalDeliveryAttempts,
                                int redeliveryAttempts,
                                boolean isDeadLetterMessage,
                                boolean isBeingDelivered) {
        this.id = requireNonNull(id, "No queue entry id provided");
        this.queueName = requireNonNull(queueName, "No queueName provided");
        this.message = requireNonNull(message, "No message provided");
        this.addedTimestamp = requireNonNull(addedTimestamp, "No addedTimestamp provided");
        this.nextDeliveryTimestamp = nextDeliveryTimestamp;
        this.deliveryTimestamp = deliveryTimestamp;
        this.lastDeliveryError = lastDeliveryError;
        this.totalDeliveryAttempts = totalDeliveryAttempts;
        this.redeliveryAttempts = redeliveryAttempts;
        this.isDeadLetterMessage = isDeadLetterMessage;
        this.isBeingDelivered = isBeingDelivered;
    }

    @Override
    public QueueEntryId getId() {
        return id;
    }

    @Override
    public QueueName getQueueName() {
        return queueName;
    }

    @Override
    public Message getMessage() {
        return message;
    }

    @Override
    public DeliveryMode getDeliveryMode() {
        return DeliveryMode.NORMAL;
    }

    @Override
    public boolean isBeingDelivered() {
        return isBeingDelivered;
    }

    @Override
    public OffsetDateTime getAddedTimestamp() {
        return addedTimestamp;
    }

    @Override
    public OffsetDateTime getNextDeliveryTimestamp() {
        return nextDeliveryTimestamp;
    }

    @Override
    public OffsetDateTime getDeliveryTimestamp() {
        return deliveryTimestamp;
    }

    @Override
    public void markForRedeliveryIn(Duration deliveryDelay) {
        this.manuallyRequestedRedeliveryDelay = deliveryDelay;
    }

    @Override
    public boolean isManuallyMarkedForRedelivery() {
        return manuallyRequestedRedeliveryDelay != null;
    }

    @Override
    public Duration getRedeliveryDelay() {
        return manuallyRequestedRedeliveryDelay;
    }

    @Override
    public String getLastDeliveryError() {
        return lastDeliveryError;
    }

    @Override
    public int getTotalDeliveryAttempts() {
        return totalDeliveryAttempts;
    }

    @Override
    public int getRedeliveryAttempts() {
        return redeliveryAttempts;
    }

    @Override
    public boolean isDeadLetterMessage() {
        return isDeadLetterMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DefaultQueuedMessage)) return false;
        DefaultQueuedMessage that = (DefaultQueuedMessage) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "DefaultQueuedMessage{" +
                "id=" + id +
                ", queueName=" + queueName +
                ", message=" + message +
                ", addedTimestamp=" + addedTimestamp +
                ", nextDeliveryTimestamp=" + nextDeliveryTimestamp +
                ", deliveryTimestamp=" + deliveryTimestamp +
                ", lastDeliveryError='" + lastDeliveryError + '\'' +
                ", totalDeliveryAttempts=" + totalDeliveryAttempts +
                ", redeliveryAttempts=" + redeliveryAttempts +
                ", isDeadLetterMessage=" + isDeadLetterMessage +
                ", isBeingDelivered=" + isBeingDelivered +
                ", manuallyRequestedRedeliveryDelay=" + manuallyRequestedRedeliveryDelay +
                '}';
    }
}
