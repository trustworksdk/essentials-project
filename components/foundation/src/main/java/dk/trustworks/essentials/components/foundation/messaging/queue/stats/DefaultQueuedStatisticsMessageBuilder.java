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

package dk.trustworks.essentials.components.foundation.messaging.queue.stats;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;

import java.time.OffsetDateTime;

/**
 * Builder for {@link DefaultQueuedStatisticsMessage}, obtained from {@link DefaultQueuedStatisticsMessage#builder()}.
 */
public final class DefaultQueuedStatisticsMessageBuilder {
    private QueueEntryId    id;
    private QueueName       queueName;
    private OffsetDateTime  addedTimestamp;
    private OffsetDateTime  deliveryTimestamp;
    private OffsetDateTime  deletionTimestamp;
    private QueuedMessage.DeliveryMode    deliveryMode;
    private int             totalDeliveryAttempts;
    private int             redeliveryAttempts;
    private int             deliveryLatency;
    private MessageMetaData metaData;

    /**
     * @param id the queue entry id
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setId(QueueEntryId id) {
        this.id = id;
        return this;
    }

    /**
     * @param queueName the queue the message belonged to
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setQueueName(QueueName queueName) {
        this.queueName = queueName;
        return this;
    }

    /**
     * @param addedTimestamp when the message was added to the queue
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setAddedTimestamp(OffsetDateTime addedTimestamp) {
        this.addedTimestamp = addedTimestamp;
        return this;
    }

    /**
     * @param deliveryTimestamp when the message was delivered
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setDeliveryTimestamp(OffsetDateTime deliveryTimestamp) {
        this.deliveryTimestamp = deliveryTimestamp;
        return this;
    }

    /**
     * @param deletionTimestamp when the message was deleted from the queue
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setDeletionTimestamp(OffsetDateTime deletionTimestamp) {
        this.deletionTimestamp = deletionTimestamp;
        return this;
    }

    /**
     * @param deliveryMode the delivery mode the message was queued under
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setDeliveryMode(QueuedMessage.DeliveryMode deliveryMode) {
        this.deliveryMode = deliveryMode;
        return this;
    }

    /**
     * @param totalDeliveryAttempts total number of delivery attempts. Defaults to 0
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setTotalDeliveryAttempts(int totalDeliveryAttempts) {
        this.totalDeliveryAttempts = totalDeliveryAttempts;
        return this;
    }

    /**
     * @param redeliveryAttempts number of redelivery attempts. Defaults to 0
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setRedeliveryAttempts(int redeliveryAttempts) {
        this.redeliveryAttempts = redeliveryAttempts;
        return this;
    }

    /**
     * @param deliveryLatency the delivery latency. Defaults to 0
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setDeliveryLatency(int deliveryLatency) {
        this.deliveryLatency = deliveryLatency;
        return this;
    }

    /**
     * @param metaData the message meta data
     * @return this builder instance for fluent chaining
     */
    public DefaultQueuedStatisticsMessageBuilder setMetaData(MessageMetaData metaData) {
        this.metaData = metaData;
        return this;
    }

    /**
     * @return the new {@link DefaultQueuedStatisticsMessage}
     */
    @SuppressWarnings("removal")
    public DefaultQueuedStatisticsMessage build() {
        return new DefaultQueuedStatisticsMessage(id,
                                                  queueName,
                                                  addedTimestamp,
                                                  deliveryTimestamp,
                                                  deletionTimestamp,
                                                  deliveryMode,
                                                  totalDeliveryAttempts,
                                                  redeliveryAttempts,
                                                  deliveryLatency,
                                                  metaData);
    }
}
