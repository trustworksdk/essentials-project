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
import java.util.Objects;

import static dk.trustworks.essentials.components.foundation.messaging.queue.QueuedMessage.DeliveryMode;

/**
 * Represents the default implementation of the {@link QueuedStatisticsMessage} interface.
 * This class encapsulates a message in a queue along with its associated metadata
 * and statistical details, including delivery mode, delivery attempts, latency,
 * and timestamps relevant to the message's lifecycle.
 */
public class DefaultQueuedStatisticsMessage implements QueuedStatisticsMessage {
    public final QueueEntryId    id;
    public final QueueName       queueName;
    public final OffsetDateTime  addedTimestamp;
    public final OffsetDateTime  deliveryTimestamp;
    public final OffsetDateTime  deletionTimestamp;
    public final DeliveryMode    deliveryMode;
    public final int             totalDeliveryAttempts;
    public final int             redeliveryAttempts;
    public final int             deliveryLatency;
    public final MessageMetaData metaData;

    /**
     * Creates a builder for a {@link DefaultQueuedStatisticsMessage}.
     *
     * @return a new builder
     */
    public static DefaultQueuedStatisticsMessageBuilder builder() {
        return new DefaultQueuedStatisticsMessageBuilder();
    }

    /**
     * @param id                    the queue entry id
     * @param queueName             the queue the message belonged to
     * @param addedTimestamp        when the message was added to the queue
     * @param deliveryTimestamp     when the message was delivered
     * @param deletionTimestamp     when the message was deleted from the queue
     * @param deliveryMode          the delivery mode the message was queued under
     * @param totalDeliveryAttempts total number of delivery attempts
     * @param redeliveryAttempts    number of redelivery attempts
     * @param deliveryLatency       the delivery latency
     * @param metaData              the message meta data
     * @deprecated Use {@link #builder()}. Three adjacent {@code OffsetDateTime}s followed by three adjacent
     *         {@code int}s cannot be checked by the compiler and cannot be read at the call site. This constructor
     *         delegates to the same implementation and behaves identically.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public DefaultQueuedStatisticsMessage(QueueEntryId id,
                                          QueueName queueName,
                                          OffsetDateTime addedTimestamp,
                                          OffsetDateTime deliveryTimestamp,
                                          OffsetDateTime deletionTimestamp,
                                          DeliveryMode deliveryMode,
                                          int totalDeliveryAttempts,
                                          int redeliveryAttempts,
                                          int deliveryLatency,
                                          MessageMetaData metaData) {
        this.id = id;
        this.queueName = queueName;
        this.addedTimestamp = addedTimestamp;
        this.deliveryTimestamp = deliveryTimestamp;
        this.deletionTimestamp = deletionTimestamp;
        this.deliveryMode = deliveryMode;
        this.totalDeliveryAttempts = totalDeliveryAttempts;
        this.redeliveryAttempts = redeliveryAttempts;
        this.deliveryLatency = deliveryLatency;
        this.metaData = metaData;
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
    public OffsetDateTime getAddedTimestamp() {
        return addedTimestamp;
    }

    @Override
    public OffsetDateTime getDeliveryTimestamp() {
        return deliveryTimestamp;
    }

    @Override
    public OffsetDateTime getDeletionTimestamp() {
        return deletionTimestamp;
    }

    @Override
    public Integer getTotalAttempts() {
        return totalDeliveryAttempts;
    }

    @Override
    public Integer getRedeliveryAttempts() {
        return redeliveryAttempts;
    }

    @Override
    public DeliveryMode getDeliveryMode() {
        return deliveryMode;
    }

    @Override
    public MessageMetaData getMetaData() {
        return metaData;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DefaultQueuedStatisticsMessage that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "DefaultQueuedStatisticsMessage{" +
                "id=" + id +
                ", queueName=" + queueName +
                ", addedTimestamp=" + addedTimestamp +
                ", deliveryTimestamp=" + deliveryTimestamp +
                ", deletionTimestamp=" + deletionTimestamp +
                ", deliveryMode=" + deliveryMode +
                ", totalDeliveryAttempts=" + totalDeliveryAttempts +
                ", redeliveryAttempts=" + redeliveryAttempts +
                ", deliveryLatency=" + deliveryLatency +
                ", metaData=" + metaData +
                '}';
    }
}
