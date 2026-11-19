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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.ViewProjection;
import dk.trustworks.essentials.components.foundation.schema.SchemaValidationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the starter follows {@code essentials.schema.mode}, beside the old event-store starter as in
 * {@link EventLogAutoConfigurationIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class EventLogSchemaModeIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm");

    private DriverManagerDataSource dataSource;

    @BeforeEach
    void emptyDatabase() throws SQLException {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        }
    }

    private ApplicationContextRunner runner() {
        return runnerWithoutProjection().withUserConfiguration(ProjectionConfiguration.class);
    }

    /** For the modes where the engine's tables are absent at build time: nothing registers an aggregate type then */
    private ApplicationContextRunner runnerWithoutProjection() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Application.class)
                .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl(),
                                    "spring.datasource.username=" + postgres.getUsername(),
                                    "spring.datasource.password=" + postgres.getPassword(),
                                    "essentials.event-log.enabled=true");
    }

    @Test
    void create_mode_creates_the_schema_and_starts_the_processors() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(EventLogSchemaContributor.class);
            assertThat(tableExists(EventLogSchema.PROGRESS_TABLE)).isTrue();
            assertThat(ctx.getBean("ordersView", ViewProjection.class).isRunning()).isTrue();
        });
    }

    @Test
    void validate_mode_refuses_to_start_without_the_engines_schema() {
        runnerWithoutProjection().withPropertyValues("essentials.schema.mode=validate")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootCause(ctx.getStartupFailure())).isInstanceOf(SchemaValidationException.class);
                });
    }

    @Test
    void validate_mode_starts_against_a_schema_created_before() {
        runner().run(ctx -> assertThat(ctx).hasNotFailed());

        runner().withPropertyValues("essentials.schema.mode=validate")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(EventLog.class);
                    assertThat(ctx.getBean("ordersView", ViewProjection.class).isRunning()).isTrue();
                });
    }

    @Test
    void emit_mode_creates_nothing_and_starts_no_processor(@TempDir Path scriptDirectory) throws Exception {
        runnerWithoutProjection().withUserConfiguration(UnregisteredProjectionConfiguration.class)
                                 .withPropertyValues("essentials.schema.mode=emit",
                                                     "essentials.schema.emit.exit=false",
                                                     "essentials.schema.emit.script-file=" + scriptDirectory.resolve("schema.sql"))
                                 .run(ctx -> {
                                     assertThat(ctx).hasNotFailed();
                                     // Started, it would fail: its aggregate type is not registered
                                     assertThat(ctx.getBean("ordersView", ViewProjection.class).isRunning()).isFalse();
                                 });

        assertThat(tableExists(EventLogSchema.PROGRESS_TABLE)).isFalse();
    }

    /** Finding 1 of unit 10-A: a type registered while the context is built must not reach the database before the schema is validated */
    @Test
    void validate_mode_with_an_aggregate_type_registered_during_the_build_fails_on_the_schema_not_the_database() {
        runner().withPropertyValues("essentials.schema.mode=validate")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootCause(ctx.getStartupFailure())).isInstanceOf(SchemaValidationException.class);
                });
    }

    /** Finding 1 of unit 10-A: in emit an aggregate type registered during the build goes into the script, not onto the database */
    @Test
    void emit_mode_writes_an_aggregate_type_registered_during_the_build_to_the_script_only(@TempDir Path scriptDirectory) throws Exception {
        var script = scriptDirectory.resolve("schema.sql");
        runner().withPropertyValues("essentials.schema.mode=emit",
                                    "essentials.schema.emit.exit=false",
                                    "essentials.schema.emit.script-file=" + script)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean("ordersView", ViewProjection.class).isRunning()).isFalse();
                });

        var eventTable = AggregateTypeTables.of(TestOrder.ORDERS.toString()).eventTable();
        assertThat(tableExists(EventLogSchema.PROGRESS_TABLE)).isFalse();
        assertThat(tableExists(eventTable)).isFalse();
        assertThat(java.nio.file.Files.readString(script)).contains(eventTable);
    }

    @Test
    void no_contribution_when_a_migration_tool_owns_the_schema() {
        runner().withPropertyValues("essentials.event-log.initialize-schema=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(EventLogSchemaContributor.class);
                });
    }

    private boolean tableExists(String name) throws SQLException {
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

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
    }

    /**
     * The aggregate type is registered while the context is built, as a repository bean would. That reaches the engine's
     * tables before the schema harness runs, so it is used only where they exist by then: {@code create} (the engine
     * creates them as it starts) and {@code validate} after a {@code create}.
     */
    @Configuration(proxyBeanMethods = false)
    static class ProjectionConfiguration {
        @Bean
        ViewProjection ordersView(EventLog eventLog, JSONEventSerializer serializer, PageTransactionSource transactions) throws SQLException {
            eventLog.registerAggregateType(TestOrder.ORDERS.toString());
            return projection(eventLog, serializer, transactions);
        }
    }

    /** For {@code emit}: nothing touches the database while the context is built, and the projection is never started */
    @Configuration(proxyBeanMethods = false)
    static class UnregisteredProjectionConfiguration {
        @Bean
        ViewProjection ordersView(EventLog eventLog, JSONEventSerializer serializer, PageTransactionSource transactions) {
            return projection(eventLog, serializer, transactions);
        }
    }

    private static ViewProjection projection(EventLog eventLog, JSONEventSerializer serializer, PageTransactionSource transactions) {
        return ViewProjection.builder("orders-view", TestOrder.ORDERS.toString())
                             .on(TestOrder.OrderPlaced.class, (event, context) -> { })
                             .transactions(transactions)
                             .build(eventLog, serializer);
    }
}
