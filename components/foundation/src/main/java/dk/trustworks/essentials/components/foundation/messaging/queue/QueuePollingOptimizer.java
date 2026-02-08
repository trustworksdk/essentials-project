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

import dk.trustworks.essentials.components.foundation.messaging.queue.operations.GetNextMessageReadyForDelivery;

import java.util.List;

/**
 * Optimizer designed to work together with the {@link DefaultDurableQueueConsumer}<br>
 * The optimizer is responsible for optimizing the frequency by which the {@link DefaultDurableQueueConsumer}
 * is polling the underlying database for new messages related to a given {@link QueueName}.<br>
 * If a given Queue doesn't experience a high influx of message, or a lot of message's have ({@link QueuedMessage#getNextDeliveryTimestamp()})
 * that is further into the future, then it doesn't make sense to poll the database too often.<br>
 * The {@link SimpleQueuePollingOptimizer} supports extending the polling sleep time (i.e. the time between calls to
 * {@link DurableQueues#getNextMessageReadyForDelivery(GetNextMessageReadyForDelivery)}) up until a given threshold.
 *
 * @see #None()
 * @see SimpleQueuePollingOptimizer
 */
public interface QueuePollingOptimizer extends DurableQueueConsumerNotifications {
    static QueuePollingOptimizer None() {
        return new QueuePollingOptimizer() {
            @Override
            public void queuePollingReturnedNoMessages() {
            }

            @Override
            public void queuePollingReturnedMessage(QueuedMessage queuedMessage) {
            }

            @Override
            public boolean shouldSkipPolling() {
                return false;
            }

            @Override
            public void messageAdded(QueuedMessage queuedMessage) {
            }

            @Override
            public String toString() {
                return "NoQueuePollingOptimizer";
            }
        };
    }

    void queuePollingReturnedNoMessages();

    void queuePollingReturnedMessage(QueuedMessage queuedMessage);

    default void queuePollingReturnedMessages(List<QueuedMessage> queuedMessages) {}

    boolean shouldSkipPolling();

}
