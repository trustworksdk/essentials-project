# CDC — Improvements Worth Implementing

Companion to [cdc.md](cdc.md) §13 (Known Limitations). The items here are the
ones judged to have meaningful operational ROI and a tractable implementation
path. Items in §13 that are *not* repeated here are accepted as-is.

Order is by priority. Each entry: what, why, where, sketch.

---

## P1 — Slot-health metrics

### What
Expose the `SlotState` snapshot already produced by
[`WalReplicationTailer.getSlotStateSnapshot()`](WalReplicationTailer.java:1164)
as continuously-updated Micrometer gauges:

- `essentials.cdc.slot.lag_bytes` — `pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)`
- `essentials.cdc.slot.active` — 0/1
- `essentials.cdc.slot.wal_status` — encode `reserved`/`extended`/`unreserved`/`lost` as 0/1/2/3 (or use multi-value tag if the metrics backend supports it)
- `essentials.cdc.slot.inactive_since_seconds` — derived from `inactive_since` column

Tagged by `slot_name`.

### Why
The single biggest operational risk (§5: WAL retention) is already invisible —
operators have to either run SQL by hand or wait for the effectiveness monitor
to trip. Once the gauges exist, alerting is a one-liner per environment. This
turns slot growth from "incident" into "page".

### Where
- New scheduled task in `WalReplicationTailer` (or a separate
  `CdcSlotMonitor` component) that calls `getSlotStateSnapshot()` on the
  health-check interval and publishes to gauges.
- Reuse the existing `MeterRegistry` injection.

### Sketch
```java
public final class CdcSlotMetrics {
    private final WalReplicationTailer tailer;
    private final AtomicLong lagBytes = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger walStatus = new AtomicInteger();

    public CdcSlotMetrics(WalReplicationTailer tailer, MeterRegistry registry,
                          String slotName, ScheduledExecutorService scheduler,
                          Duration interval) {
        this.tailer = tailer;
        var tags = Tags.of("slot_name", slotName);
        Gauge.builder("essentials.cdc.slot.lag_bytes", lagBytes, AtomicLong::get).tags(tags).register(registry);
        Gauge.builder("essentials.cdc.slot.active", active, AtomicInteger::get).tags(tags).register(registry);
        Gauge.builder("essentials.cdc.slot.wal_status", walStatus, AtomicInteger::get).tags(tags).register(registry);
        scheduler.scheduleAtFixedRate(this::refresh, 0, interval.toSeconds(), TimeUnit.SECONDS);
    }

    private void refresh() {
        tailer.getSlotStateSnapshot().ifPresent(s -> {
            lagBytes.set(s.lagBytes());
            active.set(s.active() ? 1 : 0);
            // walStatus encoding goes here — extend SlotState if needed
        });
    }
}
```

Effort: small (~half-day). Existing snapshot method does the heavy lifting.

---

## P2 — Fail-fast on degraded slot at startup

### What
Extend
[`PgReplicationSlots.validateSlotOrThrow`](PgReplicationSlots.java:219) to check:

- `wal_status` ∈ {`reserved`} — fail if `extended` (over the keep-size bound),
  `unreserved`, or `lost`.
- `conflicting = true` — fail (slot is unrecoverable).
- `invalidation_reason != null` — fail with the reason in the exception message.

These columns are already read into `SlotInfo`; only the validation logic is
missing.

### Why
A slot that has already been invalidated will currently pass validation and
fail at stream-start with an opaque PostgreSQL error. Failing at validation
gives the operator a clear "your slot is dead, recreate it" signal at
deterministic time (startup) rather than runtime.

### Where
[`PgReplicationSlots.validateSlotOrThrow`](PgReplicationSlots.java:219).

### Sketch
```java
if (slot.walStatus != null && !"reserved".equalsIgnoreCase(slot.walStatus)) {
    throw new SQLException("Replication slot '" + slotName + "' has wal_status='"
        + slot.walStatus + "' (expected 'reserved'); slot is degraded or lost. "
        + "Drop and recreate via: SELECT pg_drop_replication_slot('" + slotName + "');");
}
if (Boolean.TRUE.toString().equalsIgnoreCase(slot.conflicting)) {
    throw new SQLException("Replication slot '" + slotName + "' is conflicting; "
        + "invalidation_reason='" + slot.invalidationReason + "'. Slot must be recreated.");
}
```

Effort: trivial (~1 hour incl. tests).

---

## P3 — Inbox backlog + poison-rows gauges

### What
Two new gauges, tagged by `slot_name`:

- `essentials.cdc.inbox.received_backlog` — `count(*) where status='RECEIVED'`
- `essentials.cdc.inbox.poison_rows` — `count(*) where status='POISON'`

Sample on the same schedule as the slot-health gauges (P1).

### Why
- **Backlog gauge** answers "is the dispatcher keeping up with the tailer?"
  Currently invisible — operators see published-events rate but not the queue
  depth feeding it.
- **Poison gauge** answers "is something silently being dropped?" Currently
  poison rows accumulate until the 90-day TTL purge with no aggregate visibility.

Both are cheap to compute (single `COUNT` per status; the inbox already has an
index on `(slot_name, status, inbox_id)`).

### Where
[`CdcInboxRepository`](CdcInboxRepository.java) — add `countByStatus(slotName, status)`
helper; sample from the same scheduler as P1.

### Sketch
```java
public long countByStatus(String slotName, CdcInboxRepository.Status status) {
    return jdbi.withHandle(h -> h.createQuery(
        "SELECT count(*) FROM " + tableName +
        " WHERE slot_name = :slot AND status = :status")
        .bind("slot", slotName)
        .bind("status", status.name())
        .mapTo(Long.class)
        .one());
}
```

Effort: small (~half-day).

---

## P4 — Configurable idle LSN push interval

### What
Promote
[`WalReplicationTailer.IDLE_LSN_PUSH_INTERVAL_NANOS`](WalReplicationTailer.java:112)
from a hardcoded 30-second constant to a property:

```
cdc.walReplicationTailer.idleLsnPushInterval = 30s   # default
```

### Why
Defensive flexibility. Some environments tighten `wal_sender_timeout` below the
default 60s; once that drops under 60s the 30s push interval starts cutting it
close. Promoting the constant takes no design effort and removes a future
gotcha.

### Where
[`CdcProperties.WalReplicationTailerProperties`](CdcProperties.java:278) +
[`WalReplicationTailer`](WalReplicationTailer.java).

Effort: trivial (~30 min). Drop-in.

---

## P5 — Startup advisory log for `max_slot_wal_keep_size`

### What
At tailer startup, query `current_setting('max_slot_wal_keep_size')`. If
returned value is `-1` (unbounded — the default), log INFO recommending the
setting:

> ⚠️ PostgreSQL `max_slot_wal_keep_size` is unbounded. Consider setting a value
> (e.g. 10GB) so a stuck CDC slot cannot fill the disk. See cdc.md §5.6.

### Why
Pure operator education — costs nothing, prevents a class of incidents.
Especially valuable in dev/test environments where the setting often gets
forgotten.

### Where
[`WalReplicationTailer`](WalReplicationTailer.java) startup path, after slot
validation.

Effort: trivial (~30 min).

---

## Summary Table

| # | Item                                          | Effort       | Risk reduced                                                 |
| - | --------------------------------------------- | ------------ | ------------------------------------------------------------ |
| 1 | Slot-health metrics gauge                     | half-day     | Disk overflow from unmonitored slot growth.                  |
| 2 | Fail-fast on degraded slot                    | ~1 hour      | Opaque runtime failure on invalidated slot.                  |
| 3 | Inbox backlog + poison gauges                 | half-day     | Silent dispatcher fall-behind; silent decode-failure stream. |
| 4 | Configurable idle LSN push interval           | ~30 min      | Tight `wal_sender_timeout` hitting the hardcoded 30s.        |
| 5 | Advisory log for `max_slot_wal_keep_size`     | ~30 min      | Operator forgets the server-side disk safety net.            |

Total ≈ **2 days of focused work** to close the operational visibility gaps
around the framework's biggest risk.

---

## Explicitly out of scope

These were considered and deliberately left in [cdc.md](cdc.md) §13 rather than
added here:

- **Orphaned-slot auto-cleanup** (§13.1) — fundamentally requires human judgement; auto-drop is too dangerous.
- **Eliminating the SIGKILL failover gap** (§13.2) — requires external coordinator; defeats the architecture.
- **Federating CdcEventBus across the cluster** (§13.6) — solved by the inbox; federation would add complexity for no gain.
- **SKIP LOCKED contention metric** (§13.5) — nice-to-have but not load-bearing for any operational decision.
