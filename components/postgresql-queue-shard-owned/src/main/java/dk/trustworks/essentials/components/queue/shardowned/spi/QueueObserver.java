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
 * was installed to produce. The exception is {@link #takeoverStalled}, which says why.
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
     * This instance acquired a shard ({@code acquired} true) or no longer holds it (false), which is the signal that
     * consumers are moving. The shard is an unordered shard or an ordered unit, whichever lane the consumer serves.
     * <p>
     * Every acquisition is followed by exactly one release, however the shard is lost: released by this instance (to
     * rebalance, or handed back from a dead pump), found taken by another instance (on the heartbeat that finds it), or
     * given up when the consumer stops - released, or kept because an ordered key was still in its handler, or left to
     * lapse because its release failed. So, over the consumers this observer is attached to, acquisitions minus releases
     * is the number of shards they hold, and zero once they have stopped. One caveat on order: a shard the heartbeat
     * acquires just as its consumer stops can have its release told before its acquisition.
     */
    default void shardOwnershipChanged(int shard, boolean acquired) {
    }

    /**
     * A shard this instance holds a lease on has stopped, or started again, being served because its
     * takeover failed or then completed.
     * <p>
     * {@code stalled == true} on the shard's first failed takeover. The pump retries the takeover every
     * 200 ms with no limit and keeps the lease, so the shard delivers nothing in the meantime and no other
     * instance takes it; {@link QueueHealth#unownedShards()} cannot see it, because it is leased. A failure
     * caused by data (a trigger or constraint refusing the takeover's dead-letter move) does not clear on its
     * own. {@code stalled == false} once, when the takeover completes or the lease ends with it still failing.
     * The retries in between are not reported here. So a count of {@code true} minus {@code false} calls is
     * the number of shards stalled right now, which {@code MicrometerQueueObserver} publishes as
     * {@code essentials.queue.shards.awaiting.takeover}.
     * <p>
     * The lane is given, unlike {@link #shardOwnershipChanged}, because shard numbers repeat across the two
     * lanes and a stalled shard is one to look up, not only to count.
     * <p>
     * <b>The one callback the engine catches.</b> It is called on a pump thread outside the per-shard guard
     * that keeps one shard's failure from ending the thread, so a throw here would stop every shard that pump
     * serves. What it throws, a non-critical {@code Error} included, is logged at ERROR instead, and the pump goes on;
     * only a critical error ({@code VirtualMachineError}, {@code LinkageError}) propagates.
     */
    default void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
    }
}
