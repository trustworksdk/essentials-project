package {{packagePath}}.orders.use_cases.cancel_order

import {{packagePath}}.orders.events.OrderEvent
import {{packagePath}}.orders.events.OrderPlaced
import {{packagePath}}.orders.events.OrderCancelled
import {{packagePath}}.orders.types.OrderStatus
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver

/**
 * Pure left-fold `(event, state) → state` rebuilding [OrderState] from the stream.
 * Per-slice: used by [CancelOrderDecider] via
 * `Evolver.applyEvents(...)`. Pure function: no I/O, no validation, no side effects.
 */
class OrderStateEvolver : Evolver<OrderEvent, OrderState> {
    override fun applyEvent(event: OrderEvent, state: OrderState?): OrderState? =
        when (event) {
            is OrderPlaced    -> OrderState(event.id, OrderStatus.PLACED)
            is OrderCancelled -> state?.copy(status = OrderStatus.CANCELLED)
        }
}
