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

package dk.trustworks.essentials.examples.webshop.shipping.use_cases.ship_order

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderPackagingRequested
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderShipped
import dk.trustworks.essentials.examples.webshop.shipping.events.ShippingOrderEvent
import org.springframework.stereotype.Service

/**
 * An order can only ship once it has been packed, and only once. Both rules are answered from this stream alone,
 * which is what a well-drawn consistency boundary buys: the decision needs no other context's data.
 */
@Service
class ShipOrderDecider : Decider<ShipOrder, ShippingOrderEvent> {

    override fun handle(cmd: ShipOrder, events: List<ShippingOrderEvent>): OrderShipped? {
        if (events.none { it is OrderPackagingRequested }) {
            throw OrderHasNotBeenPackagedException(cmd.id)
        }
        if (events.any { it is OrderShipped }) {
            return null
        }
        return OrderShipped(cmd.id, cmd.trackingNumber)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is ShipOrder
}
