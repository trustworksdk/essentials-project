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

package dk.trustworks.essentials.components.eventlog.processor.migration;

import dk.trustworks.essentials.components.eventlog.migration.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.postgresql.InvalidTableOrColumnNameException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MigrationSourceTablesTest {
    private static final AggregateIdSerializer ID_SERIALIZER = AggregateIdSerializer.serializerFor(String.class);

    @Test
    void the_standard_configuration_maps_to_the_default_source_table() {
        var factory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(EssentialsJSONEventSerializers.create(), IdentifierColumnType.UUID,
                                                                                                         JSONColumnType.JSONB, new TenantSerializer.TenantIdSerializer());
        var orders   = factory.createEventStreamConfigurationFor(AggregateType.of("Orders"), ID_SERIALIZER);
        var payments = factory.createEventStreamConfigurationFor(AggregateType.of("Payments"), ID_SERIALIZER);

        assertThat(MigrationSourceTables.from(orders)).isEqualTo(SourceTable.of("Orders", "orders_events"));
        assertThat(MigrationSourceTables.from(List.of(orders, payments))).containsExactly(SourceTable.of("Orders", "orders_events"),
                                                                                         SourceTable.of("Payments", "payments_events"));
    }

    @Test
    void custom_table_and_column_names_cross_over() {
        var columns = EventStreamTableColumnNames.builder()
                                                 .globalOrderColumn("g").timestampColumn("ts").eventIdColumn("eid").causedByEventIdColumn("cause")
                                                 .aggregateIdColumn("aid").eventOrderColumn("eo").eventTypeColumn("et").eventRevisionColumn("rev")
                                                 .eventPayloadColumn("payload").eventMetaDataColumn("meta").correlationIdColumn("corr_id").tenantColumn("tnt")
                                                 .build();
        var factory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(type -> "legacy_" + type.toString().toLowerCase(), columns,
                                                                                                         EssentialsJSONEventSerializers.create(), IdentifierColumnType.TEXT,
                                                                                                         JSONColumnType.JSON, new TenantSerializer.TenantIdSerializer());

        assertThat(MigrationSourceTables.from(factory.createEventStreamConfigurationFor(AggregateType.of("Orders"), ID_SERIALIZER)))
                .isEqualTo(new SourceTable("Orders", "legacy_orders", new SourceColumns("g", "aid", "eo", "eid", "cause", "corr_id", "et", "rev", "ts", "payload", "meta", "tnt")));
    }

    @Test
    void an_unsafe_table_name_is_refused_by_the_source_table() {
        var factory = SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(type -> "orders; DROP TABLE x", EventStreamTableColumnNames.defaultColumnNames(),
                                                                                                         EssentialsJSONEventSerializers.create(), IdentifierColumnType.UUID,
                                                                                                         JSONColumnType.JSONB, new TenantSerializer.TenantIdSerializer());
        assertThatThrownBy(() -> {
            var configuration = factory.createEventStreamConfigurationFor(AggregateType.of("Orders"), ID_SERIALIZER);
            MigrationSourceTables.from(configuration);
        }).isInstanceOf(InvalidTableOrColumnNameException.class);
    }
}
