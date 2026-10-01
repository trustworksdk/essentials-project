# Slice: orders.cancel_order

**Kind:** command
**Status:** live
**Owner:** orders-team
**Purpose:** TODO one sentence, present tense.

## Invariants
- TODO the rule this slice enforces (enforced by `CancelOrderDecider`).
- Re-issuing CancelOrder once OrderCancelled exists is a no-op (idempotent).

## Boundaries
**Reacts to / reads:** `CancelOrder` (via `POST /api/orders/cancel` → CommandBus)
**Publishes:** `OrderCancelled` (owned by this slice; defined in `orders/events/OrderCancelled.java`)
**Forbidden:**
  - Never import another slice's internals — only `orders/events/` and `orders/types/`. Use the
    this slice's own Evolver for state; never reach into another slice's Decider or Evolver.
  - Never add this command to a shared Decider, controller, or event file — this slice owns
    `CancelOrderDecider`, `CancelOrderAPI`, and the `OrderCancelled` variant.

## Data
**Owns (writes):** Order event stream (this slice writes `OrderCancelled`)
**Reads:** Order event stream via the shared Evolver

## Files
- `CancelOrder.java` — command (implements `OrderCommand`)
- `CancelOrderDecider.java` — `EventStreamDecider<CancelOrder, OrderEvent>` (this slice's decision logic)
- `CancelOrderAPI.java` — single-method `POST /api/orders/cancel`
- event: `orders/events/OrderCancelled.java`
- test: `CancelOrderTest.java` (GivenWhenThenScenario)

## Java sealed mechanics
`OrderCancelled` is listed in the `permits` clause of `orders/events/OrderEvent.java`. Appending
that one name is the single sanctioned cross-slice edit in the slice law — it is a declaration-list
change, not a change to another slice's decision-making. Do not drop `sealed` to avoid it.

## Wiring
Registered as a `@Bean` in `orders/config/OrdersConfiguration.java`. An unregistered Decider passes
every unit test and fails every `@SpringBootTest`.
