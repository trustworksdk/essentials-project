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

import java.time.Instant;
import java.util.UUID;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * One event to append. Payload and metadata are opaque JSON text: bound as text and cast to {@code json}, so the bytes
 * stored and read back are exactly the ones given (the frozen wire format).
 *
 * @param eventId         the event's id
 * @param causedByEventId the causing event's id, or {@code null}
 * @param correlationId   the correlation id, or {@code null}
 * @param eventType       the event type name; interned to a {@code smallint} by {@link EventTypeRegistry}
 * @param eventRevision   0 to {@link Short#MAX_VALUE}
 * @param timestamp       stored as {@code timestamptz}, at microsecond precision
 * @param payload         JSON text
 * @param metadata        JSON text
 */
public record EventToAppend(UUID eventId, UUID causedByEventId, UUID correlationId, String eventType, int eventRevision, Instant timestamp, String payload, String metadata) {
    public EventToAppend {
        requireNonNull(eventId, "No eventId provided");
        requireNonBlank(eventType, "No eventType provided");
        requireTrue(eventRevision >= 0 && eventRevision <= Short.MAX_VALUE, "eventRevision must be between 0 and " + Short.MAX_VALUE + " but was " + eventRevision);
        requireNonNull(timestamp, "No timestamp provided");
        requireNonNull(payload, "No payload provided");
        requireNonNull(metadata, "No metadata provided");
    }
}
