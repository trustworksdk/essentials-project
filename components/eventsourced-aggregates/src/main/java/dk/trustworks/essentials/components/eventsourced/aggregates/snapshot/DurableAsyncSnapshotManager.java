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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.shared.Lifecycle;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import org.slf4j.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Manages the execution of durable asynchronous snapshot jobs using a scheduled polling mechanism.
 * This class leverages a {@link PostgresqlAggregateSnapshotJobProcessor} for processing snapshot jobs
 * and utilizes configurations provided by {@link DurableAsyncSnapshotSettings}.
 */
public class DurableAsyncSnapshotManager implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(DurableAsyncSnapshotManager.class);

    private final PostgresqlAggregateSnapshotJobProcessor processor;
    private final DurableAsyncSnapshotSettings            settings;

    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService scheduler;
    private ExecutorService          workerExecutor;
    private ScheduledFuture<?>       pollingFuture;

    /**
     * Constructs a new instance of {@code DurableAsyncSnapshotManager}, initializing it with the
     * provided {@code processor} and {@code settings}.
     *
     * @param processor the {@link PostgresqlAggregateSnapshotJobProcessor} responsible for
     *                  managing the snapshot processing jobs. Must not be null.
     * @param settings the {@link DurableAsyncSnapshotSettings} containing configuration values
     *                 such as polling interval, batch size, and retry settings. Must not be null.
     * @throws IllegalArgumentException if either {@code processor} or {@code settings} is null.
     */
    public DurableAsyncSnapshotManager(PostgresqlAggregateSnapshotJobProcessor processor,
                                       DurableAsyncSnapshotSettings settings) {
        this.processor = requireNonNull(processor, "No processor provided");
        this.settings = requireNonNull(settings, "No settings provided");
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        log.info("⚙️ Starting Essentials Snapshot Manager with '{}' worker threads", settings.workerThreads());


        scheduler = Executors.newSingleThreadScheduledExecutor(ThreadFactoryBuilder.builder()
                                                                                   .nameFormat("durable-async-snapshot-scheduler-%d")
                                                                                   .daemon(true)
                                                                                   .build());
        // Bounded queue plus CallerRunsPolicy, deliberately not Executors.newFixedThreadPool: that one queues into an
        // unbounded LinkedBlockingQueue, and since processNextBatch submits a whole locked batch without waiting for
        // it, a poll interval shorter than the time to drain a batch made the queue — and the serialized snapshot
        // payload each queued job retains — grow without limit. With the defaults (poll 1s, batch 25, 2 workers) that
        // needed only ~80ms of work per job to start running away.
        //
        // Once the queue is full the polling thread runs the job itself, which throttles polling to the rate the
        // workers can actually sustain. That also keeps a job's queue wait to a few job durations, so
        // processingTimeout stays a signal about genuinely stuck work rather than about backlog.
        workerExecutor = new ThreadPoolExecutor(settings.workerThreads(),
                                                settings.workerThreads(),
                                                0L,
                                                TimeUnit.MILLISECONDS,
                                                new ArrayBlockingQueue<>(Math.max(1, settings.workerThreads())),
                                                ThreadFactoryBuilder.builder()
                                                                    .nameFormat("durable-async-snapshot-worker-%d")
                                                                    .daemon(true)
                                                                    .build(),
                                                new ThreadPoolExecutor.CallerRunsPolicy());
        pollingFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (!started.get()) {
                return;
            }

            try {
                processor.processNextBatch(workerExecutor);
            } catch (Throwable e) {
                if (!started.get() || Thread.currentThread().isInterrupted()) {
                    log.debug("Ignoring durable async snapshot polling failure because shutdown is in progress", e);
                    return;
                }
                log.warn("Durable async snapshot polling failed", e);
            }
        }, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        log.info("Started DurableAsyncSnapshotManager");
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) return;

        log.info("⏹ Stopping Essentials Snapshot Manager");

        if (pollingFuture != null) {
            pollingFuture.cancel(false);
            pollingFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
        scheduler = null;
        workerExecutor = null;

        log.info("🛑 Stopped Essentials Snapshot Manager");
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }
}
