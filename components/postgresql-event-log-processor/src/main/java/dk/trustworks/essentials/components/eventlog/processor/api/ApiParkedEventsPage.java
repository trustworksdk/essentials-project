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
 * One page of a subscription's parked events: {@link ParkedPage}
 */
public record ApiParkedEventsPage(List<ApiParkedEvent> events, OptionalLong nextAfter) {
    public ApiParkedEventsPage {
        events = List.copyOf(events);
    }

    public static ApiParkedEventsPage from(ParkedPage page) {
        return new ApiParkedEventsPage(page.rows().stream().map(ApiParkedEvent::from).toList(), page.nextAfter());
    }

    /** One parked row: {@link ParkedRow}; {@code blockedBy} empty for a parked head */
    public record ApiParkedEvent(long globalOrder, String aggregateId, OptionalLong blockedBy, int attempts, Optional<String> errorType,
                                 Optional<String> errorMessage, OffsetDateTime parkedAt, Optional<String> requested, Optional<OffsetDateTime> requestedAt) {
        public static ApiParkedEvent from(ParkedRow row) {
            return new ApiParkedEvent(row.globalOrder(), row.aggregateId(), row.blockedBy(), row.attempts(), row.errorType(), row.errorMessage(), row.parkedAt(),
                                      row.requested(), row.requestedAt());
        }
    }
}
