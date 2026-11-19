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

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The events to append to one aggregate's stream in one statement.
 *
 * @param aggregateType      a registered aggregate type
 * @param aggregateId        the stream
 * @param expectedEventOrder the stream's last event order the caller saw: {@link #NEW_STREAM} for a stream that must
 *                           not exist yet, {@code >= 0} for an existing stream, or {@code null} for no optimistic check
 * @param tenant             the tenant, or {@code null}
 * @param events             at least one event; they get event orders {@code expected + 1, expected + 2, ...}
 */
public record AppendRequest(String aggregateType, String aggregateId, Long expectedEventOrder, String tenant, List<EventToAppend> events) {
    /**
     * The expected event order of a stream with no events
     */
    public static final long NEW_STREAM = -1L;

    public AppendRequest {
        requireNonBlank(aggregateType, "No aggregateType provided");
        requireNonBlank(aggregateId, "No aggregateId provided");
        requireTrue(expectedEventOrder == null || expectedEventOrder >= NEW_STREAM, "expectedEventOrder must be >= -1 or null");
        requireNonEmpty(requireNonNull(events, "No events provided"), "No events provided");
        events = List.copyOf(events);
    }

    /**
     * @return a request that checks the stream's last event order is {@code expectedEventOrder} ({@link #NEW_STREAM}
     * for a new stream)
     */
    public static AppendRequest afterEventOrder(String aggregateType, String aggregateId, long expectedEventOrder, List<EventToAppend> events) {
        return new AppendRequest(aggregateType, aggregateId, expectedEventOrder, null, events);
    }

    /**
     * @return a request with no optimistic check: the events follow whatever the stream holds when the statement runs
     */
    public static AppendRequest withoutExpectedOrder(String aggregateType, String aggregateId, List<EventToAppend> events) {
        return new AppendRequest(aggregateType, aggregateId, null, null, events);
    }

    /**
     * @return a copy with {@code tenant}
     */
    public AppendRequest withTenant(String tenant) {
        return new AppendRequest(aggregateType, aggregateId, expectedEventOrder, tenant, events);
    }
}
