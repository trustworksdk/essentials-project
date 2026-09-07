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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A message to enqueue.
 * <p>
 * The payload is bytes, not an object graph. Serialization is the caller's, which keeps the engine
 * out of a decision it has no stake in — the measurements put JSON's contribution to per-message
 * write volume at 3%, so there is nothing here worth spending a coupling on.
 *
 * @param key   the ordering key, or null for the unordered lane. Messages sharing a key are delivered
 *              in {@code keyOrder} and never concurrently
 * @param keyOrder position within the key, supplied by the producer because only the producer knows
 *              the intended order. Ignored when {@code key} is null
 * @param delay how long before the message becomes eligible for delivery
 */
public record Message(byte[] payload, int payloadType, String key, long keyOrder, java.time.Duration delay) {

    public Message {
        requireNonNull(payload, "No payload provided");
    }

    public static Message of(byte[] payload, int payloadType) {
        return new Message(payload, payloadType, null, 0L, java.time.Duration.ZERO);
    }

    public static Message ordered(byte[] payload, int payloadType, String key, long keyOrder) {
        return new Message(payload, payloadType, requireNonNull(key, "No key provided"), keyOrder, java.time.Duration.ZERO);
    }

    public boolean isOrdered() {
        return key != null;
    }
}
