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

package dk.trustworks.essentials.examples.webshop.sales.routing

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId

/**
 * Marks a command as addressing a `ShoppingBasket`. See [ProductCommand] for why these marker interfaces exist
 * and why they are not sealed.
 */
interface ShoppingBasketCommand {
    val id: ShoppingBasketId
}

/**
 * Marks a command as addressing an `Order` in the `sales` context.
 *
 * `shipping` and `payment` have their own command interfaces over the same [OrderId], because they write their
 * own streams about the same order. One id, three aggregate types, three consistency boundaries.
 */
interface OrderCommand {
    val id: OrderId
}
