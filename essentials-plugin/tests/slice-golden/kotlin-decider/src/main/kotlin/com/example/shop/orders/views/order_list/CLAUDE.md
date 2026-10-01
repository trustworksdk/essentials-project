# Slice: orders.order_list

**Kind:** view
**Status:** live
**Owner:** orders-team
**Purpose:** TODO one sentence, present tense.

## Invariants
- The projection is idempotent: an event whose `EventOrder` is not newer than the stored
  `version` is a replay and is skipped.
- This slice never produces events.

## Boundaries
**Reacts to / reads:** `OrderPlaced` from `orders/events/` (TODO list every projected event)
**Serves:** `GET /api/orders`
**Forbidden:**
  - Never import another slice's internals — only `orders/events/` and `orders/types/`.
  - Never write to the event store, call a Decider, or read another slice's repository.
  - Never call `repository.update(entity)` without a `Version` — that is CRUD auto-increment and
    breaks idempotency. Use `repository.update(entity, Version(message.order))`.

## Data
**Owns (writes):** the `orders_order_list` read model — no other slice reads or writes it
**Reads:** its own read model only

## Files
- `OrderListView.kt` — read model (`VersionedEntity`, `version` = EventOrder)
- `OrderListRepository.kt` — `DelegatingDocumentDbRepository` + this slice's queries
- `OrderListProjection.kt` — `ViewEventProcessor`; its `@MessageHandler` methods take `OrderedMessage`
  because the projection needs `message.order` for idempotency
- `OrderListAPI.kt` — query API, `GET /api/orders` (add further queries over this same read model
  here rather than in a new slice; declare each in `serves` + `endpoints`)
- test: `OrderListProjectionIT.kt`

## Processor choice
`ViewEventProcessor` — async, low latency, eventually consistent. Switch to
`InTransactionEventProcessor` only if this read model must be current the moment the command API
returns. Never a plain `EventProcessor` for a view.
