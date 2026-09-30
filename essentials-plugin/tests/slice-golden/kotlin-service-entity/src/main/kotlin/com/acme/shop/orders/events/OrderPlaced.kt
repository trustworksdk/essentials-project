package com.acme.shop.orders.events

import com.acme.shop.orders.types.OrderId

/**
 * Event variant emitted by the place_order slice. One variant per file (rules/slice-design.md §R3) —
 * §R3 applies on this lane unchanged; what differs is where the event *goes*.
 *
 * On the service-entity style this is published on the `EventBus` as an **integration fact**. It is
 * never appended to a stream and never replayed to reconstruct state — the row is the state.
 *
 * NO POLYMORPHIC TYPING NEEDED. The event-sourced lanes annotate variants with `@JsonTypeInfo` /
 * `@JsonTypeName`. Here delivery is in-process and nothing is serialised, so the annotations are noise. Add them only if this event
 * crosses a **durable** boundary — and prefer not to: translate to an explicit external type in a
 * translation slice first, which keeps the internal event free to change. The serialisation trap on
 * this lane lives on the *command*.
 *
 * It takes the FIELDS it needs. It must never name `PlaceOrder` or any other command type:
 * `events/` is importable across bounded contexts, so a command reference here drags one slice's
 * wire contract into every foreign consumer (§R4).
 */
data class OrderPlaced(
    override val orderId: OrderId,
    // TODO: replace with the facts this event carries
    val placeholder: String
) : OrderEvent
