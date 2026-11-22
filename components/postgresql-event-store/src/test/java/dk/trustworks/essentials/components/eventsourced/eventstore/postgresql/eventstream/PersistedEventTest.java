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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventJSONTest.createSerializer;
import static org.assertj.core.api.Assertions.*;

class PersistedEventTest {
    public static final  String META_DATA_JSON = "{ \"metaData\": \"someValue\" }";
    private static final String EVENT_JSON     = "{\"testField\": \"testFieldValue\"}";

    private       JSONEventSerializer     jsonSerializer;
    private final EventType               eventType          = new EventType(this.getClass().getName() + "$TestEvent");
    private final EventName               eventName          = new EventName("ExampleEvent");
    private final EventJSONTest.TestEvent deserializedObject = new EventJSONTest.TestEvent("testFieldValue");


    @BeforeEach
    void setUp() {
        jsonSerializer = createSerializer();
    }


    @Test
    void valueEquals_should_return_true_for_equal_properties() {
        // Given
        var eventId          = EventId.random();
        var aggregateType    = AggregateType.of("TestAggregate");
        var aggregateId      = "aggregate-id-123";
        var event            = new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON);
        var eventOrder       = EventOrder.of(1);
        var eventRevision    = EventRevision.of(1);
        var globalEventOrder = GlobalEventOrder.of(1);
        var metaData         = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON);
        var timestamp        = OffsetDateTime.now();
        var causedByEventId  = Optional.of(EventId.random());
        var correlationId    = Optional.of(CorrelationId.random());
        var tenant           = Optional.of(TenantId.of("tenant-1"));

        var event1 = PersistedEvent.from(eventId, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);
        var event2 = PersistedEvent.from(eventId, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);

        // When & Then
        assertThat(event1.valueEquals(event2)).isTrue();
    }

    @Test
    void valueEquals_should_return_true_for_equal_properties_with_optional_empty() {
        // Given
        var eventId          = EventId.random();
        var aggregateType    = AggregateType.of("TestAggregate");
        var aggregateId      = "aggregate-id-123";
        var event            = new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON);
        var eventOrder       = EventOrder.of(1);
        var eventRevision    = EventRevision.of(1);
        var globalEventOrder = GlobalEventOrder.of(1);
        var metaData         = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON);
        var timestamp        = OffsetDateTime.now();
        var causedByEventId  = Optional.<EventId>empty();
        var correlationId    = Optional.<CorrelationId>empty();
        var tenant           = Optional.<Tenant>empty();

        var event1 = PersistedEvent.from(eventId, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);
        var event2 = PersistedEvent.from(eventId, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);

        // When & Then
        assertThat(event1.valueEquals(event2)).isTrue();
    }

    @Test
    void valueEquals_should_return_false_for_different_properties() {
        // Given
        var eventId1         = EventId.random();
        var eventId2         = EventId.random();
        var aggregateType    = AggregateType.of("TestAggregate");
        var aggregateId      = "aggregate-id-123";
        var event            = new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON);
        var eventOrder       = EventOrder.of(1);
        var eventRevision    = EventRevision.of(1);
        var globalEventOrder = GlobalEventOrder.of(1);
        var metaData         = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON);
        var timestamp        = OffsetDateTime.now();
        var causedByEventId  = Optional.of(EventId.random());
        var correlationId    = Optional.of(CorrelationId.random());
        var tenant           = Optional.of(TenantId.of("tenant-1"));

        var event1 = PersistedEvent.from(eventId1, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);
        var event2 = PersistedEvent.from(eventId2, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);

        // When & Then
        assertThat(event1.valueEquals(event2)).isFalse();
    }

    @Test
    void equals_should_return_true_for_same_eventId_even_if_other_properties_are_different() {
        // Given
        var eventId         = EventId.random();
        var aggregateType   = AggregateType.of("TestAggregate");
        var event           = new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON);
        var eventOrder      = EventOrder.of(1);
        var eventRevision   = EventRevision.of(1);
        var metaData        = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON);
        var timestamp       = OffsetDateTime.now();
        var causedByEventId = Optional.of(EventId.random());
        var correlationId   = Optional.of(CorrelationId.random());
        var tenant          = Optional.of(TenantId.of("tenant-1"));

        var event1 = PersistedEvent.from(eventId, aggregateType, "aggregate-id-123", event, eventOrder,
                                         eventRevision, GlobalEventOrder.of(1), metaData, timestamp, causedByEventId, correlationId, tenant);
        var event2 = PersistedEvent.from(eventId, aggregateType, "aggregate-id-456", event, eventOrder,
                                         eventRevision, GlobalEventOrder.of(2), metaData, timestamp, causedByEventId, correlationId, tenant);

        // When & Then
        assertThat(event1).isEqualTo(event2);
    }

    @Test
    void equals_should_return_false_for_different_eventId() {
        // Given
        var eventId1         = EventId.random();
        var eventId2         = EventId.random();
        var aggregateType    = AggregateType.of("TestAggregate");
        var aggregateId      = "aggregate-id-123";
        var event            = new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON);
        var eventOrder       = EventOrder.of(1);
        var eventRevision    = EventRevision.of(1);
        var globalEventOrder = GlobalEventOrder.of(1);
        var metaData         = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON);
        var timestamp        = OffsetDateTime.now();
        var causedByEventId  = Optional.of(EventId.random());
        var correlationId    = Optional.of(CorrelationId.random());
        var tenant           = Optional.of(TenantId.of("tenant-1"));

        var event1 = PersistedEvent.from(eventId1, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);
        var event2 = PersistedEvent.from(eventId2, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);

        // When & Then
        assertThat(event1).isNotEqualTo(event2);
    }

    @Test
    void should_return_correct_property_values() {
        // Given
        var eventId          = EventId.random();
        var aggregateType    = AggregateType.of("TestAggregate");
        var aggregateId      = "aggregate-id-123";
        var eventJson        = new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON);
        var eventOrder       = EventOrder.of(1);
        var eventRevision    = EventRevision.of(1);
        var globalEventOrder = GlobalEventOrder.of(1);
        var metaData         = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON);
        var timestamp        = OffsetDateTime.now();
        var causedByEventId  = Optional.of(EventId.random());
        var correlationId    = Optional.of(CorrelationId.random());
        var tenant           = Optional.of(TenantId.of("tenant-1"));

        var event = PersistedEvent.from(eventId, aggregateType, aggregateId, eventJson, eventOrder,
                                        eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);

        // When & Then
        assertThat((CharSequence) event.eventId()).isEqualTo(eventId);
        assertThat((CharSequence) event.aggregateType()).isEqualTo(aggregateType);
        assertThat(event.aggregateId()).isEqualTo(aggregateId);
        assertThat(event.event()).isEqualTo(eventJson);
        assertThat(event.eventOrder()).isEqualTo(eventOrder);
        assertThat(event.eventRevision()).isEqualTo(eventRevision);
        assertThat(event.globalEventOrder()).isEqualTo(globalEventOrder);
        assertThat(event.metaData()).isEqualTo(metaData);
        assertThat(event.timestamp()).isEqualTo(timestamp);
        assertThat(event.causedByEventId()).isEqualTo(causedByEventId);
        assertThat(event.correlationId()).isEqualTo(correlationId);
        assertThat(event.tenant()).isEqualTo(tenant);
    }

    @Test
    void hashCode_should_be_equal_for_same_eventId_even_if_other_properties_are_different() {
        // Given
        var eventId         = EventId.random();
        var aggregateType   = AggregateType.of("TestAggregate");
        var event           = new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON);
        var eventOrder      = EventOrder.of(1);
        var eventRevision   = EventRevision.of(1);
        var metaData        = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON);
        var timestamp       = OffsetDateTime.now();
        var causedByEventId = Optional.of(EventId.random());
        var correlationId   = Optional.of(CorrelationId.random());
        var tenant          = Optional.of(TenantId.of("tenant-1"));

        var event1 = PersistedEvent.from(eventId, aggregateType, "aggregate-id-123", event, eventOrder,
                                         eventRevision, GlobalEventOrder.of(1), metaData, timestamp, causedByEventId, correlationId, tenant);
        var event2 = PersistedEvent.from(eventId, aggregateType, "aggregate-id-456", event, eventOrder,
                                         eventRevision, GlobalEventOrder.of(2), metaData, timestamp, causedByEventId, correlationId, tenant);

        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
    }

    @Test
    void hashCode_should_return_false_for_different_eventId() {
        // Given
        var eventId1         = EventId.random();
        var eventId2         = EventId.random();
        var aggregateType    = AggregateType.of("TestAggregate");
        var aggregateId      = "aggregate-id-123";
        var event            = new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON);
        var eventOrder       = EventOrder.of(1);
        var eventRevision    = EventRevision.of(1);
        var globalEventOrder = GlobalEventOrder.of(1);
        var metaData         = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON);
        var timestamp        = OffsetDateTime.now();
        var causedByEventId  = Optional.of(EventId.random());
        var correlationId    = Optional.of(CorrelationId.random());
        var tenant           = Optional.of(TenantId.of("tenant-1"));

        var event1 = PersistedEvent.from(eventId1, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);
        var event2 = PersistedEvent.from(eventId2, aggregateType, aggregateId, event, eventOrder,
                                         eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);

        // When & Then
        assertThat(event1.hashCode()).isNotEqualTo(event2.hashCode());
    }

    @Test
    void toString_should_return_string_without_event_json_payload() {
        // Given
        var eventId          = EventId.random();
        var aggregateType    = AggregateType.of("TestAggregate");
        var aggregateId      = "aggregate-id-123";
        var eventJson        = new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON);
        var eventOrder       = EventOrder.of(1);
        var eventRevision    = EventRevision.of(1);
        var globalEventOrder = GlobalEventOrder.of(1);
        var metaData         = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON);
        var timestamp        = OffsetDateTime.now();
        var causedByEventId  = Optional.of(EventId.random());
        var correlationId    = Optional.of(CorrelationId.random());
        var tenant           = Optional.of(TenantId.of("tenant-1"));

        var event = PersistedEvent.from(eventId, aggregateType, aggregateId, eventJson, eventOrder,
                                        eventRevision, globalEventOrder, metaData, timestamp, causedByEventId, correlationId, tenant);

        // When
        var toStringResult = event.toString();

        // Then
        assertThat(toStringResult).isNotEmpty();
        assertThat(toStringResult).doesNotContain(EVENT_JSON);
        assertThat(toStringResult).contains(META_DATA_JSON);
    }

    private PersistedEvent fullyPopulatedEvent() {
        return PersistedEvent.from(EventId.random(),
                                   AggregateType.of("TestAggregate"),
                                   "aggregate-id-123",
                                   new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON),
                                   EventOrder.of(3),
                                   EventRevision.of(2),
                                   GlobalEventOrder.of(7),
                                   new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON),
                                   OffsetDateTime.now(),
                                   Optional.of(EventId.random()),
                                   Optional.of(CorrelationId.random()),
                                   Optional.of(TenantId.of("tenant-1")));
    }

    private static void assertAllPropertiesExceptEventAndMetaDataAreCopied(PersistedEvent copy, PersistedEvent original) {
        assertThat((CharSequence) copy.eventId()).isEqualTo(original.eventId());
        assertThat((CharSequence) copy.aggregateType()).isEqualTo(original.aggregateType());
        assertThat(copy.aggregateId()).isEqualTo(original.aggregateId());
        assertThat(copy.eventOrder()).isEqualTo(original.eventOrder());
        assertThat(copy.eventRevision()).isEqualTo(original.eventRevision());
        assertThat(copy.globalEventOrder()).isEqualTo(original.globalEventOrder());
        assertThat(copy.timestamp()).isEqualTo(original.timestamp());
        assertThat(copy.causedByEventId()).isEqualTo(original.causedByEventId());
        assertThat(copy.correlationId()).isEqualTo(original.correlationId());
        assertThat(copy.tenant()).isEqualTo(original.tenant());
    }

    @Test
    void withEvent_replaces_only_the_event_and_keeps_every_other_property() {
        var original    = fullyPopulatedEvent();
        var originalJson = original.event();
        var replacement = new EventJSON(jsonSerializer, new EventJSONTest.TestEvent("decrypted"), eventType, "{\"testField\": \"decrypted\"}");

        var copy = original.withEvent(replacement);

        assertThat(copy).isNotSameAs(original);
        assertThat(copy.event()).isSameAs(replacement);
        assertThat(copy.metaData()).isSameAs(original.metaData());
        assertAllPropertiesExceptEventAndMetaDataAreCopied(copy, original);
        assertThat(original.event()).isSameAs(originalJson);
        assertThat(copy).isEqualTo(original);
        assertThat(copy.valueEquals(original)).isFalse();
    }

    @Test
    void withMetaData_replaces_only_the_metadata_and_keeps_every_other_property() {
        var original         = fullyPopulatedEvent();
        var originalMetaData = original.metaData();
        var replacement      = new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), "{ \"metaData\": \"otherValue\" }");

        var copy = original.withMetaData(replacement);

        assertThat(copy).isNotSameAs(original);
        assertThat(copy.metaData()).isSameAs(replacement);
        assertThat(copy.event()).isSameAs(original.event());
        assertAllPropertiesExceptEventAndMetaDataAreCopied(copy, original);
        assertThat(original.metaData()).isSameAs(originalMetaData);
        assertThat(copy).isEqualTo(original);
        assertThat(copy.valueEquals(original)).isFalse();
    }

    @Test
    void withEvent_and_withMetaData_keep_empty_optionals_empty() {
        var original = PersistedEvent.from(EventId.random(), AggregateType.of("TestAggregate"), "aggregate-id-123",
                                           new EventJSON(jsonSerializer, deserializedObject, eventType, EVENT_JSON), EventOrder.of(0),
                                           EventRevision.of(1), GlobalEventOrder.of(1),
                                           new EventMetaDataJSON(jsonSerializer, EventMetaData.class.getName(), META_DATA_JSON), OffsetDateTime.now(),
                                           Optional.empty(), Optional.empty(), Optional.empty());

        var copy = original.withEvent(original.event()).withMetaData(original.metaData());

        assertThat(copy.valueEquals(original)).isTrue();
        assertThat(copy.causedByEventId()).isEmpty();
        assertThat(copy.correlationId()).isEmpty();
        assertThat(copy.tenant()).isEmpty();
    }

    @Test
    void withEvent_and_withMetaData_reject_null() {
        var original = fullyPopulatedEvent();
        assertThatThrownBy(() -> original.withEvent(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> original.withMetaData(null)).isInstanceOf(IllegalArgumentException.class);
    }
}