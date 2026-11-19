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

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A local snapshot of a subscription, read without a statement; the cluster view is the admin
 * listing.
 *
 * @param definition        what was subscribed
 * @param progressModel     {@code PERIODIC} or {@code IN_HANDLER_TRANSACTION}
 * @param phase             where the delivery thread is: {@code STARTING}, {@code STANDBY}, {@code SUSPENDED},
 *                          {@code CATCHING_UP}, {@code WAITING_FOR_WATERMARK}, {@code ATTACHING}, {@code BRIDGING},
 *                          {@code ATTACHED} or {@code STOPPED}
 * @param resumePoint       the resume point this event log last committed: the first {@code global_order} not yet
 *                          known to be handled
 * @param receivedWatermark the highest {@code global_order} below which the subscription has received every row
 * @param lag               the events at or below the aggregate type's tail-reader cursor not yet behind the resume
 *                          point; empty while no reader runs for the type here
 * @param leaseState        an exclusive subscription's lease: {@code STANDBY}, {@code OWNED}, {@code SUSPENDED} or
 *                          {@code LOST}; empty for a per-instance one
 * @param fence             the fence the exclusive subscription delivers under, empty unless it owns the lease
 * @param heldAggregates    aggregates held behind a parked head (one per parked head)
 * @param lastError         the last failure, as {@code class: message}, if any
 */
public record SubscriptionStatus(SubscriptionDefinition definition, String progressModel, String phase, long resumePoint, long receivedWatermark,
                                 OptionalLong lag, Optional<String> leaseState, OptionalLong fence, int heldAggregates, Optional<String> lastError) {
    public SubscriptionStatus {
        requireNonNull(definition, "No definition provided");
        requireNonNull(progressModel, "No progressModel provided");
        requireNonNull(phase, "No phase provided");
        requireNonNull(lag, "No lag provided");
        requireNonNull(leaseState, "No leaseState provided");
        requireNonNull(fence, "No fence provided");
        requireNonNull(lastError, "No lastError provided");
    }
}
