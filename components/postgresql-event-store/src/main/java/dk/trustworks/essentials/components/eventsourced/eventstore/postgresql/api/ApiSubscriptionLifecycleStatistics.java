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
 * Start/stop statistics for a subscription, as observed in the queried instance.
 *
 * @param starts        how many times the subscription has been started
 * @param stops         how many times the subscription has been stopped
 * @param lastStartedAt when the subscription was last started. Null if it never started in this instance
 * @param lastStoppedAt when the subscription was last stopped. Null if it never stopped in this instance
 */
public record ApiSubscriptionLifecycleStatistics(
        long starts,
        long stops,
        OffsetDateTime lastStartedAt,
        OffsetDateTime lastStoppedAt
) {

    public static ApiSubscriptionLifecycleStatistics from(SubscriptionStatistics.Lifecycle lifecycle) {
        requireNonNull(lifecycle, "No lifecycle provided");
        return new ApiSubscriptionLifecycleStatistics(
                lifecycle.starts(),
                lifecycle.stops(),
                ApiSubscriptionStatistics.toOffsetDateTime(lifecycle.lastStartedAt()),
                ApiSubscriptionStatistics.toOffsetDateTime(lifecycle.lastStoppedAt()));
    }
}
