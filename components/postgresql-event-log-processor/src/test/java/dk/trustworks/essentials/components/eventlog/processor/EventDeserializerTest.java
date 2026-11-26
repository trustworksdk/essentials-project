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

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.Added;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.foundation.json.JSONDeserializationException;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.reflection.LoadingClassFailedException;
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
    void the_dependencies_give_each_aggregate_type_its_own_typed_aggregate_id() {
        // The annotated base classes used to hard-wire String ids: a handler ported from the old engine expecting a typed id got a String
        var dependencies = new EventLogProcessorDependencies(dk.trustworks.essentials.components.eventlog.EventLog.builder().setDataSource(new org.postgresql.ds.PGSimpleDataSource()).build(),
                                                             EssentialsJSONEventSerializers.create(),
                                                             org.mockito.Mockito.mock(dk.trustworks.essentials.components.eventlog.PageTransactionSource.class),
                                                             aggregateType -> aggregateType.equals("Orders")
                                                                              ? AggregateIdSerializer.serializerFor(EventId.class)
                                                                              : new AggregateIdSerializer.StringIdSerializer());
        var stored = stored("OrderPlaced", "{\"a\":1}", null);

        assertThat(dependencies.deserializerFor("Orders").deserialize(stored).aggregateId()).isEqualTo(EventId.of("o1"));
        assertThat(dependencies.deserializerFor("Invoices").deserialize(stored).aggregateId()).isEqualTo("o1");
    }

    /** An application's own tenant type, as an application with a TenantSerializer bean of its own has */
    record CountryTenant(String country) implements Tenant {
    }

    static class CountryTenantSerializer implements TenantSerializer<CountryTenant> {
        @Override
        public Class<?> tenantType() {
            return CountryTenant.class;
        }

        @Override
        public String serialize(CountryTenant tenant) {
            return tenant == null ? null : tenant.country();
        }

        @Override
        public Optional<CountryTenant> deserialize(String tenant) {
            return Optional.ofNullable(tenant).map(CountryTenant::new);
        }
    }

    @Test
    void the_dependencies_give_each_aggregate_type_the_tenant_type_its_event_store_configuration_writes() {
        // The tenant serializer used to be hard-wired to TenantId: a handler casting event.tenant() to the application's
        // tenant type got a ClassCastException and the event was parked
        var eventLog = dk.trustworks.essentials.components.eventlog.EventLog.builder().setDataSource(new org.postgresql.ds.PGSimpleDataSource()).build();
        var pages    = org.mockito.Mockito.mock(dk.trustworks.essentials.components.eventlog.PageTransactionSource.class);
        var custom = new EventLogProcessorDependencies(eventLog, EssentialsJSONEventSerializers.create(), pages,
                                                       aggregateType -> new AggregateIdSerializer.StringIdSerializer(),
                                                       aggregateType -> aggregateType.equals("Orders")
                                                                        ? new CountryTenantSerializer()
                                                                        : new TenantSerializer.TenantIdSerializer());
        var stored = stored("OrderPlaced", "{\"a\":1}", "DK");

        assertThat(custom.deserializerFor("Orders").deserialize(stored).tenant()).hasValue(new CountryTenant("DK"));
        assertThat(custom.deserializerFor("Invoices").deserialize(stored).tenant()).hasValue(TenantId.of("DK"));
        // The three- and four-argument constructors keep TenantId tenants
        assertThat(new EventLogProcessorDependencies(eventLog, EssentialsJSONEventSerializers.create(), pages)
                           .deserializerFor("Orders").deserialize(stored).tenant()).hasValue(TenantId.of("DK"));
    }

    @Test
    void an_unknown_payload_class_throws() {
        assertThatThrownBy(() -> deserializer.deserialize(stored("FQCN:no.such.Type", "{}", null))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void the_jackson_parser_exception_name_is_the_real_class() {
        // Matched by name because the module has no Jackson dependency: a Jackson rename must fail here, not silently retry forever
        assertThat(EventDeserializer.JACKSON_STREAM_READ_EXCEPTION).isEqualTo(tools.jackson.core.exc.StreamReadException.class.getName());
        assertThat(tools.jackson.core.exc.StreamReadException.class.isAssignableFrom(tools.jackson.databind.DatabindException.class)).isFalse();
    }

    @Test
    void a_payload_that_is_not_json_parks_as_terminal_naming_the_event() {
        for (var malformed : List.of("{not json", "{\"orderId\":\"o1\",\"amount\":1", "{\"orderId\":\"o1\",\"amount\":1} trailing", "", "  ")) {
            var stored = stored("FQCN:" + Added.class.getName(), malformed, null);

            assertThatThrownBy(() -> deserializer.deserialize(stored))
                    .as(malformed)
                    .isInstanceOfSatisfying(ParkEventException.class, e -> {
                        assertThat(e.event()).isSameAs(stored);
                        assertThat(e).hasCauseInstanceOf(JSONDeserializationException.class)
                                     .hasMessageContaining("payload")
                                     .hasMessageContaining(stored.eventId().toString())
                                     .hasMessageContaining("not valid JSON");
                    });
        }
    }

    @Test
    void metadata_that_is_not_json_parks_as_terminal() {
        var stored = new StoredEvent("Orders", 7, 3, UUID.randomUUID(), null, null, "FQCN:" + Added.class.getName(), 1, OffsetDateTime.now(), "o1",
                                     deserializer.serializer().serialize(new Added("o1", 5)), "{broken", null);

        assertThatThrownBy(() -> deserializer.deserialize(stored)).isInstanceOf(ParkEventException.class)
                                                                  .hasMessageContaining("metadata");
    }

    @Test
    void an_unknown_payload_class_is_an_ordinary_failure_that_retries() {
        // A rolling deployment brings the class: retrying heals it
        assertThatThrownBy(() -> deserializer.deserialize(stored("FQCN:no.such.Type", "{\"a\":1}", null)))
                .isNotInstanceOf(ParkEventException.class)
                .isInstanceOf(LoadingClassFailedException.class);
        // Even with a payload that would not parse: the class is resolved first, and its absence is the healable part
        assertThatThrownBy(() -> deserializer.deserialize(stored("FQCN:no.such.Type", "{not json", null)))
                .isNotInstanceOf(ParkEventException.class);
    }

    @Test
    void valid_json_of_the_wrong_shape_is_an_ordinary_failure_that_retries() {
        // A deployment can bring the class's new shape: retrying may heal it
        for (var wrongShape : List.of("{\"orderId\":\"o1\",\"amount\":\"abc\"}", "[1,2]", "\"text\"", "{\"orderId\":{},\"amount\":1}")) {
            assertThatThrownBy(() -> deserializer.deserialize(stored("FQCN:" + Added.class.getName(), wrongShape, null)))
                    .as(wrongShape)
                    .isInstanceOf(JSONDeserializationException.class)
                    .isNotInstanceOf(ParkEventException.class);
        }
    }

    @Test
    void the_parser_failure_is_found_anywhere_in_the_cause_chain_and_nothing_else_matches() {
        var parserFailure = new tools.jackson.core.exc.UnexpectedEndOfInputException(null, null, "end");
        assertThat(EventDeserializer.isStreamReadFailure(new RuntimeException(new RuntimeException(parserFailure)))).isTrue();
        assertThat(EventDeserializer.isStreamReadFailure(new IllegalStateException("x", new IllegalArgumentException()))).isFalse();
    }
}
