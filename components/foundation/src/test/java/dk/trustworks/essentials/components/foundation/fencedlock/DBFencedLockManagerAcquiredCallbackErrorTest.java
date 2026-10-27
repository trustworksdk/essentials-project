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

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.components.foundation.fencedlock.DBFencedLockManagerTestSupport.*;
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
    /**
     * Run by the lock manager's sync event subscriber for every event, after recording it - may throw
     */
    private volatile Consumer<FencedLockEvents>    eventSubscriberAction = event -> {
    };

    @BeforeEach
    void setup() {
        storage = new InMemoryLockStorage();
        events = new CopyOnWriteArrayList<>();
        var eventBus = new LocalEventBus("acquired-callback-error-test");
        eventBus.addSyncSubscriber(event -> {
            events.add((FencedLockEvents) event);
            eventSubscriberAction.accept((FencedLockEvents) event);
        });
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
        eventSubscriberAction = event -> {
        };
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

    /**
     * The other recovery path the <code>acquireLockAsync</code> javadoc gives: <code>stop()</code> keeps every async acquiring's
     * registration, the ended one included, and <code>start()</code> schedules each again - with nothing registered again.
     */
    @Test
    void a_critical_error_ended_async_acquiring_is_restarted_by_a_stop_and_start_of_the_lock_manager() {
        var acquiredCalls = new AtomicInteger();
        var releasedCalls = new AtomicInteger();
        var criticalError = new ExceptionInInitializerError("first lockAcquired fails critically");
        var logEvents     = captureLockManagerLog();
        try {
            node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                         .onLockAcquired(lock -> {
                                                             if (acquiredCalls.incrementAndGet() == 1) {
                                                                 throw criticalError;
                                                             }
                                                         })
                                                         .onLockReleased(lock -> releasedCalls.incrementAndGet())
                                                         .build());

            // The critical error released the lock and ended the async acquiring
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
                          assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                          assertThat(releasedCalls.get()).isEqualTo(1);
                      });
            assertThat(acquiredCalls.get()).isEqualTo(1);

            // A stop and start of the lock manager schedules the kept registration again: it acquires the lock and calls
            // lockAcquired again, although acquireLockAsync was not called again
            node.stop();
            node.start();
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(acquiredCalls.get()).isEqualTo(2));
            assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isTrue();
            assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
            assertThat(releasedCalls.get()).isEqualTo(1);
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    /**
     * <code>start()</code> resumes every registration the last <code>stop()</code> kept, also one whose component was
     * discarded without calling <code>cancelAsyncLockAcquiring</code>: its callback is given the lock again and nothing else on
     * this instance serves it. So each resumed registration is logged at WARN, naming the lock and the callback's class.
     */
    @Test
    void start_logs_each_resumed_async_acquiring_at_warn_naming_the_lock_and_the_callbacks_class() {
        var otherLockName = LockName.of("acquired-callback-error-other-lock");
        var callback      = new CountingLockCallback();
        var otherCallback = new CountingLockCallback();
        node.acquireLockAsync(LOCK_NAME, callback);
        node.acquireLockAsync(otherLockName, otherCallback);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(callback.acquired.get()).isEqualTo(1);
                      assertThat(otherCallback.acquired.get()).isEqualTo(1);
                  });

        node.stop();
        var logEvents = captureLockManagerLog();
        try {
            node.start();

            var resumedWarnings = logEvents.list.stream()
                                                .filter(event -> event.getLevel() == Level.WARN)
                                                .map(ILoggingEvent::getFormattedMessage)
                                                .filter(message -> message.contains("Resuming async lock acquiring"))
                                                .toList();
            assertThat(resumedWarnings).hasSize(2);
            assertThat(resumedWarnings).filteredOn(message -> message.contains("'" + LOCK_NAME + "'"))
                                       .singleElement()
                                       .satisfies(message -> assertThat(message).contains(CountingLockCallback.class.getName(),
                                                                                         "cancelAsyncLockAcquiring"));
            assertThat(resumedWarnings).filteredOn(message -> message.contains("'" + otherLockName + "'"))
                                       .singleElement()
                                       .satisfies(message -> assertThat(message).contains(CountingLockCallback.class.getName()));

            // The resumed registrations are given their locks again, through the same callbacks
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(callback.acquired.get()).isEqualTo(2);
                          assertThat(otherCallback.acquired.get()).isEqualTo(2);
                      });
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    @Test
    void an_async_acquiring_cancelled_while_the_lock_manager_is_stopped_is_not_resumed_by_its_start() {
        var acquiredCalls = new AtomicInteger();
        var releasedCalls = new AtomicInteger();
        node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                     .onLockAcquired(lock -> acquiredCalls.incrementAndGet())
                                                     .onLockReleased(lock -> releasedCalls.incrementAndGet())
                                                     .build());
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(acquiredCalls.get()).isEqualTo(1));

        // The stop releases the lock; cancelAsyncLockAcquiring then removes the kept registration for good
        node.stop();
        assertThat(releasedCalls.get()).isEqualTo(1);
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
        node.cancelAsyncLockAcquiring(LOCK_NAME);
        node.start();

        // So the start does not resume it: many ticks later the lock is not acquired and lockAcquired not called again
        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(acquiredCalls.get()).isEqualTo(1);
                      assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                  });
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isFalse();
    }

    /**
     * <code>start()</code> reschedules each kept registration while it holds the lock manager's own lock, so the first tick
     * of a resumed acquiring runs at once and waits for that lock. A <code>cancelAsyncLockAcquiring</code> in that window
     * cancels a tick that is already running: its interrupt does not end a wait for that lock, and with a driver that does
     * not fail on a pending interrupt the tick then went on to acquire the lock for the cancelled registration, leaving it
     * held by this instance with no registration that would release it. Here the test thread holds the lock manager's lock
     * around <code>start()</code> (the lock is reentrant, so <code>start()</code> takes it again and returns with it still
     * held), which leaves the resumed tick waiting for it as <code>start()</code> does.
     */
    @Test
    void a_resumed_async_acquiring_cancelled_while_its_first_tick_waits_for_the_lock_managers_lock_does_not_acquire_the_lock() throws Exception {
        storage.failLookupsWhileInterrupted = false;
        var callback = new CountingLockCallback();
        node.acquireLockAsync(LOCK_NAME, callback);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(callback.acquired.get()).isEqualTo(1));
        node.stop();
        assertThat(callback.released.get()).isEqualTo(1);

        var lockManagersLock = node.reentrantLockForTests();
        lockManagersLock.lock();
        try {
            node.start();
            // The resumed acquiring's first tick is running, and waits for the lock manager's lock
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .until(() -> asyncAcquiringThreadsOf(node).stream().anyMatch(lockManagersLock::hasQueuedThread));
            node.cancelAsyncLockAcquiring(LOCK_NAME);
        } finally {
            lockManagersLock.unlock();
        }

        // The tick, once it has the lock, finds its registration cancelled: the lock is not acquired, lockAcquired not called
        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(callback.acquired.get()).isEqualTo(1);
                      assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                  });
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isFalse();
    }

    /**
     * <code>cancelAsyncLockAcquiring</code> does not take the lock manager's lock, so it can land while a tick is in the
     * database round trip of acquiring the lock, after the tick checked its registration. The cancel then finds no lock to
     * release, and the tick went on to acquire the lock for the cancelled registration and call its <code>lockAcquired</code>,
     * leaving the lock held and confirmed by this instance with no registration that would release it. Here the tick's look-up
     * of the lock is held at a gate that an interrupt does not end - a driver whose round trip a cancel's interrupt does not
     * abort - while the test cancels. The tick then releases the lock with the cancel's interrupt pending, and a release in
     * the DB fails while the thread is interrupted, as on MongoDB, so the row is only freed when that release sets the
     * interrupt aside.
     */
    @Test
    void an_async_acquiring_cancelled_while_its_tick_acquires_the_lock_releases_the_lock_and_does_not_call_lock_acquired() {
        storage.failLookupsWhileInterrupted = false;
        storage.failReleasesWhileInterrupted = true;
        storage.blockedLookupIgnoresInterrupt = true;
        var gate = new CountDownLatch(1);
        storage.blockedLookupName = LOCK_NAME;
        storage.blockedLookupGate = gate;
        var callback = new CountingLockCallback();
        try {
            node.acquireLockAsync(LOCK_NAME, callback);
            // The tick has checked its registration and is in the look-up of the lock it is acquiring
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .until(() -> storage.lookups.get() >= 1);

            node.cancelAsyncLockAcquiring(LOCK_NAME);
        } finally {
            gate.countDown();
        }

        // The tick acquires the lock in the DB, finds its registration cancelled and releases the lock again: lockAcquired
        // is not called, and nothing is left held by this instance
        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(storage.rows.get(LOCK_NAME)).isNotNull();
                      assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                      assertThat(callback.acquired.get()).isZero();
                  });
        assertThat(callback.released.get()).isZero();
        storage.blockedLookupGate = null;
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isFalse();
        assertThat(events).filteredOn(LockReleased.class::isInstance).hasSize(1);
    }

    /**
     * As {@link #an_async_acquiring_cancelled_while_its_tick_acquires_the_lock_releases_the_lock_and_does_not_call_lock_acquired()},
     * but the tick's release of the lock fails in the DB with a critical error: the lock is still released locally, so this
     * lock manager stops confirming it and its row expires after the lock timeout, and the critical error ends the async
     * acquiring, logged at ERROR.
     */
    @Test
    void a_release_in_the_db_failing_with_a_critical_error_after_a_cancel_during_the_ticks_acquire_still_stops_confirming_the_lock() {
        storage.failLookupsWhileInterrupted = false;
        storage.blockedLookupIgnoresInterrupt = true;
        var gate = new CountDownLatch(1);
        storage.blockedLookupName = LOCK_NAME;
        storage.blockedLookupGate = gate;
        var releaseError = new NoClassDefFoundError("releaseLockInDB fails critically");
        var callback     = new CountingLockCallback();
        var logEvents    = captureLockManagerLog();
        try {
            try {
                node.acquireLockAsync(LOCK_NAME, callback);
                // The tick has checked its registration and is in the look-up of the lock it is acquiring
                Awaitility.waitAtMost(Duration.ofSeconds(5))
                          .until(() -> storage.lookups.get() >= 1);

                node.cancelAsyncLockAcquiring(LOCK_NAME);
                storage.failNextReleaseWith = releaseError;
            } finally {
                gate.countDown();
            }

            // The release's critical error ends the async acquiring, logged at ERROR
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1));
            var error = logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR).findFirst().orElseThrow();
            assertThat(error.getFormattedMessage()).contains("A critical error occurred in the async acquiring of lock", LOCK_NAME.toString());
            assertThat(((ThrowableProxy) error.getThrowableProxy()).getThrowable()).isSameAs(releaseError);

            // The release failed in the DB, but the lock was released locally: LockReleased was published, the released lock no
            // longer reports itself as held here, and no callback was called (none was registered with the lock)
            storage.blockedLookupGate = null;
            assertThat(events).filteredOn(LockReleased.class::isInstance)
                              .singleElement()
                              .satisfies(event -> assertThat(((LockReleased) event).lock.isLockedByThisLockManagerInstance()).isFalse());
            assertThat(callback.acquired.get()).isZero();
            assertThat(callback.released.get()).isZero();

            // The release failed, so the row still names this instance - but it is no longer confirmed (the confirmation
            // interval is 100 ms), so it expires after the lock timeout
            var lastConfirmed = storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp();
            Awaitility.await()
                      .during(Duration.ofMillis(600))
                      .atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
                          assertThat(storage.rows.get(LOCK_NAME).lockLastConfirmedTimestamp()).isEqualTo(lastConfirmed);
                          assertThat(callback.acquired.get()).isZero();
                      });
            assertThat(logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    /**
     * A component recreated after a stop and start of the lock manager registers its own callback for the lock name that
     * the old one's registration was kept for: the new callback replaces the kept one. The lock the resumed acquiring
     * acquired for the old callback is released first, and the next tick acquires it for the new one.
     */
    @Test
    void acquireLockAsync_with_another_callback_replaces_the_callback_of_an_async_acquiring_resumed_by_start() {
        var first = new CountingLockCallback();
        node.acquireLockAsync(LOCK_NAME, first);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(first.acquired.get()).isEqualTo(1));
        node.stop();
        node.start();
        // start() resumed the kept registration: the lock is acquired for the first callback again
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(first.acquired.get()).isEqualTo(2));
        assertThat(first.released.get()).isEqualTo(1);
        var tokenAcquiredForTheFirstCallback = storage.rows.get(LOCK_NAME).token();

        // The same callback again does nothing: the lock stays held for it
        node.acquireLockAsync(LOCK_NAME, first);
        assertThat(first.released.get()).isEqualTo(1);
        assertThat(storage.rows.get(LOCK_NAME).token()).isEqualTo(tokenAcquiredForTheFirstCallback);

        // Another callback replaces it: the lock held for the first callback is released, and acquired for the second
        var second = new CountingLockCallback();
        node.acquireLockAsync(LOCK_NAME, second);
        assertThat(first.released.get()).isEqualTo(2);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(second.acquired.get()).isEqualTo(1));
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
        assertThat(storage.rows.get(LOCK_NAME).token()).isEqualTo(tokenAcquiredForTheFirstCallback + 1);
        assertThat(second.lastAcquired.getCurrentToken()).isEqualTo(tokenAcquiredForTheFirstCallback + 1);

        // The second callback's registration was made after the start, so a third callback does not replace it ...
        var third = new CountingLockCallback();
        node.acquireLockAsync(LOCK_NAME, third);
        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(first.acquired.get()).isEqualTo(2);
                      assertThat(second.acquired.get()).isEqualTo(1);
                      assertThat(second.released.get()).isZero();
                      assertThat(third.acquired.get()).isZero();
                  });

        // ... and cancelling calls lockReleased on the second callback, the one the lock is held for
        node.cancelAsyncLockAcquiring(LOCK_NAME);
        assertThat(second.released.get()).isEqualTo(1);
        assertThat(first.released.get()).isEqualTo(2);
        assertThat(third.released.get()).isZero();
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
    }

    /**
     * The same replacement, made while the lock manager is stopped - by a component recreated before the lock manager is
     * started again: <code>start()</code> resumes the registration with the new callback, and the old one is not called again.
     */
    @Test
    void acquireLockAsync_with_another_callback_while_the_lock_manager_is_stopped_replaces_the_callback_start_resumes() {
        var first = new CountingLockCallback();
        node.acquireLockAsync(LOCK_NAME, first);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(first.acquired.get()).isEqualTo(1));
        node.stop();
        assertThat(first.released.get()).isEqualTo(1);

        var second = new CountingLockCallback();
        node.acquireLockAsync(LOCK_NAME, second);
        node.start();

        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(second.acquired.get()).isEqualTo(1));
        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(first.acquired.get()).isEqualTo(1);
                      assertThat(first.released.get()).isEqualTo(1);
                      assertThat(second.acquired.get()).isEqualTo(1);
                  });
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");

        node.cancelAsyncLockAcquiring(LOCK_NAME);
        assertThat(second.released.get()).isEqualTo(1);
        assertThat(first.released.get()).isEqualTo(1);
    }

    /**
     * The replacement releases the lock the resumed acquiring acquired for the kept callback. When that callback's
     * <code>lockReleased</code> throws, the failure is logged at ERROR and the new callback is registered all the same: the
     * next tick acquires the lock for it, and the kept callback is not called again.
     */
    @Test
    void a_failing_lock_released_callback_of_the_replaced_callback_is_logged_and_the_new_callback_registered_all_the_same() {
        var first        = new CountingLockCallback();
        var releaseError = new IllegalStateException("lockReleased of the replaced callback fails");
        var logEvents    = captureLockManagerLog();
        try {
            node.acquireLockAsync(LOCK_NAME, first);
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(first.acquired.get()).isEqualTo(1));
            node.stop();
            node.start();
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(first.acquired.get()).isEqualTo(2));
            var tokenAcquiredForTheFirstCallback = storage.rows.get(LOCK_NAME).token();

            first.failReleaseWith = releaseError;
            var second = new CountingLockCallback();
            assertThatCode(() -> node.acquireLockAsync(LOCK_NAME, second)).doesNotThrowAnyException();
            assertThat(first.released.get()).isEqualTo(2);

            var errors = logEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getFormattedMessage()).contains(LOCK_NAME.toString(), "replacing the callback");
            assertThat(((ThrowableProxy) errors.getFirst().getThrowableProxy()).getThrowable()).isSameAs(releaseError);

            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> assertThat(second.acquired.get()).isEqualTo(1));
            assertThat(second.lastAcquired.getCurrentToken()).isEqualTo(tokenAcquiredForTheFirstCallback + 1);
            assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node");
            Awaitility.await()
                      .during(Duration.ofMillis(500))
                      .atMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> {
                          assertThat(first.acquired.get()).isEqualTo(2);
                          assertThat(first.released.get()).isEqualTo(2);
                          assertThat(second.acquired.get()).isEqualTo(1);
                      });
        } finally {
            detachLockManagerLog(logEvents);
        }
    }

    /**
     * A critical error from the replaced callback's <code>lockReleased</code> is rethrown by <code>acquireLockAsync</code>,
     * which then registers nothing: the lock, released before the callback ran, is not acquired again.
     */
    @Test
    void a_critical_error_from_the_lock_released_callback_of_the_replaced_callback_is_rethrown() {
        var first         = new CountingLockCallback();
        var criticalError = new NoClassDefFoundError("lockReleased of the replaced callback fails critically");
        node.acquireLockAsync(LOCK_NAME, first);
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(first.acquired.get()).isEqualTo(1));
        node.stop();
        node.start();
        Awaitility.waitAtMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> assertThat(first.acquired.get()).isEqualTo(2));

        first.failReleaseWith = criticalError;
        var second = new CountingLockCallback();
        assertThatThrownBy(() -> node.acquireLockAsync(LOCK_NAME, second)).isSameAs(criticalError);
        assertThat(first.released.get()).isEqualTo(2);
        assertThat(node.isLockedByThisLockManagerInstance(LOCK_NAME)).isFalse();

        Awaitility.await()
                  .during(Duration.ofMillis(500))
                  .atMost(Duration.ofSeconds(5))
                  .untilAsserted(() -> {
                      assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
                      assertThat(first.acquired.get()).isEqualTo(2);
                      assertThat(second.acquired.get()).isZero();
                  });
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
            assertThat(releasedOnThread.get()).contains(DBFencedLockManager.ASYNC_LOCK_ACQUIRING_THREAD_NAME_INFIX);

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

    /**
     * Both locks fail critically, so whichever of them <code>stop()</code> releases first, the other is released after a
     * critical error.
     */
    @Test
    @SuppressWarnings("PMD.CloseResource") // Releasing the locks is the job of node.stop(), the call under test: it releases each lock before its lockReleased callback throws
    void lock_released_callbacks_failing_with_a_critical_error_while_stopping_are_thrown_from_stop_once_every_lock_is_released_and_the_lock_manager_stopped() {
        var otherLockName      = LockName.of("acquired-callback-error-other-lock");
        var lock               = node.tryAcquireLock(LOCK_NAME).orElseThrow();
        var otherLock          = node.tryAcquireLock(otherLockName).orElseThrow();
        var criticalError      = new ExceptionInInitializerError("lockReleased fails critically");
        var otherCriticalError = new ExceptionInInitializerError("other lockReleased fails critically");
        lock.registerCallback(lockReleasedFailingWith(criticalError));
        otherLock.registerCallback(lockReleasedFailingWith(otherCriticalError));

        var thrown = catchThrowable(node::stop);

        // The first critical error is thrown, carrying the second
        assertThat(thrown).isIn(criticalError, otherCriticalError);
        assertThat(thrown.getSuppressed()).singleElement()
                                          .isIn(criticalError, otherCriticalError)
                                          .isNotSameAs(thrown);
        // ... once every lock is released, in the DB too, and the lock manager is stopped
        assertThat(lock.isLocked()).isFalse();
        assertThat(otherLock.isLocked()).isFalse();
        assertThat(lock.isLockedByThisLockManagerInstance()).isFalse();
        assertThat(otherLock.isLockedByThisLockManagerInstance()).isFalse();
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
        assertThat(storage.rows.get(otherLockName).lockedBy()).isNull();
        assertThat(node.isStarted()).isFalse();
        assertThat(events).filteredOn(FencedLockEvents.FencedLockManagerStopped.class::isInstance).hasSize(1);
        // ... so it can be started again, and a second stop() has nothing left to do
        assertThatCode(node::stop).doesNotThrowAnyException();
        node.start();
        assertThat(node.tryAcquireLock(LOCK_NAME)).isPresent();
    }

    /**
     * The lock manager is stopped by the time <code>stop()</code> rethrows its critical error, so
     * {@link FencedLockEvents.FencedLockManagerStopped} is published before that - and a subscriber failing on it neither
     * takes the place of the critical error nor gets lost: it is added to the critical error as suppressed.
     */
    @Test
    @SuppressWarnings("PMD.CloseResource") // Releasing the lock is the job of node.stop(), the call under test
    void a_fenced_lock_manager_stopped_subscriber_failing_after_a_critical_error_in_stop_is_added_to_the_critical_error() {
        var lock          = node.tryAcquireLock(LOCK_NAME).orElseThrow();
        var criticalError = new ExceptionInInitializerError("lockReleased fails critically");
        lock.registerCallback(lockReleasedFailingWith(criticalError));
        var subscriberFailure = new IllegalStateException("FencedLockManagerStopped subscriber fails");
        eventSubscriberAction = event -> {
            if (event instanceof FencedLockEvents.FencedLockManagerStopped) {
                throw subscriberFailure;
            }
        };

        var thrown = catchThrowable(node::stop);

        // The critical error is thrown, carrying the subscriber's failure
        assertThat(thrown).isSameAs(criticalError);
        assertThat(thrown.getSuppressed()).containsExactly(subscriberFailure);
        // ... once FencedLockManagerStopped is published, the lock released and the lock manager stopped
        assertThat(events).filteredOn(FencedLockEvents.FencedLockManagerStopped.class::isInstance).hasSize(1);
        assertThat(lock.isLockedByThisLockManagerInstance()).isFalse();
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isNull();
        assertThat(node.isStarted()).isFalse();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // Releasing the locks is the job of node.stop(), the call under test
    void a_release_in_the_db_failing_with_a_critical_error_while_stopping_still_releases_every_lock_locally_and_stops_the_lock_manager() {
        var otherLockName = LockName.of("acquired-callback-error-other-lock");
        var lock          = node.tryAcquireLock(LOCK_NAME).orElseThrow();
        var otherLock     = node.tryAcquireLock(otherLockName).orElseThrow();
        var releasedLocks = new CopyOnWriteArrayList<FencedLock>();
        for (var heldLock : List.of(lock, otherLock)) {
            heldLock.registerCallback(LockCallback.builder()
                                                  .onLockAcquired(_lock -> {
                                                  })
                                                  .onLockReleased(releasedLocks::add)
                                                  .build());
        }
        var releaseError = new NoClassDefFoundError("releaseLockInDB fails critically");
        storage.failNextReleaseWith = releaseError;

        assertThatThrownBy(node::stop).isSameAs(releaseError);

        // The first release failed in the DB, before releaseLock released the lock locally - it is released locally all the
        // same: no lock reports itself held, lockReleased was called and LockReleased published for both
        assertThat(lock.isLockedByThisLockManagerInstance()).isFalse();
        assertThat(otherLock.isLockedByThisLockManagerInstance()).isFalse();
        assertThat(releasedLocks).containsExactlyInAnyOrder(lock, otherLock);
        assertThat(events).filteredOn(LockReleased.class::isInstance)
                          .extracting(event -> ((LockReleased) event).lock)
                          .containsExactlyInAnyOrder(lock, otherLock);
        // ... the release after it was made in the DB too, while the failed one's row expires after the lock timeout
        assertThat(List.of(storage.rows.get(LOCK_NAME).lockedBy() == null, storage.rows.get(otherLockName).lockedBy() == null))
                .containsExactlyInAnyOrder(true, false);
        // ... and the lock manager is stopped
        assertThat(node.isStarted()).isFalse();
    }

    /**
     * Counts the calls of each callback method, and keeps the last lock <code>lockAcquired</code> was called with; once
     * {@link #failReleaseWith} is set, <code>lockReleased</code> throws it after counting the call
     */
    private static final class CountingLockCallback implements LockCallback {
        final    AtomicInteger acquired = new AtomicInteger();
        final    AtomicInteger released = new AtomicInteger();
        volatile FencedLock    lastAcquired;
        volatile Throwable     failReleaseWith;

        @Override
        public void lockAcquired(FencedLock lock) {
            lastAcquired = lock;
            acquired.incrementAndGet();
        }

        @Override
        public void lockReleased(FencedLock lock) {
            released.incrementAndGet();
            var failure = failReleaseWith;
            if (failure != null) {
                Exceptions.sneakyThrow(failure);
            }
        }
    }

    /**
     * The threads of the lock manager's async acquiring executor, named after its instance id
     */
    private static List<Thread> asyncAcquiringThreadsOf(DBFencedLockManager<?, ?> lockManager) {
        var prefix = lockManager.getLockManagerInstanceId() + DBFencedLockManager.ASYNC_LOCK_ACQUIRING_THREAD_NAME_INFIX;
        return Thread.getAllStackTraces().keySet().stream()
                     .filter(thread -> thread.getName().startsWith(prefix))
                     .toList();
    }

    private static ListAppender<ILoggingEvent> captureLockManagerLog() {
        return captureLog(TestDBFencedLockManager.class);
    }

    private static void detachLockManagerLog(ListAppender<ILoggingEvent> appender) {
        detachLog(TestDBFencedLockManager.class, appender);
    }
}
