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

package dk.trustworks.essentials.components.eventlog.migration.cli;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.migration.*;
import dk.trustworks.essentials.components.foundation.schema.SchemaOwnership;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class MigrationConfigYamlTest {
    private static final String FULL = """
                                       connection:
                                         url: jdbc:postgresql://db:5432/app
                                         user: migrator
                                         passwordEnv: MIGRATION_PASSWORD
                                       schemaOwnership: HARNESS
                                       tables:
                                         - aggregateType: Orders
                                           table: orders_events
                                         - aggregateType: Payments
                                           table: payments_events
                                           columns:
                                             tenant: tenant_id
                                             eventPayload: payload
                                       settings:
                                         batchSize: 500
                                         epochWaitTimeout: PT2M
                                         epochWaitPoll: 50ms
                                         lockTimeout: 3s
                                         maxEventsPerSecond: 1000
                                         verifyWindow: 2000
                                       import:
                                         transientGapsTable: my_gaps
                                         subscriptions:
                                           - oldSubscriber: orders-projector
                                             subscriber: orders-view
                                             aggregateType: Orders
                                             model: PERIODIC
                                           - oldSubscriber: orders-mailer
                                             subscriber: mailer
                                             aggregateType: Orders
                                             model: IN_HANDLER_TRANSACTION
                                             consistency: CONTIGUOUS
                                             instances: [node-a, node-b]
                                             eventTypes: [OrderPlaced, OrderShipped]
                                             tenant: acme
                                       """;

    private static MigrationConfig parse(String yaml) {
        return MigrationConfigYaml.parse(new StringReader(yaml), Map.of("MIGRATION_PASSWORD", "s3cret")::get);
    }

    @Test
    void a_full_configuration_is_read_into_the_plan_types() {
        var config = parse(FULL);

        assertThat(config.connection()).isEqualTo(new MigrationConfig.ConnectionSettings("jdbc:postgresql://db:5432/app", "migrator", "s3cret"));
        assertThat(config.connection().toString()).doesNotContain("s3cret");
        assertThat(config.schemaOwnership()).isEqualTo(SchemaOwnership.HARNESS);
        assertThat(config.tables()).containsExactly(SourceTable.of("Orders", "orders_events"),
                                                    new SourceTable("Payments", "payments_events", withTenantAndPayload("tenant_id", "payload")));
        assertThat(config.settings()).isEqualTo(new CopySettings(500, Duration.ofMinutes(2), Duration.ofMillis(50), Duration.ofSeconds(3), 1000, 2000));

        var imports = config.imports();
        assertThat(imports.durableSubscriptionsTable()).isEqualTo(ResumePointImport.DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE);
        assertThat(imports.transientGapsTable()).isEqualTo("my_gaps");
        assertThat(imports.permanentGapsTable()).isEqualTo(ResumePointImport.DEFAULT_PERMANENT_GAPS_TABLE);
        assertThat(imports.subscriptions()).hasSize(2);

        var exclusive = imports.subscriptions().get(0).imported();
        assertThat(exclusive).isEqualTo(ImportedSubscription.exclusive("orders-projector", SubscriptionDefinition.builder("orders-view", "Orders").build(),
                                                                       ProgressModel.PERIODIC));

        var perInstance = imports.subscriptions().get(1).imported();
        assertThat(perInstance).isEqualTo(ImportedSubscription.perInstance("orders-mailer",
                                                                           SubscriptionDefinition.builder("mailer", "Orders")
                                                                                                 .consistency(ConsistencyMode.CONTIGUOUS)
                                                                                                 .eventTypes(EventTypeFilter.of("OrderPlaced", "OrderShipped"))
                                                                                                 .tenant("acme")
                                                                                                 .perInstance("node-a")
                                                                                                 .build(),
                                                                           ProgressModel.IN_HANDLER_TRANSACTION, List.of("node-a", "node-b")));
    }

    @Test
    void a_minimal_configuration_takes_the_defaults() {
        var config = parse("""
                           connection:
                             url: jdbc:postgresql://db/app
                           tables:
                             - {aggregateType: Orders, table: orders_events}
                           """);

        assertThat(config.connection()).isEqualTo(new MigrationConfig.ConnectionSettings("jdbc:postgresql://db/app", null, null));
        assertThat(config.schemaOwnership()).isEqualTo(SchemaOwnership.COMPONENT);
        assertThat(config.settings()).isEqualTo(CopySettings.defaults());
        assertThat(config.imports()).isEqualTo(MigrationConfig.Imports.none());
    }

    @Test
    void an_unknown_key_is_refused_so_a_misspelt_setting_never_falls_back_to_its_default() {
        assertThatThrownBy(() -> parse(FULL.replace("lockTimeout:", "lockTimout:"))).isInstanceOf(IllegalArgumentException.class)
                                                                                    .hasMessageContaining("settings: unknown key(s) [lockTimout]");
        assertThatThrownBy(() -> parse(FULL.replace("    columns:", "    colums:"))).hasMessageContaining("tables[1]: unknown key(s) [colums]");
    }

    @Test
    void invalid_values_are_refused_with_where_they_are() {
        assertThatThrownBy(() -> parse(FULL.replace("model: PERIODIC", "model: SOMETIMES"))).hasMessageContaining("import.subscriptions[0].model: 'SOMETIMES' is not one of");
        assertThatThrownBy(() -> parse(FULL.replace("lockTimeout: 3s", "lockTimeout: soon"))).hasMessageContaining("settings.lockTimeout: 'soon' is not a duration");
        assertThatThrownBy(() -> parse(FULL.replace("batchSize: 500", "batchSize: many"))).hasMessageContaining("settings.batchSize: expected a whole number");
        assertThatThrownBy(() -> parse(FULL.replace("table: orders_events", "table: \"orders; DROP TABLE x\""))).hasMessageContaining("Invalid table or column name");
        assertThatThrownBy(() -> parse(FULL.replace("url: jdbc:postgresql://db:5432/app", "url: jdbc:mysql://db/app"))).hasMessageContaining("jdbc:postgresql:");
        assertThatThrownBy(() -> parse("tables: []\n")).hasMessageContaining("'connection' is required");
        assertThatThrownBy(() -> parse("connection: [")).hasMessageContaining("not valid YAML");
    }

    @Test
    void the_password_comes_from_the_environment_or_the_file_never_both() {
        assertThatThrownBy(() -> MigrationConfigYaml.parse(new StringReader(FULL), name -> null))
                .hasMessageContaining("environment variable 'MIGRATION_PASSWORD' is not set");
        assertThatThrownBy(() -> parse(FULL.replace("  passwordEnv: MIGRATION_PASSWORD", "  passwordEnv: MIGRATION_PASSWORD\n  password: x")))
                .hasMessageContaining("not both");
        assertThat(parse(FULL.replace("  passwordEnv: MIGRATION_PASSWORD", "  password: plain")).connection().password()).isEqualTo("plain");
    }

    @Test
    void durations_are_ISO_8601_or_short_form() {
        assertThat(MigrationConfigYaml.duration("PT1.5S", "d")).isEqualTo(Duration.ofMillis(1500));
        assertThat(MigrationConfigYaml.duration("250ms", "d")).isEqualTo(Duration.ofMillis(250));
        assertThat(MigrationConfigYaml.duration("10s", "d")).isEqualTo(Duration.ofSeconds(10));
        assertThat(MigrationConfigYaml.duration("5m", "d")).isEqualTo(Duration.ofMinutes(5));
        assertThat(MigrationConfigYaml.duration("2h", "d")).isEqualTo(Duration.ofHours(2));
        assertThatThrownBy(() -> MigrationConfigYaml.duration("PTx", "d")).hasMessageContaining("not an ISO-8601 duration");
    }

    @Test
    void a_tag_that_would_construct_an_object_is_refused() {
        assertThatThrownBy(() -> parse("connection: !!java.net.URL [\"http://example.com\"]\ntables: []\n")).hasMessageContaining("not valid YAML");
    }

    private static SourceColumns withTenantAndPayload(String tenant, String payload) {
        var d = SourceColumns.defaults();
        return new SourceColumns(d.globalOrder(), d.aggregateId(), d.eventOrder(), d.eventId(), d.causedByEventId(), d.correlationId(), d.eventType(), d.eventRevision(),
                                 d.timestamp(), payload, d.eventMetaData(), tenant);
    }
}
