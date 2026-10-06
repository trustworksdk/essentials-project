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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lock this instance fails to confirm is lost, so it must be released locally - and {@link LockCallback#lockReleased(FencedLock)}
 * called - without waiting for the DB release attempt. When the DB is unreachable that attempt can take several times the
 * socket timeout (MongoDB driver 5.12+ retries connection establishment with backoff), and the instance would keep
 * reporting a lock another instance may already have taken over.
 * <p>
 * The storage here blocks {@link FencedLockStorage#releaseLockInDB} until the test lets it go, so a local release that
 * waits on the DB never happens within the assertion window.
 * <p>
 * The DB release is best effort, so it must neither undo the confirmations made in the same tick (it runs in its own
 * {@link UnitOfWork}, after the confirmation one has completed) nor hold up the confirmation thread against a DB that has
 * just failed on IO (no DB release when the confirmation {@link UnitOfWork} failed on IO, and none after the first IO failure).
 */
class DBFencedLockManagerLostLockReleaseTest {
    private static final LockName LOCK_NAME       = LockName.of("lost-lock");
    private static final LockName OTHER_LOCK_NAME = LockName.of("other-lock");

    private BlockingReleaseLockStorage   storage;
    private ThreadLocalUnitOfWorkFactory unitOfWorkFactory;
    private TestDBFencedLockManager      lockManager;

    @BeforeEach
    void setup() {
        storage = new BlockingReleaseLockStorage();
        unitOfWorkFactory = new ThreadLocalUnitOfWorkFactory();
        lockManager = new TestDBFencedLockManager(storage,
                                                  unitOfWorkFactory,
                                                  FencedLockManagerSettings.builder()
                                                                           .setLockManagerInstanceId("node1")
                                                                           .setLockTimeOut(Duration.ofSeconds(3))
                                                                           .setLockConfirmationInterval(Duration.ofMillis(100))
                                                                           .setReleaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation(true)
                                                                           .build());
        lockManager.start();
    }

    @AfterEach
    void cleanup() {
        storage.allowReleaseInDB.countDown();
        lockManager.stop();
    }

    @Test
    void a_lock_reported_as_taken_over_is_released_locally_before_the_db_release_completes() {
        verifyLostLockIsReleasedLocallyFirst(ConfirmOutcome.TAKEN_OVER);
    }

    @Test
    void a_lock_whose_confirmation_fails_with_an_io_error_is_released_locally_before_the_db_release_completes() {
        verifyLostLockIsReleasedLocallyFirst(ConfirmOutcome.IO_FAILURE);
    }

    @Test
    void a_failing_db_release_of_a_lost_lock_does_not_roll_back_the_confirmations_made_in_the_same_tick() {
        // Given
        storage.allowReleaseInDB.countDown();
        storage.releaseInDBFailure = new IllegalStateException("Release failed");
        var confirmedLock = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();
        var lostLock      = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();

        // When
        storage.confirmOutcomeByLock.put(LOCK_NAME, ConfirmOutcome.TAKEN_OVER);

        // Then the DB release is attempted in a UnitOfWork of its own
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(storage.releaseInDBUnitOfWorks).hasSize(1));
        assertThat(lostLock.isLocked()).isFalse();
        var confirmationUnitOfWork = storage.lastConfirmationUnitOfWork.get(LOCK_NAME);
        assertThat(storage.releaseInDBUnitOfWorks.getFirst()).isNotSameAs(confirmationUnitOfWork);

        // And the confirmation UnitOfWork of that tick - which also confirmed the other lock - is committed, not rolled back
        assertThat(confirmationUnitOfWork.status()).isEqualTo(UnitOfWorkStatus.Committed);
        assertThat(confirmedLock.isLocked()).isTrue();
    }

    @Test
    void no_db_release_is_attempted_when_the_confirmation_unit_of_work_fails_with_an_io_error() {
        // Given
        storage.allowReleaseInDB.countDown();
        var lock      = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var otherLock = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();

        // When
        unitOfWorkFactory.failCommitWithIOException = true;

        // Then every lock is released locally
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(lock.isLocked()).isFalse();
                      assertThat(otherLock.isLocked()).isFalse();
                  });
        // And none in the DB that has just failed on IO - the rows expire after lockTimeOut
        assertThat(storage.releaseInDBUnitOfWorks).isEmpty();
    }

    @Test
    void the_db_release_of_lost_locks_stops_at_the_first_io_failure() {
        // Given
        storage.allowReleaseInDB.countDown();
        storage.releaseInDBFailure = new UncheckedIOException(new IOException("Connection lost"));
        var lock      = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var otherLock = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();

        // When
        storage.confirmOutcome = ConfirmOutcome.IO_FAILURE;

        // Then both locks are released locally, but only one DB release is attempted
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(lock.isLocked()).isFalse();
                      assertThat(otherLock.isLocked()).isFalse();
                      assertThat(storage.releaseInDBUnitOfWorks).hasSize(1);
                  });
        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> assertThat(storage.releaseInDBUnitOfWorks).hasSize(1));
    }

    private void verifyLostLockIsReleasedLocallyFirst(ConfirmOutcome lostOutcome) {
        // Given
        var lock = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var releasedLock = new AtomicReference<FencedLock>();
        lock.registerCallback(new LockCallback() {
            @Override
            public void lockAcquired(FencedLock lock) {
            }

            @Override
            public void lockReleased(FencedLock lock) {
                releasedLock.set(lock);
            }
        });
        assertThat(lock.isLocked()).isTrue();

        // When
        storage.confirmOutcome = lostOutcome;

        // Then the lock is released locally while the DB release is still blocked
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(releasedLock.get()).isSameAs(lock));
        assertThat(lock.isLocked()).isFalse();
        assertThat(storage.releasedInDBTokens).isEmpty();

        // And the DB release is still attempted, for the token the lost lock held
        storage.allowReleaseInDB.countDown();
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(storage.releasedInDBTokens).containsExactly(storage.getInitialTokenValue()));
    }

    // ------------------------------------------------------------------------------------------------------------

    enum ConfirmOutcome {
        CONFIRMED,
        TAKEN_OVER,
        IO_FAILURE
    }

    static class TestDBFencedLockManager extends DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> {
        TestDBFencedLockManager(FencedLockStorage<ThreadLocalUnitOfWork, DBFencedLock> lockStorage,
                                UnitOfWorkFactory<ThreadLocalUnitOfWork> unitOfWorkFactory,
                                FencedLockManagerSettings settings) {
            super(lockStorage, unitOfWorkFactory, settings, null);
        }
    }

    /**
     * Stores nothing: a lock is acquired by insert, confirmed per {@link #confirmOutcomeByLock} falling back to
     * {@link #confirmOutcome}, and released in the DB only once {@link #allowReleaseInDB} is counted down - then failing
     * with {@link #releaseInDBFailure} when set.
     */
    static class BlockingReleaseLockStorage implements FencedLockStorage<ThreadLocalUnitOfWork, DBFencedLock> {
        final    CountDownLatch                       allowReleaseInDB           = new CountDownLatch(1);
        final    List<Long>                           releasedInDBTokens         = new CopyOnWriteArrayList<>();
        final    Map<LockName, ConfirmOutcome>        confirmOutcomeByLock       = new ConcurrentHashMap<>();
        final    Map<LockName, ThreadLocalUnitOfWork> lastConfirmationUnitOfWork = new ConcurrentHashMap<>();
        final    List<ThreadLocalUnitOfWork>          releaseInDBUnitOfWorks     = new CopyOnWriteArrayList<>();
        volatile ConfirmOutcome                       confirmOutcome             = ConfirmOutcome.CONFIRMED;
        volatile RuntimeException                     releaseInDBFailure;

        @Override
        public void initializeLockStorage(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow) {
        }

        @Override
        public boolean confirmLockInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, DBFencedLock fencedLock, OffsetDateTime confirmedTimestamp) {
            lastConfirmationUnitOfWork.put(fencedLock.getName(), uow);
            return switch (confirmOutcomeByLock.getOrDefault(fencedLock.getName(), confirmOutcome)) {
                case CONFIRMED -> true;
                case TAKEN_OVER -> false;
                case IO_FAILURE -> throw new UncheckedIOException(new IOException("Connection lost"));
            };
        }

        @Override
        public boolean releaseLockInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, DBFencedLock fencedLock) {
            releaseInDBUnitOfWorks.add(uow);
            try {
                if (!allowReleaseInDB.await(30, TimeUnit.SECONDS)) {
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (releaseInDBFailure != null) {
                throw releaseInDBFailure;
            }
            releasedInDBTokens.add(fencedLock.getCurrentToken());
            return true;
        }

        @Override
        public Optional<DBFencedLock> lookupLockInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, LockName lockName) {
            return Optional.empty();
        }

        @Override
        public DBFencedLock createUninitializedLock(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, LockName lockName) {
            return DBFencedLock.builder()
                               .setFencedLockManager(lockManager)
                               .setLockName(lockName)
                               .setCurrentToken(getUninitializedTokenValue())
                               .build();
        }

        @Override
        public DBFencedLock createInitializedLock(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, LockName name, long currentToken, String lockedByLockManagerInstanceId,
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
        public boolean insertLockIntoDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, DBFencedLock initialLock, OffsetDateTime lockAcquiredAndLastConfirmedTimestamp) {
            return true;
        }

        @Override
        public boolean updateLockInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, DBFencedLock timedOutLock, DBFencedLock newLockReadyToBeAcquiredLocally) {
            return false;
        }

        @Override
        public void deleteLockInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, LockName nameOfLockToDelete) {
        }

        @Override
        public void deleteAllLocksInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow) {
        }

        @Override
        public List<DBFencedLock> getAllLocksInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow) {
            return List.of();
        }

        @Override
        public List<DBFencedLock> getAllLocksInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, long startIndex, long pageSize) {
            return List.of();
        }
    }

    /**
     * The lock manager runs on several threads (caller, confirmation, async acquiring), so the current unit of work is per thread.
     */
    static class ThreadLocalUnitOfWorkFactory implements UnitOfWorkFactory<ThreadLocalUnitOfWork> {
        private final ThreadLocal<ThreadLocalUnitOfWork> currentUnitOfWork = new ThreadLocal<>();
        /**
         * When set, every commit fails with an IO exception - as when the DB has become unreachable
         */
        volatile boolean failCommitWithIOException;

        @Override
        public ThreadLocalUnitOfWork getRequiredUnitOfWork() {
            var unitOfWork = currentUnitOfWork.get();
            if (unitOfWork == null) {
                throw new NoActiveUnitOfWorkException();
            }
            return unitOfWork;
        }

        @Override
        public ThreadLocalUnitOfWork getOrCreateNewUnitOfWork() {
            var unitOfWork = currentUnitOfWork.get();
            if (unitOfWork == null) {
                unitOfWork = new ThreadLocalUnitOfWork(currentUnitOfWork::remove, () -> failCommitWithIOException);
                currentUnitOfWork.set(unitOfWork);
                unitOfWork.start();
            }
            return unitOfWork;
        }

        @Override
        public Optional<ThreadLocalUnitOfWork> getCurrentUnitOfWork() {
            return Optional.ofNullable(currentUnitOfWork.get());
        }
    }

    /**
     * Like the real unit of work implementations, committing a unit of work marked rollback-only rolls it back
     */
    static class ThreadLocalUnitOfWork implements UnitOfWork {
        private final    Runnable         onCompleted;
        private final    BooleanSupplier  failCommitWithIOException;
        private volatile UnitOfWorkStatus status;
        private volatile Throwable        causeOfRollback;

        ThreadLocalUnitOfWork(Runnable onCompleted, BooleanSupplier failCommitWithIOException) {
            this.onCompleted = onCompleted;
            this.failCommitWithIOException = failCommitWithIOException;
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
            if (failCommitWithIOException.getAsBoolean()) {
                throw new UncheckedIOException(new IOException("Connection lost during commit"));
            }
            status = UnitOfWorkStatus.Committed;
            onCompleted.run();
        }

        @Override
        public void rollback(Throwable cause) {
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
