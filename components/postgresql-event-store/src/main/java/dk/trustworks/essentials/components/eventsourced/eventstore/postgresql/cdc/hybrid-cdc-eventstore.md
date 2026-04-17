
# Hybrid CDC + EventStore Design

## Overview

Hybrid CDC combines:

- PostgreSQL logical replication (`wal2json`) for low-latency live ingestion
- `eventstore_cdc_inbox` + `CdcDispatcher` for durable, idempotent fan-out
- EventStore polling for deterministic backfill and fallback
- gap handling + poison reset semantics for forward progress

This keeps EventStore correctness guarantees while reducing polling amplification.

## Architecture

```
PostgreSQL WAL
  -> wal2json replication stream
  -> WalReplicationTailer (writes CDC inbox)
  -> CdcDispatcher (converts + publishes)
  -> CdcEventBus (live stream)
  -> CdcEventStore (BackfillThenLiveOrdered)
  -> subscriptions
```

## Replication Slot Ownership

One tailer can run per slot at a time, and ownership is coordinated with a PostgreSQL advisory lock.

- lock key: deterministic hash of `slotName`
- acquisition: `pg_try_advisory_lock(...)`
- lifetime: held while tailer is running
- release: explicit on stop, implicit on connection loss

`WalReplicationTailer` exposes this via status/metrics:

- `slotLockAcquired` (boolean)
- `streaming` (boolean)
- `lastReceivedLsn`, `lastMessageAt`, `lastError`

The slot itself is still validated (`logical`, plugin, DB, activity), but `active_pid` is only diagnostic and not treated as ownership.

## CDC Modes and Startup Semantics

Key settings:

- `cdc.enabled`
- `cdc.mode = auto | require`
- slot mode (`PgSlotMode`): `CREATE_IF_MISSING | REQUIRE_EXISTING | RECREATE | EXTERNAL`

Behavior:

- `mode=require`: CDC startup failures fail application startup.
- `mode=auto`: CDC startup failures degrade to polling fallback.

Common failure reasons:

- missing `wal2json` plugin
- replication permissions/configuration
- slot conflicts or slot validation failures

## Availability and Fallback

`CdcAvailability` state:

- `ACTIVE`
- `INACTIVE`
- `FAILED`

`CdcEventStore.pollEvents(...)` behavior:

- `ACTIVE`: hybrid backfill + live stream
- otherwise: delegate to classic polling

This allows safe operation in `auto` mode even when CDC cannot start.

## Inbox and Poison Handling

Inbox table: `eventstore_cdc_inbox` with idempotent insert on `(slot_name, lsn)`.

Lifecycle:

- `RECEIVED`
- `DISPATCHED`
- `POISON`

When conversion fails:

1. mark row `POISON`
2. extract global orders from WAL payload
3. register permanent gaps
4. notify `CdcPoisonNotifier` (e.g. `SubscriptionResetOnPoisonNotifier`)

Resume points are never moved forward due to poison.

## Ordering Guarantees

`BackfillThenLiveOrdered` guarantees ordered output:

1. snapshot head global order
2. backfill `[resume .. head]`
3. subscribe live `> head`
4. buffer/gate live emissions until backfill completes

Result: monotonic ordered stream across backfill + live.

## Multi-Node Behavior

- CDC tailer: single active tailer per slot via advisory lock
- exclusive subscriptions: fenced lock ensures one active subscription handler
- non-exclusive subscriptions: duplicates can occur by design; idempotent handlers required

## Observability

`CdcAvailability` metrics:

- `essentials.cdc.active` (gauge)
- `essentials.cdc.fallback_total` (counter)
- `essentials.cdc.start_failures_total` (counter, incl. reason tag)

Tailer status includes `slotLockAcquired` and streaming diagnostics for operators.
