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

package dk.trustworks.essentials.components.foundation.ttl;

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.postgresql.ttl.PostgresqlTTLManager;
import dk.trustworks.essentials.components.foundation.scheduler.DefaultEssentialsScheduler;
import dk.trustworks.essentials.components.foundation.scheduler.executor.FixedDelay;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.waitAtMost;

@Testcontainers
public class PostgresqlTTLManagerIT_WithExecutor extends AbstractTTLManagerTest {

    @Container
    PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test-db")
            .withUsername("postgres")
            .withPassword("postgres");

    protected JdbiUnitOfWorkFactory      unitOfWorkFactory;
    protected FencedLockManager          fencedLockManager;
    protected DefaultEssentialsScheduler scheduler;
    protected PostgresqlTTLManager       ttlManager;

    @Test
    public void schedule_ttl_scheduled_job() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        fencedLockManager = new TestFencedLockManager(jdbi);
        scheduler = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager, 1);
        scheduler.start();
        ttlManager = new PostgresqlTTLManager(scheduler, unitOfWorkFactory);
        ttlManager.start();

        setupTestData(unitOfWorkFactory);
        int rowsInTable = getNumberOfRowsInTable(unitOfWorkFactory);
        assertThat(rowsInTable).isEqualTo(5);

        String deleteStatement = "DELETE FROM " + TEST_TABLE_NAME + " WHERE expiry_ts < now()";
        var ttlJobDefinition = new TTLJobDefinitionBuilder()
                .withAction(new DefaultTTLJobAction("test", TEST_TABLE_NAME, "expiry_ts < now()", deleteStatement))
                .withSchedule(new FixedDelayScheduleConfiguration(new FixedDelay(0, 1000, TimeUnit.MILLISECONDS)))
                .build();
        ttlManager.scheduleTTLJob(ttlJobDefinition);
        waitAtMost(Duration.ofSeconds(10)).until(() -> {
            int rows = getNumberOfRowsInTable(unitOfWorkFactory);
            return rows == 3 || rows == 2; // Timing in regards to the expire of the test data database rows
        });

        scheduler.stop();
        ttlManager.stop();
    }

    public PostgreSQLContainer<?> getPostgreSQLContainer() {
        return postgreSQLContainer;
    }
}
