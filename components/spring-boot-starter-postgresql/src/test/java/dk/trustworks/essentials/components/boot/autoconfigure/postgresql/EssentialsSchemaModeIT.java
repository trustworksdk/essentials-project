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

import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.schema.SchemaValidationException;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code essentials.schema.mode} end to end, through the starter's own beans.
 */
@Testcontainers
class EssentialsSchemaModeIT {
    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("schema-mode-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                             DataSourceTransactionManagerAutoConfiguration.class,
                                                             EssentialsComponentsConfiguration.class,
                                                             EssentialsSchemaConfiguration.class))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withPropertyValues("spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                                        "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                                        "spring.datasource.password=" + postgreSQLContainer.getPassword(),
                                        "essentials.scheduler.enabled=true");

    @BeforeEach
    void emptyDatabase() throws SQLException {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
    }

    @Test
    void create_mode_is_the_default_and_every_component_creates_and_records_its_schema() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(exists("durable_queues")).isTrue();
            assertThat(exists("fenced_locks")).isTrue();
            assertThat(ledgerModules()).contains("postgresql-queue", "postgresql-fenced-lock", "foundation-scheduler", "foundation-ttl");
        });
    }

    @Test
    void validate_mode_refuses_to_start_against_a_database_without_the_schema_and_creates_nothing() throws SQLException {
        contextRunner.withPropertyValues("essentials.schema.mode=validate")
                     .run(ctx -> {
                         assertThat(ctx).hasFailed();
                         assertThat(rootCause(ctx.getStartupFailure())).isInstanceOf(SchemaValidationException.class)
                                                                       .hasMessageContaining("'postgresql-queue' change");
                     });
        assertThat(exists("durable_queues")).isFalse();
        assertThat(exists("fenced_locks")).isFalse();
    }

    @Test
    void emit_mode_writes_the_script_starts_no_lifecycle_and_the_script_makes_validate_pass() throws Exception {
        var scriptFile = tempDir.resolve("essentials-schema.sql");
        contextRunner.withPropertyValues("essentials.schema.mode=emit",
                                         "essentials.schema.emit.exit=false",
                                         "essentials.schema.emit.script-file=" + scriptFile)
                     .run(ctx -> {
                         assertThat(ctx).hasNotFailed();
                         assertThat(ctx.getBean(FencedLockManager.class).isStarted()).as("no lifecycle is started in the emit mode").isFalse();
                     });
        assertThat(exists("durable_queues")).as("emit executes nothing").isFalse();
        var script = Files.readString(scriptFile);
        assertThat(script).contains("-- Module postgresql-queue").contains("-- Module postgresql-fenced-lock").contains("-- Module foundation-scheduler");

        execute(script);

        contextRunner.withPropertyValues("essentials.schema.mode=validate")
                     .run(ctx -> {
                         assertThat(ctx).hasNotFailed();
                         assertThat(ctx.getBean(FencedLockManager.class).isStarted()).as("started by the lifecycle manager after validation").isTrue();
                     });
    }

    @Test
    void external_mode_executes_and_verifies_nothing() throws SQLException {
        contextRunner.withPropertyValues("essentials.schema.mode=external",
                                         "essentials.life-cycles.start-life-cycles=false")
                     .run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(exists("durable_queues")).isFalse();
        assertThat(exists("essentials_schema_history")).isFalse();
    }

    private static Throwable rootCause(Throwable throwable) {
        var cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
    }

    private static void execute(String sql) throws SQLException {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean exists(String relation) throws SQLException {
        try (var connection = connection(); var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, relation);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private static Set<String> ledgerModules() throws SQLException {
        var modules = new TreeSet<String>();
        try (var connection = connection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT DISTINCT module_id FROM essentials_schema_history")) {
            while (resultSet.next()) {
                modules.add(resultSet.getString(1));
            }
        }
        return modules;
    }
}
