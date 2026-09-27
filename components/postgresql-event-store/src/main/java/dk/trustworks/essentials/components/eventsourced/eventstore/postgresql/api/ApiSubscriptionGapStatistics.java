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
 * Global-event-order gap statistics for a subscription, as observed in the queried instance.
 * <p>
 * A gap is a global event order the subscription queried for and got no event for - usually a transaction that has
 * not committed yet, or one that rolled back. It is registered as a transient gap and asked for again; it is resolved
 * if its event turns up, or promoted to permanent once it is judged never to arrive, after which the subscription
 * stops waiting for it. Counted on every path that reconciles gaps, including the Change Data Capture catch-up that
 * the polling statistics do not cover.
 * <p>
 * Transient gaps coming and going is normal under concurrent writers. A steadily rising
 * {@link #promotedToPermanentGaps()} is the number to watch: each is a global event order this subscription will
 * never deliver an event for.
 *
 * @param newTransientGaps             how many gaps were first registered as transient
 * @param resolvedTransientGaps        how many transient gaps were resolved because their event arrived
 * @param promotedToPermanentGaps      how many transient gaps the subscription gave up on as permanent
 * @param lastNewTransientGapAt        when a transient gap was last registered. Null if none was in this instance
 * @param lastPromotedToPermanentGapAt when a gap was last promoted to permanent. Null if none was in this instance
 */
public record ApiSubscriptionGapStatistics(
        long newTransientGaps,
        long resolvedTransientGaps,
        long promotedToPermanentGaps,
        OffsetDateTime lastNewTransientGapAt,
        OffsetDateTime lastPromotedToPermanentGapAt
) {

    public static ApiSubscriptionGapStatistics from(SubscriptionStatistics.Gaps gaps) {
        requireNonNull(gaps, "No gaps provided");
        return new ApiSubscriptionGapStatistics(
                gaps.newTransientGaps(),
                gaps.resolvedTransientGaps(),
                gaps.promotedToPermanentGaps(),
                ApiSubscriptionStatistics.toOffsetDateTime(gaps.lastNewTransientGapAt()),
                ApiSubscriptionStatistics.toOffsetDateTime(gaps.lastPromotedToPermanentGapAt()));
    }
}
