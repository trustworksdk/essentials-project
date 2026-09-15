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
 * @param payloadType a discriminator the application defines, so a handler can tell what the bytes
 *              are without unpacking them first. <b>Opaque to the engine.</b> It is stored, carried
 *              through dead-lettering and resurrection, and handed back to the handler; it is never
 *              compared, indexed or interpreted, and nothing validates it.
 *              <p>
 *              There is deliberately no registry mapping it to a type name, which means the mapping
 *              lives in each application — two services disagreeing about what {@code 1} means is not
 *              something this engine can detect. An earlier draft described it as an "interned FQCN"
 *              and justified it as keeping an index dense; neither was true, since the column is in
 *              no index and nothing interns it.
 * @param key   the ordering key, or null for the unordered lane. Messages sharing a key are never
 *              delivered concurrently, and are delivered in ascending {@code keyOrder} among those
 *              committed and visible when the key is free. A key does not wait for a {@code keyOrder}
 *              that has not arrived — one committing late, after a higher one was delivered, is
 *              counted rather than prevented. The cases, and the two that look like they should
 *              reorder but do not, are in the ordering-guarantee section of this module's README
 * @param keyOrder position within the key, supplied by the producer because only the producer knows
 *              the intended order. Part of the ordered table's primary key, so a value may not be
 *              reused for a key — the enqueue fails rather than overwriting. Gaps are allowed.
 *              Ignored when {@code key} is null
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

    /**
     * Deliverable no earlier than {@code delay} from now.
     * <p>
     * "From now" is the database's now, not this JVM's: the delay is applied server-side at insert,
     * so a delayed message does not depend on the enqueueing node's clock any more than a lease does.
     */
    public static Message delayed(byte[] payload, int payloadType, java.time.Duration delay) {
        return new Message(payload, payloadType, null, 0L, requireNonNull(delay, "No delay provided"));
    }

    public static Message delayedOrdered(byte[] payload, int payloadType, String key, long keyOrder,
                                         java.time.Duration delay) {
        return new Message(payload, payloadType, requireNonNull(key, "No key provided"), keyOrder,
                           requireNonNull(delay, "No delay provided"));
    }

    public boolean isOrdered() {
        return key != null;
    }
}
