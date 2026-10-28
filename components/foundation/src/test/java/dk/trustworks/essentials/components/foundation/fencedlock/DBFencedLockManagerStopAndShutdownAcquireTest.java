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

import dk.trustworks.essentials.components.foundation.fencedlock.DBFencedLockManagerAcquireCommitFailureTest.*;
import dk.trustworks.essentials.components.foundation.lifecycle.ShutdownContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Blocking acquires against a lock manager that is stopping, or has begun shutting down: they must not touch the DB once
 * shutdown has started, nor record a lock nothing will confirm in a manager that has stopped. Uses the in-memory lock table
 * of {@link DBFencedLockManagerAcquireCommitFailureTest}.
 */
class DBFencedLockManagerStopAndShutdownAcquireTest {
    private static final LockName LOCK_NAME  = LockName.of("stop-shutdown-lock");
    private static final LockName OTHER_LOCK = LockName.of("stop-shutdown-other-lock");

    private InMemoryLockStorage     storage;
    private TestDBFencedLockManager node;
    private ExecutorService         executor;

    @BeforeEach
    void setup() {
        storage = new InMemoryLockStorage();
        node = createLockManager("node");
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void cleanup() {
        executor.shutdownNow();
        node.stop();
    }

    @Test
    void an_acquire_that_lost_the_race_to_stop_does_not_record_or_acquire_the_lock() throws Exception {
        // Given another call holds the lock manager's lock, waiting on a slow DB read
        var gate = new CountDownLatch(1);
        storage.blockedLookupName = OTHER_LOCK;
        storage.blockedLookupGate = gate;
        var holder = executor.submit(() -> node.isLockAcquired(OTHER_LOCK));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> storage.lookups.get() == 1);

        // And stop() is queued for the lock manager's lock first, then an acquire that has already seen the manager started
        var stopper = startThread("stopper", () -> node.stop());
        awaitWaiting(stopper);
        var acquired = new AtomicReference<Optional<FencedLock>>();
        var acquirer = startThread("acquirer", () -> acquired.set(node.tryAcquireLock(LOCK_NAME)));
        awaitWaiting(acquirer);

        // When the holder finishes
        gate.countDown();
        assertThat(holder.get(5, TimeUnit.SECONDS)).isFalse();
        stopper.join(5000);
        acquirer.join(5000);

        // Then stop() has run, and the acquire gave up: no row locked by a manager that has stopped
        assertThat(node.isStarted()).isFalse();
        assertThat(acquired.get()).isEmpty();
        assertThat(storage.rows).doesNotContainKey(LOCK_NAME);
    }

    @Test
    void blocking_acquires_do_not_query_the_db_once_shutdown_has_started() {
        node.shutdownStarting(ShutdownContext.startingNow(Duration.ofSeconds(10)));
        var lookupsBefore = storage.lookups.get();

        assertThat(node.tryAcquireLock(LOCK_NAME)).isEmpty();
        assertThat(node.tryAcquireLock(LOCK_NAME, Duration.ofSeconds(5))).isEmpty();
        assertThatThrownBy(() -> node.acquireLock(LOCK_NAME)).isInstanceOf(IllegalStateException.class);

        assertThat(storage.lookups.get()).isEqualTo(lookupsBefore);
        assertThat(storage.rows).doesNotContainKey(LOCK_NAME);
    }

    @Test
    void a_lock_this_manager_already_holds_is_still_returned_once_shutdown_has_started() {
        // Given node holds the lock when shutdown starts
        var lock = node.tryAcquireLock(LOCK_NAME).orElseThrow();
        node.shutdownStarting(ShutdownContext.startingNow(Duration.ofSeconds(10)));
        var lookupsBefore = storage.lookups.get();

        // Then every blocking acquire returns the lock it holds, without asking the DB
        assertThat(node.tryAcquireLock(LOCK_NAME)).containsSame(lock);
        assertThat(node.tryAcquireLock(LOCK_NAME, Duration.ofSeconds(5))).containsSame(lock);
        assertThat(node.acquireLock(LOCK_NAME)).isSameAs(lock);
        assertThat(storage.lookups.get()).isEqualTo(lookupsBefore);
    }

    @Test
    void a_timed_acquire_stops_waiting_for_the_lock_managers_lock_at_its_timeout() throws Exception {
        // Given another call holds the lock manager's lock, waiting on a slow DB read
        var gate = new CountDownLatch(1);
        storage.blockedLookupName = OTHER_LOCK;
        storage.blockedLookupGate = gate;
        var holder = executor.submit(() -> node.isLockAcquired(OTHER_LOCK));
        try {
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> storage.lookups.get() == 1);

            // When a timed acquire runs while the lock manager's lock is still held
            var acquired = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> node.tryAcquireLock(LOCK_NAME, Duration.ofMillis(300)));

            // Then it gave up at its timeout, long before the holder finished, and acquired nothing
            assertThat(acquired).isEmpty();
            assertThat(storage.rows).doesNotContainKey(LOCK_NAME);
        } finally {
            gate.countDown();
        }
        assertThat(holder.get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void an_interrupted_timed_acquire_waiting_for_the_lock_managers_lock_returns_empty_and_keeps_the_interrupt() throws Exception {
        // Given another call holds the lock manager's lock, waiting on a slow DB read
        var gate = new CountDownLatch(1);
        storage.blockedLookupName = OTHER_LOCK;
        storage.blockedLookupGate = gate;
        var holder = executor.submit(() -> node.isLockAcquired(OTHER_LOCK));
        try {
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> storage.lookups.get() == 1);

            // And a timed acquire is waiting for the lock manager's lock
            var acquired = new AtomicReference<Optional<FencedLock>>();
            var interruptPendingAfterwards = new AtomicReference<Boolean>();
            var acquirer = startThread("acquirer", () -> {
                acquired.set(node.tryAcquireLock(LOCK_NAME, Duration.ofSeconds(30)));
                interruptPendingAfterwards.set(Thread.currentThread().isInterrupted());
            });
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> acquirer.getState() == Thread.State.TIMED_WAITING);

            // When the waiting thread is interrupted
            acquirer.interrupt();
            acquirer.join(5000);

            // Then the acquire returned empty while the holder was still busy, leaving the interrupt pending
            assertThat(acquirer.isAlive()).isFalse();
            assertThat(acquired.get()).isEmpty();
            assertThat(interruptPendingAfterwards.get()).isTrue();
            assertThat(storage.rows).doesNotContainKey(LOCK_NAME);
        } finally {
            gate.countDown();
        }
        assertThat(holder.get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void a_blocking_acquire_waiting_for_a_contended_lock_gives_up_when_shutdown_starts() throws Exception {
        var other = createLockManager("other");
        try {
            other.tryAcquireLock(LOCK_NAME).orElseThrow();
            var waiting = executor.submit(() -> node.acquireLock(LOCK_NAME));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> storage.lookups.get() >= 2);

            node.shutdownStarting(ShutdownContext.startingNow(Duration.ofSeconds(10)));

            assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            other.stop();
        }
    }

    @Test
    void a_commit_outcome_lookup_is_skipped_when_shutdown_has_found_the_database_unreachable() {
        // Given shutdown starts, finding the DB unreachable, while node's acquire has reached the DB but failed to commit
        var shutdown = ShutdownContext.startingNow(Duration.ofSeconds(10));
        shutdown.databaseUnreachable("test");
        storage.nextAcquireCommit = CommitOutcome.APPLIED_THEN_CONNECTION_LOST;
        storage.afterAcquireApplied = () -> node.shutdownStarting(shutdown);
        var lookupsBefore = storage.lookups.get();

        // When node acquires
        var acquired = node.tryAcquireLock(LOCK_NAME);

        // Then it did not ask the DB again, and does not hold the lock; the row it locked is left to expire
        assertThat(acquired).isEmpty();
        assertThat(storage.lookups.get()).isEqualTo(lookupsBefore + 1); // only the acquire's own look-up of the row
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
    }

    private TestDBFencedLockManager createLockManager(String lockManagerInstanceId) {
        var lockManager = new TestDBFencedLockManager(storage,
                                                      new StagingUnitOfWorkFactory(storage),
                                                      FencedLockManagerSettings.builder()
                                                                               .setLockManagerInstanceId(lockManagerInstanceId)
                                                                               .setLockTimeOut(Duration.ofSeconds(30))
                                                                               .setLockConfirmationInterval(Duration.ofSeconds(10))
                                                                               .build());
        lockManager.start();
        return lockManager;
    }

    private static Thread startThread(String name, Runnable runnable) {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitWaiting(Thread thread) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> thread.getState() == Thread.State.WAITING);
    }
}
