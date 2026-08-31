# Slice: market_data.risk_approve_instrument

**Kind:** automation   **Status:** live   **Owner:** market-data-team
**Purpose:** Send every newly registered instrument through an external risk assessment and record the
answer on the instrument.

This is the demo's example of `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)` — a message handler that
performs blocking I/O against an external system without holding a database connection while it waits.

## Why the handler is `UnitOfWorkMode.NONE`

A `@MessageHandler` method runs inside a `UnitOfWork` by default, and a `UnitOfWork` is what checks out a
pooled connection and opens a transaction. A handler that blocks on an external system therefore parks a
connection in `idle in transaction` for the whole call — one per consumer thread, for as long as the remote
system takes, with nothing being written in the meantime. That is how a healthy application runs out of
connections while its database is idle.

`NONE` moves the boundary inside the method:

```java
@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
void on(InstrumentRegistered e) {
    var assessment = riskAssessmentGateway.assess(e.instrumentId(), e.symbol());  // no UnitOfWork, no connection

    usingUnitOfWork(() -> recordDecision(e.instrumentId(), assessment));          // the transactional tail
}
```

`usingUnitOfWork` / `withUnitOfWork` come from `AbstractEventProcessor`. Between the two statements there is
no ambient `UnitOfWork`, so touching a transactional resource there fails fast instead of quietly opening a
transaction — loading the instrument *before* the call would be that mistake.

## What `NONE` costs, and how this slice pays it

| Consequence | How this slice handles it |
|---|---|
| The blocking call is no longer part of the transaction that acknowledges the message, so a failure in the tail redelivers the message and repeats the call | `Instrument.recordRiskApproval` / `recordRiskRejection` apply nothing once a decision exists, so the second attempt leaves the stream as the first left it. The stub also decides deterministically per symbol, so the repeat call reaches the same answer |
| The call must finish well inside the DurableQueues message-handling timeout (30s by default), or the message is reset as stuck and redelivered while this attempt is still blocked | `trading-demo.risk-approval.latency` is 500ms by default, two orders of magnitude below the timeout. Raising it past the timeout is how you observe the failure mode |
| Ordering degrades on such a timeout, because a stuck-message reset can hand the same `OrderedMessage` key to another thread | Nothing here depends on ordering: one instrument gets one decision, and the guard above makes a duplicate attempt harmless |

A `ViewEventProcessor` rejects `NONE` handlers outright — it handles each message in a single `UnitOfWork` so
that the view update and the acknowledgement commit together. That is why this slice is an `EventProcessor`
and why the risk state is projected by `views/instrument_details` rather than written here.

## The risk service is a stub

`RiskAssessmentGateway` sleeps for the configured latency and then decides from the symbol. What the handler
needs demonstrated is a call that occupies its thread for a stretch of wall-clock time it does not control
and that is not a database operation; an HTTP round trip would behave identically from the handler's point of
view and would only add a dependency and a port to the demo. Swapping in a real client is a change to that
one class.

Symbols listed in `trading-demo.risk-approval.rejected-symbols` are refused, so the rejection path can be
demonstrated without a random outcome.

## Boundaries

**Reacts to / reads:** `InstrumentRegistered`, from the `Instruments` `AggregateType`.
**Publishes:** `InstrumentRiskApproved`, `InstrumentRiskRejected`.
**Owns (writes):** the `Instrument` aggregate stream. Loads and mutates, so no `save` — the `UnitOfWork` that
the handler opened persists the applied events on commit.
**Endpoint:** none. An automation has no external API; the outcome is observable through
`views/instrument_details`.

## Rejection is not suspension

`InstrumentRiskRejected` and `InstrumentSuspended` are separate events on purpose. A suspension is a
deliberate decision to stop trading an instrument that *was* cleared; a rejection records that it never was.
Collapsing them would leave the read model unable to tell the two apart.
