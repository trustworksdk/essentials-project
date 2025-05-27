package dk.trustworks.essentials.components.foundation.messaging.queue.stats;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;

import java.time.OffsetDateTime;

/**
 * Represents statistical information for a specific queue.
 * Provides metadata and insights into the performance and activity of the queue over a defined time period.
 *
 * @param queueName              The name of the queue for which statistics are gathered.
 * @param fromTimestamp          The starting timestamp from which the statistics are considered.
 * @param totalMessagesDelivered The total number of messages delivered from the queue during the specified period.
 * @param avgDeliveryLatencyMs   The average message delivery latency in milliseconds.
 * @param firstDelivery          The timestamp of the first message delivery within the specified period.
 * @param lastDelivery           The timestamp of the last message delivery within the specified period.
 */
public record QueueStatistics(
        QueueName queueName,
        OffsetDateTime fromTimestamp,
        long totalMessagesDelivered,
        int avgDeliveryLatencyMs,
        OffsetDateTime firstDelivery,
        OffsetDateTime lastDelivery
) { }
