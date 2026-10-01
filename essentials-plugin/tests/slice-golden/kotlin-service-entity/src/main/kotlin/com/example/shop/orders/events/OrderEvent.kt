package com.example.shop.orders.events

import com.example.shop.orders.types.OrderId

/**
 * Sealed event parent for the Orders bounded context (rules/slice-design.md §R3).
 *
 * §R3 applies on this lane **unchanged**: a sealed interface, one concrete variant per file, each
 * owned by its emitting slice. What differs is delivery — these events are published on the
 * `EventBus` as integration facts, never appended to a stream and never replayed.
 *
 * NO `@JsonTypeInfo` HERE, and that is deliberate. Here delivery is in-process and nothing is
 * serialised. Add polymorphic typing only if these events cross a **durable** boundary — and prefer
 * translating to an explicit external type in a translation slice instead.
 *
 * The serialisation trap on this lane is on the **command**, which is persisted in the durable-queue
 * table when sent with `sendAndDontWait`. See `references/llm/LLM-foundation.md` § Commands are
 * persisted.
 *
 * Kotlin sealed hierarchies must live in the same package, which is why variants sit in `events/`
 * beside this file even though each is owned by its emitting slice.
 *
 * NOTHING HERE MAY IMPORT A COMMAND TYPE (§R4). `events/` is the BC's importable surface, so a
 * command reference makes one slice's wire contract part of every foreign consumer's compile surface.
 */
sealed interface OrderEvent {
    val orderId: OrderId
}
