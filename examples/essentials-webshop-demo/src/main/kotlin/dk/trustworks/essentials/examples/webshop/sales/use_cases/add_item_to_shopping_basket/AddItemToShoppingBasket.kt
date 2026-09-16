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

import dk.trustworks.essentials.examples.webshop.sales.routing.ShoppingBasketCommand
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import dk.trustworks.essentials.types.Amount

/**
 * Put one unit of a product in the basket.
 *
 * The command carries the [price] the shopper was shown. The basket is not the catalogue's judge - it records
 * what was offered, and the event keeps that price for the life of the basket. A price that has moved since the
 * page was rendered is a business question (honour it, or tell the shopper), not something to paper over by
 * looking the price up again here.
 */
data class AddItemToShoppingBasket(
    override val id: ShoppingBasketId,
    val product: ProductId,
    val price: Amount
) : ShoppingBasketCommand
