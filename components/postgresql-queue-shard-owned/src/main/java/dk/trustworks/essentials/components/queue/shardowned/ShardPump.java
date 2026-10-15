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
     * How long a shard whose takeover failed waits before the pump retries it. Paced like a failing statement, and for
     * the same reason (a permanent failure would otherwise spin), but per shard: the pump's other shards are not paced.
     */
    static final         long   TAKEOVER_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(200L);

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
     * Makes {@link #add} and the pump's death agree on which owners are counted in
     * {@link ShardOwnerMetrics#shardsOnDeadPumps}, and serialises {@link #forgetAfterExit()}. Guards {@link #countedDead}
     * and {@link #forgotten}; {@link #dead} is written under it.
     */
    private final Object                            deathLock   = new Object();
    /**
     * The owners counted in {@link ShardOwnerMetrics#shardsOnDeadPumps} because they sit on this pump and it has died.
     */
    private final List<LeasedOwner>                 countedDead = new ArrayList<>();
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
     * {@link TakeoverStalls#NO_STALL_ERROR_LOGGED} before the first. Pump thread only.
     */
    private          long                           nextLoopFailureErrorNanos = TakeoverStalls.NO_STALL_ERROR_LOGGED;
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
        synchronized (deathLock) {
            owners.add(owner);
            // ShardRuntime routes new shards to live pumps, so this is reached only when none is left, or when the pump
            // died between being picked and being handed the shard. Either way the shard is leased and unserved.
            if (dead && !forgotten) {
                countOnDeadPump(owner);
            }
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
     * serves nothing, and {@link ShardRuntime} routes new shards to the live ones.
     */
    boolean isDead() {
        return dead;
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
     * exception or non-critical {@code Error} from one owner's {@code pumpOnce} or {@code onTakeover}, is that owner's
     * alone: the per-owner guards count it ({@link PumpFailures}, {@link TakeoverStalls}) and the other owners keep being
     * served. A lost connection, and anything thrown outside those guards (from {@code needsAttention}, {@code leaseHeld}
     * or {@code deliveryPermitted}, the pump's own bookkeeping, an observer), end the pass: {@link #serve()} reconnects
     * 200 ms later and runs every owner's takeover again, which the owners make safe (it already runs on every reconnect).
     * Only a critical error ({@code VirtualMachineError},
     * {@code LinkageError}: see {@link Exceptions#isCriticalError}) ends the thread, because the JVM is then in no state to
     * trust a retry - nor to hand the pump's shards to another pump, which the same cause could kill in turn.
     * <p>
     * <b>Stopping and dying are told apart by {@code running}, not by how {@link #serve()} ended.</b> On an orderly stop
     * the pump serves none of its shards any more, so none of them is awaiting a takeover here, and its stalls are
     * forgotten. A pump that ends while {@code running} is still set has died. Nothing restarts it and the heartbeat keeps
     * renewing every lease it held, so its shards stay leased to this instance and are served by nobody. So the pump
     * marks itself dead: {@link ShardRuntime} then routes newly acquired shards to its live pumps, and every shard on this
     * one is counted in {@link ShardOwnerMetrics#shardsOnDeadPumps} on its queue's metrics, a shard handed to it later
     * included ({@link #add}). That count is readable only through {@link ShardOwnedQueue#metrics()}: no
     * {@code QueueObserver} callback reports it, so through {@code MessageQueue} (the Spring starter, the
     * {@code DurableQueues} adapter) the ERROR below is the only signal. Its stalls stay counted in
     * {@link ShardOwnerMetrics#shardsAwaitingTakeover}: forgetting
     * them zeroed the one metric for a leased-but-unserved shard exactly when that had got worse. The death is logged at
     * ERROR here because {@link ShardRuntime} submits the pump to an executor whose {@code Future} nobody reads, so the
     * throwable would otherwise vanish; it is still rethrown. Both counts are dropped once the runtime stops
     * ({@link #forgetAfterExit()}), because the stop ends the leases the dead pump was keeping. The cure is restarting
     * the {@code ShardRuntime} (or the application), which the ERROR says.
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
            if (running.get()) {
                log.error("{} died while its runtime is still running. The {} shard(s) it serves stay leased to this instance "
                                  + "and deliver nothing: the heartbeat keeps renewing their leases, so no other instance takes "
                                  + "them over, and nothing restarts the pump. Shards acquired from now on go to the runtime's "
                                  + "live pumps, to this one only if none is left. Restart the ShardRuntime (or the application). "
                                  + "Until that runtime stops, these shards are counted in shardsOnDeadPumps, and those whose "
                                  + "takeover was failing in shardsAwaitingTakeover",
                          name, owners.size(), died);
                markDead();
            } else {
                stalls.forgetAll();
            }
            exited = true;
            if (dead && !running.get()) {
                // The runtime stopped while this pump was dying, possibly after its stop had looked for exited pumps.
                forgetAfterExit();
            }
        }
    }

    private void markDead() {
        synchronized (deathLock) {
            dead = true;
            for (var owner : owners) {
                countOnDeadPump(owner);
            }
        }
    }

    /**
     * Called under {@link #deathLock}.
     */
    private void countOnDeadPump(LeasedOwner owner) {
        owner.ownerMetrics().shardsOnDeadPumps.incrementAndGet();
        countedDead.add(owner);
    }

    /**
     * The runtime has stopped and this pump's thread has ended, so none of its shards is served by it any more: forget its
     * stalls, and stop counting its shards in {@link ShardOwnerMetrics#shardsOnDeadPumps}. A no-op for a pump that stopped
     * in order, which forgot its stalls on its way out and counted nothing. A pump that had died while the runtime ran
     * kept both counted ({@link #run()}); without this they stayed counted, in metrics that outlive the runtime, for the
     * rest of the process, so the restart its own ERROR recommends never cleared the count and the observer was never
     * told the stall ended.
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

                while (running.get()) {
                    var delivered       = 0;
                    var statementFailed = false;
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
                        if (!owner.leaseHeld()) {
                            continue;
                        }
                        // BEFORE needsAttention, which consumes the shard's wake-up flag. Skipping
                        // after it would swallow the signal and leave the shard waiting for the
                        // backstop once delivery is permitted again.
                        if (!owner.deliveryPermitted()) {
                            continue;
                        }
                        // The wake-up was for one of this pump's shards, not all of them.
                        if (!owner.needsAttention()) {
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
                            // Counted and rate-limited (PumpFailures): it is retried on every pass with the
                            // lease kept, and a stack trace per pass was a log flood with no counter behind it.
                            Exceptions.rethrowIfCriticalError(e);
                            pumpFailures.failed(owner, "threw during delivery", e);
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
                            if (!connection.isValid(1)) {
                                throw e;
                            }
                            statementFailed = true;
                            pumpFailures.failed(owner, "issued a statement that failed on a healthy connection", e);
                        }
                    }
                    owners.removeIf(owner -> {
                        if (owner.leaseHeld()) {
                            return false;
                        }
                        takenOver.remove(owner);
                        takeoverRetryAtNanos.remove(owner);
                        stalls.forget(owner);
                        pumpFailures.forget(owner);
                        return true;
                    });
                    if (loopFailures > 0) {
                        log.info("{}: a pass completed again after {} failed pass(es)", name, loopFailures);
                        loopFailures = 0;
                    }

                    if (statementFailed) {
                        // Paced like the reconnect path, and for the same reason. A statement that
                        // fails permanently — a blocked delete, a full disk, a broken constraint —
                        // otherwise spins this loop as fast as the database can refuse it: measured
                        // at 8 405 stack traces in one test run. Not reconnecting was right; not
                        // pacing was not. A failing takeover is paced per owner instead (takeoverRetryAtNanos).
                        TimeUnit.MILLISECONDS.sleep(200L);
                    } else if (delivered == 0) {
                        park(takenOver, takeoverRetryAtNanos);
                    }
                }
                if (flushOnExit.get()) {
                    for (var owner : owners) {
                        owner.flushOnStop(connection);
                    }
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (SQLException e) {
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
                    TimeUnit.MILLISECONDS.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException | Error e) {
                // Thrown outside every per-owner guard: from needsAttention, leaseHeld or deliveryPermitted, from the
                // pump's own bookkeeping, from an observer, or from flushOnStop. Ending the thread here used to leave
                // every shard on this pump leased and served by nobody, for something a retry may well cure; so it is
                // treated like a lost connection. A critical error still ends the pump (run() says what follows).
                Exceptions.rethrowIfCriticalError(e);
                passFailed(e);
                if (!running.get()) {
                    return;
                }
                try {
                    // Paced like the reconnect, or a failure that does not clear spins the loop as fast as it can throw.
                    TimeUnit.MILLISECONDS.sleep(200L);
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
        if (nextLoopFailureErrorNanos == TakeoverStalls.NO_STALL_ERROR_LOGGED || now - nextLoopFailureErrorNanos >= 0) {
            nextLoopFailureErrorNanos = now + TakeoverStalls.ERROR_EVERY_NANOS;
            log.error("{}: a pass ended on an exception thrown outside its per-shard guards ({} failed pass(es) in a row). "
                              + "The pump reconnects in 200 ms and runs the takeover of each of its {} shard(s) again; while "
                              + "the cause persists those shards deliver little or nothing, and QueueHealth.unownedShards does "
                              + "not count them because they stay leased. Counted in pumpLoopFailures; logged at ERROR at most "
                              + "once a minute per pump, every failure at DEBUG",
                      name, loopFailures, owners.size(), e);
        } else {
            log.debug("{}: a pass ended on an exception thrown outside its per-shard guards ({} failed pass(es) in a row): {}",
                      name, loopFailures, String.valueOf(e));
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
     * owner deadline. Pacing the pass instead, as a failing {@code pumpOnce} statement does, held every other shard on
     * this pump to one pass per 200 ms for as long as the takeover failed. The
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
     * most once a minute per queue, naming one shard and counting the others, and every failure at DEBUG.
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
            if (!connection.isValid(1)) {
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
     * <b>Failure is not failure of the pass.</b> Owners are left without a batch and read for
     * themselves, exactly as before this existed. The batched read is tried again on the next pass, so a failure is
     * counted in {@link ShardOwnerMetrics#batchedReadFailures} and logged through {@link PumpFailures}: a WARN with the
     * stack trace at most once a minute per queue, DEBUG otherwise.
     */
    private void batchRead(Connection connection, List<LeasedOwner> attentive) {
        var byQueue = new HashMap<Short, List<BatchReadableOwner>>();
        for (var owner : attentive) {
            if (owner instanceof BatchReadableOwner readable) {
                byQueue.computeIfAbsent(readable.queueId(), ignored -> new ArrayList<>()).add(readable);
            }
        }
        for (var entry : byQueue.entrySet()) {
            if (entry.getValue().size() < 2) {
                // One shard is not worth a wider statement, and zero is not worth one at all.
                continue;
            }
            try {
                batchReadQueue(connection, entry.getKey(), entry.getValue());
                pumpFailures.batchedReadSucceeded(entry.getKey());
            } catch (SQLException | RuntimeException e) {
                // Counted and rate-limited (PumpFailures): it is tried again on every pass, and a stack trace per pass
                // was a log flood beside the throttled pumpOnce ERROR. On the queue's metrics: every owner in the batch
                // shares them.
                pumpFailures.batchedReadFailed(entry.getKey(), entry.getValue().getFirst().ownerMetrics(), e);
            }
        }
    }

    private void batchReadQueue(Connection connection, short queueId, List<BatchReadableOwner> batchable)
            throws SQLException {
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
        // here would be invisible to whoever calls queue.metrics(). Every owner in this batch shares
        // one queue and therefore one metrics object.
        batchable.get(0).ownerMetrics().orderedReadStatements.add(sweptShards.length > 0 ? 3 : 1);

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
     * Park until a hint arrives or the backstop fires, shortened by the soonest deadline any of this
     * pump's owners has — a retry falling due on one shard must not be delayed by the others being
     * quiet. A failed takeover's retry is one of those deadlines, and the only one an owner awaiting its takeover has.
     *
     * @param takenOver            the owners whose takeover has completed on this connection; only their own
     *                             {@link LeasedOwner#parkDeadlineMillis()} counts
     * @param takeoverRetryAtNanos when each owner whose takeover failed may retry it ({@link System#nanoTime()})
     */
    private void park(Set<LeasedOwner> takenOver, Map<LeasedOwner, Long> takeoverRetryAtNanos) throws InterruptedException {
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
            var deadline = owner.parkDeadlineMillis();
            if (deadline < wait) {
                wait = Math.max(1L, deadline);
            }
        }
        var now = System.nanoTime();
        for (var retryAt : takeoverRetryAtNanos.values()) {
            // Rounded up, so the pump does not wake just short of the retry and park again
            var deadline = Math.max(0L, (retryAt - now + 999_999L) / 1_000_000L);
            if (deadline < wait) {
                wait = Math.max(1L, deadline);
            }
        }
        // The honoured/backstop counters are kept by the owners now, in needsAttention, where it is
        // known which shard a wake-up was actually for. Counting them here would count one wake-up
        // per pump regardless of how many shards it served.
        if (!wakeup.await(wait)) {
            metrics.backstopPolls.increment();
        }
    }
}
