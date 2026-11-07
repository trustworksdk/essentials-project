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

import dk.trustworks.essentials.examples.webshop.sales.events.ItemRemovedFromShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class RemoveItemFromShoppingBasketAPI(private val commandBus: CommandBus) {

    @DeleteMapping("/api/shopping-baskets/{basketId}/items/{productId}")
    fun removeItem(
        @PathVariable basketId: ShoppingBasketId,
        @PathVariable productId: ProductId
    ): ResponseEntity<Void> {
        val event: ItemRemovedFromShoppingBasket? =
            commandBus.send(RemoveItemFromShoppingBasket(basketId, productId))
        // Removing something that is not in the basket is a no-op, not a 404: the basket already looks the way
        // the caller asked for.
        return if (event != null) ResponseEntity.noContent().build() else ResponseEntity.ok().build()
    }
}
