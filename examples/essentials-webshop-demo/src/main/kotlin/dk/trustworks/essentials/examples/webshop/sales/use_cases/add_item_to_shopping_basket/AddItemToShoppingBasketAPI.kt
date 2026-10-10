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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.add_item_to_shopping_basket

import dk.trustworks.essentials.examples.webshop.sales.events.ItemAddedToShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import dk.trustworks.essentials.reactive.command.CommandBus
import dk.trustworks.essentials.types.Amount
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AddItemToShoppingBasketAPI(private val commandBus: CommandBus) {

    data class AddItemRequest(val product: ProductId, val price: Amount)

    @PostMapping("/api/shopping-baskets/{basketId}/items")
    fun addItem(
        @PathVariable basketId: ShoppingBasketId,
        @RequestBody request: AddItemRequest
    ): ResponseEntity<Void> {
        val event: ItemAddedToShoppingBasket? =
            commandBus.send(AddItemToShoppingBasket(basketId, request.product, request.price))
        // 201 when a line was added, 200 when the command was a no-op. Typing the result is also how the
        // caller learns the decider's answer at all - `send` returns whatever the decider returned.
        return if (event != null) ResponseEntity.status(201).build() else ResponseEntity.ok().build()
    }
}
