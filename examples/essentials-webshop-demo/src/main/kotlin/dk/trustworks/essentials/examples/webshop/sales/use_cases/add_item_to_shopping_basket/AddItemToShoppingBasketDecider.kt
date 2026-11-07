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

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.events.ItemAddedToShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ShoppingBasketEvent
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketAlreadyCheckedOutException
import org.springframework.stereotype.Service

/**
 * Adding an item is almost unconditional - a basket starts existing the moment something goes into it, so there
 * is nothing to create first and no "basket not found".
 *
 * The one rule: once checkout has been requested, the basket is closed. Its total has already been quoted to the
 * order and to the payment hold, so a late line would make those figures wrong.
 *
 * Note what is *not* here: no uniqueness check, no quantity cap, no total recount. Adding the same product twice
 * is two facts, not a conflict, and quantity is something readers derive by folding the stream.
 */
@Service
class AddItemToShoppingBasketDecider : Decider<AddItemToShoppingBasket, ShoppingBasketEvent> {

    override fun handle(
        cmd: AddItemToShoppingBasket,
        events: List<ShoppingBasketEvent>
    ): ItemAddedToShoppingBasket? {
        if (events.any { it is CheckOutRequested }) {
            throw ShoppingBasketAlreadyCheckedOutException(cmd.id)
        }
        return ItemAddedToShoppingBasket(cmd.id, cmd.product, cmd.price)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is AddItemToShoppingBasket
}
