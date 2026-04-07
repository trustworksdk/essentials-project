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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.aggregates.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventJSON;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventMetaDataJSON;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.types.LongRange;

import java.time.OffsetDateTime;
import java.util.Arrays;

import static org.mockito.Mockito.mock;

final class AddNewAggregateSnapshotStrategyTestData {
    private static final OrderId ORDER_ID = OrderId.random();
    private static final JSONEventSerializer JSON_SERIALIZER = mock(JSONEventSerializer.class);

    private AddNewAggregateSnapshotStrategyTestData() {
    }

    static AggregateEventStream<OrderId> persistedEvents(AggregateType aggregateType, EventOrder... eventOrders) {
        var min = Arrays.stream(eventOrders).mapToLong(EventOrder::longValue).min().orElseThrow();
        var max = Arrays.stream(eventOrders).mapToLong(EventOrder::longValue).max().orElseThrow();
        return AggregateEventStream.of(configuration(aggregateType),
                                       ORDER_ID,
                                       LongRange.between(min, max),
                                       Arrays.stream(eventOrders).map(eventOrder -> persistedEvent(aggregateType, eventOrder)));
    }

    private static AggregateEventStreamConfiguration configuration(AggregateType aggregateType) {
        return SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(aggregateType,
                                                                                                   JSON_SERIALIZER,
                                                                                                   new AggregateIdSerializer.StringIdSerializer(),
                                                                                                   IdentifierColumnType.TEXT,
                                                                                                   JSONColumnType.JSONB);
    }

    private static PersistedEvent persistedEvent(AggregateType aggregateType, EventOrder eventOrder) {
        return PersistedEvent.from(EventId.random(),
                                   aggregateType,
                                   ORDER_ID,
                                   new EventJSON(JSON_SERIALIZER, EventName.of("TestEvent"), "{}"),
                                   eventOrder,
                                   EventRevision.of(1),
                                   GlobalEventOrder.of(100 + eventOrder.longValue()),
                                   new EventMetaDataJSON(JSON_SERIALIZER, null, "{}"),
                                   OffsetDateTime.now(),
                                   java.util.Optional.empty(),
                                   java.util.Optional.empty(),
                                   java.util.Optional.empty());
    }
}
