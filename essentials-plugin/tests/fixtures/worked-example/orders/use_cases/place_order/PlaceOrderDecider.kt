package {{packagePath}}.orders.use_cases.place_order

import {{packagePath}}.orders.events.OrderEvent
import {{packagePath}}.orders.events.OrderPlaced
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider

/**
 * Decider for THIS slice only — the standard Essentials `Decider<COMMAND, EVENT>`
 * design (one Decider class per command). NEVER a shared Decider with a
 * `when(command)` over many commands — that is the god-Decider anti-pattern that
 * makes every slice edit the same file (rules/slice-design.md §R1).
 *
 * Pure: `handle(cmd, events) → event?`. Returns null for idempotent no-ops, throws
 * to reject. No I/O.
 */
class PlaceOrderDecider : Decider<PlaceOrder, OrderEvent> {
    override fun handle(cmd: PlaceOrder, events: List<OrderEvent>): OrderEvent? {
        if (events.any { it is OrderPlaced }) return null           // idempotent
        require(cmd.quantity > 0) { "quantity must be positive" }   // invariant — warrants a PBT
        return OrderPlaced(cmd.id, cmd.sku, cmd.quantity)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is PlaceOrder
}
