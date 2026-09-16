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

package dk.trustworks.essentials.examples.webshop.sales.events

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import dk.trustworks.essentials.types.Amount

/**
 * Every event in the `ShoppingBaskets` event stream.
 */
sealed interface ShoppingBasketEvent {
    val id: ShoppingBasketId
}

/**
 * A product went into the basket, **at the price it cost at that moment**.
 *
 * Carrying the price is a modelling decision, not a convenience. The catalogue price is free to change while the
 * basket sits there; the basket's total must not change underneath the shopper, and no downstream reader should
 * have to ask the catalogue what a line cost. The fact recorded here is "this line, this price" - which is also
 * why the basket can compute its own total from its own stream, with nothing to join.
 */
data class ItemAddedToShoppingBasket(
    override val id: ShoppingBasketId,
    val product: ProductId,
    val price: Amount
) : ShoppingBasketEvent

/**
 * One unit of the product left the basket. An `ItemRemoved` for a product that was added three times leaves two.
 *
 * It carries [price] - the price the removed unit went in at - because every reader has to subtract *that*
 * figure, not today's. Without it, the basket projection and the checkout total would each have to reconstruct
 * which unit was removed, and would each get it slightly wrong when two units of one product went in at
 * different prices. The decider knows the answer at the moment of the decision, so it records it.
 */
data class ItemRemovedFromShoppingBasket(
    override val id: ShoppingBasketId,
    val product: ProductId,
    val price: Amount
) : ShoppingBasketEvent

/**
 * The shopper wants to check out, so an order is coming into existence.
 *
 * This event is the hand-over between the basket and the rest of the system: it mints [orderId] and states the
 * [total] the basket added up to at that moment. Every context downstream - `sales`' order slices, `payment`'s
 * hold, `shipping`'s packaging - learns about the order from here, and none of them has to re-add the basket.
 */
data class CheckOutRequested(
    override val id: ShoppingBasketId,
    val orderId: OrderId,
    val total: Amount
) : ShoppingBasketEvent
