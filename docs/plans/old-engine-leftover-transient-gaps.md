# Investigation: transient gap rows the old engine leaves behind

**Status:** open, to be investigated (noted 2026-11-17). Nothing is decided and nothing has been changed.
**Scope:** the existing event store, `components/postgresql-event-store` (`PostgresqlEventStore` and its durable
subscriptions). Reading and reproducing are fine; any fix to that module needs the maintainer's go-ahead first, because
the event-store redesign builds a new engine and leaves the existing one unchanged.

## What was seen

The phase-9 migration gate (`docs/plans/event-store-engine-phase9.md` §17.3-17.4) ran the trading demo on the old
engine, with CDC on, and imported its subscriptions' resume points. Some subscribers had rows in
`transient_subscriber_gaps` for gaps whose events existed in the event table, and had already been handled:

- `AccountActivityProjection` had filled gaps at `global_order` 4385 and 4386. Both are `FundsHeld` events with more
  than 130 later events in their streams, and both have their row in `projection_account_activity`, a table only that
  projection writes. The gap rows survived a graceful stop.
- In the gate's run, `AccountActivityProjection` had 4 such filled gaps (of 1 998 open), `UnclearedDepositsProjection`
  3 (of 1 953) and three `Orders` views 1 each. A smaller rebuild found 2, 3 and 5 in three `Accounts` views.
- Only view processors were affected, never routers or event processors. Every other open gap matched a real hole in
  the event tables.

## What is known about the old engine's gap handling

Read only, from the gate agent's reading of the code:

- A transient gap row is deleted when a poll whose query included the gap finds the event, when the subscriber
  acknowledges a delivered gap fill inside its unit of work, or on a reset.
- A gap is promoted to permanent only once it is older than 120 s and a poll asked for it (at most 50 promotions per
  poll).
- Under CDC, recording a gap is deferred to the next resume-point save. This is the first suspect: a gap recorded late
  could be written after its fill has already been delivered, and then nothing removes it.

The path that leaves the row was not found.

## Why it matters

- The phase-9 import restarts each subscription at the lowest open transient gap that has an event, so it skips
  nothing (invariant I8). Leftover rows make that restart point far too low: about 109 000 already-handled events
  replayed into `AccountActivityProjection`, and about 43 000 into each of three `Orders` views. The maintainer chose to
  keep the safe value by default and add a per-mapping `trustOldResumePoint` opt-in (decision `QP-4b2431c225`).
- In the released engine the rows may cost repeated gap queries, and may hide a real ordering bug between CDC delivery,
  gap recording and the resume-point save.

## Suggested steps

1. Reproduce with the trading demo or a smaller old-engine IT: CDC on, a view processor, concurrent appenders that
   produce late commits; then check `transient_subscriber_gaps` against the view's table after a graceful stop.
2. Repeat with CDC off, to confirm or rule out the deferred gap recording.
3. Trace the order of gap recording, fill delivery and gap deletion under CDC, and find the window where a row is
   written after its fill was delivered.
4. Report the cause and a proposed fix to the maintainer before changing `postgresql-event-store`.
