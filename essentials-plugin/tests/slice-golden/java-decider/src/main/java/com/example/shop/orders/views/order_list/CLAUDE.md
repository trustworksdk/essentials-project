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
    breaks idempotency. Use the `long` overload: `repository.update(entity, message.getOrder())`.

## Data
**Owns (writes):** the `orders_order_list` read model — no other slice reads or writes it
**Reads:** its own read model only

## Files
- `OrderListView.java` — read model (`JavaVersionedEntity`, `version` = EventOrder). The `@Id` field is
  `public` on purpose: the reflection layer reads it as a field, and a private one dead-letters every
  message so the projection silently never populates.
- `OrderListRepositoryConfiguration.java` — `DocumentDbRepositoryFactory.createForStringId` wiring +
  this slice's queries. Named `…Configuration` on purpose: a `@Configuration` class registers a bean
  under its own decapitalised name, so calling it `OrderListRepository` would collide with its
  `orderListRepository()` `@Bean` method and fail context startup for the whole project.
- `OrderListProjection.java` — `ViewEventProcessor`; its `@MessageHandler` methods take
  `OrderedMessage` because the projection needs `message.getOrder()` for idempotency
- `OrderListAPI.java` — query API, `GET /api/orders` (add further queries over this same read model
  here rather than in a new slice; declare each in `serves` + `endpoints`)
- test: `OrderListProjectionIT.java`

## Processor choice
`ViewEventProcessor` — async, low latency, eventually consistent. Switch to
`InTransactionEventProcessor` only if this read model must be current the moment the command API
returns. Never a plain `EventProcessor` for a view.

## Persistence
DocumentDB (`JavaVersionedEntity` + `createForStringId`), which manages its own table and
indexes. A JDBI `@SqlObject` repository over a Flyway-managed table is the supported alternative —
choose it when you need hand-tuned SQL or the view must live in an existing relational schema.

## Build dependency
`postgresql-document-db` declares `kotlin-stdlib-jdk8` and `kotlin-reflect` in **`provided`** scope,
so they are not transitive. Even a pure-Java module needs both at compile time — `createForStringId`
and the `Condition` DSL expose `KClass`/`KProperty1` overloads javac must resolve. Missing them
fails the build with `cannot access kotlin.reflect.KClass`.
