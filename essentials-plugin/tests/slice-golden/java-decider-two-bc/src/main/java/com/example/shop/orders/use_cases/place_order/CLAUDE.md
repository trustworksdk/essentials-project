# Slice: orders.place_order

**Kind:** command
**Status:** live
**Owner:** orders-team
**Purpose:** TODO one sentence, present tense.

## Invariants
- TODO the rule this slice enforces (enforced by `PlaceOrderDecider`).
- Re-issuing PlaceOrder once OrderPlaced exists is a no-op (idempotent).

## Boundaries
**Reacts to / reads:** `PlaceOrder` (via `POST /api/orders` → CommandBus)
**Publishes:** `OrderPlaced` (owned by this slice; defined in `orders/events/OrderPlaced.java`)
**Forbidden:**
  - Never import another slice's internals — only `orders/events/` and `orders/types/`. Use the
    this slice's own Evolver for state; never reach into another slice's Decider or Evolver.
  - Never add this command to a shared Decider, controller, or event file — this slice owns
    `PlaceOrderDecider`, `PlaceOrderAPI`, and the `OrderPlaced` variant.

## Data
**Owns (writes):** Order event stream (this slice writes `OrderPlaced`)
**Reads:** Order event stream via the shared Evolver

## Files
- `PlaceOrder.java` — command (implements `OrderCommand`)
- `PlaceOrderDecider.java` — `EventStreamDecider<PlaceOrder, OrderEvent>` (this slice's decision logic)
- `PlaceOrderAPI.java` — single-method `POST /api/orders`
- event: `orders/events/OrderPlaced.java`
- test: `PlaceOrderTest.java` (GivenWhenThenScenario)

## Java sealed mechanics
`OrderPlaced` is listed in the `permits` clause of `orders/events/OrderEvent.java`. Appending
that one name is the single sanctioned cross-slice edit in the slice law — it is a declaration-list
change, not a change to another slice's decision-making. Do not drop `sealed` to avoid it.

## Wiring
Registered as a `@Bean` in `orders/config/OrdersConfiguration.java`. An unregistered Decider passes
every unit test and fails every `@SpringBootTest`.
