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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.shared.Lifecycle;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Lifecycle-managed scheduled scanner for closing-books policies.
 * <p>
 * A single manager can coordinate many {@link ClosingBooksScheduledScanProcessor}s and uses a shared
 * {@link FencedLockManager} lock to ensure only one node performs scheduled scans at a time.
 */
public class ClosingBooksManager implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(ClosingBooksManager.class);

    private final List<ClosingBooksScheduledScanProcessor> processors;
    private final ClosingBooksManagerSettings              settings;
    private final FencedLockManager                        fencedLockManager;
    private final LockName                                 lockName;
    private final ClosingBooksManagementMeasurementSupport measurementSupport;

    private final AtomicBoolean            started = new AtomicBoolean();
    private       ScheduledExecutorService scheduler;
    private       ScheduledFuture<?>       pollingFuture;

    /**
     * Constructs a new instance of {@code ClosingBooksManager}.
     *
     * @param processors         A list of {@code ClosingBooksScheduledScanProcessor} instances used to process scheduled scan operations for closing books.
     * @param settings           The {@code ClosingBooksManagerSettings} that defines configuration such as poll interval, batch size, and lock acquire timeout.
     * @param fencedLockManager  The {@code FencedLockManager} responsible for managing distributed locks to ensure synchronization across nodes.
     * @param lockName           The {@code LockName} used to identify the specific lock managed by {@code FencedLockManager}.
     */
    public ClosingBooksManager(List<ClosingBooksScheduledScanProcessor> processors,
                               ClosingBooksManagerSettings settings,
                               FencedLockManager fencedLockManager,
                               LockName lockName) {
        this(processors, settings, fencedLockManager, lockName, Optional.empty());
    }

    /**
     * Constructs a new instance of {@code ClosingBooksManager}.
     *
     * @param processors          A list of {@code ClosingBooksScheduledScanProcessor} instances used to process
     *                             scheduled scan operations for closing books. Must contain at least one processor.
     * @param settings            The {@code ClosingBooksManagerSettings} that defines configuration details such as
     *                             poll interval, batch size, and lock acquire timeout.
     * @param fencedLockManager   The {@code FencedLockManager} responsible for managing distributed locks to ensure
     *                             synchronization across nodes.
     * @param lockName            The {@code LockName} used to identify the specific lock managed by {@code FencedLockManager}.
     * @param meterRegistryOptional An {@code Optional} containing the {@code MeterRegistry} for metrics and monitoring
     *                             support. This can be empty if metrics are not enabled.
     */
    public ClosingBooksManager(List<ClosingBooksScheduledScanProcessor> processors,
                               ClosingBooksManagerSettings settings,
                               FencedLockManager fencedLockManager,
                               LockName lockName,
                               Optional<MeterRegistry> meterRegistryOptional) {
        this.processors = new CopyOnWriteArrayList<>(requireNonNull(processors, "No processors provided"));
        if (this.processors.isEmpty()) {
            throw new IllegalArgumentException("At least one processor must be provided");
        }
        this.settings = requireNonNull(settings, "No settings provided");
        this.fencedLockManager = requireNonNull(fencedLockManager, "No fencedLockManager provided");
        this.lockName = requireNonNull(lockName, "No lockName provided");
        this.measurementSupport = new ClosingBooksManagementMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        log.info("⚙️ Starting Essentials ClosingBooks Manager");


        scheduler = Executors.newSingleThreadScheduledExecutor(ThreadFactoryBuilder.builder()
                                                                                   .nameFormat("closing-books-manager-%d")
                                                                                   .daemon(true)
                                                                                   .build());
        pollingFuture = scheduler.scheduleWithFixedDelay(() -> {
            if (!started.get()) {
                return;
            }

            pollOnce();
        }, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);

        log.info("Started ClosingBooksManager with {} processors using lock '{}'", processors.size(), lockName);
    }

    private void pollOnce() {
        try {
            executeWithLock();
        } catch (Throwable e) {
            if (!started.get() || Thread.currentThread().isInterrupted()) {
                log.debug("Ignoring closing books manager poll failure because shutdown is in progress", e);
                return;
            }
            processors.forEach(processor -> measurementSupport.incrementManagerPollOutcome(processor.aggregateType(), "failed"));
            log.warn("Closing books manager poll failed for lock '{}'", lockName, e);
        }
    }

    private void executeWithLock() {
        var acquiredLock      = fencedLockManager.tryAcquireLock(lockName, settings.lockAcquireTimeout());
        if (acquiredLock.isEmpty()) {
            processors.forEach(processor -> measurementSupport.incrementManagerPollOutcome(processor.aggregateType(), "lock_not_acquired"));
            log.debug("Closing books manager skipped poll because lock '{}' was not acquired", lockName);
            return;
        }

        try {
            for (var processor : processors) {
                measurementSupport.recordManagerPoll(processor.aggregateType(), () -> {
                    var processedCount = processor.processNextBatch(settings.batchSize());
                    measurementSupport.incrementManagerPollOutcome(processor.aggregateType(), processedCount > 0 ? "processed" : "idle");
                });
            }
        } finally {
            acquiredLock.get().release();
        }
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) return;

        log.info("⏹ Stopping Essentials ClosingBooks Manager");

        if (pollingFuture != null) {
            pollingFuture.cancel(false);
            pollingFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        log.info("🛑  Stopped ClosingBooksManager with lock '{}'", lockName);
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }
}
