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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.components.eventlog.AggregateTypeTables;
import dk.trustworks.essentials.components.eventlog.EventLogSchema;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Where the harness's SQL finds an aggregate type's events, on either engine: the {@code FROM} clause and the column
 * expressions for the aggregate id, the event type name (ending in the event's fully qualified class name on both
 * engines, so {@code LIKE '%.Simple'} matches), the JSON payload and the append timestamp.
 * <p>
 * Classic engine: the type's own event table, through the store's configuration of it. New engine
 * ({@code trading-demo.engine=event-log}): its default {@code el_<type>_events} table, joined to
 * {@code el_event_types} because it stores the event type as a {@code smallint} id. Every name comes from the event
 * store's configuration or the engine's naming rules, never from a request.
 */
record EventTable(String from, String aggregateId, String eventType, String payload, String timestamp) {

    static EventTable of(ConfigurableEventStore<?> eventStore, AggregateType aggregateType) {
        requireNonNull(eventStore, "No eventStore provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        // Resolved per call by the callers, not in a constructor: an aggregate type's stream configuration is
        // registered lazily, when its repository is first built
        var configuration = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        if (configuration instanceof SeparateTablePerAggregateEventStreamConfiguration classic) {
            var columns = classic.eventStreamTableColumnNames;
            return new EventTable(classic.eventStreamTableName,
                                  columns.aggregateIdColumn,
                                  columns.eventTypeColumn,
                                  columns.eventPayloadColumn,
                                  columns.timestampColumn);
        }
        var table = AggregateTypeTables.of(aggregateType.toString()).eventTable();
        return new EventTable(table + " e JOIN " + EventLogSchema.EVENT_TYPES_TABLE + " et ON et.id = e.event_type",
                              "e.aggregate_id",
                              "et.name",
                              "e.event_payload",
                              "e.timestamp");
    }
}
