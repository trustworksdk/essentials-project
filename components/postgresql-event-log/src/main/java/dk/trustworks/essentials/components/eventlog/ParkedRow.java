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
 * One {@code el_parked_events} row (design {@code docs/archive/event-store-engine-phase8.md} §4.1). The event itself is
 * not copied: it is the aggregate type's event at {@code globalOrder}.
 *
 * @param blockedBy   empty for a parked head; else the head this event is held behind
 * @param requested   {@code RETRY} or {@code DISCARD} while an operator's request waits to be served (§4.3)
 */
public record ParkedRow(long globalOrder, String aggregateId, OptionalLong blockedBy, int attempts, Optional<String> errorType, Optional<String> errorMessage,
                        OffsetDateTime parkedAt, Optional<String> requested, Optional<OffsetDateTime> requestedAt) {
    public ParkedRow {
        requireNonNull(aggregateId, "No aggregateId provided");
        requireNonNull(blockedBy, "No blockedBy provided");
        requireNonNull(errorType, "No errorType provided");
        requireNonNull(errorMessage, "No errorMessage provided");
        requireNonNull(parkedAt, "No parkedAt provided");
        requireNonNull(requested, "No requested provided");
        requireNonNull(requestedAt, "No requestedAt provided");
    }

    /** @return whether this row is a parked head (not held behind another) */
    public boolean head() {
        return blockedBy.isEmpty();
    }
}
