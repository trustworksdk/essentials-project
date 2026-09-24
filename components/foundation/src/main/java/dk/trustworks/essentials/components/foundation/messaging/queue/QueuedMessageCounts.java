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

import java.time.Instant;

/**
 * How much work a queue is holding, <b>across the whole cluster</b> — these come from the queue storage, not from
 * any one instance's counters. Contrast
 * {@link dk.trustworks.essentials.components.foundation.messaging.queue.observability.QueueStatistics}, which
 * covers only the deliveries this JVM performed.
 *
 * @param queueName                        the name of the queue
 * @param numberOfQueuedMessages           the total number of (non-dead-letter) messages queued
 * @param numberOfQueuedDeadLetterMessages the total number of dead-letter messages queued
 * @param numberOfMessagesBeingDelivered   how many of {@code numberOfQueuedMessages} are currently out with a
 *                                         consumer, or {@code null} when the implementation <b>cannot count them
 *                                         cluster-wide</b> — an engine whose consumers track deliveries in memory
 *                                         rather than in the queue storage. {@code null} means <em>unknown</em>,
 *                                         never zero: do not read it as "nothing is in flight"
 * @param oldestReadyMessageTimestamp      when the oldest message that is ready for delivery <em>became</em> ready,
 *                                         or {@code null} when nothing is ready. Together with
 *                                         {@code numberOfMessagesBeingDelivered} this is what separates "this
 *                                         queue has nothing to do" from "this queue is stalled" — a depth of zero
 *                                         handled messages means nothing on its own. When the in-flight count is
 *                                         unknown the age still says how long work has waited, but not whether
 *                                         anyone is working on it
 */
public record QueuedMessageCounts(QueueName queueName,
                                  long numberOfQueuedMessages,
                                  long numberOfQueuedDeadLetterMessages,
                                  Long numberOfMessagesBeingDelivered,
                                  Instant oldestReadyMessageTimestamp) {
}
