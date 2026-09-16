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

package dk.trustworks.essentials.examples.webshop.sales.types

/**
 * Rejections that more than one slice of the `sales` context can raise, and that the HTTP layer maps to a 409.
 *
 * A rejection used by a single slice stays in that slice - see `change_product_price`'s own
 * `ProductHasNotBeenAddedException`. These two are here because two slices each raise them, and a slice may not
 * reach into another slice's package.
 */
class ShoppingBasketAlreadyCheckedOutException(val basketId: ShoppingBasketId) :
    RuntimeException("Shopping basket '$basketId' has already been checked out")

class ShoppingBasketIsEmptyException(val basketId: ShoppingBasketId) :
    RuntimeException("Shopping basket '$basketId' is empty")

/**
 * Raised when an order command arrives for an order that has no `CheckOutRequested` behind it, or when
 * `PlaceOrder` arrives before the order has both shipping and payment details.
 */
class OrderIsNotReadyToBePlacedException(val orderId: OrderId, val missing: String) :
    RuntimeException("Order '$orderId' cannot be placed: $missing")
