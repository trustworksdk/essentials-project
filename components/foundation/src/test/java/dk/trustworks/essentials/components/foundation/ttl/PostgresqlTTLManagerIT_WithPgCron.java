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

package dk.trustworks.essentials.components.foundation.ttl;

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.postgresql.ttl.PostgresqlTTLManager;
import dk.trustworks.essentials.components.foundation.scheduler.DefaultEssentialsScheduler;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.CronExpression;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.waitAtMost;

/**
 * When running it locally set env variable PGCRON_IMAGE=lcramontw/postgres-with-pg-cron:latest
 */
@Testcontainers
public class PostgresqlTTLManagerIT_WithPgCron extends AbstractTTLManagerTest {

    private static final String          IMAGE_PROP  = System.getenv().getOrDefault("PGCRON_IMAGE", "essentials-postgres-with-pgcron:latest");
    protected static     DockerImageName pgCronImage = DockerImageName.parse(IMAGE_PROP).asCompatibleSubstituteFor("postgres");

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(pgCronImage)
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_cron", "-c", "cron.database_name=test-db")
            .withDatabaseName("test-db")
            .withUsername("postgres")
            .withPassword("postgres");

    protected JdbiUnitOfWorkFactory      unitOfWorkFactory;
    protected FencedLockManager          fencedLockManager;
    protected DefaultEssentialsScheduler scheduler;
    protected PostgresqlTTLManager       ttlManager;

    @Test
    public void schedule_ttl_pgcron_job() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        fencedLockManager = new TestFencedLockManager(jdbi);
        scheduler = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager, 1);
        scheduler.start();
        ttlManager = new PostgresqlTTLManager(scheduler, unitOfWorkFactory);
        ttlManager.start();

        setupTestData(unitOfWorkFactory);
        int rowsInTable = getNumberOfRowsInTable(unitOfWorkFactory);
        assertThat(rowsInTable).isEqualTo(5);

        var cronExpression  = CronExpression.TEN_SECOND;
        var deleteStatement = "DELETE FROM " + TEST_TABLE_NAME + " WHERE expiry_ts < now()";
        var ttlJobDefinition = new TTLJobDefinitionBuilder()
                .withAction(new DefaultTTLJobAction("test", TEST_TABLE_NAME, "WHERE expiry_ts < now()", deleteStatement))
                .withSchedule(new CronScheduleConfiguration(cronExpression, Optional.empty()))
                .build();
        ttlManager.scheduleTTLJob(ttlJobDefinition);
        rowsInTable = getNumberOfRowsInTable(unitOfWorkFactory);
        assertThat(rowsInTable).isEqualTo(5);
        waitAtMost(Duration.ofSeconds(30)).until(() -> {
            int rows = getNumberOfRowsInTable(unitOfWorkFactory);
            return rows == 2 || rows == 3; // Timing in regards to the expire of the test data database rows
        });

        scheduler.stop();
        ttlManager.stop();
    }

    public PostgreSQLContainer<?> getPostgreSQLContainer() {
        return postgreSQLContainer;
    }

}
