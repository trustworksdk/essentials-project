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
package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamPersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
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

import static org.assertj.core.api.Assertions.*;

/**
 * {@code essentials.schema.mode} across the event store starter: the fixed tables, the snapshot table, and the event
 * stream tables registered per AggregateType at runtime.
 */
@Testcontainers
class EventStoreSchemaModeIT {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("event-store-schema-mode")
            .withUsername("test-user")
            .withPassword("secret-password");

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                             DataSourceTransactionManagerAutoConfiguration.class,
                                                             EssentialsComponentsConfiguration.class,
                                                             EssentialsSchemaConfiguration.class,
                                                             EventStoreConfiguration.class,
                                                             SnapshotConfiguration.class))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withPropertyValues("spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                                        "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                                        "spring.datasource.password=" + postgreSQLContainer.getPassword(),
                                        "essentials.eventstore.cdc.enabled=false",
                                        "essentials.eventstore.snapshots.enabled=true",
                                        "essentials.life-cycles.start-life-cycles=false");

    @BeforeEach
    void emptyDatabase() throws SQLException {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
    }

    @Test
    void create_mode_registers_an_event_stream_table_as_before_and_records_it() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            persistenceStrategy(ctx).addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(UUID.class));

            assertThat(exists("orders_events")).isTrue();
            assertThat(ledgerModules()).contains("postgresql-event-store", "postgresql-event-store-subscriptions", "postgresql-event-store-gaps",
                                                 "eventsourced-aggregates-snapshots", "postgresql-queue");
        });
    }

    @Test
    void validate_mode_refuses_to_start_without_the_event_store_schema() throws SQLException {
        contextRunner.withPropertyValues("essentials.schema.mode=validate")
                     .run(ctx -> {
                         assertThat(ctx).hasFailed();
                         assertThat(rootCause(ctx.getStartupFailure())).isInstanceOf(SchemaValidationException.class)
                                                                       .hasMessageContaining("'postgresql-event-store-subscriptions' change")
                                                                       .hasMessageContaining("'eventsourced-aggregates-snapshots' change");
                     });
        assertThat(exists("durable_subscriptions")).isFalse();
    }

    @Test
    void the_emitted_script_satisfies_validate_and_an_aggregate_type_registered_later_is_validated_too() throws Exception {
        var scriptFile = tempDir.resolve("essentials-schema.sql");
        contextRunner.withPropertyValues("essentials.schema.mode=emit",
                                         "essentials.schema.emit.exit=false",
                                         "essentials.schema.emit.script-file=" + scriptFile)
                     .run(ctx -> assertThat(ctx).hasNotFailed());
        var script = Files.readString(scriptFile);
        assertThat(script).contains("-- Module postgresql-event-store-gaps").contains("-- Module eventsourced-aggregates-snapshots");
        assertThat(exists("durable_subscriptions")).as("emit executes nothing").isFalse();

        execute(script);

        contextRunner.withPropertyValues("essentials.schema.mode=validate")
                     .run(ctx -> {
                         assertThat(ctx).hasNotFailed();
                         assertThatThrownBy(() -> persistenceStrategy(ctx).addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(UUID.class)))
                                 .as("an event stream table nobody created is refused when registered")
                                 .isInstanceOf(SchemaValidationException.class)
                                 .hasMessageContaining("'postgresql-event-store' change 'event-stream-table' on 'orders_events': not applied");
                     });
        assertThat(exists("orders_events")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static AggregateEventStreamPersistenceStrategy<SeparateTablePerAggregateEventStreamConfiguration> persistenceStrategy(org.springframework.context.ApplicationContext ctx) {
        return ctx.getBean(AggregateEventStreamPersistenceStrategy.class);
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
