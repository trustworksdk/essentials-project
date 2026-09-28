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

package dk.trustworks.essentials.components.queue.shardowned.spi;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * A message's identity.
 * <p>
 * Structured rather than opaque, because the structure is the addressing scheme: a message lives in
 * exactly one lane of one shard, and that is what makes it findable without an index and ownable
 * without a lock. An opaque id would hide the one fact every operation on it depends on.
 * <p>
 * <b>An id is unique within one queue, not globally.</b> Sequences are per {@code (queue, shard)}, so
 * {@code u-0-1} exists in every queue that has ever enqueued an unordered message. Anything that
 * addresses a message from outside the queue it belongs to — an admin API, a support ticket, a log
 * line worth acting on — must carry the {@link QueueName} alongside it.
 *
 * <h2>Text form</h2>
 * {@link #toString()} renders {@code <lane>-<shard>-<sequence>}, and {@link #parse(String)} reads it
 * back. The lane is a single character so the whole id stays short enough to read at a glance and to
 * pass through a URL path segment without escaping: {@code u-3-1042}, {@code o-0-7}.
 * <p>
 * The form is deliberately the same one the tables use, so an id taken from an HTTP response can be
 * typed straight into {@code psql} against {@code shard_queue_unordered} — {@code lane} picks the
 * table, {@code shard} and {@code sequence} are the primary key's remaining columns.
 */
public record MessageId(Lane lane, int shard, long sequence) {

    public MessageId {
        requireNonNull(lane, "No lane provided");
        requireTrue(shard >= 0, "shard must not be negative");
        requireTrue(sequence > 0, "sequence must be positive");
    }

    public enum Lane {
        UNORDERED('u'),
        ORDERED('o');

        private final char code;

        Lane(char code) {
            this.code = code;
        }

        /**
         * The single character this lane is written as in a {@link MessageId}'s text form.
         */
        public char code() {
            return code;
        }

        /**
         * The lane written as {@code code}.
         *
         * @throws IllegalArgumentException if no lane uses that character
         */
        public static Lane ofCode(char code) {
            for (var lane : values()) {
                if (lane.code == code) {
                    return lane;
                }
            }
            throw new IllegalArgumentException("Unknown lane code '" + code + "' - expected one of 'u' (unordered) or 'o' (ordered)");
        }
    }

    /**
     * Reads the {@code <lane>-<shard>-<sequence>} form produced by {@link #toString()}.
     *
     * @throws IllegalArgumentException if {@code value} is not that form. Callers exposing this to
     *                                  the outside world get a message naming the expected shape
     *                                  rather than an index-out-of-bounds from a split.
     */
    public static MessageId parse(String value) {
        requireNonNull(value, "No value provided");
        var parts = value.split("-");
        if (parts.length != 3 || parts[0].length() != 1) {
            throw new IllegalArgumentException("'" + value + "' is not a message id - expected '<lane>-<shard>-<sequence>', e.g. 'u-3-1042'");
        }
        try {
            return new MessageId(Lane.ofCode(parts[0].charAt(0)),
                                 Integer.parseInt(parts[1]),
                                 Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + value + "' is not a message id - shard and sequence must be numbers", e);
        }
    }

    @Override
    public String toString() {
        return lane.code() + "-" + shard + "-" + sequence;
    }
}
