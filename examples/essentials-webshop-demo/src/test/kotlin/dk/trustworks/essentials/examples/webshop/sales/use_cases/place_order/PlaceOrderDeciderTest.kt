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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.place_order

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.sales.events.OrderEvent
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import dk.trustworks.essentials.examples.webshop.sales.events.PaymentDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.events.ShippingDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.types.Address
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.OrderIsNotReadyToBePlacedException
import dk.trustworks.essentials.examples.webshop.sales.types.PaymentMethod
import dk.trustworks.essentials.examples.webshop.sales.types.ShippingMethod
import org.junit.jupiter.api.Test

class PlaceOrderDeciderTest {

    private val scenario = GivenWhenThenScenario<PlaceOrder, OrderEvent>(PlaceOrderDecider())

    private val orderId = OrderId.random()
    private val address = Address("Vestergade 1", "8000", "Aarhus", "DK")

    private fun shippingDetails() = ShippingDetailsAdded(orderId, address, ShippingMethod.STANDARD)

    private fun paymentDetails() = PaymentDetailsAdded(orderId, address, PaymentMethod.CREDIT_CARD)

    @Test
    fun `An order with shipping and payment details can be placed`() {
        scenario
            .given(shippingDetails(), paymentDetails())
            .when_(PlaceOrder(orderId))
            .then_(OrderPlaced(orderId))
    }

    @Test
    fun `Placing an order twice records nothing the second time`() {
        scenario
            .given(shippingDetails(), paymentDetails(), OrderPlaced(orderId))
            .when_(PlaceOrder(orderId))
            .thenExpectNoEvent()
    }

    @Test
    fun `An order without shipping details cannot be placed`() {
        scenario
            .given(paymentDetails())
            .when_(PlaceOrder(orderId))
            .thenFailsWithException(
                OrderIsNotReadyToBePlacedException(orderId, "no shipping details have been added")
            )
    }

    @Test
    fun `An order without payment details cannot be placed`() {
        scenario
            .given(shippingDetails())
            .when_(PlaceOrder(orderId))
            .thenFailsWithException(
                OrderIsNotReadyToBePlacedException(orderId, "no payment details have been added")
            )
    }

    @Test
    fun `Corrected details still place the order once`() {
        scenario
            .given(
                shippingDetails(),
                ShippingDetailsAdded(orderId, Address("Nygade 7", "8000", "Aarhus", "DK"), ShippingMethod.EXPRESS),
                paymentDetails()
            )
            .when_(PlaceOrder(orderId))
            .then_(OrderPlaced(orderId))
    }
}
