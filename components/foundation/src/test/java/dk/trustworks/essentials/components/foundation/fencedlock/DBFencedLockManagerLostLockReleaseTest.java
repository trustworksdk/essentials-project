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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lock this instance fails to confirm is lost, so it must be released locally - and {@link LockCallback#lockReleased(FencedLock)}
 * called - without waiting for the DB release attempt. When the DB is unreachable that attempt can take several times the
 * socket timeout (MongoDB driver 5.12+ retries connection establishment with backoff), and the instance would keep
 * reporting a lock another instance may already have taken over.
 * <p>
 * The storage here blocks {@link FencedLockStorage#releaseLockInDB} until the test lets it go, so a local release that
 * waits on the DB never happens within the assertion window.
 */
class DBFencedLockManagerLostLockReleaseTest {
    private static final LockName LOCK_NAME = LockName.of("lost-lock");

    private BlockingReleaseLockStorage storage;
    private TestDBFencedLockManager    lockManager;

    @BeforeEach
    void setup() {
        storage = new BlockingReleaseLockStorage();
        lockManager = new TestDBFencedLockManager(storage,
                                                  new ThreadLocalUnitOfWorkFactory(),
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
     * Stores nothing: the single lock is acquired by insert, confirmed per {@link #confirmOutcome}, and released in the DB
     * only once {@link #allowReleaseInDB} is counted down.
     */
    static class BlockingReleaseLockStorage implements FencedLockStorage<ThreadLocalUnitOfWork, DBFencedLock> {
        final    CountDownLatch  allowReleaseInDB   = new CountDownLatch(1);
        final    List<Long>      releasedInDBTokens = new CopyOnWriteArrayList<>();
        volatile ConfirmOutcome  confirmOutcome     = ConfirmOutcome.CONFIRMED;

        @Override
        public void initializeLockStorage(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow) {
        }

        @Override
        public boolean confirmLockInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, DBFencedLock fencedLock, OffsetDateTime confirmedTimestamp) {
            return switch (confirmOutcome) {
                case CONFIRMED -> true;
                case TAKEN_OVER -> false;
                case IO_FAILURE -> throw new UncheckedIOException(new IOException("Connection lost"));
            };
        }

        @Override
        public boolean releaseLockInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, DBFencedLock fencedLock) {
            try {
                if (!allowReleaseInDB.await(30, TimeUnit.SECONDS)) {
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
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
                unitOfWork = new ThreadLocalUnitOfWork(currentUnitOfWork::remove);
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

    static class ThreadLocalUnitOfWork implements UnitOfWork {
        private final Runnable         onCompleted;
        private       UnitOfWorkStatus status;
        private       Throwable        causeOfRollback;

        ThreadLocalUnitOfWork(Runnable onCompleted) {
            this.onCompleted = onCompleted;
        }

        @Override
        public void start() {
            status = UnitOfWorkStatus.Started;
        }

        @Override
        public void commit() {
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
