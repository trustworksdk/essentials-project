package com.acme.shop.orders.use_cases.cancel_order

import com.acme.shop.orders.events.OrderEvent
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver

/**
 * Pure left-fold `(event, state) -> state` rebuilding [OrderState] from the stream.
 *
 * Owned by THIS slice — used by its Decider via `Evolver.applyEvents(...)`. Pure: no I/O, no
 * validation, no side effects. Validation is the Decider's job; the Evolver only says what is
 * currently true.
 *
 * Moves to `use_cases/_shared/` only under the three-consumer promotion bar — see
 * [OrderState].
 *
 * The `when` is exhaustive over the sealed `OrderEvent` hierarchy, so adding a variant
 * makes the compiler point at every evolver that must consider it.
 */
class OrderStateEvolver : Evolver<OrderEvent, OrderState> {
    override fun applyEvent(event: OrderEvent, state: OrderState?): OrderState? =
        when (event) {
            // TODO: one branch per event variant this BC emits
            else -> state
        }
}
