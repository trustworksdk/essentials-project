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

package dk.trustworks.essentials.components.eventlog.integration;

import dk.trustworks.essentials.components.eventlog.StoredEvent;
import dk.trustworks.essentials.components.eventlog.integration.test_data.Added;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class EventDeserializerTest {
    private final EventDeserializer deserializer = EventDeserializer.of(EssentialsJSONEventSerializers.create());

    private StoredEvent stored(String eventType, String payload, String tenant) {
        var serializer = deserializer.serializer();
        return new StoredEvent("Orders", 7, 3, UUID.randomUUID(), UUID.randomUUID(), null, eventType, 1, OffsetDateTime.now(), "o1", payload,
                               serializer.serialize(EventMetaData.of("k", "v")), tenant);
    }

    @Test
    void a_serialized_event_type_is_deserialized_with_the_values_as_value_types() {
        var stored = stored("FQCN:" + Added.class.getName(), deserializer.serializer().serialize(new Added("o1", 5)), "tenant-a");
        var event  = deserializer.deserialize(stored);
        assertThat(event.payload()).isEqualTo(new Added("o1", 5));
        assertThat(event.aggregateId()).isEqualTo("o1");
        assertThat(event.globalOrder().longValue()).isEqualTo(7);
        assertThat(event.eventOrder().longValue()).isEqualTo(3);
        assertThat((Object) event.eventId()).isEqualTo(EventId.of(stored.eventId().toString()));
        assertThat(event.causedBy()).hasValue(EventId.of(stored.causedByEventId().toString()));
        assertThat(event.correlationId()).isEmpty();
        assertThat(event.metaData()).containsEntry("k", "v");
        assertThat(event.tenant()).hasValue(TenantId.of("tenant-a"));
        assertThat(event.raw()).isSameAs(stored);
    }

    @Test
    void a_named_event_keeps_its_json_text_as_payload() {
        var event = deserializer.deserialize(stored("OrderPlaced", "{\"a\":1}", null));
        assertThat(event.payload()).isEqualTo("{\"a\":1}");
        assertThat(event.eventType().getEventName()).isPresent();
        assertThat(event.tenant()).isEmpty();
    }

    @Test
    void an_unknown_payload_class_throws() {
        assertThatThrownBy(() -> deserializer.deserialize(stored("FQCN:no.such.Type", "{}", null))).isInstanceOf(RuntimeException.class);
    }
}
