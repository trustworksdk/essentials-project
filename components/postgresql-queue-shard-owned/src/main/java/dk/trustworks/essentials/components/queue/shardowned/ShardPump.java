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
 * marked lost, and the loop exits.
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
        owners.add(requireNonNull(owner, "No owner provided"));
        // So a pump parked on its backstop starts serving the new shard now rather than up to half a
        // second later.
        wakeup.signal();
    }

    public int shardsServed() {
        return owners.size();
    }

    @Override
    public void run() {
        try {
            serve();
        } finally {
            // This pump serves none of its shards any more, so none of them is awaiting a takeover here.
            stalls.forgetAll();
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
                        } catch (RuntimeException e) {
                            // One thread now serves several shards, so an unchecked exception from
                            // one owner used to end the thread and stall every shard beside it —
                            // silently, because the lease went on being renewed. Whatever is wrong
                            // with this owner is its own problem; the rest keep running.
                            log.error("{}: shard {} threw; continuing with the other shards",
                                      name, owner.shard(), e);
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
                            log.error("{}: shard {} issued a statement that failed on a healthy "
                                              + "connection; continuing with the other shards", name, owner.shard(), e);
                        }
                    }
                    owners.removeIf(owner -> {
                        if (owner.leaseHeld()) {
                            return false;
                        }
                        takenOver.remove(owner);
                        takeoverRetryAtNanos.remove(owner);
                        stalls.forget(owner);
                        return true;
                    });

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
            }
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
     * {@link ShardOwnerMetrics#shardsAwaitingTakeover} in {@link ShardOwnedQueue#metrics()} next to it (a
     * {@code PostgresqlMessageQueue} keeps those metrics private, so through {@code MessageQueue} only the ERROR
     * log shows it):
     * {@link TakeoverStalls} counts the shard there from its first failure until the takeover completes, counts
     * every failure in {@link ShardOwnerMetrics#takeoverFailures}, and logs the first failure at ERROR, repeats
     * at DEBUG and at ERROR once a minute.
     *
     * @return whether the takeover completed
     * @throws SQLException only when the connection itself is broken - the reconnect loop's case
     */
    private boolean takeOver(Connection connection, LeasedOwner owner, Set<LeasedOwner> takenOver) throws SQLException {
        try {
            owner.onTakeover(connection);
            stalls.completed(owner);
            return true;
        } catch (RuntimeException e) {
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
     * themselves, exactly as before this existed.
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
            } catch (SQLException | RuntimeException e) {
                log.warn("{}: batched read for queue {} failed; those shards read for themselves this "
                                 + "pass", name, entry.getKey(), e);
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
