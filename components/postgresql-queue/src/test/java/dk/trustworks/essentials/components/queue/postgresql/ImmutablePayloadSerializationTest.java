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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.MessageMetaData;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.test_data.*;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A queue payload is an ordinary application object, and the common shape is an immutable one: {@code final} fields, a
 * single all-args constructor, no setters and no default constructor. Nothing but the Essentials immutable Jackson
 * module can populate that, and the failure mode is silent — Jackson happily constructs the object and leaves every
 * field {@code null}, so the payload only looks wrong once a consumer dereferences it, far from the cause.
 * <p>
 * This runs against whichever Jackson flavor the build selected, so it holds the two flavors to the same behaviour.
 * {@link OrderEvent.OrderAdded} is deliberately the subject: {@code orderId} is a final field declared on the
 * <em>superclass</em>, which is the case a module that only walks declared fields gets wrong.
 */
class ImmutablePayloadSerializationTest {

    @Test
    void an_immutable_payload_with_final_fields_round_trips_through_the_active_flavors_serializer() {
        var jsonSerializer = EssentialsObjectMappers.createJSONSerializer();
        var payload        = new OrderEvent.OrderAdded(OrderId.of("order-1"), CustomerId.of("customer-1"), 1234L);

        var json = jsonSerializer.serialize(payload);
        var deserialized = jsonSerializer.deserialize(json, OrderEvent.OrderAdded.class);

        // Cast to Object: an Essentials CharSequenceType is both a CharSequence and Comparable, which AssertJ's
        // assertThat overloads cannot disambiguate.
        assertThat((Object) deserialized.orderId)
                .as("inherited final field, populated by the Essentials immutable Jackson module — null here means the "
                    + "module for the active Jackson flavor was not registered or does not set inherited fields")
                .isEqualTo(payload.orderId);
        assertThat((Object) deserialized.orderingCustomerId).isEqualTo(payload.orderingCustomerId);
        assertThat(deserialized.orderNumber).isEqualTo(payload.orderNumber);
    }

    /**
     * The single-property case: {@code OrderAccepted} declares no fields of its own and has a lone single-argument
     * constructor. Jackson 3 reads such a constructor as a delegating creator, so {@code orderId} is never bound and
     * the payload comes back with a null id and <em>no error</em> — data silently lost on the way out of the queue
     * rather than a failure at the boundary. Jackson 2 populated it by final-field reflection instead, which is why
     * {@code EssentialsObjectMappers} re-enables that for Jackson 3.
     */
    @Test
    void a_payload_whose_only_property_is_an_inherited_final_field_round_trips() {
        var jsonSerializer = EssentialsObjectMappers.createJSONSerializer();
        var payload        = new OrderEvent.OrderAccepted(OrderId.of("order-2"));

        var json         = jsonSerializer.serialize(payload);
        var deserialized = jsonSerializer.deserialize(json, OrderEvent.OrderAccepted.class);

        assertThat(json).isEqualTo("{\"orderId\":\"order-2\"}");
        assertThat((Object) deserialized.orderId).isEqualTo(payload.orderId);
    }

    /**
     * The queue persists the metadata alongside the payload, and {@link MessageMetaData} is a {@code Map}
     * implementation rather than a bean. Mapper settings aimed at immutable beans have reached it before and broke
     * every queue operation with "Failed to deserialize message meta-data", so it is asserted here next to the
     * payloads instead of only in the integration tests.
     */
    @Test
    void message_metadata_round_trips() {
        var jsonSerializer = EssentialsObjectMappers.createJSONSerializer();
        var metaData       = MessageMetaData.of("correlation_id", "corr-1", "trace_id", "trace-1");

        var json         = jsonSerializer.serialize(metaData);
        var deserialized = jsonSerializer.deserialize(json, MessageMetaData.class);

        // Written as a JSON object of its entries, not as a bean wrapping a map. Key order is not asserted: the
        // backing map is a HashMap.
        assertThat(json).startsWith("{").contains("\"correlation_id\":\"corr-1\"", "\"trace_id\":\"trace-1\"");
        assertThat(deserialized).isEqualTo(metaData);
    }

    /** Essentials value types must stay JSON primitives, or previously persisted payloads stop being readable. */
    @Test
    void essentials_value_types_serialize_as_primitives() {
        var jsonSerializer = EssentialsObjectMappers.createJSONSerializer();

        var json = jsonSerializer.serialize(new OrderEvent.OrderAdded(OrderId.of("order-1"), CustomerId.of("customer-1"), 1234L));

        assertThat(json).contains("\"orderId\":\"order-1\"", "\"orderingCustomerId\":\"customer-1\"");
    }
}
