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

package dk.trustworks.essentials.examples.webshop.shipping.use_cases.ship_order

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderPackagingRequested
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderShipped
import dk.trustworks.essentials.examples.webshop.shipping.events.ShippingOrderEvent
import dk.trustworks.essentials.examples.webshop.shipping.types.TrackingNumber
import org.junit.jupiter.api.Test

class ShipOrderDeciderTest {

    private val scenario = GivenWhenThenScenario<ShipOrder, ShippingOrderEvent>(ShipOrderDecider())

    private val orderId = OrderId.random()
    private val trackingNumber = TrackingNumber.of("TRACK-12345")

    @Test
    fun `A packaged order can be shipped`() {
        scenario
            .given(OrderPackagingRequested(orderId))
            .when_(ShipOrder(orderId, trackingNumber))
            .then_(OrderShipped(orderId, trackingNumber))
    }

    @Test
    fun `An order that has not been packaged cannot be shipped`() {
        scenario
            .given()
            .when_(ShipOrder(orderId, trackingNumber))
            .thenFailsWithException(OrderHasNotBeenPackagedException(orderId))
    }

    @Test
    fun `Shipping twice records nothing the second time, even with a different tracking number`() {
        scenario
            .given(OrderPackagingRequested(orderId), OrderShipped(orderId, trackingNumber))
            .when_(ShipOrder(orderId, TrackingNumber.of("TRACK-99999")))
            .thenExpectNoEvent()
    }
}
