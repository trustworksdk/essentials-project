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
package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned;

import dk.trustworks.essentials.components.foundation.schema.SchemaValidationException;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedSchemaContributor;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the starter's start-up initialisation follows {@code essentials.schema.mode}.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedSchemaModeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private DriverManagerDataSource dataSource;

    @BeforeEach
    void emptyDatabase() throws SQLException {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        }
    }

    private ApplicationContextRunner runner() {
        return runnerWithoutQueues().withPropertyValues("essentials.shard-owned-queue.queues.orders=2");
    }

    private ApplicationContextRunner runnerWithoutQueues() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                         ShardOwnedQueueAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl(),
                                    "essentials.shard-owned-queue.enabled=true",
                                    "spring.datasource.username=" + postgres.getUsername(),
                                    "spring.datasource.password=" + postgres.getPassword());
    }

    @Test
    void create_mode_creates_the_schema_and_registers_the_queues_as_before() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ShardOwnedSchemaContributor.class);
            assertThat(ShardOwnedSchema.resolve(dataSource, dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of("orders"))).isPresent();
        });
    }

    @Test
    void validate_mode_refuses_to_start_without_the_engines_schema() {
        runner().withPropertyValues("essentials.schema.mode=validate")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootCause(ctx.getStartupFailure())).isInstanceOf(SchemaValidationException.class)
                                                                  .hasMessageContaining("shard_queue_registry");
                });
    }

    @Test
    void validate_mode_registers_the_queues_against_a_provisioned_schema_creating_their_sequences_directly() throws Exception {
        ShardOwnedSchema.initialize(dataSource);

        runner().withPropertyValues("essentials.schema.mode=validate")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var queue = ShardOwnedSchema.resolve(dataSource, dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of("orders")).orElseThrow();
                    assertThat(sequenceExists(ShardOwnedSchema.sequenceName(queue.queueId(), 1))).isTrue();
                });
    }

    @Test
    void emit_mode_touches_nothing_and_registers_nothing() throws Exception {
        runner().withPropertyValues("essentials.schema.mode=emit")
                .run(ctx -> assertThat(ctx).hasNotFailed());

        assertThat(sequenceExists(ShardOwnedSchema.QUEUE_ID_SEQUENCE)).as("not even the fixed schema").isFalse();
    }

    @Test
    void no_contribution_when_a_migration_tool_owns_the_schema() {
        runnerWithoutQueues().withPropertyValues("essentials.shard-owned-queue.initialize-schema=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ShardOwnedSchemaContributor.class));
    }

    private boolean sequenceExists(String name) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, name);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        var cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
