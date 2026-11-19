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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The bridge from the old engine's configuration to the migration's input: a
 * {@link SeparateTablePerAggregateEventStreamConfiguration} becomes a {@link SourceTable} with the same aggregate type,
 * table and column names. It lives here because the migration module never depends on {@code postgresql-event-store}.
 * <p>
 * Only names cross over. The configuration's id and JSON column types are deliberately not used: pre-flight reads the
 * actual types from the catalog, which is what the copy has to match.
 * <p>
 * Needs {@code postgresql-event-log-migration} on the classpath; this module declares it {@code provided}.
 */
public final class MigrationSourceTables {
    private MigrationSourceTables() {
    }

    /**
     * @return the migration's source table for {@code configuration}
     */
    public static SourceTable from(SeparateTablePerAggregateEventStreamConfiguration configuration) {
        requireNonNull(configuration, "No configuration provided");
        var c = requireNonNull(configuration.eventStreamTableColumnNames, "The configuration has no eventStreamTableColumnNames");
        return new SourceTable(configuration.aggregateType.toString(),
                               configuration.eventStreamTableName,
                               new SourceColumns(c.globalOrderColumn,
                                                 c.aggregateIdColumn,
                                                 c.eventOrderColumn,
                                                 c.eventIdColumn,
                                                 c.causedByEventIdColumn,
                                                 c.correlationIdColumn,
                                                 c.eventTypeColumn,
                                                 c.eventRevisionColumn,
                                                 c.timestampColumn,
                                                 c.eventPayloadColumn,
                                                 c.eventMetaDataColumn,
                                                 c.tenantColumn));
    }

    /**
     * @return the migration's source tables for {@code configurations}, in their order
     */
    public static List<SourceTable> from(Collection<SeparateTablePerAggregateEventStreamConfiguration> configurations) {
        return requireNonNull(configurations, "No configurations provided").stream().map(MigrationSourceTables::from).toList();
    }
}
