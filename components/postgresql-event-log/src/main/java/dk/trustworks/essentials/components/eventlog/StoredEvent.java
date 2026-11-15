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
import java.util.UUID;

/**
 * One event as read from an event table. Payload and metadata are the stored JSON text, byte for byte.
 *
 * @param aggregateType   the aggregate type
 * @param globalOrder     order within the aggregate type
 * @param eventOrder      order within the stream
 * @param eventId         the event's id
 * @param causedByEventId the causing event's id, or {@code null}
 * @param correlationId   the correlation id, or {@code null}
 * @param eventType       the event type name, resolved from {@link EventTypeRegistry}
 * @param eventRevision   the revision
 * @param timestamp       the stored timestamp
 * @param aggregateId     the stream
 * @param payload         JSON text
 * @param metadata        JSON text
 * @param tenant          the tenant, or {@code null}
 */
public record StoredEvent(String aggregateType, long globalOrder, long eventOrder, UUID eventId, UUID causedByEventId, UUID correlationId, String eventType,
                          int eventRevision, OffsetDateTime timestamp, String aggregateId, String payload, String metadata, String tenant) {
}
