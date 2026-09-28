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

/**
 * Where metrics, tracing and audit logging attach.
 * <p>
 * This was missing from the first draft of the SPI, which listed cross-cutting instrumentation as a
 * genuine consumer need in its own justification and then provided nowhere to put it. Metrics are not
 * optional in a queue — a queue you cannot see the depth of is a queue you find out about from an
 * incident — so the omission was an error rather than a scoping decision.
 * <p>
 * Two shapes, because the needs genuinely differ:
 * <ul>
 *     <li>{@link #aroundDelivery} <b>wraps</b> the handler, which is what tracing requires: a span has
 *         to enclose the work, not be told about it afterwards.</li>
 *     <li>The rest are <b>notifications</b>. Counting does not need to wrap anything, and a counter
 *         that can throw inside the delivery path is a liability.</li>
 * </ul>
 * Every method has a default, so implementing one concern does not mean stubbing the others. The
 * engine calls these on its own threads and does not catch what they throw — an observer that fails
 * is a bug in the observer, and swallowing it would hide the bug while corrupting the measurement it
 * was installed to produce.
 * <p>
 * No Micrometer dependency here, deliberately: this module stays free of a metrics framework, and a
 * Micrometer binding is a thin adapter a consumer declares itself. The project treats third-party
 * integrations that way throughout.
 */
public interface QueueObserver {

    default void enqueued(int messageCount, boolean ordered) {
    }

    /**
     * Wraps the handler call. An implementation must invoke {@code delivery} exactly once, and must
     * let its exception propagate — swallowing it would tell the engine the message succeeded.
     *
     * @param key the ordering key, or null for an unordered message
     */
    default void aroundDelivery(String key, Runnable delivery) {
        delivery.run();
    }

    /**
     * Successful handling, measured from dispatch to return.
     */
    default void delivered(String key, long durationNanos) {
    }

    default void deliveryFailed(String key, int attempt, Throwable cause) {
    }

    default void retryScheduled(String key, int attempt, long delayMillis) {
    }

    default void deadLettered(MessageId id, int attempts, Throwable cause) {
    }

    /**
     * A shard was taken or lost, which is the signal that consumers are moving.
     */
    default void shardOwnershipChanged(int shard, boolean acquired) {
    }
}
