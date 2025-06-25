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

package dk.trustworks.essentials.components.foundation.scheduler;

import dk.trustworks.essentials.components.foundation.scheduler.pgcron.*;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.PgCronRepository.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.shared.network.Network;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * When running it locally set env variable PGCRON_IMAGE=lcramontw/postgres-with-pg-cron:latest
 */
@Testcontainers
public class PgCronRepositoryIT {

    private static final String          IMAGE_PROP  = System.getenv().getOrDefault("PGCRON_IMAGE", "essentials-postgres-with-pgcron:latest");
    protected static     DockerImageName pgCronImage = DockerImageName.parse(IMAGE_PROP).asCompatibleSubstituteFor("postgres");

    @Container
    private static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(pgCronImage)
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_cron", "-c", "cron.database_name=test-db")
            .withDatabaseName("test-db")
            .withUsername("postgres")
            .withPassword("postgres")
            .withInitScript("test-containers-init.sql")
            .withReuse(true);

    private static Jdbi                  jdbi;
    private        JdbiUnitOfWorkFactory unitOfWorkFactory;
    private        PgCronRepository      repository;

    @BeforeAll
    static void setUp() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
    }

    @BeforeEach
    void beforeEach() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);
        repository = new PgCronRepository(unitOfWorkFactory);
        List<PgCronEntry> entries = repository.fetchPgCronEntries(0, 10);
        entries.forEach(e -> repository.unschedule(e.jobId()));
    }

    @Test
    void verify_schedule_and_does_job_exist() {
        var job   = new PgCronJob("test", "test_fn", null, CronExpression.of("0 0 * * *"));
        var jobId = repository.schedule(job);
        assertThat(jobId).isNotNull();

        var existingId = repository.doesJobExist(job.name());
        assertThat(jobId).isEqualTo(existingId);

        var secondId = repository.schedule(job);
        assertThat(jobId).isEqualTo(secondId);
    }

    @Test
    void verify_fetch_and_count_entries() {
        var job   = new PgCronJob("test", "test_fn", null, CronExpression.of("0 1 * * *"));
        var jobId = repository.schedule(job);

        var total = repository.getTotalPgCronEntries();
        assertThat(total >= 1).isTrue();

        List<PgCronEntry> entries = repository.fetchPgCronEntries(0, 10);
        assertThat(entries.isEmpty()).isFalse();

        var entry = entries.stream()
                           .filter(e -> e.jobId().equals(jobId))
                           .findFirst()
                           .orElse(null);
        assertThat(entry).isNotNull();
        assertThat(job.cronExpression().toString()).isEqualTo(entry.schedule());
        assertThat("SELECT test_fn();").isEqualTo(entry.command());
    }

    @Test
    void verify_unschedule() {
        var job   = new PgCronJob("test", "test_fn", null, CronExpression.of("0 2 * * *"));
        var jobId = repository.schedule(job);
        repository.unschedule(jobId);

        assertThat(repository.doesJobExist(job.name())).isNull();
    }

    @Test
    void verify_job_run_details() {
        var job   = new PgCronJob("test", "test_fn", null, CronExpression.of("0 3 * * *"));
        var jobId = repository.schedule(job);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("INSERT INTO cron.job_run_details(jobid, runid, job_pid, database, username, command, status, return_message, start_time, end_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                 jobId, 1, 1234, postgreSQLContainer.getDatabaseName(), postgreSQLContainer.getUsername(),
                                 "SELECT test_fn();", "success", "ok", OffsetDateTime.now(), OffsetDateTime.now());
        });

        var detailsCount = repository.getTotalPgCronJobDetails(jobId);
        assertThat(1).isEqualTo(detailsCount);

        List<PgCronJobRunDetails> details = repository.fetchPgCronJobDetails(jobId, 0, 10);
        assertThat(1).isEqualTo(details.size());

        var detail = details.get(0);
        assertThat(jobId).isEqualTo(detail.jobId());
        assertThat("success").isEqualTo(detail.status());
    }

    @Test
    void verify_invalid_function_name_throws() {
        var job = new PgCronJob("test", "invalid-fn(DROP TABLE);", null, CronExpression.of("0 4 * * *"));
        assertThatThrownBy(() -> repository.schedule(job)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_delete_job_by_name_ending_with_instance_id() {
        var instanceId = Network.hostName();
        var job        = new PgCronJob("test", "test_fn", null, CronExpression.of("0 2 * * *"));
        repository.schedule(job);

        repository.deleteJobByNameEndingWithInstanceId(instanceId);

        assertThat(repository.getTotalPgCronEntries()).isZero();
    }

}
