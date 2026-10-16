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

import dk.trustworks.essentials.shared.Exceptions;
import org.slf4j.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One thread and one connection, serving many shards.
 * <p>
 * <b>Why this exists.</b> Each owner used to run its own loop on its own thread with its own
 * connection, so database contact scaled with {@code shards x lanes x queues}: nine held connections
 * per queue at four shards, which is 425 at the twenty-five queues and eight shards this was
 * eventually asked about. Nothing about shard ownership required that. A shard's identity is a row in
 * the lease table; its state — cursor, hole map, in-flight set — is a few fields in memory. The read
 * is a primary-key range scan that returns in about a hundred microseconds, so one thread can walk
 * many shards' worth of it. The old implementation's {@code CentralizedMessageFetcher} had already
 * reached the same conclusion with one scheduler thread for every queue.
 * <p>
 * <b>Parking.</b> All the shards a pump serves share one {@link ShardWakeup}, so the listener signals
 * the pump rather than an individual shard — the pump then reads whichever of its shards actually has
 * something. That costs an occasional empty read on a quiet shard and saves a thread per shard.
 * <p>
 * <b>Reconnecting rather than dying</b> is inherited from the owners and matters more here, because a
 * thread that dies now takes several shards down with it rather than one. The lease still bounds the
 * retrying: if the database is genuinely gone the heartbeat cannot renew either, every owner is
 * marked lost, and the loop exits. Anything else that ends a pass outside the per-shard guards is
 * reconnected from too ({@link #serve()}); only a critical error ends the thread ({@link #run()}).
 */
final class ShardPump implements Runnable {
    private static final Logger log                  = LoggerFactory.getLogger(ShardPump.class);
    /**
     * How long a shard whose takeover failed waits before the pump retries it. Paced like a failing delivery pass
     * ({@link #PUMP_RETRY_NANOS}), and for the same reason (a permanent failure would otherwise spin), per shard: the
     * pump's other shards are not paced.
     */
    static final         long   TAKEOVER_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(200L);
    /**
     * How long a shard whose delivery pass ({@link LeasedOwner#pumpOnce}) failed on a healthy connection, or threw, or
     * whose selection checks threw, waits before the pump pumps it again. Per shard, never by pacing the pass: a 200 ms
     * sleep after a failed pass held every other shard on the pump, across every queue it serves, to five passes a
     * second for as long as the one shard failed.
     */
    static final         long   PUMP_RETRY_NANOS     = TimeUnit.MILLISECONDS.toNanos(200L);
    /**
     * How long the pump waits before it reconnects after a lost connection or a pass that ended outside the per-owner
     * guards ({@link #serve()}). Paced, or a failure that does not clear spins the loop as fast as it can throw.
     */
    static final         long   RECONNECT_NANOS      = TimeUnit.MILLISECONDS.toNanos(200L);
    /**
     * How long, in seconds, {@link Connection#isValid(int)} may take to tell a failing statement from a failing
     * connection after a {@link SQLException}. Short because the pump - and every shard it serves - waits on the answer,
     * and a connection that cannot answer a validity probe within a second is treated as broken: reconnecting is the cure
     * either way.
     */
    static final         int    CONNECTION_VALIDITY_TIMEOUT_SECONDS = 1;

    private final ShardOwnedStorage                 storage;
    private final ShardOwnerSettings                settings;
    private final ShardOwnerMetrics                 metrics;
    private final ShardWakeup                       wakeup = new ShardWakeup();
    private final CopyOnWriteArrayList<LeasedOwner> owners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean                     running;
    private final AtomicBoolean                     flushOnExit;
    private final String                            name;
    /**
     * Whether the pool is full right now, asked only when acquiring a connection fails. A full pool and
     * an unreachable database fail acquisition with the same exception, and the fixes are unrelated.
     */
    private final Supplier<Optional<String>>        poolExhaustion;
    /**
     * Shards whose takeover keeps failing. Kept across reconnects, unlike {@code takenOver}: a shard is still
     * not served while the pump reconnects.
     */
    private final TakeoverStalls                    stalls;
    /**
     * Shards whose delivery pass keeps failing, and queues whose ordered-lane batched read does. Kept across reconnects,
     * like {@code stalls}.
     */
    private final PumpFailures                      pumpFailures;
    /**
     * Makes {@link #add}, the pump's death and {@link #handedBack} agree on which owners are counted in
     * {@link ShardOwnerMetrics#shardsOnDeadPumps}, and serialises {@link #forgetAfterExit()}. Guards {@link #countedDead}
     * and {@link #forgotten}; {@link #dead} is written under it.
     */
    private final Object                            deathLock   = new Object();
    /**
     * The owners counted in {@link ShardOwnerMetrics#shardsOnDeadPumps}: they sit on this pump, it has died, and their
     * queue has not handed their lease back yet ({@link #handedBack}).
     */
    private final Set<LeasedOwner>                  countedDead = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Set when the thread ends on a critical error while {@code running} is still set. Read by {@link ShardRuntime} to
     * route new shards elsewhere.
     */
    private volatile boolean                        dead;
    /**
     * Set as the last thing the pump's thread does. Its volatile write follows every access the thread made to
     * {@link #stalls}, so whoever reads it as {@code true} may read them.
     */
    private volatile boolean                        exited;
    /**
     * Whether {@link #forgetAfterExit()} has run: what a dead pump kept counted has been forgotten, and an owner added
     * since must not be counted again.
     */
    private          boolean                        forgotten;
    /**
     * When the next pass failure may be logged at ERROR ({@link System#nanoTime()}), or
     * {@link ErrorWindow#NEVER_TAKEN} before the first. Pump thread only.
     */
    private          long                           nextLoopFailureErrorNanos = ErrorWindow.NEVER_TAKEN;
    /**
     * Passes that failed since the last one that completed. Pump thread only.
     */
    private          long                           loopFailures;

    ShardPump(ShardOwnedStorage storage, ShardOwnerSettings settings, ShardOwnerMetrics metrics,
              AtomicBoolean running, AtomicBoolean flushOnExit, String name,
              Supplier<Optional<String>> poolExhaustion) {
        this.storage = requireNonNull(storage, "No storage provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.metrics = requireNonNull(metrics, "No metrics provided");
        this.running = requireNonNull(running, "No running flag provided");
        this.flushOnExit = requireNonNull(flushOnExit, "No flushOnExit flag provided");
        this.name = requireNonNull(name, "No name provided");
        this.poolExhaustion = requireNonNull(poolExhaustion, "No poolExhaustion provided");
        this.stalls = new TakeoverStalls(name);
        this.pumpFailures = new PumpFailures(name);
    }

    /**
     * The wake-up every shard this pump serves is registered against.
     */
    public ShardWakeup wakeup() {
        return wakeup;
    }

    /**
     * Hand this pump another shard. Safe while it is running — rebalancing acquires shards long after
     * the pumps have started, and the new owner is picked up on the next iteration.
     */
    public void add(LeasedOwner owner) {
        requireNonNull(owner, "No owner provided");
        Throwable endFailed = null;
        synchronized (deathLock) {
            owners.add(owner);
            // ShardRuntime routes new shards to live pumps, so this is reached only when none is left, or when the pump
            // died between the shard being placed on it and being handed to it (ShardRuntime.Placement). Either way
            // nothing will serve the shard here, so it is ended like the owners the pump held when it died (markDead).
            if (dead && !forgotten) {
                endFailed = endOnDeadPump(owner);
            }
        }
        // A throw from ending the owner is its own, logged by endOnDeadPump, and the owner is counted and handed back
        // whatever it was. Only a critical error reaches the caller - the queue registering the shard - as from every
        // per-owner guard.
        if (endFailed != null) {
            Exceptions.rethrowIfCriticalError(endFailed);
        }
        // So a pump parked on its backstop starts serving the new shard now rather than up to half a
        // second later.
        wakeup.signal();
    }

    public int shardsServed() {
        return owners.size();
    }

    /**
     * Whether this pump's thread ended on a critical error while its runtime was running ({@link #run()}). A dead pump
     * serves nothing, and {@link ShardRuntime} routes new shards to the live ones. True only once every owner it held has
     * been counted and ended - or ending it threw, which still leaves it counted ({@link #markDead()}).
     */
    boolean isDead() {
        return dead;
    }

    /**
     * Whether {@code owner} sits on this pump, which has died, and its queue has not handed its lease back yet
     * ({@link #handedBack}): an owner its queue's heartbeat has to release. Answers from {@link #countedDead} under
     * {@link #deathLock}, so it is true as soon as the owner has been ended by the death, before {@link #isDead()} is.
     */
    boolean awaitsHandBack(LeasedOwner owner) {
        synchronized (deathLock) {
            return countedDead.contains(owner);
        }
    }

    /**
     * {@code owner}'s queue has given its lease up after this pump died - released it to the cluster, or stopped: stop
     * counting it in {@link ShardOwnerMetrics#shardsOnDeadPumps} and in {@link ShardOwnerMetrics#shardsAwaitingTakeover},
     * and drop it. A no-op for an owner that is not counted here.
     * <p>
     * Safe from the queue's thread although the stall and failure maps belong to the pump's: an owner is counted here only
     * once the thread has left its loop for good ({@link #markDead()}, or {@link #add} afterwards), and its last access to
     * those maps precedes its release of {@link #deathLock}, which this takes.
     */
    void handedBack(LeasedOwner owner) {
        synchronized (deathLock) {
            if (!countedDead.remove(owner)) {
                return;
            }
            owner.ownerMetrics().shardsOnDeadPumps.decrementAndGet();
            owners.remove(owner);
            stalls.forget(owner);
            pumpFailures.forget(owner);
        }
    }

    /**
     * Whether this pump's thread has ended, in order or not.
     */
    boolean hasExited() {
        return exited;
    }

    /**
     * Serve until {@code running} is cleared.
     * <p>
     * <b>The recovery rule: a pump does not die of anything a retry could cure.</b> A failing statement, or an unchecked
     * exception or non-critical {@code Error} from one owner's {@code pumpOnce}, {@code onTakeover}, selection checks
     * ({@code leaseHeld}, {@code deliveryPermitted}, {@code needsAttention}) or stop-time {@code flushOnStop}, is that
     * owner's alone: the per-owner guards count it ({@link PumpFailures}, {@link TakeoverStalls}; a failed flush is logged)
     * and the other owners keep being served. A lost connection, and anything thrown outside those guards (by the pool,
     * the pump's own bookkeeping, or an owner's {@code parkDeadlineMillis}), end the pass: {@link #serve()} reconnects
     * {@link #RECONNECT_NANOS} (200 ms) later and runs every owner's takeover again, which the owners make safe (it already
     * runs on every reconnect), and the pass counts in {@link ShardOwnerMetrics#pumpLoopFailures}. {@code parkDeadlineMillis}
     * is asked only on a pass that delivered nothing, after every attentive owner was pumped, so a throw there costs no
     * delivery on that pass; but while it persists every such idle pass ends that way, so the pump reconnects and reruns
     * every takeover about five times a second, and a message arriving meanwhile waits for the reconnect rather than
     * being woken for. Only a critical error ({@code VirtualMachineError}, {@code LinkageError}: see
     * {@link Exceptions#isCriticalError}) ends the thread, because the JVM is then in no state to trust a retry - so the
     * pump is not restarted, and its shards are handed back instead (below).
     * <p>
     * <b>Stopping and dying are told apart by {@code running}, not by how {@link #serve()} ended.</b> On an orderly stop
     * the pump serves none of its shards any more, so none of them is awaiting a takeover here, and its stalls are
     * forgotten. A pump that ends while {@code running} is still set has died, and nothing restarts it.
     * <p>
     * <b>A dead pump's shards are handed back, not kept.</b> The pump marks itself dead ({@link #markDead()}):
     * {@link ShardRuntime} routes newly acquired shards to its live pumps, and every owner on this one is ended, an owner
     * handed to it later included ({@link #add}). An unordered owner's lease ends ({@code onLeaseEnded(RELEASED)}). An
     * ordered owner drains instead ({@code beginDraining()}): its handlers run on the runtime's shared executor and may
     * still be running, and handing a unit over with a key in a handler lets the successor start that key beside it, which
     * is reordering. Its queue's heartbeat then hands the leases back to the cluster
     * ({@code ShardOwnedQueue.handBackUnitsOnDeadPumps}), an ordered one only once no key is in a handler. Other instances
     * take them under a bumped fence, or this one does, on a live pump ({@link ShardRuntime#pumpFor}). The acknowledgements
     * still pending on them are not flushed - the owners' state is not trusted after a critical error on the thread that
     * kept it - so those messages are redelivered by the next owner (at-least-once).
     * <p>
     * Kept, the shards stayed leased to this instance and were served by nobody until the runtime was restarted, which is
     * worse than a crash: a crash is noticed and restarted, and its shards are taken over a lease lifetime later. Two
     * reasons for keeping them were weighed. That the same cause could kill the pump they were handed to: for a JVM-wide
     * cause (metaspace, a missing class) a re-acquired shard already went to a live pump here, and another instance is
     * another JVM. A cause carried by the data - an {@code OutOfMemoryError} from reading one shard's oversized payloads -
     * does follow the shard to its next pump or instance, which reads the same rows; that is accepted, as it is after a
     * crash, which hands the same shard on the same way. That a release cannot be trusted after a
     * {@code VirtualMachineError}: the release is the queue's, on its heartbeat thread rather than the dead pump's, and it
     * is one statement, bounded by this instance's ownership, that changes no message.
     * <p>
     * Until its lease is handed back a shard is counted in {@link ShardOwnerMetrics#shardsOnDeadPumps} on its queue's
     * metrics, and, if its takeover was failing, still in {@link ShardOwnerMetrics#shardsAwaitingTakeover}: forgetting
     * that at the death zeroed the one metric for a leased-but-unserved shard exactly when it had got worse. Both fall when
     * the lease is handed back ({@link #handedBack}), or when the runtime stops ({@link #forgetAfterExit()}). Neither
     * reaches a {@code QueueObserver}, so through {@code MessageQueue} (the Spring starter, the {@code DurableQueues}
     * adapter) the ERROR below is the only signal of the death itself; the shards handed back are reported as ownership
     * changes. The death is logged at ERROR here because {@link ShardRuntime} submits the pump to an executor whose
     * {@code Future} nobody reads, so the throwable would otherwise vanish; it is still rethrown. The runtime runs a pump
     * short until it is restarted, and with no live pump left this instance acquires nothing: its share of the shards
     * stays unowned, which {@code QueueHealth.unownedShards()} reports. The cure is restarting the {@code ShardRuntime}
     * (or the application), which the ERROR says.
     * <p>
     * {@code running} is the flag of the runtime's run that started this pump, not one shared across restarts, so a pump
     * that outlived {@link ShardRuntime#stop()}'s wait and leaves {@code serve()} after the next {@link ShardRuntime#start()}
     * still reads it as cleared and exits in order.
     */
    @Override
    public void run() {
        Throwable died = null;
        try {
            serve();
        } catch (Throwable e) {
            died = e;
            throw e;
        } finally {
            // Nested, so a throw from the bookkeeping - the ERROR below, after a critical error - still marks the pump
            // dead and exited: unmarked it read as live and was handed new shards, and unexited it was never forgotten.
            try {
                if (running.get()) {
                    try {
                        log.error("{} died while its runtime is still running, and nothing restarts it. The {} shard(s) it served are "
                                          + "handed back to the cluster by their queues' next heartbeat (an ordered unit once no key is in "
                                          + "a handler; keys still there after shedGrace are named at WARN), for another instance or a live "
                                          + "pump here to take over; acknowledgements pending on "
                                          + "them are not flushed, so those messages are redelivered. Shards acquired from now on go to the "
                                          + "runtime's live pumps; with none left this instance acquires nothing. Restart the ShardRuntime "
                                          + "(or the application) to get the pump back. Until handed back, these shards are counted in "
                                          + "shardsOnDeadPumps, and those whose takeover was failing in shardsAwaitingTakeover",
                                  name, owners.size(), died);
                    } finally {
                        markDead();
                    }
                } else {
                    stalls.forgetAll();
                }
            } finally {
                exited = true;
                if (dead && !running.get()) {
                    // The runtime stopped while this pump was dying, possibly after its stop had looked for exited pumps.
                    forgetAfterExit();
                }
            }
        }
    }

    /**
     * The pump has died while its runtime runs: end every owner on it ({@link #endOnDeadPump}) so its queue hands the
     * lease back ({@link #run()} says why), and route new shards elsewhere ({@link #dead}).
     * <p>
     * {@link #dead} is set LAST, after every owner is counted and ended: {@link #isDead()} reads it without the lock, so
     * whoever sees the pump dead also sees every owner it held counted in {@link ShardOwnerMetrics#shardsOnDeadPumps}.
     * Set first, a reader could see the death with only part of the count. Nothing is lost by the owners being ended
     * before the flag: {@link #add} decides under the lock, and {@link ShardRuntime#onDeadPump} asks
     * {@link #awaitsHandBack} (under the lock) rather than {@link #isDead()}.
     * <p>
     * Last, but on every path: ending an owner runs its code right after a critical error, when another throw is most
     * likely. A throw from one owner is that owner's alone ({@link #endOnDeadPump}) - logged, not rethrown, even a critical
     * one, because the pump is already dying and {@link #run()} rethrows its death - so the owners after it are still
     * ended and counted. The same holds for anything {@link #endOnDeadPump} still throws - an {@code OutOfMemoryError}
     * in its ERROR line, after the owner is counted, or, rarely, in counting it, which leaves that one owner uncounted:
     * the throw is dropped here and the loop goes on, or the owners after it would stay leased and unserved,
     * uncounted, until a restart. And {@link #dead} is set in a {@code finally}:
     * left unset, the pump read as live, and {@link ShardRuntime#pumpFor} kept placing new shards on a pump that serves
     * nothing.
     */
    private void markDead() {
        synchronized (deathLock) {
            try {
                for (var owner : owners) {
                    try {
                        endOnDeadPump(owner);
                    } catch (Throwable loggingFailed) {
                        // Mostly endOnDeadPump's own ERROR line, after the owner was counted. Rarely counting it
                        // (countedDead.add or the metric increment, an OutOfMemoryError say): that one owner is then
                        // not ended, and may be left uncounted and never handed back. Either way the loop goes on.
                        // Dropped, not logged: logging may be what just failed, and run() rethrows the pump's death.
                    }
                }
            } finally {
                dead = true;
            }
        }
    }

    /**
     * Count {@code owner} in {@link ShardOwnerMetrics#shardsOnDeadPumps} until its queue hands its lease back
     * ({@link #handedBack}), and end it, because this pump will never serve it again. An unordered owner's lease ends. An
     * ordered owner drains: a key may still be in one of its handlers on the shared executor, and the lane never releases
     * a unit with a key in flight. Its queue's heartbeat releases the lease, an ordered one once nothing is in a handler.
     * Called under {@link #deathLock}.
     * <p>
     * Counted before the owner's code runs, so a throw from {@code onLeaseEnded} or {@code beginDraining} leaves the owner
     * counted and awaiting hand-back ({@link #awaitsHandBack}): its queue still releases the lease row and then calls
     * {@code onLeaseEnded(RELEASED)} itself, which the owners make safe to repeat. The throw is logged at ERROR here and
     * returned, not thrown: whether it propagates is the caller's choice ({@link #markDead()}, {@link #add}). What can
     * still throw out of here is that ERROR line, with the owner counted, or, rarely (an {@code OutOfMemoryError}),
     * counting it, before it is; {@link #markDead()} drops either.
     *
     * @return what ending the owner threw, or {@code null}
     */
    private Throwable endOnDeadPump(LeasedOwner owner) {
        if (!countedDead.add(owner)) {
            return null;
        }
        owner.ownerMetrics().shardsOnDeadPumps.incrementAndGet();
        try {
            if ("ordered".equals(owner.lane())) {
                owner.beginDraining();
            } else {
                owner.onLeaseEnded(LeasedOwner.LeaseEnd.RELEASED);
            }
            return null;
        } catch (RuntimeException | Error e) {
            log.error("{}: ending queue {}, {} shard {} on the dead pump threw; the shard is still counted in shardsOnDeadPumps, "
                              + "and its queue's heartbeat still hands its lease back",
                      name, owner.queueId(), owner.lane(), owner.shard(), e);
            return e;
        }
    }

    /**
     * The runtime has stopped and this pump's thread has ended, so none of its shards is served by it any more: forget its
     * stalls, and stop counting its shards in {@link ShardOwnerMetrics#shardsOnDeadPumps}. A no-op for a pump that stopped
     * in order, which forgot its stalls on its way out and counted nothing. A pump that had died while the runtime ran
     * kept both counted until each shard's lease was handed back ({@link #run()}, {@link #handedBack}); without this the
     * shards not handed back by then stayed counted, in metrics that outlive the runtime, for the rest of the process, so
     * the restart its own ERROR recommends never cleared the count and the observer was never told the stall ended.
     * <p>
     * A no-op, too, until the pump's thread is known to have ended ({@link #exited}): the stall map belongs to the pump's
     * thread, and the volatile write of {@code exited} after its last access is what makes another thread's read safe. So
     * {@link ShardRuntime#stop()} may call it on every pump whether or not its wait for the pumps timed out; a pump that is
     * still in its loop forgets its own stalls when it leaves, since {@code running} is cleared by then. Idempotent.
     */
    void forgetAfterExit() {
        synchronized (deathLock) {
            if (!exited || forgotten) {
                return;
            }
            forgotten = true;
            stalls.forgetAll();
            for (var owner : countedDead) {
                owner.ownerMetrics().shardsOnDeadPumps.decrementAndGet();
            }
            countedDead.clear();
        }
    }

    private void serve() {
        while (running.get()) {
            try (var connection = storage.connection()) {
                // Reset per connection, not per owner: after a reconnect every owner has to bump
                // attempts again, because the takeover it is recovering from may be its own.
                var takenOver = Collections.newSetFromMap(new IdentityHashMap<LeasedOwner, Boolean>());
                // When each owner whose takeover failed may try again (System.nanoTime()). Reset with takenOver: after
                // a reconnect every takeover is retried at once.
                var takeoverRetryAtNanos = new IdentityHashMap<LeasedOwner, Long>();
                // When each owner whose delivery pass failed may be pumped again (System.nanoTime()). Reset with
                // takenOver: after a reconnect every owner is pumped again at once.
                var pumpRetryAtNanos = new IdentityHashMap<LeasedOwner, Long>();

                while (running.get()) {
                    var delivered = 0;
                    // needsAttention has a side effect — it consumes the shard's wake-up flag — so
                    // it must be asked exactly once per owner per pass.
                    var attentive = new ArrayList<LeasedOwner>();
                    for (var owner : owners) {
                        // Before the first pump, never after. Pumps start before any shard has been
                        // leased and rebalancing adds shards later, so an owner's first iteration is
                        // routinely not the pump's first — and bumping after it had already delivered
                        // and acknowledged found nothing to bump, which is what a crashed owner's
                        // successor depends on to stop an infinite redelivery loop.
                        if (!takenOver.contains(owner)) {
                            // Not pumped until its takeover has completed: onTakeover is what positions
                            // the cursor and, on the ordered lane, loads the dead-letter and retry blocks
                            // the first dispatch must already respect.
                            var retryAt = takeoverRetryAtNanos.get(owner);
                            if (retryAt != null && System.nanoTime() - retryAt < 0) {
                                continue;
                            }
                            takenOver.add(owner);
                            if (!takeOver(connection, owner, takenOver)) {
                                // Paced per owner, never by pacing the pass: that held every other shard on this
                                // pump to one pass per 200 ms for as long as the takeover kept failing. park()
                                // wakes in time for the retry.
                                takeoverRetryAtNanos.put(owner, System.nanoTime() + TAKEOVER_RETRY_NANOS);
                                continue;
                            }
                            takeoverRetryAtNanos.remove(owner);
                        }
                        // A failed pass is retried PUMP_RETRY_NANOS later, BEFORE needsAttention like the delivery
                        // gate below and for the same reason: a wake-up arriving meanwhile must still be there when the
                        // retry falls due. Dropped once due, whatever the checks after it decide, so a due entry never
                        // reaches park(), which would otherwise wake every millisecond for it.
                        var pumpRetryAt = pumpRetryAtNanos.get(owner);
                        if (pumpRetryAt != null) {
                            if (System.nanoTime() - pumpRetryAt < 0) {
                                continue;
                            }
                            pumpRetryAtNanos.remove(owner);
                        }
                        boolean selected;
                        try {
                            // In this order, short-circuiting. deliveryPermitted BEFORE needsAttention, which consumes
                            // the shard's wake-up flag: skipping after it would swallow the signal and leave the shard
                            // waiting for the backstop once delivery is permitted again. And needsAttention because the
                            // wake-up was for one of this pump's shards, not all of them.
                            selected = owner.leaseHeld() && owner.deliveryPermitted() && owner.needsAttention();
                        } catch (RuntimeException | Error e) {
                            // The owner's own code, so the owner's own failure, guarded like pumpOnce below. Left to the
                            // outer catch it ended the pass before any owner was pumped and reconnected, rerunning every
                            // owner's takeover: one owner's bug stopped every shard on the pump for as long as it threw.
                            // Retried PUMP_RETRY_NANOS later; a critical error still ends the pump (run()).
                            Exceptions.rethrowIfCriticalError(e);
                            pumpFailures.selectionFailed(owner, e);
                            pumpRetryAtNanos.put(owner, System.nanoTime() + PUMP_RETRY_NANOS);
                            continue;
                        }
                        // The checks completed without throwing, so a run of selection failures ends here, whether or
                        // not they select the shard: an idle shard is not pumped, and its run used to stay open until it
                        // was, so a throw hours later was reported as the next in a row. A run of failed passes is left
                        // alone; only the pass succeeding ends that one, or a retry whose checks pass would end it.
                        pumpFailures.selectionSucceeded(owner);
                        if (!selected) {
                            continue;
                        }
                        attentive.add(owner);
                    }

                    batchRead(connection, attentive);

                    for (var owner : attentive) {
                        try {
                            delivered += owner.pumpOnce(connection);
                            pumpFailures.succeeded(owner);
                        } catch (RuntimeException | Error e) {
                            // One thread now serves several shards, so an unchecked exception from
                            // one owner used to end the thread and stall every shard beside it —
                            // silently, because the lease went on being renewed. Whatever is wrong
                            // with this owner is its own problem; the rest keep running.
                            //
                            // A non-critical Error (an AssertionError) included, as for a handler (ShardOwner):
                            // left to the outer catch it ended the pass for every owner after this one and forced a
                            // reconnect, which reran every owner's takeover, five times a second while it persisted.
                            // A critical one still ends the pump (run()).
                            //
                            // Counted and rate-limited (PumpFailures): it is retried with the lease kept, and a
                            // stack trace per pass was a log flood with no counter behind it. Paced per owner, like a
                            // failing statement: retried on every pass, it ran as fast as its siblings kept the pump busy.
                            Exceptions.rethrowIfCriticalError(e);
                            pumpFailures.failed(owner, "threw during delivery", e);
                            pumpRetryAtNanos.put(owner, System.nanoTime() + PUMP_RETRY_NANOS);
                        } catch (SQLException e) {
                            // A failing STATEMENT is not a failing CONNECTION, and conflating them
                            // is worse than either. Letting this reach the reconnect loop below made
                            // a permanently-broken statement — a bad cast, a missing column, a
                            // constraint — look exactly like a database blip: the pump reconnected
                            // several times a second, forever, logging at WARN, while every shard it
                            // served quietly stopped delivering. Nothing failed and nothing alerted.
                            //
                            // So ask the connection whether it is actually broken. If it is, rethrow
                            // and let the reconnect loop do its job; if it is not, this is a bug in
                            // one owner's SQL and it is treated like any other bug in one owner.
                            if (!connection.isValid(CONNECTION_VALIDITY_TIMEOUT_SECONDS)) {
                                throw e;
                            }
                            pumpFailures.failed(owner, "issued a statement that failed on a healthy connection", e);
                            // Paced, like the reconnect path, and for the same reason: a statement that fails
                            // permanently - a blocked delete, a full disk, a broken constraint - otherwise spins as
                            // fast as the database can refuse it (measured at 8 405 stack traces in one test run). Per
                            // owner, never by pacing the pass: a 200 ms sleep after the pass held every other shard on
                            // this pump to five passes a second while this one failed. park() wakes in time for it.
                            pumpRetryAtNanos.put(owner, System.nanoTime() + PUMP_RETRY_NANOS);
                        }
                    }
                    owners.removeIf(owner -> {
                        if (stillLeased(owner, pumpRetryAtNanos)) {
                            return false;
                        }
                        takenOver.remove(owner);
                        takeoverRetryAtNanos.remove(owner);
                        pumpRetryAtNanos.remove(owner);
                        stalls.forget(owner);
                        pumpFailures.forget(owner);
                        return true;
                    });
                    // Asked BEFORE the pass is reported complete: parkDeadlineMillis is an owner call outside the
                    // per-owner guards, so a throw from it ends this pass (the outer catch). Reported first, a
                    // parkDeadlineMillis that kept throwing logged "completed again" at INFO on every pass and reset the
                    // count of failed passes in a row to one each time.
                    var parkMillis = delivered == 0 ? parkMillis(takenOver, takeoverRetryAtNanos, pumpRetryAtNanos) : 0L;
                    if (loopFailures > 0) {
                        log.info("{}: a pass completed again after {} failed pass(es)", name, loopFailures);
                        loopFailures = 0;
                    }

                    if (delivered == 0) {
                        park(parkMillis);
                    }
                }
                if (flushOnExit.get()) {
                    for (var owner : owners) {
                        if (!flushOnStop(connection, owner)) {
                            break;
                        }
                    }
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (SQLException e) {
                if (!running.get()) {
                    // Stopping: there is no reconnect to announce, and losing the connection on the way out is not a
                    // connection failure the pump recovers from. What it had not flushed is redelivered by the shards'
                    // next owners (at-least-once).
                    log.warn("{} lost its connection while stopping", name, e);
                    return;
                }
                metrics.connectionFailures.increment();
                // Asked for any SQLException, not only a timeout type: pools disagree on what they
                // throw when full (Hikari a SQLTransientConnectionException, DBCP2 and Tomcat plain
                // SQLExceptions), and the live count is what separates the cases anyway.
                var exhausted = poolExhaustion.get();
                if (exhausted.isPresent()) {
                    // Not a database problem, and reporting it as one sends the reader to the wrong
                    // place. The shards this pump serves deliver nothing until a connection is free.
                    log.error("{} could not get a connection: {}. Its shards stop delivering until one is "
                                      + "free; raise the pool's maximum size. Retrying", name, exhausted.get(), e);
                } else {
                    log.warn("{} lost its connection; reconnecting", name, e);
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(RECONNECT_NANOS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException | Error e) {
                // Thrown outside every per-owner guard: by the pool handing out the connection, from the pump's own
                // bookkeeping, or from an owner's parkDeadlineMillis (asked only on a pass that delivered nothing, after
                // every attentive owner was pumped; while it keeps throwing, every idle pass ends here, reconnects and
                // reruns every owner's takeover - run() says what that costs). Ending the thread here used to leave
                // every shard on this pump leased and served by nobody, for something a retry may well cure; so it is
                // treated like a lost connection. A critical error still ends the pump (run() says what follows).
                Exceptions.rethrowIfCriticalError(e);
                if (!running.get()) {
                    // Stopping: there is no reconnect and no takeover to announce, and a stop is not a failed pass.
                    log.warn("{}: a pass ended on an exception thrown outside its per-shard guards while the pump was stopping",
                             name, e);
                    return;
                }
                passFailed(e);
                try {
                    // Paced like the reconnect, or a failure that does not clear spins the loop as fast as it can throw.
                    TimeUnit.NANOSECONDS.sleep(RECONNECT_NANOS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * A pass ended on something thrown outside the per-owner guards ({@link #serve()}). Counted in
     * {@link ShardOwnerMetrics#pumpLoopFailures} on the runtime's metrics and once on each distinct queue's metrics among
     * the owners served - a pass is no one queue's, and only the owners' metrics reach {@link ShardOwnedQueue#metrics()}.
     * Logged at ERROR with the stack trace at most once a minute per pump, at DEBUG otherwise: a cause that does not clear
     * fails every pass, five a second.
     */
    private void passFailed(Throwable e) {
        var charged = Collections.newSetFromMap(new IdentityHashMap<ShardOwnerMetrics, Boolean>());
        charged.add(metrics);
        for (var owner : owners) {
            charged.add(owner.ownerMetrics());
        }
        charged.forEach(queueMetrics -> queueMetrics.pumpLoopFailures.increment());
        loopFailures++;
        var now = System.nanoTime();
        if (ErrorWindow.isOpen(nextLoopFailureErrorNanos, now)) {
            nextLoopFailureErrorNanos = now + ErrorWindow.EVERY_NANOS;
            log.error("{}: a pass ended on an exception thrown outside its per-shard guards ({} failed pass(es) in a row). "
                              + "The pump reconnects in {} ms and runs the takeover of each of its {} shard(s) again; while "
                              + "the cause persists those shards deliver little or nothing, and QueueHealth.unownedShards does "
                              + "not count them because they stay leased. Counted in pumpLoopFailures; logged at ERROR at most "
                              + "once a minute per pump, every failure at DEBUG",
                      name, loopFailures, TimeUnit.NANOSECONDS.toMillis(RECONNECT_NANOS), owners.size(), e);
        } else {
            log.debug("{}: a pass ended on an exception thrown outside its per-shard guards ({} failed pass(es) in a row): {}",
                      name, loopFailures, String.valueOf(e));
        }
    }

    /**
     * {@link LeasedOwner#leaseHeld()} for the end-of-pass sweep that drops owners whose lease ended. A throw keeps the owner
     * and is its own failure, as in the selection checks: escaping here, it ended every pass after the selection guard had
     * already caught the same throw. Charged only when the owner is not already waiting out a retry, so a lease check
     * that keeps throwing is counted once per retry, by whichever of the two asks first.
     * <p>
     * A lease check that completes here ends no run of selection failures: it is one check of three, and an owner waiting
     * out a retry for a throwing {@code deliveryPermitted} or {@code needsAttention} would otherwise have its run cut on
     * every pass, never counting past one. The run a throw here opens ends when the owner's selection checks next all
     * complete, on the next pass.
     */
    private boolean stillLeased(LeasedOwner owner, Map<LeasedOwner, Long> pumpRetryAtNanos) {
        try {
            return owner.leaseHeld();
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            if (!pumpRetryAtNanos.containsKey(owner)) {
                pumpFailures.selectionFailed(owner, e);
                pumpRetryAtNanos.put(owner, System.nanoTime() + PUMP_RETRY_NANOS);
            }
            return true;
        }
    }

    /**
     * Flush one owner's acknowledgements as the pump stops. A failure is that owner's alone: logged at WARN, and the next
     * owner is flushed. Escaping, it skipped every owner after this one, and reached {@link #serve()}'s outer catch,
     * which counted the stop as a failed pass in {@code pumpLoopFailures} and logged at ERROR a reconnect that never
     * came. What is not flushed is redelivered by the shard's next owner (at-least-once).
     *
     * @return whether the next owner can be flushed: {@code false} once the connection itself is broken
     */
    private boolean flushOnStop(Connection connection, LeasedOwner owner) {
        try {
            owner.flushOnStop(connection);
            return true;
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            warnFlushOnStopFailed(owner, e);
            return true;
        } catch (SQLException e) {
            if (isValid(connection)) {
                warnFlushOnStopFailed(owner, e);
                return true;
            }
            log.warn("{}: lost its connection flushing the acknowledgements of queue {}, {} shard {} on stop; the shards "
                             + "not flushed yet are not flushed, and their next owners redeliver what was not acknowledged",
                     name, owner.queueId(), owner.lane(), owner.shard(), e);
            return false;
        }
    }

    /**
     * One owner's stop-time flush failed while the connection is still usable: the next owner is flushed.
     */
    private void warnFlushOnStopFailed(LeasedOwner owner, Throwable cause) {
        log.warn("{}: flushing the acknowledgements of queue {}, {} shard {} on stop failed; flushing the others",
                 name, owner.queueId(), owner.lane(), owner.shard(), cause);
    }

    /**
     * {@link Connection#isValid(int)} for {@link #flushOnStop}, where a probe that itself throws counts as a broken
     * connection. The pass's own probes call {@code isValid} directly so that throw reaches the reconnect loop.
     */
    private static boolean isValid(Connection connection) {
        try {
            return connection.isValid(CONNECTION_VALIDITY_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Run one owner's {@link LeasedOwner#onTakeover}, treating a failure the way a failing
     * {@link LeasedOwner#pumpOnce} is treated: a failing STATEMENT is not a failing CONNECTION.
     * <p>
     * A takeover pass that failed on a healthy connection - an exhaustion dead-letter move refused by a
     * constraint or a trigger, say - used to reach the reconnect loop. It was retried there, but only
     * by reconnecting several times a second, logged at WARN as a lost connection, and every shard
     * this pump serves stopped delivering for as long as the pass kept failing: a pass runs every
     * takeover before it pumps any owner, so the throw ended each pass before any owner was pumped,
     * wherever the failing owner sat among them. Now the owner is taken out of
     * {@code takenOver} and its takeover is retried {@link #TAKEOVER_RETRY_NANOS} (200 ms) later, while the other shards
     * keep running at their full rate: the wait is the failing owner's alone, and the pump parks on it like on any other
     * owner deadline. Pacing the pass instead held every other shard on this pump to one pass per 200 ms for as long as
     * the takeover failed; a failing {@code pumpOnce} is paced per owner the same way ({@link #PUMP_RETRY_NANOS}). The
     * owners keep the retry cheap: the attempt charge is recorded the moment it commits and the
     * exhaustion pass is cleared only once it has run, so retrying neither charges twice nor skips it.
     * <p>
     * <b>Retrying has no bound.</b> While the takeover keeps failing, the shard stays leased to this instance and
     * delivers nothing, and no other instance takes it (why: {@link LeasedOwner#onTakeover}).
     * {@code QueueHealth.unownedShards()} does not count it, because it is leased. Watch
     * {@link ShardOwnerMetrics#shardsAwaitingTakeover} in {@link ShardOwnedQueue#metrics()} next to it, or, through
     * {@code MessageQueue}, the observer callback {@code QueueObserver.takeoverStalled} (Micrometer:
     * {@code essentials.queue.shards.awaiting.takeover}):
     * {@link TakeoverStalls} counts the shard there from its first failure until the takeover completes, tells the
     * observer at both ends, counts every failure in {@link ShardOwnerMetrics#takeoverFailures}, and logs at ERROR at
     * most once a minute per subscription, naming one shard and counting the others, and every failure at DEBUG.
     *
     * @return whether the takeover completed
     * @throws SQLException only when the connection itself is broken - the reconnect loop's case
     */
    private boolean takeOver(Connection connection, LeasedOwner owner, Set<LeasedOwner> takenOver) throws SQLException {
        try {
            owner.onTakeover(connection);
            stalls.completed(owner);
            return true;
        } catch (RuntimeException | Error e) {
            // A non-critical Error too, or it reached serve()'s outer catch: a reconnect that reran every owner's
            // takeover, and failed this one again, five times a second. A critical one still ends the pump (run()).
            Exceptions.rethrowIfCriticalError(e);
            takenOver.remove(owner);
            stalls.failed(owner, "threw during its takeover", e);
            return false;
        } catch (SQLException e) {
            if (!connection.isValid(CONNECTION_VALIDITY_TIMEOUT_SECONDS)) {
                throw e;
            }
            takenOver.remove(owner);
            stalls.failed(owner, "failed its takeover on a healthy connection", e);
            return false;
        }
    }

    /**
     * Issue this pass's ordered-lane reads once per QUEUE, rather than once per shard. Three
     * statements per queue per pass instead of three per shard — see {@link BatchReadableOwner} for
     * why that decides the affordable routing space, and for why it is an idle-cost mechanism.
     * <p>
     * <b>Grouped by queue id, not issued on this pump's storage handle.</b> A pump serves every queue
     * in the process and its handle is bound to queue 0 for opening connections; binding that in a
     * batched statement queries a queue that does not exist and returns nothing, silently.
     * <p>
     * <b>Grouped by metrics too.</b> A second {@code consume()} on the same queue in one process (warned about, not
     * refused) gives its owners their own {@link ShardOwnerMetrics} on the same runtime. Grouped by queue id alone, the
     * subscriptions shared one statement, and its count and its failures went to whichever subscription's owner came
     * first. Each subscription now gets its own statement, counted on its own metrics.
     * <p>
     * <b>Failure is not failure of the pass.</b> Owners are left without a batch and read for
     * themselves, exactly as before this existed. The batched read is tried again on the next pass, so a failure is
     * counted in {@link ShardOwnerMetrics#batchedReadFailures} and logged through {@link PumpFailures}: a WARN with the
     * stack trace at most once a minute per subscription (one per queue unless a queue has several), naming the queue
     * and the batch's shards, DEBUG otherwise.
     */
    private void batchRead(Connection connection, List<LeasedOwner> attentive) {
        var batches = new LinkedHashMap<PumpFailures.BatchedRead, List<BatchReadableOwner>>();
        for (var owner : attentive) {
            if (owner instanceof BatchReadableOwner readable) {
                batches.computeIfAbsent(new PumpFailures.BatchedRead(readable.queueId(), readable.ownerMetrics()),
                                        ignored -> new ArrayList<>())
                       .add(readable);
            }
        }
        for (var entry : batches.entrySet()) {
            if (entry.getValue().size() < 2) {
                // One shard is not worth a wider statement, and zero is not worth one at all.
                continue;
            }
            var batch = entry.getKey();
            try {
                batchReadQueue(connection, batch, entry.getValue());
                pumpFailures.batchedReadSucceeded(batch, entry.getValue());
            } catch (SQLException | RuntimeException | Error e) {
                // A non-critical Error too: the batched read calls owner code (batchReadCursor, batchSweepDue,
                // applyBatchRead), and an AssertionError from it reached serve()'s outer catch, which ended the pass
                // before any owner was pumped and reconnected, five times a second, for as long as it persisted. A
                // critical one still ends the pump (run()), as from the pumpOnce and takeover guards.
                //
                // Counted and rate-limited (PumpFailures): it is tried again on every pass, and a stack trace per pass
                // was a log flood beside the throttled pumpOnce ERROR. On the batch's own metrics: grouped by them.
                Exceptions.rethrowIfCriticalError(e);
                pumpFailures.batchedReadFailed(batch, entry.getValue(), e);
            }
        }
    }

    private void batchReadQueue(Connection connection, PumpFailures.BatchedRead batch, List<BatchReadableOwner> batchable)
            throws SQLException {
        var queueId  = batch.queueId();
        var shards   = new int[batchable.size()];
        var cursors  = new long[batchable.size()];
        var sweeping = new ArrayList<BatchReadableOwner>();
        for (var index = 0; index < batchable.size(); index++) {
            var owner = batchable.get(index);
            shards[index] = owner.shard();
            cursors[index] = owner.batchReadCursor();
            if (owner.batchSweepDue()) {
                sweeping.add(owner);
            }
        }
        // A FULL batch per shard, never a share of one: the batched read must return exactly what the
        // per-shard read it replaces would have.
        var cursorRows = storage.readOrderedFromCursors(connection, queueId, shards, cursors,
                                                        settings.readBatchSize());
        var sweptShards = new int[sweeping.size()];
        for (var index = 0; index < sweeping.size(); index++) {
            sweptShards[index] = sweeping.get(index).shard();
        }
        var sweptRows = storage.sweepOrderedFromHeads(connection, queueId, sweptShards,
                                                      settings.readBatchSize());
        var nextVisible = storage.millisUntilNextVisibleByShard(connection, queueId,
                                                                ShardOwnedSchema.ORDERED_TABLE, sweptShards);
        // On the OWNERS' metrics, not this pump's: the runtime builds its own counters, so counting
        // here would be invisible to whoever calls queue.metrics(). The batch is grouped by those
        // metrics (batchRead), so every owner in it shares them.
        batch.metrics().orderedReadStatements.add(sweptShards.length > 0 ? 3 : 1);

        var isSweeping = Collections.newSetFromMap(new IdentityHashMap<BatchReadableOwner, Boolean>());
        isSweeping.addAll(sweeping);
        for (var owner : batchable) {
            var swept = isSweeping.contains(owner)
                        ? sweptRows.getOrDefault(owner.shard(), List.of())
                        : null;
            var until = nextVisible.containsKey(owner.shard())
                        ? OptionalLong.of(nextVisible.get(owner.shard()))
                        : OptionalLong.empty();
            owner.applyBatchRead(cursorRows.getOrDefault(owner.shard(), List.of()), swept, until);
        }
    }

    /**
     * How long to park: until the backstop, shortened by the soonest deadline any of this
     * pump's owners has — a retry falling due on one shard must not be delayed by the others being
     * quiet. A failed takeover's retry is one of those deadlines, and the only one an owner awaiting its takeover has; a
     * failed delivery pass's retry is the only one an owner waiting to be pumped again has.
     *
     * @param takenOver            the owners whose takeover has completed on this connection; only their own
     *                             {@link LeasedOwner#parkDeadlineMillis()} counts
     * @param takeoverRetryAtNanos when each owner whose takeover failed may retry it ({@link System#nanoTime()})
     * @param pumpRetryAtNanos     when each owner whose delivery pass failed may be pumped again ({@link System#nanoTime()});
     *                             their own {@link LeasedOwner#parkDeadlineMillis()} does not count until then
     * @return the wait in milliseconds, never below 1
     */
    private long parkMillis(Set<LeasedOwner> takenOver, Map<LeasedOwner, Long> takeoverRetryAtNanos,
                            Map<LeasedOwner, Long> pumpRetryAtNanos) {
        // The ceiling, not the backstop. `pollBackstop` used to floor every park at 500ms, which put
        // a fixed query rate under a process however quiet it was; the owners' own deadlines already
        // account for their sweeps, retries and flushes, and a notification wakes this pump
        // immediately regardless of how long it intended to sleep.
        var wait = Math.max(settings.pollBackstopMillis(), settings.maxSweepIntervalNanos() / 1_000_000L);
        for (var owner : owners) {
            // An owner awaiting its takeover is not pumped, so its own deadlines mean nothing yet - and it has never
            // swept, so its sweep always reads as overdue and its deadline as 0. Folding it in parked the pump 1 ms
            // at a time, about a thousand passes a second, for as long as the takeover kept failing.
            if (!takenOver.contains(owner)) {
                continue;
            }
            // Likewise an owner waiting out a failed pass: it is not pumped before its retry, and its own deadline is
            // typically 0 by then (the acknowledgement that failed is still pending), which would park 1 ms at a time.
            if (pumpRetryAtNanos.containsKey(owner)) {
                continue;
            }
            var deadline = owner.parkDeadlineMillis();
            if (deadline < wait) {
                wait = Math.max(1L, deadline);
            }
        }
        var now = System.nanoTime();
        wait = untilSoonestRetry(takeoverRetryAtNanos, now, wait);
        return untilSoonestRetry(pumpRetryAtNanos, now, wait);
    }

    /**
     * Park until a hint arrives or {@code wait} ({@link #parkMillis}) runs out.
     */
    private void park(long wait) throws InterruptedException {
        // The honoured/backstop counters are kept by the owners now, in needsAttention, where it is
        // known which shard a wake-up was actually for. Counting them here would count one wake-up
        // per pump regardless of how many shards it served.
        if (!wakeup.await(wait)) {
            metrics.backstopPolls.increment();
        }
    }

    /**
     * {@code wait} shortened to the soonest of {@code retryAtNanos}, in milliseconds and never below 1.
     */
    private static long untilSoonestRetry(Map<LeasedOwner, Long> retryAtNanos, long nowNanos, long wait) {
        for (var retryAt : retryAtNanos.values()) {
            // Rounded up, so the pump does not wake just short of the retry and park again
            var deadline = Math.max(0L, (retryAt - nowNanos + 999_999L) / 1_000_000L);
            if (deadline < wait) {
                wait = Math.max(1L, deadline);
            }
        }
        return wait;
    }
}
