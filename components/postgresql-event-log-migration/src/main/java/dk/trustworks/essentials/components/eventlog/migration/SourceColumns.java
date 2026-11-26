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

package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import java.util.List;

/**
 * The column names of one old-engine event table ({@code EventStreamTableColumnNames}). Every name
 * is validated with {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)} before it is ever concatenated into SQL.
 * The column <em>types</em> are not part of this: pre-flight reads them from the catalog.
 */
public record SourceColumns(String globalOrder,
                            String aggregateId,
                            String eventOrder,
                            String eventId,
                            String causedByEventId,
                            String correlationId,
                            String eventType,
                            String eventRevision,
                            String timestamp,
                            String eventPayload,
                            String eventMetaData,
                            String tenant) {
    public SourceColumns {
        for (var name : List.of(globalOrder, aggregateId, eventOrder, eventId, causedByEventId, correlationId, eventType, eventRevision, timestamp, eventPayload,
                                eventMetaData, tenant)) {
            PostgresqlUtil.checkIsValidTableOrColumnName(name, "old event table column");
        }
    }

    /**
     * @return the old engine's default names ({@code EventStreamTableColumnNames.defaultColumnNames()})
     */
    public static SourceColumns defaults() {
        return new SourceColumns("global_order", "aggregate_id", "event_order", "event_id", "caused_by_event_id", "correlation_id", "event_type", "event_revision",
                                 "timestamp", "event_payload", "event_metadata", "tenant");
    }

    /** @return every column name, in the order of the record */
    List<String> all() {
        return List.of(globalOrder, aggregateId, eventOrder, eventId, causedByEventId, correlationId, eventType, eventRevision, timestamp, eventPayload, eventMetaData,
                       tenant);
    }
}
