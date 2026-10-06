# Slice: orders.screen_order

**Kind:** automation
**Status:** live
**Owner:** orders-team
**Purpose:** Cancel a placed order whose quantity is over the screening limit.

## Invariants
- An order is cancelled by screening at most once (`ScreenOrderTodo.mayRequestCancel()`).

## Boundaries
**Reacts to:** `OrderPlaced`, `OrderCancelled` (the BC's public events in `orders/events/`)
**Dispatches:** `CancelOrder`, by constructing `cancel_order`'s command and sending it on the command bus
**Forbidden:**
  - No API. An automation is reached only by events.
  - Never call `CancelOrderDecider` or read its state; send the command instead.

## Data
**Owns (writes):** `ScreenOrderTodo` process state
**Reads:** Order event stream

> Teaching example — see `orders/CLAUDE.md`.
