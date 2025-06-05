package dk.trustworks.essentials.components.foundation.scheduler;

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.scheduler.executor.*;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.Lifecycle;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

public class DefaultScheduler implements EssentialsScheduler, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(DefaultScheduler.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final FencedLockManager                                             fencedLockManager;
    private final int                                                           schedulerThreads;
    private       boolean                                                       pgCronAvailable;
    private       LockName                                                      lockName;

    private volatile boolean started;

    private       ScheduledExecutorService       executorService;
    private final PgCronRepository               pgCronRepository;
    private final ExecutorScheduledJobRepository executorScheduledJobRepository;

    private final List<PgCronJob>         pgCronJobs   = new CopyOnWriteArrayList<>();
    private final Map<PgCronJob, Integer> pgCronJobIds = new ConcurrentHashMap<>();

    private final List<ExecutorJob>                    executorJobs       = new CopyOnWriteArrayList<>();
    private final Map<ExecutorJob, ScheduledFuture<?>> executorJobFutures = new ConcurrentHashMap<>();

    public DefaultScheduler(HandleAwareUnitOfWorkFactory<?> unitOfWorkFactory,
                            FencedLockManager lockManager,
                            int schedulerThreads) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.fencedLockManager = lockManager;
        this.schedulerThreads = schedulerThreads;
        this.pgCronRepository = new PgCronRepository(unitOfWorkFactory);
        this.executorScheduledJobRepository = new ExecutorScheduledJobRepository(unitOfWorkFactory);
    }

    @Override
    public boolean scheduleJob(EssentialsScheduledJob job) {
        if (job instanceof PgCronJob pgCronJob) {
            pgCronJobs.add(pgCronJob);
            if (started) {
                schedulePgCronJobInternal(pgCronJob);
            }
        } else if (job instanceof ExecutorJob executorJob) {
            executorJobs.add(executorJob);
            if (started) {
                scheduleExecutorJobInternal(executorJob);
            }
        } else {
            log.warn("Unknown job type '{}'", job.getClass().getName());
            return false;
        }

        return false;
    }

    @Override
    public void start() {
        if (!started) {
            started = true;

            executorService = Executors.newScheduledThreadPool(schedulerThreads);

            tryAndCreatePgCronExtension();
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                pgCronAvailable = PostgresqlUtil.isPGExtensionAvailable(uow.handle(), "pg_cron");
                if(pgCronAvailable) {
                    boolean pgCronIsNotLoaded = determineIfPgCronIsLoaded();
                    if (pgCronIsNotLoaded) {
                        log.info("Detected that PGCron while available has not been loaded via shared_preload_libraries, skipping job '{}'", job);
                        pgCronAvailable = false;
                    }
                }
            });
            log.info("⚙️ Starting Essentials Scheduler with pg_cron available '{}'", pgCronAvailable);

            lockName = LockName.of("essentials-scheduler");
            fencedLockManager.acquireLockAsync(lockName,
                                               LockCallback.builder()
                                                           .onLockAcquired(lock -> {
                                                               log.info("FencedLock '{}' for EssentialsScheduler was ACQUIRED - will schedule jobs.", lockName);
                                                               scheduleJobs();
                                                           })
                                                           .onLockReleased(lock -> {
                                                               log.info("FencedLock '{}' for EssentialsScheduler  was RELEASED - will unschedule pg_cron jobs", lockName);
                                                               if (pgCronAvailable) {
                                                                   for (PgCronJob job : pgCronJobs) {
                                                                       Integer jobId = pgCronJobIds.get(job);
                                                                       if (jobId != null) {
                                                                           // TODO: should we do this?
                                                                           pgCronRepository.unschedule(jobId);
                                                                       }
                                                                   }
                                                               }

                                                           })
                                                           .build());

            try {

                Optional<FencedLock> fencedLock = fencedLockManager.tryAcquireLock(lockName);
                fencedLock.ifPresent(lock -> {
                    scheduleJobs();
                });

            } catch (Exception e) {
                log.warn("Error while trying to acquire lock for postgresql-scheduler", e);
            }
        }
    }

    private boolean determineIfPgCronIsLoaded() {
        try {
            pgCronRepository.schedule(new PgCronJob("test()", CronExpression.OEN_SECOND));
        } catch (Exception e) {
            return PostgresqlUtil.isPGExtensionNotLoadedException(e);
        }
        return false;
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
            log.warn("Failed to create pg_cron extension", e);
        }
    }

    private boolean scheduleExecutorJobInternal(ExecutorJob job) {
        ScheduledFuture<?> future = executorService.scheduleAtFixedRate(
                job.task(), job.fixedDelay().initialDelay(), job.fixedDelay().period(), job.fixedDelay().unit());
        executorJobFutures.put(job, future);
        return true;
    }

    private boolean schedulePgCronJobInternal(PgCronJob job) {
        Integer jobId = pgCronRepository.schedule(job);
        if (jobId != null) {
            log.info("Added PgCronJob '{}' with jobId '{}'", job, jobId);
            pgCronJobIds.put(job, jobId);
            return true;
        }
        return false;
    }

    @Override
    public void stop() {
        if (started) {
            started = false;
            log.info("⚙ Stopping Essentials Scheduler with pg_cron available '{}'", pgCronAvailable);

            fencedLockManager.cancelAsyncLockAcquiring(lockName);
            executorScheduledJobRepository.delete();

            for (ScheduledFuture<?> future : executorJobFutures.values()) {
                future.cancel(true);
            }
            if (executorService != null) {
                executorService.shutdownNow();
                executorService = null;
            }
            log.info("Stopped Essentials Scheduler");
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }
}
