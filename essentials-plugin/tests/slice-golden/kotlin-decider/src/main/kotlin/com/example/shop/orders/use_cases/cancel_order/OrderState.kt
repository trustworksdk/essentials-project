package com.example.shop.orders.use_cases.cancel_order

import com.example.shop.orders.types.OrderId

/**
 * Immutable Order state, rebuilt from the event stream by [OrderStateEvolver].
 *
 * PER-SLICE BY DEFAULT — it lives in THIS slice and holds only the fields THIS Decider reads.
 * Deciding from a state nobody else shares is the normal case, not a workaround.
 *
 * Do NOT move this to `use_cases/_shared/` until THREE OR MORE Deciders need the SAME state, and
 * none of them needs a field the others do not. Two is a coincidence. A shared State gives every
 * consumer a reason to edit it and drifts toward the union of everyone's needs — the god aggregate
 * one layer down (rules/slice-design.md § The `_shared/` promotion bar).
 *
 * Promotion is a plain MOVE: both type names stay, only the package changes. So waiting costs
 * nothing, and un-sharing later costs a lot.
 *
 * Many Deciders need no state at all — an idempotency check over the raw event list is enough.
 * Delete this file and its Evolver if that is true here.
 */
data class OrderState(
    val id: OrderId,
    // TODO: the fields this BC's deciders need to decide
    val status: String
)
