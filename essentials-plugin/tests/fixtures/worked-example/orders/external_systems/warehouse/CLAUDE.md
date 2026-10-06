# Slice: orders.warehouse

**Kind:** translation (outbound)
**Status:** live
**Owner:** orders-team
**Purpose:** Reserve stock in the warehouse system for every placed order.

## Boundaries
**Reacts to:** `OrderPlaced`
**Calls out:** the warehouse, through `WarehouseClient`, with a `WarehouseReservationRequest`
**Forbidden:**
  - The warehouse's types never leave this directory. `WarehouseTranslator` is the only place they are built.
  - Nothing calls in. An inbound half would be a webhook in this directory, not an endpoint of the BC.

## Data
**Owns:** nothing persisted
**Reads:** Order event stream

> Teaching example — see `orders/CLAUDE.md`.
