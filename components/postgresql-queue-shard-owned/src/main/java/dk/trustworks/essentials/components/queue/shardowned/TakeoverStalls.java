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
import dk.trustworks.essentials.shared.Exceptions;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One pump's shards whose takeover has failed and not yet completed. Each of these shards is leased but not
 * served.
 * <p>
 * {@link ShardPump} retries a failing {@link LeasedOwner#onTakeover} {@link ShardPump#TAKEOVER_RETRY_NANOS} after
 * each failure, with no limit, and keeps the shard (why: {@link LeasedOwner#onTakeover}). Without this, every retry
 * logged a full ERROR stack trace, about five a second for as long as the takeover failed. No counter recorded it, and
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
 * the {@code DurableQueues} adapter's included, whenever there is a {@code MeterRegistry}.
 * <p>
 * The log follows the {@code BlockedKeyReport}/{@code LeaseOverrun} pattern: at most one ERROR a minute <b>per
 * queue</b>, with the stack trace, naming one shard and counting the others; everything else at DEBUG, one line
 * without a stack trace. The window is on the owner's metrics ({@link ShardOwnerMetrics#nextTakeoverStallErrorNanos}),
 * shared by every pump serving the queue, not on a {@code Stall}. Per shard was not enough, for the reason
 * {@code BlockedKeyReport} gives for keys: what makes a takeover fail is rarely one shard's. A trigger or constraint
 * refusing the dead-letter move is database-wide, and an {@code onTakeover} bug throws for every owner, so after a crash
 * or rebalance one instance taking over 64 ordered units and its unordered shards logged some 70 stack-trace ERRORs at
 * once, then 70 a minute. Now the first failure of a stall gets the ERROR when the window is open, and a repeat gets it
 * once the window has passed; either way the line counts the queue's other stalled shards, which
 * {@link ShardOwnerMetrics#shardsAwaitingTakeover} holds exactly. Once no shard of the queue is stalled the window
 * reopens, so the next stall is reported in full at once. An INFO for each shard when it recovers.
 * <p>
 * The observer call is the one observer call the engine catches ({@link QueueObserver#takeoverStalled} says
 * why): what it throws, a non-critical {@code Error} included, is logged at ERROR and the pump goes on. The counters are updated before it, so a
 * throwing observer cannot leave them out of step.
 * <p>
 * Confined to the pump's thread, so nothing here is synchronised: another thread touches it only once the pump's thread
 * has left its loop for good, under a lock that thread released after its last access ({@link ShardPump#forgetAfterExit},
 * {@link ShardPump#handedBack}). The counters it writes and the ERROR window are
 * shared with the other pumps serving the same queue, which is why they are atomics and the window is taken by CAS.
 * <p>
 * Logged under {@link ShardPump}'s logger, where the takeover failure has always been logged, so existing log
 * configuration keeps applying to it.
 */
final class TakeoverStalls {
    private static final Logger log          = LoggerFactory.getLogger(ShardPump.class);
    private static final long   RETRY_MILLIS = TimeUnit.NANOSECONDS.toMillis(ShardPump.TAKEOVER_RETRY_NANOS);

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

        Stall(long sinceNanos) {
            this.sinceNanos = sinceNanos;
            this.failures = 1;
        }
    }

    /**
     * The owner's takeover failed and is retried by the pump {@link ShardPump#TAKEOVER_RETRY_NANOS} later.
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
            // Counted before the window is tried, so the count the ERROR gives is never short of this shard.
            var awaiting = metrics.shardsAwaitingTakeover.incrementAndGet();
            if (takeErrorWindow(metrics, now)) {
                log.error("{}: queue {}, {} shard {} {}. The shard stays leased to this instance and delivers nothing "
                                  + "until its takeover completes; the takeover is retried every {} ms with no limit, "
                                  + "and the pump keeps serving its other shards. {} other shard(s) on this queue are also "
                                  + "awaiting their takeover. "
                                  + "QueueHealth.unownedShards does not count this shard. ShardOwnedQueue.metrics() reports "
                                  + "it under shardsAwaitingTakeover, and every attempt under takeoverFailures; through "
                                  + "MessageQueue it is reported to QueueObserver.takeoverStalled; the gauge "
                                  + "essentials.queue.shards.awaiting.takeover exists only where a MicrometerQueueObserver is "
                                  + "registered on the queue (the Spring starter registers one when there is a MeterRegistry). "
                                  + "Every failed attempt, on any shard, is logged at DEBUG, and at ERROR at most once a "
                                  + "minute per queue while any shard of it keeps failing",
                          pumpName, owner.queueId(), owner.lane(), owner.shard(), what, RETRY_MILLIS, othersThan(awaiting), cause);
            } else {
                // Another shard of this queue had the ERROR within the minute, most likely for the same cause.
                log.debug("{}: queue {}, {} shard {} {}; the shard stays leased and delivers nothing until its takeover "
                                  + "completes, retried every {} ms ({} shard(s) on this queue now awaiting their takeover): {}",
                          pumpName, owner.queueId(), owner.lane(), owner.shard(), what, RETRY_MILLIS, awaiting, String.valueOf(cause));
            }
            notifyObserver(owner, true);
            return;
        }
        stall.failures++;
        if (takeErrorWindow(metrics, now)) {
            log.error("{}: queue {}, {} shard {} has still not completed its takeover: {} failed attempt(s) in {} s; "
                              + "the latest {}. The shard stays leased to this instance and has delivered nothing since. "
                              + "{} other shard(s) on this queue are also awaiting their takeover. "
                              + "Logged at most once a minute per queue",
                      pumpName, owner.queueId(), owner.lane(), owner.shard(), stall.failures,
                      TimeUnit.NANOSECONDS.toSeconds(now - stall.sinceNanos), what,
                      othersThan(metrics.shardsAwaitingTakeover.get()), cause);
        } else {
            // No stack trace: a repeat comes every TAKEOVER_RETRY_NANOS per shard, and the queue's once-a-minute ERROR
            // carries one. The cause's own string still says what failed.
            log.debug("{}: queue {}, {} shard {} {} (failed attempt {}): {}",
                      pumpName, owner.queueId(), owner.lane(), owner.shard(), what, stall.failures, String.valueOf(cause));
        }
    }

    /**
     * Whether this failure gets the queue's ERROR: the window is open (never taken, or reopened by every stall ending) or
     * a minute has passed since it was last taken, and this pump won the CAS against the others serving the queue.
     */
    private static boolean takeErrorWindow(ShardOwnerMetrics metrics, long now) {
        return ErrorWindow.take(metrics.nextTakeoverStallErrorNanos, now);
    }

    /**
     * The queue's stalled shards other than the one being logged. Floored at zero: the count is shared with other pumps,
     * which may have taken their own shards out of it since this one was counted.
     */
    private static int othersThan(int awaiting) {
        return Math.max(0, awaiting - 1);
    }

    /**
     * One shard of the queue fewer is awaiting its takeover. When that was the last, the ERROR window reopens, unless
     * another pump has taken it since, so the queue's next stall is reported in full at once rather than up to a minute
     * later at DEBUG.
     */
    private static void noLongerAwaiting(ShardOwnerMetrics metrics) {
        noLongerAwaiting(metrics.shardsAwaitingTakeover::decrementAndGet, metrics.nextTakeoverStallErrorNanos);
    }

    /**
     * {@link #noLongerAwaiting(ShardOwnerMetrics)} on its two parts, so a test can run another pump between them.
     * <p>
     * The window is read before the count goes down, and reopened only if it still holds that value. Between the count
     * reaching zero and the reopen, a pump on another thread can count a new stall and take the window for its ERROR;
     * reopening unconditionally undid that take, and the new stall's next retry logged a second stack-trace ERROR within
     * the minute. A pump that took the window before the read counted its stall first, so the count does not reach zero
     * here; one that takes it after the read changes it, so the reopen does nothing.
     *
     * @param countDown takes this shard out of the queue's count of shards awaiting their takeover, returning what is left
     * @param window    the queue's {@link ShardOwnerMetrics#nextTakeoverStallErrorNanos}
     */
    static void noLongerAwaiting(IntSupplier countDown, AtomicLong window) {
        var seen = window.get();
        if (countDown.getAsInt() == 0) {
            ErrorWindow.reopen(window, seen);
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
        noLongerAwaiting(owner.ownerMetrics());
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
            noLongerAwaiting(owner.ownerMetrics());
            notifyObserver(owner, false);
        }
    }

    /**
     * The pump is stopping in order ({@code running} cleared), or it had died and its runtime has since stopped
     * ({@link ShardPump#forgetAfterExit()}): none of its shards is served by it any more. Not called when the pump
     * dies while the runtime runs, because its shards stay leased and unserved until their queue hands each back, which
     * forgets that one ({@link ShardPump#handedBack}; why: {@link ShardPump#run}).
     */
    void forgetAll() {
        for (var owner : List.copyOf(stalls.keySet())) {
            forget(owner);
        }
    }

    /**
     * Caught, unlike every other observer call: this one runs on the pump's thread outside its per-owner guard (from
     * {@code ShardPump.takeOver}, its lease-ended cleanup and its exit; a dead pump's hand-back runs it on the queue's
     * heartbeat), so a throw would end the thread and stop every
     * shard it serves. Called once per transition, never per retry, so an observer that always throws logs twice per
     * stall, not five times a second.
     */
    private void notifyObserver(LeasedOwner owner, boolean stalled) {
        var lane = "ordered".equals(owner.lane()) ? MessageId.Lane.ORDERED : MessageId.Lane.UNORDERED;
        try {
            owner.ownerMetrics().observer().takeoverStalled(lane, owner.shard(), stalled);
        } catch (RuntimeException | Error e) {
            // A non-critical Error (an AssertionError from a test double, say) is an observer bug like any other, and would
            // end the pump just the same. A critical one (VirtualMachineError, LinkageError) still propagates, as it does
            // from the per-owner guards.
            Exceptions.rethrowIfCriticalError(e);
            log.error("{}: queue {}, {} shard {}: QueueObserver.takeoverStalled(stalled={}) threw. That is a bug in the "
                              + "observer; the pump keeps running, but what the observer reports for this shard may now "
                              + "be wrong",
                      pumpName, owner.queueId(), owner.lane(), owner.shard(), stalled, e);
        }
    }
}
