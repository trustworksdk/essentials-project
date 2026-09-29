# Slice: orders.place_order

**Kind:** command
**Status:** live
**Owner:** orders-team
**Purpose:** Place a new order for a SKU and quantity.

## Invariants
- Quantity must be positive (enforced by `PlaceOrderDecider`).
- Placing an already-placed order is a no-op (idempotent).

## Boundaries
**Reacts to / reads:** `PlaceOrder` command (via `/api/orders` POST → CommandBus)
**Publishes:** `OrderPlaced` (owned by this slice; defined in `orders/events/OrderPlaced.kt`)
**Forbidden:**
  - Never import another slice's internals — only `orders/events/` and `orders/types/`. This Decider needs no folded state at all; if it ever does, add its own `OrderStateEvolver` here. Never reach into `cancel_order`'s Decider or its Evolver.
  - Never add this command to a shared Decider/controller/event file — this slice owns `PlaceOrderDecider`, `PlaceOrderAPI`, and the `OrderPlaced` variant.

## Data
**Owns (writes):** Order aggregate stream (shared aggregate; this slice writes `OrderPlaced`)
**Reads:** Order event stream via the shared `OrderStateEvolver`

## Files
- `PlaceOrder.kt` — command (implements `OrderCommand`)
- `PlaceOrderDecider.kt` — `Decider<PlaceOrder, OrderEvent>` (this slice's decision logic)
- `PlaceOrderAPI.kt` — single-method `POST /api/orders`
- event: `orders/events/OrderPlaced.kt`

> Teaching example. The `orders` BC demonstrates the per-slice / anti-god-class structure
> and the standard Essentials Decider/Evolver design — replace it with your real BC.
