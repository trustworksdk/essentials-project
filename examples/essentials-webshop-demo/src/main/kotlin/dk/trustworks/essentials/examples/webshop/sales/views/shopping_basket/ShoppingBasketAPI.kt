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

package dk.trustworks.essentials.examples.webshop.sales.views.shopping_basket

import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import dk.trustworks.essentials.types.Amount
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class ShoppingBasketAPI(private val repository: ShoppingBasketLineViewRepository) {

    data class BasketLineResponse(val productId: String, val quantity: Int, val linePrice: Amount)

    data class BasketResponse(val basketId: String, val lines: List<BasketLineResponse>, val total: Amount)

    /**
     * An empty basket is an empty list, not a 404: the basket exists as soon as the shopper believes it does,
     * and nothing in the system needed to be told about it first.
     */
    @GetMapping("/api/shopping-baskets/{basketId}")
    fun basket(@PathVariable basketId: ShoppingBasketId): BasketResponse {
        val lines = repository.findByBasketId(basketId.toString())
        return BasketResponse(
            basketId = basketId.toString(),
            lines = lines.map { BasketLineResponse(it.productId, it.quantity, it.linePrice) },
            total = lines.fold(Amount.ZERO) { sum, line -> sum.add(line.linePrice) }
        )
    }
}
