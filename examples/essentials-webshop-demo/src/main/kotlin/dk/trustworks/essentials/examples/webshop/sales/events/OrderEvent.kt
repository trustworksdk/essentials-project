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

import dk.trustworks.essentials.examples.webshop.sales.types.Address
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.PaymentMethod
import dk.trustworks.essentials.examples.webshop.sales.types.ShippingMethod

/**
 * Every event in the `Orders` event stream.
 *
 * `shipping` and `payment` both subscribe to [OrderPlaced], `shipping` also reads [ShippingDetailsAdded] and
 * [OrderCancelled]. That makes these four the published contract of the `sales` context, and the reason its
 * `events/` package is importable from elsewhere while everything under `use_cases/` is not.
 */
sealed interface OrderEvent {
    val id: OrderId
}

/**
 * Where the order should go, and how it should get there.
 *
 * Note that the shipping *address* is a `sales` concern - it is collected in the checkout flow - while what to
 * do about it is `shipping`'s. Recording it here and letting `shipping` read it off the event is the whole
 * integration: no call, no shared table.
 */
data class ShippingDetailsAdded(
    override val id: OrderId,
    val shippingAddress: Address,
    val shippingMethod: ShippingMethod
) : OrderEvent

/**
 * How the order will be paid, and where the invoice goes. No card number appears here, and none should: the
 * event stream is permanent, and a card number that should not be kept forever must not enter it.
 */
data class PaymentDetailsAdded(
    override val id: OrderId,
    val invoiceAddress: Address,
    val paymentMethod: PaymentMethod
) : OrderEvent

/**
 * The customer confirmed the order. This is the event the rest of the business waits for: `payment` places its
 * hold on it, `shipping` puts the order on its packaging list, and both learn about it without `sales` knowing
 * either of them exists.
 */
data class OrderPlaced(
    override val id: OrderId
) : OrderEvent

/**
 * The order will not be fulfilled after all, and [reason] says why - "the bank declined the card", in the case
 * this was added for.
 *
 * Cancelling does not erase anything. [OrderPlaced] stays in the stream, because it happened; this event records
 * that a later decision overrode it. A status column would have lost that, and with it the answer to "how many
 * placed orders do we lose to declined cards?".
 *
 * Like [OrderPlaced] it is a bare fact, and each context decides for itself what it means: `shipping` drops the
 * order off its work list, and a real `payment` would release any hold it is still holding.
 */
data class OrderCancelled(
    override val id: OrderId,
    val reason: String
) : OrderEvent
