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
 * was installed to produce. The exceptions are {@link #takeoverStalled}, which says why, and
 * {@link #deadLettered} for a message dead-lettered as its unordered shard was released, which says what follows.
 *
 * <h2>Calling the queue back from {@link #shardOwnershipChanged}</h2>
 * A {@code MessageQueue.consume()}, {@code start()} or {@link Subscription#start()} tells its acquisitions, and the
 * releases it causes itself, and a {@code MessageQueue.stop()} its releases, on its caller's thread while it holds the
 * queue's lifecycle lock. A call into the same queue from such a callback runs on that thread, inside that call, and
 * what it gets depends on whether that call still lets anything start. It does not while it stops consumers: a
 * {@code stop()}; a {@code consume()} or start owing a stop - the observer called {@code stop()}, or a stop on another
 * thread gave up waiting for it - or doing the stop it owes; and a {@code consume()} or start whose start failed, while it
 * stops the lanes it had started. A call made from the releases those tell, or from an acquisition told once a stop is
 * owed, is made <i>where nothing starts</i>. A lane whose own start fails after adopting units tells their releases
 * before that failure reaches the {@code consume()} or start, which is still starting then: a call from one of those
 * releases is not made where nothing starts, and gets what a call from an acquisition gets. {@code PostgresqlMessageQueue}
 * gives these outcomes:
 * <ul>
 *     <li>{@code stop()}: from an acquisition, or from any release a {@code consume()} or start tells, marks the queue
 *         not started and returns; that {@code consume()} or start starts no further consumer and stops every one before
 *         it returns. From a release a {@code stop()} tells, nothing more happens: that stop is stopping every
 *         consumer.</li>
 *     <li>{@link Subscription#start()}: starts as from any thread. Where nothing starts, it starts neither lane and logs
 *         a WARN saying so, whatever its lanes are doing: one running then may be stopped by the outer call before it
 *         returns.</li>
 *     <li>{@code start()}: starts as from any thread. Where nothing starts, it starts nothing and logs a WARN saying so,
 *         bar one case: a queue already marked started, while a {@code consume()} or {@link Subscription#start()} stops
 *         the lanes its failed start had started, returns at once with no WARN - that call leaves the queue started. While
 *         a queue {@code start()} stops the lanes its own failed start had started, the queue reads started, marked by
 *         that start, which marks it not started as it fails, bar a critical error; a {@code start()} there logs the
 *         WARN.</li>
 *     <li>{@code consume()}: a further, competing subscription under an instance id of its own, started on this thread -
 *         registered before the subscription an outer {@code consume()} is starting. Where nothing starts, it registers
 *         the subscription <b>without starting it</b> and logs a WARN: the returned {@code Subscription} reads
 *         {@code isStarted()} false and consumes nothing until the queue's {@code start()}, or its own, is called once the
 *         outer call has returned.</li>
 *     <li>{@link Subscription#unsubscribe()}, and {@link Subscription#close()}, which calls it: ends the subscription as
 *         from any thread, except that taking its lanes out of the queue is left to the call holding the lock, which
 *         does it before it returns and starts neither lane meanwhile; the unsubscribe stops both lanes on this
 *         thread.</li>
 *     <li>{@link Subscription#stop()}: stops that subscription's lanes, without the queue's lock, as from any thread.</li>
 * </ul>
 * Releases told by {@link Subscription#stop()} or {@link Subscription#unsubscribe()}, or by a {@code stop()} that gave up
 * waiting for a start still running, are told without the queue's lifecycle lock, and a heartbeat's acquisitions and
 * releases - a rebalance, a unit found taken - on the heartbeat's thread, outside any of those calls: a call from there
 * takes the queue's lifecycle lock as one from any other thread does.
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

    /**
     * A message was moved to the dead-letter table.
     * <p>
     * One dead-lettered as its unordered shard was released with the message in a handler longer than
     * {@code stuckHandlerTimeout} (its error is {@code ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER}) is told by the
     * instance releasing the shard, before the release; what this throws then is caught and logged at WARN, and the shard is
     * released all the same, the move having been made by then.
     */
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
     * is the number of shards they hold, and zero once they have stopped with {@code stop()}; {@code stopAbruptly()},
     * which stands in for a crash, tells no releases. One caveat on order: a shard the heartbeat acquires just as its
     * consumer stops can have its release told before its acquisition.
     * <p>
     * A stop tells its releases last. An implementation that throws for one leaves nothing of the stop undone - every
     * shard is given up as above and told, and every consumer of the queue or subscription is stopped - and the first
     * throw then escapes {@code stop()}. A {@code stop()} that did not wait for a start still registering and leasing
     * (past its shutdown budget, or on an interrupted thread) leaves its work to that start, and so does a
     * {@code stopAbruptly()}, which never waits for one. The start then tells its acquisitions, and for a {@code stop()}
     * the releases; what the observer throws there, for either stop, is logged at WARN, and escapes neither the stop,
     * which has returned, nor the start.
     * <p>
     * A start tells its acquisitions on its own thread. A {@code MessageQueue.stop()} called from here then marks the queue
     * not started and returns, and the {@code consume()} or start it was called from does the stopping before it returns:
     * it starts no further consumer and stops every one. What each other call into the queue does from here - a
     * {@code consume()} from a release a stop tells registers a subscription it does not start - is listed in the class
     * javadoc.
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
