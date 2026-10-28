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

import java.time.Duration;

/**
 * Builder for {@link ReleaseUndeliveredMessage}
 */
public final class ReleaseUndeliveredMessageBuilder {
    private QueueEntryId queueEntryId;
    private Duration     deliveryDelay;

    /**
     *
     * @param queueEntryId the unique id of the message to release
     * @return this builder instance
     */
    public ReleaseUndeliveredMessageBuilder setQueueEntryId(QueueEntryId queueEntryId) {
        this.queueEntryId = queueEntryId;
        return this;
    }

    /**
     *
     * @param deliveryDelay how long the queue will wait until it delivers the message again
     * @return this builder instance
     */
    public ReleaseUndeliveredMessageBuilder setDeliveryDelay(Duration deliveryDelay) {
        this.deliveryDelay = deliveryDelay;
        return this;
    }

    /**
     * Build a {@link ReleaseUndeliveredMessage} instance from the builder properties
     *
     * @return the {@link ReleaseUndeliveredMessage} instance
     */
    public ReleaseUndeliveredMessage build() {
        return new ReleaseUndeliveredMessage(queueEntryId, deliveryDelay);
    }
}
