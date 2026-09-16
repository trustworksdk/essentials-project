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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.change_product_price

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.sales.events.ProductAdded
import dk.trustworks.essentials.examples.webshop.sales.events.ProductEvent
import dk.trustworks.essentials.examples.webshop.sales.events.ProductPriceChanged
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.types.Amount
import org.junit.jupiter.api.Test

/**
 * The acceptance criteria of the slice, in the same Given/When/Then shape they were written in on the event
 * model. No event store, no Spring context, no mocks - the decider is a function, so these are function tests.
 *
 * The three tests are the decider's three possible outcomes: an event, no event, an exception.
 */
class ChangeProductPriceDeciderTest {

    private val scenario = GivenWhenThenScenario<ChangeProductPrice, ProductEvent>(ChangeProductPriceDecider())

    @Test
    fun `Changing the price`() {
        val productId = ProductId.random()
        val originalPrice = Amount.of("125.95")
        val newPrice = Amount.of("100.00")

        scenario
            .given(ProductAdded(productId, "Test product", originalPrice))
            .when_(ChangeProductPrice(productId, newPrice))
            .then_(ProductPriceChanged(productId, newPrice))
    }

    @Test
    fun `Changing the price to the existing price results in no change`() {
        val productId = ProductId.random()
        val price = Amount.of("125.95")

        scenario
            .given(ProductAdded(productId, "Test product", price))
            .when_(ChangeProductPrice(productId, price))
            .thenExpectNoEvent()
    }

    @Test
    fun `Changing the price to the same value written at a different scale results in no change`() {
        val productId = ProductId.random()

        scenario
            .given(ProductAdded(productId, "Test product", Amount.of("100.00")))
            .when_(ChangeProductPrice(productId, Amount.of("100.0")))
            .thenExpectNoEvent()
    }

    @Test
    fun `The price that counts is the latest one, not the original`() {
        val productId = ProductId.random()

        scenario
            .given(
                ProductAdded(productId, "Test product", Amount.of("125.95")),
                ProductPriceChanged(productId, Amount.of("100.00"))
            )
            .when_(ChangeProductPrice(productId, Amount.of("100.00")))
            .thenExpectNoEvent()
    }

    @Test
    fun `Changing the price on an empty event stream fails`() {
        val productId = ProductId.random()
        val price = Amount.of("125.95")

        scenario
            .given()
            .when_(ChangeProductPrice(productId, price))
            .thenFailsWithException(ProductHasNotBeenAddedException(productId))
    }
}
