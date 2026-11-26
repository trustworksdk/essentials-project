# Event log: parked aggregates retry by themselves

Status: built, steps 1-10 done (7367c9bc0..0de20325e, docs in the commit that closes it). Decided 2026-11-25: a parked aggregate is retried by the engine forever on a capped backoff
(1 s doubling to 5 min, configurable); only a `ParkEventException` stops for an operator. Per-aggregate order is
kept exactly as today.

## Today
A failing event is retried `retry-attempts` (3) times at 10/20/40 ms, then parked (`Subscription.java:1738-1786`,
`rollbackAndRetry` 2047-2085). Later events of the aggregate are held behind it. Only an operator request
(`requested = RETRY|DISCARD`, `DatabaseEventLogAdmin.request` 136-175) un-parks, served on the delivery thread by
`serveParkedRequests()` (2247): `releaseParked` (in-transaction, 2260-2417) or `releaseParkedPeriodic` (2428-2545).

## Design
- **Schema** (`EventLogSchemaContributor` 188-206, `ADD COLUMN IF NOT EXISTS` like `requested`): `retries int default 0`,
  `next_retry_at timestamptz`, `terminal boolean default false`, `last_failed_at timestamptz`. Metadata-only, no index.
- **Settings**: `parkedRetryInitial` 1 s, `parkedRetryMax` 5 min on `EventLogSettings` and per subscription in
  `ErrorSettings`; starter `essentials.event-log.parked-retry-initial` / `-max`.
- **Writes ride on existing statements**: park sets `next_retry_at`/`terminal`; failed-again advances
  `min(max, initial << retries)`; a new head restarts at `initial`. `PARKED_HEADS` returns the remaining wait from the
  database clock, so a takeover continues the schedule and node clocks do not matter.
- **Schedule in memory** (`ParkedEvents`): due-time ordered set, cached earliest due.
- **Due retries reuse the operator path**: `serveParkedRequests()` queues an AUTO `ParkedRequest` for at most
  `parkedRetryBatch` (4) due heads per pass. Zero statements while nothing is due; one serve per due head. Wait loops
  sleep until the earliest due time. Only the running owner acts (standby never serves; operator and auto requests
  share one queue; reset reloads; stop() rolls back).
- **Lease safety**: a slow retry holds the progress row that the JVM-wide `LEASE_RENEW` waits on. Either (a) gate AUTO
  serves on lease freshness, or (b) late locking: read, run the handler unlocked, then lock progress + parked rows,
  guard-check and commit, as the periodic path already does.
- **Terminal**: `ParkEventException` only (deserialization failures are plain RuntimeExceptions today).
- **Observability**: `EventLogObserver.onParkedRetry` / `onParkedHeads`; meters `…parked.heads{state}`,
  `…parked.retries{origin,outcome}`, `…parked.oldest.age`.
- **Admin**: bulk retry-all / discard-all (SPI, `EssentialsAdminApiSpec`, controller, console); stats per subscription
  (retrying/terminal counts, oldest age, next retry, last error); `EventLogParkedHealthIndicator`.
- **Invariants**: amend I14; new I17 "a non-terminal parked head of a running subscription is retried no later than
  `parkedRetryMax` plus one loop pass after it is due".

## Steps
1. Schema + row fields, behaviour unchanged. 2. Settings + backoff unit test. 3. In-memory schedule. 4. Due check,
AUTO origin, heal/terminal/order ITs + mutants. 5. Lease safety, lease/reset/takeover ITs. 6. Observer + meters.
7. Admin stats + bulk in engine. 8. SPI/spec/controller/console + parity tests. 9. Health indicator. 10. Docs and
release notes.

Mutants: `PARKED_RETRY_NEVER_DUE`, `PARKED_RETRY_TERMINAL_RETRIED`, `PARKED_RETRY_BACKOFF_NOT_ADVANCED`,
`PARKED_RETRY_STATEMENT_WHEN_IDLE`, `PARKED_RETRY_SCHEDULE_NOT_LOADED`, and a lease mutant for (a) or (b).

## Decisions (maintainer, 2026-11-25)
1. Lease safety: **late locking for every serve**, automatic and operator alike (option b); no lease gate needed.
2. A head that fails again: **the latest failure decides** whether it is terminal or retrying.
3. Deserialization: **terminal only for malformed JSON**; an unknown event class keeps retrying (rolling deploys).
4. Health: a terminal park reports a **configurable status, default DOWN**, documented as excluded from
   liveness/readiness groups.
