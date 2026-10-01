package com.example.shop.orders.use_cases.place_order;

import com.example.shop.orders.types.OrderId;

/**
 * Command for the place_order slice — the intent, as data.
 *
 * A {@code record}: commands are immutable value objects. Unlike the event-sourced lanes there is no
 * routing marker interface to implement — the {@code CommandBus} routes by command *type* to the
 * {@code @CmdHandler} method that accepts it, and there is no event stream to select
 * (rules/slice-design.md § Service-entity style).
 *
 * SERIALISATION — this lane inverts the usual advice. The events stay in-process on the
 * {@code EventBus}; the command is the artefact that gets serialised. Sent with
 * {@code sendAndDontWait} (a delayed send included), it is persisted as JSON in the durable-queue
 * table and read back later, possibly after a deploy — so its constructor parameter names are part of
 * the JSON contract under Jackson 3, and renaming one breaks the commands already queued.
 * {@code send(...)} does not persist it. See {@code references/llm/LLM-foundation.md}
 * § Commands are persisted.
 *
 * DEFENSIVE COPYING — if this command carries a mutable value object that the entity will hold onto,
 * copy it on the way in. The event-sourced lanes never hand a command's value object to a long-lived
 * object; here the entity outlives the command and would share the reference.
 */
public record PlaceOrder(
        OrderId id,
        // TODO: replace with this command's real payload
        String placeholder
) {
}
