package com.acme.shop.orders.events

import com.acme.shop.orders.types.OrderId
import com.fasterxml.jackson.annotation.JsonTypeName

/**
 * Emitted by the cancel_order slice. One variant, one file (rules/slice-design.md §R3).
 *
 * This variant is logically OWNED by `use_cases/cancel_order/` — record that in the slice's CLAUDE.md.
 * Never collect several variants into one file, and never edit another slice's variant.
 *
 * `@JsonTypeName` names this event's `@type` in its JSON; deserialization itself goes by the recorded
 * class name.
 */
@JsonTypeName("OrderCancelled")
data class OrderCancelled(
    override val id: OrderId,
    // TODO: replace with the facts this event records
    val placeholder: String
) : OrderEvent
