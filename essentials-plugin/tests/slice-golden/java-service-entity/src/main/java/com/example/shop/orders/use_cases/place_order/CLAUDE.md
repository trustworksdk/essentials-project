# Slice: orders.place_order

**Kind:** command
**Lane:** service-entity (`rules/slice-design.md` §R5) — state-stored entity, no event store
**Status:** live
**Owner:** orders-team
**Purpose:** TODO one sentence, present tense.

## Invariants
- TODO the rule this slice enforces. It lives on `Order`, not in the handler — the entity is the
  consistency boundary on this lane.
- The change is idempotent: applying it twice changes the row once and publishes one event.

## Boundaries
**Handles:** `PlaceOrder` from the `CommandBus`
**Writes:** the `Order` entity, through `orders/entities/OrderRepository`
**Publishes:** `OrderPlaced` on the `EventBus` (an integration fact — never appended to a stream)
**Serves:** `POST /api/orders`
**Forbidden:**
  - Never import another slice's internals — only `orders/events/` and `orders/types/`. Naming another
    slice's *command type* is allowed only to dispatch it on the command bus.
  - Never put a domain rule in the handler. Load, call the one entity method, save, publish.
  - Never return the `Order` from the API — it is a managed, mutable object, and returning it
    makes the whole write model your wire contract. A query belongs to a view slice.
  - Never add a second `@CmdHandler` method here. A second command is a second slice.

## Data
**Owns (writes):** the `Order` row. Gate 4 expects exactly one command slice per writer, and on
this lane that is a convention rather than a framework guarantee — `save()` is callable from anywhere.

## Files
- `PlaceOrder.java` — the command; it **is** the request body (§R2)
- `PlaceOrderHandler.java` — `@CmdHandler`, one command type. Auto-registered with the `CommandBus` by
  `ReactiveHandlersBeanPostProcessor`; there is no `@Bean` to write, only a wiring check
- `PlaceOrderAPI.java` — `POST /api/orders`, one mapping
- `orders/events/OrderPlaced.java` — the event variant, one per file (§R3)
- tests: `PlaceOrderTest.java` (entity invariant, pure — no Spring) and `PlaceOrderIT.java` (through the
  `CommandBus`, asserting the event was published)

## Serialisation
This lane inverts the usual advice: **the command is the serialised artefact**, not the event. The
event stays in-process on the `EventBus`; `PlaceOrder` sent with `sendAndDontWait` (a delayed send
included) is persisted as JSON in the durable-queue table and read back later, possibly after a
deploy. Its constructor parameter names are therefore part of the JSON contract, and renaming one
breaks the commands already queued; `send(...)` does not persist it — see
`references/llm/LLM-foundation.md` § Commands are persisted.

If `PlaceOrder` carries a mutable value object that `Order` keeps, **defensive-copy it**. The
entity outlives the command and would otherwise share the reference.

## Wiring
Nothing to write. Confirm the handler is a Spring bean in a scanned package, and that
`reactive-bean-post-processor-enabled` (default `true`) is not switched off — disabling it silently
unwires every handler in the application.
