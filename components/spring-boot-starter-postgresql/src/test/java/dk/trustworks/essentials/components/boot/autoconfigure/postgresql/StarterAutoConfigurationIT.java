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
import dk.trustworks.essentials.components.queue.postgresql.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.DurableQueuesApi;
import dk.trustworks.essentials.components.foundation.postgresql.api.PostgresqlQueryStatisticsApi;
import dk.trustworks.essentials.components.foundation.scheduler.api.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

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
                    .withInitializer(ctx -> TestPropertyValues.of(
                            "spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.password=" + postgreSQLContainer.getPassword(),
                            "essentials.durable-queues.enable-queue-statistics=true",
                            "essentials.durable-queues.shared-queue-statistics-table-name=durable_queues_statistics",
                            "essentials.scheduler.enabled=true"
                    ).applyTo(ctx.getEnvironment())); // needed

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
            // isNotNull, not isNotEmpty: this used to be non-empty only incidentally. The single job in this
            // context was the durable-statistics TTL job, registered from the @TTLJob annotation on
            // PostgresqlDurableQueuesStatistics - and delivery statistics are now collected in memory, so there is
            // no statistics table to prune and no TTL job to register. Asserting isEmpty() instead would over-fit
            // in the other direction: any future @TTLJob bean in the starter would break it.
            assertThat(executorJobs).isNotNull();
        });
    }

    /**
     * The two-table split is reachable from configuration, and off unless asked for. Both halves matter: the flag
     * is the only supported way to get the split under Spring, and defaulting it on would silently strand any
     * backlog sitting in the shared table.
     */
    @Test
    void the_split_queue_tables_flag_selects_the_split_implementation() {
        contextRunner.run(ctx ->
            assertThat(ctx.getBean(DurableQueues.class))
                    .as("off by default")
                    .isInstanceOf(PostgresqlDurableQueues.class));

        contextRunner.withPropertyValues("essentials.durable-queues.use-split-queue-tables=true")
                     .run(ctx -> assertThat(ctx.getBean(DurableQueues.class))
                             .isInstanceOf(PostgresqlSplitDurableQueues.class));
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
}
