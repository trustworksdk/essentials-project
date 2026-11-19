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
 * One {@code el_progress} row as the admin listing reads it (design {@code docs/archive/event-store-engine-phase8.md}
 * §4.1): the cluster's view, from the database, whichever node runs the subscription.
 *
 * @param resumePoint  the first {@code global_order} not yet handled
 * @param leaseOwner   an exclusive row's lease owner ({@code [nodeName/]instance}), while one holds or held it
 * @param filterDigest the stored filter digest (phase 8 §2.6); empty without a filter
 * @param parkedHeads  parked heads (held aggregates)
 * @param parkedTotal  parked rows, heads and the events held behind them
 * @param lag          the aggregate type's highest {@code global_order} minus {@code resumePoint - 1}, at least 0; empty if the
 *                     type is not registered
 */
public record SubscriptionRow(ProgressKey key, boolean exclusive, String consistencyMode, String progressModel, long resumePoint, long resetEpoch,
                              Optional<String> leaseOwner, Optional<OffsetDateTime> leaseUntil, OptionalLong fence, OffsetDateTime updatedAt,
                              Optional<String> filterDigest, long parkedHeads, long parkedTotal, OptionalLong lag) {
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
    }
}
