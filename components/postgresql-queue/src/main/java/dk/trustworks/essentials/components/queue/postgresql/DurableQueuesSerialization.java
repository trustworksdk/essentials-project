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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import tools.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.foundation.json.Jackson3JSONSerializer;
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
     * Create the default {@link JSONSerializer}, with the canonical Essentials mapper configuration.
     */
    public static JSONSerializer createDefaultJSONSerializer() {
        return EssentialsObjectMappers.createJSONSerializer();
    }

    /**
     * Default Jackson 3 {@link ObjectMapper} with the canonical Essentials configuration (including
     * {@code EssentialTypesJacksonModule} and {@code EssentialsImmutableJacksonModule} when present), which is used
     * together with the {@link JSONSerializer}
     *
     * @return the default {@link ObjectMapper}
     */
    public static ObjectMapper createDefaultObjectMapper() {
        return EssentialsObjectMappers.createJackson3ObjectMapper();
    }


}
