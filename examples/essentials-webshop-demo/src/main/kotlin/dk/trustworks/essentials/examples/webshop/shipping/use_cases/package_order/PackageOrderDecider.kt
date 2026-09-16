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

package dk.trustworks.essentials.examples.webshop.shipping.use_cases.package_order

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderPackagingRequested
import dk.trustworks.essentials.examples.webshop.shipping.events.ShippingOrderEvent
import org.springframework.stereotype.Service

/**
 * Packaging is requested once per order. Asking twice - two warehouse staff picking the same line off the list -
 * records nothing the second time.
 *
 * Note what this decider does *not* check: that the order was actually placed. It cannot, because "was it
 * placed?" is a fact in `sales`' stream, and a decider only ever sees its own. What makes that safe is the
 * to-do view: an order appears on the packaging list because `OrderPlaced` was projected into it, so the
 * command is only ever offered for orders that exist and are placed. The read model is the guard, and the
 * alternative - calling into `sales` from here - is the coupling this design is avoiding.
 */
@Service
class PackageOrderDecider : Decider<PackageOrder, ShippingOrderEvent> {

    override fun handle(cmd: PackageOrder, events: List<ShippingOrderEvent>): OrderPackagingRequested? {
        if (events.any { it is OrderPackagingRequested }) {
            return null
        }
        return OrderPackagingRequested(cmd.id)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is PackageOrder
}
