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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.cancel_order

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.sales.events.OrderCancelled
import dk.trustworks.essentials.examples.webshop.sales.events.OrderEvent
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import org.springframework.stereotype.Service

/**
 * Both rules this decider can enforce are answered from the `Orders` stream, and the ones it cannot are answered
 * somewhere else on purpose.
 *
 * What it checks: the order was placed (before that there is nothing to call off - an abandoned basket is not a
 * cancelled order), and it has not been cancelled already.
 *
 * What it deliberately does **not** check:
 *
 * - **that the payment was declined.** That fact lives in `payment`'s stream, which this decider cannot see and
 *   must not be given. The shop page only offers the button on a declined order, exactly as the warehouse's
 *   to-do view - not `PackageOrderDecider` - is what keeps unpayable orders from being packed. A guard that
 *   needs another context's data belongs in a read model, where it is allowed to be a moment stale, and never in
 *   a decider, where staleness would be a correctness bug.
 * - **that the order has not already shipped.** `shipping` owns that, on its own stream. Cancelling a shipped
 *   order is a real business situation (a return, a recall), not a contradiction to be rejected here.
 */
@Service
class CancelOrderDecider : Decider<CancelOrder, OrderEvent> {

    override fun handle(cmd: CancelOrder, events: List<OrderEvent>): OrderCancelled? {
        if (events.any { it is OrderCancelled }) {
            return null   // already cancelled - a double-clicked button, not a second cancellation
        }
        if (events.none { it is OrderPlaced }) {
            throw OrderCannotBeCancelledException(cmd.id, "it has not been placed")
        }
        return OrderCancelled(cmd.id, cmd.reason)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is CancelOrder
}
