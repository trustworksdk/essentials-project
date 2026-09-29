# Slice: orders.cancel_order

**Kind:** command
**Status:** live
**Owner:** orders-team
**Purpose:** Cancel a previously placed order.

## Invariants
- Only a PLACED order can be cancelled (enforced by `CancelOrderDecider` via shared `OrderState`).
- Cancelling an already-cancelled order is a no-op (idempotent).

## Boundaries
**Reacts to / reads:** `CancelOrder` command (via `/api/orders/{id}/cancel`); Order stream via shared `OrderStateEvolver`
**Publishes:** `OrderCancelled` (owned by this slice)
**Forbidden:**
  - Never edit `place_order`'s Decider/API/event — this slice owns its own.
  - Never reconstruct another slice's State to call its Decider. Reading the shared `OrderStateEvolver` for THIS slice's invariant is the sanctioned pattern.

## Data
**Owns (writes):** Order stream (`OrderCancelled`)
**Reads:** Order stream via shared `OrderStateEvolver`

> Teaching example — see `orders/CLAUDE.md`.
