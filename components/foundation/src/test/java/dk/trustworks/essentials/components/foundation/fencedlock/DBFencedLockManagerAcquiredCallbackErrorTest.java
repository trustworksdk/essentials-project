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
import dk.trustworks.essentials.components.foundation.fencedlock.DBFencedLockManagerAcquireCommitFailureTest.*;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockEvents.LockReleased;
import dk.trustworks.essentials.components.foundation.lifecycle.ShutdownContext;
import dk.trustworks.essentials.reactive.LocalEventBus;
import dk.trustworks.essentials.shared.Exceptions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

/**
 * A lock callback that fails with a non-critical {@link Error} (an {@link AssertionError}, say) or with a checked exception
 * thrown sneakily (undeclared) must be recovered from like one that fails with a {@link RuntimeException}: a failing
 * <code>lockAcquired</code> releases the lock again - which calls <code>lockReleased</code> - and a later tick of the async
 * acquiring tries to acquire it again (with this test's single lock manager it does, and the callback is retried).
 * <p>
 * Before, such an Error escaped the async acquiring tick, and such a checked exception was only logged: either way the lock
 * stayed held by this instance, with nothing serving it and no other instance able to take it, and no later tick called the
 * callback again.
 * <p>
 * A critical error ({@link Exceptions#isCriticalError(Throwable)}) from <code>lockAcquired</code> still ends the async
 * acquiring of the lock, but the lock is released and the error logged at ERROR first.
 * Uses the in-memory lock table of {@link DBFencedLockManagerAcquireCommitFailureTest}, with every commit succeeding.
 */
class DBFencedLockManagerAcquiredCallbackErrorTest {
    private static final LockName LOCK_NAME = LockName.of("acquired-callback-error-lock");

    private InMemoryLockStorage                   storage;
    private TestDBFencedLockManager               node;
    /**
     * Every {@link FencedLockEvents} the lock manager published
     */
    private List<FencedLockEvents>                 events;

    @BeforeEach
    void setup() {
        storage = new InMemoryLockStorage();
        events = new CopyOnWriteArrayList<>();
        var eventBus = new LocalEventBus("acquired-callback-error-test");
        eventBus.addSyncSubscriber(event -> events.add((FencedLockEvents) event));
        node = new TestDBFencedLockManager(storage,
                                           new StagingUnitOfWorkFactory(storage),
                                           FencedLockManagerSettings.builder()
                                                                    .setLockManagerInstanceId("node")
                                                                    .setLockTimeOut(Duration.ofSeconds(30))
                                                                    .setLockConfirmationInterval(Duration.ofMillis(100))
                                                                    .build(),
                                           eventBus);
        node.start();
    }

    @AfterEach
    void cleanup() {
        node.stop();
    }

    @Test
    void a_lock_acquired_callback_failing_with_an_error_releases_the_lock_and_is_retried() {
        var acquiredCalls = new AtomicInteger();
        var releasedCalls = new AtomicInteger();
        node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                     .onLockAcquired(lock -> {
                                                         if (acquiredCalls.incrementAndGet() == 1) {
                                                             throw new AssertionError("first lockAcquired fails");
                                                         }
                                                     })
                                                     .onLockReleased(lock -> releasedCalls.incrementAndGet())
                                                     .build());

        // The failed callback released the lock (lockReleased was called), and the next tick acquired it again
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(acquiredCalls.get()).isEqualTo(2));
        assertThat(releasedCalls.get()).isEqualTo(1);
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isTrue();
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
        assertThat(storage.rows.get(LOCK_NAME).token()).isEqualTo(storage.getInitialTokenValue() + 1);
    }

    @Test
    void a_lock_acquired_callback_failing_with_a_sneakily_thrown_checked_exception_releases_the_lock_and_is_retried() {
        var acquiredCalls = new AtomicInteger();
        var releasedCalls = new AtomicInteger();
        node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                     .onLockAcquired(lock -> {
                                                         if (acquiredCalls.incrementAndGet() == 1) {
                                                             Exceptions.sneakyThrow(new IOException("first lockAcquired fails"));
                                                         }
                                                     })
                                                     .onLockReleased(lock -> releasedCalls.incrementAndGet())
                                                     .build());

        // The failed callback released the lock (lockReleased was called), and the next tick acquired it again
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(acquiredCalls.get()).isEqualTo(2));
        assertThat(releasedCalls.get()).isEqualTo(1);
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isTrue();
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
        assertThat(storage.rows.get(LOCK_NAME).token()).isEqualTo(storage.getInitialTokenValue() + 1);
    }

    @Test
    void a_lock_acquired_callback_failing_with_a_critical_error_releases_the_lock_logs_the_error_and_ends_the_async_acquiring() {
        var acquiredCalls = new AtomicInteger();
        var releasedCalls = new AtomicInteger();
        var criticalError = new ExceptionInInitializerError("lockAcquired fails critically");
        var logEvents     = captureLockManagerLog();
        try {
            node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                         .onLockAcquired(lock -> {
                                                             acquiredCalls.incrementAndGet();
                                                             throw criticalError;
                                                         })
                                                         .onLockReleased(lock -> releasedCalls.incrementAndGet())
                                                         .build());

            // The lock is released - the row no longer names this instance, so it is neither held nor confirmed by it.
            // The DB release commits before lockReleased runs, so both are waited for
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                          assertThat(releasedCalls.get()).isEqualTo(1);
                      });
            assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isFalse();
            assertThat(node.lookupLock(LOCK_NAME).map(FencedLock::isLockedByThisLockManagerInstance)).hasValue(false);

            // The error ended the async acquiring: many ticks later the callback has not been called again
            Awaitility.await()
                      .during(Duration.ofMillis(500))
                      .atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(acquiredCalls.get()).isEqualTo(1);
                          assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                      });

            // ... and it was logged at ERROR, once, since the scheduled future it escapes into is never read
            assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains(LOCK_NAME.toString(), "critical error", "released");
                        assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable()).isSameAs(criticalError);
                    });
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    /**
     * The recovery path the <code>acquireLockAsync</code> javadoc gives for an acquiring a critical error ended - and that
     * the lock-driven processors' stop/start relies on: a repeated <code>acquireLockAsync</code> does nothing until
     * <code>cancelAsyncLockAcquiring</code> has removed the ended acquiring, after which it acquires the lock again.
     */
    @Test
    void a_critical_error_ended_async_acquiring_is_restarted_only_after_cancelAsyncLockAcquiring() {
        var acquiredCalls = new AtomicInteger();
        var releasedCalls = new AtomicInteger();
        var criticalError = new ExceptionInInitializerError("first lockAcquired fails critically");
        var callback = LockCallback.builder()
                                   .onLockAcquired(lock -> {
                                       if (acquiredCalls.incrementAndGet() == 1) {
                                           throw criticalError;
                                       }
                                   })
                                   .onLockReleased(lock -> releasedCalls.incrementAndGet())
                                   .build();
        var logEvents = captureLockManagerLog();
        try {
            node.acquireLockAsync(LOCK_NAME, callback);

            // The critical error released the lock and ended the async acquiring
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
                          assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                          assertThat(releasedCalls.get()).isEqualTo(1);
                      });
            assertThat(acquiredCalls.get()).isEqualTo(1);

            // Asking again before a cancel does not restart it: the ended acquiring keeps its entry
            node.acquireLockAsync(LOCK_NAME, callback);
            Awaitility.await()
                      .during(Duration.ofMillis(500))
                      .atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(acquiredCalls.get()).isEqualTo(1);
                          assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                          assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isFalse();
                      });

            // Cancelling the ended acquiring removes it, so the next acquireLockAsync acquires the lock and calls lockAcquired again
            node.cancelAsyncLockAcquiring(LOCK_NAME);
            node.acquireLockAsync(LOCK_NAME, callback);
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(acquiredCalls.get()).isEqualTo(2));
            assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isTrue();
            assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
            assertThat(releasedCalls.get()).isEqualTo(1);
            assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    @Test
    void a_failing_lock_released_callback_after_a_critical_error_in_lock_acquired_is_added_to_the_error_as_suppressed() {
        var acquiredCalls = new AtomicInteger();
        var criticalError = new ExceptionInInitializerError("lockAcquired fails critically");
        var releaseError  = new AssertionError("lockReleased fails");
        var logEvents     = captureLockManagerLog();
        try {
            node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                         .onLockAcquired(lock -> {
                                                             acquiredCalls.incrementAndGet();
                                                             throw criticalError;
                                                         })
                                                         .onLockReleased(lock -> {
                                                             throw releaseError;
                                                         })
                                                         .build());

            // The critical error, not the release's failure, is what is logged - carrying the release's failure as suppressed
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1));
            var logged = ((ThrowableProxy) logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR).findFirst().orElseThrow().getThrowableProxy()).getThrowable();
            assertThat(logged).isSameAs(criticalError);
            assertThat(logged.getSuppressed()).containsExactly(releaseError);

            // The lock is no longer held by this instance, so it is no longer confirmed either, and no later tick ran
            Awaitility.await()
                      .during(Duration.ofMillis(500))
                      .atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                          assertThat(acquiredCalls.get()).isEqualTo(1);
                      });
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    @Test
    void a_release_in_the_db_failing_with_a_critical_error_after_one_in_lock_acquired_still_stops_confirming_the_lock() {
        var acquiredCalls = new AtomicInteger();
        var acquiredLock  = new AtomicReference<FencedLock>();
        var releasedLocks = new CopyOnWriteArrayList<FencedLock>();
        var criticalError = new ExceptionInInitializerError("lockAcquired fails critically");
        var releaseError  = new NoClassDefFoundError("releaseLockInDB fails critically");
        var logEvents     = captureLockManagerLog();
        try {
            storage.failNextReleaseWith = releaseError;
            node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                         .onLockAcquired(lock -> {
                                                             acquiredCalls.incrementAndGet();
                                                             acquiredLock.set(lock);
                                                             throw criticalError;
                                                         })
                                                         .onLockReleased(releasedLocks::add)
                                                         .build());

            // The critical error from lockAcquired is what is logged - carrying the release's failure as suppressed
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1));
            var logged = ((ThrowableProxy) logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR).findFirst().orElseThrow().getThrowableProxy()).getThrowable();
            assertThat(logged).isSameAs(criticalError);
            assertThat(logged.getSuppressed()).containsExactly(releaseError);

            // The release failed in the DB, but the lock was released locally: it no longer reports itself as held here,
            // lockReleased was called - so what lockAcquired started can be stopped before another instance takes the lock
            // over - and LockReleased was published
            assertThat(acquiredLock.get()).isNotNull();
            assertThat(acquiredLock.get().isLockedByThisLockManagerInstance()).isFalse();
            assertThat(releasedLocks).singleElement().isSameAs(acquiredLock.get());
            assertThat(events).filteredOn(LockReleased.class::isInstance)
                              .singleElement()
                              .satisfies(event -> assertThat(((LockReleased) event).lock).isSameAs(acquiredLock.get()));

            // The release failed, so the row still names this instance - but it is no longer confirmed (the confirmation
            // interval is 100 ms), so it expires after the lock timeout; and no later tick ran
            var lastConfirmed = storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp();
            Awaitility.await()
                      .during(Duration.ofMillis(600))
                      .atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
                          assertThat(storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp()).isEqualTo(lastConfirmed);
                          assertThat(acquiredCalls.get()).isEqualTo(1);
                      });
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    @Test
    void a_release_in_the_db_failing_with_a_critical_error_after_a_non_critical_one_in_lock_acquired_still_stops_confirming_the_lock() {
        var acquiredCalls = new AtomicInteger();
        var acquiredLock  = new AtomicReference<FencedLock>();
        var releasedLocks = new CopyOnWriteArrayList<FencedLock>();
        var callbackError = new RuntimeException("lockAcquired fails");
        var releaseError  = new NoClassDefFoundError("releaseLockInDB fails critically");
        var logEvents     = captureLockManagerLog();
        try {
            storage.failNextReleaseWith = releaseError;
            node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                         .onLockAcquired(lock -> {
                                                             acquiredCalls.incrementAndGet();
                                                             acquiredLock.set(lock);
                                                             throw callbackError;
                                                         })
                                                         .onLockReleased(releasedLocks::add)
                                                         .build());

            // Two ERROR lines: the failed callback, then the release's critical error, which ends the async acquiring
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(2));
            var errors = logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertThat(((ThrowableProxy) errors.get(0).getThrowableProxy()).getThrowable()).isSameAs(callbackError);
            assertThat(errors.get(1).getFormattedMessage()).contains("A critical error occurred in the async acquiring of lock", LOCK_NAME.toString());
            assertThat(((ThrowableProxy) errors.get(1).getThrowableProxy()).getThrowable()).isSameAs(releaseError);
            assertThat(releaseError.getSuppressed()).isEmpty();

            // The release failed in the DB, but the lock was released locally: it no longer reports itself as held here,
            // lockReleased was called - so what lockAcquired started can be stopped before another instance takes the lock
            // over - and LockReleased was published
            assertThat(acquiredLock.get()).isNotNull();
            assertThat(acquiredLock.get().isLockedByThisLockManagerInstance()).isFalse();
            assertThat(releasedLocks).singleElement().isSameAs(acquiredLock.get());
            assertThat(events).filteredOn(LockReleased.class::isInstance)
                              .singleElement()
                              .satisfies(event -> assertThat(((LockReleased) event).lock).isSameAs(acquiredLock.get()));

            // The release failed, so the row still names this instance - but it is no longer confirmed (the confirmation
            // interval is 100 ms), so it expires after the lock timeout; and no later tick ran
            var lastConfirmed = storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp();
            Awaitility.await()
                      .during(Duration.ofMillis(600))
                      .atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
                          assertThat(storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp()).isEqualTo(lastConfirmed);
                          assertThat(acquiredCalls.get()).isEqualTo(1);
                      });
            assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(2);
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    @Test
    void a_failing_local_release_after_a_critical_error_releasing_in_the_db_is_added_to_that_error_as_suppressed() {
        var acquiredLock  = new AtomicReference<FencedLock>();
        var callbackError = new RuntimeException("lockAcquired fails");
        var releaseError  = new NoClassDefFoundError("releaseLockInDB fails critically");
        var localError    = new AssertionError("lockReleased fails");
        var logEvents     = captureLockManagerLog();
        try {
            storage.failNextReleaseWith = releaseError;
            node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                         .onLockAcquired(lock -> {
                                                             acquiredLock.set(lock);
                                                             throw callbackError;
                                                         })
                                                         .onLockReleased(lock -> {
                                                             throw localError;
                                                         })
                                                         .build());

            // The release's critical error, not the local release's failure, is what escapes the tick and is logged -
            // carrying the local release's failure as suppressed
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(2));
            var errors = logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertThat(((ThrowableProxy) errors.get(1).getThrowableProxy()).getThrowable()).isSameAs(releaseError);
            assertThat(releaseError.getSuppressed()).containsExactly(localError);

            // ... and the local release was completed all the same: marked released and LockReleased published
            assertThat(acquiredLock.get().isLockedByThisLockManagerInstance()).isFalse();
            assertThat(events).filteredOn(LockReleased.class::isInstance)
                              .singleElement()
                              .satisfies(event -> assertThat(((LockReleased) event).lock).isSameAs(acquiredLock.get()));
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // The re-acquired lock must stay held for the assertions; the node.stop() in cleanup releases it
    void a_lock_re_acquired_by_the_lock_released_callback_after_a_failed_lock_acquired_stays_held_and_confirmed() {
        var acquiredCalls = new AtomicInteger();
        var reacquired    = new AtomicReference<FencedLock>();
        node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                     .onLockAcquired(lock -> {
                                                         if (acquiredCalls.incrementAndGet() == 1) {
                                                             throw new RuntimeException("first lockAcquired fails");
                                                         }
                                                     })
                                                     .onLockReleased(lock -> {
                                                         // On the async acquiring's thread, inside the release of the lock whose
                                                         // lockAcquired failed: the lock is free again, so this acquires it anew
                                                         if (reacquired.get() == null) {
                                                             reacquired.set(node.tryAcquireLock(LOCK_NAME).orElseThrow());
                                                         }
                                                     })
                                                     .build());

        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(reacquired.get()).isNotNull());
        var reacquiredLock = reacquired.get();
        assertThat(reacquiredLock.isLockedByThisLockManagerInstance()).isTrue();

        // The newer acquisition is not dropped along with the failed one (DBFencedLock.equals compares the name only): it
        // stays this lock manager's - the cached instance is returned - and its row keeps being confirmed, rather than
        // expiring while its holder believes it holds it
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp()).isAfter(reacquiredLock.getLockAcquiredTimestamp()));
        var confirmed = storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp();
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp()).isAfter(confirmed));
        assertThat(node.tryAcquireLock(LOCK_NAME)).containsSame(reacquiredLock);
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
        assertThat(storage.rows.get(LOCK_NAME).token()).isEqualTo(reacquiredLock.getCurrentToken());
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isTrue();
        // The async acquiring finds the lock held here, so it does not acquire it again
        assertThat(acquiredCalls.get()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // The re-acquired lock must stay held for the assertions; the node.stop() in cleanup releases it
    void a_lock_re_acquired_by_a_lock_released_callback_that_then_throws_after_a_failed_lock_acquired_stays_held_and_confirmed() {
        var acquiredCalls = new AtomicInteger();
        var reacquired    = new AtomicReference<FencedLock>();
        node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                     .onLockAcquired(lock -> {
                                                         if (acquiredCalls.incrementAndGet() == 1) {
                                                             throw new RuntimeException("first lockAcquired fails");
                                                         }
                                                     })
                                                     .onLockReleased(lock -> {
                                                         // On the async acquiring's thread, inside the release of the lock whose
                                                         // lockAcquired failed: the lock is free again, so this acquires it anew -
                                                         // and then fails, so the release throws after the newer acquisition has
                                                         // been recorded, and the failed lock is released locally past it
                                                         if (reacquired.get() == null) {
                                                             reacquired.set(node.tryAcquireLock(LOCK_NAME).orElseThrow());
                                                             throw new RuntimeException("lockReleased fails after re-acquiring the lock");
                                                         }
                                                     })
                                                     .build());

        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(reacquired.get()).isNotNull());
        var reacquiredLock = reacquired.get();

        // The local release of the failed acquisition removes that lock only (DBFencedLock.equals compares the name only), so
        // the newer acquisition stays this lock manager's - the cached instance is returned - and its row keeps being
        // confirmed, rather than expiring while its holder believes it holds it
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp()).isAfter(reacquiredLock.getLockAcquiredTimestamp()));
        var confirmed = storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp();
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp()).isAfter(confirmed));
        assertThat(reacquiredLock.isLockedByThisLockManagerInstance()).isTrue();
        assertThat(node.tryAcquireLock(LOCK_NAME)).containsSame(reacquiredLock);
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
        assertThat(storage.rows.get(LOCK_NAME).token()).isEqualTo(reacquiredLock.getCurrentToken());
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isTrue();
        // The async acquiring finds the lock held here, so it does not acquire it again
        assertThat(acquiredCalls.get()).isEqualTo(1);
    }

    @Test
    void a_critical_error_outside_lock_acquired_is_logged_and_ends_the_async_acquiring() {
        var acquiredCalls = new AtomicInteger();
        var criticalError = new ExceptionInInitializerError("looking up the lock fails critically");
        var logEvents     = captureLockManagerLog();
        try {
            storage.failNextLookupWith = criticalError;
            node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                         .onLockAcquired(lock -> acquiredCalls.incrementAndGet())
                                                         .onLockReleased(lock -> {
                                                         })
                                                         .build());

            // Logged at ERROR, once, since the scheduled future it escapes into is never read
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1));
            assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR))
                    .first()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("A critical error occurred in the async acquiring of lock", LOCK_NAME.toString());
                        assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable()).isSameAs(criticalError);
                    });

            // The error ended the async acquiring: many ticks later the lock has not been looked up again, nor acquired
            var lookups = storage.lookups.get();
            Awaitility.await()
                      .during(Duration.ofMillis(500))
                      .atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(storage.lookups.get()).isEqualTo(lookups);
                          assertThat(acquiredCalls.get()).isZero();
                          assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
                      });
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    @Test
    void a_lock_released_callback_failing_with_an_error_does_not_end_the_async_acquiring() {
        var acquiredCalls       = new AtomicInteger();
        var releasedCalls       = new AtomicInteger();
        var releasedLock        = new AtomicReference<FencedLock>();
        var secondReleasedCalls = new AtomicInteger();
        node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                     .onLockAcquired(lock -> {
                                                         if (acquiredCalls.incrementAndGet() == 1) {
                                                             // A second callback on the same lock, registered after the failing one
                                                             lock.registerCallback(LockCallback.builder()
                                                                                               .onLockAcquired(_lock -> {
                                                                                               })
                                                                                               .onLockReleased(_lock -> secondReleasedCalls.incrementAndGet())
                                                                                               .build());
                                                             throw new AssertionError("first lockAcquired fails");
                                                         }
                                                     })
                                                     .onLockReleased(lock -> {
                                                         if (releasedCalls.incrementAndGet() == 1) {
                                                             releasedLock.set(lock);
                                                             throw new AssertionError("first lockReleased fails");
                                                         }
                                                     })
                                                     .build());

        // The Error from lockReleased (called by the release after the failed lockAcquired) does not end the
        // scheduled acquiring: a later tick acquires the lock again
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(acquiredCalls.get()).isEqualTo(2));
        assertThat(releasedCalls.get()).isEqualTo(1);
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isTrue();

        // ... and the release it interrupted was completed all the same: the released lock no longer reports itself
        // locked, the callback registered after the failing one was called, and LockReleased was published for it
        assertThat(releasedLock.get()).isNotNull();
        assertThat(releasedLock.get().isLocked()).isFalse();
        assertThat(releasedLock.get().isLockedByThisLockManagerInstance()).isFalse();
        assertThat(secondReleasedCalls.get()).isEqualTo(1);
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(LockReleased.class,
                                                                                        released -> assertThat(released.lock).isSameAs(releasedLock.get())));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // The lock manager owns the held lock and the test needs it held until the takeover; the async acquiring drops it once it finds it lost, and the node.stop() in cleanup releases the lock it re-acquires
    void a_lock_released_callback_failing_with_an_error_when_the_async_acquiring_finds_the_lock_lost_does_not_end_the_async_acquiring() {
        var acquiredLocks         = new CopyOnWriteArrayList<FencedLock>();
        var releasedCalls         = new AtomicInteger();
        var releasedOnThread      = new AtomicReference<String>();
        var releaseError          = new AssertionError("first lockReleased fails");
        var logEvents             = captureLockManagerLog();
        try {
            node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                         .onLockAcquired(lock -> {
                                                             // markAsLocked below calls this too, with the lock naming the other instance
                                                             if (lock.isLockedByThisLockManagerInstance()) {
                                                                 acquiredLocks.add(lock);
                                                             }
                                                         })
                                                         .onLockReleased(lock -> {
                                                             if (releasedCalls.incrementAndGet() == 1) {
                                                                 releasedOnThread.set(Thread.currentThread().getName());
                                                                 throw releaseError;
                                                             }
                                                         })
                                                         .build());
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(acquiredLocks).hasSize(1));
            var heldLock = (DBFencedLock) acquiredLocks.get(0);

            // Paused - and once the confirmation has logged that it skips, so none is in flight - the lock manager confirms no
            // lock, so the lost lock is noticed by the async acquiring tick, which checks a held lock while paused too. Another
            // instance takes the lock over: the row names it, and so does the lock held here, which stays recorded as acquired by
            // this instance - the tick's lost-lock branch
            node.pause();
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .until(() -> logEvents.list.stream().anyMatch(event -> event.getFormattedMessage().contains("skipping confirmAllLocallyAcquiredLocks")));
            var takenOverRow = storage.rows.get(LOCK_NAME);
            var takenOverAt  = OffsetDateTime.now(Clock.systemUTC());
            storage.rows.put(LOCK_NAME, new Row(takenOverRow.token() + 1, "other-node", takenOverAt, takenOverAt));
            heldLock.markAsLocked(takenOverAt, "other-node", takenOverRow.token() + 1);

            // The async acquiring tick called lockReleased, which failed
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(releasedCalls.get()).isEqualTo(1));
            assertThat(releasedOnThread.get()).contains("-Lock-Acquiring-");

            // The other instance releases the lock: the Error did not end the async acquiring, so a later tick acquires it again
            var releasedRow = storage.rows.get(LOCK_NAME);
            storage.rows.put(LOCK_NAME, new Row(releasedRow.token(), null, releasedRow.lockAcquiredTimestamp(), releasedRow.lockLastConfirmedTimestamp()));
            node.resume();
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(acquiredLocks).hasSize(2));
            assertThat(releasedCalls.get()).isEqualTo(1);
            assertThat(acquiredLocks.get(1).isLockedByThisLockManagerInstance()).isTrue();
            assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
            assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isTrue();

            // ... and the Error was logged, once
            assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR))
                    .singleElement()
                    .satisfies(event -> assertThat(((ThrowableProxy) event.getThrowableProxy()).getThrowable()).isSameAs(releaseError));
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // close() is release(), the call under test, made inside assertThatThrownBy
    void a_failing_lock_released_callback_does_not_leave_a_released_lock_half_marked_or_unannounced() {
        var lock                = node.tryAcquireLock(LOCK_NAME).orElseThrow();
        var callbackError       = new AssertionError("lockReleased fails");
        var secondReleasedCalls = new AtomicInteger();
        var lockedWhileReleasing = new AtomicBoolean(true);
        lock.registerCallback(LockCallback.builder()
                                          .onLockAcquired(_lock -> {
                                          })
                                          .onLockReleased(_lock -> {
                                              lockedWhileReleasing.set(_lock.isLockedByThisLockManagerInstance());
                                              throw callbackError;
                                          })
                                          .build());
        lock.registerCallback(LockCallback.builder()
                                          .onLockAcquired(_lock -> {
                                          })
                                          .onLockReleased(_lock -> secondReleasedCalls.incrementAndGet())
                                          .build());

        // The callback's failure reaches the caller of release() ...
        assertThatThrownBy(lock::release).isSameAs(callbackError);

        // ... once the release is complete: in the DB, locally, every callback called and LockReleased published
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isFalse();
        assertThat(lock.isLocked()).isFalse();
        assertThat(lock.isLockedByThisLockManagerInstance()).isFalse();
        assertThat(secondReleasedCalls.get()).isEqualTo(1);
        // The lock already read as released while its callbacks ran
        assertThat(lockedWhileReleasing.get()).isFalse();
        assertThat(events).filteredOn(LockReleased.class::isInstance)
                          .singleElement()
                          .satisfies(event -> assertThat(((LockReleased) event).lock).isSameAs(lock));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // close() is release(), the call under test, made inside assertThatThrownBy
    void a_failing_lock_released_callback_during_shutdown_does_not_leave_a_released_lock_half_marked_or_unannounced() {
        var lock                = node.tryAcquireLock(LOCK_NAME).orElseThrow();
        var callbackError       = new AssertionError("lockReleased fails");
        var secondReleasedCalls = new AtomicInteger();
        lock.registerCallback(LockCallback.builder()
                                          .onLockAcquired(_lock -> {
                                          })
                                          .onLockReleased(_lock -> {
                                              throw callbackError;
                                          })
                                          .build());
        lock.registerCallback(LockCallback.builder()
                                          .onLockAcquired(_lock -> {
                                          })
                                          .onLockReleased(_lock -> secondReleasedCalls.incrementAndGet())
                                          .build());
        node.shutdownStarting(ShutdownContext.startingNow(Duration.ofSeconds(10)));

        assertThatThrownBy(lock::release).isSameAs(callbackError);

        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isFalse();
        assertThat(lock.isLocked()).isFalse();
        assertThat(lock.isLockedByThisLockManagerInstance()).isFalse();
        assertThat(secondReleasedCalls.get()).isEqualTo(1);
        assertThat(events).filteredOn(LockReleased.class::isInstance)
                          .singleElement()
                          .satisfies(event -> assertThat(((LockReleased) event).lock).isSameAs(lock));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // Releasing the locks is the job of node.stop(), the call under test
    void lock_released_callbacks_failing_with_an_error_while_stopping_do_not_keep_the_other_locks_held_or_the_lock_manager_started() {
        var otherLockName = LockName.of("acquired-callback-error-other-lock");
        var lock          = node.tryAcquireLock(LOCK_NAME).orElseThrow();
        var otherLock     = node.tryAcquireLock(otherLockName).orElseThrow();
        for (var heldLock : List.of(lock, otherLock)) {
            heldLock.registerCallback(LockCallback.builder()
                                                  .onLockAcquired(_lock -> {
                                                  })
                                                  .onLockReleased(_lock -> {
                                                      throw new AssertionError("lockReleased fails");
                                                  })
                                                  .build());
        }

        assertThatCode(node::stop).doesNotThrowAnyException();

        // Every lock is released, in the DB too, and the lock manager is stopped
        assertThat(lock.isLocked()).isFalse();
        assertThat(otherLock.isLocked()).isFalse();
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
        assertThat(storage.rows.get(otherLockName).lockedBy()).isNull();
        assertThat(node.isStarted()).isFalse();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // Releasing the lock is the job of node.stop(), the call under test: it releases the lock before the lockReleased callback throws
    void a_lock_released_callback_failing_with_a_critical_error_while_stopping_is_thrown_from_stop() {
        var lock          = node.tryAcquireLock(LOCK_NAME).orElseThrow();
        var criticalError = new ExceptionInInitializerError("lockReleased fails critically");
        var failed        = new AtomicBoolean();
        lock.registerCallback(LockCallback.builder()
                                          .onLockAcquired(_lock -> {
                                          })
                                          .onLockReleased(_lock -> {
                                              // Once only, so the stop() in cleanup completes
                                              if (failed.compareAndSet(false, true)) {
                                                  throw criticalError;
                                              }
                                          })
                                          .build());

        assertThatThrownBy(node::stop).isSameAs(criticalError);
    }

    private static ListAppender<ILoggingEvent> captureLockManagerLog() {
        var appender = new ListAppender<ILoggingEvent>();
        // Appended to by the lock manager's threads while the test thread reads it
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(TestDBFencedLockManager.class)).addAppender(appender);
        return appender;
    }

    private static void detachLockManagerLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(TestDBFencedLockManager.class)).detachAppender(appender);
    }
}
