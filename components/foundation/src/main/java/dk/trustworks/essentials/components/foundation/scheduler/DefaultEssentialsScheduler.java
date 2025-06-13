/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.foundation.scheduler;

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.scheduler.executor.*;
import dk.trustworks.essentials.components.foundation.scheduler.executor.ExecutorScheduledJobRepository.ExecutorJobEntry;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.*;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.PgCronRepository.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.*;
import dk.trustworks.essentials.shared.network.Network;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * ** Note: This scheduler is not intended to replace a full-fledged scheduler such as Quartz or Spring, it is a simple
 * scheduler that utilizes the postgresql pg_cron extension if available or a simple ScheduledExecutorService to schedule jobs.
 * It is meant to be used internally by essentials components to schedule jobs.
 * **
 * <p>
 * DefaultEssentialsScheduler is a task scheduler implementation that manages scheduling for both
 * PostgreSQL-based cron jobs (pg_cron) and standard Java Executor-based jobs. The class ensures
 * proper execution of tasks based on available locking mechanisms and PostgreSQL support.
 * <p>
 * Responsibilities:
 * - Schedules and manages both Executor-based and pg_cron-based jobs.
 * - Coordinates job scheduling with the availability of PostgreSQL's pg_cron extension.
 * - Manages task lifecycle using a distributed lock to ensure coordinated task execution across multiple nodes.
 */
public class DefaultEssentialsScheduler implements EssentialsScheduler, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(DefaultEssentialsScheduler.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final FencedLockManager                                             fencedLockManager;
    private final int                                                           schedulerThreads;
    private       boolean                                                       pgCronAvailable;
    private final LockName                                                      lockName;

    private volatile boolean started;

    private       ScheduledExecutorService             executorService;
    private final PgCronRepository                     pgCronRepository;
    private final ExecutorScheduledJobRepository       executorScheduledJobRepository;

    private final List<PgCronJob>                      pgCronJobs   = new CopyOnWriteArrayList<>();
    private final Map<PgCronJob, Integer>              pgCronJobIds = new ConcurrentHashMap<>();

    private final List<ExecutorJob>                    executorJobs       = new CopyOnWriteArrayList<>();
    private final Map<ExecutorJob, ScheduledFuture<?>> executorJobFutures = new ConcurrentHashMap<>();

    public DefaultEssentialsScheduler(HandleAwareUnitOfWorkFactory<?> unitOfWorkFactory,
                                      FencedLockManager lockManager,
                                      int schedulerThreads) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null");
        this.fencedLockManager = requireNonNull(lockManager, "lockManager cannot be null");
        requireTrue(schedulerThreads > 0, "schedulerThreads must be greater than 0");
        this.schedulerThreads = schedulerThreads;
        this.pgCronRepository = new PgCronRepository(unitOfWorkFactory);
        this.executorScheduledJobRepository = new ExecutorScheduledJobRepository(unitOfWorkFactory);
        this.lockName = new LockName("essentials-scheduler");
    }

    @Override
    public void schedulePgCronJob(PgCronJob job) {
        log.debug("Scheduling PgCronJob '{}'", job);
        pgCronJobs.add(job);
        if (started && pgCronAvailable && fencedLockManager.isLockAcquired(lockName)) {
            schedulePgCronJobInternal(job);
        } else {
            log.info("PgCron is not available or scheduler is not started can't schedule job '{}'", job);
        }
    }

    @Override
    public void scheduleExecutorJob(ExecutorJob job) {
        log.debug("Adding ExecutorJob '{}'", job);
        executorJobs.add(job);
        if (started && fencedLockManager.isLockAcquired(lockName)) {
            scheduleExecutorJobInternal(job);
        } else {
            log.info("Scheduler is not started can't schedule job '{}'", job);
        }
    }

    @Override
    public boolean isPgCronAvailable() {
        return pgCronAvailable;
    }

    @Override
    public LockName getLockName() {
        return lockName;
    }

    /**
     * Experimental
     */
    public boolean cancelPgCronJob(Integer jobId) {
        log.debug("Cancelling PgCronJob '{}'", jobId);
        try {
            // TODO: use MultiTableChangeListener to remove in memory job
            pgCronRepository.unschedule(jobId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to unschedule pg_cron jobId {}", jobId, e);
        }
        return false;
    }

    /**
     * Experimental
     */
    public boolean cancelExecutorJob(String name) {
        log.debug("Cancelling ExecutorJob '{}'", name);
        try {
            // TODO: use MultiTableChangeListener to cancel future and remove in memory job executor job
            return executorScheduledJobRepository.deleteByName(name);
        } catch (Exception e) {
            log.warn("Failed to cancel executor job {}", name, e);
        }
        return false;
    }

    private void scheduleExecutorJobInternal(ExecutorJob job) {
        if (!executorScheduledJobRepository.existsByName(job.name())) {
            ScheduledFuture<?> future = executorService.scheduleAtFixedRate(
                    job.task(), job.fixedDelay().initialDelay(), job.fixedDelay().period(), job.fixedDelay().unit());
            log.info("✅ Added ExecutorJob '{}'", job);
            executorJobFutures.put(job, future);
            executorScheduledJobRepository.insert(job);
        } else {
            log.warn("ExecutorJob '{}' already exists", job);
        }
    }

    private void schedulePgCronJobInternal(PgCronJob job) {
        if (pgCronRepository.doesJobExist(job.name()) == null) {
            var jobId = pgCronRepository.schedule(job);
            if (jobId != null) {
                log.info("✅ Added PgCronJob '{}' with jobId '{}'", job, jobId);
                pgCronJobIds.put(job, jobId);
            }
        } else {
            log.warn("PgCronJob '{}' already exists", job);
        }
    }

    private Optional<PgCronJob> findJobById(Integer jobId) {
        return pgCronJobIds.entrySet().stream()
                           .filter(e -> e.getValue().equals(jobId))
                           .map(Map.Entry::getKey)
                           .findFirst();
    }

    private Optional<ExecutorJob> findJobByName(String name) {
        return executorJobFutures.keySet().stream()
                                 .filter(job -> job.name().equals(name))
                                 .findFirst();
    }

    @Override
    public void start() {
        if (!started) {
            started = true;

            executorService = Executors.newScheduledThreadPool(schedulerThreads);

            tryAndCreatePgCronExtension();
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                var available = PostgresqlUtil.isPGExtensionAvailable(uow.handle(), "pg_cron");
                if (available) {
                    boolean loaded = determineIfPgCronIsLoaded();
                    pgCronAvailable = loaded;
                    if (!loaded) {
                        log.warn("Detected that pg_cron exists but is not shared_preload_libraries‐loaded; disabling pg_cron support.");
                    }
                } else {
                    pgCronAvailable = false;
                }
            });
            log.info("⚙️ Starting Essentials Scheduler (with pg_cron available = '{}')", pgCronAvailable);

            fencedLockManager.acquireLockAsync(lockName,
                                               LockCallback.builder()
                                                           .onLockAcquired(this::onLockAcquired)
                                                           .onLockReleased(this::onLockReleased)
                                                           .build());
        }
    }

    private boolean determineIfPgCronIsLoaded() {
        try {
            Integer testId = pgCronRepository.schedule(new PgCronJob("test", "test", CronExpression.ONE_SECOND));
            pgCronRepository.unschedule(testId);
        } catch (Exception e) {
            var notLoaded = PostgresqlUtil.isPGExtensionNotLoadedException(e);
            if (!notLoaded) {
                log.warn("Failed to determine if pg_cron is loaded", e);
            }
            return false;
        }
        return true;
    }

    private void onLockAcquired(FencedLock lock) {
        log.info("🎉 FencedLock '{}' was ACQUIRED; purging stale entries, then scheduling all jobs.", lockName);

        var instanceId = Network.hostName();

        if (pgCronAvailable) {
            try {
                pgCronRepository.deleteJobByNameEndingWithInstanceId(instanceId);
            } catch (Exception e) {
                log.warn("Failed to purge stale pg_cron jobs on lock acquisition", e);
            }
        }

        try {
            executorScheduledJobRepository.deleteByNameEndingWithInstanceId(instanceId);
        } catch (Exception e) {
            log.warn("Failed to purge stale executor scheduled jobs on lock acquisition", e);
        }

        scheduleJobs();
    }

    private void onLockReleased(FencedLock lock) {
        log.info("🚨 FencedLock '{}' was RELEASED; unscheduling all pg_cron and executor tasks immediately.", lockName);

        unschedulePgCronJobs();

        unscheduleExecutorJobs();

        try {
            executorScheduledJobRepository.deleteAll();
        } catch (Exception e) {
            log.warn("Failed to purge stale executor scheduled jobs on lock acquisition", e);
        }
    }

    private void scheduleJobs() {
        if (pgCronAvailable) {
            for (PgCronJob job : pgCronJobs) {
                schedulePgCronJobInternal(job);
            }
        }

        for (ExecutorJob job : executorJobs) {
            scheduleExecutorJobInternal(job);
        }
    }

    private void tryAndCreatePgCronExtension() {
        try {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                uow.handle().execute("CREATE EXTENSION IF NOT EXISTS pg_cron;");
            });
        } catch (UnitOfWorkException e) {
            log.warn("Failed to create pg_cron extension -> '{}'", e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (started) {
            started = false;
            log.info("⏹ Stopping Essentials Scheduler (pg_cron available = '{}')", pgCronAvailable);

            if (fencedLockManager.isLockAcquired(lockName)) {
                try {
                    executorScheduledJobRepository.deleteAll();
                } catch (Exception e) {
                    log.warn("Error deleting executor scheduled jobs in stop()", e);
                }
            }

            fencedLockManager.cancelAsyncLockAcquiring(lockName);

            unschedulePgCronJobs();

            unscheduleExecutorJobs();

            if (executorService != null) {
                executorService.shutdownNow();
                executorService = null;
            }

            log.info("🛑 Stopped Essentials Scheduler");
        }
    }

    private void unscheduleExecutorJobs() {
        for (ScheduledFuture<?> future : executorJobFutures.values()) {
            future.cancel(true);
        }
        executorJobFutures.clear();
    }

    private void unschedulePgCronJobs() {
        if (pgCronAvailable) {
            for (Map.Entry<PgCronJob, Integer> pair : pgCronJobIds.entrySet()) {
                Integer id = pair.getValue();
                if (id != null) {
                    try {
                        pgCronRepository.unschedule(id);
                    } catch (Exception e) {
                        log.warn("Failed to unschedule pg_cron jobId {}", id, e);
                    }
                }
            }
            pgCronJobIds.clear();
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    public List<PgCronEntry> fetchPgCronEntries(long startIndex, long pageSize) {
        if (pgCronAvailable) {
            return pgCronRepository.fetchPgCronEntries(startIndex, pageSize);

        }
        return Collections.emptyList();
    }

    public long getTotalPgCronEntries() {
        if (pgCronAvailable) {
            return pgCronRepository.getTotalPgCronEntries();

        }
        return 0L;
    }

    public List<PgCronJobRunDetails> fetchPgCronJobRunDetails(Integer jobId, long startIndex, long pageSize) {
        if (pgCronAvailable) {
            return pgCronRepository.fetchPgCronJobDetails(jobId, startIndex, pageSize);
        }
        return Collections.emptyList();
    }

    public long getTotalPgCronJobRunDetails(Integer jobId) {
        if (pgCronAvailable) {
            return pgCronRepository.getTotalPgCronJobDetails(jobId);
        }
        return 0L;
    }

    public List<ExecutorJobEntry> fetchExecutorJobEntries(long startIndex, long pageSize) {
        return executorScheduledJobRepository.fetchExecutorJobEntries(pageSize, startIndex, true);
    }

    public long geTotalExecutorJobEntries() {
        return executorScheduledJobRepository.getTotalExecutorJobEntries();
    }

}
