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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.SubscriptionStatistics;

import java.time.OffsetDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Event handling throughput, timing and failure statistics for a subscription, as observed in the queried instance.
 *
 * @param eventsPublishedToSubscriber   how many events the event store published to the subscriber's event flux
 * @param eventsHandled                 how many events the subscriber's event handler completed
 * @param failures                      how many events the subscriber's event handler failed to handle
 * @param lastEventHandledAt            when an event was last handled. Null if no event was handled in this instance
 * @param lastEventHandledGlobalOrder   the global event order of the last event handled. Null if no event was handled in this instance
 * @param averageHandlingTimeMillis     mean event-handler duration in milliseconds. Null if no event was handled in this instance
 * @param maxHandlingTimeMillis         slowest observed event-handler duration in milliseconds. Null if no event was handled in this instance
 * @param lastFailureAt                 when handling last failed. Null if handling never failed in this instance
 * @param lastFailureReason             exception type and message of the last handling failure. Null if handling never failed in this instance
 * @param lastNumberOfEventsRequested   the most recent number of events requested from the subscription's event flux
 */
public record ApiSubscriptionEventHandlingStatistics(
        long eventsPublishedToSubscriber,
        long eventsHandled,
        long failures,
        OffsetDateTime lastEventHandledAt,
        Long lastEventHandledGlobalOrder,
        Long averageHandlingTimeMillis,
        Long maxHandlingTimeMillis,
        OffsetDateTime lastFailureAt,
        String lastFailureReason,
        long lastNumberOfEventsRequested
) {

    public static ApiSubscriptionEventHandlingStatistics from(SubscriptionStatistics.EventHandling eventHandling) {
        requireNonNull(eventHandling, "No eventHandling provided");
        return new ApiSubscriptionEventHandlingStatistics(
                eventHandling.eventsPublishedToSubscriber(),
                eventHandling.eventsHandled(),
                eventHandling.failures(),
                ApiSubscriptionStatistics.toOffsetDateTime(eventHandling.lastEventHandledAt()),
                eventHandling.lastEventHandledGlobalOrder() != null ? eventHandling.lastEventHandledGlobalOrder().longValue() : null,
                ApiSubscriptionStatistics.toMillis(eventHandling.averageEventHandlingTime()),
                ApiSubscriptionStatistics.toMillis(eventHandling.maxEventHandlingTime()),
                ApiSubscriptionStatistics.toOffsetDateTime(eventHandling.lastFailureAt()),
                eventHandling.lastFailureReason(),
                eventHandling.lastNumberOfEventsRequested());
    }
}
