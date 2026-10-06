# Slice: orders.order_list

**Kind:** view
**Lane:** service-entity (`rules/slice-design.md` §R5) — reads the entity's own table
**Status:** live
**Owner:** orders-team
**Purpose:** TODO one sentence, present tense.

## Invariants
- Read-only. This slice never writes, and never injects the BC's write repository.
- The read is **strongly consistent** — same table, same transaction as the write. That is what this
  lane buys, and it is the one thing it does better than a projection. Do not add eventual
  consistency you do not need.

## Boundaries
**Reads:** the `Order` table, through this slice's own `OrderListQueries`
**Serves:** `GET /api/orders`, `GET /api/orders/{orderId}`
**Forbidden:**
  - Never inject `OrderRepository` — that is the BC's write repository. This slice declares its
    own narrow read-only interface, which is what stops one shared repository accumulating every
    screen's finders.
  - Never return `Order` — return `OrderListView`. The entity is managed and mutable, and
    returning it makes the whole write model your wire contract.
  - Never call `save` / `delete` anywhere in this slice.
  - Never read another slice's queries or internals — only `orders/events/` and `orders/types/`.

## Data
**Owns (writes):** nothing. Unlike a projection-backed view, this slice owns a *query*, not a table.
**Reads:** the `Order` table — shared with the write side, which is why the interface is narrow.

## Files
- `OrderListView.java` — the read shape: a closed Spring Data interface projection. It **is** the
  response body, so there is no mapper and nothing to keep in sync (§R2)
- `OrderListQueries.java` — slice-private read-only queries. Extends bare `Repository`, **not**
  `JpaRepository`/`MongoRepository`, so no write method is exposed even by accident
- `OrderListAPI.java` — `GET /api/orders`. Several query methods are fine over this same shape;
  declare each in `serves` + `endpoints`
- test: `OrderListIT.java`

## What this lane does NOT have
No projector, no `OrderedMessage`, no `Version`, no `EventOrder`, no separate read-model table, and no
Flyway migration for one. `projections` in `slice.yaml` is empty and stays empty.

Consequently **§ Evolving a view slice does not apply**: there is no stream to replay, so the `_v2`
migration-twin machinery is meaningless here. Changing the read shape is an ordinary schema migration
of the write table plus an edit to `OrderListView`.
