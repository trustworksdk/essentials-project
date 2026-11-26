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
 * One {@code el_parked_events} row. The event itself is
 * not copied: it is the aggregate type's event at {@code globalOrder}.
 *
 * @param blockedBy   empty for a parked head; else the head this event is held behind
 * @param requested   {@code RETRY} or {@code DISCARD} while an operator's request waits to be served
 * @param retries     a head's failed automatic or operator retries since it became the head; 0 for a held row
 * @param nextRetryAt when a retrying head's next automatic retry is due; empty for a held row and a terminal head
 * @param terminal    whether the head failed with a {@link ParkEventException}: only an operator retries or discards it
 * @param lastFailedAt when the head last failed; empty for a held row that has not run
 */
public record ParkedRow(long globalOrder, String aggregateId, OptionalLong blockedBy, int attempts, Optional<String> errorType, Optional<String> errorMessage,
                        OffsetDateTime parkedAt, Optional<String> requested, Optional<OffsetDateTime> requestedAt, int retries, Optional<OffsetDateTime> nextRetryAt,
                        boolean terminal, Optional<OffsetDateTime> lastFailedAt) {
    /**
     * @deprecated use the canonical constructor, which carries the retry state; this one reports no retries, no
     * schedule and not terminal
     */
    @Deprecated(forRemoval = true, since = "0.62.0")
    public ParkedRow(long globalOrder, String aggregateId, OptionalLong blockedBy, int attempts, Optional<String> errorType, Optional<String> errorMessage,
                     OffsetDateTime parkedAt, Optional<String> requested, Optional<OffsetDateTime> requestedAt) {
        this(globalOrder, aggregateId, blockedBy, attempts, errorType, errorMessage, parkedAt, requested, requestedAt, 0, Optional.empty(), false, Optional.empty());
    }

    public ParkedRow {
        requireNonNull(aggregateId, "No aggregateId provided");
        requireNonNull(blockedBy, "No blockedBy provided");
        requireNonNull(errorType, "No errorType provided");
        requireNonNull(errorMessage, "No errorMessage provided");
        requireNonNull(parkedAt, "No parkedAt provided");
        requireNonNull(requested, "No requested provided");
        requireNonNull(requestedAt, "No requestedAt provided");
        requireNonNull(nextRetryAt, "No nextRetryAt provided");
        requireNonNull(lastFailedAt, "No lastFailedAt provided");
    }

    /** @return whether this row is a parked head (not held behind another) */
    public boolean head() {
        return blockedBy.isEmpty();
    }
}
