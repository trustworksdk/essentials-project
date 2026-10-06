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

package dk.trustworks.essentials.components.foundation.messaging.queue.observability;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;

import java.time.Duration;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Records every delivery outcome into a {@link QueueStatisticsRegistry}.
 * <p>
 * Unlike the event store's {@code StatisticsCollectingEventStoreSubscriptionObserver}, this is not a delegating
 * decorator: {@link DurableQueueMessageObserver} is not a single-slot SPI, so this observer and, say, a Micrometer
 * one are composed side by side with {@link DurableQueueMessageObserver#composite(java.util.List)} instead of one
 * wrapping the other.
 */
public class StatisticsCollectingDurableQueueMessageObserver implements DurableQueueMessageObserver {
    private final QueueStatisticsRegistry registry;

    /**
     * @param registry the registry to record into
     */
    public StatisticsCollectingDurableQueueMessageObserver(QueueStatisticsRegistry registry) {
        this.registry = requireNonNull(registry, "No registry provided");
    }

    /**
     * @return the registry this observer records into
     */
    public QueueStatisticsRegistry getRegistry() {
        return registry;
    }

    @Override
    public void messageHandled(QueuedMessage message, Duration handlerDuration) {
        statisticsFor(message).ifPresent(statistics -> statistics.recordHandled(handlerDuration));
    }

    @Override
    public void messageRedeliveryRequested(QueuedMessage message) {
        statisticsFor(message).ifPresent(MutableQueueStatistics::recordRedeliveryRequested);
    }

    @Override
    public void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {
        statisticsFor(message).ifPresent(statistics -> statistics.recordRetried(cause));
    }

    @Override
    public void messageDeadLettered(QueuedMessage message, Throwable cause, MessageDeliveryOutcome outcome) {
        statisticsFor(message).ifPresent(statistics -> statistics.recordDeadLettered(cause));
    }

    private Optional<MutableQueueStatistics> statisticsFor(QueuedMessage message) {
        if (message == null || message.getQueueName() == null) {
            return Optional.empty();
        }
        return registry.statisticsFor(message.getQueueName());
    }

    @Override
    public String toString() {
        return "StatisticsCollectingDurableQueueMessageObserver";
    }
}
