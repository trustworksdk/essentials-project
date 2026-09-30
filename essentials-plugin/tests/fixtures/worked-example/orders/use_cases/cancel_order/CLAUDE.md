# Slice: orders.cancel_order

**Kind:** command
**Status:** live
**Owner:** orders-team
**Purpose:** Cancel a previously placed order.

## Invariants
- Only a PLACED order can be cancelled (enforced by `CancelOrderDecider` over this slice's own `OrderState`).
- Cancelling an already-cancelled order is a no-op (idempotent).

## Boundaries
**Reacts to / reads:** `CancelOrder` command (via `/api/orders/{orderId}/cancel`); Order stream via this slice's own `OrderStateEvolver`
**Publishes:** `OrderCancelled` (owned by this slice)
**Forbidden:**
  - Never edit `place_order`'s Decider/API/event — this slice owns its own.
  - Never reconstruct another slice's State to call its Decider.

## Data
**Owns (writes):** Order stream (`OrderCancelled`)
**Reads:** Order stream, folded by this slice's own `OrderStateEvolver`

> Teaching example — see `orders/CLAUDE.md`.
