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
**Publishes:** `OrderCancelled` (owned by this slice; defined in `orders/events/OrderCancelled.kt`)
**Forbidden:**
  - Never import another slice's internals — only `orders/events/` and `orders/types/`. Use the
    this slice's own Evolver for state; never reach into another slice's Decider or Evolver.
  - Never add this command to a shared Decider, controller, or event file — this slice owns
    `CancelOrderDecider`, `CancelOrderAPI`, and the `OrderCancelled` variant.

## Data
**Owns (writes):** Order event stream (this slice writes `OrderCancelled`)
**Reads:** Order event stream via the shared Evolver

## Files
- `CancelOrder.kt` — command (implements `OrderCommand`)
- `CancelOrderDecider.kt` — `Decider<CancelOrder, OrderEvent>` (this slice's decision logic)
- `CancelOrderAPI.kt` — single-method `POST /api/orders/cancel`
- event: `orders/events/OrderCancelled.kt`
- test: `CancelOrderTest.kt` (GivenWhenThenScenario)

## Wiring
Registered as a `@Bean` in `orders/config/OrdersConfiguration.kt`. An unregistered Decider passes
every unit test and fails every `@SpringBootTest`.
