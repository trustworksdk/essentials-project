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

package dk.trustworks.essentials.components.eventlog.processor.api;

import dk.trustworks.essentials.components.eventlog.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * One subscription as the admin API shows it: {@link SubscriptionRow}'s fields.
 * <p>
 * Of its {@code parkedHeads}, {@code retryingHeads} are retried by the engine on their own (a backoff doubling from the
 * subscription's initial parked retry delay up to its maximum) and {@code terminalHeads} wait for an operator's retry
 * or discard: their latest failure was a {@code ParkEventException} or malformed JSON.
 */
public record ApiEventLogSubscription(String subscriber, String aggregateType, String instanceName, boolean exclusive, String consistencyMode,
                                      String progressModel, long resumePoint, long resetEpoch, Optional<String> leaseOwner,
                                      Optional<OffsetDateTime> leaseUntil, OptionalLong fence, OffsetDateTime updatedAt, Optional<String> filterDigest,
                                      long parkedHeads, long parkedTotal, OptionalLong lag, long retryingHeads, long terminalHeads,
                                      Optional<OffsetDateTime> oldestParkedAt, Optional<OffsetDateTime> nextRetryAt, int maxRetries,
                                      Optional<OffsetDateTime> lastFailedAt) {
    /**
     * @deprecated use the canonical constructor, which carries the parked heads' retry state; this one reports every
     * parked head as retrying and no timestamps
     */
    @Deprecated(forRemoval = true, since = "0.62.0")
    public ApiEventLogSubscription(String subscriber, String aggregateType, String instanceName, boolean exclusive, String consistencyMode,
                                   String progressModel, long resumePoint, long resetEpoch, Optional<String> leaseOwner,
                                   Optional<OffsetDateTime> leaseUntil, OptionalLong fence, OffsetDateTime updatedAt, Optional<String> filterDigest,
                                   long parkedHeads, long parkedTotal, OptionalLong lag) {
        this(subscriber, aggregateType, instanceName, exclusive, consistencyMode, progressModel, resumePoint, resetEpoch, leaseOwner, leaseUntil, fence, updatedAt,
             filterDigest, parkedHeads, parkedTotal, lag, parkedHeads, 0, Optional.empty(), Optional.empty(), 0, Optional.empty());
    }

    public static ApiEventLogSubscription from(SubscriptionRow row) {
        var key = row.key();
        return new ApiEventLogSubscription(key.subscriber(), key.aggregateType(), key.instanceName(), row.exclusive(), row.consistencyMode(), row.progressModel(),
                                           row.resumePoint(), row.resetEpoch(), row.leaseOwner(), row.leaseUntil(), row.fence(), row.updatedAt(),
                                           row.filterDigest(), row.parkedHeads(), row.parkedTotal(), row.lag(), row.retryingHeads(), row.terminalHeads(),
                                           row.oldestParkedAt(), row.nextRetryAt(), row.maxRetries(), row.lastFailedAt());
    }

    public ProgressKey key() {
        return new ProgressKey(subscriber, aggregateType, instanceName);
    }
}
