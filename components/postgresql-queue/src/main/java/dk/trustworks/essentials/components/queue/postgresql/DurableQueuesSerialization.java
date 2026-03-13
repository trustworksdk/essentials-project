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

package dk.trustworks.essentials.components.queue.postgresql;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.components.foundation.json.Jackson3JSONSerializer;
import dk.trustworks.essentials.components.foundation.json.JacksonJSONSerializer;
import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueDeserializationException;
import dk.trustworks.essentials.components.foundation.messaging.queue.MessageMetaData;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueEntryId;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Helper class for serialization operations used by PostgresqlDurableQueues.
 * This class contains methods for serializing and deserializing message payloads and metadata.
 */
public class DurableQueuesSerialization {
    private final JSONSerializer jsonSerializer;

    /**
     * Creates a new DurableQueuesSerialization instance.
     *
     * @param jsonSerializer the JSON serializer to use for serialization/deserialization
     */
    public DurableQueuesSerialization(JSONSerializer jsonSerializer) {
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
    }

    /**
     * Deserializes a message payload.
     *
     * @param queueName the queue name
     * @param queueEntryId the queue entry ID
     * @param messagePayload the message payload as a string
     * @param messagePayloadType the type of the message payload
     * @return the deserialized message payload
     * @throws DurableQueueDeserializationException if deserialization fails
     */
    public Object deserializeMessagePayload(QueueName queueName, QueueEntryId queueEntryId, String messagePayload, String messagePayloadType) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(queueEntryId, "No queueEntryId provided");
        requireNonNull(messagePayload, "No messagePayload provided");
        requireNonNull(messagePayloadType, "No messagePayloadType provided");
        try {
            return jsonSerializer.deserialize(messagePayload, messagePayloadType);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            throw new DurableQueueDeserializationException(msg("Failed to deserialize message payload of type {}", messagePayloadType), e, queueName, queueEntryId);
        }
    }

    /**
     * Deserializes message metadata.
     *
     * @param queueName the queue name
     * @param queueEntryId the queue entry ID
     * @param metaData the metadata as a string
     * @return the deserialized message metadata
     * @throws DurableQueueDeserializationException if deserialization fails
     */
    public MessageMetaData deserializeMessageMetadata(QueueName queueName, QueueEntryId queueEntryId, String metaData) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(queueEntryId, "No queueEntryId provided");
        requireNonNull(metaData, "No messagePayload provided");
        try {
            return jsonSerializer.deserialize(metaData, MessageMetaData.class);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            throw new DurableQueueDeserializationException(msg("Failed to deserialize message meta-data"), e, queueName, queueEntryId);
        }
    }

    /**
     * Create default {@link JSONSerializer}. Uses Jackson 3 when the Jackson 3 Essentials modules are present,
     * otherwise falls back to Jackson 2.
     */
    public static JSONSerializer createDefaultJSONSerializer() {
        var jackson3Serializer = tryCreateJackson3JSONSerializer();
        return jackson3Serializer != null ? jackson3Serializer : new JacksonJSONSerializer(createDefaultObjectMapper());
    }

    /**
     * Default {@link ObjectMapper} supporting {@link Jdk8Module}, {@link JavaTimeModule}, {@link EssentialTypesJacksonModule} and {@link EssentialsImmutableJacksonModule}, which
     * is used together with the {@link JSONSerializer}
     *
     * @return the default {@link ObjectMapper}
     */
    public static ObjectMapper createDefaultObjectMapper() {
        var objectMapper = JsonMapper.builder()
                                     .disable(MapperFeature.AUTO_DETECT_GETTERS)
                                     .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                                     .disable(MapperFeature.AUTO_DETECT_SETTERS)
                                     .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                                     .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                     .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                     .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                                     .enable(MapperFeature.AUTO_DETECT_CREATORS)
                                     .enable(MapperFeature.AUTO_DETECT_FIELDS)
                                     .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
                                     .addModule(new Jdk8Module())
                                     .addModule(new JavaTimeModule())
                                     .build();
        registerJackson2ModuleIfCompatible(objectMapper, "dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule");
        registerJackson2ModuleIfCompatible(objectMapper, "dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule");

        objectMapper.setVisibility(objectMapper.getSerializationConfig().getDefaultVisibilityChecker()
                                               .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                                               .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                                               .withCreatorVisibility(JsonAutoDetect.Visibility.ANY));
        return objectMapper;
    }

    private static JSONSerializer tryCreateJackson3JSONSerializer() {
        try {
            // Only select Jackson 3 path when the essentials modules are the jackson3 variants
            var moduleClass = Class.forName("dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule");
            var superClassName = moduleClass.getSuperclass().getName();
            if (!superClassName.startsWith("tools.jackson.")) {
                return null;
            }

            var jsonMapperClass = Class.forName("tools.jackson.databind.json.JsonMapper");
            var builder = jsonMapperClass.getMethod("builder").invoke(null);
            var jacksonModuleClass = Class.forName("tools.jackson.databind.JacksonModule");
            var essentialTypesModule = moduleClass.getDeclaredConstructor().newInstance();
            var immutableModuleClass = Class.forName("dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule");
            var immutableModule = immutableModuleClass.getDeclaredConstructor().newInstance();

            builder.getClass().getMethod("addModule", jacksonModuleClass).invoke(builder, essentialTypesModule);
            builder.getClass().getMethod("addModule", jacksonModuleClass).invoke(builder, immutableModule);
            var mapper = builder.getClass().getMethod("build").invoke(builder);

            var serializerClass = Class.forName("dk.trustworks.essentials.components.foundation.json.Jackson3JSONSerializer");
            return (JSONSerializer) serializerClass.getDeclaredConstructor(mapper.getClass()).newInstance(mapper);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static void registerJackson2ModuleIfCompatible(ObjectMapper objectMapper, String moduleClassName) {
        try {
            var moduleClass = Class.forName(moduleClassName);
            var module = moduleClass.getDeclaredConstructor().newInstance();
            if (com.fasterxml.jackson.databind.Module.class.isAssignableFrom(moduleClass)) {
                objectMapper.registerModule((com.fasterxml.jackson.databind.Module) module);
            }
        } catch (Throwable ignore) {
            // ignore, fallback mapper still works without optional modules
        }
    }
}
