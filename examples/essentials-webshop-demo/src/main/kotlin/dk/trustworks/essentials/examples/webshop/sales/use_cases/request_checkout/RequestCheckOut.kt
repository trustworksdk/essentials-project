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

import dk.trustworks.essentials.examples.webshop.sales.routing.ShoppingBasketCommand
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId

/**
 * Turn this basket into an order.
 *
 * The caller supplies the [orderId], exactly as it supplies the basket id: the browser mints it, so a retried
 * checkout addresses the same order instead of creating a second one. That is what makes the decider's
 * idempotency check possible at all - with a server-generated id, a retry would be indistinguishable from a
 * second checkout.
 */
data class RequestCheckOut(
    override val id: ShoppingBasketId,
    val orderId: OrderId
) : ShoppingBasketCommand
