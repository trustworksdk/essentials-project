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
 * Updated whenever the subscription reads events by polling the event store. With Change Data Capture enabled that
 * still happens: a subscription polls when it is established while CDC is not yet active - common at start-up - and
 * when CDC becomes unavailable and it falls back to polling, and stops once it switches to the CDC bus. Non-zero
 * counters on a CDC-enabled store are therefore normal; read them together with the CDC status. The CDC catch-up
 * (backfill) that runs before a subscription switches to live CDC delivery is not counted here.
 *
 * @param polls                                how many times the event store was queried for this subscriber
 * @param pollsWithoutEvents                   how many of those queries returned no events
 * @param skippedPolls                         how many polls were skipped entirely because no new events were persisted
 * @param lastPollAt                           when the event store was last polled. Null if it was never polled in this instance
 * @param lastPollDurationMillis               how long the most recent poll took, in milliseconds. Null if it was never polled in this instance
 * @param consecutiveNoPersistedEventsReturned the most recently reported number of consecutive polls returning no events
 * @param gapReconciliations                   how many polls ran gap reconciliation - one per poll that queried the event
 *                                             store, whether or not there was a gap. What reconciliation found is in
 *                                             {@link ApiSubscriptionStatistics#gaps()}
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
