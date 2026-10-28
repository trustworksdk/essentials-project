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
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blocking acquires - {@link DBFencedLockManager#acquireLock(LockName)} and
 * {@link DBFencedLockManager#tryAcquireLock(LockName, Duration)} - against a lock another instance holds: each retry must ask
 * the DB again, so a lock released while the caller waits is acquired, and a lock that stays held ends the timed wait
 * with an empty result rather than an exception.
 * Uses the in-memory lock table of {@link DBFencedLockManagerAcquireCommitFailureTest}, with every commit succeeding.
 */
class DBFencedLockManagerBlockingAcquireTest {
    private static final LockName LOCK_NAME = LockName.of("blocking-acquire-lock");
    /**
     * Far longer than any wait below, so a lock that is only freed by timing out shows up as a failure
     */
    private static final Duration LOCK_TIME_OUT = Duration.ofSeconds(30);

    private InMemoryLockStorage      storage;
    private TestDBFencedLockManager  node1;
    private TestDBFencedLockManager  node2;
    private ScheduledExecutorService executor;

    @BeforeEach
    void setup() {
        storage = new InMemoryLockStorage();
        node1 = createLockManager("node1");
        node2 = createLockManager("node2");
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void cleanup() {
        executor.shutdownNow();
        node1.stop();
        node2.stop();
    }

    @Test
    void tryAcquireLock_with_a_timeout_acquires_a_lock_released_while_it_waits() {
        var node1Lock = node1.tryAcquireLock(LOCK_NAME).orElseThrow();
        executor.schedule(node1Lock::release, 300, TimeUnit.MILLISECONDS);

        var node2Lock = node2.tryAcquireLock(LOCK_NAME, Duration.ofSeconds(5));

        assertThat(node2Lock).isPresent();
        assertThat(node2Lock.get().getLockedByLockManagerInstanceId()).isEqualTo("node2");
        assertThat(node2Lock.get().getCurrentToken()).isEqualTo(storage.getInitialTokenValue() + 1);
    }

    @Test
    void acquireLock_acquires_a_lock_released_while_it_waits() throws Exception {
        var node1Lock = node1.tryAcquireLock(LOCK_NAME).orElseThrow();
        executor.schedule(node1Lock::release, 300, TimeUnit.MILLISECONDS);

        var node2Lock = CompletableFuture.supplyAsync(() -> node2.acquireLock(LOCK_NAME))
                                         .get(5, TimeUnit.SECONDS);

        assertThat(node2Lock.getLockedByLockManagerInstanceId()).isEqualTo("node2");
        assertThat(node2Lock.getCurrentToken()).isEqualTo(storage.getInitialTokenValue() + 1);
    }

    @Test
    void tryAcquireLock_with_a_timeout_returns_empty_when_the_lock_stays_held() {
        node1.tryAcquireLock(LOCK_NAME).orElseThrow();

        var node2Lock = node2.tryAcquireLock(LOCK_NAME, Duration.ofMillis(500));

        assertThat(node2Lock).isEmpty();
        assertThat(storage.rows.get(LOCK_NAME).lockedBy()).isEqualTo("node1");
    }

    private TestDBFencedLockManager createLockManager(String lockManagerInstanceId) {
        var lockManager = new TestDBFencedLockManager(storage,
                                                      new StagingUnitOfWorkFactory(storage),
                                                      FencedLockManagerSettings.builder()
                                                                               .setLockManagerInstanceId(lockManagerInstanceId)
                                                                               .setLockTimeOut(LOCK_TIME_OUT)
                                                                               .setLockConfirmationInterval(Duration.ofMillis(100))
                                                                               .build());
        lockManager.start();
        return lockManager;
    }
}
