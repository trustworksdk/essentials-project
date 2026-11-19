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

package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.AggregateTypeTables;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@link EventLogEventStore}'s per-aggregate-type configuration (design phase 8 §5.1): the existing
 * {@link AggregateEventStreamConfiguration} - serializer, aggregate-id and tenant serializers - plus the engine's
 * validated table names for the aggregate type.
 * <p>
 * The column-type settings of the base class describe the old engine's tables and are not used: the engine's event
 * table has fixed column types ({@code text} aggregate id, {@code uuid} event, causation and correlation ids,
 * {@code json} payload and metadata). Event, causation and correlation ids must therefore be UUIDs.
 */
public final class EventLogAggregateEventStreamConfiguration extends AggregateEventStreamConfiguration {
    /**
     * The engine's table names for {@link #aggregateType}
     */
    public final AggregateTypeTables tables;

    /**
     * @param aggregateType         the aggregate type
     * @param jsonSerializer        the serializer for payloads and metadata - from {@code EssentialsJSONEventSerializers}, never a hand-built mapper
     * @param aggregateIdSerializer how the aggregate id is written to the engine's {@code aggregate_id} text column
     * @param tenantSerializer      how a tenant is written to the engine's {@code tenant} column
     */
    public EventLogAggregateEventStreamConfiguration(AggregateType aggregateType,
                                                     JSONEventSerializer jsonSerializer,
                                                     AggregateIdSerializer aggregateIdSerializer,
                                                     TenantSerializer<?> tenantSerializer) {
        super(AggregateEventStreamConfiguration.builder()
                                               .setAggregateType(requireNonNull(aggregateType, "No aggregateType provided"))
                                               .setQueryFetchSize(EventStore.DEFAULT_QUERY_BATCH_SIZE)
                                               .setJsonSerializer(requireNonNull(jsonSerializer, "No jsonSerializer provided"))
                                               .setAggregateIdSerializer(requireNonNull(aggregateIdSerializer, "No aggregateIdSerializer provided"))
                                               .setAggregateIdColumnType(IdentifierColumnType.TEXT)
                                               .setEventIdColumnType(IdentifierColumnType.UUID)
                                               .setCorrelationIdColumnType(IdentifierColumnType.UUID)
                                               .setEventJsonColumnType(JSONColumnType.JSON)
                                               .setEventMetadataJsonColumnType(JSONColumnType.JSON)
                                               .setTenantSerializer(requireNonNull(tenantSerializer, "No tenantSerializer provided"))
                                               .build());
        this.tables = AggregateTypeTables.of(aggregateType.toString());
    }
}
