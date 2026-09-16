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

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver
import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.events.ItemAddedToShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ItemRemovedFromShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ShoppingBasketEvent
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketIsEmptyException
import dk.trustworks.essentials.types.Amount
import org.springframework.stereotype.Service

/**
 * The hand-over point of the whole flow, and the reason `kotlin-eventsourcing` lets a decision produce **one**
 * event rather than a list.
 *
 * Checkout could have been modelled as `BasketClosed` + `OrderCreated` + `TotalCalculated`, which would be three
 * implementation steps dressed up as facts. One event named after what happened in the business -
 * [CheckOutRequested], carrying the order id and the total - is the model the constraint pushes you towards, and
 * it is the better one: three contexts read this single event and none of them has to correlate a sequence.
 *
 * The total is folded from the basket's own stream, so it is the sum of the prices that were actually shown to
 * the shopper - not today's catalogue prices.
 */
@Service
class RequestCheckOutDecider : Decider<RequestCheckOut, ShoppingBasketEvent> {

    override fun handle(cmd: RequestCheckOut, events: List<ShoppingBasketEvent>): CheckOutRequested? {
        if (events.any { it is CheckOutRequested }) {
            return null   // already checked out - the retry gets the same order
        }

        val total = Evolver.applyEvents(basketTotalEvolver(), Amount.ZERO, events)
        if (total.compareTo(Amount.ZERO) <= 0) {
            throw ShoppingBasketIsEmptyException(cmd.id)
        }

        return CheckOutRequested(cmd.id, cmd.orderId, total)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is RequestCheckOut
}

/**
 * The basket's running total, folded from its own stream.
 *
 * A second evolver over the same events as `remove_item_from_shopping_basket`'s - each answering its own
 * question, neither knowing about the other. That slice needs to know which units are in the basket and what
 * they cost; this one only needs a sum. Two small folds beat one shared `ShoppingBasketState` that grows a field
 * every time a slice is added.
 *
 * Both add and remove carry the price of the unit they concern, so the sum is arithmetic over the stream rather
 * than a lookup: the total is the prices actually shown to the shopper, and a catalogue change mid-basket cannot
 * move it.
 */
private fun basketTotalEvolver() = Evolver<ShoppingBasketEvent, Amount> { event, state ->
    val current = state ?: Amount.ZERO
    when (event) {
        is ItemAddedToShoppingBasket -> current.add(event.price)
        is ItemRemovedFromShoppingBasket -> current.subtract(event.price)
        is CheckOutRequested -> current
    }
}
