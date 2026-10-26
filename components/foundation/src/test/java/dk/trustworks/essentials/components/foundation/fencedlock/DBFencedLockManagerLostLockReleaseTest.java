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

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockEvents.*;
import dk.trustworks.essentials.components.foundation.lifecycle.ShutdownContext;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.reactive.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;

import static org.assertj.core.api.Assertions.*;

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
 * <p>
 * A non-critical {@link Error} (an {@link AssertionError}, say) from a <code>lockReleased</code> callback or a lock event
 * subscriber, thrown on the confirmation thread, is handled like a {@link RuntimeException}: a lost lock is still released
 * locally and {@link LockReleased} published (in the DB too, unless the confirmation {@link UnitOfWork} failed on IO - see
 * above), the other confirmations of the tick are kept, and the scheduled confirmation keeps running. A critical error
 * ({@link dk.trustworks.essentials.shared.Exceptions#isCriticalError(Throwable)}) still escapes, rolling back the tick's
 * confirmations and ending the scheduled confirmation - but every lock still held is released locally, and the error logged at
 * ERROR, first. From then on the lock manager acquires no lock, which it could no longer confirm, until it is stopped and
 * started again - and an async acquiring registered before the stop is resumed by the start, without being registered
 * again.
 */
class DBFencedLockManagerLostLockReleaseTest {
    private static final LockName LOCK_NAME       = LockName.of("lost-lock");
    private static final LockName OTHER_LOCK_NAME = LockName.of("other-lock");

    private BlockingReleaseLockStorage   storage;
    private ThreadLocalUnitOfWorkFactory unitOfWorkFactory;
    private TestDBFencedLockManager      lockManager;
    /**
     * Every {@link FencedLockEvents} the lock manager published
     */
    private List<FencedLockEvents>       events;
    /**
     * Run by the lock manager's sync event subscriber for every event, after recording it - may throw
     */
    private volatile Consumer<FencedLockEvents> eventSubscriberAction = event -> {
    };

    @BeforeEach
    void setup() {
        storage = new BlockingReleaseLockStorage();
        unitOfWorkFactory = new ThreadLocalUnitOfWorkFactory();
        events = new CopyOnWriteArrayList<>();
        var eventBus = new LocalEventBus("lost-lock-release-test");
        eventBus.addSyncSubscriber(event -> {
            events.add((FencedLockEvents) event);
            eventSubscriberAction.accept((FencedLockEvents) event);
        });
        lockManager = new TestDBFencedLockManager(storage,
                                                  unitOfWorkFactory,
                                                  FencedLockManagerSettings.builder()
                                                                           .setLockManagerInstanceId("node1")
                                                                           .setLockTimeOut(Duration.ofSeconds(3))
                                                                           .setLockConfirmationInterval(Duration.ofMillis(100))
                                                                           .setReleaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation(true)
                                                                           .build(),
                                                  eventBus);
        lockManager.start();
    }

    @AfterEach
    void cleanup() {
        eventSubscriberAction = event -> {
        };
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

    @Test
    void a_lost_lock_whose_lock_released_callback_fails_with_an_error_is_still_released_and_the_confirmations_of_the_tick_are_kept() {
        // Given
        storage.allowReleaseInDB.countDown();
        var confirmedLock = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();
        var lostLock      = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        lostLock.registerCallback(lockReleasedFailingWith(new AssertionError("lockReleased fails")));

        // When
        storage.confirmOutcomeByLock.put(LOCK_NAME, ConfirmOutcome.TAKEN_OVER);

        // Then
        verifyLostLockIsReleasedAndTheConfirmationOfTheTickIsKept(lostLock, confirmedLock);
    }

    @Test
    void a_lost_lock_whose_lock_released_event_subscriber_fails_with_an_error_is_still_released_and_the_confirmations_of_the_tick_are_kept() {
        // Given
        storage.allowReleaseInDB.countDown();
        var confirmedLock = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();
        var lostLock      = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        eventSubscriberAction = event -> {
            if (event instanceof LockReleased) {
                throw new AssertionError("LockReleased subscriber fails");
            }
        };

        // When
        storage.confirmOutcomeByLock.put(LOCK_NAME, ConfirmOutcome.TAKEN_OVER);

        // Then
        verifyLostLockIsReleasedAndTheConfirmationOfTheTickIsKept(lostLock, confirmedLock);
    }

    @Test
    void a_lock_released_callback_failing_with_an_error_while_every_lock_is_released_after_an_io_failure_does_not_end_the_confirmation() {
        // Given
        storage.allowReleaseInDB.countDown();
        var lock      = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var otherLock = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();
        lock.registerCallback(lockReleasedFailingWith(new AssertionError("lockReleased fails")));
        otherLock.registerCallback(lockReleasedFailingWith(new AssertionError("lockReleased fails")));

        // When
        unitOfWorkFactory.failCommitWithIOException = true;

        // Then every lock is released locally, and LockReleased published for each
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(lock.isLocked()).isFalse();
                      assertThat(otherLock.isLocked()).isFalse();
                      assertThat(lockReleasedEventsFor(LOCK_NAME)).hasSize(1);
                      assertThat(lockReleasedEventsFor(OTHER_LOCK_NAME)).hasSize(1);
                  });

        // And once the DB is reachable again, a lock acquired afterwards is confirmed: the scheduled confirmation is still running
        unitOfWorkFactory.failCommitWithIOException = false;
        var reacquiredLock = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        awaitConfirmationKeepsRunning(LOCK_NAME);
        assertThat(reacquiredLock.isLocked()).isTrue();
    }

    @Test
    void a_lock_acquired_event_subscriber_failing_with_an_error_during_confirmation_does_not_roll_back_the_confirmations_of_the_tick() {
        // Given
        var lock                    = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var otherLock               = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();
        var unitOfWorksOfFailedTicks = new CopyOnWriteArrayList<UnitOfWork>();

        // When
        eventSubscriberAction = event -> {
            if (event instanceof LockAcquired lockAcquired && lockAcquired.lock.getName().equals(LOCK_NAME)) {
                unitOfWorksOfFailedTicks.add(unitOfWorkFactory.getRequiredUnitOfWork());
                throw new AssertionError("LockAcquired subscriber fails");
            }
        };

        // Then the confirmation UnitOfWork of a tick in which the subscriber failed is committed, not rolled back
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(unitOfWorksOfFailedTicks).isNotEmpty();
                      assertThat(unitOfWorksOfFailedTicks.getFirst().status()).isNotEqualTo(UnitOfWorkStatus.Started);
                  });
        assertThat(unitOfWorksOfFailedTicks.getFirst().status()).isEqualTo(UnitOfWorkStatus.Committed);

        // And both locks stay held and keep being confirmed
        awaitConfirmationKeepsRunning(LOCK_NAME);
        awaitConfirmationKeepsRunning(OTHER_LOCK_NAME);
        assertThat(lock.isLocked()).isTrue();
        assertThat(otherLock.isLocked()).isTrue();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // Releasing the locks is the job of the lock manager when the confirmation ends, the behaviour under test
    void a_lock_acquired_event_subscriber_failing_with_a_critical_error_during_confirmation_releases_every_held_lock_locally_and_ends_the_confirmation() {
        // Given
        var lock                     = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var otherLock                = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();
        var unitOfWorksOfFailedTicks = new CopyOnWriteArrayList<UnitOfWork>();
        var criticalError            = new ExceptionInInitializerError("LockAcquired subscriber fails critically");
        var logEvents                = captureLockManagerLog();
        try {
            // When
            eventSubscriberAction = event -> {
                if (event instanceof LockAcquired lockAcquired && lockAcquired.lock.getName().equals(LOCK_NAME)) {
                    unitOfWorksOfFailedTicks.add(unitOfWorkFactory.getRequiredUnitOfWork());
                    throw criticalError;
                }
            };

            // Then the critical error escapes the confirmation loop: the confirmation UnitOfWork of that tick is rolled back
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(unitOfWorksOfFailedTicks).isNotEmpty();
                          assertThat(unitOfWorksOfFailedTicks.getFirst().status()).isNotEqualTo(UnitOfWorkStatus.Started);
                      });
            assertThat(unitOfWorksOfFailedTicks.getFirst().status()).isEqualTo(UnitOfWorkStatus.RolledBack);

            // And before it ended the confirmation, every lock this instance held was released locally - neither is confirmed
            // any more, so neither may go on reporting itself held by this instance - and LockReleased published for each.
            // And it was logged at ERROR, once, since the scheduled future it escapes into is never read. Awaited together:
            // per lock, isLocked() turns false before LockReleased is published, and the ERROR line follows the last lock
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(lock.isLocked()).isFalse();
                          assertThat(otherLock.isLocked()).isFalse();
                          assertThat(lockReleasedEventsFor(LOCK_NAME)).hasSize(1);
                          assertThat(lockReleasedEventsFor(OTHER_LOCK_NAME)).hasSize(1);
                          assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR))
                                  .singleElement()
                                  .satisfies(event -> {
                                      assertThat(event.getFormattedMessage()).contains("critical error", "2 lock(s)", "ended");
                                      assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable()).isSameAs(criticalError);
                                  });
                      });
            assertThat(lock.isLockedByThisLockManagerInstance()).isFalse();
            assertThat(otherLock.isLockedByThisLockManagerInstance()).isFalse();
            // ... locally only: the rows expire after the lock timeout
            assertThat(storage.releaseInDBUnitOfWorks).isEmpty();

            // And the scheduled confirmation has ended, so this lock manager acquires no lock it could not confirm
            eventSubscriberAction = event -> {
            };
            assertThat(lockManager.tryAcquireLock(LOCK_NAME)).isEmpty();
            assertThat(unitOfWorksOfFailedTicks).hasSize(1);
        } finally {
            ((Logger) LoggerFactory.getLogger(TestDBFencedLockManager.class)).detachAppender(logEvents);
        }
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // lock is released locally by the lock manager when the confirmation ends, the behaviour under test; reacquiredLock is released by stop() in cleanup()
    void once_a_critical_error_has_ended_the_confirmation_no_lock_is_acquired_until_the_lock_manager_is_stopped_and_started_again() {
        // Given a critical error that has ended the scheduled confirmation
        assertThat(lockManager.isLockConfirmationEnded()).isFalse();
        var lock          = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var criticalError = new ExceptionInInitializerError("LockAcquired subscriber fails critically");
        var logEvents     = captureLockManagerLog();
        try {
            eventSubscriberAction = event -> {
                if (event instanceof LockAcquired lockAcquired && lockAcquired.lock.getName().equals(LOCK_NAME)) {
                    throw criticalError;
                }
            };
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(lock.isLocked()).isFalse();
                          assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
                      });
        } finally {
            ((Logger) LoggerFactory.getLogger(TestDBFencedLockManager.class)).detachAppender(logEvents);
        }
        eventSubscriberAction = event -> {
        };
        var lockAcquiredEventsAtTheEnd = lockAcquiredEvents().size();

        // When / Then no lock is acquired: none of them could be confirmed, so each row would expire while its holder believed it held it.
        // The lock manager still reports itself started, but reports that its lock confirmation has ended
        assertThat(lockManager.isStarted()).isTrue();
        assertThat(lockManager.isLockConfirmationEnded()).isTrue();
        assertThat(lockManager.tryAcquireLock(LOCK_NAME)).isEmpty();
        assertThat(lockManager.tryAcquireLock(OTHER_LOCK_NAME)).isEmpty();
        var timedAcquireStarted = System.nanoTime();
        assertThat(lockManager.tryAcquireLock(OTHER_LOCK_NAME, Duration.ofSeconds(3))).isEmpty();
        // ... at once, without retrying until the timeout
        assertThat(Duration.ofNanos(System.nanoTime() - timedAcquireStarted)).isLessThan(Duration.ofSeconds(2));
        assertThatThrownBy(() -> lockManager.acquireLock(OTHER_LOCK_NAME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lock confirmation has ended");
        // And an async acquiring acquires nothing either: no lockAcquired callback
        var asyncAcquired = new CopyOnWriteArrayList<FencedLock>();
        lockManager.acquireLockAsync(OTHER_LOCK_NAME, new LockCallback() {
            @Override
            public void lockAcquired(FencedLock lock) {
                asyncAcquired.add(lock);
            }

            @Override
            public void lockReleased(FencedLock lock) {
            }
        });
        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> assertThat(asyncAcquired).isEmpty());
        assertThat(lockAcquiredEvents()).hasSize(lockAcquiredEventsAtTheEnd);

        // And once the lock manager is stopped and started again, it acquires locks and confirms them - a start on the still
        // started lock manager does nothing and a stop alone does not clear the ended confirmation, the start after it does
        lockManager.start();
        assertThat(lockManager.isLockConfirmationEnded()).isTrue();
        lockManager.stop();
        assertThat(lockManager.isLockConfirmationEnded()).isTrue();
        lockManager.start();
        assertThat(lockManager.isLockConfirmationEnded()).isFalse();
        var reacquiredLock = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        awaitConfirmationKeepsRunning(LOCK_NAME);
        assertThat(reacquiredLock.isLocked()).isTrue();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // otherLock, and the lock the resumed async acquiring takes, are released by stop() in cleanup()
    void an_async_acquiring_registered_before_the_confirmation_ended_is_resumed_by_a_stop_and_start_of_the_lock_manager() {
        // Given an async acquiring that has acquired its lock
        var asyncAcquired = new CopyOnWriteArrayList<FencedLock>();
        var asyncReleased = new CopyOnWriteArrayList<FencedLock>();
        var lockCallback = new LockCallback() {
            @Override
            public void lockAcquired(FencedLock lock) {
                asyncAcquired.add(lock);
            }

            @Override
            public void lockReleased(FencedLock lock) {
                asyncReleased.add(lock);
            }
        };
        lockManager.acquireLockAsync(LOCK_NAME, lockCallback);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(asyncAcquired).hasSize(1));

        // And a critical error that has ended the scheduled confirmation, releasing that lock locally
        var criticalError = new ExceptionInInitializerError("LockAcquired subscriber fails critically");
        var logEvents     = captureLockManagerLog();
        try {
            eventSubscriberAction = event -> {
                if (event instanceof LockAcquired lockAcquired && lockAcquired.lock.getName().equals(LOCK_NAME)) {
                    throw criticalError;
                }
            };
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(asyncReleased).hasSize(1);
                          assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
                      });
        } finally {
            ((Logger) LoggerFactory.getLogger(TestDBFencedLockManager.class)).detachAppender(logEvents);
        }
        eventSubscriberAction = event -> {
        };

        // When the lock manager is stopped and started again
        lockManager.stop();
        lockManager.start();

        // Then the lock manager acquires locks again ...
        var otherLock = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();
        assertThat(otherLock.isLocked()).isTrue();
        // ... and resumes the async acquiring without it being registered again: stop() kept its registration and start()
        // scheduled it again, so it acquires its lock and lockAcquired is called again, on the same callback
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(asyncAcquired).hasSize(2));
        awaitConfirmationKeepsRunning(LOCK_NAME);
        assertThat(asyncAcquired.getLast().isLocked()).isTrue();

        // And a component registering it again on its own start changes nothing: the registration is already there
        lockManager.acquireLockAsync(LOCK_NAME, lockCallback);
        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(3))
                  .untilAsserted(() -> {
                      assertThat(asyncAcquired).hasSize(2);
                      assertThat(asyncReleased).hasSize(1);
                  });
    }

    private List<LockAcquired> lockAcquiredEvents() {
        return events.stream()
                     .filter(LockAcquired.class::isInstance)
                     .map(LockAcquired.class::cast)
                     .toList();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // Releasing the locks is the job of the lock manager when the confirmation ends, the behaviour under test
    void a_local_release_failing_with_a_critical_error_as_the_confirmation_ends_does_not_keep_the_other_held_lock_held() {
        // Given two held locks, each with a lockReleased callback failing critically - both, so neither map order lets the
        // test pass with the lock released second skipped
        var lock                   = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var otherLock              = lockManager.tryAcquireLock(OTHER_LOCK_NAME).orElseThrow();
        var lockReleasedError      = new ExceptionInInitializerError("lockReleased of lost-lock fails critically");
        var otherLockReleasedError = new ExceptionInInitializerError("lockReleased of other-lock fails critically");
        lock.registerCallback(lockReleasedFailingWith(lockReleasedError));
        otherLock.registerCallback(lockReleasedFailingWith(otherLockReleasedError));
        var criticalError = new ExceptionInInitializerError("LockAcquired subscriber fails critically");
        var logEvents     = captureLockManagerLog();
        try {
            // When a critical error ends the confirmation
            eventSubscriberAction = event -> {
                if (event instanceof LockAcquired lockAcquired && lockAcquired.lock.getName().equals(LOCK_NAME)) {
                    throw criticalError;
                }
            };

            // Then the failing local release of one lock does not stop the other being released locally, and both
            // failures are added to the critical error, logged at ERROR once the last lock is done. No LockReleased is
            // asserted: a lockReleased callback failing critically skips that lock's LockReleased
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR))
                              .singleElement()
                              .satisfies(event -> {
                                  var loggedError = ((ThrowableProxy) event.getThrowableProxy()).getThrowable();
                                  assertThat(loggedError).isSameAs(criticalError);
                                  assertThat(loggedError.getSuppressed()).containsExactlyInAnyOrder(lockReleasedError, otherLockReleasedError);
                              }));
            assertThat(lock.isLocked()).isFalse();
            assertThat(otherLock.isLocked()).isFalse();
            assertThat(lock.isLockedByThisLockManagerInstance()).isFalse();
            assertThat(otherLock.isLockedByThisLockManagerInstance()).isFalse();
        } finally {
            ((Logger) LoggerFactory.getLogger(TestDBFencedLockManager.class)).detachAppender(logEvents);
        }
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // The lock is released by the test, the behaviour under test
    void a_release_waiting_while_the_confirmation_ends_with_a_critical_error_does_not_announce_the_release_a_second_time() throws Exception {
        // Given a held lock whose lockReleased callbacks are counted
        storage.allowReleaseInDB.countDown();
        var lock             = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var lockReleasedCalls = new AtomicInteger();
        lock.registerCallback(new LockCallback() {
            @Override
            public void lockAcquired(FencedLock lock) {
            }

            @Override
            public void lockReleased(FencedLock lock) {
                lockReleasedCalls.incrementAndGet();
            }
        });
        // And a confirmation tick held inside its UnitOfWork - holding the lock manager's lock - until the test lets it end
        // with a critical error
        var tickHeld      = new CountDownLatch(1);
        var endTheTick    = new CountDownLatch(1);
        var criticalError = new ExceptionInInitializerError("LockAcquired subscriber fails critically");
        var logEvents     = captureLockManagerLog();
        try {
            eventSubscriberAction = event -> {
                if (event instanceof LockAcquired lockAcquired && lockAcquired.lock.getName().equals(LOCK_NAME)) {
                    tickHeld.countDown();
                    try {
                        endTheTick.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw criticalError;
                }
            };
            assertThat(tickHeld.await(5, TimeUnit.SECONDS)).isTrue();

            // When the lock is released on another thread, which waits for the lock manager's lock behind the tick ...
            var releaseFailure = new AtomicReference<Throwable>();
            var releasing      = new Thread(() -> {
                try {
                    lock.release();
                } catch (Throwable e) {
                    releaseFailure.set(e);
                }
            }, "releasing-thread");
            releasing.start();
            var managerLock = managerLockOf(lockManager);
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .until(() -> managerLock.hasQueuedThread(releasing));
            // ... and the tick then ends the confirmation, releasing every held lock locally
            endTheTick.countDown();
            releasing.join(Duration.ofSeconds(10).toMillis());

            // Then the release is announced once: by the end of the confirmation. The waiting release finds the lock released
            // locally already and neither calls lockReleased nor publishes LockReleased again
            assertThat(releasing.isAlive()).isFalse();
            assertThat(releaseFailure.get()).isNull();
            assertThat(lock.isLocked()).isFalse();
            assertThat(lockReleasedCalls.get()).isEqualTo(1);
            assertThat(lockReleasedEventsFor(LOCK_NAME)).hasSize(1);
            assertThat(lockManager.isLockConfirmationEnded()).isTrue();
        } finally {
            endTheTick.countDown();
            ((Logger) LoggerFactory.getLogger(TestDBFencedLockManager.class)).detachAppender(logEvents);
        }
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // newerLock is released by stop() in cleanup(); staleLock was released when it was lost
    void releasing_a_lost_lock_after_the_lock_was_acquired_again_leaves_the_newer_acquisition_held_and_confirmed() {
        // Given a lock that was lost - released locally and LockReleased published - ...
        storage.allowReleaseInDB.countDown();
        var staleLock = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        storage.confirmOutcomeByLock.put(LOCK_NAME, ConfirmOutcome.TAKEN_OVER);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(staleLock.isLocked()).isFalse();
                      assertThat(lockReleasedEventsFor(LOCK_NAME)).hasSize(1);
                      assertThat(storage.releasedInDBTokens).hasSize(1);
                  });
        // ... and acquired again by this lock manager, as a new FencedLock
        storage.confirmOutcomeByLock.remove(LOCK_NAME);
        var newerLock = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        assertThat(newerLock).isNotSameAs(staleLock);
        var releasedInDBBefore = storage.releaseInDBUnitOfWorks.size();

        // When the stale lock is released (or closed)
        staleLock.release();

        // Then nothing is released: the newer acquisition stays held by this lock manager, which keeps confirming it and
        // returns it to the next acquire, no release is attempted in the DB, and no LockReleased is published again
        assertThat(newerLock.isLocked()).isTrue();
        assertThat(lockManager.tryAcquireLock(LOCK_NAME)).containsSame(newerLock);
        awaitConfirmationKeepsRunning(LOCK_NAME);
        assertThat(newerLock.isLocked()).isTrue();
        assertThat(storage.releaseInDBUnitOfWorks).hasSize(releasedInDBBefore);
        assertThat(lockReleasedEventsFor(LOCK_NAME)).hasSize(1);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // newerLock is released by stop() in cleanup(); staleLock was released when it was lost
    void releasing_a_lost_lock_during_shutdown_after_the_lock_was_acquired_again_leaves_the_newer_acquisition_held() {
        // Given a lock that was lost and acquired again by this lock manager, as a new FencedLock
        storage.allowReleaseInDB.countDown();
        var staleLock = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        storage.confirmOutcomeByLock.put(LOCK_NAME, ConfirmOutcome.TAKEN_OVER);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(staleLock.isLocked()).isFalse();
                      assertThat(lockReleasedEventsFor(LOCK_NAME)).hasSize(1);
                      assertThat(storage.releasedInDBTokens).hasSize(1);
                  });
        storage.confirmOutcomeByLock.remove(LOCK_NAME);
        var newerLock = lockManager.tryAcquireLock(LOCK_NAME).orElseThrow();
        var releasedInDBBefore = storage.releaseInDBUnitOfWorks.size();

        // When shutdown starts and the stale lock is released
        lockManager.shutdownStarting(ShutdownContext.startingNow(Duration.ofSeconds(10)));
        staleLock.release();

        // Then the newer acquisition stays held by this lock manager - an acquire during shutdown still returns it - no
        // release is attempted in the DB, and no LockReleased is published again
        assertThat(newerLock.isLocked()).isTrue();
        assertThat(lockManager.tryAcquireLock(LOCK_NAME)).containsSame(newerLock);
        assertThat(storage.releaseInDBUnitOfWorks).hasSize(releasedInDBBefore);
        assertThat(lockReleasedEventsFor(LOCK_NAME)).hasSize(1);
    }

    private static ReentrantLock managerLockOf(DBFencedLockManager<?, ?> lockManager) throws ReflectiveOperationException {
        var field = DBFencedLockManager.class.getDeclaredField("reentrantLock");
        field.setAccessible(true);
        return (ReentrantLock) field.get(lockManager);
    }

    private static ListAppender<ILoggingEvent> captureLockManagerLog() {
        var appender = new ListAppender<ILoggingEvent>();
        // Appended to by the lock manager's threads while the test thread reads it
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(TestDBFencedLockManager.class)).addAppender(appender);
        return appender;
    }

    private void verifyLostLockIsReleasedAndTheConfirmationOfTheTickIsKept(FencedLock lostLock, FencedLock confirmedLock) {
        // The lost lock is released in the DB too, for the token it held
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(storage.releasedInDBTokens).containsExactly(storage.getInitialTokenValue()));
        assertThat(lostLock.isLocked()).isFalse();
        // And LockReleased is published for it
        assertThat(lockReleasedEventsFor(LOCK_NAME)).hasSize(1);
        // And the confirmation UnitOfWork of that tick - which also confirmed the other lock - is committed, not rolled back
        assertThat(storage.lastConfirmationUnitOfWork.get(LOCK_NAME).status()).isEqualTo(UnitOfWorkStatus.Committed);
        // And the scheduled confirmation keeps running
        awaitConfirmationKeepsRunning(OTHER_LOCK_NAME);
        assertThat(confirmedLock.isLocked()).isTrue();
    }

    private void awaitConfirmationKeepsRunning(LockName lockName) {
        var attemptsBefore = storage.confirmAttempts(lockName);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(storage.confirmAttempts(lockName)).isGreaterThanOrEqualTo(attemptsBefore + 3));
    }

    private List<LockReleased> lockReleasedEventsFor(LockName lockName) {
        return events.stream()
                     .filter(event -> event instanceof LockReleased lockReleased && lockReleased.lock.getName().equals(lockName))
                     .map(LockReleased.class::cast)
                     .toList();
    }

    private static LockCallback lockReleasedFailingWith(Error error) {
        return new LockCallback() {
            @Override
            public void lockAcquired(FencedLock lock) {
            }

            @Override
            public void lockReleased(FencedLock lock) {
                throw error;
            }
        };
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
                                FencedLockManagerSettings settings,
                                EventBus eventBus) {
            super(lockStorage, unitOfWorkFactory, settings, eventBus);
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
        /**
         * The number of {@link #confirmLockInDB} calls per lock name
         */
        final    Map<LockName, AtomicInteger>         confirmAttemptsByLock      = new ConcurrentHashMap<>();
        volatile ConfirmOutcome                       confirmOutcome             = ConfirmOutcome.CONFIRMED;
        volatile RuntimeException                     releaseInDBFailure;

        int confirmAttempts(LockName lockName) {
            var attempts = confirmAttemptsByLock.get(lockName);
            return attempts == null ? 0 : attempts.get();
        }

        @Override
        public void initializeLockStorage(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow) {
        }

        @Override
        public boolean confirmLockInDB(DBFencedLockManager<ThreadLocalUnitOfWork, DBFencedLock> lockManager, ThreadLocalUnitOfWork uow, DBFencedLock fencedLock, OffsetDateTime confirmedTimestamp) {
            lastConfirmationUnitOfWork.put(fencedLock.getName(), uow);
            confirmAttemptsByLock.computeIfAbsent(fencedLock.getName(), name -> new AtomicInteger()).incrementAndGet();
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
