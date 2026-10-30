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

package dk.trustworks.essentials.components.foundation.messaging.queue;

/**
 * Marks an {@link OrderedMessage} whose {@link OrderedMessage#getKey() key} and {@link OrderedMessage#getOrder() order}
 * identify it: two messages with the same key and order are the same message, so queueing it again while it is still
 * queued means nothing.
 * <p>
 * An event processor's reader is the producer this is for. Its messages reference an event by aggregate id and event
 * order, and after an unclean failover it re-forwards every event since its last resume-point snapshot - some still
 * queued. A {@link DurableQueues} whose ordered messages are unique per key and order (the shard-owned engine) refuses
 * such a duplicate, which failed the reader's forward until the original had been handled. Given this marker on every
 * ordered message of a {@code queueMessage(s)} call, such an implementation may leave an already queued one in place
 * instead of refusing it - the shard-owned adapter does so once such a call has been refused for a duplicate (so the
 * producer's retry succeeds), as writing every call that way costs every message more. Implementations that accept
 * duplicates ({@code PostgresqlDurableQueues}, MongoDB) ignore the marker and queue it again, as they always have - the
 * handler sees the event twice, which at-least-once delivery allows.
 * <p>
 * Do not put it on a message whose key and order do not identify it: a different message under a key and order already
 * queued would then be dropped instead of refused.
 */
public interface IdentifiedByKeyAndOrder {
}
