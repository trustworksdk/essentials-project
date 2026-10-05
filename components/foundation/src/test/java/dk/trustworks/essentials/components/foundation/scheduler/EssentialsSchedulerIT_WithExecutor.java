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

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.scheduler.executor.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.waitAtMost;

@Testcontainers
public class EssentialsSchedulerIT_WithExecutor extends AbstractEssentialsSchedulerTest {

    @Container
    static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("test-db")
            .withUsername("postgres")
            .withPassword("postgres");

    @Override
    protected PostgreSQLContainer getPostgreSQLContainer() {
        return postgreSQLContainer;
    }

    @Test
    public void schedule_with_1_node() {
        JdbiUnitOfWorkFactory unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        FencedLockManager     fencedLockManager = new TestFencedLockManager(jdbi);
        fencedLockManager.start();
        DefaultEssentialsScheduler essentialsScheduler = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager, 2);
        essentialsScheduler.start();

        assertThat(essentialsScheduler.isPgCronAvailable()).isFalse();
        waitAtMost(Duration.ofSeconds(5)).until(() ->
                                                        fencedLockManager.isLockAcquired(essentialsScheduler.getLockName())

                                               );
        long cronCount = essentialsScheduler.getTotalPgCronEntries();
        long execCount = essentialsScheduler.getTotalExecutorJobEntries();
        assertThat(cronCount).isEqualTo(0);
        assertThat(execCount).isEqualTo(0);

        setupTestData(unitOfWorkFactory);
        int rowsInTable = getNumberOfRowsInTable(unitOfWorkFactory);
        assertThat(rowsInTable).isEqualTo(5);

        ExpireRows  expireRows = new ExpireRows(unitOfWorkFactory);
        FixedDelay  fixedDelay = new FixedDelay(0, 1000, TimeUnit.MILLISECONDS);
        ExecutorJob job        = new ExecutorJob("deleteExpiredRows", fixedDelay, expireRows::deleteExpiredRows);
        essentialsScheduler.scheduleExecutorJob(job);

        waitAtMost(Duration.ofSeconds(10)).until(() -> {
            int rows = getNumberOfRowsInTable(unitOfWorkFactory);
            return rows == 3 || rows == 2; // Timing in regards to the expire of the test data database rows
        });

        cronCount = essentialsScheduler.getTotalPgCronEntries();
        execCount = essentialsScheduler.getTotalExecutorJobEntries();
        assertThat(cronCount).isEqualTo(0);
        assertThat(execCount).isEqualTo(1);

        essentialsScheduler.stop();
        fencedLockManager.stop();

        assertThat(essentialsScheduler.getTotalExecutorJobEntries()).isEqualTo(0);
        waitAtMost(Duration.ofSeconds(5)).until(() -> !fencedLockManager.isLockAcquired(essentialsScheduler.getLockName()));
    }

    @Test
    public void schedule_with_2_nodes() {
        JdbiUnitOfWorkFactory unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);

        FencedLockManager fencedLockManager1 = new TestFencedLockManager(jdbi);
        fencedLockManager1.start();
        FencedLockManager fencedLockManager2 = new TestFencedLockManager(jdbi);
        fencedLockManager2.start();

        DefaultEssentialsScheduler essentialsScheduler1 = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager1, 2);
        DefaultEssentialsScheduler essentialsScheduler2 = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager2, 2);

        essentialsScheduler1.start();
        essentialsScheduler2.start();

        assertThat(essentialsScheduler1.isPgCronAvailable()).isFalse();
        assertThat(essentialsScheduler2.isPgCronAvailable()).isFalse();
        waitAtMost(Duration.ofSeconds(5)).until(() ->
                                                        fencedLockManager1.isLockAcquired(essentialsScheduler1.getLockName()) ^
                                                                fencedLockManager2.isLockAcquired(essentialsScheduler2.getLockName())

                                               );
        long cronCount1 = essentialsScheduler1.getTotalPgCronEntries();
        long execCount1 = essentialsScheduler1.getTotalExecutorJobEntries();
        assertThat(cronCount1).isEqualTo(0);
        assertThat(execCount1).isEqualTo(0);
        long cronCount2 = essentialsScheduler2.getTotalPgCronEntries();
        long execCount2 = essentialsScheduler2.getTotalExecutorJobEntries();
        assertThat(cronCount2).isEqualTo(0);
        assertThat(execCount2).isEqualTo(0);

        setupTestData(unitOfWorkFactory);
        int rowsInTable = getNumberOfRowsInTable(unitOfWorkFactory);
        assertThat(rowsInTable).isEqualTo(5);

        ExpireRows expireRows = new ExpireRows(unitOfWorkFactory);
        FixedDelay fixedDelay = new FixedDelay(0, 1000, TimeUnit.MILLISECONDS);
        essentialsScheduler1.scheduleExecutorJob(new ExecutorJob("deleteExpiredRows", fixedDelay, expireRows::deleteExpiredRows));
        essentialsScheduler2.scheduleExecutorJob(new ExecutorJob("deleteExpiredRows", fixedDelay, expireRows::deleteExpiredRows));

        waitAtMost(Duration.ofSeconds(10)).until(() -> {
            int rows = getNumberOfRowsInTable(unitOfWorkFactory);
            return rows == 3 || rows == 2; // Timing in regards to the expire of the test data database rows
        });

        cronCount1 = essentialsScheduler1.getTotalPgCronEntries();
        execCount1 = essentialsScheduler1.getTotalExecutorJobEntries();
        assertThat(cronCount1).isEqualTo(0);
        assertThat(execCount1).isEqualTo(1);

        fencedLockManager1.stop();
        essentialsScheduler1.stop();
        fencedLockManager2.stop();
        essentialsScheduler2.stop();
    }

    @Test
    public void schedule_with_2_nodes_failover() {
        JdbiUnitOfWorkFactory unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);

        setupTestData(unitOfWorkFactory);
        setupTestFunction(unitOfWorkFactory);
        ExpireRows expireRows = new ExpireRows(unitOfWorkFactory);
        FixedDelay fixedDelay = new FixedDelay(0, 1000, TimeUnit.MILLISECONDS);

        FencedLockManager fencedLockManager1 = new TestFencedLockManager(jdbi);
        fencedLockManager1.start();
        DefaultEssentialsScheduler essentialsScheduler1 =
                new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager1, 2);
        essentialsScheduler1.scheduleExecutorJob(
                new ExecutorJob("deleteExpiredRows", fixedDelay, expireRows::deleteExpiredRows)
                                                );

        FencedLockManager fencedLockManager2 = new TestFencedLockManager(jdbi);
        fencedLockManager2.start();
        DefaultEssentialsScheduler essentialsScheduler2 =
                new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager2, 2);
        essentialsScheduler2.scheduleExecutorJob(
                new ExecutorJob("deleteExpiredRows", fixedDelay, expireRows::deleteExpiredRows)
                                                );

        essentialsScheduler1.start();
        essentialsScheduler2.start();

        waitAtMost(Duration.ofSeconds(5)).until(() ->
                                                        fencedLockManager1.isLockAcquired(essentialsScheduler1.getLockName())
                                               );

        waitAtMost(Duration.ofSeconds(30)).until(() ->
                                                         getNumberOfRowsInTable(unitOfWorkFactory) == 2 || getNumberOfRowsInTable(unitOfWorkFactory) == 3 // Timing in regards to the expire of the test data database rows
                                                );

        long cronCount = essentialsScheduler1.getTotalPgCronEntries();
        long execCount = essentialsScheduler1.getTotalExecutorJobEntries();
        assertThat(cronCount).isEqualTo(0);
        assertThat(execCount).isEqualTo(1);

        essentialsScheduler1.stop();
        fencedLockManager1.stop();

        waitAtMost(Duration.ofSeconds(10)).until(() ->
                                                         fencedLockManager2.isLockAcquired(essentialsScheduler2.getLockName())
                                                );

        waitAtMost(Duration.ofSeconds(30)).until(() ->
                                                         getNumberOfRowsInTable(unitOfWorkFactory) == 1 || getNumberOfRowsInTable(unitOfWorkFactory) == 2 // Timing in regards to the expire of the test data database rows
                                                );

        cronCount = essentialsScheduler2.getTotalPgCronEntries();
        execCount = essentialsScheduler2.getTotalExecutorJobEntries();
        assertThat(cronCount).isEqualTo(0);
        assertThat(execCount).isEqualTo(1);

        essentialsScheduler2.stop();
        fencedLockManager2.stop();
    }

    @Test
    public void an_executor_job_is_run_on_demand_by_the_lock_holder_under_its_registered_or_stored_name() {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        var fencedLockManager = new TestFencedLockManager(jdbi);
        fencedLockManager.start();
        var scheduler = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager, 2);
        scheduler.start();
        waitAtMost(Duration.ofSeconds(5)).until(() -> fencedLockManager.isLockAcquired(scheduler.getLockName()));

        try {
            var runs = new AtomicInteger();
            // Scheduled far enough out that only the on-demand runs count
            scheduler.scheduleExecutorJob(new ExecutorJob("countRuns", new FixedDelay(1, 1, TimeUnit.HOURS), runs::incrementAndGet));
            scheduler.scheduleExecutorJob(new ExecutorJob("alwaysFails", new FixedDelay(1, 1, TimeUnit.HOURS), () -> {
                throw new IllegalStateException("boom");
            }));

            var run = scheduler.runJobNow("countRuns");
            assertThat(run).hasValueSatisfying(r -> {
                assertThat(r.jobName()).isEqualTo("countRuns");
                assertThat(r.jobType()).isEqualTo(ScheduledJobRun.JobType.EXECUTOR);
                assertThat(r.succeeded()).isTrue();
                assertThat(r.error()).isNull();
            });
            // The name as stored in the executor jobs table - and listed by the admin API - carries the instance suffix
            var storedName = scheduler.fetchExecutorJobEntries(0, 10).stream()
                                      .map(ExecutorScheduledJobRepository.ExecutorJobEntry::name)
                                      .filter(name -> name.startsWith("countRuns"))
                                      .findFirst().orElseThrow();
            assertThat(scheduler.runJobNow(storedName)).hasValueSatisfying(r -> assertThat(r.succeeded()).isTrue());
            assertThat(runs).hasValue(2);

            assertThat(scheduler.runJobNow("alwaysFails")).hasValueSatisfying(r -> {
                assertThat(r.succeeded()).isFalse();
                assertThat(r.error()).contains("IllegalStateException").contains("boom");
            });
            assertThat(scheduler.runJobNow("noSuchJob")).isEmpty();
        } finally {
            scheduler.stop();
            fencedLockManager.stop();
        }
    }

    @Test
    public void an_executor_job_is_not_run_on_demand_by_an_instance_that_does_not_hold_the_lock() {
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        var lockManager1      = new TestFencedLockManager(jdbi);
        var lockManager2      = new TestFencedLockManager(jdbi);
        lockManager1.start();
        lockManager2.start();
        var scheduler1 = new DefaultEssentialsScheduler(unitOfWorkFactory, lockManager1, 2);
        var scheduler2 = new DefaultEssentialsScheduler(unitOfWorkFactory, lockManager2, 2);
        scheduler1.start();
        waitAtMost(Duration.ofSeconds(5)).until(() -> lockManager1.isLockAcquired(scheduler1.getLockName()));
        scheduler2.start();

        try {
            var runs = new AtomicInteger();
            scheduler1.scheduleExecutorJob(new ExecutorJob("countRuns", new FixedDelay(1, 1, TimeUnit.HOURS), runs::incrementAndGet));
            scheduler2.scheduleExecutorJob(new ExecutorJob("countRuns", new FixedDelay(1, 1, TimeUnit.HOURS), runs::incrementAndGet));

            // The holder is read with lookupLock. The test lock manager only knows the locks it holds itself, so here
            // it is unknown; PostgresqlFencedLockManager reads it from the lock table
            assertThatThrownBy(() -> scheduler2.runJobNow("countRuns"))
                    .isInstanceOfSatisfying(ScheduledJobNotRunnableHereException.class,
                                            e -> assertThat(e.getLockHolderInstanceId()).isIn(null, lockManager1.getLockManagerInstanceId()))
                    .hasMessageContaining("countRuns");
            assertThat(runs).hasValue(0);
            assertThat(scheduler1.runJobNow("countRuns")).hasValueSatisfying(r -> assertThat(r.succeeded()).isTrue());
            assertThat(runs).hasValue(1);
        } finally {
            scheduler2.stop();
            scheduler1.stop();
            lockManager2.stop();
            lockManager1.stop();
        }
    }
}
