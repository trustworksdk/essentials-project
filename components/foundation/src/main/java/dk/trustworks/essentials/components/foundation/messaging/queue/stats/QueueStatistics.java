/*
 * Copyright 2021-2025 the original author or authors.
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
