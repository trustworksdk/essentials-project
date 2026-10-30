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
import java.util.function.Consumer;

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
 * Every path carries that local release out the same way. It removes the {@link FencedLock} instance it releases, never a
 * newer acquisition of the same lock name ({@link DBFencedLock#equals(Object)} compares the name only), and only the call
 * that removes the lock marks it as released and publishes {@link LockReleased}: a lock two paths release at once (a
 * {@link FencedLock#release()} waiting while the lock confirmation ends, say) has its <code>lockReleased</code> callbacks
 * called and its {@link LockReleased} published once. A {@link FencedLock#release()} of an instance this lock manager does
 * not hold - one lost and since acquired again as a new instance, or the copy {@link #lookupLock(LockName)} reads from the
 * DB - releases nothing. The paths differ only in what
 * becomes of a failure of the callbacks or of publishing the event, as described below. The async acquiring's tick, finding
 * a lock it holds no longer reporting itself as held by this instance, removes it the same way; the lock is already marked
 * as released, so it is not marked again and no {@link LockReleased} is published for it, and the tick calls only the
 * acquiring's own <code>lockReleased</code>.
 * <p>
 * On the lock confirmation, an async acquiring and {@link #stop()}, a failure of a <code>lockAcquired</code>/<code>lockReleased</code>
 * callback, a {@link FencedLockEvents} subscriber or the storage that is not a critical error is logged and handled like a
 * {@link RuntimeException}: what was running carries on.
 * A critical error ({@link dk.trustworks.essentials.shared.Exceptions#isCriticalError(Throwable)}, also when it is the cause,
 * or a suppressed exception, of the throwable thrown) is not recovered from. What it ends depends on the thread it reaches:
 * <ul>
 *     <li><b>the lock confirmation</b>: the critical error ends the scheduled lock confirmation. First every lock this instance
 *     still holds is released locally (not in the DB - the rows expire after the lock timeout), the error is logged at
 *     ERROR, because the task's {@link ScheduledFuture} it escapes into is never read, and
 *     {@link FencedLockEvents.LockConfirmationEnded} is published. Thrown inside the confirmation's
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
 *     <li>after the scheduled lock confirmation has ended, {@link #isStarted()} is still <code>true</code> and
 *     {@link #isLockConfirmationEnded()} (also on {@link FencedLockManager}) returns <code>true</code>, and
 *     {@link FencedLockEvents.LockConfirmationEnded} has been published, so code (a liveness probe or a subscriber, say) can
 *     tell this state, but
 *     this lock manager acquires no lock, since it could not confirm it - its row would expire after the lock timeout while its holder
 *     believed it held it: {@link #tryAcquireLock(LockName)} and {@link #tryAcquireLock(LockName, Duration)} return empty,
 *     {@link #acquireLock(LockName)} throws {@link IllegalStateException}, and the ticks of an async acquiring, one still
 *     running or one {@link #acquireLockAsync(LockName, LockCallback)} starts afterwards, do nothing, so its
 *     <code>lockAcquired</code> is not called. The first refused acquire of each lock, and the first refused tick of each
 *     async acquiring, is logged at WARN, later ones at DEBUG. This lock manager does not recover from it on its own: the way back is to
 *     restart the application, or stop and start the lock manager ({@link #stop()}, then {@link #start()}), which starts
 *     the lock confirmation again and resumes every async acquiring not cancelled with
 *     {@link #cancelAsyncLockAcquiring(LockName)}. No Spring Boot starter registers a health indicator for this state:
 *     reporting it <code>DOWN</code> would let a liveness probe carry out the restart, but a probe must not restart pods by
 *     default, so such an indicator needs an opt-in property, like the opt-in dead-letter threshold of the
 *     <code>DurableQueuesHealthIndicator</code>, and none exists for this state yet. An application that wants one calls
 *     {@link FencedLockManager#isLockConfirmationEnded()} from its own indicator</li>
 *     <li>after {@link #stop()} has thrown a critical error, the lock manager is stopped ({@link #isStarted()} is
 *     <code>false</code>) and can be started again</li>
 *     <li>an async acquiring ended by a critical error keeps its entry - see {@link #acquireLockAsync(LockName, LockCallback)}
 *     for how to restart it</li>
 * </ul>
 *
 * <h2>Async acquiring across a stop and start</h2>
 * {@link #stop()} keeps the registration of every async acquiring (the lock name and its {@link LockCallback}), and
 * {@link #start()} resumes each with the callback it holds, logging each resumed registration at WARN with the lock name and the
 * callback's class. <b>A component must therefore call {@link #cancelAsyncLockAcquiring(LockName)} in its own stop</b>: one
 * discarded without it has its registration resumed by the next {@link #start()}, and its callback is given the lock again
 * with nothing serving it - and while this instance confirms the lock, no other instance takes it over. See
 * {@link #acquireLockAsync(LockName, LockCallback)}.
 *
 * @param <UOW>  the type of {@link UnitOfWork} required
 * @param <LOCK> the concrete type of {@link DBFencedLock} used
 */
public abstract class DBFencedLockManager<UOW extends UnitOfWork, LOCK extends DBFencedLock> implements FencedLockManager, ShutdownAware {
    /**
     * What {@link #start()} puts between the lock manager instance id and the thread number in the name of each thread of the
     * async acquiring executor (<code>&lt;lockManagerInstanceId&gt;-Lock-Acquiring-&lt;n&gt;</code>). Package-private so
     * tests can find those threads by the same name
     */
    static final String ASYNC_LOCK_ACQUIRING_THREAD_NAME_INFIX = "-Lock-Acquiring-";

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    private final FencedLockStorage<UOW, LOCK>                lockStorage;
    /**
     * Entries only exist if this lock manager instance believes that it has acquired the given lock<br>
     * Key: Lock name<br>
     * Value: The acquired {@link DBFencedLock}
     */
    private final ConcurrentMap<LockName, LOCK>               locksAcquiredByThisLockManager;
    /**
     * One entry per async acquiring registered with {@link #acquireLockAsync(LockName, LockCallback)} and not yet cancelled
     * with {@link #cancelAsyncLockAcquiring(LockName)}. {@link #stop()} cancels each entry's scheduled future but keeps the
     * entry, and {@link #start()} schedules every kept entry again, as a new entry<br>
     * Key: lock name<br>
     * Value: the registration's {@link LockCallback}, its <code>keptByStop</code> flag (see {@link AsyncLockAcquiring#keptByStop}),
     * and its current {@link ScheduledFuture}
     */
    private final ConcurrentMap<LockName, AsyncLockAcquiring> asyncLockAcquirings;
    private final Duration                                    lockTimeOut;
    /**
     * See {@link LockSpreading}. When enabled, {@link #asyncLockAcquiringTick} asks {@link #mayAcquireUnderLockSpreading}
     * before it acquires a free lock
     */
    private final LockSpreading                               lockSpreading;
    /**
     * When this instance, not being a lock's preferred owner, first saw the lock free (by {@link System#nanoTime()}).
     * Only used while {@link #lockSpreading} is enabled
     */
    private final ConcurrentMap<LockName, Long>               seenFreeByNonPreferredOwnerSinceNanos = new ConcurrentHashMap<>();
    /**
     * The live lock manager instances last read from the membership locks, and when. Only used while
     * {@link #lockSpreading} is enabled
     */
    private volatile LockSpreadingMembership                  lockSpreadingMembership;
    /**
     * When this instance may next release a lock to its preferred owner (by {@link System#nanoTime()}): one lock per
     * {@link LockSpreading#rebalanceInterval()} for the whole lock manager. Only used while {@link #lockSpreading} rebalances
     */
    private final AtomicLong                                  nextRebalanceAtNanos                  = new AtomicLong();
    /**
     * Locks this instance took back after the grace because their preferred owner did not take them, mapped to the
     * membership they were taken back under: not released again until the membership changes. A lock whose preferred owner
     * never asks for it (or is paused) would otherwise be moved back and forth for good - a time-based suppression was
     * tried and churned, as releasing one lock per interval cycles through the suppressed ones faster than they expire
     */
    private final ConcurrentMap<LockName, SortedSet<String>>  rebalanceSuppressedForMembership      = new ConcurrentHashMap<>();
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
    /**
     * The lock names an acquire ({@link #tryAcquireLock(LockName)}, {@link #tryAcquireLock(LockName, Duration)},
     * {@link #acquireLock(LockName)}) has been refused for, and logged at WARN, since the scheduled lock confirmation ended -
     * later refusals for the same name are logged at DEBUG. Cleared by {@link #start()} and when the confirmation ends
     */
    private final Set<LockName>      acquiresRefusedAsTheConfirmationHasEnded        = ConcurrentHashMap.newKeySet();
    /**
     * The lock names whose async acquiring ({@link #acquireLockAsync(LockName, LockCallback)}) has had a tick refused, and
     * logged at WARN, since the scheduled lock confirmation ended - its later ticks are logged at DEBUG. Cleared by {@link #start()}
     * and when the confirmation ends
     */
    private final Set<LockName>      asyncAcquiringsRefusedAsTheConfirmationHasEnded = ConcurrentHashMap.newKeySet();

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
        this.lockSpreading = settings.lockSpreading();
        this.eventBus = eventBus;

        locksAcquiredByThisLockManager = new ConcurrentHashMap<>();
        asyncLockAcquirings = new ConcurrentHashMap<>();


        log.info("[{}] Initializing '{}' using storage '{}' and lockConfirmationInterval: {} ms, lockTimeOut: {} ms",
                 this.lockManagerInstanceId,
                 this.getClass().getName(),
                 lockStorage.getClass().getName(),
                 lockConfirmationInterval.toMillis(),
                 lockTimeOut.toMillis());
        if (lockSpreading.enabled()) {
            log.info("[{}] Spreading async acquired locks over the live lock manager instances, acquireGrace: {} ms",
                     this.lockManagerInstanceId,
                     lockSpreading.acquireGrace().toMillis());
        }
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
                acquiresRefusedAsTheConfirmationHasEnded.clear();
                asyncAcquiringsRefusedAsTheConfirmationHasEnded.clear();
                lockConfirmationExecutor = Executors.newScheduledThreadPool(1,
                                                                            new ThreadFactoryBuilder()
                                                                                    .nameFormat(lockManagerInstanceId + "-FencedLock-Confirmation-%d")
                                                                                    .daemon(true)
                                                                                    .build());

                asyncLockAcquiringExecutor = Executors.newScheduledThreadPool(2,
                                                                              ThreadFactoryBuilder.builder()
                                                                                                  .nameFormat(lockManagerInstanceId + ASYNC_LOCK_ACQUIRING_THREAD_NAME_INFIX + "%d")
                                                                                                  .daemon(true)
                                                                                                  .build());

                confirmationScheduledFuture = lockConfirmationExecutor.scheduleAtFixedRate(this::confirmAllLocallyAcquiredLocks,
                                                                                           lockConfirmationInterval.toMillis(),
                                                                                           lockConfirmationInterval.toMillis(),
                                                                                           TimeUnit.MILLISECONDS);

                // Resume every async acquiring the last stop() kept. Each is rescheduled inside computeIfPresent, so a
                // concurrent cancelAsyncLockAcquiring - which does not take reentrantLock - either removes the entry
                // first, and nothing is scheduled for it, or removes the rescheduled entry and cancels its new future.
                // The first tick of a rescheduled entry runs at once and waits for reentrantLock, which this holds, so
                // that cancel can come while the tick is already running - a cancel(true) does not end that wait. The
                // tick therefore acts only while its own entry is still the one in asyncLockAcquirings, which it checks
                // once it has reentrantLock (asyncLockAcquiringTick).
                // Logged at WARN: a registration whose component was discarded without cancelAsyncLockAcquiring is resumed
                // too, and its callback then holds the lock with nothing on this instance serving it
                var executor = asyncLockAcquiringExecutor;
                for (var lockName : asyncLockAcquirings.keySet()) {
                    asyncLockAcquirings.computeIfPresent(lockName, (name, kept) -> {
                        log.warn("[{}] Resuming async lock acquiring for lock '{}' kept across the last stop(), with the LockCallback '{}'. " +
                                         "A component that no longer wants the lock must call cancelAsyncLockAcquiring when it stops, " +
                                         "or its callback is given the lock again",
                                 lockManagerInstanceId, name, kept.lockCallback().getClass().getName());
                        kept.scheduledFuture().cancel(true);
                        return scheduleAsyncLockAcquiring(executor, name, kept.lockCallback(), true);
                    });
                }

                started = true;
                if (lockSpreading.enabled()) {
                    // Join the membership before any async acquiring ticks (they wait for reentrantLock, held here): an
                    // instance that only joined on its first tick saw no other member, preferred itself for every lock, and
                    // instances starting together raced for all of them as if nothing were spread
                    lockSpreadingMembership = null;
                    seenFreeByNonPreferredOwnerSinceNanos.clear();
                    rebalanceSuppressedForMembership.clear();
                    // The first lock moves one interval after start, once the other instances have read this one's membership
                    nextRebalanceAtNanos.set(System.nanoTime() + lockSpreading.rebalanceInterval().toNanos());
                    liveLockManagerInstances();
                }
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
            // reentrantLock, so every acquire from here on that would take reentrantLock - one that needs the DB - is refused:
            // _tryAcquireLock checks the flag again once it holds the lock. Returning a lock this instance already holds takes
            // no reentrantLock, so a caller racing this tick can still be handed one - releaseHeldLocksLocallyAsTheConfirmationEnds
            // below releases it locally straight after
            confirmationEnded = true;
            // Cleared here as well as in start(): a refusal that read the flag of an earlier end just before start() can
            // record its name after start() cleared the sets, and would then be logged at DEBUG only for this end
            acquiresRefusedAsTheConfirmationHasEnded.clear();
            asyncAcquiringsRefusedAsTheConfirmationHasEnded.clear();
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
     * confirmed again. Each lock is released locally ({@link #releaseLocally(DBFencedLock, Consumer)}), so it stops reporting
     * itself as held by this instance before its row can expire and another instance take it over. Not in the DB: the rows
     * expire after <code>lockTimeOut</code>. A failure of one local release, critical or not, is added to <code>failure</code>
     * as suppressed and the next lock released all the same. <code>failure</code> is logged at ERROR afterwards, because the
     * task's {@link ScheduledFuture} it escapes into is never read, naming the locks whose async acquiring now acquires nothing.
     * Then {@link LockConfirmationEnded} is published; a failure of a subscriber is logged at ERROR and added to
     * <code>failure</code> as suppressed, never thrown in its place.
     *
     * @param failure the failure that ends the scheduled confirmation
     */
    private void releaseHeldLocksLocallyAsTheConfirmationEnds(Throwable failure) {
        var heldLocks     = List.copyOf(locksAcquiredByThisLockManager.values());
        var releasedLocks = new ArrayList<LockName>(heldLocks.size());
        heldLocks.forEach(lock -> {
            if (releaseLocally(lock, localReleaseFailure -> addSuppressedOnce(failure, localReleaseFailure))) {
                releasedLocks.add(lock.getName());
            }
        });
        var stalledAsyncAcquirings = asyncLockAcquirings.keySet().stream().map(LockName::toString).sorted().toList();
        log.error(msg("[{}] The lock confirmation failed with {} - released the {} lock(s) this lock manager instance held locally only "
                      + "(their rows expire after the lock timeout) and the scheduled lock confirmation has ended: this lock manager "
                      + "acquires no lock from now on (tryAcquireLock returns empty, acquireLock throws, async acquiring acquires nothing), "
                      + "until the application is restarted, or the lock manager is stopped and started. "
                      + "The {} async lock acquiring(s) that acquire nothing until then are for lock(s): {}",
                      lockManagerInstanceId, isCriticalError(failure) ? "a critical error" : "an unexpected failure", heldLocks.size(),
                      stalledAsyncAcquirings.size(), stalledAsyncAcquirings), failure);
        try {
            notify(new LockConfirmationEnded(this, failure, releasedLocks));
        } catch (Throwable e) {
            log.error(msg("[{}] A subscriber failed handling the LockConfirmationEnded event", lockManagerInstanceId), e);
            addSuppressedOnce(failure, e);
        }
    }


    /**
     * First half of releasing a lock this instance failed to confirm: remove it locally, mark it as released and
     * notify {@link LockReleased}, without any DB round trip. The lock is already lost, so the local release must
     * not wait on the DB: when the DB is unreachable a release attempt can take several times the socket timeout
     * (MongoDB driver 5.12+ retries connection establishment with backoff), and for all that time this instance
     * would keep reporting a lock that another instance may take over once <code>lockTimeOut</code> has passed.
     * The best-effort DB release follows in {@link #releaseLostLocksInDB(List)}.
     * <p>
     * Runs on the confirmation thread, partly outside any catch: a throwing <code>lockReleased</code> callback or event
     * subscriber must not escape and end the scheduled confirmation. A non-critical failure, an {@link Error} (an
     * {@link AssertionError}, say) included, is logged and handled like a {@link RuntimeException}: escaping the onError
     * handler of the confirmation {@link UnitOfWork}, it would end the scheduled confirmation; escaping the confirmation loop,
     * it would roll back the confirmations made in the tick and skip the DB release of this lock. {@link LockReleased} is
     * published even when a callback fails non-critically ({@link #markAsReleasedAndNotify(DBFencedLock)}). A critical error
     * is rethrown.
     *
     * @param lock the lock that could not be confirmed
     * @return true if this call released the lock locally, false if this lock manager no longer held it
     */
    private boolean releaseLostLockLocally(LOCK lock) {
        var released = releaseLocally(lock, failure -> {
            rethrowIfCriticalError(failure);
            log.error(msg("[{}] Failure while releasing lost lock '{}' locally (a lockReleased callback or a LockReleased subscriber threw)",
                          lockManagerInstanceId, lock.getName()), failure);
        });
        if (released) {
            log.debug("[{}] Released lost lock '{}' locally: {}", lockManagerInstanceId, lock.getName(), lock);
        }
        return released;
    }

    /**
     * The one local release of a lock this instance holds - see the class javadoc, "Errors". Removes <code>lock</code> from
     * {@link #locksAcquiredByThisLockManager}, which stops its confirmation, and, when this call removed it and it still reports
     * itself as held by this instance, marks it as released, which calls its <code>lockReleased</code> callbacks, and publishes
     * {@link LockReleased} ({@link #markAsReleasedAndNotify(DBFencedLock)}). Makes no DB round trip.
     * <p>
     * The remove goes by identity, not by name or by {@link ConcurrentMap#remove(Object, Object)}:
     * {@link DBFencedLock#equals(Object)} compares the lock name only, so either would also remove a newer acquisition of the
     * same lock - one acquired after this one was lost, or one a <code>lockReleased</code> callback made on this thread through
     * {@link #tryAcquireLock(LockName)}, say - which would then no longer be confirmed and would expire while its holder
     * believes it holds it.
     * <p>
     * Only the call that removes the lock marks it and publishes the event, and the removal is atomic, so a lock two paths
     * release at once - the end of the lock confirmation and a {@link #releaseLock} that waited for {@link #reentrantLock}, or
     * a {@link #releaseLock} on another thread, which runs this outside {@link #reentrantLock} - is announced as released once.
     * A lock that no longer reports itself as held by this instance has been marked as released already: it is removed, not
     * marked again.
     *
     * @param lock      the lock to release locally
     * @param onFailure given the failure of marking the lock as released or of publishing {@link LockReleased} (see
     *                  {@link #markAsReleasedAndNotify(DBFencedLock)}), to log, rethrow or add to another failure
     * @return true if this call removed the lock, false if this lock manager did not hold <code>lock</code> (it was released
     * locally already, or this lock manager holds a newer acquisition of the lock, or none)
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // the lock is removed by identity, see above
    private boolean releaseLocally(LOCK lock, Consumer<Throwable> onFailure) {
        var removedByThisCall = new boolean[1];
        locksAcquiredByThisLockManager.computeIfPresent(lock.getName(), (name, held) -> {
            if (held != lock) {
                return held;
            }
            removedByThisCall[0] = true;
            return null;
        });
        if (!removedByThisCall[0]) {
            return false;
        }
        if (lock.isLockedByThisLockManagerInstance()) {
            try {
                markAsReleasedAndNotify(lock);
            } catch (Throwable failure) {
                onFailure.accept(failure);
            }
        }
        return true;
    }

    /**
     * Does this lock manager hold <code>lock</code> - this very instance, not merely a lock of the same name?
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // a lock is held by identity, see releaseLocally
    private boolean holds(LOCK lock) {
        return locksAcquiredByThisLockManager.get(lock.getName()) == lock;
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
        asyncLockAcquirings.values().forEach(acquiring -> acquiring.scheduledFuture().cancel(true));
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
        if (!holds(lock)) {
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
        releaseLocally(lock, failure -> sneakyThrow(failure));
        log.debug("[{}] {} lock '{}' during shutdown", lockManagerInstanceId, released ? "Released" : "Dropped (left to expire)", lock.getName());
    }

    /**
     * Internal method only to be called by subclasses of {@link DBFencedLockManager} and {@link DBFencedLock}
     * <p>
     * Releases nothing unless this lock manager holds <code>lock</code> itself, the very instance: a stale instance - one lost
     * and since acquired again as a new instance - must not release, or stop confirming, the newer acquisition of the same
     * lock name. The local half (<code>releaseLocally</code>) marks the lock as released and publishes {@link LockReleased} only
     * when this call removes it, so a lock released locally by another path while this call waited for this lock manager's lock
     * (the end of the lock confirmation, say) is not announced as released twice. A failure of the <code>lockReleased</code>
     * callbacks or of publishing the event is thrown to the caller (see <code>markAsReleasedAndNotify</code>).
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
        if (holds(lock)) {
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
            if (!releaseLocally(lock, failure -> sneakyThrow(failure))) {
                log.debug("[{}] Lock '{}' was released locally by another path during this release - not announcing its release again",
                          lockManagerInstanceId, lock.getName());
            }
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
            // NullPointerException on the caller's thread.
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
                            releaseLocallyAfterFailedRelease(lock, criticalError);
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

                // Each registration is kept - only its scheduled future is cancelled - so start() resumes it.
                // cancelAsyncLockAcquiring is what removes one for good
                asyncLockAcquirings.forEach((lockName, acquiring) -> {
                    log.debug("[{}] Suspending async acquiring of Lock '{}' due to Stopping - start() resumes it",
                              lockManagerInstanceId,
                              lockName);

                    try {
                        acquiring.scheduledFuture().cancel(true);
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

    /**
     * Has the scheduled lock confirmation of this lock manager ended (in practice on a critical error - see the class javadoc,
     * "Errors")? While it has, {@link #isStarted()} can still be <code>true</code>, but this lock manager acquires no lock -
     * see the class javadoc, "The state left behind", for what that means and the way back.
     *
     * @return <code>true</code> from the moment the scheduled lock confirmation ended until the lock manager is stopped and
     * started again ({@link #start()} on a lock manager that is still started does nothing, and a {@link #stop()} alone does
     * not clear it), otherwise <code>false</code>
     */
    @Override
    public boolean isLockConfirmationEnded() {
        return confirmationEnded;
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

    /**
     * The one wording of an acquire refused because the scheduled lock confirmation has ended: the log line of
     * {@link #logNotAcquiringAsTheConfirmationHasEnded(LockName)} and the exception {@link #acquireLock(LockName)} throws.
     * Its arguments are the {@code lockManagerInstanceId} and the {@link LockName}
     */
    private static final String NOT_ACQUIRING_AS_THE_CONFIRMATION_HAS_ENDED =
            "[{}] The scheduled lock confirmation has ended - not acquiring lock '{}', which this lock manager could not confirm, "
            + "until the application is restarted, or the lock manager is stopped and started";

    /**
     * Logs a refused acquire at WARN the first time for each lock name since the scheduled lock confirmation ended, and at
     * DEBUG after that, so a caller that retries does not repeat the WARN
     */
    private void logNotAcquiringAsTheConfirmationHasEnded(LockName lockName) {
        if (acquiresRefusedAsTheConfirmationHasEnded.add(lockName)) {
            log.warn(NOT_ACQUIRING_AS_THE_CONFIRMATION_HAS_ENDED + " (logged at WARN for the first refused acquire of this lock, at DEBUG after that)",
                     lockManagerInstanceId, lockName);
        } else {
            log.debug(NOT_ACQUIRING_AS_THE_CONFIRMATION_HAS_ENDED, lockManagerInstanceId, lockName);
        }
    }

    /**
     * Logs a refused tick of an async acquiring at WARN for its first tick since the scheduled lock confirmation ended, and at
     * DEBUG for its later ticks, which come every lock confirmation interval
     */
    private void logAsyncAcquiringNotAcquiringAsTheConfirmationHasEnded(LockName lockName) {
        var message = "[{}] The scheduled lock confirmation has ended - the async acquiring of lock '{}' is not acquiring it, as this lock manager "
                      + "could not confirm it, until the application is restarted, or the lock manager is stopped and started";
        if (asyncAcquiringsRefusedAsTheConfirmationHasEnded.add(lockName)) {
            log.warn(message + " (logged at WARN for the first refused tick of an async acquiring of this lock, at DEBUG for its later ticks)", lockManagerInstanceId, lockName);
        } else {
            log.debug(message, lockManagerInstanceId, lockName);
        }
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

    /**
     * Whether {@link #asyncLockAcquiringTick} may try to acquire a lock this instance does not hold, under
     * {@link #lockSpreading}: always when spreading is off, for a membership lock, or when this instance is the lock's
     * preferred owner; otherwise only once this instance has seen the lock free for {@link LockSpreading#acquireGrace()}.
     * A failure to read the lock or the membership falls back to acquiring as without spreading - exclusion never
     * depends on this, only who takes a free lock.
     *
     * @param lockName the lock the tick is about to acquire
     * @return whether to try
     */
    private boolean mayAcquireUnderLockSpreading(LockName lockName) {
        if (!lockSpreading.enabled() || LockSpreading.isMembershipLock(lockName)) {
            return true;
        }
        var preferredOwner = preferredOwner(lockName, liveLockManagerInstances());
        if (preferredOwner.equals(lockManagerInstanceId)) {
            seenFreeByNonPreferredOwnerSinceNanos.remove(lockName);
            return true;
        }
        var existingLock = withUnitOfWork(uow -> lockStorage.lookupLockInDB(this, uow, lockName),
                                          e -> null);
        if (existingLock == null) {
            return true;
        }
        var free = existingLock.isEmpty()
                || !existingLock.get().isLocked()
                || existingLock.get().isLockedByThisLockManagerInstance()
                || isLockTimedOut(existingLock.get());
        if (!free) {
            seenFreeByNonPreferredOwnerSinceNanos.remove(lockName);
            return false;
        }
        var now       = System.nanoTime();
        var freeSince = seenFreeByNonPreferredOwnerSinceNanos.computeIfAbsent(lockName, name -> now);
        if (now - freeSince >= lockSpreading.acquireGrace().toNanos()) {
            seenFreeByNonPreferredOwnerSinceNanos.remove(lockName);
            if (lockSpreading.rebalances()) {
                rebalanceSuppressedForMembership.put(lockName, liveLockManagerInstances());
            }
            log.debug("[{}] Lock '{}' has been free for {} ms without its preferred owner '{}' taking it - acquiring it",
                      lockManagerInstanceId, lockName, Duration.ofNanos(now - freeSince).toMillis(), preferredOwner);
            return true;
        }
        log.trace("[{}] Leaving free lock '{}' to its preferred owner '{}' for now", lockManagerInstanceId, lockName, preferredOwner);
        return false;
    }

    /**
     * Whether {@link #asyncLockAcquiringTick} should release a lock this instance holds to the lock's preferred owner, under
     * {@link LockSpreading#rebalances()}: the preferred owner is another live instance, the lock was not taken back from it
     * under the current membership (see {@link #rebalanceSuppressedForMembership}), and this lock manager has not released another lock within the
     * last {@link LockSpreading#rebalanceInterval()} - claimed here, so two ticks cannot both release
     *
     * @param lockName a lock this instance holds
     * @return whether to release it
     */
    private boolean mayRebalanceAway(LockName lockName) {
        if (!lockSpreading.rebalances() || paused || LockSpreading.isMembershipLock(lockName)) {
            return false;
        }
        var now = System.nanoTime();
        var due = nextRebalanceAtNanos.get();
        if (now - due < 0) {
            return false;
        }
        var members         = liveLockManagerInstances();
        var suppressedUnder = rebalanceSuppressedForMembership.get(lockName);
        if (suppressedUnder != null) {
            if (suppressedUnder.equals(members)) {
                return false;
            }
            rebalanceSuppressedForMembership.remove(lockName, suppressedUnder);
        }
        if (members.size() < 2 || preferredOwner(lockName, members).equals(lockManagerInstanceId)) {
            return false;
        }
        return nextRebalanceAtNanos.compareAndSet(due, now + lockSpreading.rebalanceInterval().toNanos());
    }

    /**
     * Releases a lock this instance holds so its preferred owner takes it on its next acquire tick. The lock's
     * {@code lockReleased} callbacks run, as on any release; the async acquiring stays registered, and this instance takes
     * the lock back after the grace if the preferred owner does not
     *
     * @param lockName the lock
     * @param lock     this instance's lock
     */
    private void releaseToPreferredOwner(LockName lockName, LOCK lock) {
        log.info("[{}] Releasing lock '{}' to its preferred owner '{}' (lock spreading rebalance)",
                 lockManagerInstanceId, lockName, preferredOwner(lockName, liveLockManagerInstances()));
        try {
            releaseLock(lock);
        } catch (Throwable releaseFailure) {
            releaseLocallyAfterFailedRelease(lock, releaseFailure);
            rethrowIfCriticalError(releaseFailure);
            log.error(msg("[{}] Releasing lock '{}' to its preferred owner failed - this lock manager no longer confirms it, so a row the "
                          + "release did not free expires after the lock timeout", lockManagerInstanceId, lockName), releaseFailure);
        }
    }

    /**
     * The live lock manager instances - this one always among them - from the confirmed membership locks, read at most
     * once per {@code lockConfirmationInterval}. Makes sure this instance holds its own membership lock first; the
     * confirmation keeps it alive from then on. A failed read keeps the last membership read
     *
     * @return the instance ids, sorted
     */
    private SortedSet<String> liveLockManagerInstances() {
        var membership = lockSpreadingMembership;
        var now        = System.nanoTime();
        if (membership != null && now - membership.readAtNanos() < lockConfirmationInterval.toNanos()) {
            return membership.lockManagerInstanceIds();
        }
        var membershipLockName = LockName.of(LockSpreading.MEMBERSHIP_LOCK_NAME_PREFIX + lockManagerInstanceId);
        if (!locksAcquiredByThisLockManager.containsKey(membershipLockName)) {
            try {
                tryAcquireLock(membershipLockName);
            } catch (Exception e) {
                rethrowIfCriticalError(e);
                log.debug(msg("[{}] Failed to acquire the membership lock '{}'", lockManagerInstanceId, membershipLockName), e);
            }
        }
        var instanceIds = new TreeSet<String>();
        instanceIds.add(lockManagerInstanceId);
        var read = withUnitOfWork(uow -> {
                                      for (var lock : lockStorage.getAllLocksInDB(this, uow)) {
                                          if (LockSpreading.isMembershipLock(lock.getName()) && lock.isLocked() && !isLockTimedOut(lock)) {
                                              instanceIds.add(lock.getLockedByLockManagerInstanceId());
                                          }
                                      }
                                      return Boolean.TRUE;
                                  },
                                  e -> Boolean.FALSE);
        if (!read && membership != null) {
            return membership.lockManagerInstanceIds();
        }
        var ids = Collections.unmodifiableSortedSet(instanceIds);
        lockSpreadingMembership = new LockSpreadingMembership(ids, now);
        return ids;
    }

    /**
     * Rendezvous (highest random weight) hashing: the instance whose hash of {@code (lockName, instanceId)} is highest.
     * Every instance computes the same answer from the same membership, and a member joining or leaving changes the
     * answer for only the locks it wins or held. The hash is computed here (FNV-1a over UTF-8, then a 64-bit finalizer),
     * never {@link String#hashCode()} alone, whose weak mixing of similar ids would skew the spread
     *
     * @param lockName            the lock
     * @param lockManagerInstanceIds the live instances, not empty
     * @return the preferred owner's id
     */
    static String preferredOwner(LockName lockName, SortedSet<String> lockManagerInstanceIds) {
        String best      = null;
        long   bestScore = 0;
        for (var instanceId : lockManagerInstanceIds) {
            var score = rendezvousScore(lockName.toString(), instanceId);
            if (best == null || Long.compareUnsigned(score, bestScore) > 0) {
                best = instanceId;
                bestScore = score;
            }
        }
        return best;
    }

    private static long rendezvousScore(String lockName, String instanceId) {
        var hash = 0xcbf29ce484222325L;
        for (var b : (lockName + '\u0000' + instanceId).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return hash;
    }

    private record LockSpreadingMembership(SortedSet<String> lockManagerInstanceIds, long readAtNanos) {
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
                throw new IllegalStateException(msg(NOT_ACQUIRING_AS_THE_CONFIRMATION_HAS_ENDED, lockManagerInstanceId, lockName));
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
     * for the lock. That is about an acquiring a critical error of its own has ended; an acquiring that acquires nothing
     * because the scheduled lock confirmation has ended is told by {@link FencedLockEvents.LockConfirmationEnded}, published
     * when the confirmation ends, and by {@link #isLockConfirmationEnded()} - see the class javadoc, "The state left behind".
     * The ended acquiring keeps its entry, so calling this method again for the same lock name does not restart it. To
     * restart it, call {@link #cancelAsyncLockAcquiring(LockName)} and then this method, or stop and start the lock manager.
     * <p>
     * {@link #stop()} cancels the scheduled acquiring but keeps the registration (the lock name and this
     * <code>lockCallback</code>), and {@link #start()} schedules every kept registration again, an ended one included, and
     * logs each one it resumes at WARN, naming the lock and the callback's class; only
     * {@link #cancelAsyncLockAcquiring(LockName)} removes a registration for good, so a component must call it in its own
     * stop, or the next {@link #start()} gives this <code>lockCallback</code> the lock again. Called for a lock name whose registration
     * a {@link #stop()} kept - by a component recreated across a stop and start of the lock manager, say - with another
     * <code>lockCallback</code> than the kept one, this method replaces the kept registration with one for the new
     * <code>lockCallback</code>, as {@link #cancelAsyncLockAcquiring(LockName)} followed by this method would: the lock, when
     * this instance holds it, is released (which calls <code>lockReleased</code> on the callbacks registered with the lock -
     * the kept one, when its resumed acquiring acquired it), the acquiring goes on for the new <code>lockCallback</code>, and
     * the kept one is not called again. A failure of that release (a <code>lockReleased</code> callback throwing, say) is
     * logged and the new registration made all the same; a critical error is rethrown. While the lock manager is stopped,
     * the new <code>lockCallback</code> replaces the kept one (no lock is held then), and {@link #start()} schedules it.
     * Called with the kept <code>lockCallback</code> itself, or for a lock name whose registration was made since the last
     * {@link #start()}, this method does nothing and keeps the registered <code>lockCallback</code>. Called while the lock
     * manager is not started, it registers nothing new.
     */
    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // a registration's callback is the caller's instance, compared by identity
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
            if (!started) {
                // Every registration is a kept one while the lock manager is stopped: start() schedules it with this callback
                var replaced = new boolean[1];
                asyncLockAcquirings.computeIfPresent(lockName, (name, kept) -> {
                    if (kept.lockCallback() == lockCallback) {
                        return kept;
                    }
                    replaced[0] = true;
                    return new AsyncLockAcquiring(lockCallback, true, kept.scheduledFuture());
                });
                if (replaced[0]) {
                    log.debug("[{}] Lock Manager isn't started - replaced the callback of the kept async lock acquiring for lock '{}', "
                              + "which start() resumes", lockManagerInstanceId, lockName);
                    return;
                }
            }
            if (!started || stopping || shutdown != null || executor == null) {
                log.debug("[{}] Lock Manager isn't started - ignoring async lock acquiring for lock '{}'", lockManagerInstanceId, lockName);
                return;
            }

            var registered = asyncLockAcquirings.get(lockName);
            if (registered != null && registered.keptByStop() && registered.lockCallback() != lockCallback) {
                replaceKeptAsyncLockAcquiring(lockName);
            }
            asyncLockAcquirings.computeIfAbsent(lockName, _lockName -> {
                log.debug("[{}] Starting async Lock acquiring for lock '{}'", lockManagerInstanceId, lockName);
                return scheduleAsyncLockAcquiring(executor, lockName, lockCallback, false);
            });
        } finally {
            reentrantLock.unlock();
        }
    }

    /**
     * Removes the registration a {@link #stop()} kept for the lock name, and {@link #start()} resumed, so that
     * {@link #acquireLockAsync(LockName, LockCallback)} can register its other callback in its place - as
     * {@link #cancelAsyncLockAcquiring(LockName)} does, which releases the lock when this instance holds it. A failure of that
     * release is logged, not thrown, so the caller's callback is registered all the same: the release has removed the lock
     * locally already when its <code>lockReleased</code> callback throws. A critical error is rethrown.
     *
     * @param lockName the name of the lock
     */
    private void replaceKeptAsyncLockAcquiring(LockName lockName) {
        log.debug("[{}] Replacing the callback of the kept async lock acquiring for lock '{}' - releasing the lock if this instance holds it",
                  lockManagerInstanceId, lockName);
        try {
            cancelAsyncLockAcquiring(lockName);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            log.error(msg("[{}] Releasing lock '{}' while replacing the callback of its kept async acquiring failed (a lockReleased callback or LockReleased subscriber threw) - registering the new callback all the same",
                          lockManagerInstanceId, lockName), e);
        }
    }

    /**
     * An async acquiring registered with {@link #acquireLockAsync(LockName, LockCallback)}: the callback it was registered
     * with, kept across {@link #stop()} so {@link #start()} can schedule it again, and the scheduled future of its ticks
     * since the last (re)scheduling - cancelled while the lock manager is stopped. Each (re)scheduling makes a new instance,
     * and its ticks act only while that instance is the one in {@link #asyncLockAcquirings} (see
     * {@link #asyncLockAcquiringTick(LockName, AsyncLockAcquiring)}), so it is compared by identity
     */
    private static final class AsyncLockAcquiring {
        private final    LockCallback       lockCallback;
        private final    boolean            keptByStop;
        /**
         * Set once, right after the scheduling, before the instance is put in {@link #asyncLockAcquirings}
         */
        private volatile ScheduledFuture<?> scheduledFuture;

        /**
         * @param lockCallback    the callback the async acquiring was registered with
         * @param keptByStop      <code>true</code> for a registration {@link #start()} resumed, or one
         *                        {@link #acquireLockAsync(LockName, LockCallback)} replaced while the lock manager was stopped:
         *                        after {@link #start()}, {@link #acquireLockAsync(LockName, LockCallback)} with another callback
         *                        replaces it. <code>false</code> for one registered since the last {@link #start()}; {@link #stop()}
         *                        leaves the flag unchanged. While the lock manager is stopped,
         *                        {@link #acquireLockAsync(LockName, LockCallback)} treats every registration as kept, whatever
         *                        the flag says
         * @param scheduledFuture the future of its ticks, or <code>null</code> when it is set right after the scheduling
         */
        private AsyncLockAcquiring(LockCallback lockCallback, boolean keptByStop, ScheduledFuture<?> scheduledFuture) {
            this.lockCallback = lockCallback;
            this.keptByStop = keptByStop;
            this.scheduledFuture = scheduledFuture;
        }

        LockCallback lockCallback() {
            return lockCallback;
        }

        boolean keptByStop() {
            return keptByStop;
        }

        ScheduledFuture<?> scheduledFuture() {
            return scheduledFuture;
        }
    }

    /**
     * Schedules the ticks of a new registration. Called under {@link #reentrantLock} and inside the compute that puts the
     * registration in {@link #asyncLockAcquirings}: its first tick, which runs at once, waits for {@link #reentrantLock} and
     * so finds the registration in place
     */
    private AsyncLockAcquiring scheduleAsyncLockAcquiring(ScheduledExecutorService executor, LockName lockName, LockCallback lockCallback, boolean keptByStop) {
        var acquiring = new AsyncLockAcquiring(lockCallback, keptByStop, null);
        acquiring.scheduledFuture = executor.scheduleAtFixedRate(() -> asyncLockAcquiringTick(lockName, acquiring),
                                                                 0,
                                                                 lockConfirmationInterval.toMillis(),
                                                                 TimeUnit.MILLISECONDS);
        return acquiring;
    }

    /**
     * A single tick of the background acquiring scheduled by {@link #acquireLockAsync(LockName, LockCallback)}: acquire
     * the lock if this instance doesn't hold it, or notice and report that another instance has taken it over.
     * <p>
     * The tick does nothing unless <code>acquiring</code> is still the registration in {@link #asyncLockAcquirings} once it
     * holds {@link #reentrantLock}: a cancelled tick can already be running, waiting for that lock (behind {@link #start()},
     * say), and a cancel's interrupt does not end that wait. A {@link #cancelAsyncLockAcquiring(LockName)} made after that
     * check, while the tick acquires the lock - that cancel does not take {@link #reentrantLock}, and its interrupt need not
     * abort a database round trip - is caught by a second check, made once the acquired lock is recorded in
     * {@link #locksAcquiredByThisLockManager}: a tick that finds its registration gone then releases the lock, with the
     * cancel's pending interrupt set aside for the release, and does not call <code>lockAcquired</code>.
     *
     * @param lockName  the lock being acquired
     * @param acquiring the registration this tick was scheduled for - its callback is notified when the lock is acquired or lost
     */
    @SuppressWarnings({"unchecked", "PMD.CompareObjectsWithEquals"})
    private void asyncLockAcquiringTick(LockName lockName, AsyncLockAcquiring acquiring) {
        var lockCallback        = acquiring.lockCallback();
        var criticalErrorLogged = false;
        try {
            reentrantLock.lock();
            if (!started || shutdown != null) {
                return;
            }
            if (asyncLockAcquirings.get(lockName) != acquiring) {
                log.debug("[{}] The async acquiring of lock '{}' this tick was scheduled for has been cancelled or replaced - not acquiring the lock for it",
                          lockManagerInstanceId, lockName);
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

                if (!mayAcquireUnderLockSpreading(lockName)) {
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
                    locksAcquiredByThisLockManager.put(lockName, (LOCK) fencedLock);
                    // A cancelAsyncLockAcquiring made during the acquire above (it does not take reentrantLock) removes the
                    // registration and then reads locksAcquiredByThisLockManager; this tick has recorded the lock there and
                    // now reads the registration. So either the cancel finds the lock and releases it, or this tick finds the
                    // registration gone and releases the lock itself. Outside shutdown the cancel's release in the DB waits
                    // for reentrantLock, so it runs once this tick has called lockAcquired and calls lockReleased after it;
                    // during shutdown its one bounded attempt may skip that wait or give it up, and then calls lockReleased
                    // while lockAcquired may still be running. When both happen, both release the lock, but only the first
                    // to release it locally calls lockReleased and publishes LockReleased (releaseLocally); the other at
                    // most repeats the release in the DB. The callback is registered
                    // only after this check, so the cancelled one is never called for a lock it was not given
                    if (asyncLockAcquirings.get(lockName) != acquiring) {
                        log.debug("[{}] The async acquiring of lock '{}' was cancelled while this tick acquired the lock - releasing it",
                                  lockManagerInstanceId, lockName);
                        releaseLockOfCancelledAsyncAcquiring(lockName, (LOCK) fencedLock);
                        return;
                    }
                    fencedLock.registerCallback(lockCallback);
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
                        releaseAfterFailedLockAcquired((LOCK) fencedLock);
                    }
                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("[{}] Couldn't async Acquire lock '{}' as it is acquired by another Lock Manager instance: {}",
                                  lockManagerInstanceId, lockName, lookupLock(lockName));
                    }
                }
            } else if (existingLock.isLockedByThisLockManagerInstance() && mayRebalanceAway(lockName)) {
                releaseToPreferredOwner(lockName, existingLock);
            } else if (!existingLock.isLockedByThisLockManagerInstance()) {
                log.debug("[{}] Noticed that lock '{}' isn't locked by this Lock Manager instance anymore. Releasing the lock",
                          lockManagerInstanceId,
                          lockName);
                // Already marked as released, so releaseLocally only removes it - see the class javadoc, "Errors"
                releaseLocally(existingLock, failure -> sneakyThrow(failure));
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
     * Releases a lock a tick of an async acquiring acquired after {@link #cancelAsyncLockAcquiring(LockName)} had removed its
     * registration, so it is not left held and confirmed by this instance with no registration that would release it. No
     * callback is registered with the lock, so no <code>lockReleased</code> is called; {@link LockReleased} is published.
     * <p>
     * The tick can get here with the cancel's interrupt pending (<code>cancel(true)</code>), which fails a release in the
     * DB on a driver that fails on a pending interrupt (MongoDB's), so the pending interrupt is set aside for the release and
     * restored afterwards, as in {@link #resolveAcquireWithUnknownCommitOutcome(DBFencedLock)}.
     * <p>
     * A failure of the release ({@link LockReleased} subscriber throwing, or a critical error from <code>releaseLockInDB</code>,
     * say) completes the release locally ({@link #releaseLocallyAfterFailedRelease(DBFencedLock, Throwable)}), so this
     * lock manager stops confirming the lock: a row the release did not free in the DB expires after the lock timeout. A
     * non-critical failure is then logged; a critical error is rethrown.
     *
     * @param lockName the name of the lock
     * @param lock     the lock the tick acquired
     */
    private void releaseLockOfCancelledAsyncAcquiring(LockName lockName, LOCK lock) {
        var interrupted = Thread.interrupted();
        try {
            releaseLock(lock);
        } catch (Throwable releaseFailure) {
            releaseLocallyAfterFailedRelease(lock, releaseFailure);
            rethrowIfCriticalError(releaseFailure);
            log.error(msg("[{}] Releasing lock '{}', acquired by a tick of its async acquiring after that acquiring was cancelled, failed - "
                          + "this lock manager no longer confirms the lock, so a row the release did not free expires after the lock timeout",
                          lockManagerInstanceId, lockName), releaseFailure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Releases a lock whose <code>lockAcquired</code> callback failed with a non-critical error, so the next attempt of the
     * async acquiring can retry. A failure of the release is rethrown as it is: the <code>lockAcquired</code> failure has been
     * logged by the caller already and is not added to it.
     * <p>
     * A critical error from the release itself (<code>releaseLockInDB</code>, say) escapes before {@link #releaseLock} removes
     * the lock locally, and ends the async acquiring in the caller's outer catch. Releasing it locally here first
     * ({@link #releaseLocallyAfterFailedRelease(DBFencedLock, Throwable)}) stops this lock manager confirming it, so
     * the lock row expires after the lock timeout rather than staying held unserved, and calls <code>lockReleased</code>, so
     * what <code>lockAcquired</code> started is stopped before another instance can take the lock over.
     *
     * @param lock the lock that was acquired
     */
    private void releaseAfterFailedLockAcquired(LOCK lock) {
        try {
            releaseLock(lock);
        } catch (Throwable releaseFailure) {
            releaseLocallyAfterFailedRelease(lock, releaseFailure);
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
     * <code>releaseLockInDB</code>, say) - see {@link #releaseLocallyAfterFailedRelease(DBFencedLock, Throwable)}: it is
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
            releaseLocallyAfterFailedRelease(lock, criticalError);
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
     * Releases the lock locally ({@link #releaseLocally(DBFencedLock, Consumer)}): removes it from
     * {@link #locksAcquiredByThisLockManager} by identity, which stops its confirmation (the lock row expires after the lock
     * timeout), and marks it as released and notifies {@link LockReleased} when this call removed it and it still reports itself
     * as held by this instance. A lock {@link #releaseLock}
     * already removed and marked as released (its <code>lockReleased</code> callback threw, say) is not notified again, and a
     * newer acquisition of the same lock - one a <code>lockReleased</code> callback made on this thread through
     * {@link #tryAcquireLock(LockName)}, say - is left held. A failure of this local release, critical or not, is added to
     * <code>releaseFailure</code> as suppressed, never thrown in its place.
     *
     * @param lock           the lock whose release threw
     * @param releaseFailure the failure that escapes to the caller, which any failure of the local release is added to as suppressed
     */
    private void releaseLocallyAfterFailedRelease(LOCK lock, Throwable releaseFailure) {
        releaseLocally(lock, localReleaseFailure -> addSuppressedOnce(releaseFailure, localReleaseFailure));
    }

    @Override
    public void cancelAsyncLockAcquiring(LockName lockName) {
        requireNonNull(lockName, "You must supply a lockName");
        var acquiring = asyncLockAcquirings.remove(lockName);
        if (acquiring != null) {
            log.debug("[{}] Canceling async Lock acquiring for lock '{}'", lockManagerInstanceId, lockName);
            acquiring.scheduledFuture().cancel(true);
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
    public Optional<Duration> getLockTimeOut() {
        return Optional.of(lockTimeOut);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" +
                "lockManagerInstanceId='" + lockManagerInstanceId + '\'' +
                '}';
    }

    /**
     * For tests only: the {@link #reentrantLock} of this lock manager, so a test in this package can hold it to park a
     * tick, <code>start()</code> or <code>stop()</code> behind it. Production code takes it only through this class's own methods
     *
     * @return this lock manager's {@link #reentrantLock}
     */
    ReentrantLock reentrantLockForTests() {
        return reentrantLock;
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
