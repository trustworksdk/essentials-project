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

import dk.trustworks.essentials.shared.Lifecycle;
import org.slf4j.*;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The threads and connections every queue in a process shares.
 * <p>
 * <b>Why this exists.</b> Pumps, the wake-up listener and the heartbeat were all scoped to a single
 * {@link ShardOwnedQueue}, so database contact scaled with the number of queues: {@code (pumps x lanes +
 * lanes) x queues} held connections, which is 150 at twenty-five queues and about 1 800 at three
 * hundred. None of that is required by shard ownership. A shard's identity is a row in the lease
 * table, its state is a few fields in memory, and {@code queue_id} is a bind parameter in every
 * statement rather than a property of a connection — so one pump can serve shards belonging to any
 * queue and either lane, and one listener can serve all of them.
 * <p>
 * Scoping the pumps to a queue was the same mistake as scoping them to a shard, one level up. This
 * makes the cost a property of the process:
 * <pre>
 *   connections = pumpThreads + 1        (regardless of how many queues)
 *   threads     = pumpThreads + 2        (pumps, listener, heartbeat)
 * </pre>
 * <p>
 * <b>The wake-up channel was already global.</b> {@link ShardWakeupListener} listens on one channel and
 * the payload carries {@code queueId:lane:shard}, so a single listener demultiplexes to the right
 * shard of the right lane of the right queue. Nothing had to change for that — it was always able to
 * serve every queue, and was simply being constructed once per queue.
 */
public final class ShardRuntime implements Lifecycle, AutoCloseable {
    private static final Duration PUMP_TERMINATION_TIMEOUT = Duration.ofSeconds(30);
    /** Set by {@link #shutdownStarting(Supplier)}; {@code null} outside an application shutdown */
    private volatile Supplier<Duration> remainingShutdownTime;
    private static final Logger log = LoggerFactory.getLogger(ShardRuntime.class);

    /**
     * One shared runtime per {@link DataSource}, reference counted.
     * <p>
     * This exists because the alternative default was dangerous. A queue built without a runtime used
     * to stand one up for itself, which is correct for one queue and a self-inflicted denial of
     * service for a hundred: a hundred runtimes, each with its own pumps and listener, exhausted a
     * 500-connection pool on the first attempt. A default that degrades as the caller adds queues is
     * the wrong default, not a caveat to document — so doing nothing now gets the shared runtime, and
     * a private one has to be asked for.
     * <p>
     * <b>Keyed by identity, not by equality.</b> A {@code DataSource} is a resource rather than a
     * value, and the wrappers a framework puts around one — Spring's
     * {@code LazyConnectionDataSourceProxy} and {@code TransactionAwareDataSourceProxy} among them —
     * are free to define equality however they like. Two distinct pools comparing equal would share
     * one runtime and one set of connections drawn from the wrong pool; identity cannot do that.
     */
    private static final Map<DataSource, Shared> SHARED = new IdentityHashMap<>();

    /**
     * The queue id a pump's storage handle is bound to: none. A pump is process-wide, so a statement
     * it issues on this handle would query queue 0 and silently return nothing — see the pump's own
     * {@code queueId()} for how per-queue work is bound instead.
     * <p>
     * A named constant rather than a {@code (short) 0} at the call site: a constant expression narrows
     * implicitly in an assignment, so this is the same value with no cast for a static analyser to
     * read as a truncation.
     */
    private static final short NO_QUEUE_ID = 0;

    private record Shared(ShardRuntime runtime, int borrowers) {
    }

    static synchronized ShardRuntime acquireShared(DataSource dataSource, ShardOwnerSettings settings) {
        var existing = SHARED.get(dataSource);
        if (existing != null) {
            // The first borrower's runtime-scoped values are the ones in force, because the pumps were
            // started from them and are already running. Saying so is the point: a second queue asking
            // for four pump threads and silently getting two is the kind of thing that is only
            // discovered while reading a heap dump. Only those values are compared: the rest of a
            // borrower's settings (stuckHandlerTimeout, the documented way to bound one queue's
            // handlers, among them) are its own queue's, and warning that they were ignored would be
            // false.
            var inForce   = existing.runtime().settings;
            var requested = settings.runtimeScoped();
            if (!inForce.equals(requested)) {
                log.warn("A shared ShardRuntime for this DataSource already exists and was built with a different "
                                 + "pumpThreads, readBatchSize, pollBackstop or maxSweepInterval; its pumps keep the values "
                                 + "they were built with, so the values passed here do not apply to them. The queue's other "
                                 + "settings are its own and apply as passed. Construct the ShardRuntime yourself and pass it "
                                 + "to every ShardOwnedQueue if the process needs a specific runtime configuration. "
                                 + "In force: {}. Passed here: {}",
                         inForce, requested);
            }
            SHARED.put(dataSource, new Shared(existing.runtime(), existing.borrowers() + 1));
            return existing.runtime();
        }
        var runtime = new ShardRuntime(dataSource, settings);
        SHARED.put(dataSource, new Shared(runtime, 1));
        return runtime;
    }

    /**
     * Give a shared runtime back. The last borrower out closes it.
     */
    static synchronized void releaseShared(DataSource dataSource) {
        var existing = SHARED.get(dataSource);
        if (existing == null) {
            return;
        }
        if (existing.borrowers() > 1) {
            SHARED.put(dataSource, new Shared(existing.runtime(), existing.borrowers() - 1));
            return;
        }
        SHARED.remove(dataSource);
        existing.runtime().stop();
    }

    private final DataSource                       dataSource;
    /**
     * Only the values of the settings this runtime was built with that it and its pumps read: what
     * {@link #acquireShared} compares a later borrower's settings against. Every other value of a borrowing queue's
     * settings is read only by that borrowing queue.
     */
    private final ShardOwnerSettings.RuntimeScoped settings;
    private final ShardOwnerMetrics                metrics;
    private final ConnectionPoolMetadata           pool;
    private final AtomicBoolean                    running     = new AtomicBoolean();
    /**
     * The {@code running} flag handed to this run's pumps: a new one per {@link #start()}, cleared by {@link #stop()}. Not
     * {@link #running} itself, which a restart sets again: a pump that outlived the previous stop's wait would then read it
     * as set when it finally left its loop, and take its orderly exit for a death.
     */
    private       AtomicBoolean                    pumpsRunning;
    private final AtomicBoolean                    flushOnExit = new AtomicBoolean(true);
    private final List<ShardPump>                  pumps       = new ArrayList<>();
    private final Map<String, ShardWakeup>         wakeups     = new ConcurrentHashMap<>();
    /**
     * Recreated by every {@link #start()}, because an {@code ExecutorService} cannot be restarted
     * once shut down. This is what makes the runtime genuinely restartable rather than merely
     * stoppable — the same distinction {@code ShardOwnedQueue.stop()} had to make.
     */
    private       ExecutorService                  pumpExecutor;
    private       ExecutorService                  handlerExecutor;
    private       ScheduledExecutorService         heartbeat;
    private       ShardWakeupListener              listener;
    private       PoolBudget.Reservation           poolReservation;

    public ShardRuntime(DataSource dataSource, ShardOwnerSettings settings) {
        this(dataSource, settings, new ShardOwnerMetrics());
    }

    public ShardRuntime(DataSource dataSource, ShardOwnerSettings settings, ShardOwnerMetrics metrics) {
        this(dataSource, settings, metrics, ConnectionPoolMetadata.unknown());
    }

    /**
     * A runtime that can check its pool before it takes from it.
     * <p>
     * The pumps and the listener hold {@code pumpThreads + 1} connections for as long as the runtime
     * runs. Given the pool's size, a pool no larger than that is refused here — at construction,
     * before any connection is taken — and a pool more than half consumed by it is logged as a
     * warning. Without it, a pool too small does not fail: pumps retry forever while their shards stop
     * delivering. The Spring Boot starter passes metadata for any pool Spring Boot can read.
     *
     * @param pool what can be learned about the pool behind {@code dataSource};
     *             {@link ConnectionPoolMetadata#unknown()} skips the check
     * @throws IllegalStateException if the pool's maximum size does not exceed
     *                               {@code settings.pumpThreads() + 1}
     */
    public ShardRuntime(DataSource dataSource, ShardOwnerSettings settings, ShardOwnerMetrics metrics,
                        ConnectionPoolMetadata pool) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.settings = requireNonNull(settings, "No settings provided").runtimeScoped();
        this.metrics = requireNonNull(metrics, "No metrics provided");
        this.pool = requireNonNull(pool, "No pool metadata provided");
        start();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Idempotent, and restartable after {@link #stop()}: the executors and the listener are built
     * here rather than in the constructor, because none of them can be revived once shut down.
     */
    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        remainingShutdownTime = null;
        try {
            // Before the first connection is taken: a pool the pumps would exhaust does not fail, it
            // retries forever — see PoolBudget.
            this.poolReservation = PoolBudget.reserve(dataSource, pool, settings.pumpThreads());
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        pumps.clear();
        var pumpCount = Math.max(1, settings.pumpThreads());
        this.pumpExecutor = Executors.newFixedThreadPool(pumpCount, named("shard-queue-pump"));
        // Handlers are user code that mostly waits. One virtual thread per message in flight, shared
        // by every queue, rather than a platform pool sized per shard.
        this.handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(named("shard-queue-heartbeat"));

        // A storage handle bound to no queue in particular: the pumps only use it to open connections,
        // and every statement an owner issues carries its own queue id.
        var connections = new ShardOwnedStorage(dataSource, NO_QUEUE_ID);
        this.pumpsRunning = new AtomicBoolean(true);
        for (var index = 0; index < pumpCount; index++) {
            var pump = new ShardPump(connections, settings, metrics, pumpsRunning, flushOnExit, "pump-" + index,
                                     () -> PoolBudget.exhaustion(dataSource, pool));
            pumps.add(pump);
            pumpExecutor.submit(pump);
        }
        this.listener = new ShardWakeupListener(dataSource, wakeups);
        // Started here, after it is fully constructed, rather than from inside its own constructor.
        this.listener.start();
    }

    @Override
    public boolean isStarted() {
        return running.get();
    }

    private static ThreadFactory named(String prefix) {
        return runnable -> {
            var thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Where one shard of one lane of one queue is served: the pump chosen for it, and the shard's wake-up, which cascades
     * to that same pump. An owner is built with {@link #wakeup()} and handed to {@link #register(LeasedOwner)}, both from
     * one placement.
     * <p>
     * <b>One placement, not two lookups.</b> Choosing the pump once for the wake-up and again for the registration let the
     * two disagree: a pump that died between the lookups left the owner on a live pump while its wake-up still cascaded to
     * the dead one, so notifications for the shard woke nobody and it was served only when its new pump's park deadline
     * or backstop fired, until the shard was acquired again. Placed once, the owner and its wake-up always share a pump.
     * Should that pump die between {@link ShardRuntime#place} and {@link #register(LeasedOwner)}, the owner is ended on
     * the dead pump like the owners it held when it died, counted in {@link ShardOwnerMetrics#shardsOnDeadPumps}, and its
     * queue hands the lease back ({@link ShardPump#add(LeasedOwner)}).
     *
     * @param key           the shard's key in the runtime's wake-ups, as the notification payload names it
     * @param createdWakeup whether this placement put {@link #wakeup()} there, rather than reusing the one it found
     *                      ({@link ShardRuntime#withdraw})
     */
    record Placement(ShardPump pump, ShardWakeup wakeup, String key, boolean createdWakeup) {
        void register(LeasedOwner owner) {
            pump.add(owner);
        }
    }

    /**
     * Choose the pump that will serve one shard of one lane of one queue ({@link #pumpFor}) and the shard's wake-up,
     * cascading to that pump. The wake-up is keyed the same way the notification payload is, so the listener can route
     * without knowing anything about queues.
     * <p>
     * The wake-up is kept per shard across ownerships, and replaced when it no longer cascades to the chosen pump: once a
     * pump has died ({@link ShardPump#run()}) its shards are routed elsewhere, and a wake-up still pointing at it would
     * leave a re-acquired shard served only when its new pump's backstop fires.
     */
    Placement place(short queueId, String lane, int shard) {
        var pump    = pumpFor(queueId, lane, shard);
        var key     = ShardWakeupListener.key(queueId, lane, shard);
        var created = new AtomicBoolean();
        var wakeup = wakeups.compute(key, (ignored, existing) -> {
            if (existing != null && existing.cascadesTo(pump.wakeup())) {
                return existing;
            }
            created.set(true);
            return new ShardWakeup(pump.wakeup());
        });
        return new Placement(pump, wakeup, key, created.get());
    }

    /**
     * Undo a {@link #place placement} whose owner was never registered, because building it failed: forget the
     * wake-up the placement put in this runtime, so the shard leaves no entry behind that nothing forgets until the runtime
     * stops. A wake-up the placement reused is left: it was there before, kept per shard across ownerships, and the
     * placement added nothing. Removed only while it is still the placement's own, so a wake-up a later placement put
     * there in its stead is left too.
     */
    void withdraw(Placement placement) {
        if (placement.createdWakeup()) {
            wakeups.remove(placement.key(), placement.wakeup());
        }
    }

    /**
     * Which pump serves a shard. Spread over the queue and lane as well as the shard, so a process
     * with many queues of few shards still uses every pump.
     * <p>
     * <b>Never a dead pump while a live one is left.</b> A pump that died on a critical error ({@link ShardPump#run()})
     * serves nothing, and routing over every pump kept handing it newly acquired shards after every rebalance. A shard
     * whose pump is alive keeps it; one whose pump is dead is spread over the live ones. With none alive it goes to its
     * dead pump, which ends it and counts it in {@link ShardOwnerMetrics#shardsOnDeadPumps} until its queue hands the lease
     * back; a queue acquires nothing while {@link #hasLivePump()} is false.
     */
    ShardPump pumpFor(short queueId, String lane, int shard) {
        var spread = Objects.hash(queueId, lane, shard);
        var pump   = pumps.get(Math.floorMod(spread, pumps.size()));
        if (!pump.isDead()) {
            return pump;
        }
        var live = pumps.stream().filter(candidate -> !candidate.isDead()).toList();
        return live.isEmpty() ? pump : live.get(Math.floorMod(spread, live.size()));
    }

    /**
     * Whether any pump is alive. With none, a queue acquires nothing: whatever it took would land on a dead pump, be ended
     * there and be handed back on the next heartbeat, every heartbeat ({@link ShardPump#run()}).
     */
    boolean hasLivePump() {
        return pumps.stream().anyMatch(pump -> !pump.isDead());
    }

    /**
     * Whether {@code owner} sits on a pump that has died, and its queue has not handed its lease back yet
     * ({@link #handedBack}). The pump ended it when it died ({@link ShardPump#run()}); what is left is the queue's: release
     * the lease, an ordered one once no key is in a handler, or one has been there longer than {@code stuckHandlerTimeout}.
     * <p>
     * Which pump, not what state: whether the owner's lease row still names this instance is the owner's own
     * {@link LeasedOwner#leaseRow()}, which the death moved to {@code ENDED_ROW_HELD}. The queue asks this for two things:
     * to pick the owners its hand-back is for, and to keep a dead pump's owners out of {@code stop()}'s acknowledgement
     * flush, since their state is not trusted after the critical error. Both ask which pump, never the lease row.
     * Asks each pump's {@link ShardPump#awaitsHandBack} alone, not gated on {@link ShardPump#isDead()}, which a dying pump
     * sets only after it has ended every owner on it.
     */
    boolean onDeadPump(LeasedOwner owner) {
        for (var pump : pumps) {
            if (pump.awaitsHandBack(owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code owner}'s queue is done with a unit whose pump had died ({@link #onDeadPump}) - it released the lease row, found
     * the unit taken, or stopped: it is no longer counted in {@link ShardOwnerMetrics#shardsOnDeadPumps}, nor, if its
     * takeover was failing, in {@link ShardOwnerMetrics#shardsAwaitingTakeover}. A no-op for an owner no dead pump counts,
     * so the queue may call it for every owner it drops.
     */
    void handedBack(LeasedOwner owner) {
        for (var pump : pumps) {
            pump.handedBack(owner);
        }
    }

    void forget(short queueId, String lane, int shard) {
        wakeups.remove(ShardWakeupListener.key(queueId, lane, shard));
    }

    HandlerDispatch dispatch(int parallelConsumers) {
        return new HandlerDispatch(handlerExecutor, Math.max(1, parallelConsumers));
    }

    /**
     * Register a queue's heartbeat. One scheduler thread runs every queue's renewals and rebalances.
     */
    ScheduledFuture<?> scheduleHeartbeat(Runnable task, long intervalMillis) {
        return heartbeat.scheduleAtFixedRate(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public long notificationsReceived() {
        return listener.notificationsReceived();
    }

    public long listenerReconnects() {
        return listener.reconnects();
    }

    public int pumpCount() {
        return pumps.size();
    }

    /**
     * How many units the pumps are currently serving, across every queue and lane. Package-private: it is for
     * asserting that a stopped queue's owners have left the pumps, not a figure to operate on.
     */
    int unitsServed() {
        return pumps.stream().mapToInt(ShardPump::shardsServed).sum();
    }

    /**
     * How many shards this runtime holds a wake-up for ({@link #place}), across every queue and lane. Package-private: it
     * is for asserting that a queue left no wake-up behind for a unit it did not keep, not a figure to operate on.
     */
    int wakeupsHeld() {
        return wakeups.size();
    }

    /**
     * The pumps whose owner list holds {@code owner} ({@link ShardPump#serves}): the pumps serving it now, not the one
     * {@link #pumpFor} would route its unit to. Package-private: it is for asserting where an owner landed, and that a
     * stopped queue's owners have left the pumps.
     */
    List<ShardPump> pumpsServing(LeasedOwner owner) {
        return List.copyOf(pumps).stream().filter(pump -> pump.serves(owner)).toList();
    }

    /**
     * Equivalent to {@link #stop()}, so try-with-resources and a container both work.
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * The application is shutting down under a time budget: {@link #stop()} waits for the pumps no longer than what is
     * left of it, instead of up to thirty seconds. Against an unreachable database a pump sits in a pooled connection
     * checkout for the pool's whole timeout, and waiting for it then only delays every component stopped after this one.
     * <p>
     * Takes a plain supplier so the engine does not depend on Spring; the Spring starter bridges it from the application's
     * shutdown signal.
     *
     * @param remainingShutdownTime what is left of the shutdown's time budget, asked for when {@link #stop()} runs
     */
    public void shutdownStarting(Supplier<Duration> remainingShutdownTime) {
        this.remainingShutdownTime = requireNonNull(remainingShutdownTime, "No remainingShutdownTime provided");
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        pumpsRunning.set(false);
        // Signal the PUMPS, not only the shards.
        //
        // A shard's wake-up cascades to its pump, so signalling shards happens to release any pump
        // that serves one. A pump serving none is released by nothing — and that is the common case
        // at shutdown, in a process that configured the engine but never consumed, or whose shards
        // were released first. It then sleeps out its whole park, up to max(pollBackstop,
        // maxSweepInterval), before it notices `running` is false: a thirty-second shutdown, which
        // in a Spring application is thirty seconds added to every restart.
        pumps.forEach(pump -> pump.wakeup().signal());
        wakeups.values().forEach(ShardWakeup::signal);
        listener.stop();
        heartbeat.shutdownNow();
        pumpExecutor.shutdown();
        try {
            var pumpWait = PUMP_TERMINATION_TIMEOUT;
            var shutdownBudget = remainingShutdownTime;
            if (shutdownBudget != null && shutdownBudget.get().compareTo(pumpWait) < 0) {
                pumpWait = shutdownBudget.get();
            }
            if (!pumpExecutor.awaitTermination(pumpWait.toMillis(), TimeUnit.MILLISECONDS)) {
                pumpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pumpExecutor.shutdownNow();
        }
        // A pump that died while the runtime ran kept the shards its queues had not handed back yet counted, in
        // shardsOnDeadPumps and, where the takeover was failing, in shardsAwaitingTakeover (ShardPump.run); none of them is
        // served here once this stop ends, so forget them.
        // Asked of every pump whether or not the wait above timed out or was interrupted: each acts only once its own
        // thread has ended, which is what makes its state safe to read from here, and a dead pump's thread has. Doing this
        // only when every pump ended in time left a dead pump's counts standing for the rest of the process whenever another
        // pump was slow to stop. A pump still in its loop forgets its own stalls when it leaves, its running flag cleared.
        pumps.forEach(ShardPump::forgetAfterExit);
        handlerExecutor.shutdown();
        // Cleared so a restart does not signal wake-ups belonging to owners that no longer exist.
        wakeups.clear();
        PoolBudget.release(poolReservation);
        poolReservation = null;
        log.debug("Shard runtime stopped");
    }
}
