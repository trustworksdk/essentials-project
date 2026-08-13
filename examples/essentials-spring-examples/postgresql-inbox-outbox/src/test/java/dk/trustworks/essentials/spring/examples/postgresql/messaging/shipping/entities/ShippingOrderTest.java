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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.entities;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.ShippingDestinationAddress;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entity carries the bounded context's only invariant, so it gets a test that needs no Spring context and no
 * container - which is most of the point of keeping the decision on the entity rather than in the handler.
 */
class ShippingOrderTest {
    @Test
    void an_order_that_has_not_been_shipped_can_be_marked_as_shipped() {
        var order = newOrder();

        assertThat(order.markOrderAsShipped()).isTrue();
    }

    @Test
    void marking_an_already_shipped_order_as_shipped_is_a_no_op() {
        var order = newOrder();
        order.markOrderAsShipped();

        // ShipOrder arrives over an at-least-once Inbox, so this is the redelivery case rather than a caller error
        assertThat(order.markOrderAsShipped()).isFalse();
    }

    private static ShippingOrder newOrder() {
        return new ShippingOrder(OrderId.random().toString(),
                                 ShippingDestinationAddress.builder()
                                                           .setRecipientName("Test Tester")
                                                           .setStreet("Test Street 1")
                                                           .setZipCode("1234")
                                                           .setCity("Test City")
                                                           .build());
    }
}
