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
import java.util.Arrays;

import static dk.trustworks.essentials.shared.FailFast.*;

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
 *         and the metadata, and reads them back from exactly their bytes: the envelope is assembled and
 *         taken apart around them, so it needs no JSON library of its own and a custom serializer stays in
 *         charge of the application's types. Taking it apart only has to find where the payload ends,
 *         which a string-aware scan does without building anything.</li>
 *     <li><b>{@value #FORMAT_VERSION_1}</b>, written by 0.60.0 — the payload and metadata held as JSON
 *         <em>strings</em> inside a flat object of three strings. Every message was therefore serialized
 *         twice on the way in and parsed twice on the way out, and its payload appeared escaped in
 *         {@code shard_queue_unordered_readable}. Measured on a typical event with three metadata entries,
 *         {@value #FORMAT_VERSION} takes about 30% less CPU to write and read (write 1 450 to 900 ns, read 2 800
 *         to 2 050 ns) and 9% fewer bytes. Reading it in one pass through Jackson would save about 135 ns more a
 *         read, not enough to give this adapter a Jackson dependency and take the payload away from a custom
 *         serializer.</li>
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
     * @throws IllegalArgumentException when {@code formatVersion} is not one {@link #isReadable(int) this adapter reads}
     */
    public static Message deserialize(JSONSerializer jsonSerializer, byte[] bytes, int formatVersion, String key, long order) {
        requireNonNull(jsonSerializer, "No jsonSerializer provided");
        requireNonNull(bytes, "No bytes provided");
        Object          payload;
        MessageMetaData metaData;
        if (formatVersion == FORMAT_VERSION) {
            var typeStart   = expect(bytes, 0, TYPE_PREFIX);
            var typeEnd     = indexOf(bytes, typeStart, (byte) '"');
            var type        = new String(bytes, typeStart, typeEnd - typeStart, StandardCharsets.UTF_8);
            var payloadFrom = expect(bytes, typeEnd, PAYLOAD_PREFIX);
            var payloadTo   = endOfValue(bytes, payloadFrom);
            var metaFrom    = expect(bytes, payloadTo, METADATA_PREFIX);
            var metaTo      = bytes.length - 1;
            requireTrue(metaTo >= metaFrom && bytes[metaTo] == '}', "Envelope does not end after its metaData");
            payload = jsonSerializer.deserialize(Arrays.copyOfRange(bytes, payloadFrom, payloadTo), type);
            metaData = jsonSerializer.deserialize(Arrays.copyOfRange(bytes, metaFrom, metaTo), MessageMetaData.class);
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
     * @return the index after {@code expected}, which must start at {@code at}
     */
    private static int expect(byte[] bytes, int at, byte[] expected) {
        // Not requireTrue: its message would be built on every call, which measured ~450 ns of a ~2 300 ns read
        if (at + expected.length > bytes.length || !Arrays.equals(bytes, at, at + expected.length, expected, 0, expected.length)) {
            throw new IllegalArgumentException("Envelope is malformed: expected " + new String(expected, StandardCharsets.UTF_8) + " at byte " + at);
        }
        return at + expected.length;
    }

    private static int indexOf(byte[] bytes, int from, byte wanted) {
        for (var i = from; i < bytes.length; i++) {
            if (bytes[i] == wanted) {
                return i;
            }
        }
        throw new IllegalArgumentException("Envelope is malformed: no closing quote after byte " + from);
    }

    /**
     * The index just past the JSON value starting at {@code from} (after any whitespace on either side of it). Only
     * strings can hide a bracket, and inside a string only an escaped character can hide its closing quote, so
     * tracking those two is enough to find where an object or array ends. The {@link JSONSerializer} validates the
     * value itself when it reads it.
     */
    private static int endOfValue(byte[] bytes, int from) {
        var i = skipWhitespace(bytes, from);
        requireTrue(i < bytes.length, "Envelope is malformed: no payload value");
        var first = bytes[i];
        if (first == '{' || first == '[') {
            var depth    = 0;
            var inString = false;
            for (; i < bytes.length; i++) {
                var b = bytes[i];
                if (inString) {
                    if (b == '\\') {
                        i++;
                    } else if (b == '"') {
                        inString = false;
                    }
                } else if (b == '"') {
                    inString = true;
                } else if (b == '{' || b == '[') {
                    depth++;
                } else if ((b == '}' || b == ']') && --depth == 0) {
                    return skipWhitespace(bytes, i + 1);
                }
            }
            throw new IllegalArgumentException("Envelope is malformed: payload is not closed");
        }
        if (first == '"') {
            for (i++; i < bytes.length; i++) {
                if (bytes[i] == '\\') {
                    i++;
                } else if (bytes[i] == '"') {
                    return skipWhitespace(bytes, i + 1);
                }
            }
            throw new IllegalArgumentException("Envelope is malformed: payload string is not closed");
        }
        // A number, true, false or null runs to the next separator
        while (i < bytes.length && bytes[i] != ',' && bytes[i] != '}' && !isWhitespace(bytes[i])) {
            i++;
        }
        return skipWhitespace(bytes, i);
    }

    private static int skipWhitespace(byte[] bytes, int from) {
        var i = from;
        while (i < bytes.length && isWhitespace(bytes[i])) {
            i++;
        }
        return i;
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }
}
