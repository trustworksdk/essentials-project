# Slice: orders.order_list

**Kind:** view
**Status:** live
**Owner:** orders-team
**Purpose:** List orders with their current status, all of them or by status.

## Boundaries
**Reacts to / reads:** `OrderPlaced`, `OrderCancelled` (the BC's public events in `orders/events/`)
**Serves:** `GET /api/orders`, `GET /api/orders?status=…`
**Forbidden:**
  - Read only the BC's public `events/` — never another slice's Decider/State.
  - This view owns its own read model + endpoint; it is not bolted onto a command slice's controller.

## Data
**Owns (writes):** `OrderListView` read model (PostgreSQL DocumentDB, `orders_order_list`)
**Reads:** Order event stream, through `OrderListProjection` (a `ViewEventProcessor`)

> Teaching example — see `orders/CLAUDE.md`.
