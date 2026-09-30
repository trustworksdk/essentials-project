package com.acme.shop.orders.events;

import com.acme.shop.orders.types.OrderId;

/**
 * Event variant emitted by the place_order slice. One variant per file (rules/slice-design.md §R3) —
 * §R3 applies on this lane unchanged; what differs is where the event *goes*.
 *
 * On the service-entity style this is published on the {@code EventBus} as an **integration fact**:
 * something other slices and other bounded contexts react to. It is never appended to a stream and
 * never replayed to reconstruct state — the row is the state.
 *
 * NO POLYMORPHIC TYPING NEEDED. The event-sourced lanes annotate variants with {@code @JsonTypeInfo}
 * / {@code @JsonTypeName}. Here delivery is in-process and nothing is serialised, so the annotations are noise. Add them only if
 * this event crosses a **durable** boundary — and prefer not to: translate to an explicit external
 * type in a translation slice first (`external_systems/<sys>/`), which keeps the internal event free
 * to change. The serialisation trap on this lane lives on the *command*, not here.
 *
 * Owned by the emitting slice even though it sits in the BC's shared `events/` package — that is the
 * §R3 arrangement, and `events/` is the BC's importable surface (§R4).
 *
 * It takes the FIELDS it needs. It must never name {@link PlaceOrder} or any other command type:
 * `events/` is importable across bounded contexts, so a command reference here drags one slice's
 * wire contract into every foreign consumer (§R4).
 */
public record OrderPlaced(
        OrderId id,
        // TODO: replace with the facts this event carries
        String placeholder
) implements OrderEvent {
}
