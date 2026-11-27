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

package dk.trustworks.essentials.components.foundation.scheduler;

import dk.trustworks.essentials.components.foundation.fencedlock.LockCallback;
import dk.trustworks.essentials.components.foundation.scheduler.executor.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.test.fencedlock.ShutdownOverlapFencedLockManager;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.waitAtMost;

/**
 * The shutdown-overlap cases of {@link DefaultEssentialsScheduler}, driven by {@link ShutdownOverlapFencedLockManager}.
 * They live in foundation-test's test tree, apart from <code>EssentialsSchedulerIT_WithExecutor</code> in foundation,
 * because the test double is in foundation-test, which depends on foundation.
 */
@Testcontainers
public class EssentialsSchedulerShutdownOverlapIT {

    @Container
    static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("scheduler-shutdown-overlap");

    private ShutdownOverlapFencedLockManager fencedLockManager;
    private DefaultEssentialsScheduler       essentialsScheduler;

    @BeforeEach
    void setup() {
        var jdbi              = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        fencedLockManager = new ShutdownOverlapFencedLockManager();
        fencedLockManager.start();
        essentialsScheduler = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager, 2);
        essentialsScheduler.start();
    }

    @AfterEach
    void cleanup() {
        if (essentialsScheduler != null) {
            essentialsScheduler.stop();
            essentialsScheduler = null;
        }
        if (fencedLockManager != null) {
            fencedLockManager.stop();
            fencedLockManager = null;
        }
    }

    /**
     * During a shutdown the lock manager can call <code>lockReleased</code> before the async acquiring tick's
     * <code>lockAcquired</code> has run (see {@link LockCallback}). A scheduler stopped in that window must not schedule its
     * jobs when the late <code>lockAcquired</code> arrives: nothing would unschedule them, and pg_cron jobs would stay in the
     * database after the JVM is gone.
     */
    @Test
    public void a_lockAcquired_arriving_after_stop_released_the_lock_schedules_nothing() {
        var runs = new AtomicInteger();
        essentialsScheduler.scheduleExecutorJob(new ExecutorJob("countRuns", new FixedDelay(0, 50, TimeUnit.MILLISECONDS), runs::incrementAndGet));

        // The shutdown release runs lockReleased from the scheduler's stop, and only then does the tick's lockAcquired arrive
        essentialsScheduler.stop();
        assertThat(fencedLockManager.wasCancelled(essentialsScheduler.getLockName())).isTrue();
        fencedLockManager.deliverLateLockAcquired(essentialsScheduler.getLockName());

        assertThat(essentialsScheduler.getTotalExecutorJobEntries()).isZero();
        org.awaitility.Awaitility.await()
                                 .during(Duration.ofMillis(300))
                                 .atMost(Duration.ofSeconds(2))
                                 .untilAsserted(() -> assertThat(runs.get()).isZero());
    }

    /**
     * The control for {@link #a_lockAcquired_arriving_after_stop_released_the_lock_schedules_nothing()}: with no overlap the
     * same test double's <code>lockAcquired</code> schedules the job
     */
    @Test
    public void a_lockAcquired_while_started_schedules_the_jobs() {
        var runs = new AtomicInteger();
        essentialsScheduler.scheduleExecutorJob(new ExecutorJob("countRunsWhileStarted", new FixedDelay(0, 50, TimeUnit.MILLISECONDS), runs::incrementAndGet));

        fencedLockManager.deliverLockAcquired(essentialsScheduler.getLockName());

        waitAtMost(Duration.ofSeconds(5)).until(() -> runs.get() > 0);
        assertThat(essentialsScheduler.getTotalExecutorJobEntries()).isEqualTo(1);
    }
}
