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

import dk.trustworks.essentials.components.foundation.json.*;
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
 * It is on disk the moment the first message is enqueued, so it is a compatibility surface, not an
 * implementation detail. The engine's {@code payloadType} carries the format version rather than anything
 * about the application's type. It is the one field the engine stores outside the envelope, so it is the
 * only place a reader can look before it has decided how to parse, and formats are told apart per row
 * rather than per deployment. Every format ever written stays readable: rows outlive deployments, and a
 * dead letter can sit for weeks before it is resurrected — with the format version it was written in.
 *
 * <h2>The formats</h2>
 * <ul>
 *     <li><b>{@value #FORMAT_VERSION}</b>, written by this version — the payload and metadata embedded as JSON:
 *         {@code {"payloadType":"<class name>","payload":{...},"metaData":{...}}}, always in that order and
 *         with no whitespace between the envelope's own tokens. The {@link JSONSerializer} writes the payload
 *         and the metadata, and the envelope is assembled around their bytes, so it needs no JSON library of its
 *         own and a custom serializer stays in charge of the application's types. It is read with
 *         {@link JSONSerializer#readProperties}: {@code payloadType} first, then the payload as that type, in one
 *         pass where the serializer can stream ({@link Jackson3JSONSerializer} does).</li>
 *     <li><b>{@value #FORMAT_VERSION_1}</b>, written by 0.60.0 — the payload and metadata held as JSON
 *         <em>strings</em> inside a flat object of three strings. Every message was therefore serialized
 *         twice on the way in and parsed twice on the way out, and its payload appeared escaped in
 *         {@code shard_queue_unordered_readable}. Measured on a typical event with three metadata entries,
 *         {@value #FORMAT_VERSION} takes 35-40% less CPU to write and read (read 2 650 to 1 500 ns, write
 *         1 400 to 900-1 070 ns across runs) and 9% fewer bytes.</li>
 * </ul>
 * The format changed without a release that only reads it first because the adapter shipped in 0.60.0 off
 * by default and unused: no cluster runs it at mixed versions. A future change to a format that is in use
 * needs that step — a reader older than the format fails to parse it, and the message is retried and
 * dead-lettered.
 *
 * <h2>What is not in here</h2>
 * The ordering key and its sequence go in the engine's own columns, not the envelope — the engine
 * routes on the key, so it has to see it. Which lane a message is in is what says whether it was an
 * {@link OrderedMessage}, so that is not stored either. Delivery attempts, timestamps and the last
 * error are the engine's to track and would go stale the moment they were written here.
 *
 * @param payloadType the payload's class name; only format {@value #FORMAT_VERSION_1} is read into this record
 * @param payload     the payload JSON
 * @param metaData    the metadata JSON, or {@code null} (format {@value #FORMAT_VERSION_1} only)
 */
public record MessageEnvelope(String payloadType, String payload, String metaData) {

    /**
     * Written into the engine's {@code payloadType} column for every message this adapter enqueues.
     * <p>
     * Bump it only when a reader could not otherwise tell the formats apart, and keep the old reader.
     */
    public static final int FORMAT_VERSION = 2;

    /**
     * The format 0.60.0 wrote: payload and metadata as JSON strings inside the envelope. Still read, never written.
     */
    public static final int FORMAT_VERSION_1 = 1;

    private static final byte[] TYPE_PREFIX     = "{\"payloadType\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYLOAD_PREFIX  = "\",\"payload\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] METADATA_PREFIX = ",\"metaData\":".getBytes(StandardCharsets.UTF_8);

    public MessageEnvelope {
        requireNonNull(payloadType, "No payloadType provided");
        requireNonNull(payload, "No payload provided");
    }

    /**
     * @return whether {@link #deserialize(JSONSerializer, byte[], int, String, long)} can read a message stored with
     * this format version
     */
    public static boolean isReadable(int formatVersion) {
        return formatVersion == FORMAT_VERSION || formatVersion == FORMAT_VERSION_1;
    }

    /**
     * Packs a message for storage in format {@value #FORMAT_VERSION}.
     *
     * @param message the message. {@link OrderedMessage}'s key and order are <em>not</em> packed —
     *                they are the engine's routing information and travel in its columns
     */
    public static byte[] serialize(JSONSerializer jsonSerializer, Message message) {
        requireNonNull(jsonSerializer, "No jsonSerializer provided");
        requireNonNull(message, "No message provided");
        var payload     = requireNonNull(message.getPayload(), "Message payload was null");
        var type        = payload.getClass().getName().getBytes(StandardCharsets.UTF_8);
        var payloadJson = jsonSerializer.serializeAsBytes(payload);
        var metaData    = jsonSerializer.serializeAsBytes(message.getMetaData());

        var envelope = new byte[TYPE_PREFIX.length + type.length + PAYLOAD_PREFIX.length + payloadJson.length
                + METADATA_PREFIX.length + metaData.length + 1];
        var at = append(envelope, 0, TYPE_PREFIX);
        // A class name cannot hold a quote, a backslash or a control character, so it needs no escaping
        at = append(envelope, at, type);
        at = append(envelope, at, PAYLOAD_PREFIX);
        at = append(envelope, at, payloadJson);
        at = append(envelope, at, METADATA_PREFIX);
        at = append(envelope, at, metaData);
        envelope[at] = '}';
        return envelope;
    }

    /**
     * Unpacks a message.
     * <p>
     * The key and order are supplied by the caller from the engine's columns rather than read from the
     * envelope, which is why this takes them: an ordered message has to come back out as an
     * {@link OrderedMessage} or a handler that switches on the type sees something that never went in.
     *
     * @param formatVersion the engine's {@code payloadType} for the row - the format it was written in
     * @param key           the ordering key, or {@code null} for an unordered message
     * @param order         the ordering sequence; ignored when {@code key} is {@code null}
     * @throws IllegalArgumentException     when {@code formatVersion} is not one {@link #isReadable(int) this adapter reads}
     * @throws JSONDeserializationException when the bytes are not a well-formed envelope of that format
     */
    public static Message deserialize(JSONSerializer jsonSerializer, byte[] bytes, int formatVersion, String key, long order) {
        requireNonNull(jsonSerializer, "No jsonSerializer provided");
        requireNonNull(bytes, "No bytes provided");
        Object          payload;
        MessageMetaData metaData;
        if (formatVersion == FORMAT_VERSION) {
            var read = new FormatTwo();
            jsonSerializer.readProperties(bytes, read);
            if (read.payload == null || read.metaData == null) {
                throw new JSONDeserializationException("Envelope is malformed: it lacks its payload or its metaData");
            }
            payload = read.payload;
            metaData = read.metaData;
        } else if (formatVersion == FORMAT_VERSION_1) {
            var envelope = jsonSerializer.deserialize(bytes, MessageEnvelope.class);
            payload = jsonSerializer.deserialize(envelope.payload(), envelope.payloadType());
            metaData = envelope.metaData() == null
                       ? new MessageMetaData()
                       : jsonSerializer.deserialize(envelope.metaData(), MessageMetaData.class);
        } else {
            throw new IllegalArgumentException("Envelope format " + formatVersion + " is not one this adapter reads ("
                                                       + FORMAT_VERSION_1 + " or " + FORMAT_VERSION + ")");
        }
        return key == null
               ? new Message(payload, metaData)
               : new OrderedMessage(payload, key, order, metaData);
    }

    /**
     * Unpacks a message stored in format {@value #FORMAT_VERSION_1}, the only format there was when this was added.
     *
     * @deprecated says nothing about the format the bytes are in, and cannot read what this version writes. Use
     * {@link #deserialize(JSONSerializer, byte[], int, String, long)} with the row's format version.
     */
    @Deprecated(forRemoval = true, since = "0.60.1")
    public static Message deserialize(JSONSerializer jsonSerializer, byte[] bytes, String key, long order) {
        return deserialize(jsonSerializer, bytes, FORMAT_VERSION_1, key, order);
    }

    private static int append(byte[] target, int at, byte[] source) {
        System.arraycopy(source, 0, target, at, source.length);
        return at + source.length;
    }

    /**
     * Reads a format-{@value #FORMAT_VERSION} envelope's properties as {@link JSONSerializer#readProperties} hands
     * them over. The payload's type has to be known before the payload is read, which is why it is written first.
     */
    private static final class FormatTwo implements JSONPropertyReader {
        private String          payloadType;
        private Object          payload;
        private MessageMetaData metaData;

        @Override
        public void read(String name, JSONPropertyValue value) {
            switch (name) {
                case "payloadType" -> payloadType = value.asString();
                case "payload" -> {
                    if (payloadType == null) {
                        throw new JSONDeserializationException("Envelope is malformed: its payload comes before its payloadType");
                    }
                    payload = value.as(payloadType);
                }
                case "metaData" -> metaData = value.as(MessageMetaData.class);
                default -> {
                    // Unknown properties are skipped, so a later version may add one without breaking this reader
                }
            }
        }
    }
}
