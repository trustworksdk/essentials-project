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
 * Event-store polling statistics for a subscription, as observed in the queried instance.
 * <p>
 * Only the polling path updates these counters. A subscription that is served over Change Data Capture leaves them at
 * zero, which is expected and not a sign of a stalled subscription - read them together with the CDC status.
 *
 * @param polls                                how many times the event store was queried for this subscriber
 * @param pollsWithoutEvents                   how many of those queries returned no events
 * @param skippedPolls                         how many polls were skipped entirely because no new events were persisted
 * @param lastPollAt                           when the event store was last polled. Null if it was never polled in this instance
 * @param lastPollDurationMillis               how long the most recent poll took, in milliseconds. Null if it was never polled in this instance
 * @param consecutiveNoPersistedEventsReturned the most recently reported number of consecutive polls returning no events
 * @param gapReconciliations                   how many times transient global-event-order gaps were reconciled after a poll
 */
public record ApiSubscriptionPollingStatistics(
        long polls,
        long pollsWithoutEvents,
        long skippedPolls,
        OffsetDateTime lastPollAt,
        Long lastPollDurationMillis,
        int consecutiveNoPersistedEventsReturned,
        long gapReconciliations
) {

    public static ApiSubscriptionPollingStatistics from(SubscriptionStatistics.Polling polling) {
        requireNonNull(polling, "No polling provided");
        return new ApiSubscriptionPollingStatistics(
                polling.polls(),
                polling.pollsWithoutEvents(),
                polling.skippedPolls(),
                ApiSubscriptionStatistics.toOffsetDateTime(polling.lastPollAt()),
                ApiSubscriptionStatistics.toMillis(polling.lastPollDuration()),
                polling.consecutiveNoPersistedEventsReturned(),
                polling.gapReconciliations());
    }
}
