/*
 * Copyright 2021-2025 the original author or authors.
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

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class TestFencedLockManagerIT {

    @Container
    private static final PostgreSQLContainer<?> postgresContainer =
            new PostgreSQLContainer<>("postgres:latest")
                    .withDatabaseName("testdb")
                    .withUsername("user")
                    .withPassword("password");

    private Jdbi                  jdbi;
    private TestFencedLockManager fencedLockManager;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword()
                          );
        fencedLockManager = new TestFencedLockManager(jdbi);
        fencedLockManager.start();
    }

    @AfterEach
    void tearDown() {
        fencedLockManager.stop();
    }

    @Test
    void testAcquireAndReleaseLock() {
        var                  lockName = LockName.of("testLock");
        Optional<FencedLock> lockOpt  = fencedLockManager.tryAcquireLock(lockName);
        assertThat(lockOpt.isPresent()).isTrue();
        FencedLock lock = lockOpt.get();
        assertThat((CharSequence) lockName).isEqualTo(lock.getName());
        assertThat(fencedLockManager.isLockAcquired(lockName)).isTrue();
        assertThat(fencedLockManager.isLockedByThisLockManagerInstance(lockName)).isTrue();
        // No other instance holds it
        assertThat(fencedLockManager.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        // lookup should return the lock
        assertThat(fencedLockManager.lookupLock(lockName).isPresent()).isTrue();
        // After stop(), lock should be released
        fencedLockManager.stop();
        assertThat(fencedLockManager.isLockAcquired(lockName)).isFalse();
        assertThat(fencedLockManager.lookupLock(lockName)).isEmpty();
    }

    @Test
    void testAcquireLockWithTimeoutAndLookup() {
        var lockName = LockName.of("timeoutLock");
        // Blocking acquire with timeout
        var lock = fencedLockManager.acquireLock(lockName);
        assertThat(lock).isNotNull();
        assertThat((CharSequence) lockName).isEqualTo(lock.getName());
        // lookup should see it
        assertThat(fencedLockManager.lookupLock(lockName)).isPresent();
    }

    @Test
    void testLockedByAnotherInstance() {
        LockName lockName = LockName.of("sharedLock");
        // Create a second manager instance
        TestFencedLockManager otherManager = new TestFencedLockManager(jdbi);
        otherManager.start();
        // First manager acquires the lock
        FencedLock lock1 = fencedLockManager.acquireLock(lockName);
        assertThat(fencedLockManager.isLockAcquired(lockName)).isTrue();
        // Other manager should see it's held by another
        assertThat(otherManager.isLockAcquiredByAnotherLockManagerInstance(lockName)).isTrue();
        // Release via first manager
        fencedLockManager.stop();
        assertThat(otherManager.isLockAcquiredByAnotherLockManagerInstance(lockName)).isFalse();
        otherManager.stop();
    }

    @Test
    void testAsyncAcquireSuccess() throws InterruptedException {
        LockName       lockName = LockName.of("asyncLockSuccess");
        CountDownLatch latch    = new CountDownLatch(1);
        // Acquire asynchronously on free lock
        fencedLockManager.acquireLockAsync(lockName, new LockCallback() {
            @Override
            public void lockAcquired(FencedLock lock) {
                latch.countDown();
            }

            @Override
            public void lockReleased(FencedLock lock) {

            }

        });
        // Wait for callback
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fencedLockManager.isLockAcquired(lockName)).isTrue();
    }

    @Test
    void testAsyncAcquireAndCancel() throws InterruptedException {
        LockName lockName = LockName.of("asyncLockCancel");
        // First, occupy the lock with manager
        FencedLock lock1 = fencedLockManager.acquireLock(lockName);
        // Second manager to test async cancel
        TestFencedLockManager otherManager = new TestFencedLockManager(jdbi);
        otherManager.start();
        CountDownLatch latch = new CountDownLatch(1);
        otherManager.acquireLockAsync(lockName, new LockCallback() {
            @Override
            public void lockAcquired(FencedLock lock) {
                latch.countDown();
            }

            @Override
            public void lockReleased(FencedLock lock) {
            }
        });
        // Allow some time for attempt
        Thread.sleep(100);
        // Cancel async acquisition
        otherManager.cancelAsyncLockAcquiring(lockName);
        // Ensure callback was not called
        assertThat(latch.await(200, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(otherManager.isLockAcquired(lockName)).isFalse();
        // Cleanup
        otherManager.stop();
    }
}
