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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.place_order

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.sales.events.OrderEvent
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import dk.trustworks.essentials.examples.webshop.sales.events.PaymentDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.events.ShippingDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.types.OrderIsNotReadyToBePlacedException
import org.springframework.stereotype.Service

/**
 * The order is complete when both detail steps have happened - and the stream is where that is checked, not a
 * status column that some other code has to have remembered to update.
 *
 * `OrderPlaced` carries no payload beyond the id. Everything a reader needs is already recorded: the total came
 * with `CheckOutRequested`, the address with `ShippingDetailsAdded`, the payment method with
 * `PaymentDetailsAdded`. Copying them onto this event would create a second, competing copy of facts that are
 * already permanent.
 *
 * This is also where the single-event rule earns its keep. A `place_order` that emitted `OrderPlaced` plus
 * `PackagingRequested` plus `FundsHoldRequested` would be this slice deciding how two other contexts work.
 * Instead it records one fact, and `shipping` and `payment` each decide for themselves what it means.
 */
@Service
class PlaceOrderDecider : Decider<PlaceOrder, OrderEvent> {

    override fun handle(cmd: PlaceOrder, events: List<OrderEvent>): OrderPlaced? {
        if (events.any { it is OrderPlaced }) {
            return null   // already placed - a double-clicked button, not a second order
        }
        if (events.none { it is ShippingDetailsAdded }) {
            throw OrderIsNotReadyToBePlacedException(cmd.id, "no shipping details have been added")
        }
        if (events.none { it is PaymentDetailsAdded }) {
            throw OrderIsNotReadyToBePlacedException(cmd.id, "no payment details have been added")
        }
        return OrderPlaced(cmd.id)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is PlaceOrder
}
