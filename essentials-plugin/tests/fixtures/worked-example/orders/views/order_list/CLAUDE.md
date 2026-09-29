# Slice: orders.order_list

**Kind:** view
**Status:** live
**Owner:** orders-team
**Purpose:** List all orders with their current status.

## Boundaries
**Reacts to / reads:** `OrderPlaced`, `OrderCancelled` (the BC's public events in `orders/events/`)
**Serves:** `GET /api/orders`
**Forbidden:**
  - Read only the BC's public `events/` — never another slice's Decider/State.
  - This view owns its own read model + endpoint; it is not bolted onto a command slice's controller.

## Data
**Owns (writes):** `OrderListView` read model (in-memory in this example; use DocumentDb/JDBI in production)
**Reads:** Order event stream (projected)

> Teaching example — the projection's subscription/store wiring is illustrative; see
> `OrderListProjection.kt` and the `essentials-docs` skill for `InTransactionEventProcessor`
> vs `ViewEventProcessor` and the read store.
