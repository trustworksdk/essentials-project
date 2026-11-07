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

import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator
import dk.trustworks.essentials.types.CharSequenceType

/**
 * Identifier for an order.
 *
 * This is the id three bounded contexts agree on: `sales` mints it at checkout, `shipping` uses it as the stream
 * id of its own `ShippingOrders` aggregate, and `payment` as the stream id of its `CreditCardHolds`. They share
 * the id rather than a lookup table, which is what lets each context own its own stream for the same order
 * without asking anyone for a translation.
 *
 * Sharing an id across contexts is the coupling being accepted deliberately. Sharing the *type* is legal because
 * `types/` is an exported package; injecting another context's write side would not be.
 */
class OrderId : CharSequenceType<OrderId> {
    constructor(value: CharSequence) : super(value)
    constructor(value: String) : super(value)

    companion object {
        @JvmStatic
        fun of(value: CharSequence): OrderId = OrderId(value)

        @JvmStatic
        fun random(): OrderId = OrderId(RandomIdGenerator.generate())
    }
}
