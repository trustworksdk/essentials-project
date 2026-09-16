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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.add_shipping_details_to_order

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.sales.events.OrderEvent
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import dk.trustworks.essentials.examples.webshop.sales.events.ShippingDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.types.OrderIsNotReadyToBePlacedException
import org.springframework.stereotype.Service

/**
 * Details may be corrected as often as the customer likes until the order is placed - each correction is its own
 * fact, and the latest one wins for every reader. Re-sending the *same* details produces no event: that is a
 * double-submitted form, not a correction.
 */
@Service
class AddShippingDetailsToOrderDecider : Decider<AddShippingDetailsToOrder, OrderEvent> {

    override fun handle(cmd: AddShippingDetailsToOrder, events: List<OrderEvent>): ShippingDetailsAdded? {
        if (events.any { it is OrderPlaced }) {
            throw OrderIsNotReadyToBePlacedException(cmd.id, "the order has already been placed")
        }
        val current = events.filterIsInstance<ShippingDetailsAdded>().lastOrNull()
        if (current != null &&
            current.shippingAddress == cmd.shippingAddress &&
            current.shippingMethod == cmd.shippingMethod
        ) {
            return null
        }
        return ShippingDetailsAdded(cmd.id, cmd.shippingAddress, cmd.shippingMethod)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is AddShippingDetailsToOrder
}
