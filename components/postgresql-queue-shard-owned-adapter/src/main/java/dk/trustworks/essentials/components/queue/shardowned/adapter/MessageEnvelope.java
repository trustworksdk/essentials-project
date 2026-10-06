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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;

import java.nio.charset.StandardCharsets;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * How a {@link Message} is stored in a shard-owned queue's {@code bytea} payload.
 *
 * <h2>Why an envelope is needed at all</h2>
 * {@code PostgresqlDurableQueues} spreads a message over its own columns — payload JSON, payload type
 * FQCN, metadata JSON — because it owns its schema. The shard-owned engine deliberately does not: a
 * message is {@code byte[]} plus an opaque {@code int payloadType} that the engine never interprets,
 * which is what lets one schema carry any application's messages. Everything
 * {@link DurableQueues} keeps in columns therefore has to travel inside those bytes.
 *
 * <h2>This is a persisted format</h2>
 * It is on disk the moment the first message is enqueued, so it is a compatibility surface from day
 * one, not an implementation detail. Two consequences, both deliberate:
 * <ul>
 *     <li>The engine's {@code payloadType} carries {@link #FORMAT_VERSION} rather than anything about
 *         the application's type. It is the one field the engine stores outside the envelope, so it
 *         is the only place a reader can look before it has decided how to parse. A future format can
 *         then be told apart per row rather than per deployment.</li>
 *     <li>{@code payload} and {@code metaData} are held as JSON <em>strings</em>, so the envelope is a
 *         flat object of three strings whatever the application's payload looks like. The cost is
 *         that the payload appears escaped in {@code shard_queue_unordered_readable} — one
 *         {@code ::json} away in psql, and worth it against embedding a polymorphic document whose
 *         shape depends on a type the reader has not resolved yet.</li>
 * </ul>
 *
 * <h2>What is not in here</h2>
 * The ordering key and its sequence go in the engine's own columns, not the envelope — the engine
 * routes on the key, so it has to see it. Which lane a message is in is what says whether it was an
 * {@link OrderedMessage}, so that is not stored either. Delivery attempts, timestamps and the last
 * error are the engine's to track and would go stale the moment they were written here.
 */
public record MessageEnvelope(String payloadType, String payload, String metaData) {

    /**
     * Written into the engine's {@code payloadType} column for every message this adapter enqueues.
     * <p>
     * Bump it only when a reader could not otherwise tell the formats apart, and keep the old reader.
     */
    public static final int FORMAT_VERSION = 1;

    public MessageEnvelope {
        requireNonNull(payloadType, "No payloadType provided");
        requireNonNull(payload, "No payload provided");
    }

    /**
     * Packs a message for storage.
     *
     * @param message the message. {@link OrderedMessage}'s key and order are <em>not</em> packed —
     *                they are the engine's routing information and travel in its columns
     */
    public static byte[] serialize(JSONSerializer jsonSerializer, Message message) {
        requireNonNull(jsonSerializer, "No jsonSerializer provided");
        requireNonNull(message, "No message provided");
        var payload = requireNonNull(message.getPayload(), "Message payload was null");
        var envelope = new MessageEnvelope(payload.getClass().getName(),
                                           jsonSerializer.serialize(payload),
                                           message.getMetaData() == null
                                           ? null
                                           : jsonSerializer.serialize(message.getMetaData()));
        return jsonSerializer.serialize(envelope).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Unpacks a message.
     * <p>
     * The key and order are supplied by the caller from the engine's columns rather than read from the
     * envelope, which is why this takes them: an ordered message has to come back out as an
     * {@link OrderedMessage} or a handler that switches on the type sees something that never went in.
     *
     * @param key   the ordering key, or {@code null} for an unordered message
     * @param order the ordering sequence; ignored when {@code key} is {@code null}
     */
    public static Message deserialize(JSONSerializer jsonSerializer, byte[] bytes, String key, long order) {
        requireNonNull(jsonSerializer, "No jsonSerializer provided");
        requireNonNull(bytes, "No bytes provided");
        var envelope = jsonSerializer.<MessageEnvelope>deserialize(new String(bytes, StandardCharsets.UTF_8),
                                                                   MessageEnvelope.class);
        Object payload = jsonSerializer.deserialize(envelope.payload(), envelope.payloadType());
        var metaData = envelope.metaData() == null
                       ? new MessageMetaData()
                       : jsonSerializer.<MessageMetaData>deserialize(envelope.metaData(), MessageMetaData.class);
        return key == null
               ? new Message(payload, metaData)
               : new OrderedMessage(payload, key, order, metaData);
    }
}
