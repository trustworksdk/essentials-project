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

package dk.trustworks.essentials.components.foundation.fencedlock;

import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockEvents.*;
import dk.trustworks.essentials.components.foundation.lifecycle.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.reactive.*;
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import dk.trustworks.essentials.shared.functional.*;
import org.slf4j.*;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;


/**
 * Common super base class for implementing persistent/durable {@link FencedLockManager}'s
 *
 * @param <UOW>  the type of {@link UnitOfWork} required
 * @param <LOCK> the concrete type of {@link DBFencedLock} used
 */
public abstract class DBFencedLockManager<UOW extends UnitOfWork, LOCK extends DBFencedLock> implements FencedLockManager, ShutdownAware {
    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    private final FencedLockStorage<UOW, LOCK>                lockStorage;
    /**
     * Entries only exist if this lock manager instance believes that it has acquired the given lock<br>
     * Key: Lock name<br>
     * Value: The acquired {@link DBFencedLock}
     */
    private final ConcurrentMap<LockName, LOCK>               locksAcquiredByThisLockManager;
    /**
     * Entries only exist if acquiring the lock is being performed asynchronously
     * Key: lock name<br>
     * Value: the {@link ScheduledFuture} returned from {@link ScheduledExecutorService#scheduleAtFixedRate(Runnable, long, long, TimeUnit)}
     */
    private final ConcurrentMap<LockName, ScheduledFuture<?>> asyncLockAcquirings;
    private final Duration                                    lockTimeOut;
    private final Duration                                    lockConfirmationInterval;
    private final String                                      lockManagerInstanceId;

    private final UnitOfWorkFactory<? extends UOW> unitOfWorkFactory;
    /**
     * Where {@link FencedLockEvents} are published, or {@code null} to publish nowhere. Held nullable rather than as
     * an {@code Optional} field: {@link #notify(FencedLockEvents)} is on the lock confirmation path and an
     * {@code Optional} field costs an allocation per access without buying anything a null check does not.
     */
    private final EventBus                         eventBus;
    /**
     * The one fair lock of this manager. It guards: the lock confirmation tick, every async-acquiring tick,
     * {@link #acquireLockAsync(LockName, LockCallback)}, the teardown in {@link #stop()} and {@link #start()}, shutdown releases, and the
     * acquire - its commit, the commit-outcome lookup and recording the result - of the blocking {@link #tryAcquireLock(LockName)}
     * variants and {@link #acquireLock(LockName)}. {@link #usingUnitOfWork(CheckedConsumer, CheckedConsumer)} and
     * {@link #withUnitOfWork(CheckedFunction, CheckedFunction)} take it as well, so <b>every database round trip this manager
     * makes is serialised by it</b>: a holder may wait on the database for up to two round trips (acquire and commit-outcome lookup),
     * and a lock confirmation queued behind it waits for them. Against a degraded database a confirmation can therefore be late -
     * keep {@code lockTimeOut} comfortably above the confirmation interval plus a few of the database's slow round trips.
     * Narrowing the acquire's critical section would not help while the unit of work helpers themselves hold this lock.
     */
    private final ReentrantLock                    reentrantLock = new ReentrantLock(true);
    private final boolean                          releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation;

    private volatile boolean started;
    private volatile boolean stopping;
    /**
     * Paused is used for testing purposes to pause async lock acquiring and confirmation
     */
    private volatile boolean paused;
    /**
     * Set by {@link #shutdownStarting(ShutdownContext)}, cleared by {@link #stop()}. While set, no background work touches
     * the database and lock releases are a bounded best effort - see {@link #shutdownStarting(ShutdownContext)}
     */
    private volatile ShutdownContext shutdown;

    private   ScheduledExecutorService lockConfirmationExecutor;
    private   ScheduledExecutorService asyncLockAcquiringExecutor;
    /**
     * {@link #tryAcquireLock(LockName)}/{@link #tryAcquireLock(LockName, Duration)} and {@link #acquireLock(LockName)} pause interval between retries
     */
    protected int                      syncAcquireLockPauseIntervalMs = 100;
    private volatile ScheduledFuture<?> confirmationScheduledFuture;

    /**
     * @param lockStorage       the lock storage used for the lock manager
     * @param unitOfWorkFactory the {@link UnitOfWork} factory
     * @param settings          the lock timing and identity configuration — see {@link FencedLockManagerSettings#builder()}.
     *                          The {@code lockConfirmationInterval < lockTimeOut} invariant is validated when the settings are created,
     *                          not here
     * @param eventBus          {@link LocalEventBus} where {@link FencedLockEvents} will be published, or {@code null} to publish nowhere
     */
    protected DBFencedLockManager(FencedLockStorage<UOW, LOCK> lockStorage,
                                  UnitOfWorkFactory<? extends UOW> unitOfWorkFactory,
                                  FencedLockManagerSettings settings,
                                  EventBus eventBus) {
        requireNonNull(settings, "No settings provided - see FencedLockManagerSettings.builder()");

        this.lockStorage = requireNonNull(lockStorage, "No lockStorage provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.lockManagerInstanceId = settings.lockManagerInstanceId();
        this.lockTimeOut = settings.lockTimeOut();
        this.lockConfirmationInterval = settings.lockConfirmationInterval();
        this.releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation = settings.releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation();
        this.eventBus = eventBus;

        locksAcquiredByThisLockManager = new ConcurrentHashMap<>();
        asyncLockAcquirings = new ConcurrentHashMap<>();


        log.info("[{}] Initializing '{}' using storage '{}' and lockConfirmationInterval: {} ms, lockTimeOut: {} ms",
                 this.lockManagerInstanceId,
                 this.getClass().getName(),
                 lockStorage.getClass().getName(),
                 lockConfirmationInterval.toMillis(),
                 lockTimeOut.toMillis());
        usingUnitOfWork(uow -> lockStorage.initializeLockStorage(this, uow),
                        e -> {
                            throw new IllegalStateException(msg("[{}] Failed to initialize lock storage", this.lockManagerInstanceId), e);
                        });
    }

    @Override
    public void start() {
        if (!started) {
            log.info("[{}] Starting lock manager", lockManagerInstanceId);
            // Symmetric with stop(): the executors and the started flag are published under the lock that
            // acquireLockAsync reads them under, so a caller racing a restart sees either the whole old state or the
            // whole new one, never a half-initialized manager
            reentrantLock.lock();
            try {
                stopping = false;
                lockConfirmationExecutor = Executors.newScheduledThreadPool(1,
                                                                            new ThreadFactoryBuilder()
                                                                                    .nameFormat(lockManagerInstanceId + "-FencedLock-Confirmation-%d")
                                                                                    .daemon(true)
                                                                                    .build());

                asyncLockAcquiringExecutor = Executors.newScheduledThreadPool(2,
                                                                              ThreadFactoryBuilder.builder()
                                                                                                  .nameFormat(lockManagerInstanceId + "-Lock-Acquiring-%d")
                                                                                                  .daemon(true)
                                                                                                  .build());

                confirmationScheduledFuture = lockConfirmationExecutor.scheduleAtFixedRate(this::confirmAllLocallyAcquiredLocks,
                                                                                           lockConfirmationInterval.toMillis(),
                                                                                           lockConfirmationInterval.toMillis(),
                                                                                           TimeUnit.MILLISECONDS);

                started = true;
            } finally {
                reentrantLock.unlock();
            }
            log.info("[{}] Started lock manager", lockManagerInstanceId);
            notify(new FencedLockManagerStarted(this));
        } else {
            log.debug("[{}] Lock Manager was already started", lockManagerInstanceId);
        }
    }

    protected void notify(FencedLockEvents event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

    public void pause() {
        log.info("[{}] Pausing async lock acquiring and lock confirmation", lockManagerInstanceId);
        paused = true;
    }

    public void resume() {
        log.info("[{}] Resuming async lock acquiring and lock confirmation", lockManagerInstanceId);
        paused = false;
    }

    private void confirmAllLocallyAcquiredLocks() {
        if (stopping || shutdown != null) {
            log.debug("[{}] Shutting down, skipping confirmAllLocallyAcquiredLocks", lockManagerInstanceId);
            return;
        }
        if (locksAcquiredByThisLockManager.isEmpty()) {
            log.debug("[{}] No locks to confirm for this Lock Manager instance", lockManagerInstanceId);
            return;
        }

        if (paused) {
            log.info("[{}] Lock Manager is paused, skipping confirmAllLocallyAcquiredLocks", lockManagerInstanceId);
            return;
        }
        try {
            reentrantLock.lock();
            var numberOfLocallyAcquiredLocksBeforeConfirmation = locksAcquiredByThisLockManager.size();
            if (log.isTraceEnabled()) {
                log.trace("[{}] Confirming {} locks acquired by this Lock Manager Instance: {}", lockManagerInstanceId, numberOfLocallyAcquiredLocksBeforeConfirmation, locksAcquiredByThisLockManager.keySet());
            } else {
                log.debug("[{}] Confirming {} locks acquired by this Lock Manager Instance", lockManagerInstanceId, numberOfLocallyAcquiredLocksBeforeConfirmation);
            }
            var confirmedTimestamp = OffsetDateTime.now(Clock.systemUTC());
            // Released in the DB only once the confirmation UnitOfWork has completed - see releaseLostLocksInDB
            var lostLocks = new ArrayList<LOCK>();
            usingUnitOfWork(uow -> {
                locksAcquiredByThisLockManager.forEach((lockName, fencedLock) -> {
                    if (fencedLock.getLockedByLockManagerInstanceId() == null) {
                        log.debug("[{}] Skipping confirming lock '{}' since lockedByLockManagerInstanceId is NULL: {}", lockManagerInstanceId, fencedLock.getName(), fencedLock);
                        return;
                    }
                    try {
                        log.trace("[{}] Attempting to confirm lock '{}': {}", lockManagerInstanceId, fencedLock.getName(), fencedLock);
                        boolean confirmedWithSuccess = false;
                        try {
                            confirmedWithSuccess = lockStorage.confirmLockInDB(this, uow, fencedLock, confirmedTimestamp);
                        } catch (Exception e) {
                            if (IOExceptionUtil.isIOException(e)) {
                                if (releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation) {
                                    log.debug(msg("[{}] IO related failure while attempting to perform confirmLockInDB for '{}' - will release lock: {}", lockManagerInstanceId, fencedLock.getName(), fencedLock), e);
                                } else {
                                    log.debug(msg("[{}] IO related failure while attempting to perform confirmLockInDB for '{}' - will retain lock: {}", lockManagerInstanceId, fencedLock.getName(), fencedLock), e);
                                    return;
                                }
                            } else {
                                log.error(msg("[{}] Technical failure while attempting to perform confirmLockInDB for '{}': {}", lockManagerInstanceId, fencedLock.getName(), fencedLock), e);
                            }
                        }
                        if (confirmedWithSuccess) {
                            fencedLock.markAsConfirmed(confirmedTimestamp);
                            log.debug("[{}] Confirmed lock '{}': {}", lockManagerInstanceId, fencedLock.getName(), fencedLock);
                            notify(new LockAcquired(fencedLock, this));
                        } else {
                            // We failed to confirm this lock, someone must have taken over the lock in the meantime
                            log.info("[{}] Failed to confirm lock '{}': {}", lockManagerInstanceId, fencedLock.getName(), fencedLock);
                            if (releaseLostLockLocally(fencedLock)) {
                                lostLocks.add(fencedLock);
                            }
                        }
                    } catch (Exception e) {
                        log.error(msg("[{}] Technical failure while trying to confirm lock '{}'", lockManagerInstanceId, fencedLock.getName()), e);
                    }
                });
            }, e -> {
                if (IOExceptionUtil.isIOException(e)) {
                    log.debug("[{}] Failed to acknowledge the {} locks acquired by this Lock Manager Instance", lockManagerInstanceId, locksAcquiredByThisLockManager.size(), e);
                } else {
                    log.error("[{}] Failed to acknowledge the {} locks acquired by this Lock Manager Instance", lockManagerInstanceId, locksAcquiredByThisLockManager.size(), e);
                }

                if (IOExceptionUtil.isIOException(e)) {
                    // The DB has just failed on IO, so a DB release would almost certainly fail too - each attempt only after
                    // waiting out the socket timeout(s), while holding this thread and reentrantLock. Release locally only:
                    // the rows expire after lockTimeOut
                    lostLocks.clear();
                    if (releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation) {
                        log.info("[{}] Releasing all locally acquired locks due to IO Exception - locally only, their DB rows expire after lockTimeOut", lockManagerInstanceId);
                        locksAcquiredByThisLockManager.forEach((lockName, fencedLock) -> releaseLostLockLocally(fencedLock));
                    }
                }
            });
            releaseLostLocksInDB(lostLocks);
            if (log.isTraceEnabled()) {
                log.trace("[{}] Completed confirmation of {} locks acquired by this Lock Manager Instance. Number of Locks acquired locally after confirmation {}: {}",
                          lockManagerInstanceId, numberOfLocallyAcquiredLocksBeforeConfirmation, locksAcquiredByThisLockManager.size(), locksAcquiredByThisLockManager.keySet());
            } else {
                log.debug("[{}] Completed confirmation of {} locks acquired by this Lock Manager Instance. Number of Locks acquired locally after confirmation {}",
                          lockManagerInstanceId, numberOfLocallyAcquiredLocksBeforeConfirmation, locksAcquiredByThisLockManager.size());
            }
        } finally {
            reentrantLock.unlock();
        }
    }


    /**
     * First half of releasing a lock this instance failed to confirm: remove it locally, mark it as released and
     * notify {@link LockReleased}, without any DB round trip. The lock is already lost, so the local release must
     * not wait on the DB: when the DB is unreachable a release attempt can take several times the socket timeout
     * (MongoDB driver 5.12+ retries connection establishment with backoff), and for all that time this instance
     * would keep reporting a lock that another instance may take over once <code>lockTimeOut</code> has passed.
     * The best-effort DB release follows in {@link #releaseLostLocksInDB(List)}.
     *
     * @param lock the lock that could not be confirmed
     * @return true if the lock was held locally and has now been released, false if it was already gone
     */
    private boolean releaseLostLockLocally(LOCK lock) {
        if (locksAcquiredByThisLockManager.remove(lock.getName()) == null) {
            return false;
        }
        log.debug("[{}] Releasing lost lock '{}' locally: {}", lockManagerInstanceId, lock.getName(), lock);
        // Runs on the confirmation thread, partly outside any catch: a throwing lockReleased callback or event
        // subscriber must not escape and end the scheduled confirmation.
        try {
            lock.markAsReleased();
        } catch (Exception e) {
            rethrowIfCriticalError(e);
            log.error(msg("[{}] Failure while marking lost lock '{}' as released", lockManagerInstanceId, lock.getName()), e);
        }
        try {
            notify(new LockReleased(lock, this));
        } catch (Exception e) {
            rethrowIfCriticalError(e);
            log.error(msg("[{}] Failure while notifying that lost lock '{}' was released", lockManagerInstanceId, lock.getName()), e);
        }
        return true;
    }

    /**
     * Second half of releasing lost locks (see {@link #releaseLostLockLocally(DBFencedLock)}): a best-effort release
     * in the DB, so another instance does not have to wait for <code>lockTimeOut</code>. The storage matches on lock
     * name and fence token, so this never releases a newer acquisition of the same lock.
     * <p>
     * Must be called after the confirmation {@link UnitOfWork} has completed, never inside it, so each release runs in a
     * {@link UnitOfWork} of its own. Inside it a release would join the confirmation {@link UnitOfWork}, and a failing
     * release would mark it rollback-only (on PostgreSQL the failed statement aborts the transaction anyway) - rolling back
     * every confirmation made in the same tick, although those locks are already marked confirmed and
     * {@link LockAcquired} notified.
     * <p>
     * Stops at the first IO failure: every further attempt against the unreachable DB would wait out the socket timeout(s)
     * too, while holding the confirmation thread and <code>reentrantLock</code>. The remaining rows expire after
     * <code>lockTimeOut</code>.
     *
     * @param lostLocks the lost locks, already released locally
     */
    private void releaseLostLocksInDB(List<LOCK> lostLocks) {
        for (var i = 0; i < lostLocks.size(); i++) {
            if (!releaseLostLockInDB(lostLocks.get(i))) {
                log.debug("[{}] Skipping DB release of the remaining {} lost lock(s) after an IO failure - their DB rows expire after lockTimeOut",
                          lockManagerInstanceId, lostLocks.size() - i - 1);
                return;
            }
        }
    }

    /**
     * Best-effort DB release of one lost lock in its own {@link UnitOfWork} - see {@link #releaseLostLocksInDB(List)}
     *
     * @param lock the lost lock, already released locally
     * @return false if the release failed with an IO exception (the DB is presumably unreachable), otherwise true
     */
    private boolean releaseLostLockInDB(LOCK lock) {
        var dbReachable = new AtomicBoolean(true);
        var releasedInDB = withUnitOfWork(uow -> lockStorage.releaseLockInDB(this, uow, lock),
                                          e -> {
                                              if (IOExceptionUtil.isIOException(e)) {
                                                  dbReachable.set(false);
                                                  log.debug(msg("[{}] Failed to release lost lock '{}' in DB", lockManagerInstanceId, lock.getName()), e);
                                              } else {
                                                  log.error(msg("[{}] Failed to release lost lock '{}' in DB", lockManagerInstanceId, lock.getName()), e);
                                              }
                                              return false;
                                          });
        log.debug("[{}] Lost lock '{}' {} in DB", lockManagerInstanceId, lock.getName(), releasedInDB ? "released" : "not released");
        return dbReachable.get();
    }

    /**
     * From here on nothing in this lock manager waits on the database for longer than the shutdown allows:
     * <ul>
     *     <li>the confirmation and async-acquiring ticks are cancelled, interrupting any that are blocked - each holds
     *     {@code reentrantLock} for its whole database round-trip, so a tick waiting out the connection timeout used to
     *     hold up every release queued behind it</li>
     *     <li>no new async acquiring is started, and ticks that still run return straight away</li>
     *     <li>a lock release is one {@link ShutdownContext#attemptCleanup bounded attempt}, skipped once the database has
     *     shown itself unreachable. The lock is dropped locally either way; its row expires after the lock timeout</li>
     * </ul>
     * Releases are called from other components' shutdown - an event processor stopping its subscription cancels its
     * lock acquiring - typically before this lock manager's own {@link #stop()}, which is why this cannot wait for it.
     */
    @Override
    public void shutdownStarting(ShutdownContext shutdown) {
        requireNonNull(shutdown, "No shutdown provided");
        this.shutdown = shutdown;
        // Deliberately without reentrantLock: the ticks being cancelled may be holding it
        var confirmation = confirmationScheduledFuture;
        if (confirmation != null) {
            confirmation.cancel(true);
        }
        asyncLockAcquirings.values().forEach(acquiring -> acquiring.cancel(true));
        log.debug("[{}] Shutdown starting - lock confirmation and async acquiring cancelled", lockManagerInstanceId);
    }

    private void releaseLockDuringShutdown(LOCK lock, ShutdownContext shutdown) {
        if (!locksAcquiredByThisLockManager.containsKey(lock.getName())) {
            return;
        }
        var released = shutdown.attemptCleanup(msg("[{}] release fenced lock '{}'", lockManagerInstanceId, lock.getName()), () -> {
            // Interruptibly, so the attempt's timeout also covers waiting for the lock
            reentrantLock.lockInterruptibly();
            try {
                unitOfWorkFactory.usingUnitOfWork(uow -> lockStorage.releaseLockInDB(this, uow, lock));
            } finally {
                reentrantLock.unlock();
            }
        });
        locksAcquiredByThisLockManager.remove(lock.getName());
        lock.markAsReleased();
        notify(new LockReleased(lock, this));
        log.debug("[{}] {} lock '{}' during shutdown", lockManagerInstanceId, released ? "Released" : "Dropped (left to expire)", lock.getName());
    }

    /**
     * Internal method only to be called by subclasses of {@link DBFencedLockManager} and {@link DBFencedLock}
     *
     * @param lock the lock to be released
     */
    protected void releaseLock(LOCK lock) {
        requireNonNull(lock, "No lock was provided");
        var shutdown = this.shutdown;
        if (shutdown != null) {
            releaseLockDuringShutdown(lock, shutdown);
            return;
        }
        if (locksAcquiredByThisLockManager.containsKey(lock.getName())) {
            log.debug("[{}] Releasing lock '{}': {}", lockManagerInstanceId, lock.getName(), lock);
            var releaseWithSuccess = withUnitOfWork(uow -> lockStorage.releaseLockInDB(this, uow, lock),
                                                    e -> {
                                                        if (IOExceptionUtil.isIOException(e)) {
                                                            log.debug("[{}] Failed to release lock '{}' in DB", lockManagerInstanceId, lock.getName(), e);
                                                        } else {
                                                            log.error("[{}] Failed to release lock '{}' in DB", lockManagerInstanceId, lock.getName(), e);
                                                        }
                                                        return false;
                                                    });
            log.trace("[{}] Removing {} DB released lock '{}' locally, marking and notifying it as released: {}",
                      lockManagerInstanceId, releaseWithSuccess ? "successfully" : "failed", lock.getName(), lock);
            locksAcquiredByThisLockManager.remove(lock.getName());
            lock.markAsReleased();
            notify(new LockReleased(lock, this));
            if (releaseWithSuccess) {
                log.debug("[{}] Released Lock '{}': {}", lockManagerInstanceId, lock.getName(), lock);
            } else {
                // We didn't release the lock after all, someone else acquired the lock in the meantime
                log.trace("[{}] Checking '{}' lock status in the DB",
                          lockManagerInstanceId, lock.getName());
                usingUnitOfWork(uow -> {
                    lockStorage.lookupLockInDB(this, uow, lock.getName()).ifPresent(lockAcquiredByAnotherLockManager -> {
                        log.debug("[{}] Post release of Lock '{}' DB reported current-owner status: {}", lockManagerInstanceId, lock.getName(), lockAcquiredByAnotherLockManager);
                    });
                }, e -> log.debug("[{}] Post release of Lock '{}' - failed to look-up in the DB which node has acquired the lock", lockManagerInstanceId, lock.getName(), e));
            }
            log.trace("[{}] Completed releasing lock '{}'",
                      lockManagerInstanceId, lock.getName());
        }

    }

    @Override
    public Optional<FencedLock> lookupLock(LockName lockName) {
        requireNonNull(lockName, "No lockName provided");

        if (!started) {
            throw new IllegalStateException(msg("The {} isn't started", this.getClass().getSimpleName()));
        }

        var fencedLock = withUnitOfWork(uow -> lockStorage.lookupLockInDB(this, uow, lockName).map(FencedLock.class::cast),
                                        e -> {
                                            log.trace("[{}] Failed to lookup lock '{}'", lockManagerInstanceId, lockName, e);
                                            return Optional.<FencedLock>empty();
                                        });
        log.trace("[{}] Lookup FencedLock with name '{}' result: {}",
                  lockManagerInstanceId,
                  lockName,
                  fencedLock);
        return fencedLock;
    }

    @Override
    public void stop() {
        if (started) {
            log.info("[{}] Stopping lock manager", lockManagerInstanceId);
            // Held across the whole teardown so that acquireLockAsync - which reads asyncLockAcquiringExecutor under
            // the same lock - either completes entirely before this and gets its scheduled future cancelled below, or
            // runs entirely after it and sees started == false. Interleaving the two is what produced a
            // NullPointerException on the caller's thread, and would otherwise leave a cancelled future stranded in
            // asyncLockAcquirings, which a later start() would then treat as a live acquiring and never re-schedule.
            reentrantLock.lock();
            try {
                stopping = true;
                if (confirmationScheduledFuture != null) {
                    log.debug("[{}] Stopping confirmationScheduledFuture",
                              lockManagerInstanceId);
                    confirmationScheduledFuture.cancel(true);
                    confirmationScheduledFuture = null;
                    log.debug("[{}] Stopped confirmationScheduledFuture",
                              lockManagerInstanceId);
                }

                locksAcquiredByThisLockManager.values().forEach(lock -> {
                    log.debug("[{}] Releasing acquired Lock '{}' due to Stopping",
                              lockManagerInstanceId,
                              lock.getName());
                    try {
                        lock.release();
                    } catch (Exception e) {
                        if (IOExceptionUtil.isIOException(e)) {
                            log.debug(msg("[{}] Failed to release FencedLock with name '{}'",
                                          lockManagerInstanceId,
                                          lock.getName()), e);
                        } else {
                            log.warn(msg("[{}] Failed to release FencedLock with name '{}'",
                                         lockManagerInstanceId,
                                         lock.getName()), e);
                        }
                    }
                });
                locksAcquiredByThisLockManager.clear();

                asyncLockAcquirings.forEach((lockName, scheduledFuture) -> {
                    log.debug("[{}] Cancelling acquiring of Lock '{}' due to Stopping",
                              lockManagerInstanceId,
                              lockName);

                    try {
                        scheduledFuture.cancel(true);
                    } catch (Exception e) {
                        if (IOExceptionUtil.isIOException(e)) {
                            log.debug(msg("[{}] Failed to stop acquiring of FencedLock with name '{}'",
                                          lockManagerInstanceId,
                                          lockName), e);
                        } else {
                            log.warn(msg("[{}] Failed to stop acquiring of FencedLock with name '{}'",
                                         lockManagerInstanceId,
                                         lockName), e);
                        }
                    }
                });
                asyncLockAcquirings.clear();

                if (asyncLockAcquiringExecutor != null) {
                    log.debug("[{}] Shutting down asyncLockAcquiringExecutor",
                              lockManagerInstanceId);
                    asyncLockAcquiringExecutor.shutdownNow();
                    asyncLockAcquiringExecutor = null;
                    log.debug("[{}] Shutdown asyncLockAcquiringExecutor",
                              lockManagerInstanceId);
                }
                if (lockConfirmationExecutor != null) {
                    log.debug("[{}] Shutting down lockConfirmationExecutor",
                              lockManagerInstanceId);

                    lockConfirmationExecutor.shutdownNow();
                    lockConfirmationExecutor = null;
                    log.debug("[{}] Shutdown lockConfirmationExecutor",
                              lockManagerInstanceId);
                }

                started = false;
                stopping = false;
                shutdown = null;
            } finally {
                reentrantLock.unlock();
            }
            log.info("[{}] Stopped lock manager", lockManagerInstanceId);
            notify(new FencedLockManagerStopped(this));
        } else {
            log.info("[{}] Lock Manager was already stopped", lockManagerInstanceId);
        }
    }


    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public Optional<FencedLock> tryAcquireLock(LockName lockName) {
        var result = _tryAcquireLock(lockName, NO_WAIT_LIMIT).block();
        return Optional.ofNullable(result);
    }

    /**
     * The timeout bounds the retries and the wait for this manager's internal lock (queued behind a lock confirmation, say), but
     * not a database round trip already in flight: a single attempt - the acquire, its commit and, if the commit failed, the
     * look-up of its outcome - can last as long as the connection timeout of an unreachable database. The call can therefore
     * return later than {@code timeout}.
     */
    @Override
    public Optional<FencedLock> tryAcquireLock(LockName lockName, Duration timeout) {
        requireNonNull(timeout, "No timeout value provided");
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var lock = _tryAcquireLock(lockName, Math.max(0L, deadline - System.nanoTime())).block();
            if (lock != null) {
                return Optional.of(lock);
            }
            var remainingMs = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
            if (remainingMs <= 0 || shutdown != null || !pauseBeforeNextAcquireAttempt(Math.min(remainingMs, syncAcquireLockPauseIntervalMs))) {
                return Optional.empty();
            }
        }
    }

    /**
     * The blocking acquires retry on the caller's thread, each attempt asking the DB anew. Not a Reactor
     * {@code repeatWhenEmpty}: {@link #_tryAcquireLock(LockName)} acquires eagerly, so repeating its {@link Mono} replays the
     * first attempt's outcome - a contended lock was then never acquired. Nor a Reactor timeout: one cancelling an attempt
     * still in flight would drop a lock that attempt went on to acquire, leaving it held with no caller to release it
     *
     * @return {@code false} if the caller's thread was interrupted while pausing (the interrupt is restored)
     */
    private boolean pauseBeforeNextAcquireAttempt(long pauseMs) {
        try {
            Thread.sleep(pauseMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * @param waitLimitNanos how long to wait for {@link #reentrantLock}, or {@link #NO_WAIT_LIMIT} to wait for as long as it takes
     */
    private Mono<LOCK> _tryAcquireLock(LockName lockName, long waitLimitNanos) {
        requireNonNull(lockName, "No lockName provided");
        if (!started) {
            throw new IllegalStateException(msg("The {} isn't started", this.getClass().getSimpleName()));
        }
        if (shutdown != null) {
            // Like acquireLockAsync and the async ticks: no new database work once shutdown has started
            log.debug("[{}] Shutting down - not attempting to acquire lock '{}'", lockManagerInstanceId, lockName);
            return Mono.empty();
        }

        log.debug("[{}] Attempting to acquire lock '{}'", lockManagerInstanceId, lockName);
        var alreadyAcquiredLock = locksAcquiredByThisLockManager.get(lockName);
        if (alreadyAcquiredLock != null && alreadyAcquiredLock.isLocked() && !isLockTimedOut(alreadyAcquiredLock)) {
            if (alreadyAcquiredLock.isLockedByThisLockManagerInstance()) {
                log.debug("[{}] Returned cached locally acquired lock '{}", lockManagerInstanceId, lockName);
                return Mono.just(alreadyAcquiredLock);
            } else {
                releaseLock(alreadyAcquiredLock);
            }
        }
        // Held across the acquire, its commit and recording the outcome locally, so stop() - which releases and clears
        // every locally recorded lock under the same lock - never runs in between. It can have run before, while this thread
        // waited for the lock: the state is therefore checked again once the lock is held
        if (!lockForAcquire(waitLimitNanos)) {
            return Mono.empty();
        }
        try {
            if (!started || stopping || shutdown != null) {
                log.debug("[{}] Lock manager stopped or shutting down while waiting to acquire lock '{}' - not acquiring it", lockManagerInstanceId, lockName);
                return Mono.empty();
            }
            // Set as soon as the acquire statement has succeeded, so a failure of the UnitOfWork after it - its commit -
            // is told apart from a failure to acquire
            var acquiredInUnitOfWork = new AtomicReference<LOCK>();
            var acquiredLock = withUnitOfWork(uow -> {
                var lock = lockStorage.lookupLockInDB(this, uow, lockName)
                                      .orElseGet(() -> lockStorage.createUninitializedLock(this, lockName));
                var acquired = resolveLock(uow, lock);
                acquiredInUnitOfWork.set(acquired);
                return acquired;
            }, e -> {
                if (acquiredInUnitOfWork.get() == null) {
                    log.debug("[{}] Failed to lookup lock '{}'", lockManagerInstanceId, lockName, e);
                } else {
                    log.debug("[{}] Failed to commit the acquiring of lock '{}' - checking whether it reached the DB", lockManagerInstanceId, lockName, e);
                }
                return null;
            });
            if (acquiredLock == null && acquiredInUnitOfWork.get() != null) {
                acquiredLock = resolveAcquireWithUnknownCommitOutcome(acquiredInUnitOfWork.get());
            }
            if (acquiredLock == null) {
                return Mono.empty();
            }
            // Only now that the acquire is known to be committed: recorded before the commit, a commit that then failed
            // left a lock held locally that the DB never gave this instance - see resolveAcquireWithUnknownCommitOutcome
            locksAcquiredByThisLockManager.put(acquiredLock.getName(), acquiredLock);
            notify(new LockAcquired(acquiredLock, this));
            return Mono.just(acquiredLock);
        } finally {
            reentrantLock.unlock();
        }
    }

    private static final long NO_WAIT_LIMIT = -1L;

    /**
     * @return true if {@link #reentrantLock} is now held by the calling thread; false if the wait limit passed or the thread was
     * interrupted while waiting (the interrupt is restored)
     */
    private boolean lockForAcquire(long waitLimitNanos) {
        if (waitLimitNanos == NO_WAIT_LIMIT) {
            reentrantLock.lock();
            return true;
        }
        try {
            return reentrantLock.tryLock(waitLimitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The acquire statement for {@code attemptedLock} succeeded, but the {@link UnitOfWork} then failed - in practice its
     * commit: the connection was lost, or (MongoDB, whose driver fails on a pending interrupt) the acquiring thread was
     * interrupted, as when a shutdown cancels the async acquiring. Whether the acquire reached the DB is then unknown:
     * <ul>
     *     <li>if it did, the row is locked by this instance while nothing here knows it - never confirmed, never released,
     *     blocking every other instance, and this one, until the lock timeout</li>
     *     <li>if it did not, the lock must not be held locally: another instance may acquire the row with the very fence token
     *     attempted here, and a local lock carrying it would be released as "lost" at the next confirmation - by lock name
     *     and token, so releasing the other instance's lock</li>
     * </ul>
     * The row is looked up to decide: only a row locked by this instance with the token just issued means the acquire was
     * committed, and only then is the lock this instance's. A pending interrupt is set aside for the lookup and restored
     * afterwards. When the lookup fails as well the outcome stays unknown and the lock is not held; a row the acquire did
     * lock expires after the lock timeout.
     *
     * @param attemptedLock the lock as acquired by the acquire statement whose commit failed
     * @return {@code attemptedLock} if its acquire was committed, otherwise {@code null}
     */
    private LOCK resolveAcquireWithUnknownCommitOutcome(LOCK attemptedLock) {
        var interrupted = Thread.interrupted();
        try {
            var lockInDB = lookupLockAfterFailedCommit(attemptedLock);
            var committed = lockInDB.filter(lock -> Objects.equals(lock.getLockedByLockManagerInstanceId(), lockManagerInstanceId))
                                    .filter(lock -> Objects.equals(lock.getCurrentToken(), attemptedLock.getCurrentToken()))
                                    .isPresent();
            log.debug("[{}] Acquiring of lock '{}' with token {} {} although its commit failed", lockManagerInstanceId, attemptedLock.getName(), attemptedLock.getCurrentToken(),
                      committed ? "WAS committed - holding the lock" : "was not committed");
            return committed ? attemptedLock : null;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The look-up of {@link #resolveAcquireWithUnknownCommitOutcome(DBFencedLock)}. During shutdown it is one
     * {@link ShutdownContext#attemptCleanup bounded attempt}, skipped once the database is known to be unreachable - this thread
     * holds {@link #reentrantLock}, which every shutdown release waits for. A skipped or failed look-up reads as "not committed".
     */
    private Optional<LOCK> lookupLockAfterFailedCommit(LOCK attemptedLock) {
        var shutdown = this.shutdown;
        if (shutdown != null) {
            var lookedUp = new AtomicReference<Optional<LOCK>>(Optional.empty());
            shutdown.attemptCleanup(msg("[{}] look up lock '{}' after its acquiring failed to commit", lockManagerInstanceId, attemptedLock.getName()),
                                    () -> lookedUp.set(unitOfWorkFactory.withUnitOfWork(uow -> lockStorage.lookupLockInDB(this, uow, attemptedLock.getName()))));
            return lookedUp.get();
        }
        return withUnitOfWork(uow -> lockStorage.lookupLockInDB(this, uow, attemptedLock.getName()),
                              e -> {
                                  log.debug(msg("[{}] Failed to look up lock '{}' after its acquiring failed to commit - its row, if locked, expires after the lock timeout",
                                                lockManagerInstanceId, attemptedLock.getName()), e);
                                  return Optional.<LOCK>empty();
                              });
    }

    /**
     * Runs the acquire statement that fits the lock's state in the DB. Records nothing locally: the {@link UnitOfWork} has
     * yet to commit
     *
     * @return the lock as acquired, or {@code null} if it is held by another instance or another instance got there first
     */
    private LOCK resolveLock(UOW uow, LOCK existingLock) {
        requireNonNull(uow, "No uow provided");
        requireNonNull(existingLock, "No existingLock provided");

        if (existingLock.isLocked()) {
            if (existingLock.isLockedByThisLockManagerInstance()) {
                log.debug("[{}] Will try to confirm Lock as the DB reports the lock '{}' was already acquired by this JVM node: '{}'", lockManagerInstanceId, existingLock.getName(), existingLock);
            }
            if (isLockTimedOut(existingLock)) {
                // Timed out - let us acquire the lock and update timestamps/tokens
                var now = OffsetDateTime.now(Clock.systemUTC());
                var newLock = lockStorage.createInitializedLock(this,
                                                                existingLock.getName(),
                                                                existingLock.getCurrentToken() + 1L,
                                                                lockManagerInstanceId,
                                                                now,
                                                                now);
                log.debug("[{}] Found a TIMED-OUT lock '{}', that was acquired by Lock Manager '{}'. Will attempt to acquire the lock. Timed-out lock: {} - New lock: {}",
                          lockManagerInstanceId,
                          existingLock.getName(),
                          existingLock.getLockedByLockManagerInstanceId(),
                          existingLock,
                          newLock);
                return updateLock(uow, existingLock, newLock);
            } else {
                return null;
            }
        } else {
            if (Objects.equals(existingLock.getCurrentToken(), lockStorage.getUninitializedTokenValue())) {
                return insertLock(uow, existingLock);
            } else {
                var now = OffsetDateTime.now(Clock.systemUTC());
                var newLock = lockStorage.createInitializedLock(this,
                                                                existingLock.getName(),
                                                                existingLock.getCurrentToken() + 1L,
                                                                lockManagerInstanceId,
                                                                now,
                                                                now);
                log.debug("[{}] Found un-acquired lock '{}'. Have Acquired lock. Existing lock: {} - New lock: {}", lockManagerInstanceId, existingLock.getName(), existingLock, newLock);
                return updateLock(uow, existingLock, newLock);
            }
        }
    }

    private LOCK insertLock(UOW uow, LOCK initialLock) {
        requireNonNull(uow, "No uow provided");
        requireNonNull(initialLock, "No initialLock provided");
        var now = OffsetDateTime.now(Clock.systemUTC());
        var insertedSuccessfully = lockStorage.insertLockIntoDB(this,
                                                                uow,
                                                                initialLock,
                                                                now);
        if (insertedSuccessfully) {
            initialLock.markAsLocked(now, lockManagerInstanceId, lockStorage.getInitialTokenValue());
            log.debug("[{}] Acquired lock '{}' for the first time (insert): {}", lockManagerInstanceId, initialLock.getName(), initialLock);
            return initialLock;
        } else {
            // We didn't acquire the lock after all
            log.debug("[{}] Failed to acquire lock '{}' for the first time (insert)", lockManagerInstanceId, initialLock.getName());
            return null;
        }
    }


    private LOCK updateLock(UOW uow, LOCK timedOutLock, LOCK newLockReadyToBeAcquiredLocally) {
        requireNonNull(uow, "No uow provided");
        requireNonNull(timedOutLock, "No timedOutLock provided");
        requireNonNull(newLockReadyToBeAcquiredLocally, "No newLockReadyToBeAcquiredLocally provided");

        var updatedSuccessfully = lockStorage.updateLockInDB(this, uow, timedOutLock, newLockReadyToBeAcquiredLocally);

        if (updatedSuccessfully) {
            log.debug("[{}] Acquired lock '{}' (update): {}", lockManagerInstanceId, timedOutLock.getName(), newLockReadyToBeAcquiredLocally);
            newLockReadyToBeAcquiredLocally.markAsLocked(newLockReadyToBeAcquiredLocally.getLockAcquiredTimestamp(),
                                                         newLockReadyToBeAcquiredLocally.getLockedByLockManagerInstanceId(),
                                                         newLockReadyToBeAcquiredLocally.getCurrentToken());
            return newLockReadyToBeAcquiredLocally;
        } else {
            // We didn't acquire the lock after all
            log.debug("[{}] Didn't acquire timed out lock '{}', someone else acquired it or confirmed it in the mean time (update): {}",
                      lockManagerInstanceId,
                      timedOutLock.getName(),
                      lockStorage.lookupLockInDB(this, uow, timedOutLock.getName()));
            return null;
        }
    }

    private boolean isLockTimedOut(LOCK lock) {
        requireNonNull(lock, "No lock provided");
        var durationSinceLastConfirmation = lock.getDurationSinceLastConfirmation();
        return durationSinceLastConfirmation.compareTo(lockTimeOut) >= 1;
    }

    @Override
    public FencedLock acquireLock(LockName lockName) {
        while (true) {
            var lock = _tryAcquireLock(lockName, NO_WAIT_LIMIT).block();
            if (lock != null) {
                return lock;
            }
            if (shutdown != null) {
                throw new IllegalStateException(msg("[{}] Shutting down while waiting to acquire lock '{}'", lockManagerInstanceId, lockName));
            }
            if (!pauseBeforeNextAcquireAttempt(syncAcquireLockPauseIntervalMs)) {
                throw new IllegalStateException(msg("[{}] Interrupted while waiting to acquire lock '{}'", lockManagerInstanceId, lockName));
            }
        }
    }

    @Override
    public boolean isLockAcquired(LockName lockName) {
        var lock = withUnitOfWork(uow -> lockStorage.lookupLockInDB(this, uow, lockName),
                                  e -> {
                                      log.debug("[{}] Failed to lookup lock '{}'", lockManagerInstanceId, lockName, e);
                                      return Optional.<FencedLock>empty();
                                  });
        return lock.map(FencedLock::isLocked).orElse(false);
    }

    @Override
    public boolean isLockedByThisLockManagerInstance(LockName lockName) {
        var lock = withUnitOfWork(uow -> lockStorage.lookupLockInDB(this, uow, lockName),
                                  e -> {
                                      log.debug("[{}] Failed to lookup lock '{}'", lockManagerInstanceId, lockName, e);
                                      return Optional.<FencedLock>empty();
                                  });
        return lock.map(FencedLock::isLockedByThisLockManagerInstance).orElse(false);
    }

    @Override
    public boolean isLockAcquiredByAnotherLockManagerInstance(LockName lockName) {
        var lock = withUnitOfWork(uow -> lockStorage.lookupLockInDB(this, uow, lockName),
                                  e -> {
                                      log.debug("[{}] Failed to lookup lock '{}'", lockManagerInstanceId, lockName, e);
                                      return Optional.<FencedLock>empty();
                                  });
        return lock.map(value -> value.isLocked() && !value.isLockedByThisLockManagerInstance()).orElse(false);
    }

    @Override
    public void acquireLockAsync(LockName lockName, LockCallback lockCallback) {
        requireNonNull(lockName, "You must supply a lockName");
        requireNonNull(lockCallback, "You must supply a lockCallback");

        reentrantLock.lock();
        try {
            // Read the executor into a local under the same lock stop() holds while it shuts the executor down and
            // clears the field. Callers legitimately race a concurrent stop() - an exclusive event store subscription
            // being reset does stop()/start() around the reset, and a subscription manager shutting down in between
            // used to make this throw a NullPointerException on the caller's own (often non-pooled) thread.
            var executor = asyncLockAcquiringExecutor;
            if (!started || stopping || shutdown != null || executor == null) {
                log.debug("[{}] Lock Manager isn't started - ignoring async lock acquiring for lock '{}'", lockManagerInstanceId, lockName);
                return;
            }

            asyncLockAcquirings.computeIfAbsent(lockName, _lockName -> {
                log.debug("[{}] Starting async Lock acquiring for lock '{}'", lockManagerInstanceId, lockName);
                return executor.scheduleAtFixedRate(() -> asyncLockAcquiringTick(lockName, lockCallback),
                                                    0,
                                                    lockConfirmationInterval.toMillis(),
                                                    TimeUnit.MILLISECONDS);
            });
        } finally {
            reentrantLock.unlock();
        }
    }

    /**
     * A single tick of the background acquiring scheduled by {@link #acquireLockAsync(LockName, LockCallback)}: acquire
     * the lock if this instance doesn't hold it, or notice and report that another instance has taken it over.
     *
     * @param lockName     the lock being acquired
     * @param lockCallback the callback notified when the lock is acquired or lost
     */
    @SuppressWarnings("unchecked")
    private void asyncLockAcquiringTick(LockName lockName, LockCallback lockCallback) {
        try {
            reentrantLock.lock();
            if (!started || shutdown != null) {
                return;
            }
            var existingLock = locksAcquiredByThisLockManager.get(lockName);
            if (existingLock == null) {
                if (paused) {
                    log.info("[{}] Lock Manager is paused, skipping async acquiring for lock '{}'", lockManagerInstanceId, lockName);
                    return;
                }

                Optional<FencedLock> lock;
                try {
                    lock = tryAcquireLock(lockName);
                } catch (Exception e) {
                    log.error(msg("[{}] Technical error while performing tryAcquireLock for lock '{}'", lockManagerInstanceId, lockName), e);
                    return;
                }
                if (lock.isPresent()) {
                    log.debug("[{}] Async Acquired lock '{}'", lockManagerInstanceId, lockName);
                    var fencedLock = lock.get();
                    fencedLock.registerCallback(lockCallback);
                    locksAcquiredByThisLockManager.put(lockName, (LOCK) fencedLock);
                    try {
                        lockCallback.lockAcquired(fencedLock);
                    } catch (RuntimeException e) {
                        // The lock WAS acquired; it is the callback that failed. Keeping the lock would
                        // be the worst of both outcomes: this instance owns a lock it is not serving,
                        // no other instance can take it, and no later tick will ever call the callback
                        // again - the next tick finds the lock already held by this instance and takes
                        // neither branch below, so the failure is permanent and silent apart from one
                        // log line whose text says the acquisition failed, when it did not.
                        //
                        // Releasing turns that into something recoverable: the next tick tries again,
                        // and a cause that clears itself (a queue registered a moment later, a
                        // dependency that finished starting) is picked up without a restart. A cause
                        // that does not clear logs once per tick, which is the paced, visible failure
                        // a permanent one should be.
                        log.error(msg("[{}] Lock '{}' was acquired but its lockAcquired callback failed - releasing it "
                                      + "so the next attempt can retry rather than holding a lock nothing is serving",
                                      lockManagerInstanceId, lockName), e);
                        releaseLock((LOCK) fencedLock);
                    }
                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("[{}] Couldn't async Acquire lock '{}' as it is acquired by another Lock Manager instance: {}",
                                  lockManagerInstanceId, lockName, lookupLock(lockName));
                    }
                }
            } else if (!existingLock.isLockedByThisLockManagerInstance()) {
                log.debug("[{}] Noticed that lock '{}' isn't locked by this Lock Manager instance anymore. Releasing the lock",
                          lockManagerInstanceId,
                          lockName);
                locksAcquiredByThisLockManager.remove(lockName);
                lockCallback.lockReleased(existingLock);
            }
        } catch (Exception e) {
            log.error(msg("[{}] Technical error while trying to acquire lock '{}'", lockManagerInstanceId, lockName), e);
        } finally {
            reentrantLock.unlock();
        }
    }

    @Override
    public void cancelAsyncLockAcquiring(LockName lockName) {
        requireNonNull(lockName, "You must supply a lockName");
        var scheduledFuture = asyncLockAcquirings.remove(lockName);
        if (scheduledFuture != null) {
            log.debug("[{}] Canceling async Lock acquiring for lock '{}'", lockManagerInstanceId, lockName);
            scheduledFuture.cancel(true);
            var acquiredLock = locksAcquiredByThisLockManager.get(lockName);
            if (acquiredLock != null) {
                log.debug("[{}] Releasing Lock due to cancelling the lock acquiring '{}'", lockManagerInstanceId, lockName);
                acquiredLock.release();
            }
        }
    }

    @Override
    public String getLockManagerInstanceId() {
        return lockManagerInstanceId;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" +
                "lockManagerInstanceId='" + lockManagerInstanceId + '\'' +
                '}';
    }

    /**
     * Return the DB specific uninitialized value for a {@link FencedLock#getCurrentToken()} that has yet to be persisted for the very first time
     *
     * @return the DB specific uninitialized value for a {@link FencedLock#getCurrentToken()} that has yet to be persisted for the very first time
     */
    public Long getUninitializedTokenValue() {
        return lockStorage.getUninitializedTokenValue();
    }

    /**
     * Return the DB specific value for a {@link FencedLock#getCurrentToken()} that is being persisted the very first time
     *
     * @return the DB specific value for a {@link FencedLock#getCurrentToken()} that is being persisted the very first time
     */
    public long getInitialTokenValue() {
        return lockStorage.getInitialTokenValue();
    }

    /**
     * Force delete all locks in the Database (useful for e.g. integration tests) - use with caution!
     */
    public void deleteAllLocksInDB() {
        usingUnitOfWork(uow -> lockStorage.deleteAllLocksInDB(this, uow),
                e -> {
                    throw new UnitOfWorkException(msg("[{}] Failed to delete all Locks in the lock storage", lockManagerInstanceId), e);
                }
       );
    }

    /**
     * Retrieves all locks currently stored in the database.
     * <p>
     * This method performs the operation as part of a unit of work.
     *
     * @return a list of all locks found in the database
     * @throws UnitOfWorkException if an error occurs while performing the unit of work
     */
    public List<LOCK> getAllLocksInDB() {
        return withUnitOfWork(uow -> lockStorage.getAllLocksInDB(this, uow),
                e -> {
                    throw new UnitOfWorkException(msg("[{}] Failed to get all Locks in the lock storage", lockManagerInstanceId), e);
                }
        );
    }

    /**
     * Releases a lock in the database specified by the provided lock name.
     * <p>
     * This method performs the operation as part of a unit of work.
     *
     * @param lockName the name of the lock to be released in the database
     * @return {@code true} if the lock is successfully released, otherwise {@code false}
     * @throws UnitOfWorkException if an error occurs during the unit of work execution
     */
    public boolean releaseLockInDB(LockName lockName) {
        return withUnitOfWork(uow -> {
                    return lockStorage.lookupLockInDB(this, uow, lockName)
                            .map(lock -> lockStorage.releaseLockInDB(this, uow, lock))
                            .orElse(Boolean.FALSE);
                },
                e -> {
                    throw new UnitOfWorkException(msg("[{}] Failed to release Lock '{}' the lock storage", lockManagerInstanceId, lockName), e);
                }
        );
    }

    /**
     * Use a {@link UnitOfWork} to perform transactional changes
     *
     * @param unitOfWorkConsumer the consumer of the created {@link UnitOfWork}
     * @param onError            The consumer that consumes any exception caused by calling {@link UnitOfWorkFactory#usingUnitOfWork(CheckedConsumer)}
     */
    protected void usingUnitOfWork(CheckedConsumer<UOW> unitOfWorkConsumer, CheckedConsumer<Throwable> onError) {
        reentrantLock.lock();
        try {
            unitOfWorkFactory.usingUnitOfWork(unitOfWorkConsumer::accept);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            log.debug(msg("[{}] Technical error performing usingUnitOfWork", lockManagerInstanceId), e);
            try {
                onError.accept(e);
            } catch (Exception subException) {
                throw new UnitOfWorkException(msg("[{}] Technical error while handling onError related to a usingUnitOfWork call that failed with '{}'", lockManagerInstanceId, e.getMessage()), subException);
            }
        } finally {
            reentrantLock.unlock();
        }
    }

    /**
     * Use a {@link UnitOfWork} to perform transactional changes
     *
     * @param unitOfWorkFunction The function that consumes the created {@link UnitOfWork}
     * @param onError            The function that consumes any exception caused by calling {@link UnitOfWorkFactory#withUnitOfWork(CheckedFunction)}
     * @param <R>                the return value from the <code>unitOfWorkFunction</code>
     * @return the result of the calling the <code>unitOfWorkFunction</code>
     */
    protected <R> R withUnitOfWork(CheckedFunction<UOW, R> unitOfWorkFunction, CheckedFunction<Throwable, R> onError) {
        reentrantLock.lock();
        try {
            return unitOfWorkFactory.withUnitOfWork(unitOfWorkFunction::apply);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            log.debug(msg("[{}] Technical error performing withUnitOfWork", lockManagerInstanceId), e);
            try {
                return onError.apply(e);
            } catch (Exception subException) {
                throw new UnitOfWorkException(msg("[{}] Technical error handling onError related to a withUnitOfWork call that failed with '{}'", lockManagerInstanceId, e.getMessage()), subException);
            }
        } finally {
            reentrantLock.unlock();
        }
    }
}
