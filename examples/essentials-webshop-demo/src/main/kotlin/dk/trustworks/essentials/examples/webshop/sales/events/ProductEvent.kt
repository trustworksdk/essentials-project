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

import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.types.Amount

/**
 * Every event in the `Products` event stream. Sealed, so an evolver folding this stream can `when` over it
 * exhaustively and the compiler reports the branch nobody added when a new event arrives.
 *
 * `events/` is one of the two packages a bounded context exposes to its neighbours (the other is `types/`), so
 * these are part of the contract: renaming one is a persisted-data change, not a refactor. Essentials stores the
 * concrete class name with each event and provides no upcasting.
 *
 * **The constructor parameter names are part of the JSON contract**, not just naming. Jackson 3 reads parameter
 * names from the bytecode and uses the primary constructor as a properties-based creator, so a parameter named
 * differently from the property it populates deserializes as `null`. Renaming `price` to `initialPrice` below
 * would silently break every already-persisted event. Jackson 2 populates the backing fields instead - so this
 * only bites on one of the two flavours, which is why both are built.
 */
sealed interface ProductEvent {
    val id: ProductId
}

/**
 * Implemented by every event that carries a price, so the current price can be found by scanning the stream
 * backwards for the last event that has one - without knowing which event types those are.
 *
 * This is the smallest possible read of an event stream: no state class, no evolver. `ShoppingBasket` needs the
 * opposite - see the evolver in `remove_item_from_shopping_basket`.
 */
interface HasProductPrice {
    val price: Amount
}

/**
 * A product is for sale, under this name, at this price. The first event of every `Products` stream.
 */
data class ProductAdded(
    override val id: ProductId,
    val name: String,
    override val price: Amount
) : ProductEvent, HasProductPrice

/**
 * The product now sells for a different price. The old price is not overwritten anywhere - it is still in the
 * stream, at its own position, which is what makes "what did this cost on the 3rd?" answerable at all.
 */
data class ProductPriceChanged(
    override val id: ProductId,
    override val price: Amount
) : ProductEvent, HasProductPrice
