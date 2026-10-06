# Slice: payments.request_payment

**Kind:** command
**Status:** live
**Owner:** payments-team
**Purpose:** TODO one sentence, present tense.

## Invariants
- TODO the rule this slice enforces (enforced by `RequestPaymentDecider`).
- Re-issuing RequestPayment once PaymentRequested exists is a no-op (idempotent).

## Boundaries
**Reacts to / reads:** `RequestPayment` (via `POST /api/payments` → CommandBus)
**Publishes:** `PaymentRequested` (owned by this slice; defined in `payments/events/PaymentRequested.java`)
**Forbidden:**
  - Never import another slice's internals — only `payments/events/` and `payments/types/`. Use the
    this slice's own Evolver for state; never reach into another slice's Decider or Evolver.
  - Never add this command to a shared Decider, controller, or event file — this slice owns
    `RequestPaymentDecider`, `RequestPaymentAPI`, and the `PaymentRequested` variant.

## Data
**Owns (writes):** Payment event stream (this slice writes `PaymentRequested`)
**Reads:** Payment event stream via the shared Evolver

## Files
- `RequestPayment.java` — command (implements `PaymentCommand`)
- `RequestPaymentDecider.java` — `EventStreamDecider<RequestPayment, PaymentEvent>` (this slice's decision logic)
- `RequestPaymentAPI.java` — single-method `POST /api/payments`
- event: `payments/events/PaymentRequested.java`
- test: `RequestPaymentTest.java` (GivenWhenThenScenario)

## Java sealed mechanics
`PaymentRequested` is listed in the `permits` clause of `payments/events/PaymentEvent.java`. Appending
that one name is the single sanctioned cross-slice edit in the slice law — it is a declaration-list
change, not a change to another slice's decision-making. Do not drop `sealed` to avoid it.

## Wiring
Registered as a `@Bean` in `payments/config/PaymentsConfiguration.java`. An unregistered Decider passes
every unit test and fails every `@SpringBootTest`.
