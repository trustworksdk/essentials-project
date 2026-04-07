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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.foundation.json.JacksonJSONSerializer;
import org.slf4j.*;

/**
 * A JSON serializer implementation that adds support for serialization and deserialization of
 * aggregate snapshots. It acts as a decorator for another {@link JSONEventSerializer},
 * often enhancing its functionality specifically for snapshot serialization needs.
 * <p>
 * If the delegated serializer is Jackson-based, this implementation enables additional
 * Jackson-specific configurations through the use of the {@link AggregateSnapshotJacksonModule}.
 * For non-Jackson-based serializers, this class defaults to using the provided serializer
 * without added framework filtering.
 * </p>
 */
final class AggregateSnapshotJSONSerializer implements JSONEventSerializer {
    private static final Logger log = LoggerFactory.getLogger(AggregateSnapshotJSONSerializer.class);

    private final JSONEventSerializer delegate;

    private AggregateSnapshotJSONSerializer(JSONEventSerializer delegate) {
        this.delegate = delegate;
    }

    static JSONEventSerializer create(JSONEventSerializer serializer) {
        if (serializer instanceof AggregateSnapshotJSONSerializer) {
            return serializer;
        }
        if (serializer instanceof JacksonJSONSerializer jacksonJsonSerializer) {
            ObjectMapper snapshotObjectMapper = jacksonJsonSerializer.getObjectMapper().copy();
            snapshotObjectMapper.registerModule(new AggregateSnapshotJacksonModule());
            return new AggregateSnapshotJSONSerializer(new JacksonJSONEventSerializer(snapshotObjectMapper));
        }
        log.warn("JSONEventSerializer '{}' is not Jackson-based; snapshot serialization will use it without framework bookkeeping filtering",
                 serializer.getClass().getName());
        return serializer;
    }

    @Override
    public EventJSON serializeEvent(Object objectToSerialize) {
        return delegate.serializeEvent(objectToSerialize);
    }

    @Override
    public EventMetaDataJSON serializeMetaData(EventMetaData metaData) {
        return delegate.serializeMetaData(metaData);
    }

    @Override
    public String serialize(Object obj) {
        return delegate.serialize(obj);
    }

    @Override
    public String serializePrettyPrint(Object obj) {
        return delegate.serializePrettyPrint(obj);
    }

    @Override
    public byte[] serializeAsBytes(Object obj) {
        return delegate.serializeAsBytes(obj);
    }

    @Override
    public <T> T deserialize(String json, String javaType) {
        return delegate.deserialize(json, javaType);
    }

    @Override
    public <T> T deserialize(String json, Class<T> javaType) {
        return delegate.deserialize(json, javaType);
    }

    @Override
    public <T> T deserialize(byte[] json, String javaType) {
        return delegate.deserialize(json, javaType);
    }

    @Override
    public <T> T deserialize(byte[] json, Class<T> javaType) {
        return delegate.deserialize(json, javaType);
    }

    @Override
    public ClassLoader getClassLoader() {
        return delegate.getClassLoader();
    }

    @Override
    public void setClassLoader(ClassLoader classLoader) {
        delegate.setClassLoader(classLoader);
    }
}
