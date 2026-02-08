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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.waitAtMost;

@Testcontainers
public class EssentialsSchedulerIT_WithExecutor extends AbstractEssentialsSchedulerTest {

    @Container
    PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test-db")
            .withUsername("postgres")
            .withPassword("postgres");

    @Override
    protected PostgreSQLContainer<?> getPostgreSQLContainer() {
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
}
