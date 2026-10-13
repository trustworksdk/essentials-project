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

import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver
import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.events.ItemAddedToShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ItemRemovedFromShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ShoppingBasketEvent
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.types.Amount

/**
 * What the basket holds right now: for each product, the prices of the units in it.
 *
 * An [Evolver] is the other half of the decider pattern: `(event, state) -> state`, applied left to right over
 * the stream. It is the same shape as a projection into a database table, except the result lives for the
 * duration of one decision and is then thrown away - so the state can be exactly the question the decider needs
 * answered, and nothing else.
 *
 * This one answers two questions at once, because removing a unit needs both: *is* this product in the basket,
 * and *at what price* did the unit that is coming out go in. The list is in the order the units were added, so
 * the last one is the unit being removed.
 *
 * It is deliberately not a `ShoppingBasketState` carrying everything: `request_checkout` folds the same stream
 * into a running total instead, and neither fold has to grow to accommodate the other.
 *
 * The `when` is exhaustive over a sealed event family, so adding a fourth basket event makes this fail to
 * compile rather than quietly fold it into nothing.
 */
fun basketLinesEvolver() = Evolver<ShoppingBasketEvent, Map<ProductId, List<Amount>>> { event, state ->
    val current = state ?: emptyMap()
    when (event) {
        is ItemAddedToShoppingBasket ->
            current + (event.product to ((current[event.product] ?: emptyList()) + event.price))

        is ItemRemovedFromShoppingBasket -> {
            val remaining = (current[event.product] ?: emptyList()).dropLast(1)
            if (remaining.isEmpty()) current - event.product else current + (event.product to remaining)
        }

        // Checkout closes the basket; it does not change what is in it. The guard for that is in the decider.
        is CheckOutRequested -> current
    }
}
