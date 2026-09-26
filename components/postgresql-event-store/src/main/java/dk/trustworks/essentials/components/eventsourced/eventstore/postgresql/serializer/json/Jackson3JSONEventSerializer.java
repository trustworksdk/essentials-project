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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventType;
import dk.trustworks.essentials.components.foundation.json.*;
import tools.jackson.databind.ObjectMapper;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The Jackson 3 ({@code tools.jackson}) {@link JSONEventSerializer}. Build it through
 * {@link EssentialsJSONEventSerializers#create()} so the mapper carries the canonical Essentials configuration.
 * <p>
 * It writes the same JSON the Jackson 2 {@code JacksonJSONEventSerializer} of Essentials 0.50 and earlier wrote, which
 * is what keeps event and metadata payloads persisted by those versions readable. That equivalence is pinned by the
 * golden documents in {@code EssentialsObjectMappersWireFormatTest} and {@code types-jackson3}'s
 * {@code WireFormatCompatibilityTest}.
 */
public final class Jackson3JSONEventSerializer extends Jackson3JSONSerializer implements JSONEventSerializer {

    public Jackson3JSONEventSerializer(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public EventJSON serializeEvent(Object objectToSerialize) {
        requireNonNull(objectToSerialize, "No objectToSerialize provided");
        try {
            return new EventJSON(this,
                                 objectToSerialize,
                                 EventType.of(objectToSerialize.getClass()),
                                 objectMapper.writeValueAsString(objectToSerialize));
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            throw new JSONSerializationException(msg("Failed to serialize {} to JSON", objectToSerialize.getClass().getName()),
                                                 e);
        }
    }

    @Override
    public EventMetaDataJSON serializeMetaData(EventMetaData metaData) {
        requireNonNull(metaData, "No metaData provided");
        try {
            return new EventMetaDataJSON(this,
                                         metaData,
                                         metaData.getClass().getName(),
                                         objectMapper.writeValueAsString(metaData));
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            throw new JSONSerializationException(msg("Failed to serialize {} to JSON", metaData.getClass().getName()),
                                                 e);
        }
    }
}
