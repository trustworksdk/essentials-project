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

package dk.trustworks.essentials.components.queue.shardowned.api;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;

import java.nio.charset.*;
import java.time.*;
import java.util.HexFormat;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One message as an administrative caller sees it.
 * <p>
 * Separate from {@link QueuedMessage} and {@link DeadLetter} for two reasons, both of which would be
 * defects if this simply returned the engine records:
 * <ul>
 *     <li>{@code payload} is a {@link String} that may be {@code null} — the payload is redacted
 *         unless the caller holds the payload-reader role. A {@code byte[]} field has no way to say
 *         "withheld" that a JSON encoder will not render as an empty array.</li>
 *     <li>{@code id} is the text form. A structured {@link MessageId} would serialise as a nested
 *         object that no caller can put back into a URL.</li>
 * </ul>
 * The queue name travels with the id because {@link MessageId} is unique within a queue, not across
 * queues — a message read from one queue's response is meaningless when applied to another.
 *
 * @param payload      the payload rendered as text, or {@code null} if the caller may not read it.
 *                     Never an empty string for a withheld payload: an empty payload is a legal
 *                     message and must stay distinguishable from a redacted one
 * @param attempts     deliveries recorded so far. Written at failure or at takeover rather than at
 *                     dispatch, so a message read while in flight shows one lower than the number of
 *                     times a handler has actually seen it
 * @param visibleAt    when the message becomes eligible for delivery; {@code null} for a dead letter,
 *                     which is not eligible at all
 * @param isDeadLetter whether this was read from the dead-letter table rather than from a lane
 * @param blockedByKeyOrder for a dead letter that was never delivered, the {@code key_order} of the
 *                     message whose failure stopped its key; {@code null} for anything else, including
 *                     a dead letter that was itself tried and failed. An operator cannot tell those
 *                     two apart from {@code attempts}, which a takeover bumps on rows that never ran,
 *                     and this is the field that separates a broken handler from a broken key
 */
public record ApiShardOwnedMessage(String id,
                                   QueueName queueName,
                                   String lane,
                                   int shard,
                                   long sequence,
                                   String key,
                                   String payload,
                                   int payloadType,
                                   int attempts,
                                   OffsetDateTime enqueuedAt,
                                   OffsetDateTime visibleAt,
                                   boolean isDeadLetter,
                                   String lastError,
                                   Long blockedByKeyOrder) {

    /** True when this dead letter was never handed to a handler — its key was already blocked. */
    public boolean neverDelivered() {
        return blockedByKeyOrder != null;
    }

    public static ApiShardOwnedMessage from(QueueName queueName, QueuedMessage message, boolean includePayload) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(message, "No message provided");
        return new ApiShardOwnedMessage(message.id().toString(),
                                        queueName,
                                        message.id().lane().name(),
                                        message.id().shard(),
                                        message.id().sequence(),
                                        message.key(),
                                        includePayload ? render(message.payload()) : null,
                                        message.payloadType(),
                                        message.attempts(),
                                        atOffset(message.enqueuedAt()),
                                        atOffset(message.visibleAt()),
                                        false,
                                        null,
                                        null);
    }

    public static ApiShardOwnedMessage from(QueueName queueName, DeadLetter deadLetter, boolean includePayload) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(deadLetter, "No deadLetter provided");
        return new ApiShardOwnedMessage(deadLetter.id().toString(),
                                        queueName,
                                        deadLetter.id().lane().name(),
                                        deadLetter.id().shard(),
                                        deadLetter.id().sequence(),
                                        deadLetter.key(),
                                        includePayload ? render(deadLetter.payload()) : null,
                                        deadLetter.payloadType(),
                                        deadLetter.attempts(),
                                        null,
                                        null,
                                        true,
                                        deadLetter.lastError(),
                                        deadLetter.blockedByKeyOrder());
    }

    private static OffsetDateTime atOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * The same rendering the {@code shard_queue_*_readable} views apply: UTF-8 when the bytes are
     * valid UTF-8, hex otherwise.
     * <p>
     * Deliberately identical to the SQL side so that an operator comparing an HTTP response against
     * {@code psql} is looking at the same text, and deliberately lossy-free rather than
     * lossy-decoding: {@link String#String(byte[], Charset)} silently substitutes U+FFFD for invalid
     * bytes, which turns a protobuf payload into an unreadable string that still *looks* like text.
     * Hex at least says what it is.
     */
    private static String render(byte[] payload) {
        if (payload == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                                         .onMalformedInput(CodingErrorAction.REPORT)
                                         .onUnmappableCharacter(CodingErrorAction.REPORT)
                                         .decode(java.nio.ByteBuffer.wrap(payload))
                                         .toString();
        } catch (CharacterCodingException e) {
            return "\\x" + HexFormat.of().formatHex(payload);
        }
    }
}
