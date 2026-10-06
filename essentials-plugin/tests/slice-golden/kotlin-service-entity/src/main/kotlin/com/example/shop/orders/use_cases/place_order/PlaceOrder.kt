package com.example.shop.orders.use_cases.place_order

import com.example.shop.orders.types.OrderId

/**
 * Command for the place_order slice — the intent, as data.
 *
 * A `data class`: commands are immutable value objects. Unlike the event-sourced lanes there is no
 * routing marker interface to implement — the `CommandBus` routes by command *type* to the
 * `@CmdHandler` method that accepts it, and there is no event stream to select
 * (rules/slice-design.md § Service-entity style).
 *
 * Commands are **not** sealed: adding a command is a new slice, never an edit to a hierarchy.
 *
 * SERIALISATION — this lane inverts the usual advice. The events stay in-process on the `EventBus`;
 * the command is the artefact that gets serialised. Sent with `sendAndDontWait` (a delayed send
 * included), it is persisted as JSON in the durable-queue table and read back later, possibly after a
 * deploy — so its constructor parameter names are part of the JSON contract under Jackson 3, and
 * renaming one breaks the commands already queued. `send(...)` does not persist it. See
 * `references/llm/LLM-foundation.md` § Commands are persisted.
 *
 * DEFENSIVE COPYING — if this command carries a mutable value object the entity will hold onto, copy
 * it on the way in. The entity outlives the command and would otherwise share the reference.
 */
data class PlaceOrder(
    val id: OrderId,
    // TODO: replace with this command's real payload
    val placeholder: String
)
