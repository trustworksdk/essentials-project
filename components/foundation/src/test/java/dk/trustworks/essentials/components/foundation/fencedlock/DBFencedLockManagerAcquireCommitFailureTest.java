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

import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.reactive.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acquiring a lock is an acquire statement followed by a commit, and the commit can fail in two ways that the lock
 * manager must tell apart:
 * <ul>
 *     <li>the commit reached the DB but the reply did not - the connection was lost, or (MongoDB) the thread was interrupted,
 *     as when a shutdown cancels the async acquiring. The row is locked by this instance, so the lock must be held and
 *     served like any other acquired lock - confirmed, its callback called, released on release/shutdown - and not left
 *     unknown to this instance, blocking every other instance until the lock timeout</li>
 *     <li>the commit did not reach the DB. The lock must not be held locally: a lock this instance believes it holds, but
 *     whose row another instance then acquires with the same fence token, is "lost" at the next confirmation - and the
 *     release of that lost lock, matching on lock name and fence token, would release the other instance's lock</li>
 * </ul>
 * The storage here stages its writes in the unit of work and applies them on commit, and the next commit of an acquire
 * can be made to fail in each of those ways.
 */
class DBFencedLockManagerAcquireCommitFailureTest {
    private static final LockName LOCK_NAME = LockName.of("commit-failure-lock");
    /**
     * Far longer than any wait below, so a lock that is only freed by timing out shows up as a failure
     */
    private static final Duration LOCK_TIME_OUT = Duration.ofSeconds(30);

    private InMemoryLockStorage     storage;
    private TestDBFencedLockManager node1;
    private TestDBFencedLockManager node2;

    @BeforeEach
    void setup() {
        storage = new InMemoryLockStorage();
        node1 = createLockManager("node1");
        node2 = createLockManager("node2");
    }

    @AfterEach
    void cleanup() {
        node1.stop();
        node2.stop();
    }

    @Test
    void an_async_acquire_whose_commit_reached_the_db_but_reported_a_failure_holds_the_lock_and_releases_it() {
        verifyAsyncAcquireWithUnknownCommitOutcomeHoldsAndReleasesTheLock(CommitOutcome.APPLIED_THEN_CONNECTION_LOST);
    }

    @Test
    void an_async_acquire_interrupted_after_its_commit_reached_the_db_holds_the_lock_and_releases_it() {
        verifyAsyncAcquireWithUnknownCommitOutcomeHoldsAndReleasesTheLock(CommitOutcome.APPLIED_THEN_INTERRUPTED);
    }

    @Test
    void an_acquire_whose_commit_did_not_reach_the_db_is_not_held_and_never_releases_the_lock_another_instance_then_acquires() {
        // Given node1's acquire fails to commit
        storage.nextAcquireCommit = CommitOutcome.NOT_APPLIED;
        assertThat(node1.tryAcquireLock(LOCK_NAME)).isEmpty();
        assertThat(storage.rows).doesNotContainKey(LOCK_NAME);

        // When node2 acquires the lock - with the very fence token node1 attempted
        var node2Lock = node2.tryAcquireLock(LOCK_NAME).orElseThrow();
        assertThat(node2Lock.getCurrentToken()).isEqualTo(storage.getInitialTokenValue());

        // Then node1 does not report the lock as its own
        assertThat(node1.tryAcquireLock(LOCK_NAME)).isEmpty();
        // And across several of node1's confirmation ticks node2 keeps its lock - node1 never releases it as a lost lock of its own
        Awaitility.await()
                  .during(Duration.ofMillis(800))
                  .atMost(Duration.ofSeconds(3))
                  .untilAsserted(() -> {
                      var row = storage.rows.get(LOCK_NAME);
                      assertThat(row.lockedBy()).isEqualTo("node2");
                      assertThat(row.token()).isEqualTo(storage.getInitialTokenValue());
                  });
        assertThat(node2Lock.isLocked()).isTrue();
    }

    @Test
    void an_acquire_whose_commit_outcome_cannot_be_looked_up_is_not_held_and_its_row_expires() {
        // Given a lock manager with a short lock timeout, and node1's acquire reaching the DB but its commit and the look-up of its outcome failing
        node1.stop();
        node2.stop();
        var events = new CopyOnWriteArrayList<Object>();
        var eventBus = new LocalEventBus("test");
        eventBus.addSyncSubscriber(events::add);
        node1 = createLockManager("node1", Duration.ofSeconds(1), eventBus);
        node2 = createLockManager("node2", Duration.ofSeconds(1), null);
        storage.nextAcquireCommit = CommitOutcome.APPLIED_THEN_CONNECTION_LOST_AND_LOOKUP_FAILS;

        // Then node1 does not hold the lock, nothing says it acquired it, and the row stays locked by node1 with the initial token
        assertThat(node1.tryAcquireLock(LOCK_NAME)).isEmpty();
        assertThat(events).noneMatch(event -> event instanceof FencedLockEvents.LockAcquired);
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node1");
        assertThat(storage.rows.get(LOCK_NAME).token()).isEqualTo(storage.getInitialTokenValue());

        // And node2 gets the lock only once the row has timed out, with the next token
        assertThat(node2.tryAcquireLock(LOCK_NAME)).isEmpty();
        var node2Lock = node2.tryAcquireLock(LOCK_NAME, Duration.ofSeconds(5)).orElseThrow();
        assertThat(node2Lock.getCurrentToken()).isEqualTo(storage.getInitialTokenValue() + 1);

        // And across several of node1's confirmation ticks node1 neither re-adopts nor releases the row
        Awaitility.await()
                  .during(Duration.ofMillis(800))
                  .atMost(Duration.ofSeconds(3))
                  .untilAsserted(() -> {
                      var row = storage.rows.get(LOCK_NAME);
                      assertThat(row.lockedBy()).isEqualTo("node2");
                      assertThat(row.token()).isEqualTo(storage.getInitialTokenValue() + 1);
                  });
        assertThat(events).noneMatch(event -> event instanceof FencedLockEvents.LockAcquired);
    }

    private void verifyAsyncAcquireWithUnknownCommitOutcomeHoldsAndReleasesTheLock(CommitOutcome commitOutcome) {
        // Given node1's acquire reaches the DB, but its commit reports a failure
        storage.nextAcquireCommit = commitOutcome;
        var acquiredLock = new AtomicReference<FencedLock>();
        node1.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                      .onLockAcquired(acquiredLock::set)
                                                      .onLockReleased(lock -> {
                                                      })
                                                      .build());

        // Then node1 holds the lock it committed and calls its callback
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(acquiredLock.get()).isNotNull());
        assertThat(storage.nextAcquireCommit).isNull();
        assertThat(acquiredLock.get().isLockedByThisLockManagerInstance()).isTrue();
        assertThat(acquiredLock.get().getCurrentToken()).isEqualTo(storage.getInitialTokenValue());
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node1");

        // When node1 stops acquiring it - as on shutdown
        node1.cancelAsyncLockAcquiring(LOCK_NAME);

        // Then the row is released, and another instance acquires the lock at once rather than after the lock timeout
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
        var node2Lock = node2.tryAcquireLock(LOCK_NAME);
        assertThat(node2Lock).isPresent();
        assertThat(node2Lock.get().getCurrentToken()).isEqualTo(storage.getInitialTokenValue() + 1);
    }

    private TestDBFencedLockManager createLockManager(String lockManagerInstanceId) {
        return createLockManager(lockManagerInstanceId, LOCK_TIME_OUT, null);
    }

    private TestDBFencedLockManager createLockManager(String lockManagerInstanceId, Duration lockTimeOut, EventBus eventBus) {
        var lockManager = new TestDBFencedLockManager(storage,
                                                      new StagingUnitOfWorkFactory(storage),
                                                      FencedLockManagerSettings.builder()
                                                                               .setLockManagerInstanceId(lockManagerInstanceId)
                                                                               .setLockTimeOut(lockTimeOut)
                                                                               .setLockConfirmationInterval(Duration.ofMillis(100))
                                                                               .build(),
                                                      eventBus);
        lockManager.start();
        return lockManager;
    }

    // ------------------------------------------------------------------------------------------------------------

    enum CommitOutcome {
        /**
         * The commit fails before reaching the DB: nothing is applied
         */
        NOT_APPLIED,
        /**
         * The commit is applied in the DB, but the connection is lost before the reply arrives
         */
        APPLIED_THEN_CONNECTION_LOST,
        /**
         * The commit is applied in the DB, but the thread is interrupted waiting for the reply - an interruptible driver
         * (MongoDB) then fails the commit, and fails every further operation on that thread while the interrupt is pending
         */
        APPLIED_THEN_INTERRUPTED,
        /**
         * Like {@link #APPLIED_THEN_CONNECTION_LOST}, and the connection stays down: the look-up of the commit's outcome fails too
         */
        APPLIED_THEN_CONNECTION_LOST_AND_LOOKUP_FAILS
    }

    record Row(long token, String lockedBy, OffsetDateTime lockAcquiredTimestamp, OffsetDateTime lockLastConfirmedTimestamp) {
    }

    static class TestDBFencedLockManager extends DBFencedLockManager<StagingUnitOfWork, DBFencedLock> {
        TestDBFencedLockManager(FencedLockStorage<StagingUnitOfWork, DBFencedLock> lockStorage,
                                UnitOfWorkFactory<StagingUnitOfWork> unitOfWorkFactory,
                                FencedLockManagerSettings settings) {
            this(lockStorage, unitOfWorkFactory, settings, null);
        }

        TestDBFencedLockManager(FencedLockStorage<StagingUnitOfWork, DBFencedLock> lockStorage,
                                UnitOfWorkFactory<StagingUnitOfWork> unitOfWorkFactory,
                                FencedLockManagerSettings settings,
                                EventBus eventBus) {
            super(lockStorage, unitOfWorkFactory, settings, eventBus);
        }
    }

    /**
     * A lock table in memory, shared by every lock manager. Writes are staged in the {@link StagingUnitOfWork} and applied
     * on its commit - as compare-and-set, like the conditional updates of the real storages. Reads fail while the
     * thread is interrupted, like an interruptible driver, unless {@link #failLookupsWhileInterrupted} is cleared.
     */
    static class InMemoryLockStorage implements FencedLockStorage<StagingUnitOfWork, DBFencedLock> {
        final    ConcurrentMap<LockName, Row> rows = new ConcurrentHashMap<>();
        /**
         * Consumed by the next commit of a unit of work that acquires a lock
         */
        volatile CommitOutcome                nextAcquireCommit;
        /**
         * Fails the next look-up, whatever it looks up
         */
        volatile boolean                      failNextLookup;
        /**
         * Thrown by the next look-up, whatever it looks up, when set
         */
        volatile Error                        failNextLookupWith;
        /**
         * Thrown by the next release in the DB, when set
         */
        volatile Error                        failNextReleaseWith;
        /**
         * Run by a commit of an acquire after it was applied in the DB and before it reports its failure
         */
        volatile Runnable                     afterAcquireApplied;
        /**
         * Run by every look-up of the lock with the given name, before it reads - a test blocks here to hold the lock manager's lock
         */
        volatile LockName                     blockedLookupName;
        volatile CountDownLatch               blockedLookupGate;
        /**
         * When cleared, a look-up no longer fails while the thread is interrupted - a driver that does not fail on a pending
         * interrupt
         */
        volatile boolean                      failLookupsWhileInterrupted = true;
        final AtomicInteger                   lookups = new AtomicInteger();

        @Override
        public void initializeLockStorage(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow) {
        }

        @Override
        public boolean insertLockIntoDB(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow, DBFencedLock initialLock,
                                        OffsetDateTime lockAcquiredAndLastConfirmedTimestamp) {
            if (rows.containsKey(initialLock.getName())) {
                return false;
            }
            var row = new Row(getInitialTokenValue(), lockManager.getLockManagerInstanceId(), lockAcquiredAndLastConfirmedTimestamp, lockAcquiredAndLastConfirmedTimestamp);
            uow.stageAcquire(() -> rows.putIfAbsent(initialLock.getName(), row));
            return true;
        }

        @Override
        public boolean updateLockInDB(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow, DBFencedLock timedOutLock,
                                      DBFencedLock newLockReadyToBeAcquiredLocally) {
            var current = rows.get(timedOutLock.getName());
            if (current == null || current.token() != timedOutLock.getCurrentToken() ||
                    !Objects.equals(current.lockLastConfirmedTimestamp(), timedOutLock.getLockLastConfirmedTimestamp())) {
                return false;
            }
            var row = new Row(newLockReadyToBeAcquiredLocally.getCurrentToken(),
                              newLockReadyToBeAcquiredLocally.getLockedByLockManagerInstanceId(),
                              newLockReadyToBeAcquiredLocally.getLockAcquiredTimestamp(),
                              newLockReadyToBeAcquiredLocally.getLockLastConfirmedTimestamp());
            uow.stageAcquire(() -> rows.replace(timedOutLock.getName(), current, row));
            return true;
        }

        @Override
        public boolean confirmLockInDB(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow, DBFencedLock fencedLock, OffsetDateTime confirmedTimestamp) {
            var current = rows.get(fencedLock.getName());
            if (current == null || current.token() != fencedLock.getCurrentToken() || !Objects.equals(current.lockedBy(), fencedLock.getLockedByLockManagerInstanceId())) {
                return false;
            }
            uow.stage(() -> rows.replace(fencedLock.getName(), current, new Row(current.token(), current.lockedBy(), current.lockAcquiredTimestamp(), confirmedTimestamp)));
            return true;
        }

        /**
         * Matches on lock name and fence token only, like the real storages
         */
        @Override
        public boolean releaseLockInDB(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow, DBFencedLock fencedLock) {
            var failure = failNextReleaseWith;
            if (failure != null) {
                failNextReleaseWith = null;
                throw failure;
            }
            var current = rows.get(fencedLock.getName());
            if (current == null || current.token() != fencedLock.getCurrentToken()) {
                return false;
            }
            uow.stage(() -> rows.replace(fencedLock.getName(), current, new Row(current.token(), null, current.lockAcquiredTimestamp(), current.lockLastConfirmedTimestamp())));
            return true;
        }

        @Override
        public Optional<DBFencedLock> lookupLockInDB(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow, LockName lockName) {
            lookups.incrementAndGet();
            var gate = blockedLookupGate;
            if (gate != null && lockName.equals(blockedLookupName)) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            var failure = failNextLookupWith;
            if (failure != null) {
                failNextLookupWith = null;
                throw failure;
            }
            if (failNextLookup) {
                failNextLookup = false;
                throw new UncheckedIOException(new IOException("Connection lost looking up the lock"));
            }
            if (failLookupsWhileInterrupted && Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Interrupted - an interruptible driver fails every operation while the interrupt is pending");
            }
            return Optional.ofNullable(rows.get(lockName))
                           .map(row -> createInitializedLock(lockManager, lockName, row.token(), row.lockedBy(), row.lockAcquiredTimestamp(), row.lockLastConfirmedTimestamp()));
        }

        @Override
        public DBFencedLock createUninitializedLock(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, LockName lockName) {
            return DBFencedLock.builder()
                               .setFencedLockManager(lockManager)
                               .setLockName(lockName)
                               .setCurrentToken(getUninitializedTokenValue())
                               .build();
        }

        @Override
        public DBFencedLock createInitializedLock(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, LockName name, long currentToken, String lockedByLockManagerInstanceId,
                                                  OffsetDateTime lockAcquiredTimestamp, OffsetDateTime lockLastConfirmedTimestamp) {
            return DBFencedLock.builder()
                               .setFencedLockManager(lockManager)
                               .setLockName(name)
                               .setCurrentToken(currentToken)
                               .setLockedByBusInstanceId(lockedByLockManagerInstanceId)
                               .setLockAcquiredTimestamp(lockAcquiredTimestamp)
                               .setLockLastConfirmedTimestamp(lockLastConfirmedTimestamp)
                               .build();
        }

        @Override
        public Long getUninitializedTokenValue() {
            return -1L;
        }

        @Override
        public long getInitialTokenValue() {
            return 1L;
        }

        @Override
        public void deleteLockInDB(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow, LockName nameOfLockToDelete) {
            rows.remove(nameOfLockToDelete);
        }

        @Override
        public void deleteAllLocksInDB(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow) {
            rows.clear();
        }

        @Override
        public List<DBFencedLock> getAllLocksInDB(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow) {
            return List.of();
        }

        @Override
        public List<DBFencedLock> getAllLocksInDB(DBFencedLockManager<StagingUnitOfWork, DBFencedLock> lockManager, StagingUnitOfWork uow, long startIndex, long pageSize) {
            return List.of();
        }
    }

    /**
     * The lock manager runs on several threads (caller, confirmation, async acquiring), so the current unit of work is per thread
     */
    static class StagingUnitOfWorkFactory implements UnitOfWorkFactory<StagingUnitOfWork> {
        private final ThreadLocal<StagingUnitOfWork> currentUnitOfWork = new ThreadLocal<>();
        private final InMemoryLockStorage            storage;

        StagingUnitOfWorkFactory(InMemoryLockStorage storage) {
            this.storage = storage;
        }

        @Override
        public StagingUnitOfWork getRequiredUnitOfWork() {
            var unitOfWork = currentUnitOfWork.get();
            if (unitOfWork == null) {
                throw new NoActiveUnitOfWorkException();
            }
            return unitOfWork;
        }

        @Override
        public StagingUnitOfWork getOrCreateNewUnitOfWork() {
            var unitOfWork = currentUnitOfWork.get();
            if (unitOfWork == null) {
                unitOfWork = new StagingUnitOfWork(currentUnitOfWork::remove, storage);
                currentUnitOfWork.set(unitOfWork);
                unitOfWork.start();
            }
            return unitOfWork;
        }

        @Override
        public Optional<StagingUnitOfWork> getCurrentUnitOfWork() {
            return Optional.ofNullable(currentUnitOfWork.get());
        }
    }

    static class StagingUnitOfWork implements UnitOfWork {
        private final    Runnable            onCompleted;
        private final    InMemoryLockStorage storage;
        private final    List<Runnable>      stagedWrites = new ArrayList<>();
        private          boolean             acquires;
        private volatile UnitOfWorkStatus    status;
        private volatile Throwable           causeOfRollback;

        StagingUnitOfWork(Runnable onCompleted, InMemoryLockStorage storage) {
            this.onCompleted = onCompleted;
            this.storage = storage;
        }

        void stage(Runnable write) {
            stagedWrites.add(write);
        }

        void stageAcquire(Runnable write) {
            acquires = true;
            stage(write);
        }

        @Override
        public void start() {
            status = UnitOfWorkStatus.Started;
        }

        @Override
        public void commit() {
            if (status == UnitOfWorkStatus.MarkedForRollbackOnly) {
                rollback(causeOfRollback);
                return;
            }
            var outcome = acquires ? storage.nextAcquireCommit : null;
            if (outcome != null) {
                storage.nextAcquireCommit = null;
            }
            if (outcome == CommitOutcome.NOT_APPLIED) {
                throw new UncheckedIOException(new IOException("Connection lost before the commit reached the DB"));
            }
            stagedWrites.forEach(Runnable::run);
            status = UnitOfWorkStatus.Committed;
            onCompleted.run();
            var afterApplied = acquires ? storage.afterAcquireApplied : null;
            if (afterApplied != null) {
                storage.afterAcquireApplied = null;
                afterApplied.run();
            }
            if (outcome == CommitOutcome.APPLIED_THEN_CONNECTION_LOST_AND_LOOKUP_FAILS) {
                storage.failNextLookup = true;
                throw new UncheckedIOException(new IOException("Connection lost waiting for the commit reply"));
            }
            if (outcome == CommitOutcome.APPLIED_THEN_CONNECTION_LOST) {
                throw new UncheckedIOException(new IOException("Connection lost waiting for the commit reply"));
            }
            if (outcome == CommitOutcome.APPLIED_THEN_INTERRUPTED) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the commit reply");
            }
        }

        @Override
        public void rollback(Throwable cause) {
            stagedWrites.clear();
            status = UnitOfWorkStatus.RolledBack;
            causeOfRollback = cause;
            onCompleted.run();
        }

        @Override
        public UnitOfWorkStatus status() {
            return status;
        }

        @Override
        public Throwable getCauseOfRollback() {
            return causeOfRollback;
        }

        @Override
        public void markAsRollbackOnly(Throwable cause) {
            status = UnitOfWorkStatus.MarkedForRollbackOnly;
            causeOfRollback = cause;
        }

        @Override
        public <T> T registerLifecycleCallbackForResource(T resource, UnitOfWorkLifecycleCallback<T> associatedUnitOfWorkCallback) {
            return resource;
        }

        @Override
        public <T> List<T> getUnitOfWorkLifecycleCallbackResources(UnitOfWorkLifecycleCallback<T> associatedUnitOfWorkCallback) {
            return List.of();
        }
    }
}
