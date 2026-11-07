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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.remove_item_from_shopping_basket

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.events.ItemAddedToShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ItemRemovedFromShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ShoppingBasketEvent
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketAlreadyCheckedOutException
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import dk.trustworks.essentials.types.Amount
import org.junit.jupiter.api.Test

/**
 * The evolver is tested through the decider that uses it, because that is the behaviour the model specifies -
 * "removing a product that is in the basket produces `ItemRemoved`" - and the fold is an implementation detail
 * of getting there.
 */
class RemoveItemFromShoppingBasketDeciderTest {

    private val scenario =
        GivenWhenThenScenario<RemoveItemFromShoppingBasket, ShoppingBasketEvent>(RemoveItemFromShoppingBasketDecider())

    private val basketId = ShoppingBasketId.random()
    private val product = ProductId.random()

    @Test
    fun `Removing an item that is in the basket records the price it went in at`() {
        scenario
            .given(ItemAddedToShoppingBasket(basketId, product, Amount.of("125.95")))
            .when_(RemoveItemFromShoppingBasket(basketId, product))
            .then_(ItemRemovedFromShoppingBasket(basketId, product, Amount.of("125.95")))
    }

    @Test
    fun `Removing one of two units removes the one added last, at its own price`() {
        scenario
            .given(
                ItemAddedToShoppingBasket(basketId, product, Amount.of("125.95")),
                ItemAddedToShoppingBasket(basketId, product, Amount.of("99.00"))
            )
            .when_(RemoveItemFromShoppingBasket(basketId, product))
            .then_(ItemRemovedFromShoppingBasket(basketId, product, Amount.of("99.00")))
    }

    @Test
    fun `Removing an item that is not in the basket is a no-op`() {
        scenario
            .given(ItemAddedToShoppingBasket(basketId, ProductId.random(), Amount.of("125.95")))
            .when_(RemoveItemFromShoppingBasket(basketId, product))
            .thenExpectNoEvent()
    }

    @Test
    fun `Removing the last remaining unit twice is a no-op the second time`() {
        scenario
            .given(
                ItemAddedToShoppingBasket(basketId, product, Amount.of("125.95")),
                ItemRemovedFromShoppingBasket(basketId, product, Amount.of("125.95"))
            )
            .when_(RemoveItemFromShoppingBasket(basketId, product))
            .thenExpectNoEvent()
    }

    @Test
    fun `A checked-out basket cannot be changed`() {
        scenario
            .given(
                ItemAddedToShoppingBasket(basketId, product, Amount.of("125.95")),
                CheckOutRequested(basketId, OrderId.random(), Amount.of("125.95"))
            )
            .when_(RemoveItemFromShoppingBasket(basketId, product))
            .thenFailsWithException(ShoppingBasketAlreadyCheckedOutException(basketId))
    }
}
