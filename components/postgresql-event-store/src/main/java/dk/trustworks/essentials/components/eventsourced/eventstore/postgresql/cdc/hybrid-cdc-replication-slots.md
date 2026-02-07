# Hybrid CDC EventStore – Replication Slot Design & Guarantees

## Overview

The Hybrid CDC EventStore uses PostgreSQL logical replication (via `wal2json`) to
stream persisted events directly from the WAL and combine them with traditional
EventStore polling.

This document explains **replication slot configuration**, **ownership rules**,
**operational modes**, and **what is and is NOT enforced by the system today**.

---

## What Is a Replication Slot?

A PostgreSQL *logical replication slot* ensures WAL records are retained until
they are consumed by a logical consumer.

Properties:

- Slots are **cluster-global**
- A slot can have **only one active consumer at a time**
- WAL will accumulate if a slot is not consumed
- Slots are independent of application lifecycle

CDC relies on this mechanism to guarantee **no event loss**.

---

## Slot Naming Strategy

Each CDC tailer uses exactly **one replication slot**, configured via
`cdc.slot.name`.

**Best practices:**

```
<app-name>-cdc
<app-name>-<env>-cdc
orders-service-prod-cdc
```

Avoid:

- Sharing a slot across applications
- Auto-generated names in production
- Reusing slots across environments

---

## Slot Ownership & Validation

On startup, the tailer validates that the slot:

- Exists (depending on mode)
- Is **logical**
- Uses the **wal2json** output plugin
- Is **not temporary**
- Is **not currently active**

If any check fails, the tailer **fails fast**.

### Important: What Is Enforced Today

✅ The tailer **does enforce**:
- Only one *active consumer* per slot (via `active_pid`)
- Correct plugin and slot type
- Fail-fast behavior on conflicts

❌ The system **does NOT coordinate**:
- Multiple application instances automatically sharing a slot
- Leader election for CDC tailers
- Slot ownership across nodes

This is a deliberate design choice.

---

## PgSlotMode Configuration

### CREATE_IF_MISSING (default)

Create the slot if missing.

- Safe default for single-consumer systems
- Fails if slot exists but is active
- Recommended for most production setups

---

### REQUIRE_EXISTING

Slot must already exist.

- Tailer never creates slots
- Fails fast if missing
- Useful when DBAs manage slots explicitly

---

### RECREATE

Drop and recreate slot at startup.

⚠️ **Dangerous in production**

- WAL position is reset
- Intended for tests and ephemeral environments only
- Will refuse to drop an active slot

---

### EXTERNAL

Tailer does not manage slots.

- Slot must exist
- No create / drop
- Still validates safety conditions

Recommended when slot lifecycle is owned externally.

---

## Multi-Node & HA Considerations

### Critical Rule

> **Only one CDC tailer instance may be active per slot**

This is **not enforced via coordination**, but via PostgreSQL itself:

- If another consumer is active, startup fails
- No silent double-consumption is possible

### Supported Patterns

- One CDC tailer per environment
- Active/passive CDC with external orchestration
- CDC enabled on exactly one node

### Not Supported

- Multiple tailers sharing one slot
- Round-robin consumption
- Fan-out from a single slot

---

## Relationship to EventStore Subscriptions

CDC operates *below* EventStore subscriptions.

Flow:

```
Postgres WAL
   ↓
Replication Slot
   ↓
Wal2JsonTailer
   ↓
CDC Inbox
   ↓
CdcDispatcher
   ↓
CdcEventBus
   ↓
CdcEventStore
   ↓
Subscriptions
```

Key points:

- Backfill always happens first
- Live CDC events are strictly ordered
- Gaps and poison events are handled centrally
- Subscription fencing and locking are unchanged

---

## Poison Events & Gaps

If a WAL message cannot be converted:

- Inbox row is quarantined
- Global event order(s) are extracted
- Permanent gaps are registered
- Subscriptions may be reset *backwards*

CDC **never advances** resume points due to poison.

---

## Operational Risks & Mitigations

| Risk | Impact | Mitigation |
|----|----|----|
| Slot not consumed | WAL disk growth | Monitoring & alerts |
| Slot reused incorrectly | Event loss | Naming discipline |
| Multiple tailers | Startup failure | Explicit deployment |
| Slot recreated in prod | Replay | Avoid RECREATE |

---

## Recommended Production Configuration

```yaml
cdc:
  enabled: true
  slot:
    name: orders-service-prod-cdc
    mode: CREATE_IF_MISSING
```

For managed DB environments:

```yaml
cdc:
  enabled: true
  slot:
    name: orders-service-prod-cdc
    mode: REQUIRE_EXISTING
```

---

## Key Takeaways

- Replication slots are powerful but unforgiving
- CDC enforces safety, not orchestration
- Slot mode makes ownership explicit
- Multi-node setups require clear intent

> **One slot. One tailer. One database.**
