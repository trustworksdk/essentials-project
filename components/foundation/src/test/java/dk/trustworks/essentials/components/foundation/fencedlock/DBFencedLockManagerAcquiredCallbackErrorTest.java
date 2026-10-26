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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lock callback that fails with a non-critical {@link Error} (an {@link AssertionError}, say) must be recovered from
 * like one that fails with a {@link RuntimeException}: a failing <code>lockAcquired</code> releases the lock again - which
 * calls <code>lockReleased</code> - and a later tick of the async acquiring tries to acquire it again (with this test's
 * single lock manager it does, and the callback is retried).
 * <p>
 * Before, such an Error escaped the async acquiring tick: the lock stayed held by this instance, with nothing serving it and
 * no other instance able to take it, and the scheduled acquiring ended for good.
 * Uses the in-memory lock table of {@link DBFencedLockManagerAcquireCommitFailureTest}, with every commit succeeding.
 */
class DBFencedLockManagerAcquiredCallbackErrorTest {
    private static final LockName LOCK_NAME = LockName.of("acquired-callback-error-lock");

    private InMemoryLockStorage     storage;
    private TestDBFencedLockManager node;

    @BeforeEach
    void setup() {
        storage = new InMemoryLockStorage();
        node = new TestDBFencedLockManager(storage,
                                           new StagingUnitOfWorkFactory(storage),
                                           FencedLockManagerSettings.builder()
                                                                    .setLockManagerInstanceId("node")
                                                                    .setLockTimeOut(Duration.ofSeconds(30))
                                                                    .setLockConfirmationInterval(Duration.ofMillis(100))
                                                                    .build());
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
    void a_lock_released_callback_failing_with_an_error_does_not_end_the_async_acquiring() {
        var acquiredCalls = new AtomicInteger();
        var releasedCalls = new AtomicInteger();
        node.acquireLockAsync(LOCK_NAME, LockCallback.builder()
                                                     .onLockAcquired(lock -> {
                                                         if (acquiredCalls.incrementAndGet() == 1) {
                                                             throw new AssertionError("first lockAcquired fails");
                                                         }
                                                     })
                                                     .onLockReleased(lock -> {
                                                         if (releasedCalls.incrementAndGet() == 1) {
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
    }
}
