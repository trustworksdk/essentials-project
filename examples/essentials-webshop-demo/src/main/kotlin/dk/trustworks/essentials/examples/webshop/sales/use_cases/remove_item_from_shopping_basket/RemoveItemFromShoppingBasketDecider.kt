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

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver
import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.events.ItemRemovedFromShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ShoppingBasketEvent
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketAlreadyCheckedOutException
import org.springframework.stereotype.Service

/**
 * Removing an item needs state, not just a scan: "is this product in the basket at all?" is the answer to a fold
 * over every add and remove that came before.
 *
 * So the decider asks an [Evolver] for it. `Evolver.applyEvents` starts from `emptyMap()` and applies the stream
 * in order, and the decider then makes a decision from the result. Still a pure function of
 * `(command, events)` - the fold is part of the decision, not a trip to a database.
 *
 * Removing a product the basket does not hold returns no event. It is not an error: the shopper clicked twice,
 * or a retry arrived, and the basket is already in the state they asked for.
 */
@Service
class RemoveItemFromShoppingBasketDecider : Decider<RemoveItemFromShoppingBasket, ShoppingBasketEvent> {

    override fun handle(
        cmd: RemoveItemFromShoppingBasket,
        events: List<ShoppingBasketEvent>
    ): ItemRemovedFromShoppingBasket? {
        if (events.any { it is CheckOutRequested }) {
            throw ShoppingBasketAlreadyCheckedOutException(cmd.id)
        }

        val lines = Evolver.applyEvents(basketLinesEvolver(), emptyMap(), events)
        val unitPrices = lines[cmd.product] ?: emptyList()

        // The unit coming out is the one that went in last, and the event records the price it went in at, so
        // no reader has to work that out again.
        return unitPrices.lastOrNull()?.let { ItemRemovedFromShoppingBasket(cmd.id, cmd.product, it) }
    }

    override fun canHandle(cmd: Any): Boolean = cmd is RemoveItemFromShoppingBasket
}
