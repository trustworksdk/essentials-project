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

package dk.trustworks.essentials.components.foundation.json;

import dk.trustworks.essentials.shared.reflection.Classes;
import tools.jackson.core.*;
import tools.jackson.databind.*;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Jackson 3 ({@code tools.jackson}) {@link ObjectMapper} based {@link JSONSerializer}
 */
public class Jackson3JSONSerializer implements JSONSerializer {
    protected final ObjectMapper objectMapper;
    /**
     * Reads one property value from a parser positioned on it and leaves the parser on the value's last token, so the
     * rest of the document is still there to read
     */
    private final   ObjectReader propertyValueReader;
    /**
     * {@link #propertyValueReader} per type - building one per value cost more than reading a small value. A
     * {@link ClassValue}, so a cached reader never keeps a class (or its {@link ClassLoader}) from being unloaded
     */
    private final   ClassValue<ObjectReader> propertyValueReaders = new ClassValue<>() {
        @Override
        protected ObjectReader computeValue(Class<?> type) {
            return propertyValueReader.forType(type);
        }
    };
    private ClassLoader classLoader;

    public Jackson3JSONSerializer(ObjectMapper objectMapper) {
        this.objectMapper = requireNonNull(objectMapper, "No object mapper instance provided");
        this.propertyValueReader = objectMapper.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        // Ensure that we have defined a default ClassLoader to avoid NullPointerException's as all class lookups use a #getClassLoader()
        setClassLoader(this.getClass().getClassLoader());
    }

    @Override
    public String serialize(Object obj) {
        requireNonNull(obj, "you must provide a non-null object");
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            throw new JSONSerializationException(msg("Failed to serialize {} to JSON", obj.getClass().getName()),
                                                 e);
        }
    }

    @Override
    public String serializePrettyPrint(Object obj) {
        requireNonNull(obj, "you must provide a non-null object");
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            throw new JSONSerializationException(msg("Failed to serialize {} to JSON", obj.getClass().getName()),
                                                 e);
        }
    }

    @Override
    public byte[] serializeAsBytes(Object obj) {
        requireNonNull(obj, "you must provide a non-null object");
        try {
            return objectMapper.writeValueAsBytes(obj);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            throw new JSONSerializationException(msg("Failed to serialize {} to JSON", obj.getClass().getName()),
                                                 e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T deserialize(String json, String javaType) {
        return deserialize(json,
                           (Class<T>) Classes.forName(requireNonNull(javaType, "No javaType provided"), getClassLoader()));
    }

    @Override
    public <T> T deserialize(String json, Class<T> javaType) {
        requireNonNull(json, "No json provided");
        requireNonNull(javaType, "No javaType provided");
        try {
            return objectMapper.readValue(json, javaType);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            throw new JSONDeserializationException(msg("Failed to deserialize JSON to {}", javaType.getName()),
                                                   e);
        }
    }

    @Override
    public <T> T deserialize(byte[] json, String javaType) {
        return deserialize(json,
                           (Class<T>) Classes.forName(requireNonNull(javaType, "No javaType provided"), getClassLoader()));
    }

    @Override
    public <T> T deserialize(byte[] json, Class<T> javaType) {
        requireNonNull(json, "No json provided");
        requireNonNull(javaType, "No javaType provided");
        try {
            return objectMapper.readValue(json, javaType);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            throw new JSONDeserializationException(msg("Failed to deserialize JSON to {}", javaType.getName()),
                                                   e);
        }
    }

    /**
     * Reads the properties in a single pass with one {@link JsonParser}: each value a reader asks for is deserialized
     * straight from where the parser stands, and each one it does not is skipped.
     */
    @Override
    public void readProperties(byte[] json, JSONPropertyReader reader) {
        requireNonNull(json, "No json provided");
        requireNonNull(reader, "No reader provided");
        try (var parser = objectMapper.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new JSONDeserializationException("Malformed JSON object: the document is not an object");
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                var name = parser.currentName();
                parser.nextToken();
                var value = new ParsedValue(parser);
                try {
                    reader.read(name, value);
                } finally {
                    value.open = false;
                }
                if (!value.read) {
                    parser.skipChildren();
                }
            }
            if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                throw new JSONDeserializationException("Malformed JSON object: expected the end of the document");
            }
        } catch (JacksonException e) {
            throw new JSONDeserializationException("Failed to read the properties of a JSON object", e);
        }
    }

    private final class ParsedValue implements JSONPropertyValue {
        private final JsonParser parser;
        private       boolean    open = true;
        private       boolean    read;

        private ParsedValue(JsonParser parser) {
            this.parser = parser;
        }

        @Override
        public String asString() {
            take();
            return switch (parser.currentToken()) {
                case VALUE_STRING -> parser.getString();
                case VALUE_NULL -> null;
                default -> throw new JSONDeserializationException("Expected a JSON string or null, found " + parser.currentToken());
            };
        }

        @Override
        public <T> T as(Class<T> javaType) {
            requireNonNull(javaType, "No javaType provided");
            take();
            try {
                return propertyValueReaders.get(javaType).readValue(parser);
            } catch (JacksonException e) {
                throw new JSONDeserializationException(msg("Failed to deserialize JSON to {}", javaType.getName()), e);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T as(String javaType) {
            requireNonNull(javaType, "No javaType provided");
            return (T) as(Classes.forName(javaType, getClassLoader()));
        }

        private void take() {
            if (!open || read) {
                throw new IllegalStateException("A property value can be read once, and only while its reader is being called");
            }
            read = true;
        }
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = requireNonNull(classLoader, "No ClassLoader provided");
    }

    /**
     * Direct access to the internal {@link ObjectMapper} used by the {@link Jackson3JSONSerializer} (or subclasses)
     *
     * @return the internal {@link ObjectMapper}
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
