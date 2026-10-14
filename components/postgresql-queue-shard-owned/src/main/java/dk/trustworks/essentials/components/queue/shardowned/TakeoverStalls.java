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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One pump's shards whose takeover has failed and not yet completed. Each of these shards is leased but not
 * served.
 * <p>
 * {@link ShardPump} retries a failing {@link LeasedOwner#onTakeover} every 200 ms, with no limit, and keeps
 * the shard (why: {@link LeasedOwner#onTakeover}). Without this, every retry logged a full ERROR stack trace,
 * about five a second for as long as the takeover failed. No counter recorded it, and
 * {@code QueueHealth.unownedShards()} cannot see the shard because it is leased, so that log flood was the
 * only sign.
 * <p>
 * Now each failure is counted in {@link ShardOwnerMetrics#takeoverFailures}, and a shard is counted in
 * {@link ShardOwnerMetrics#shardsAwaitingTakeover} from its first failure until its takeover completes or
 * its lease ends. Both are on the owner's metrics, not the pump's, because pump metrics are invisible to
 * {@link ShardOwnedQueue#metrics()}. A {@code PostgresqlMessageQueue} keeps those metrics private, so the same
 * two transitions also go to the consumer's observer, {@link QueueObserver#takeoverStalled}, which reaches every
 * observer registered on a {@code MessageQueue}. {@code MicrometerQueueObserver} publishes it as the gauge
 * {@code essentials.queue.shards.awaiting.takeover}, and the Spring starter binds that observer to every queue,
 * the {@code DurableQueues} adapter's included, whenever there is a {@code MeterRegistry}. The log follows
 * the {@code BlockedKeyReport}/{@code LeaseOverrun} pattern: an ERROR with the stack trace on a shard's first
 * failure, a one-line DEBUG on repeats (no stack trace: they come five a second), and one ERROR a minute
 * with the stack trace and the count while it keeps failing. An INFO when it recovers.
 * <p>
 * The observer call is the one observer call the engine catches ({@link QueueObserver#takeoverStalled} says
 * why): what it throws is logged at ERROR and the pump goes on. The counters are updated before it, so a
 * throwing observer cannot leave them out of step.
 * <p>
 * Confined to the pump's thread, so nothing here is synchronised. The counters it writes are shared with the
 * other pumps serving the same queue, which is why they are atomics.
 * <p>
 * Logged under {@link ShardPump}'s logger, where the takeover failure has always been logged, so existing log
 * configuration keeps applying to it.
 */
final class TakeoverStalls {
    private static final Logger log               = LoggerFactory.getLogger(ShardPump.class);
    static final         long   ERROR_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final String                              pumpName;
    private final LongSupplier                        nanoTime;
    /**
     * By identity, like the pump's {@code takenOver}: an owner is one ownership of a shard, not a value.
     */
    private final IdentityHashMap<LeasedOwner, Stall> stalls = new IdentityHashMap<>();

    TakeoverStalls(String pumpName) {
        this(pumpName, System::nanoTime);
    }

    TakeoverStalls(String pumpName, LongSupplier nanoTime) {
        this.pumpName = requireNonNull(pumpName, "No pumpName provided");
        this.nanoTime = requireNonNull(nanoTime, "No nanoTime provided");
    }

    private static final class Stall {
        final long sinceNanos;
        long       failures;
        long       nextErrorNanos;

        Stall(long sinceNanos) {
            this.sinceNanos = sinceNanos;
            this.failures = 1;
            this.nextErrorNanos = sinceNanos + ERROR_EVERY_NANOS;
        }
    }

    /**
     * The owner's takeover failed and is retried by the pump 200 ms later.
     *
     * @param what  how it failed, completing "queue Q, lane shard S ..." and "the latest ...", e.g.
     *              "threw during its takeover"
     * @param cause the failure
     */
    void failed(LeasedOwner owner, String what, Throwable cause) {
        var metrics = owner.ownerMetrics();
        metrics.takeoverFailures.increment();
        var now   = nanoTime.getAsLong();
        var stall = stalls.get(owner);
        if (stall == null) {
            stalls.put(owner, new Stall(now));
            metrics.shardsAwaitingTakeover.incrementAndGet();
            log.error("{}: queue {}, {} shard {} {}. The shard stays leased to this instance and delivers nothing "
                              + "until its takeover completes; the takeover is retried every 200 ms with no limit, "
                              + "and the pump keeps serving its other shards. "
                              + "QueueHealth.unownedShards does not count this shard. ShardOwnedQueue.metrics() reports "
                              + "it under shardsAwaitingTakeover, and every attempt under takeoverFailures; through "
                              + "MessageQueue it is reported to QueueObserver.takeoverStalled; the gauge "
                              + "essentials.queue.shards.awaiting.takeover exists only where a MicrometerQueueObserver is "
                              + "registered on the queue (the Spring starter registers one when there is a MeterRegistry). "
                              + "Repeats are logged at DEBUG, and at ERROR "
                              + "once a minute while it keeps failing",
                      pumpName, owner.queueId(), owner.lane(), owner.shard(), what, cause);
            notifyObserver(owner, true);
            return;
        }
        stall.failures++;
        if (now - stall.nextErrorNanos >= 0) {
            stall.nextErrorNanos = now + ERROR_EVERY_NANOS;
            log.error("{}: queue {}, {} shard {} has still not completed its takeover: {} failed attempt(s) in {} s; "
                              + "the latest {}. The shard stays leased to this instance and has delivered nothing since. "
                              + "Logged at most once a minute",
                      pumpName, owner.queueId(), owner.lane(), owner.shard(), stall.failures,
                      TimeUnit.NANOSECONDS.toSeconds(now - stall.sinceNanos), what, cause);
        } else {
            // No stack trace: a repeat comes every 200 ms (five a second), and the first and the once-a-minute
            // ERRORs carry it. The cause's own string still says what failed.
            log.debug("{}: queue {}, {} shard {} {} (failed attempt {}): {}",
                      pumpName, owner.queueId(), owner.lane(), owner.shard(), what, stall.failures, String.valueOf(cause));
        }
    }

    /**
     * The owner's takeover completed. A no-op unless it had been failing.
     */
    void completed(LeasedOwner owner) {
        var stall = stalls.remove(owner);
        if (stall == null) {
            return;
        }
        owner.ownerMetrics().shardsAwaitingTakeover.decrementAndGet();
        log.info("{}: queue {}, {} shard {} completed its takeover after {} failed attempt(s) in {} s and is delivering "
                         + "again",
                 pumpName, owner.queueId(), owner.lane(), owner.shard(), stall.failures,
                 TimeUnit.NANOSECONDS.toSeconds(nanoTime.getAsLong() - stall.sinceNanos));
        notifyObserver(owner, false);
    }

    /**
     * The pump no longer serves this owner (its lease ended). The shard is no longer held here, so it no longer
     * counts as awaiting its takeover. A no-op unless it had been failing.
     */
    void forget(LeasedOwner owner) {
        if (stalls.remove(owner) != null) {
            owner.ownerMetrics().shardsAwaitingTakeover.decrementAndGet();
            notifyObserver(owner, false);
        }
    }

    /**
     * The pump is stopping: none of its shards is served by it any more.
     */
    void forgetAll() {
        for (var owner : List.copyOf(stalls.keySet())) {
            forget(owner);
        }
    }

    /**
     * Caught, unlike every other observer call: this one runs on the pump's thread outside its per-owner guard (from
     * {@code ShardPump.takeOver}, its lease-ended cleanup and its exit), so a throw would end the thread and stop every
     * shard it serves. Called once per transition, never per retry, so an observer that always throws logs twice per
     * stall, not five times a second.
     */
    private void notifyObserver(LeasedOwner owner, boolean stalled) {
        var lane = "ordered".equals(owner.lane()) ? MessageId.Lane.ORDERED : MessageId.Lane.UNORDERED;
        try {
            owner.ownerMetrics().observer().takeoverStalled(lane, owner.shard(), stalled);
        } catch (RuntimeException e) {
            log.error("{}: queue {}, {} shard {}: QueueObserver.takeoverStalled(stalled={}) threw. That is a bug in the "
                              + "observer; the pump keeps running, but what the observer reports for this shard may now "
                              + "be wrong",
                      pumpName, owner.queueId(), owner.lane(), owner.shard(), stalled, e);
        }
    }
}
