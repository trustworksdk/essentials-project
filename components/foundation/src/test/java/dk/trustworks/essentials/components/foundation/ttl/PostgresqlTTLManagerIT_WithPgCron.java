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
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.CronExpression;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.waitAtMost;

/**
 * When running it locally set env variable PGCRON_IMAGE=lcramontw/postgres-with-pg-cron:latest
 */
@Testcontainers
public class PostgresqlTTLManagerIT_WithPgCron {

    private static final String          IMAGE_PROP  = System.getenv().getOrDefault("PGCRON_IMAGE", "essentials-postgres-with-pgcron:latest");
    protected static     DockerImageName pgCronImage = DockerImageName.parse(IMAGE_PROP).asCompatibleSubstituteFor("postgres");

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(pgCronImage)
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_cron", "-c", "cron.database_name=test-db")
            .withDatabaseName("test-db")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("test-containers-init.sql")
            .withReuse(true);

    protected Jdbi                            jdbi;
    protected HandleAwareUnitOfWorkFactory<?> unitOfWorkFactory;
    protected FencedLockManager               fencedLockManager;
    protected DefaultEssentialsScheduler      scheduler;
    protected PostgresqlTTLManager            ttlManager;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(getPostgreSQLContainer().getJdbcUrl(), getPostgreSQLContainer().getUsername(), getPostgreSQLContainer().getPassword());
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        fencedLockManager = new TestFencedLockManager(jdbi);
        scheduler = new DefaultEssentialsScheduler(unitOfWorkFactory, fencedLockManager, 1);
        scheduler.start();
        ttlManager = new PostgresqlTTLManager(scheduler, unitOfWorkFactory);
        ttlManager.start();
    }

    @AfterEach
    void clean() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (ttlManager != null) {
            ttlManager.stop();
        }
    }

    @Test
    public void schedule_ttl_pgcron_job() {
        var tableName = "pg_cron_test";
        setupTestData(tableName);
        int rowsInTable = getNumberOfRowsInTable(tableName);
        assertThat(rowsInTable).isEqualTo(5);

        var cronExpression  = CronExpression.TEN_SECOND;
        var deleteStatement = "DELETE FROM " + tableName + " WHERE expiry_ts < now()";
        var ttlJobDefinition = new TTLJobDefinitionBuilder()
                .withAction(new DefaultTTLJobAction(tableName, "WHERE expiry_ts < now()", deleteStatement, "test"))
                .withSchedule(new CronScheduleConfiguration(cronExpression, Optional.empty()))
                .build();
        ttlManager.scheduleTTLJob(ttlJobDefinition);
        rowsInTable = getNumberOfRowsInTable(tableName);
        assertThat(rowsInTable).isEqualTo(5);
        waitAtMost(Duration.ofSeconds(30)).until(() -> {
            int rows = getNumberOfRowsInTable(tableName);
            System.out.println("Rows in table: " + rows);
            return rows == 2 || rows == 3;
        });
    }

    private int getNumberOfRowsInTable(String tableName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            return uow.handle().createQuery("SELECT COUNT(*) FROM " + tableName)
                      .mapTo(Integer.class)
                      .one();
        });
    }

    private void setupTestData(String tableName) {
        String sql = bind("""
                              CREATE TABLE IF NOT EXISTS {:tableName} (
                                                         id SERIAL PRIMARY KEY,
                                                         name TEXT NOT NULL,
                                                         created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                                         expiry_ts TIMESTAMPTZ
                                                     );
                                                     -- Insert some test rows:
                                                     -- 1. A row with expiry in the past (expired).
                                                     -- 2. A row with expiry in the very recent past (borderline).
                                                     -- 3. Rows with expiry in the future (active).
                                                     INSERT INTO {:tableName} (name, expiry_ts)
                                                     VALUES
                                                         ('Expired row - 1 hour ago', now() - interval '1 hour'),
                                                         ('Expired row - 10 minutes ago', now() - interval '10 minutes'),
                                                         ('Active row - expires in 1 hour', now() + interval '1 hour'),
                                                         ('Active row - expires in 2 hours', now() + interval '2 hours'),
                                                         ('Borderline expired - 1 second ago', now() - interval '1 second');
                          """, arg("tableName", tableName));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute(sql);
        });
    }

    public PostgreSQLContainer<?> getPostgreSQLContainer() {
        return postgreSQLContainer;
    }

}
