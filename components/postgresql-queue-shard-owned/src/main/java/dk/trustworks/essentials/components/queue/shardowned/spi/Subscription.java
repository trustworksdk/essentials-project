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

import dk.trustworks.essentials.shared.Lifecycle;

/**
 * A running consumer. Stopping or closing it releases the shards it holds, so that another instance can pick them
 * up without waiting out a lease.
 * <p>
 * {@link #stop() Stopping} it leaves it registered with its queue, so the queue's {@code start()} starts it again, and so
 * does its own {@link #start()}. A caller letting go of it for good - one that may {@code consume()} again in its place -
 * ends it with {@link #unsubscribe()}, or with {@link #close()}, which is the same: try-with-resources, and Spring's
 * inferred destroy method for an {@link AutoCloseable} bean, call {@code close()} when the subscription is let go of.
 */
public interface Subscription extends Lifecycle, AutoCloseable {

    /**
     * Units currently held across <b>both</b> lanes. Moves as instances join and leave.
     * <p>
     * This is the number for "am I actually serving this queue". It used to report the unordered
     * lane alone, so a sole consumer of a 4-shard queue answered 4 while holding 68 — and the lane it
     * left out is the one whose ownership failures cannot be seen in queue depth. Use
     * {@link #unorderedShardsHeld()} or {@link #orderedUnitsHeld()} when the question is about one
     * lane, as it is whenever {@code shardCount} is involved: that number is the unordered lane's
     * alone.
     */
    int shardsHeld();

    /**
     * Unordered-lane shards held, out of the queue's {@code shardCount}.
     */
    int unorderedShardsHeld();

    /**
     * Ordered-lane units held, out of the queue's fixed routing space.
     */
    int orderedUnitsHeld();

    /**
     * Calls {@link #unsubscribe()}, so it ends the subscription as that does: on a queue that keeps a registry of its
     * subscriptions ({@code PostgresqlMessageQueue}'s), for good, and nothing starts it again; the default
     * {@code unsubscribe()} only stops it. To stop it and keep it registered with its queue, for the queue's
     * {@code start()} or its own {@link #start()} to start again, call {@link #stop()}.
     * <p>
     * It used to be {@link #stop()}, which left a subscription closed by try-with-resources or by Spring at shutdown
     * registered with its queue: the queue's {@code start()} after its {@code stop()} started the closed subscription
     * again, delivering to a handler its caller had let go of, and the next {@code consume()} was counted as competing
     * with it, under a new instance id.
     */
    @Override
    default void close() {
        unsubscribe();
    }

    /**
     * End the subscription for good: stop both lanes, as {@link #stop()} does, and remove them from the queue that
     * returned it. From then on nothing starts them again - neither the queue's {@code start()}, one already under way
     * when this is called included, nor this subscription's {@link #start()}, which throws once this has been called -
     * and they no longer count as one of the queue's subscriptions, so the next
     * {@code consume()} in this process is not reported as a competing consumer of one nobody holds.
     * <p>
     * {@link #stop()} leaves the subscription registered, which is right for a caller that will {@link #start()} it again,
     * and wrong for one that lets go of it and consumes anew: every such stop and {@code consume()} left one more stopped
     * pair of lanes registered with the queue, which the queue's {@code start()} then started again, running a handler its
     * caller had let go of. Calling it again does nothing. {@link #close()} calls it.
     * <p>
     * The default only stops, for an implementation that keeps no registry of its subscriptions.
     */
    default void unsubscribe() {
        stop();
    }
}
