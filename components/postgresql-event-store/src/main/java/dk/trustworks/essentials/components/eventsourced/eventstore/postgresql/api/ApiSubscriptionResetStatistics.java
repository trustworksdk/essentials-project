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
 * Resume-point reset (replay) statistics for a subscription, as observed in the queried instance.
 * <p>
 * Throughput and lag read very differently during a replay, so a recent reset is the first thing to check when a
 * subscription looks far behind.
 *
 * @param resets                 how many times the subscription's resume point was reset
 * @param lastResetAt            when the resume point was last reset. Null if it was never reset in this instance
 * @param lastResetToGlobalOrder the global event order the subscription was last reset to. Null if it was never reset in this instance
 */
public record ApiSubscriptionResetStatistics(
        long resets,
        OffsetDateTime lastResetAt,
        Long lastResetToGlobalOrder
) {

    public static ApiSubscriptionResetStatistics from(SubscriptionStatistics.Reset reset) {
        requireNonNull(reset, "No reset provided");
        return new ApiSubscriptionResetStatistics(
                reset.resets(),
                ApiSubscriptionStatistics.toOffsetDateTime(reset.lastResetAt()),
                reset.lastResetToGlobalOrder() != null ? reset.lastResetToGlobalOrder().longValue() : null);
    }
}
