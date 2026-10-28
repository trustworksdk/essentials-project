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

package dk.trustworks.essentials.components.foundation.messaging.queue.operations;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Release a message that was claimed for delivery but never reached a message handler, so it can be delivered again
 * after the specified <code>deliveryDelay</code>.<br>
 * Unlike {@link RetryMessage} the release is not a delivery attempt: {@link QueuedMessage#getRedeliveryAttempts()} and
 * {@link QueuedMessage#getLastDeliveryError()} are left untouched. Only a message that is still marked as being
 * delivered is released.<br><br>
 * Operation also matches {@link DurableQueuesInterceptor#intercept(ReleaseUndeliveredMessage, InterceptorChain)}
 */
public final class ReleaseUndeliveredMessage {
    public final QueueEntryId queueEntryId;
    private      Duration     deliveryDelay;

    /**
     * Create a new builder that produces a new {@link ReleaseUndeliveredMessage} instance
     *
     * @return a new {@link ReleaseUndeliveredMessageBuilder} instance
     */
    public static ReleaseUndeliveredMessageBuilder builder() {
        return new ReleaseUndeliveredMessageBuilder();
    }

    /**
     * Release a message that was claimed for delivery but never reached a message handler
     *
     * @param queueEntryId  the unique id of the message to release
     * @param deliveryDelay how long the queue will wait until it delivers the message again
     */
    public ReleaseUndeliveredMessage(QueueEntryId queueEntryId, Duration deliveryDelay) {
        this.queueEntryId = requireNonNull(queueEntryId, "No queueEntryId provided");
        this.deliveryDelay = deliveryDelay;
    }

    /**
     *
     * @return the unique id of the message to release
     */
    public QueueEntryId getQueueEntryId() {
        return queueEntryId;
    }

    /**
     *
     * @return how long the queue will wait until it delivers the message again
     */
    public Duration getDeliveryDelay() {
        return deliveryDelay;
    }

    /**
     *
     * @param deliveryDelay how long the queue will wait until it delivers the message again
     */
    public void setDeliveryDelay(Duration deliveryDelay) {
        this.deliveryDelay = deliveryDelay;
    }

    @Override
    public String toString() {
        return "ReleaseUndeliveredMessage{" +
                "queueEntryId=" + queueEntryId +
                ", deliveryDelay=" + deliveryDelay +
                '}';
    }

    public void validate() {
        requireNonNull(queueEntryId, "You must provide a queueEntryId");
        requireNonNull(deliveryDelay, "You must provide a deliveryDelay");
    }
}
