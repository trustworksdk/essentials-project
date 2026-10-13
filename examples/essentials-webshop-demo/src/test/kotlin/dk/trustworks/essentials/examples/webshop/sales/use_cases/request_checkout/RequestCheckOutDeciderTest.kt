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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.request_checkout

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.events.ItemAddedToShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ItemRemovedFromShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ShoppingBasketEvent
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketIsEmptyException
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import dk.trustworks.essentials.types.Amount
import org.junit.jupiter.api.Test

class RequestCheckOutDeciderTest {

    private val scenario = GivenWhenThenScenario<RequestCheckOut, ShoppingBasketEvent>(RequestCheckOutDecider())

    private val basketId = ShoppingBasketId.random()
    private val orderId = OrderId.random()
    private val coffee = ProductId.random()
    private val grinder = ProductId.random()

    @Test
    fun `Checking out totals the prices the shopper was shown`() {
        scenario
            .given(
                ItemAddedToShoppingBasket(basketId, coffee, Amount.of("125.95")),
                ItemAddedToShoppingBasket(basketId, grinder, Amount.of("874.05"))
            )
            .when_(RequestCheckOut(basketId, orderId))
            .then_(CheckOutRequested(basketId, orderId, Amount.of("1000.00")))
    }

    @Test
    fun `A removed item is not paid for`() {
        scenario
            .given(
                ItemAddedToShoppingBasket(basketId, coffee, Amount.of("125.95")),
                ItemAddedToShoppingBasket(basketId, grinder, Amount.of("874.05")),
                ItemRemovedFromShoppingBasket(basketId, grinder, Amount.of("874.05"))
            )
            .when_(RequestCheckOut(basketId, orderId))
            .then_(CheckOutRequested(basketId, orderId, Amount.of("125.95")))
    }

    @Test
    fun `Checking out twice gives the same order, and records nothing new`() {
        scenario
            .given(
                ItemAddedToShoppingBasket(basketId, coffee, Amount.of("125.95")),
                CheckOutRequested(basketId, orderId, Amount.of("125.95"))
            )
            .when_(RequestCheckOut(basketId, orderId))
            .thenExpectNoEvent()
    }

    @Test
    fun `An empty basket cannot be checked out`() {
        scenario
            .given()
            .when_(RequestCheckOut(basketId, orderId))
            .thenFailsWithException(ShoppingBasketIsEmptyException(basketId))
    }

    @Test
    fun `A basket emptied again cannot be checked out`() {
        scenario
            .given(
                ItemAddedToShoppingBasket(basketId, coffee, Amount.of("125.95")),
                ItemRemovedFromShoppingBasket(basketId, coffee, Amount.of("125.95"))
            )
            .when_(RequestCheckOut(basketId, orderId))
            .thenFailsWithException(ShoppingBasketIsEmptyException(basketId))
    }
}
