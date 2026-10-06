package com.example.shop.orders.use_cases.place_order;

import com.example.shop.orders.types.OrderId;

/**
 * Command for the place_order slice — the intent, as data.
 *
 * A {@code record}: commands are immutable value objects.
 *
 * NO ROUTING MARKER INTERFACE on this lane, unlike the decider style. There the command implements
 * {@code OrderCommand} so the framework can select the aggregate's stream from the command
 * type; here the slice's handler loads the aggregate by id itself, so there is nothing for the
 * framework to route (rules/slice-design.md § Aggregate style).
 *
 * SERIALISATION — sent with {@code sendAndDontWait} (a delayed send included), this command is
 * persisted as JSON in the durable-queue table and read back later, possibly after a deploy. It is
 * then a persisted contract: its constructor parameter names are part of the JSON under Jackson 3,
 * and renaming one breaks the commands already queued. {@code send(...)} does not persist it. See
 * {@code references/llm/LLM-foundation.md} § Commands are persisted.
 */
public record PlaceOrder(
        OrderId id,
        // TODO: replace with this command's real payload
        String placeholder
) {
}
