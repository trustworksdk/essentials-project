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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
            // The first borrower's settings are the ones in force, because the pumps, the listener
            // and the handler budget were sized from them and are already running. Saying so is the
            // point: a second queue asking for four pump threads and silently getting two is the
            // kind of thing that is only discovered while reading a heap dump.
            if (!existing.runtime().settings.equals(settings)) {
                log.warn("A shared ShardRuntime for this DataSource already exists and was built with "
                                 + "different settings; the settings passed here are ignored. Construct the "
                                 + "ShardRuntime yourself and pass it to every ShardOwnedQueue if the process "
                                 + "needs a specific configuration. In force: {}. Ignored: {}",
                         existing.runtime().settings, settings);
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

    private final DataSource               dataSource;
    private final ShardOwnerSettings       settings;
    private final ShardOwnerMetrics        metrics;
    private final AtomicBoolean            running     = new AtomicBoolean();
    private final AtomicBoolean            flushOnExit = new AtomicBoolean(true);
    private final List<ShardPump>          pumps       = new ArrayList<>();
    private final Map<String, ShardWakeup> wakeups     = new ConcurrentHashMap<>();
    /**
     * Recreated by every {@link #start()}, because an {@code ExecutorService} cannot be restarted
     * once shut down. This is what makes the runtime genuinely restartable rather than merely
     * stoppable — the same distinction {@code ShardOwnedQueue.stop()} had to make.
     */
    private       ExecutorService          pumpExecutor;
    private       ExecutorService          handlerExecutor;
    private       ScheduledExecutorService heartbeat;
    private       ShardWakeupListener      listener;

    public ShardRuntime(DataSource dataSource, ShardOwnerSettings settings) {
        this(dataSource, settings, new ShardOwnerMetrics());
    }

    public ShardRuntime(DataSource dataSource, ShardOwnerSettings settings, ShardOwnerMetrics metrics) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.metrics = requireNonNull(metrics, "No metrics provided");
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
        for (var index = 0; index < pumpCount; index++) {
            var pump = new ShardPump(connections, settings, metrics, running, flushOnExit, "pump-" + index);
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
     * The wake-up for one shard of one lane of one queue, cascading to the pump that will serve it.
     * Keyed the same way the notification payload is, so the listener can route without knowing
     * anything about queues.
     */
    ShardWakeup wakeupFor(short queueId, String lane, int shard) {
        var pump = pumpFor(queueId, lane, shard);
        return wakeups.computeIfAbsent(ShardWakeupListener.key(queueId, lane, shard),
                                       ignored -> new ShardWakeup(pump.wakeup()));
    }

    /**
     * Which pump serves a shard. Spread over the queue and lane as well as the shard, so a process
     * with many queues of few shards still uses every pump.
     */
    ShardPump pumpFor(short queueId, String lane, int shard) {
        var spread = Objects.hash(queueId, lane, shard);
        return pumps.get(Math.floorMod(spread, pumps.size()));
    }

    void register(short queueId, String lane, int shard, LeasedOwner owner) {
        pumpFor(queueId, lane, shard).add(owner);
    }

    void forget(short queueId, String lane, int shard) {
        wakeups.remove(ShardWakeupListener.key(queueId, lane, shard));
    }

    HandlerDispatch dispatch(int parallelConsumers) {
        return new HandlerDispatch(handlerExecutor, new Semaphore(Math.max(1, parallelConsumers)));
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
     * Equivalent to {@link #stop()}, so try-with-resources and a container both work.
     */
    @Override
    public void close() {
        stop();
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
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
            if (!pumpExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                pumpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pumpExecutor.shutdownNow();
        }
        handlerExecutor.shutdown();
        // Cleared so a restart does not signal wake-ups belonging to owners that no longer exist.
        wakeups.clear();
        log.debug("Shard runtime stopped");
    }
}
