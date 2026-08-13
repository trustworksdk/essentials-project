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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.views.order_status;

import dk.trustworks.essentials.spring.examples.postgresql.messaging.AbstractIntegrationTest;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.ShippingDestinationAddress;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.use_cases.register_shipping_order.RegisterShippingOrder;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.use_cases.ship_order.ShipOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code shipping.order_status} view slice reads the same table the command slices write, so unlike the
 * event-sourced {@code postgresql-cqrs} sibling there is <strong>nothing to wait for</strong>. Every assertion below
 * runs immediately after the command returns, with no {@code Awaitility} anywhere - that is the strong consistency
 * this lane buys, asserted rather than merely claimed.
 */
public class OrderStatusIT extends AbstractIntegrationTest {
    @Autowired
    private OrderStatusQueries queries;

    @Test
    void the_view_reports_shipped_status_immediately_after_the_commands_that_change_it() {
        var registeredOnly = OrderId.random();
        var shippedOrder   = OrderId.random();

        commandBus.send(new RegisterShippingOrder(registeredOnly, anAddress()));
        commandBus.send(new RegisterShippingOrder(shippedOrder, anAddress()));

        assertThat(queries.findOrderStatusById(registeredOnly.toString())).isPresent();
        assertThat(queries.findOrderStatusById(registeredOnly.toString()).get().isShipped()).isFalse();
        assertThat(queries.findOrderStatusById(shippedOrder.toString()).get().isShipped()).isFalse();

        commandBus.send(new ShipOrder(shippedOrder));

        assertThat(queries.findOrderStatusById(shippedOrder.toString()).get().isShipped()).isTrue();
        assertThat(queries.findOrderStatusById(registeredOnly.toString()).get().isShipped()).isFalse();
    }

    @Test
    void the_view_filters_and_lists_over_the_read_model_it_owns() {
        var registeredOnly = OrderId.random();
        var shippedOrder   = OrderId.random();

        commandBus.send(new RegisterShippingOrder(registeredOnly, anAddress()));
        commandBus.send(new RegisterShippingOrder(shippedOrder, anAddress()));
        commandBus.send(new ShipOrder(shippedOrder));

        assertThat(queries.findByShipped(true)).extracting(OrderStatusView::getId)
                                               .contains(shippedOrder.toString())
                                               .doesNotContain(registeredOnly.toString());
        assertThat(queries.findByShipped(false)).extracting(OrderStatusView::getId)
                                                .contains(registeredOnly.toString())
                                                .doesNotContain(shippedOrder.toString());
        assertThat(queries.findAllBy()).extracting(OrderStatusView::getId)
                                       .contains(registeredOnly.toString(), shippedOrder.toString());
    }

    private static ShippingDestinationAddress anAddress() {
        return ShippingDestinationAddress.builder()
                                         .setRecipientName("Test Tester")
                                         .setStreet("Test Street 1")
                                         .setZipCode("1234")
                                         .setCity("Test City")
                                         .build();
    }
}
