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
 * Fenced-lock statistics for an exclusive subscription, as observed in the queried instance.
 * <p>
 * Many acquisitions and releases relative to the instance's uptime means lock ownership is flapping between
 * instances, typically because the lock time-to-live is shorter than the time the subscription needs to resume.
 * A non-exclusive subscription leaves all of these at zero.
 *
 * @param acquisitions   how many times the lock was acquired by this instance
 * @param releases       how many times the lock was released by this instance
 * @param currentlyHeld  whether this instance currently holds the lock
 * @param lastAcquiredAt when the lock was last acquired. Null if this instance never acquired it
 * @param lastReleasedAt when the lock was last released. Null if this instance never released it
 */
public record ApiSubscriptionLockStatistics(
        long acquisitions,
        long releases,
        boolean currentlyHeld,
        OffsetDateTime lastAcquiredAt,
        OffsetDateTime lastReleasedAt
) {

    public static ApiSubscriptionLockStatistics from(SubscriptionStatistics.Lock lock) {
        requireNonNull(lock, "No lock provided");
        return new ApiSubscriptionLockStatistics(
                lock.acquisitions(),
                lock.releases(),
                lock.currentlyHeld(),
                ApiSubscriptionStatistics.toOffsetDateTime(lock.lastAcquiredAt()),
                ApiSubscriptionStatistics.toOffsetDateTime(lock.lastReleasedAt()));
    }
}
