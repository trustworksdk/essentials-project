package dk.trustworks.essentials.components.foundation.scheduler;

import dk.trustworks.essentials.components.foundation.scheduler.executor.*;
import dk.trustworks.essentials.components.foundation.scheduler.executor.ExecutorScheduledJobRepository.ExecutorJobEntry;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.shared.network.Network;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class ExecutorScheduledJobRepositoryIT {

    @Container
    private static final PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    private static Jdbi                           jdbi;
    private static ExecutorScheduledJobRepository repository;

    @BeforeAll
    static void setUp() {
        jdbi = Jdbi.create(postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword());

        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(jdbi);

        repository = new ExecutorScheduledJobRepository(unitOfWorkFactory);
    }

    @Test
    void verify_insert_and_exists_by_name() {
        var job = new ExecutorJob(
                "job1",
                new FixedDelay(100L, 500L, TimeUnit.SECONDS),
                () -> {}
        );

        repository.insert(job);
        assertThat(repository.existsByName(job.name())).isTrue();
    }

    @Test
    void verify_delete_by_name_and_exists() {
        var job = new ExecutorJob(
                "job-delete",
                new FixedDelay(1L, 2L, TimeUnit.MINUTES),
                () -> {}
        );
        repository.insert(job);
        assertThat(repository.existsByName(job.name())).isTrue();

        var deleted = repository.deleteByName(job.name());
        assertThat(deleted).isTrue();
        assertThat(repository.existsByName(job.name())).isFalse();
    }

    @Test
    void verify_fetch_entries_and_count() throws InterruptedException {
        // ensure empty
        repository.deleteAll();

        var jobA = new ExecutorJob(
                "A",
                new FixedDelay(10L, 20L, TimeUnit.HOURS),
                () -> {}
        );
        var jobB = new ExecutorJob(
                "B",
                new FixedDelay(30L, 40L, TimeUnit.MINUTES),
                () -> {}
        );
        var jobC = new ExecutorJob(
                "C",
                new FixedDelay(50L, 60L, TimeUnit.DAYS),
                () -> {}
        );

        repository.insert(jobA);
        Thread.sleep(5);
        repository.insert(jobB);
        Thread.sleep(5);
        repository.insert(jobC);

        var total = repository.getTotalExecutorJobEntries();
        assertThat(total).isEqualTo(3);

        var instanceId = Network.hostName();

        var ascList = repository.fetchExecutorJobEntries(10, 0, true);
        assertThat(ascList).hasSize(3);
        assertThat(ascList.stream().map(ExecutorJobEntry::name))
                .containsExactly("A_" + instanceId, "B_" + instanceId, "C_" + instanceId);

        var descList = repository.fetchExecutorJobEntries(10, 0, false);
        assertThat(descList).hasSize(3);
        assertThat(descList.stream().map(ExecutorJobEntry::name))
                .containsExactly("C_" + instanceId, "B_" + instanceId, "A_" + instanceId);
    }

    @Test
    void verify_delete_all() {
        var job = new ExecutorJob(
                "temp",
                new FixedDelay(5L, 5L, TimeUnit.SECONDS),
                () -> {}
        );
        repository.insert(job);
        assertThat(repository.getTotalExecutorJobEntries()).isGreaterThan(0);

        repository.deleteAll();
        assertThat(repository.getTotalExecutorJobEntries()).isZero();
    }

    @Test
    void verify_delete_job_by_name_ending_with_instance_id() {
        var instanceId = Network.hostName();
        var job = new ExecutorJob(
                "temp",
                new FixedDelay(5L, 5L, TimeUnit.SECONDS),
                () -> {}
        );
        repository.insert(job);

        repository.deleteByNameEndingWithInstanceId(instanceId);

        assertThat(repository.getTotalExecutorJobEntries()).isZero();
    }

}
