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

package dk.trustworks.essentials.components.foundation.test.fencedlock;

import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.time.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.*;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class DBFencedLockManagerIT<LOCK_MANAGER extends DBFencedLockManager<?, ?>> {
    private static final Logger log = LoggerFactory.getLogger(DBFencedLockManagerIT.class);

    private LOCK_MANAGER lockManagerNode1;
    private LOCK_MANAGER lockManagerNode2;
    /**
     * Set while a test has the database connection disrupted, so {@link #cleanup()} can restore it if the test fails first
     */
    private boolean      databaseConnectionDisrupted;

    protected LOCK_MANAGER getLockManagerNode1() {
        return lockManagerNode1;
    }

    protected LOCK_MANAGER getLockManagerNode2() {
        return lockManagerNode2;
    }

    @BeforeEach
    void setup() {
        if (lockManagerNode1 != null) {
            throw new IllegalStateException("LockManager for node1 is non-null during setup");
        }
        if (lockManagerNode2 != null) {
            throw new IllegalStateException("LockManager for node2 is non-null during setup");
        }


        lockManagerNode1 = createLockManagerNode1();
        assertThat(lockManagerNode1).isNotNull();
        deleteAllLocksInDBWithRetry(lockManagerNode1);
        lockManagerNode1.start();

        lockManagerNode2 = createLockManagerNode2();
        assertThat(lockManagerNode1).isNotNull();
        lockManagerNode2.start();
    }

    protected abstract LOCK_MANAGER createLockManagerNode2();

    protected abstract LOCK_MANAGER createLockManagerNode1();

    protected abstract void disruptDatabaseConnection();

    protected abstract void restoreDatabaseConnection();

    protected abstract boolean isConnectionRestored();

    /**
     * Bound for the lock outcome after a database outage. Far above the lock managers' 1 s confirm interval and 3 s lock
     * timeout, because the first ticks after the outage may first have to finish operations that stalled during it, and
     * (MongoDB) a lock document can stay write-locked until the server aborts a transaction left behind by the outage.
     */
    private static final Duration LOCK_OUTCOME_TIMEOUT = Duration.ofSeconds(30);

    /**
     * @return true when the database says node 1 owns the lock and has confirmed it at or after {@code since} - i.e. a
     * confirmation that started after that point succeeded
     */
    private boolean isLockConfirmedByNode1Since(LockName lockName, OffsetDateTime since) {
        return lockManagerNode1.lookupLock(lockName)
                               .filter(lock -> lockManagerNode1.getLockManagerInstanceId().equals(lock.getLockedByLockManagerInstanceId()))
                               .map(FencedLock::getLockLastConfirmedTimestamp)
                               .filter(lastConfirmed -> !lastConfirmed.isBefore(since))
                               .isPresent();
    }

    private void disruptDatabaseConnectionUntilRestored() {
        databaseConnectionDisrupted = true;
        disruptDatabaseConnection();
    }

    private void restoreDatabaseConnectionAndAwaitReachability() {
        restoreDatabaseConnection();
        databaseConnectionDisrupted = false;
        Awaitility.waitAtMost(Duration.ofSeconds(30))
                  .pollInterval(Duration.ofMillis(250))
                  .until(this::isConnectionRestored);
    }

    /**
     * How long {@link #deleteAllLocksInDBWithRetry(DBFencedLockManager)} keeps retrying. Generous on purpose: after a test
     * has paused the database, the server can take several seconds to be reachable again, and a lock document can stay
     * write-locked (every write a {@code WriteConflict}) until the server aborts a transaction that a commit left behind
     * during the outage. Five quick retries (1.5 s in total) were not enough for either, and a failed cleanup fails the
     * <em>next</em> test, which hides the one that caused it.
     */
    private static final Duration DELETE_ALL_LOCKS_TIMEOUT = Duration.ofSeconds(30);

    /**
     * PostgreSQL SQL states that clear by themselves: {@code 40001} serialization failure, {@code 40P01} deadlock detected and
     * {@code 55P03} lock not available
     */
    private static final Set<String> TRANSIENT_POSTGRESQL_SQL_STATES = Set.of("40001", "40P01", "55P03");

    /**
     * Only failures that can clear by themselves are worth the 30 s window, matched anywhere in the cause chain:
     * <ul>
     *     <li>an I/O failure - the database still being unreachable</li>
     *     <li>a {@code WriteConflict} - a lock document that stays write-locked</li>
     *     <li>on MongoDB, an exception labelled {@code TransientTransactionError}, or a server-selection timeout</li>
     *     <li>on PostgreSQL, an {@link SQLException} with one of the {@link #TRANSIENT_POSTGRESQL_SQL_STATES}</li>
     * </ul>
     * A missing table, a bad statement or a regression in {@code deleteAllLocksInDB} is permanent and fails at once, with its
     * own stack trace.
     */
    private static boolean isRetryableCleanupFailure(Throwable e) {
        if (IOExceptionUtil.isIOException(e)) {
            return true;
        }
        var depth = 0;
        for (var t = e; t != null && depth < 100; t = t.getCause() == t ? null : t.getCause(), depth++) {
            var message = t.getMessage();
            if (message != null && (message.contains("WriteConflict") || message.contains("Write Conflict"))) {
                return true;
            }
            if (t instanceof SQLException sqlException && TRANSIENT_POSTGRESQL_SQL_STATES.contains(sqlException.getSQLState())) {
                return true;
            }
            if (isTransientMongoFailure(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matched by class name and reflection: foundation-test does not depend on the MongoDB driver, and must not gain a
     * dependency on it for a test helper that the PostgreSQL ITs share. A {@code MongoTimeoutException} is what server
     * selection throws when no suitable server is reachable in time.
     */
    private static boolean isTransientMongoFailure(Throwable t) {
        for (Class<?> type = t.getClass(); type != null; type = type.getSuperclass()) {
            switch (type.getName()) {
                case "com.mongodb.MongoTimeoutException":
                    return true;
                case "com.mongodb.MongoException":
                    try {
                        return Boolean.TRUE.equals(type.getMethod("hasErrorLabel", String.class).invoke(t, "TransientTransactionError"));
                    } catch (ReflectiveOperationException | RuntimeException reflectionFailure) {
                        return false;
                    }
                default:
                    break;
            }
        }
        return false;
    }

    public static void deleteAllLocksInDBWithRetry(DBFencedLockManager<?, ?> lockManager) {
        var              deadline   = System.nanoTime() + DELETE_ALL_LOCKS_TIMEOUT.toNanos();
        var              attempt    = 0;
        RuntimeException lastFailure;
        do {
            try {
                lockManager.deleteAllLocksInDB();
                return;
            } catch (RuntimeException e) {
                if (!isRetryableCleanupFailure(e)) {
                    throw e;
                }
                lastFailure = e;
                attempt++;
                log.debug("deleteAllLocksInDB attempt {} failed - retrying: {}", attempt, e.getMessage());
                try {
                    Thread.sleep(Math.min(100L * attempt, 1000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while retrying deleteAllLocksInDB", ie);
                }
            }
        } while (System.nanoTime() < deadline);
        throw new RuntimeException("Failed to deleteAllLocksInDB within " + DELETE_ALL_LOCKS_TIMEOUT + " (" + attempt + " attempts)", lastFailure);
    }

    @AfterEach
    void cleanup() {
        log.info("*******  Cleaning up  *******");
        // A test that failed while the database was disrupted must not leave it unreachable for the next test
        if (databaseConnectionDisrupted) {
            log.info("------ Restoring the database connection left disrupted by the test ------");
            restoreDatabaseConnectionAndAwaitReachability();
        }
        if (lockManagerNode1 != null) {
            lockManagerNode1.stop();
            lockManagerNode1 = null;
        }
        if (lockManagerNode2 != null) {
            lockManagerNode2.stop();
            lockManagerNode2 = null;
        }
    }


    @Test
    void verify_that_we_can_perform_tryAcquire_on_a_lock_and_release_it_again() {
        var lockName = LockName.of("testLock");
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isFalse();

        // When
        var lockOptionNode1 = lockManagerNode1.tryAcquireLock(lockName);
        var lockOptionNode2 = lockManagerNode2.tryAcquireLock(lockName);

        // Then
        assertThat(lockOptionNode1).isNotNull();
        assertThat(lockOptionNode1).isPresent();
        assertThat(lockOptionNode1.get().isLocked()).isTrue();
        assertThat(lockOptionNode1.get().getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lockOptionNode1.get().isLockedByThisLockManagerInstance()).isTrue();
        var actualLock = lockManagerNode1.lookupLock(lockName);
        assertThat(actualLock).isEqualTo(lockOptionNode1);
        assertThat(actualLock.get().getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isTrue();

        // Node 2 must not be able to acquire the same lock
        assertThat(lockOptionNode2).isNotNull();
        assertThat(lockOptionNode2).isEmpty();

        // When
        lockOptionNode1.get().release();

        // Then
        assertThat(lockOptionNode1.get().isLocked()).isFalse();
        assertThat(lockOptionNode1.get().getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lockOptionNode1.get().isLockedByThisLockManagerInstance()).isFalse();
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isFalse();
    }

    /**
     * QL-1: pins the contract {@link FencedLockManager}'s Javadoc and LLM-foundation.md document - ownership is per
     * lock-manager instance, not per caller or thread. If this starts failing, the behaviour changed and so must the docs.
     */
    @Test
    void a_second_acquire_on_the_same_manager_returns_the_same_lock_and_either_release_releases_it_for_both() throws Exception {
        var lockName = LockName.of("testLock");
        var first    = lockManagerNode1.tryAcquireLock(lockName);
        // Another caller in the same JVM, on another thread
        var second = CompletableFuture.supplyAsync(() -> lockManagerNode1.tryAcquireLock(lockName)).get(10, TimeUnit.SECONDS);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get()).isSameAs(first.get());
        assertThat(lockManagerNode1.acquireLock(lockName)).isSameAs(first.get());

        // When the second caller releases "its" lock
        second.get().release();

        // Then the first caller's lock is released too, and another node can take it while the first caller still works
        assertThat(first.get().isLocked()).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isFalse();
        assertThat(lockManagerNode2.tryAcquireLock(lockName)).isPresent();
    }

    @Test
    void verify_that_we_can_acquire_a_lock_and_release_it_again() {
        // Given
        var lockName = LockName.of("testLock");
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isFalse();

        // When
        var lockNode1       = lockManagerNode1.acquireLock(lockName);
        var lockOptionNode2 = lockManagerNode2.tryAcquireLock(lockName);

        // Then
        assertThat(lockNode1).isNotNull();
        assertThat(lockNode1.isLocked()).isTrue();
        assertThat(lockNode1.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lockNode1.isLockedByThisLockManagerInstance()).isTrue();

        // Node 2 must not be able to acquire the same lock
        assertThat(lockOptionNode2).isNotNull();
        assertThat(lockOptionNode2).isEmpty();

        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isTrue();

        // When
        lockNode1.release();

        // Then
        assertThat(lockNode1.isLocked()).isFalse();
        assertThat(lockNode1.isLockedByThisLockManagerInstance()).isFalse();
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isFalse();
    }

    @Test
    void the_blocking_acquires_acquire_a_lock_another_node_releases_while_they_wait() throws Exception {
        // Given node1 holds the lock
        var lockName  = LockName.of("blockingAcquireLock");
        var lockNode1 = lockManagerNode1.acquireLock(lockName);
        var executor  = Executors.newSingleThreadScheduledExecutor();
        try {
            // Then a timed acquire on node2 that runs out returns empty rather than throwing
            assertThat(lockManagerNode2.tryAcquireLock(lockName, Duration.ofMillis(500))).isEmpty();

            // When node1 releases the lock while node2 waits for it with a timeout
            executor.schedule(lockNode1::release, 300, TimeUnit.MILLISECONDS);
            var lockNode2 = lockManagerNode2.tryAcquireLock(lockName, Duration.ofSeconds(10));

            // Then node2 acquires it with the next token
            assertThat(lockNode2).isPresent();
            assertThat(lockNode2.get().isLockedByThisLockManagerInstance()).isTrue();
            assertThat(lockNode2.get().getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue() + 1);

            // When node2 releases it while node1 waits in the untimed acquire
            executor.schedule(lockNode2.get()::release, 300, TimeUnit.MILLISECONDS);
            var lockNode1Again = CompletableFuture.supplyAsync(() -> lockManagerNode1.acquireLock(lockName))
                                                  .get(10, TimeUnit.SECONDS);

            // Then node1 acquires it again
            assertThat(lockNode1Again.isLockedByThisLockManagerInstance()).isTrue();
            assertThat(lockNode1Again.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue() + 2);
            lockNode1Again.release();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stopping_a_lockManager_releases_all_acquired_locks() {
        // Given
        var lock1 = LockName.of("lock1");
        var lock2 = LockName.of("lock2");
        assertThat(lockManagerNode1.isLockAcquired(lock1)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lock1)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lock2)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lock2)).isFalse();

        // When
        var lock1Node1       = lockManagerNode1.acquireLock(lock1);
        var lock2Node1       = lockManagerNode1.acquireLock(lock2);
        var lock1OptionNode2 = lockManagerNode2.tryAcquireLock(lock1);
        var lock2OptionNode2 = lockManagerNode2.tryAcquireLock(lock2);


        // Then
        assertThat(lock1Node1.isLocked()).isTrue();
        assertThat(lock1Node1.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lock2Node1.isLocked()).isTrue();
        assertThat(lock2Node1.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lock1OptionNode2).isEmpty();
        assertThat(lock2OptionNode2).isEmpty();

        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lock1)).isTrue();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lock1)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lock1)).isTrue();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lock1)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lock1)).isTrue();
        assertThat(lockManagerNode2.isLockAcquired(lock1)).isTrue();
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lock2)).isTrue();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lock2)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lock2)).isTrue();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lock2)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lock2)).isTrue();
        assertThat(lockManagerNode2.isLockAcquired(lock2)).isTrue();

        // When
        lockManagerNode1.stop();

        // Then
        assertThat(lock1Node1.isLocked()).isFalse();
        assertThat(lock2Node1.isLocked()).isFalse();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lock1)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lock1)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lock1)).isFalse();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lock2)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lock2)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lock2)).isFalse();

        // Test that node 2 can acquire the locks after they were release by lockManager1 shutdown
        // When
        lock1OptionNode2 = lockManagerNode2.tryAcquireLock(lock1);
        lock2OptionNode2 = lockManagerNode2.tryAcquireLock(lock2);

        // Then
        assertThat(lock1OptionNode2).isPresent();
        assertThat(lock1OptionNode2.get().isLocked()).isTrue();
        assertThat(lock1OptionNode2.get().getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue() + 1L);
        assertThat(lock1OptionNode2.get().isLockedByThisLockManagerInstance()).isTrue();
        assertThat(lock2OptionNode2).isPresent();
        assertThat(lock2OptionNode2).isPresent();
        assertThat(lock2OptionNode2.get().isLocked()).isTrue();
        assertThat(lock2OptionNode2.get().getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue() + 1L);
        assertThat(lock2OptionNode2.get().isLockedByThisLockManagerInstance()).isTrue();
    }

    @Test
    void verify_that_acquireLockAsync_allows_us_to_acquire_locks_asynchronously() throws InterruptedException {
        // Given
        var lockName = LockName.of("testLock");
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isFalse();

        var lockNode1Callback = new TestLockCallback();
        var lockNode2Callback = new TestLockCallback();

        // When
        lockManagerNode1.acquireLockAsync(lockName, lockNode1Callback);
        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue());
        lockManagerNode2.acquireLockAsync(lockName, lockNode2Callback);
        Thread.sleep(1000);

        // Then
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isTrue();
        // And
        assertThat(lockNode1Callback.lockAcquired).isNotNull();
        assertThat(lockNode1Callback.lockReleased).isNull();
        assertThat(lockNode1Callback.lockAcquired.isLocked());
        assertThat((CharSequence) lockNode1Callback.lockAcquired.getName()).isEqualTo(lockName);
        assertThat(lockNode1Callback.lockAcquired.getCurrentToken()).isGreaterThanOrEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lockNode1Callback.lockAcquired.isLockedByThisLockManagerInstance());

        // Node 2 must not be able to acquire the same lock
        assertThat(lockNode2Callback.lockAcquired).isNull();
        assertThat(lockNode2Callback.lockReleased).isNull();

        // And lock confirmation increases the lock token
        var lastLockConfirmedTimestamp = lockManagerNode1.lookupLock(lockName).get().getLockLastConfirmedTimestamp();
        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> {
                      var lock = lockManagerNode1.lookupLock(lockName);
                      assertThat(lock).isPresent();
                      assertThat(lock.get().getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
                      assertThat(lock.get().getLockLastConfirmedTimestamp()).isAfter(lastLockConfirmedTimestamp);
                  });
        // And both Lock Managers can see this changed lock state
        var lock = lockManagerNode2.lookupLock(lockName);
        assertThat(lock).isPresent();
        assertThat(lock.get().getLockLastConfirmedTimestamp()).isAfter(lastLockConfirmedTimestamp);

        // When
        // Node 1 MUST be paused before the lock is released: acquireLockAsync keeps trying to (re)acquire the lock for as long as it isn't cancelled,
        // so a release performed while node 1 is still acquiring is immediately followed by node 1 re-acquiring the now free lock with the next token,
        // which shifts the token node 2 ends up with and makes the assertions below fail
        lockManagerNode1.pause();
        lockNode1Callback.lockAcquired.release();
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(lockNode1Callback.lockReleased).isNotNull());

        // Then
        assertThat(lockNode1Callback.lockReleased).isNotNull();
        assertThat(lockNode1Callback.lockReleased.isLocked()).isFalse();
        assertThat(lockNode1Callback.lockReleased.isLockedByThisLockManagerInstance()).isFalse();

        // Node 2 should have acquired the lock
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(lockNode2Callback.lockAcquired).isNotNull());
        lockManagerNode1.resume();
        assertThat(lockNode2Callback.lockAcquired.isLocked()).isTrue();
        assertThat((CharSequence) lockNode2Callback.lockAcquired.getName()).isEqualTo(lockName);
        assertThat(lockNode2Callback.lockAcquired.isLockedByThisLockManagerInstance()).isTrue();
        assertThat(lockNode2Callback.lockAcquired.getLockAcquiredTimestamp()).isAfter(lastLockConfirmedTimestamp);
        assertThat(lockNode2Callback.lockAcquired.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue() + 1L);

        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isTrue();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
    }

    @Test
    void verify_that_acquireLockAsync_allows_us_to_acquire_a_timedout_lock_asynchronously() throws InterruptedException {
        // Given
        var lockName = LockName.of("testLock");
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isFalse();

        var lockNode1Callback = new TestLockCallback();
        var lockNode2Callback = new TestLockCallback();

        // When
        lockManagerNode1.acquireLockAsync(lockName, lockNode1Callback);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue());
        lockManagerNode2.acquireLockAsync(lockName, lockNode2Callback);
        Thread.sleep(1000);

        // Then
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isTrue();
        // And
        assertThat(lockNode1Callback.lockAcquired).isNotNull();
        assertThat(lockNode1Callback.lockReleased).isNull();
        assertThat(lockNode1Callback.lockAcquired.isLocked());
        assertThat((CharSequence) lockNode1Callback.lockAcquired.getName()).isEqualTo(lockName);
        assertThat(lockNode1Callback.lockAcquired.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lockNode1Callback.lockAcquired.isLockedByThisLockManagerInstance());

        // Node 2 must not be able to acquire the same lock
        assertThat(lockNode2Callback.lockAcquired).isNull();
        assertThat(lockNode2Callback.lockReleased).isNull();
        assertThat(lockNode1Callback.lockReleased).isNull();

        // When we pause lock confirmation (e.g. simulating a long GC pause)
        lockManagerNode1.pause();

        // Then Node 2 should have acquired the lock
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(lockNode2Callback.lockAcquired).isNotNull());
        lockManagerNode1.resume();
        assertThat(lockNode2Callback.lockAcquired.isLocked()).isTrue();
        assertThat((CharSequence) lockNode2Callback.lockAcquired.getName()).isEqualTo(lockName);
        assertThat(lockNode2Callback.lockAcquired.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue() + 1L);
        assertThat(lockNode2Callback.lockAcquired.isLockedByThisLockManagerInstance()).isTrue();

        // Then we should be notified that the lock was released on node 1
        lockManagerNode1.resume();
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(lockNode1Callback.lockReleased).isNotNull());
        assertThat(lockNode1Callback.lockReleased.isLocked()).isFalse();
        assertThat(lockNode1Callback.lockReleased.isLockedByThisLockManagerInstance()).isFalse();

        // And
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isTrue();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
    }

    @Test
    void verify_loosing_db_connection_no_locks_are_released() throws InterruptedException {
        // Given
        var lockName = LockName.of("testLock");
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isFalse();

        var lockNode1Callback = new TestLockCallback();
        var lockNode2Callback = new TestLockCallback();

        // When
        lockManagerNode1.acquireLockAsync(lockName, lockNode1Callback);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue());
        lockManagerNode2.acquireLockAsync(lockName, lockNode2Callback);
        Thread.sleep(1000);

        // Then
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode1.isLockAcquired(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquired(lockName)).isTrue();
        // And
        assertThat(lockNode1Callback.lockAcquired).isNotNull();
        assertThat(lockNode1Callback.lockReleased).isNull();
        assertThat(lockNode1Callback.lockAcquired.isLocked());
        assertThat((CharSequence) lockNode1Callback.lockAcquired.getName()).isEqualTo(lockName);
        assertThat(lockNode1Callback.lockAcquired.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lockNode1Callback.lockAcquired.isLockedByThisLockManagerInstance());

        // Node 2 must not be able to acquire the same lock
        assertThat(lockNode2Callback.lockAcquired).isNull();
        assertThat(lockNode2Callback.lockReleased).isNull();
        assertThat(lockNode1Callback.lockReleased).isNull();

        // When disrupt network connection for both nodes
        log.info("------ Disrupting the database connection ------");
        disruptDatabaseConnectionUntilRestored();

        // Then Node 1 should NOT have released the lock and node 2 should not have acquired the lock
        Thread.sleep(2000);
        log.info("------ Checking the node 1 didn't release the lock ------");
        assertThat(lockNode1Callback.lockReleased).isNull();
        assertThat(lockNode1Callback.lockAcquired.isLocked()).isTrue();
        assertThat(lockNode1Callback.lockAcquired.isLockedByThisLockManagerInstance()).isTrue();
        assertThat(lockNode1Callback.lockAcquired.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
        assertThat(lockNode2Callback.lockAcquired).isNull();
        assertThat(lockNode2Callback.lockReleased).isNull();

        // Restoring the connection could potentially result in node 2 overtaking the timed out lock
        log.info("------ Restoring the database connection ------");
        var restoredAt = OffsetDateTime.now(Clock.systemUTC());
        restoreDatabaseConnectionAndAwaitReachability();

        // Which node ends up with the lock is decided asynchronously, by the lock managers' next confirm/acquire ticks after
        // the database is reachable again - and those ticks can first have to finish the operations that stalled during the
        // outage. So wait for the outcome to be decided rather than reading it the instant the database answers a ping:
        // either node 1 has been told it lost the lock, or node 1 has confirmed the lock in the database since the restore
        // (a confirm that started after the outage succeeded, so node 1 really kept it).
        log.info("------ Waiting for the lock ownership to be decided ------");
        Awaitility.waitAtMost(LOCK_OUTCOME_TIMEOUT)
                  .pollInterval(Duration.ofMillis(100))
                  .until(() -> lockNode1Callback.lockReleased != null || isLockConfirmedByNode1Since(lockName, restoredAt));
        log.info("------ Checking which node has the lock ------");
        if (lockNode1Callback.lockReleased != null) {
            log.info("====== Node 1 lost the lock - exactly one node should take it over under the next fence token ======");
            // The timed-out lock is taken over on a later acquire tick. Usually by node 2 - but node 1's own acquireLockAsync is
            // still running, so node 1 can win it back (e.g. while node 2's attempts still hit a write conflict left by the
            // outage). Both are correct: what matters is that exactly one node holds it, under the next fence token.
            var nextToken = lockManagerNode1.getInitialTokenValue() + 1;
            Awaitility.waitAtMost(LOCK_OUTCOME_TIMEOUT)
                      .pollInterval(Duration.ofMillis(100))
                      .untilAsserted(() -> {
                          var lockInDB = lockManagerNode2.lookupLock(lockName);
                          assertThat(lockInDB).isPresent();
                          assertThat(lockInDB.get().getCurrentToken()).isEqualTo(nextToken);
                          var holder = lockInDB.get().getLockedByLockManagerInstanceId();
                          assertThat(holder).isIn(lockManagerNode1.getLockManagerInstanceId(), lockManagerNode2.getLockManagerInstanceId());
                          var holderIsNode2  = lockManagerNode2.getLockManagerInstanceId().equals(holder);
                          var holderManager  = holderIsNode2 ? lockManagerNode2 : lockManagerNode1;
                          var holderCallback = holderIsNode2 ? lockNode2Callback : lockNode1Callback;
                          var otherManager   = holderIsNode2 ? lockManagerNode1 : lockManagerNode2;
                          assertThat(holderCallback.lockAcquired).isNotNull();
                          assertThat(holderCallback.lockAcquired.getCurrentToken()).isEqualTo(nextToken);
                          assertThat(holderManager.isLockedByThisLockManagerInstance(lockName)).isTrue();
                          assertThat(holderManager.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
                          assertThat(otherManager.isLockedByThisLockManagerInstance(lockName)).isFalse();
                          assertThat(otherManager.isLockAcquired(lockName)).isTrue();
                          assertThat(otherManager.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
                          if (!holderIsNode2) {
                              assertThat(lockNode2Callback.lockAcquired).isNull();
                          }
                      });
            assertThat(lockNode2Callback.lockReleased).isNull();
            log.info("Lock '{}' taken over after the outage: {}", lockName, lockManagerNode2.lookupLock(lockName).orElse(null));
        } else {
            log.info("====== Node 1 kept the lock and node 2 should not have acquired it ======");
            assertThat(lockNode2Callback.lockAcquired).isNull();
            assertThat(lockNode2Callback.lockReleased).isNull();
            assertThat(lockNode1Callback.lockReleased).isNull();
            assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
            assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue();
            assertThat(lockManagerNode1.isLockAcquired(lockName)).isTrue();
            assertThat(lockNode1Callback.lockAcquired.getCurrentToken()).isEqualTo(lockManagerNode1.getInitialTokenValue());
            assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isFalse();
            assertThat(lockManagerNode2.isLockAcquired(lockName)).isTrue();
            assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        }
    }

    /**
     * A {@code lockAcquired} callback that throws must not leave the lock held.
     *
     * <h2>Why this is the assertion that matters</h2>
     * The lock is recorded as owned by this instance <em>before</em> the callback runs. So if the
     * callback throws and the lock stays held, the next tick finds the lock already owned here and
     * takes neither branch — it does not re-acquire, and it does not call the callback again. The
     * failure is permanent: this instance owns a lock it is not serving, no other instance can take
     * it, and the only trace is a log line whose text says the acquisition failed, when it did not.
     * <p>
     * It surfaced through the store-and-forward machinery, where it is easy to hit by accident: an
     * {@code Inbox} in {@code SingleGlobalConsumer} mode does all of its consumer wiring inside
     * {@code onLockAcquired}, so anything wrong with that wiring — a queue that is not registered
     * yet, a dependency that has not finished starting — produced an Inbox that silently never
     * consumed while holding its own lock.
     * <p>
     * <b>Retrying is therefore the proof.</b> The callback below fails twice and succeeds on the
     * third attempt, and there is no third attempt at all unless each failure released the lock. It
     * also demonstrates the property that makes releasing the right response rather than merely the
     * safe one: a cause that clears itself is picked up without a restart.
     */
    @Test
    void a_failing_lockAcquired_callback_releases_the_lock_so_the_next_attempt_can_retry() {
        var lockName          = LockName.of("callbackThrowsLock");
        var attempts          = new AtomicInteger();
        var succeededOnAttempt = new AtomicInteger(-1);
        var failuresWanted    = 2;

        getLockManagerNode1().acquireLockAsync(lockName,
                                               LockCallback.builder()
                                                           .onLockAcquired(lock -> {
                                                               var attempt = attempts.incrementAndGet();
                                                               if (attempt <= failuresWanted) {
                                                                   throw new IllegalStateException(
                                                                           "Deliberate callback failure on attempt " + attempt);
                                                               }
                                                               succeededOnAttempt.set(attempt);
                                                           })
                                                           .onLockReleased(lock -> {
                                                           })
                                                           .build());

        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(succeededOnAttempt.get())
                          .describedAs("the callback must be retried after it throws, which cannot happen "
                                       + "if the failed attempt left the lock held")
                          .isEqualTo(failuresWanted + 1));

        assertThat(getLockManagerNode1().isLockedByThisLockManagerInstance(lockName))
                .describedAs("and once the callback finally succeeds the lock is genuinely held")
                .isTrue();

        getLockManagerNode1().cancelAsyncLockAcquiring(lockName);
    }

    /**
     * A {@link DBFencedLockManager#stop()} keeps each async acquiring registered with the lock manager, and
     * {@link DBFencedLockManager#start()} resumes it, for the callback it was registered with. Resumed while another node
     * holds the lock, the acquiring stands by while that node confirms the lock - for several confirmation intervals - and
     * takes the lock, for that same callback and with no new <code>acquireLockAsync</code>, once the other node releases it.
     */
    @Test
    void a_stop_and_start_resumes_an_async_acquiring_without_taking_the_lock_from_the_node_that_took_it_over() {
        var lockName      = LockName.of("resumedLock");
        var node1Callback = new CountingLockCallback();
        var node2Callback = new CountingLockCallback();

        lockManagerNode1.acquireLockAsync(lockName, node1Callback);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(node1Callback.acquired.get()).isEqualTo(1));

        // Node 1 stops, which releases the lock, and node 2's async acquiring takes it over
        lockManagerNode1.stop();
        assertThat(node1Callback.released.get()).isEqualTo(1);
        lockManagerNode2.acquireLockAsync(lockName, node2Callback);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(node2Callback.acquired.get()).isEqualTo(1));
        var node2Token = node2Callback.lastAcquired.getCurrentToken();
        assertThat(node2Token).isEqualTo(node1Callback.lastAcquired.getCurrentToken() + 1L);

        // Node 1 starts again: its resumed acquiring does not take the lock that node 2 holds and confirms
        lockManagerNode1.start();
        Awaitility.await()
                  .during(Duration.ofSeconds(5))
                  .atMost(Duration.ofSeconds(15))
                  .untilAsserted(() -> {
                      assertThat(node1Callback.acquired.get()).isEqualTo(1);
                      assertThat(lockManagerNode2.isLockedByThisLockManagerInstance(lockName)).isTrue();
                  });
        assertThat(lockManagerNode1.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        assertThat(node2Callback.released.get()).isZero();

        // Node 2 cancels its acquiring, which releases the lock: node 1's resumed acquiring takes it, for the callback it was
        // registered with before the stop, at the next fence token
        lockManagerNode2.cancelAsyncLockAcquiring(lockName);
        assertThat(node2Callback.released.get()).isEqualTo(1);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(node1Callback.acquired.get()).isEqualTo(2));
        assertThat(node1Callback.lastAcquired.getCurrentToken()).isEqualTo(node2Token + 1L);
        assertThat(lockManagerNode1.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(lockManagerNode2.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();

        lockManagerNode1.cancelAsyncLockAcquiring(lockName);
        assertThat(node1Callback.released.get()).isEqualTo(2);
    }

    /**
     * Counts the calls of each callback method, and keeps the last lock <code>lockAcquired</code> was called with
     */
    private static class CountingLockCallback implements LockCallback {
        final    AtomicInteger acquired = new AtomicInteger();
        final    AtomicInteger released = new AtomicInteger();
        volatile FencedLock    lastAcquired;

        @Override
        public void lockAcquired(FencedLock lock) {
            lastAcquired = lock;
            acquired.incrementAndGet();
        }

        @Override
        public void lockReleased(FencedLock lock) {
            released.incrementAndGet();
        }
    }

    private static class TestLockCallback implements LockCallback {
        volatile FencedLock lockReleased;
        volatile FencedLock lockAcquired;

        @Override
        public void lockAcquired(FencedLock lock) {
            this.lockAcquired = lock;
        }

        @Override
        public void lockReleased(FencedLock lock) {
            this.lockReleased = lock;
        }

        public void reset() {
            lockReleased = null;
            lockAcquired = null;
        }
    }
}
