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

package dk.trustworks.essentials.jackson;


import tools.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.jackson.model.*;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class EssentialTypesJacksonModuleTest {
    private final ObjectMapper objectMapper = EssentialTypesJacksonModule.createObjectMapper();

    @Test
    void test_serialization_and_deserialization() throws IOException {
        var amount             = Amount.of("123.45");
        var longAmount         = Amount.of("100");
        var percentage         = Percentage.from("30%");
        var orderLineProductId = ProductId.random();
        var testSubject = new SerializationTestSubject(CustomerId.random(),
                                                       OrderId.random(),
                                                       ProductId.random(),
                                                       AccountId.random(),
                                                       amount,
                                                       longAmount,
                                                       percentage,
                                                       CurrencyCode.DKK,
                                                       CountryCode.of("DK"),
                                                       EmailAddress.of("john@example.com"),
                                                       Map.of(orderLineProductId, Quantity.of(10),
                                                              ProductId.random(), Quantity.of(5),
                                                              ProductId.random(), Quantity.of(1)),
                                                       Money.of(amount.add(percentage.of(amount)), CurrencyCode.DKK),
                                                       Created.now(),
                                                       DueDate.now(),
                                                       LastUpdated.now(),
                                                       TimeOfDay.now(),
                                                       TransactionTime.now(),
                                                       TransferTime.now());

        var serialized = objectMapper.writeValueAsString(testSubject);
        System.out.println(serialized);
        var deserializedSubject = objectMapper.readValue(serialized.getBytes(StandardCharsets.UTF_8), SerializationTestSubject.class);

        assertThat(deserializedSubject.getAccountId()).isInstanceOf(AccountId.class);
        assertThat((CharSequence) deserializedSubject.getCustomerId()).isInstanceOf(CustomerId.class);
        assertThat(deserializedSubject.getOrderId()).isInstanceOf(OrderId.class);
        assertThat((CharSequence) deserializedSubject.getProductId()).isInstanceOf(ProductId.class);
        assertThat(deserializedSubject.getAmount()).isInstanceOf(Amount.class);
        assertThat(deserializedSubject.getPercentage()).isInstanceOf(Percentage.class);
        assertThat((CharSequence) deserializedSubject.getCurrency()).isInstanceOf(CurrencyCode.class);
        assertThat((CharSequence) deserializedSubject.getCountry()).isInstanceOf(CountryCode.class);
        assertThat((CharSequence) deserializedSubject.getEmail()).isInstanceOf(EmailAddress.class);
        assertThat(deserializedSubject.getTotalPrice()).isInstanceOf(Money.class);
        assertThat(deserializedSubject.getTotalPrice().getAmount()).isInstanceOf(Amount.class);
        assertThat((CharSequence) deserializedSubject.getTotalPrice().getCurrency()).isInstanceOf(CurrencyCode.class);
        assertThat(deserializedSubject.getOrderLines().get(orderLineProductId)).isEqualTo(testSubject.getOrderLines().get(orderLineProductId));

        assertThat(deserializedSubject.getCreated()).isInstanceOf(Created.class);
        assertThat(deserializedSubject.getDueDate()).isInstanceOf(DueDate.class);
        assertThat(deserializedSubject.getLastUpdated()).isInstanceOf(LastUpdated.class);
        assertThat(deserializedSubject.getTimeOfDay()).isInstanceOf(TimeOfDay.class);
        assertThat(deserializedSubject.getTransactionTime()).isInstanceOf(TransactionTime.class);
        assertThat(deserializedSubject.getTransferTime()).isInstanceOf(TransferTime.class);

        assertThat(deserializedSubject).isEqualTo(testSubject);
    }

    /** Fields only: a boolean field with an {@code isX()} accessor serializes as the field alone, as under Jackson 2. */
    @Test
    void an_is_getter_next_to_its_field_is_not_serialized_as_a_second_property() throws IOException {
        var shipment = new ExpressShipment("shipment-1", true);

        var serialized = objectMapper.writeValueAsString(shipment);

        assertThat(serialized).isEqualTo("{\"shipmentId\":\"shipment-1\",\"isExpress\":true}");
        assertThat(objectMapper.readValue(serialized, ExpressShipment.class)).isEqualTo(shipment);
    }

    /** A document without a later-added primitive component reads it as its default, as under Jackson 2. */
    @Test
    void a_document_persisted_before_a_primitive_component_was_added_reads_with_the_default() throws IOException {
        var deserialized = objectMapper.readValue("{\"orderId\":\"order-1\"}", OrderWithLaterPrimitives.class);

        assertThat(deserialized).isEqualTo(new OrderWithLaterPrimitives("order-1", 0, false));
    }

    static final class ExpressShipment {
        private final String  shipmentId;
        private final boolean isExpress;

        ExpressShipment(String shipmentId, boolean isExpress) {
            this.shipmentId = shipmentId;
            this.isExpress = isExpress;
        }

        public boolean isExpress() {
            return isExpress;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ExpressShipment that
                    && Objects.equals(shipmentId, that.shipmentId)
                    && isExpress == that.isExpress;
        }

        @Override
        public int hashCode() {
            return Objects.hash(shipmentId, isExpress);
        }
    }

    record OrderWithLaterPrimitives(String orderId, int quantity, boolean express) {
    }
}
