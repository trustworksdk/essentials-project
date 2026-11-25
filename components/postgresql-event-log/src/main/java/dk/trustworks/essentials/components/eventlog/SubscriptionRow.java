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
package dk.trustworks.essentials.components.eventlog;

import java.time.OffsetDateTime;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One {@code el_progress} row as the admin listing reads it: the cluster's view, from the database, whichever node runs
 * the subscription.
 *
 * @param resumePoint  the first {@code global_order} not yet handled
 * @param leaseOwner   an exclusive row's lease owner ({@code [nodeName/]instance}), while one holds or held it
 * @param filterDigest the stored filter digest; empty without a filter
 * @param parkedHeads  parked heads (held aggregates)
 * @param parkedTotal  parked rows, heads and the events held behind them
 * @param lag          the aggregate type's highest {@code global_order} minus {@code resumePoint - 1}, at least 0; empty if the
 *                     type is not registered
 * @param retryingHeads  parked heads the engine retries by itself
 * @param terminalHeads  parked heads that failed with a {@link ParkEventException}: only an operator retries or discards them
 * @param oldestParkedAt when the oldest parked head was parked, empty without one
 * @param nextRetryAt    the earliest due automatic retry of a retrying head, empty without one
 * @param maxRetries     the most failed automatic retries of any parked head, 0 without one
 * @param lastFailedAt   when a parked head last failed, empty if none has
 */
public record SubscriptionRow(ProgressKey key, boolean exclusive, String consistencyMode, String progressModel, long resumePoint, long resetEpoch,
                              Optional<String> leaseOwner, Optional<OffsetDateTime> leaseUntil, OptionalLong fence, OffsetDateTime updatedAt,
                              Optional<String> filterDigest, long parkedHeads, long parkedTotal, OptionalLong lag, long retryingHeads, long terminalHeads,
                              Optional<OffsetDateTime> oldestParkedAt, Optional<OffsetDateTime> nextRetryAt, int maxRetries, Optional<OffsetDateTime> lastFailedAt) {
    /**
     * @deprecated use the canonical constructor, which carries the parked heads' retry state; this one reports every
     * parked head as retrying and no timestamps
     */
    @Deprecated(forRemoval = true, since = "0.62.0")
    public SubscriptionRow(ProgressKey key, boolean exclusive, String consistencyMode, String progressModel, long resumePoint, long resetEpoch,
                           Optional<String> leaseOwner, Optional<OffsetDateTime> leaseUntil, OptionalLong fence, OffsetDateTime updatedAt,
                           Optional<String> filterDigest, long parkedHeads, long parkedTotal, OptionalLong lag) {
        this(key, exclusive, consistencyMode, progressModel, resumePoint, resetEpoch, leaseOwner, leaseUntil, fence, updatedAt, filterDigest, parkedHeads, parkedTotal,
             lag, parkedHeads, 0, Optional.empty(), Optional.empty(), 0, Optional.empty());
    }

    public SubscriptionRow {
        requireNonNull(key, "No key provided");
        requireNonNull(consistencyMode, "No consistencyMode provided");
        requireNonNull(progressModel, "No progressModel provided");
        requireNonNull(leaseOwner, "No leaseOwner provided");
        requireNonNull(leaseUntil, "No leaseUntil provided");
        requireNonNull(fence, "No fence provided");
        requireNonNull(updatedAt, "No updatedAt provided");
        requireNonNull(filterDigest, "No filterDigest provided");
        requireNonNull(lag, "No lag provided");
        requireNonNull(oldestParkedAt, "No oldestParkedAt provided");
        requireNonNull(nextRetryAt, "No nextRetryAt provided");
        requireNonNull(lastFailedAt, "No lastFailedAt provided");
    }
}
