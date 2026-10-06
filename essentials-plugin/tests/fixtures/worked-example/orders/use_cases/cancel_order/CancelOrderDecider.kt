package {{packagePath}}.orders.use_cases.cancel_order

import {{packagePath}}.orders.events.OrderEvent
import {{packagePath}}.orders.events.OrderCancelled
import {{packagePath}}.orders.types.OrderStatus
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver

/**
 * A SECOND command slice. Note what it does NOT do: it does not edit
 * `PlaceOrderDecider`, the `OrderPlaced` event, or `PlaceOrderAPI`. Each command is
 * an independent slice with its own Decider — this is the per-slice design that
 * keeps worktrees from colliding on a shared god-Decider.
 *
 * It folds the stream into [OrderState] with its OWN [OrderStateEvolver], living in
 * this slice. That is the DEFAULT (rules/slice-design.md § The `_shared/` promotion
 * bar) — note that `PlaceOrderDecider` needs no state at all, so there is nothing to
 * share yet. `use_cases/_shared/` would only be justified at THREE Deciders needing
 * this same state, and promotion is then a plain move that keeps both names.
 */
class CancelOrderDecider : Decider<CancelOrder, OrderEvent> {
    private val evolver = OrderStateEvolver()

    override fun handle(cmd: CancelOrder, events: List<OrderEvent>): OrderEvent? {
        val state: OrderState? = Evolver.applyEvents(evolver, null, events)
        requireNotNull(state) { "Order does not exist" }
        if (state.status == OrderStatus.CANCELLED) return null      // idempotent
        require(state.canBeCancelled()) { "Cannot cancel order in ${state.status}" }
        return OrderCancelled(cmd.id, cmd.reason)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is CancelOrder
}
