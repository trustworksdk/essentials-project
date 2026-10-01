# Slice: orders.billing

**Kind:** translation
**Status:** live
**Owner:** orders-team
**Purpose:** Anti-corruption boundary to the Billing system.

## Invariants
- No Billing type appears anywhere outside this directory. If one reaches a Decider, an
  event, or a view, the ACL has failed.
- `BillingTranslator` is pure — no Spring, no Essentials, no I/O — so the mapping is
  testable without either side running.
- Inbound commands are dispatched with `sendAndDontWait`, so they are retried; the receiving slice
  must be idempotent.

## Boundaries
**Reacts to / reads:** `InvoiceIssued` from Billing (inbound); `OrderPlaced` from
`orders/events/` (outbound)
**Dispatches:** the internal command mapped in `slice.yaml` `maps`
**External API:** none of its own — `/webhooks/billing` is Billing's ingress
**Forbidden:**
  - Never import another slice's internals — only `orders/events/` and `orders/types/`.
  - Never let an external wire type cross out of this directory.

## Data
**Owns (writes):** nothing — a translation slice holds no state
**Reads:** nothing internal

## Files
- `BillingTranslator.java` — the pure mapping (both directions)
- `BillingClient.java` — typed outbound port
- `BillingClientAdapter.java` — the transport behind the port; its `send` throws until you implement it; delete if `direction: inbound`
- `OnInvoiceIssued.java` — inbound ingress → CommandBus (Inbox); delete if `direction: outbound`
- `BillingPublisher.java` — outbound `EventProcessor` (Outbox); delete if `direction: inbound`
- test: `BillingTranslatorTest.java`

## Direction
`both` by default. Delete the half you do not need and set `direction` in `slice.yaml` to match.
