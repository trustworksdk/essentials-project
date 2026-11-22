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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The decorator {@link QueuedMessage#withMessage(Message)} returns: it carries a replacement {@link Message} and
 * delegates everything else to the {@link QueuedMessage} it was made from.
 */
final class MessageReplacedQueuedMessage implements QueuedMessage {
    private final QueuedMessage delegate;
    private final Message       message;

    static QueuedMessage of(QueuedMessage delegate, Message message) {
        requireNonNull(delegate, "No delegate provided");
        requireNonNull(message, "No message provided");
        // Never stack decorators: replacing twice wraps the original once
        var original = delegate instanceof MessageReplacedQueuedMessage replaced ? replaced.delegate : delegate;
        return new MessageReplacedQueuedMessage(original, message);
    }

    private MessageReplacedQueuedMessage(QueuedMessage delegate, Message message) {
        this.delegate = delegate;
        this.message = message;
    }

    @Override
    public Message getMessage() {
        return message;
    }

    @Override
    public Object getPayload() {
        return message.getPayload();
    }

    @Override
    public MessageMetaData getMetaData() {
        return message.getMetaData();
    }

    @Override
    public QueueEntryId getId() {
        return delegate.getId();
    }

    @Override
    public QueueName getQueueName() {
        return delegate.getQueueName();
    }

    @Override
    public OffsetDateTime getAddedTimestamp() {
        return delegate.getAddedTimestamp();
    }

    @Override
    public OffsetDateTime getNextDeliveryTimestamp() {
        return delegate.getNextDeliveryTimestamp();
    }

    @Override
    public String getLastDeliveryError() {
        return delegate.getLastDeliveryError();
    }

    @Override
    public boolean isDeadLetterMessage() {
        return delegate.isDeadLetterMessage();
    }

    @Override
    public int getTotalDeliveryAttempts() {
        return delegate.getTotalDeliveryAttempts();
    }

    @Override
    public int getRedeliveryAttempts() {
        return delegate.getRedeliveryAttempts();
    }

    @Override
    public DeliveryMode getDeliveryMode() {
        return delegate.getDeliveryMode();
    }

    @Override
    public boolean isBeingDelivered() {
        return delegate.isBeingDelivered();
    }

    @Override
    public OffsetDateTime getDeliveryTimestamp() {
        return delegate.getDeliveryTimestamp();
    }

    @Override
    public void markForRedeliveryIn(Duration deliveryDelay) {
        delegate.markForRedeliveryIn(deliveryDelay);
    }

    @Override
    public boolean isManuallyMarkedForRedelivery() {
        return delegate.isManuallyMarkedForRedelivery();
    }

    @Override
    public Duration getRedeliveryDelay() {
        return delegate.getRedeliveryDelay();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QueuedMessage that)) return false;
        return getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    @Override
    public String toString() {
        return "MessageReplacedQueuedMessage{" +
                "message=" + message +
                ", original=" + delegate +
                '}';
    }
}
