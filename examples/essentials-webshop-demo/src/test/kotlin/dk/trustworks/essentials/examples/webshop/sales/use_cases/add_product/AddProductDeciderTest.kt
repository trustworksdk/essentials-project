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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.add_product

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.sales.events.ProductAdded
import dk.trustworks.essentials.examples.webshop.sales.events.ProductEvent
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.types.Amount
import org.junit.jupiter.api.Test

class AddProductDeciderTest {

    private val scenario = GivenWhenThenScenario<AddProduct, ProductEvent>(AddProductDecider())

    @Test
    fun `Adding a product`() {
        val productId = ProductId.random()

        scenario
            .given()
            .when_(AddProduct(productId, "Test product", Amount.of("125.95")))
            .then_(ProductAdded(productId, "Test product", Amount.of("125.95")))
    }

    @Test
    fun `Adding the same product twice results in no change`() {
        val productId = ProductId.random()

        scenario
            .given(ProductAdded(productId, "Test product", Amount.of("125.95")))
            .when_(AddProduct(productId, "Test product", Amount.of("125.95")))
            .thenExpectNoEvent()
    }
}
