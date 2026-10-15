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

import org.slf4j.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One pump's shards whose delivery pass ({@link LeasedOwner#pumpOnce}) has failed and not yet succeeded since.
 * <p>
 * {@link ShardPump} keeps such a shard and tries its pass again {@link ShardPump#PUMP_RETRY_NANOS} (200 ms) later, with
 * no limit and with the lease kept, the same as a failing takeover ({@link TakeoverStalls}). Only the failing shard
 * waits: the pump's other shards, of every queue it serves, are pumped at their own rate meanwhile. It used to sleep
 * 200 ms after any pass in which a statement failed, which held every shard on the pump to five passes a second for as
 * long as one kept failing. A pass that keeps failing - a read or acknowledgement the database refuses, an engine bug
 * throwing - holds the shard and delivers nothing from it, and {@code QueueHealth.unownedShards()} cannot see it because
 * the shard is leased. Before this, every failed pass logged a full stack trace at ERROR and no counter recorded it.
 * <p>
 * Now every failure is counted in {@link ShardOwnerMetrics#pumpFailures} on the owner's metrics, so
 * {@link ShardOwnedQueue#metrics()} sees it. The log follows {@link TakeoverStalls}: at most one ERROR a minute <b>per
 * queue</b>, with the stack trace, from a window on the owner's metrics ({@link ShardOwnerMetrics#nextPumpFailureErrorNanos})
 * shared by every pump serving the queue, because what makes one shard's pass fail (a revoked grant, a full disk, a
 * broken trigger) usually makes them all fail. Everything else at DEBUG, one line without a stack trace. An INFO for a
 * shard when its pass succeeds again. Unlike the takeover window, this one does not reopen when the queue's last failing
 * shard recovers, because nothing counts the failing shards across pumps: a new failure within a minute of the queue's
 * last ERROR is logged at DEBUG, its cause in the line, and at ERROR once the minute has passed if it persists.
 * <p>
 * The ordered lane's <b>batched read</b> ({@link ShardPump}'s one statement per queue for two or more attentive shards,
 * issued before their passes) meets the same causes and is retried on the same passes, so it is reported here too: every
 * failure counted in {@link ShardOwnerMetrics#batchedReadFailures}, a WARN with the stack trace at most once a minute per
 * <b>subscription</b> ({@link ShardOwnerMetrics#nextBatchedReadWarningNanos}, shared by every pump serving it; one per
 * queue unless a queue has several, see {@link BatchedRead}), naming the queue and the shards read for, DEBUG otherwise,
 * an INFO when the subscription's batched read succeeds again. A WARN, not an ERROR: the shards then read for themselves, so the batched read failing stops nothing on
 * its own; when they cannot either, their own failures are the ERROR above. Before this a revoked grant on the ordered
 * lane still logged a stack trace on every pass, from the batched read, next to the throttled ERROR.
 * <p>
 * Confined to the pump's thread, so nothing here is synchronised. The counter and the ERROR window are shared with the
 * other pumps serving the same queue, which is why they are atomics and the window is taken by CAS.
 * <p>
 * Logged under {@link ShardPump}'s logger, where the failure has always been logged, so existing log configuration keeps
 * applying to it.
 */
final class PumpFailures {
    private static final Logger log = LoggerFactory.getLogger(ShardPump.class);

    private final String                                pumpName;
    private final LongSupplier                          nanoTime;
    /**
     * By identity, like the pump's {@code takenOver}: an owner is one ownership of a shard, not a value.
     */
    private final IdentityHashMap<LeasedOwner, Failing> failing             = new IdentityHashMap<>();
    /**
     * By {@link BatchedRead}: the ordered lane's batched read is one statement per queue and metrics object, not per shard.
     * At most one entry per batch this pump has issued, dropped when that batch's read next succeeds.
     */
    private final Map<BatchedRead, Failing>             failingBatchedReads = new HashMap<>();

    PumpFailures(String pumpName) {
        this(pumpName, System::nanoTime);
    }

    PumpFailures(String pumpName, LongSupplier nanoTime) {
        this.pumpName = requireNonNull(pumpName, "No pumpName provided");
        this.nanoTime = requireNonNull(nanoTime, "No nanoTime provided");
    }

    /**
     * One batched read: the owners of one queue that share one {@link ShardOwnerMetrics}. That is one subscription, so
     * normally one per queue; a second {@code consume()} on the same queue in one process (warned about, not refused)
     * gives its owners their own metrics, and grouping by queue id alone charged both subscriptions' failures to
     * whichever came first. The metrics compare by IDENTITY, like {@link ShardPump}'s other per-metrics bookkeeping:
     * they are one subscription's counters, not a value.
     *
     * @param queueId the queue the batched statement reads
     * @param metrics the owners' shared {@link ShardOwnerMetrics}, never the pump's own
     */
    record BatchedRead(short queueId, ShardOwnerMetrics metrics) {
        BatchedRead {
            requireNonNull(metrics, "No metrics provided");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BatchedRead that && queueId == that.queueId && metrics == that.metrics;
        }

        @Override
        public int hashCode() {
            return 31 * Short.hashCode(queueId) + System.identityHashCode(metrics);
        }
    }

    private static final class Failing {
        final long sinceNanos;
        long       failures;

        Failing(long sinceNanos) {
            this.sinceNanos = sinceNanos;
            this.failures = 1;
        }
    }

    /**
     * The owner's delivery pass failed with the connection still healthy; the pump tries it again
     * {@link ShardPump#PUMP_RETRY_NANOS} later, and serves its other owners meanwhile.
     *
     * @param what  how it failed, completing "queue Q, lane shard S ...", e.g. "threw during delivery"
     * @param cause the failure
     */
    void failed(LeasedOwner owner, String what, Throwable cause) {
        var metrics = owner.ownerMetrics();
        metrics.pumpFailures.increment();
        var now     = nanoTime.getAsLong();
        var current = failing.get(owner);
        if (current == null) {
            current = new Failing(now);
            failing.put(owner, current);
        } else {
            current.failures++;
        }
        if (ErrorWindow.take(metrics.nextPumpFailureErrorNanos, now)) {
            log.error("{}: queue {}, {} shard {} {}; continuing with the other shards. {} failed pass(es) in a row over {} s. "
                              + "The shard stays leased to this instance and its pass is tried again {} ms later at the "
                              + "earliest, with no limit, while the pump's other shards keep their own rate; while it keeps "
                              + "failing the shard "
                              + "delivers nothing, and QueueHealth.unownedShards "
                              + "does not count it. ShardOwnedQueue.metrics() counts every failure under pumpFailures. Every "
                              + "failure, on any shard, is logged at DEBUG, and at ERROR at most once a minute per queue",
                      pumpName, owner.queueId(), owner.lane(), owner.shard(), what, current.failures,
                      TimeUnit.NANOSECONDS.toSeconds(now - current.sinceNanos),
                      TimeUnit.NANOSECONDS.toMillis(ShardPump.PUMP_RETRY_NANOS), cause);
        } else {
            // No stack trace: a repeat comes every PUMP_RETRY_NANOS (200 ms) at the soonest, per failing shard, and the
            // queue's once-a-minute ERROR carries one. The cause's own string still says what failed.
            log.debug("{}: queue {}, {} shard {} {} (failed pass {} in a row): {}",
                      pumpName, owner.queueId(), owner.lane(), owner.shard(), what, current.failures, String.valueOf(cause));
        }
    }

    /**
     * The owner's delivery pass completed. A no-op unless it had been failing.
     */
    void succeeded(LeasedOwner owner) {
        var current = failing.remove(owner);
        if (current == null) {
            return;
        }
        log.info("{}: queue {}, {} shard {} is delivering again after {} failed pass(es) in {} s",
                 pumpName, owner.queueId(), owner.lane(), owner.shard(), current.failures,
                 TimeUnit.NANOSECONDS.toSeconds(nanoTime.getAsLong() - current.sinceNanos));
    }

    /**
     * The pump no longer serves this owner (its lease ended). Nothing is counted per shard, so this only drops the entry;
     * a stopped pump needs no equivalent, because {@link ShardRuntime} builds new pumps on every start.
     */
    void forget(LeasedOwner owner) {
        failing.remove(owner);
    }

    /**
     * The ordered lane's batched read for a queue failed; its shards read for themselves this pass, and the pump tries the
     * batched read again on the next pass whose attentive shards of the batch number two or more.
     * <p>
     * Every line names the batch's shards as well as its queue: a queue with two subscriptions in one process has a
     * batched read, a count and a WARN window per subscription, and the queue id alone made their lines identical. The
     * subscriptions compete for the queue's leases, so they never hold the same shard, and the shards tell them apart.
     *
     * @param batch  the batch whose statement failed; counted, and its WARN window taken, on {@link BatchedRead#metrics()}
     * @param owners the owners the statement read for, named in the log line by their shards
     * @param cause  the failure
     */
    void batchedReadFailed(BatchedRead batch, List<? extends LeasedOwner> owners, Throwable cause) {
        var queueId = batch.queueId();
        var metrics = batch.metrics();
        metrics.batchedReadFailures.increment();
        var now     = nanoTime.getAsLong();
        var current = failingBatchedReads.get(batch);
        if (current == null) {
            current = new Failing(now);
            failingBatchedReads.put(batch, current);
        } else {
            current.failures++;
        }
        if (ErrorWindow.take(metrics.nextBatchedReadWarningNanos, now)) {
            log.warn("{}: batched read for queue {} (shards {}) failed; those shards read for themselves this pass. {} failed batched "
                             + "read(s) in a row over {} s; it is tried again on every pass. ShardOwnedQueue.metrics() counts "
                             + "every failure under batchedReadFailures. Every failure is logged at DEBUG, and at WARN at most "
                             + "once a minute per subscription (the consumer reading these shards)",
                     pumpName, queueId, shardsOf(owners), current.failures, TimeUnit.NANOSECONDS.toSeconds(now - current.sinceNanos), cause);
        } else {
            // No stack trace: a repeat can come on every pass, and the subscription's once-a-minute WARN carries one.
            log.debug("{}: batched read for queue {} (shards {}) failed (failure {} in a row); those shards read for themselves this pass: {}",
                      pumpName, queueId, shardsOf(owners), current.failures, String.valueOf(cause));
        }
    }

    /**
     * The ordered lane's batched read for a batch completed. A no-op unless it had been failing on this pump.
     *
     * @param batch  the batch whose statement completed
     * @param owners the owners the statement read for, named in the recovery line by their shards (only read when it is
     *               logged, so the pass that finds nothing to report builds nothing)
     */
    void batchedReadSucceeded(BatchedRead batch, List<? extends LeasedOwner> owners) {
        var current = failingBatchedReads.remove(batch);
        if (current == null) {
            return;
        }
        log.info("{}: batched read for queue {} (shards {}) succeeds again after {} failure(s) in {} s",
                 pumpName, batch.queueId(), shardsOf(owners), current.failures,
                 TimeUnit.NANOSECONDS.toSeconds(nanoTime.getAsLong() - current.sinceNanos));
    }

    /**
     * The shards of a batch's owners, ascending, e.g. {@code [1, 4, 7]}: what tells two subscriptions' batched reads on one
     * queue apart in the log. The shards present vary from pass to pass (only attentive owners are batched), but each
     * belongs to exactly one subscription.
     */
    private static String shardsOf(List<? extends LeasedOwner> owners) {
        return Arrays.toString(owners.stream().mapToInt(LeasedOwner::shard).sorted().toArray());
    }
}
