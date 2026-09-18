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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.cancel_order

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.sales.events.OrderCancelled
import dk.trustworks.essentials.examples.webshop.sales.events.OrderEvent
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import dk.trustworks.essentials.examples.webshop.sales.events.PaymentDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.events.ShippingDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.types.Address
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.PaymentMethod
import dk.trustworks.essentials.examples.webshop.sales.types.ShippingMethod
import org.junit.jupiter.api.Test

class CancelOrderDeciderTest {

    private val scenario = GivenWhenThenScenario<CancelOrder, OrderEvent>(CancelOrderDecider())

    private val orderId = OrderId.random()
    private val address = Address("Vestergade 1", "8000", "Aarhus", "DK")
    private val declined = "Payment was declined by the card issuer"

    private fun shippingDetails() = ShippingDetailsAdded(orderId, address, ShippingMethod.STANDARD)

    private fun paymentDetails() = PaymentDetailsAdded(orderId, address, PaymentMethod.CREDIT_CARD)

    @Test
    fun `A placed order can be cancelled, and the reason is recorded with it`() {
        scenario
            .given(shippingDetails(), paymentDetails(), OrderPlaced(orderId))
            .when_(CancelOrder(orderId, declined))
            .then_(OrderCancelled(orderId, declined))
    }

    @Test
    fun `Cancelling an order twice records nothing the second time`() {
        scenario
            .given(shippingDetails(), paymentDetails(), OrderPlaced(orderId), OrderCancelled(orderId, declined))
            .when_(CancelOrder(orderId, declined))
            .thenExpectNoEvent()
    }

    @Test
    fun `An order that was never placed cannot be cancelled`() {
        scenario
            .given(shippingDetails(), paymentDetails())
            .when_(CancelOrder(orderId, declined))
            .thenFailsWithException(OrderCannotBeCancelledException(orderId, "it has not been placed"))
    }

    @Test
    fun `An order nothing is known about cannot be cancelled`() {
        scenario
            .given()
            .when_(CancelOrder(orderId, declined))
            .thenFailsWithException(OrderCannotBeCancelledException(orderId, "it has not been placed"))
    }
}
