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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;

import dk.trustworks.essentials.components.foundation.fencedlock.api.DBFencedLockApi;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.DurableQueuesApi;
import dk.trustworks.essentials.components.foundation.messaging.queue.health.DurableQueuesHealthIndicator;
import dk.trustworks.essentials.components.foundation.postgresql.api.PostgresqlQueryStatisticsApi;
import dk.trustworks.essentials.components.foundation.scheduler.api.*;
import dk.trustworks.essentials.components.foundation.ttl.TTLJob;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class StarterAutoConfigurationIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("starter-test-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);
    }

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            DataSourceAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class,
                            EssentialsComponentsConfiguration.class
                    ))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    // The starter itself registers no @TTLJob bean since the queue statistics feature was
                    // removed in 0.60, so verify_api_beans supplies one — otherwise its executor-jobs
                    // assertion would pass vacuously against an empty scheduler.
                    .withBean(TestTtlJob.class)
                    .withInitializer(ctx -> TestPropertyValues.of(
                            "spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.password=" + postgreSQLContainer.getPassword(),
                            "essentials.scheduler.enabled=true"
                    ).applyTo(ctx.getEnvironment())); // needed

    /** A minimal {@code @TTLJob} bean, pointed at the queue table the starter creates anyway. */
    @TTLJob(name = "starter_autoconfiguration_it_ttl",
            tableName = PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME,
            timestampColumn = "added_ts",
            defaultTtlDays = 90)
    static class TestTtlJob {
    }

    @Test
    void verify_api_beans() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DBFencedLockApi.class);
            DBFencedLockApi dbFencedLockApi = ctx.getBean(DBFencedLockApi.class);
            assertThat(dbFencedLockApi.getAllLocks("principal")).isNotNull();

            assertThat(ctx).hasSingleBean(DurableQueuesApi.class);
            DurableQueuesApi durableQueuesApi = ctx.getBean(DurableQueuesApi.class);
            assertThat(durableQueuesApi.getQueueNames("principal")).isNotNull();

            assertThat(ctx).hasSingleBean(PostgresqlQueryStatisticsApi.class);
            PostgresqlQueryStatisticsApi postgresqlQueryStatisticsApi = ctx.getBean(PostgresqlQueryStatisticsApi.class);
            assertThat(postgresqlQueryStatisticsApi.getTopTenSlowestQueries("principal")).isNotNull();

            assertThat(ctx).hasSingleBean(SchedulerApi.class);
            SchedulerApi schedulerApi = ctx.getBean(SchedulerApi.class);
            List<ApiExecutorJob> executorJobs = schedulerApi.getExecutorJobs("principal", 0, 10);
            assertThat(executorJobs)
                    .as("TestTtlJob must have been registered with the scheduler")
                    .isNotEmpty();
        });
    }

    @Test
    void verify_essentials_properties() {
        contextRunner
                .withPropertyValues("essentials.immutable-jackson-module-enabled=true")
                .run(ctx -> {
                    EssentialsComponentsProperties props = ctx.getBean(EssentialsComponentsProperties.class);
                    assertThat(props.isImmutableJacksonModuleEnabled()).isTrue();
                });
    }

    @Test
    void the_dead_letter_health_indicator_is_registered_by_default_and_reports_up() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DurableQueuesHealthIndicator.class);

            var health = ctx.getBean(DurableQueuesHealthIndicator.class).health();
            assertThat(health.getStatus())
                    .as("no threshold is configured by default, so the indicator must never be able to fail a probe")
                    .isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry(DurableQueuesHealthIndicator.DETAIL_DEAD_LETTER_THRESHOLD, 0L)
                                           .containsKey(DurableQueuesHealthIndicator.DETAIL_TOTAL_DEAD_LETTER_MESSAGES);
        });
    }

    @Test
    void the_dead_letter_health_indicator_can_be_turned_off() {
        contextRunner
                .withPropertyValues("management.health.durable-queues.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DurableQueuesHealthIndicator.class));
    }

    @Test
    void the_dead_letter_threshold_is_bound_from_properties() {
        contextRunner
                .withPropertyValues("essentials.durable-queues.health.dead-letter-threshold=25",
                                    "essentials.durable-queues.health.cache-time-to-live=1s")
                .run(ctx -> {
                    var health = ctx.getBean(EssentialsComponentsProperties.class).getDurableQueues().getHealth();
                    assertThat(health.getDeadLetterThreshold()).isEqualTo(25L);
                    assertThat(health.getCacheTimeToLive()).isEqualTo(Duration.ofSeconds(1));

                    assertThat(ctx.getBean(DurableQueuesHealthIndicator.class).health().getDetails())
                            .containsEntry(DurableQueuesHealthIndicator.DETAIL_DEAD_LETTER_THRESHOLD, 25L);
                });
    }
}
