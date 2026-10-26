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

import static dk.trustworks.essentials.shared.Exceptions.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;


/**
 * Common super base class for implementing persistent/durable {@link FencedLockManager}'s
 *
 * <h2>Errors</h2>
 * One invariant governs every failure path: <b>before this lock manager stops confirming a lock it holds, it releases the
 * lock locally</b> - removes it from the locks it confirms and marks it as released, which calls its
 * {@link LockCallback#lockReleased(FencedLock)} callbacks, and publishes {@link LockReleased}. A lock this instance no longer
 * confirms must not go on reporting itself as held by this instance: its row expires after the lock timeout, and another
 * instance may then take the lock over while a holder here still believes it holds it. The local release holds whatever the
 * error, including when the release in the DB fails or is not attempted (the row then expires after the lock timeout). The
 * lock reports itself as released even when one of its <code>lockReleased</code> callbacks fails; one failing with a critical
 * error skips the callbacks after it and the {@link LockReleased} event.
 * <p>
 * On the lock confirmation, an async acquiring and {@link #stop()}, a failure of a <code>lockAcquired</code>/<code>lockReleased</code>
 * callback, a {@link FencedLockEvents} subscriber or the storage that is not a critical error is logged and handled like a
 * {@link RuntimeException}: what was running carries on.
 * A critical error ({@link dk.trustworks.essentials.shared.Exceptions#isCriticalError(Throwable)}, also when it is the cause,
 * or a suppressed exception, of the throwable thrown) is not recovered from. What it ends depends on the thread it reaches:
 * <ul>
 *     <li><b>the lock confirmation</b>: the critical error ends the scheduled lock confirmation. First every lock this instance
 *     still holds is released locally (not in the DB - the rows expire after the lock timeout), and the error is logged at
 *     ERROR, because the task's {@link ScheduledFuture} it escapes into is never read. Thrown inside the confirmation's
 *     {@link UnitOfWork}, it rolls back the confirmations made in that tick</li>
 *     <li><b>an async acquiring</b> ({@link #acquireLockAsync(LockName, LockCallback)}): the critical error ends the async
 *     acquiring of that lock, and is logged at ERROR first; when it came from <code>lockAcquired</code>, the lock is released
 *     before that - see {@link #acquireLockAsync(LockName, LockCallback)}</li>
 *     <li><b>the caller of {@link #stop()}</b>: the teardown is completed first - every lock gets its release, one whose
 *     release failed with a critical error is released locally, the async acquirings are cancelled, the executors shut down
 *     and {@link FencedLockEvents.FencedLockManagerStopped} published - and then the first critical error is thrown, with
 *     every later failure of a release added to it as suppressed</li>
 *     <li><b>the caller of {@link FencedLock#release()}</b> or of an acquire: the critical error is thrown to the caller</li>
 * </ul>
 * The state left behind:
 * <ul>
 *     <li>after the scheduled lock confirmation has ended, {@link #isStarted()} is still <code>true</code>, but this lock
 *     manager acquires no lock, since it could not confirm it - its row would expire after the lock timeout while its holder
 *     believed it held it: {@link #tryAcquireLock(LockName)} and {@link #tryAcquireLock(LockName, Duration)} return empty,
 *     {@link #acquireLock(LockName)} throws {@link IllegalStateException}, and the ticks of an async acquiring, one still
 *     running or one {@link #acquireLockAsync(LockName, LockCallback)} starts afterwards, do nothing, so its
 *     <code>lockAcquired</code> is not called. This lock manager does not recover from it on its own: the way back is to
 *     restart the application. A {@link #stop()} and {@link #start()} of the lock manager alone starts the lock confirmation
 *     again, so it acquires and confirms locks again, but {@link #stop()} drops every async acquiring and {@link #start()}
 *     schedules none of them again. A component that acquires its lock through
 *     {@link #acquireLockAsync(LockName, LockCallback)} when it starts - an event processor, an exclusive event store
 *     subscription, the scheduler; an inbox or an outbox when it starts consuming - is then not given its lock until it is
 *     stopped and started too, after the lock manager (an inbox or an outbox: call <code>startConsuming()</code> again,
 *     or <code>consume(messageConsumer)</code> after <code>stopConsuming()</code>, which drops its consumer); code of your
 *     own calls {@link #acquireLockAsync(LockName, LockCallback)} again after {@link #start()}</li>
 *     <li>after {@link #stop()} has thrown a critical error, the lock manager is stopped ({@link #isStarted()} is
 *     <code>false</code>) and can be started again</li>
 *     <li>an async acquiring ended by a critical error keeps its entry - see {@link #acquireLockAsync(LockName, LockCallback)}
 *     for how to restart it</li>
 * </ul>
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
    /**
     * Set, under {@code reentrantLock}, when the scheduled lock confirmation has ended (see the class javadoc, "Errors"), and
     * cleared by {@link #start()}. While set, this lock manager acquires no lock: it could not confirm it
     */
    private volatile boolean         confirmationEnded;

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
                confirmationEnded = false;
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
                    } catch (Throwable e) {
                        // A non-critical Error (from a LockAcquired event subscriber, say) is handled like a RuntimeException:
                        // escaping this loop, it would roll back the confirmations made in the tick and skip the locks after this one.
                        // A critical error does escape, and ends the scheduled confirmation once every held lock is released locally
                        rethrowIfCriticalError(e);
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
        } catch (Throwable e) {
            // Escaping, it ends the scheduled confirmation - see the class javadoc, "Errors". Recorded first, still under
            // reentrantLock, so no acquire made from here on gets a lock that would never be confirmed
            confirmationEnded = true;
            releaseHeldLocksLocallyAsTheConfirmationEnds(e);
            throw e;
        } finally {
            reentrantLock.unlock();
        }
    }

    /**
     * Called with what is about to escape the lock confirmation task - in practice a critical error
     * ({@link dk.trustworks.essentials.shared.Exceptions#isCriticalError(Throwable)}), which the confirmation loop and its
     * helpers rethrow: a task scheduled at a fixed rate that throws is never run again, so no lock this instance holds would be
     * confirmed again. Each lock is released locally ({@link #releaseLostLockLocally(DBFencedLock)}), so it stops reporting
     * itself as held by this instance before its row can expire and another instance take it over. Not in the DB: the rows
     * expire after <code>lockTimeOut</code>. A failure of one local release is added to <code>failure</code> as suppressed and
     * the next lock released all the same. <code>failure</code> is logged at ERROR afterwards, because the task's
     * {@link ScheduledFuture} it escapes into is never read.
     *
     * @param failure the failure that ends the scheduled confirmation
     */
    private void releaseHeldLocksLocallyAsTheConfirmationEnds(Throwable failure) {
        var heldLocks = List.copyOf(locksAcquiredByThisLockManager.values());
        heldLocks.forEach(lock -> {
            try {
                releaseLostLockLocally(lock);
            } catch (Throwable localReleaseFailure) {
                addSuppressedOnce(failure, localReleaseFailure);
            }
        });
        log.error(msg("[{}] The lock confirmation failed with {} - released the {} lock(s) this lock manager instance held locally only "
                      + "(their rows expire after the lock timeout) and the scheduled lock confirmation has ended: this lock manager "
                      + "acquires no lock from now on (tryAcquireLock returns empty, acquireLock throws, async acquiring acquires nothing), "
                      + "until the application is restarted (or the lock manager is stopped and started, and after it every component "
                      + "that acquires a lock through it with acquireLockAsync, which the lock manager's stop drops)",
                      lockManagerInstanceId, isCriticalError(failure) ? "a critical error" : "an unexpected failure", heldLocks.size()), failure);
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
        // subscriber must not escape and end the scheduled confirmation. A non-critical Error (an AssertionError, say)
        // is handled like a RuntimeException: escaping the onError handler of the confirmation UnitOfWork, it would end
        // the scheduled confirmation; escaping the confirmation loop, it would roll back the confirmations made in the tick
        // and skip the DB release of this lock (and, from markAsReleased, its LockReleased event).
        try {
            lock.markAsReleased();
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            log.error(msg("[{}] Failure while marking lost lock '{}' as released", lockManagerInstanceId, lock.getName()), e);
        }
        try {
            notify(new LockReleased(lock, this));
        } catch (Throwable e) {
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

    /**
     * Marks a lock this instance has removed locally as released (which calls its <code>lockReleased</code> callbacks) and
     * publishes {@link LockReleased} for it - the latter even when a callback throws, so a failing callback cannot leave
     * the release unannounced. The first failure, of a callback or of publishing the event, is rethrown afterwards, with a
     * later one added to it as suppressed. A critical error ({@link dk.trustworks.essentials.shared.Exceptions#isCriticalError(Throwable)}),
     * of either step, is rethrown at once in place of any earlier failure, which is added to it as suppressed: one from the
     * callbacks also skips publishing the event.
     *
     * @param lock the lock released by this instance
     */
    private void markAsReleasedAndNotify(LOCK lock) {
        forEachThenThrowFirst(List.<Runnable>of(lock::markAsReleased,
                                                () -> notify(new LockReleased(lock, this))),
                              Runnable::run);
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
        markAsReleasedAndNotify(lock);
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
            markAsReleasedAndNotify(lock);
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
    @SuppressWarnings("PMD.CloseResource") // A held lock's close() is its release(), which the teardown calls for every lock
    public void stop() {
        if (started) {
            log.info("[{}] Stopping lock manager", lockManagerInstanceId);
            // Held across the whole teardown so that acquireLockAsync - which reads asyncLockAcquiringExecutor under
            // the same lock - either completes entirely before this and gets its scheduled future cancelled below, or
            // runs entirely after it and sees started == false. Interleaving the two is what produced a
            // NullPointerException on the caller's thread, and would otherwise leave a cancelled future stranded in
            // asyncLockAcquirings, which a later start() would then treat as a live acquiring and never re-schedule.
            // The first critical error releasing a lock, rethrown once the teardown is complete - see the class javadoc, "Errors"
            Throwable criticalError = null;
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

                for (var lock : locksAcquiredByThisLockManager.values()) {
                    log.debug("[{}] Releasing acquired Lock '{}' due to Stopping",
                              lockManagerInstanceId,
                              lock.getName());
                    try {
                        lock.release();
                    } catch (Throwable e) {
                        // No failure ends the teardown: escaping, it would leave the locks after this one held and this lock
                        // manager started, with its async lock acquiring still running. A non-critical Error from a lockReleased
                        // callback or a LockReleased event subscriber (an AssertionError, say) is handled like a RuntimeException.
                        // A critical error is kept and rethrown at the end, with every later failure added to it as suppressed
                        // instead of logged, and each lock whose release failed from then on is released locally: a critical error from releaseLockInDB,
                        // say, escapes releaseLock before it removes the lock and marks it as released
                        if (criticalError != null || isCriticalError(e)) {
                            if (criticalError == null) {
                                criticalError = e;
                            } else {
                                addSuppressedOnce(criticalError, e);
                            }
                            releaseLocallyAfterFailedRelease(lock.getName(), lock, criticalError);
                            continue;
                        }
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
                }
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
            if (criticalError == null) {
                notify(new FencedLockManagerStopped(this));
                return;
            }
            // The lock manager is stopped, so the event is published before the critical error is rethrown - a failure
            // publishing it is added to that error rather than thrown in its place
            try {
                notify(new FencedLockManagerStopped(this));
            } catch (Throwable e) {
                addSuppressedOnce(criticalError, e);
            }
            sneakyThrow(criticalError);
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
            if (remainingMs <= 0 || shutdown != null || confirmationEnded || !pauseBeforeNextAcquireAttempt(Math.min(remainingMs, syncAcquireLockPauseIntervalMs))) {
                return Optional.empty();
            }
        }
    }

    /**
     * The blocking acquires retry on the caller's thread, each attempt asking the DB anew. Not a Reactor
     * {@code repeatWhenEmpty}: {@link #_tryAcquireLock(LockName, long)} acquires eagerly, so repeating its {@link Mono} replays the
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
        log.debug("[{}] Attempting to acquire lock '{}'", lockManagerInstanceId, lockName);
        var alreadyAcquiredLock = locksAcquiredByThisLockManager.get(lockName);
        var alreadyAcquiredLockIsValid = alreadyAcquiredLock != null && alreadyAcquiredLock.isLocked() && !isLockTimedOut(alreadyAcquiredLock);
        if (alreadyAcquiredLockIsValid && alreadyAcquiredLock.isLockedByThisLockManagerInstance()) {
            // Returned during shutdown as well: it needs no database work, and the lock is still this manager's until
            // shutdown releases it
            log.debug("[{}] Returned cached locally acquired lock '{}'", lockManagerInstanceId, lockName);
            return Mono.just(alreadyAcquiredLock);
        }
        if (shutdown != null) {
            // Like acquireLockAsync and the async ticks: no new database work once shutdown has started
            log.debug("[{}] Shutting down - not attempting to acquire lock '{}'", lockManagerInstanceId, lockName);
            return Mono.empty();
        }
        if (confirmationEnded) {
            logNotAcquiringAsTheConfirmationHasEnded(lockName);
            return Mono.empty();
        }
        if (alreadyAcquiredLockIsValid) {
            releaseLock(alreadyAcquiredLock);
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
            if (confirmationEnded) {
                logNotAcquiringAsTheConfirmationHasEnded(lockName);
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

    private void logNotAcquiringAsTheConfirmationHasEnded(LockName lockName) {
        log.debug("[{}] The scheduled lock confirmation has ended - not acquiring lock '{}', which this lock manager could not confirm, "
                  + "until the application is restarted, or the lock manager is stopped and started", lockManagerInstanceId, lockName);
    }

    private void logAsyncAcquiringNotAcquiringAsTheConfirmationHasEnded(LockName lockName) {
        log.debug("[{}] The scheduled lock confirmation has ended - the async acquiring of lock '{}' is not acquiring it, as this lock manager "
                  + "could not confirm it, until the application is restarted: a stop and start of the lock manager drops this async acquiring, "
                  + "so its component must be stopped and started too (or acquireLockAsync called again) after it", lockManagerInstanceId, lockName);
    }

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
            if (confirmationEnded) {
                throw new IllegalStateException(msg("[{}] The scheduled lock confirmation has ended - not acquiring lock '{}', which this lock manager "
                                                    + "could not confirm, until the application is restarted, or the lock manager is stopped and started",
                                                    lockManagerInstanceId, lockName));
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

    /**
     * {@inheritDoc}
     * <p>
     * When the {@link LockCallback#lockAcquired(FencedLock)} callback throws - a {@link RuntimeException}, a non-critical
     * {@link Error} or a checked exception thrown sneakily - the failure is logged and the lock released (which calls
     * {@link LockCallback#lockReleased(FencedLock)}), and a later tick tries to acquire it again - this instance or another
     * may get it; when this one does, the callback is called again.
     * The same failure from <code>lockReleased</code>, when the async acquiring finds the lock lost, is logged and the acquiring
     * carries on.
     * <p>
     * A critical error ({@link dk.trustworks.essentials.shared.Exceptions#isCriticalError(Throwable)} - a
     * {@link VirtualMachineError}, {@link ThreadDeath} or {@link LinkageError} (an {@link ExceptionInInitializerError} or
     * {@link NoClassDefFoundError}, say), or any throwable with one in its cause or suppressed chain) is not recovered from:
     * it is logged at ERROR and ends the async acquiring of the lock.
     * When it came from <code>lockAcquired</code>, the lock is released before that, so it is not left held by this
     * instance with nothing serving it.
     * When that release, or the release after a non-critical failure of <code>lockAcquired</code>, fails with a critical error
     * in the DB, the lock is still released locally: this lock manager stops confirming it, so its row expires after the lock
     * timeout, and <code>lockReleased</code> is called and {@link FencedLockEvents.LockReleased} published, so what
     * <code>lockAcquired</code> started can be stopped before another instance takes the lock over.
     * <p>
     * That end is deliberate, and nothing but the ERROR log line says the acquiring has ended. A critical error is not
     * retried, because a retry is not expected to succeed (a class whose initialiser failed stays unusable in its class
     * loader, say), and it is rethrown from the acquiring task ({@link dk.trustworks.essentials.shared.Exceptions#rethrowIfCriticalError(Throwable)}),
     * as critical errors are throughout this lock manager (see the class javadoc, "Errors", for what one ends on each of its
     * threads and the state it leaves behind). The task runs on this lock manager's own executor, so the error
     * ends up in the task's {@link ScheduledFuture}, which nothing reads: no {@link FencedLockEvents} event says the
     * acquiring has ended (the {@link FencedLockEvents.LockReleased} published when the error came from
     * <code>lockAcquired</code> and the lock was released first looks like any other release), and none of
     * {@link #isLockAcquired(LockName)}, {@link #isLockedByThisLockManagerInstance(LockName)} and
     * {@link #isLockAcquiredByAnotherLockManagerInstance(LockName)} tells an ended acquiring apart from one still waiting
     * for the lock.
     * The ended acquiring keeps its entry, so calling this method again for the same lock name does not restart it. To
     * restart it, call {@link #cancelAsyncLockAcquiring(LockName)} and then this method, or stop and start the lock manager
     * and then call this method again: {@link #stop()} drops every async acquiring, and {@link #start()} schedules none of
     * them again.
     */
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
        var criticalErrorLogged = false;
        try {
            reentrantLock.lock();
            if (!started || shutdown != null) {
                return;
            }
            if (confirmationEnded) {
                logAsyncAcquiringNotAcquiringAsTheConfirmationHasEnded(lockName);
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
                    } catch (Throwable e) {
                        // A critical error (see Exceptions.isCriticalError) is not recovered from here - it escapes the tick,
                        // which ends the scheduled acquiring of the lock. The lock is released first, so it is not left held
                        // and confirmed by this instance with nothing serving it.
                        // A non-critical Error (an AssertionError, say) and a checked exception thrown sneakily (undeclared)
                        // are handled like a RuntimeException: unhandled here, either would reach the outer catch, which only
                        // logs it, and the lock would stay held by this instance with no later tick calling the callback again.
                        if (isCriticalError(e)) {
                            releaseLockAfterCriticalError(lockName, (LOCK) fencedLock, e);
                            criticalErrorLogged = true;
                            sneakyThrow(e);
                        }
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
                        releaseAfterFailedLockAcquired(lockName, (LOCK) fencedLock);
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
        } catch (Throwable e) {
            // A non-critical Error or a checked exception thrown sneakily (from a lockReleased callback, say) must not escape
            // either: a task scheduled at a fixed rate that throws is never run again, which would end the acquiring of this
            // lock for good.
            // A critical error does escape, ending the acquiring of this lock. It is logged here first, because what it escapes
            // into is the task's ScheduledFuture, which nothing reads - unless it came from lockAcquired and was logged there.
            if (!criticalErrorLogged && isCriticalError(e)) {
                log.error(msg("[{}] A critical error occurred in the async acquiring of lock '{}' - this ends the async acquiring of the lock",
                              lockManagerInstanceId, lockName), e);
            }
            rethrowIfCriticalError(e);
            log.error(msg("[{}] Technical error while trying to acquire lock '{}'", lockManagerInstanceId, lockName), e);
        } finally {
            reentrantLock.unlock();
        }
    }

    /**
     * Releases a lock whose <code>lockAcquired</code> callback failed with a non-critical error, so the next attempt of the
     * async acquiring can retry. A failure of the release is rethrown as it is: the <code>lockAcquired</code> failure has been
     * logged by the caller already and is not added to it.
     * <p>
     * A critical error from the release itself (<code>releaseLockInDB</code>, say) escapes before {@link #releaseLock} removes
     * the lock locally, and ends the async acquiring in the caller's outer catch. Releasing it locally here first
     * ({@link #releaseLocallyAfterFailedRelease(LockName, DBFencedLock, Throwable)}) stops this lock manager confirming it, so
     * the lock row expires after the lock timeout rather than staying held unserved, and calls <code>lockReleased</code>, so
     * what <code>lockAcquired</code> started is stopped before another instance can take the lock over.
     *
     * @param lockName the name of the lock
     * @param lock     the lock that was acquired
     */
    private void releaseAfterFailedLockAcquired(LockName lockName, LOCK lock) {
        try {
            releaseLock(lock);
        } catch (Throwable releaseFailure) {
            releaseLocallyAfterFailedRelease(lockName, lock, releaseFailure);
            throw releaseFailure;
        }
    }

    /**
     * Best effort release of a lock whose <code>lockAcquired</code> callback failed with a critical error
     * ({@link dk.trustworks.essentials.shared.Exceptions#isCriticalError(Throwable)}), which the caller then rethrows, ending
     * the async acquiring of the lock. Without the release the lock would stay in {@link #locksAcquiredByThisLockManager}, so it
     * would keep being confirmed - no other instance could take it over - while nothing served it.
     * <p>
     * The lock is released locally even when the release throws before it got that far (a critical error from
     * <code>releaseLockInDB</code>, say) - see {@link #releaseLocallyAfterFailedRelease(LockName, DBFencedLock, Throwable)}: it is
     * no longer confirmed, so the lock row expires after the lock timeout, and its <code>lockReleased</code> callbacks run and
     * {@link LockReleased} is published. A release that throws (its <code>lockReleased</code> callback, say) is added to
     * <code>criticalError</code> as suppressed, never thrown in its place, and so is a failure of that local release; the error
     * is logged at ERROR afterwards, so the log line carries those failures too. A release that fails in the DB without throwing
     * is logged by {@link #releaseLock} itself, and leaves the lock row to expire after the lock timeout.
     *
     * @param lockName      the name of the lock
     * @param lock          the lock that was acquired
     * @param criticalError the critical error the <code>lockAcquired</code> callback failed with
     */
    private void releaseLockAfterCriticalError(LockName lockName, LOCK lock, Throwable criticalError) {
        try {
            releaseLock(lock);
        } catch (Throwable releaseFailure) {
            addSuppressedOnce(criticalError, releaseFailure);
            releaseLocallyAfterFailedRelease(lockName, lock, criticalError);
        }
        log.error(msg("[{}] Lock '{}' was acquired but its lockAcquired callback failed with a critical error - the lock has been released "
                      + "(or, where its release in the DB failed, released locally only, so its row expires after the lock timeout) "
                      + "and the error is rethrown, which ends the async acquiring of the lock",
                      lockManagerInstanceId, lockName), criticalError);
    }

    /**
     * Completes, locally, the release of a lock whose {@link #releaseLock} threw, so the lock is not left half released: a
     * failure that escapes {@link #releaseLock} before it removes the lock from {@link #locksAcquiredByThisLockManager} (a
     * critical error from <code>releaseLockInDB</code>, say) would otherwise leave it confirmed by this instance, or - removed
     * but never marked released - reporting itself as held by this instance, with its <code>lockReleased</code> callbacks never
     * run and no {@link LockReleased} published, while another instance takes it over once its row has expired.
     * <p>
     * Removes the lock from {@link #locksAcquiredByThisLockManager}, which stops its confirmation (the lock row expires after the
     * lock timeout), and, when the lock still reports itself as held by this instance, marks it as released and notifies
     * {@link LockReleased} ({@link #markAsReleasedAndNotify(DBFencedLock)}). A lock {@link #releaseLock} already marked as
     * released (its <code>lockReleased</code> callback threw, say) is not notified again. A failure of this local release,
     * critical or not, is added to <code>releaseFailure</code> as suppressed, never thrown in its place.
     * <p>
     * The remove goes by identity, not by {@link ConcurrentMap#remove(Object, Object)}: {@link DBFencedLock#equals(Object)}
     * compares the lock name only, so that would also remove a newer acquisition of the same lock - one a
     * <code>lockReleased</code> callback made on this thread through {@link #tryAcquireLock(LockName)}, say - which would then
     * no longer be confirmed and would expire while its holder believes it holds it.
     *
     * @param lockName       the name of the lock
     * @param lock           the lock whose release threw
     * @param releaseFailure the failure that escapes to the caller, which any failure of the local release is added to as suppressed
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private void releaseLocallyAfterFailedRelease(LockName lockName, LOCK lock, Throwable releaseFailure) {
        locksAcquiredByThisLockManager.computeIfPresent(lockName, (name, held) -> held == lock ? null : held);
        if (!lock.isLockedByThisLockManagerInstance()) {
            return;
        }
        try {
            markAsReleasedAndNotify(lock);
        } catch (Throwable localReleaseFailure) {
            addSuppressedOnce(releaseFailure, localReleaseFailure);
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
