
# Hybrid CDC + EventStore Design

## Overview

This document describes the **Hybrid CDC EventStore architecture** that combines:

- **PostgreSQL logical replication (wal2json)** for near‑real‑time ingestion
- **Inbox + Dispatcher** for durability, backpressure, and poison isolation
- **EventStore polling** for deterministic backfill and subscriber recovery
- **Gap handling** (transient + permanent) to guarantee forward progress
- **Strict ordering guarantees** between backfill and live streams
- **Subscription reset‑after‑poison semantics**
- **Multi‑node correctness** (exclusive vs non‑exclusive subscriptions)
- **Performance characteristics vs pure polling**

This design allows CDC to accelerate fan‑out and reduce DB load **without sacrificing correctness**.

---

## High‑level Architecture

```
┌────────────┐
│ PostgreSQL │
│ WAL        │
└─────┬──────┘
      │ wal2json
      ▼
┌──────────────┐
│ Wal2Json     │
│ Tailer       │
└─────┬────────┘
      │ filtered inserts only
      ▼
┌──────────────┐
│ CDC Inbox    │◄─── durable, idempotent
│ (RECEIVED)  │
└─────┬────────┘
      │ batch fetch
      ▼
┌──────────────┐
│ CDC          │
│ Dispatcher   │
├──────────────┤
│ convert WAL  │
│ detect poison│
│ extract gaps │
└─────┬────────┘
      │
      ├── valid events ─────► CdcEventBus (live)
      │
      └── poison
          │
          ├─ mark POISON
          ├─ register permanent gaps
          └─ notify subscriptions (reset‑after‑poison)
```

---

## Core Invariants

1. **GlobalEventOrder is the source of truth**
2. **At‑least‑once delivery into the Inbox**
3. **Exactly‑once processing via idempotency**
4. **Subscribers must never stall**
5. **Poison must not block progress**
6. **Ordering must be preserved across backfill + live**

---

## CDC Inbox

- Table: `eventstore_cdc_inbox`
- Idempotent insert on `(slot_name, lsn)`
- Status lifecycle:

| Status     | Meaning |
|-----------|--------|
| RECEIVED  | Ready for dispatch |
| DISPATCHED| Successfully emitted |
| POISON    | Failed conversion |

---

## Poison Handling

A WAL row is considered **poison** when:
- JSON is valid
- WAL insert targets an event table
- Conversion to `PersistedEvent` fails

### On Poison:
1. Inbox row marked `POISON`
2. Extract global orders via `WalGlobalOrdersExtractor`
3. Register **permanent gaps**
4. Notify subscriptions via `CdcPoisonNotifier`

---

## Gap Handling Model

### Transient Gaps
- Detected during polling/backfill
- Stored per subscriber
- Included opportunistically in queries
- Promoted if unresolved long enough

### Permanent Gaps
- Shared across all subscribers
- Represent **known missing global orders**
- Never block progress

```
1   2   3   4   5
✔   ✖   ✔   ✔   ✔
    ↑
  permanent gap
```

---

## Subscription Reset‑After‑Poison

Implemented via `SubscriptionResetOnPoisonNotifier`.

### Rules
- Only affects **active subscriptions**
- Skipped for **in‑transaction subscriptions**
- Reset point = `min(poison gaps)`
- Resume point is **never moved forward**
- Durable resume saved immediately

### Result
Subscribers:
- Restart cleanly
- Skip poison gap
- Continue deterministically

---

## Backfill + Live Ordering

CDC provides **live events**, polling provides **backfill**.

### Problem
Live events may arrive **before backfill completes**.

### Solution: `BackfillThenLiveOrdered`

Guarantees:
- Live subscribed immediately
- Live buffered by global order
- Live emissions gated until backfill completes
- Strict monotonic ordering

```
Backfill: 1 2 3
Live:          4 5
Output: 1 2 3 4 5
```

---

## Hybrid pollEvents Flow

1. Resolve resume point
2. Snapshot head (highest persisted GO)
3. Backfill `[resume .. head]`
4. Subscribe to CDC live `> head`
5. Merge using ordered gate

---

## Multi‑Node Correctness

### Exclusive Subscriptions
- Use fenced locks
- Only one active consumer
- Strong ordering

### Non‑Exclusive Subscriptions
- Multiple consumers
- Gap handler ensures eventual consistency
- Permanent gaps prevent deadlock

---

## Performance Characteristics

### Pure Polling
- O(N subscribers × polling rate)
- High DB read amplification
- Latency bound by polling interval

### CDC Hybrid
- O(1) DB write → many subscribers
- Near‑real‑time delivery
- Polling only for backfill/recovery

| Metric | Polling | CDC Hybrid |
|------|--------|-----------|
| DB Reads | High | Low |
| Latency | 10–100ms | ~1–5ms |
| Fan‑out | Poor | Excellent |
| Recovery | OK | Excellent |

---

## Failure Scenarios Covered

| Scenario | Outcome |
|--------|--------|
| Poison WAL | Gap registered, no stall |
| Node crash | Resume from durable point |
| CDC outage | Polling still works |
| Duplicate WAL | Inbox idempotency |
| Gap + live race | Ordered merge |

---

## Summary

This hybrid CDC design:

- Preserves **EventStore semantics**
- Eliminates polling fan‑out cost
- Guarantees ordering and progress
- Handles poison safely
- Scales across nodes
- Is fully testable end‑to‑end

**CDC accelerates. Polling guarantees. Gaps heal.**

