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

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The threads and connections every queue in a process shares.
 * <p>
 * <b>Why this exists.</b> Pumps, the wake-up listener and the heartbeat were all scoped to a single
 * {@link NextGenQueue}, so database contact scaled with the number of queues: {@code (pumps x lanes +
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
 * <b>The wake-up channel was already global.</b> {@link NextGenListener} listens on one channel and
 * the payload carries {@code queueId:lane:shard}, so a single listener demultiplexes to the right
 * shard of the right lane of the right queue. Nothing had to change for that — it was always able to
 * serve every queue, and was simply being constructed once per queue.
 */
public final class ShardRuntime implements AutoCloseable {
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
     */
    private static final Map<DataSource, Shared> SHARED = new HashMap<>();

    private record Shared(ShardRuntime runtime, int borrowers) {
    }

    static synchronized ShardRuntime acquireShared(DataSource dataSource, ShardOwnerSettings settings) {
        var existing = SHARED.get(dataSource);
        if (existing != null) {
            SHARED.put(dataSource, new Shared(existing.runtime(), existing.borrowers() + 1));
            return existing.runtime();
        }
        var runtime = new ShardRuntime(dataSource, settings);
        SHARED.put(dataSource, new Shared(runtime, 1));
        return runtime;
    }

    /** Give a shared runtime back. The last borrower out closes it. */
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
        existing.runtime().close();
    }

    private final DataSource                dataSource;
    private final ShardOwnerSettings        settings;
    private final ShardOwnerMetrics         metrics;
    private final AtomicBoolean             running = new AtomicBoolean(true);
    private final AtomicBoolean             flushOnExit = new AtomicBoolean(true);
    private final List<ShardPump>           pumps = new ArrayList<>();
    private final Map<String, ShardWakeup>  wakeups = new ConcurrentHashMap<>();
    private final ExecutorService           pumpExecutor;
    private final ExecutorService           handlerExecutor;
    private final ScheduledExecutorService  heartbeat;
    private final NextGenListener           listener;
    /**
     * The process-wide handler bound. A permit is taken before a message is dispatched and returned
     * when its handler finishes, so both lanes and every queue draw on the same budget — and a
     * deployment can size it against whatever the handlers actually contend for, usually a connection
     * pool, rather than against an accident of how many shards happen to be owned.
     */
    private final Semaphore                 handlerPermits;

    public ShardRuntime(DataSource dataSource, ShardOwnerSettings settings) {
        this(dataSource, settings, new ShardOwnerMetrics());
    }

    public ShardRuntime(DataSource dataSource, ShardOwnerSettings settings, ShardOwnerMetrics metrics) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.metrics = requireNonNull(metrics, "No metrics provided");

        var pumpCount = Math.max(1, settings.pumpThreads());
        this.pumpExecutor = Executors.newFixedThreadPool(pumpCount, named("ng-pump"));
        // Handlers are user code that mostly waits. One virtual thread per message in flight, shared
        // by every queue, rather than a platform pool sized per shard.
        this.handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.handlerPermits = new Semaphore(Math.max(1, settings.handlerConcurrency()));
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(named("ng-heartbeat"));

        // A storage handle bound to no queue in particular: the pumps only use it to open connections,
        // and every statement an owner issues carries its own queue id.
        var connections = new NextGenStorage(dataSource, (short) 0);
        for (var index = 0; index < pumpCount; index++) {
            var pump = new ShardPump(connections, settings, metrics, running, flushOnExit, "pump-" + index);
            pumps.add(pump);
            pumpExecutor.submit(pump);
        }
        this.listener = new NextGenListener(dataSource, wakeups);
        // Started here, after it is fully constructed, rather than from inside its own constructor.
        this.listener.start();
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
        return wakeups.computeIfAbsent(NextGenListener.key(queueId, lane, shard),
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
        wakeups.remove(NextGenListener.key(queueId, lane, shard));
    }

    HandlerDispatch dispatch(int parallelConsumers) {
        return new HandlerDispatch(handlerExecutor, handlerPermits,
                                   new Semaphore(Math.max(1, parallelConsumers)));
    }

    /** In-flight handler invocations across the process, for the cost gate. */
    public int handlersInFlight() {
        return Math.max(0, settings.handlerConcurrency() - handlerPermits.availablePermits());
    }

    /** Register a queue's heartbeat. One scheduler thread runs every queue's renewals and rebalances. */
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

    @Override
    public void close() {
        running.set(false);
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
        log.debug("Shard runtime stopped");
    }
}
