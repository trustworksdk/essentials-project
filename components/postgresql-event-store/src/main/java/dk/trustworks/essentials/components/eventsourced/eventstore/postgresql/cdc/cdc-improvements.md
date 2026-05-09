# CDC — Improvements Worth Implementing

Companion to [cdc.md](cdc.md) §13 (Known Limitations). The items here are the
ones judged to have meaningful operational ROI and a tractable implementation
path. Items in §13 that are *not* repeated here are accepted as-is.

Order is by priority. Each entry: what, why, where, sketch.

---

## P1 — Slot-health metrics ✅ DONE

Implemented in [`CdcSlotMetrics.java`](CdcSlotMetrics.java). Wired in the Spring
auto-config conditionally on `MeterRegistry` availability + `cdc.enabled`.

Published gauges (tagged `slot=<slotName>`, sampled every
`cdc.slot.metricsInterval`, default 30s):

- `essentials.cdc.slot.lag_bytes` — `pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)`
- `essentials.cdc.slot.active` — 0/1
- `essentials.cdc.slot.wal_status` — `0=UNKNOWN`, `1=RESERVED`, `2=EXTENDED`,
  `3=UNRESERVED`, `4=LOST`. Alert `>1` = warn, `>2` = page.
- `essentials.cdc.slot.inactive_since_seconds` — `0` while active; growing on
  inactive slot = orphaned-slot signal. Combine with `slot.active=0` for the
  alert.

Behaviour:

- Sampling failures keep the previous gauge values (no false drops on transient
  DB blip) and the scheduler is never suppressed — same defensive pattern as
  `CdcEffectivenessMonitor`.
- Initial tick at `t=0` so dashboards have real values immediately.
- Disabled when `cdc.enabled=false`, or when `cdc.slot.metricsEnabled=false`,
  or when no `MeterRegistry` is present in the context.

`SlotState` was extended with `walStatus` (enum) and `inactiveSinceSeconds`
fields; existing callers using `active()`/`confirmedFlushLsn()`/`lagBytes()`
are unchanged.

---

## P2 — Fail-fast on degraded slot at startup ✅ DONE

Implemented in [`PgReplicationSlots.validateSlotHealthOrThrow`](PgReplicationSlots.java).

Validation is now split into two phases:

- **Identity check** ([`validateSlotOrThrow`](PgReplicationSlots.java)) — slot
  type, plugin, database, persistent. Unchanged in scope.
- **Health check** (new [`validateSlotHealthOrThrow`](PgReplicationSlots.java)) —
  verifies the slot is operationally usable. Detects three signals:
  - `wal_status` not in `reserved` (PG ≥ 13).
  - `conflicting = true` (PG ≥ 16).
  - `invalidation_reason` set (PG ≥ 16).

Wiring inside [`ensureSlot`](PgReplicationSlots.java):

| Mode                | Identity check | Health check                                                        |
| ------------------- | -------------- | ------------------------------------------------------------------- |
| `EXTERNAL`          | yes            | yes                                                                 |
| `REQUIRE_EXISTING`  | yes            | yes                                                                 |
| `CREATE_IF_MISSING` | yes (when slot present) | yes (when slot present)                                    |
| `RECREATE`          | yes            | **skipped** — about to drop, degraded slot is exactly the use case  |

Each failure throws a `SQLException` whose message contains ready-to-run
remediation SQL (`SELECT pg_drop_replication_slot('…');` or a pointer to
`mode=RECREATE`) — no operator lookup required.

False-positive safety: PostgreSQL versions that don't expose
`wal_status`/`conflicting`/`invalidation_reason` columns return null for those
fields. The health check treats null as "unknown, can't verify" and passes.

---

## P3 — Inbox backlog + poison-rows gauges ✅ DONE

Implemented as [`CdcInboxRepository.registerInboxBacklogGauges(slotName)`](CdcInboxRepository.java).
The gauges sample [`countByStatus`](CdcInboxRepository.java) on demand at metrics
scrape time — there is no separate background sampler. Cadence is whatever the
metrics backend's scrape interval is (typically 15–60s).

Why on-demand rather than periodic refresh (as P1 / `CdcSlotMetrics` does):
P1 runs *one* `pg_replication_slots` query that feeds *four* gauges, so caching
across the four gauges saves redundant queries per scrape. Inbox has two
independent counts and no batching gain — on-demand keeps the pipeline simple
and removes a Lifecycle bean / scheduler from the framework.

`countByStatus` is served by the existing `(slot_name, status, inbox_id)` index
as an index-only scan, so per-scrape cost is negligible.

Published gauges (tagged `slot=<slotName>`):

- `essentials.cdc.inbox.received_backlog` — `count(*) WHERE status='RECEIVED'`.
  Growing value = dispatcher falling behind the tailer.
- `essentials.cdc.inbox.poison_rows` — `count(*) WHERE status='POISON'`.
  Non-zero value warrants investigation; growing value = systematic decode bug.

Gating:

- Skipped in DIRECT delivery mode (no inbox to count; gauges would be permanently 0).
- Skipped when `cdc.enabled=false` or `cdc.cdcDispatcher.inboxMetricsEnabled=false`.
- No-ops when no `MeterRegistry` is present.
- Idempotent: duplicate registration calls for the same slot short-circuit.

One new property on `CdcDispatcherProperties`: `inboxMetricsEnabled`
(default true).

---

## P4 — Configurable idle LSN push interval ✅ DONE

Promoted to property
[`cdc.walReplicationTailer.idleLsnPushInterval`](CdcProperties.java) (default
`30s`). Captured into a per-instance final field at
[`WalReplicationTailer`](WalReplicationTailer.java) construction time, replacing
the previous static `IDLE_LSN_PUSH_INTERVAL_NANOS` constant. The constant is
retained as `DEFAULT_IDLE_LSN_PUSH_INTERVAL_NANOS` for fallback when the
property is null or non-positive — disable mode is intentionally **not**
supported (this is a load-bearing safety mechanism for slot growth).

Defaults are unchanged from the original behaviour, so existing deployments are
unaffected.

---

## P5 — Startup advisory log for `max_slot_wal_keep_size` ✅ DONE

Implemented as
[`PgReplicationSlots.getKeepSizeAdvisoryIfUnbounded(Connection)`](PgReplicationSlots.java),
called from [`WalReplicationTailer.ensureReplicationSlot`](WalReplicationTailer.java)
once per JVM (guarded by an `AtomicBoolean` so reconnects don't repeat the log).

The helper queries `pg_settings.setting WHERE name='max_slot_wal_keep_size'`,
returns the human-readable advisory string only when the value is `-1`
(unbounded — PG's default), and is `Optional.empty()` otherwise. Best-effort:
swallows any SQL exception silently so a missing privilege never fails startup.

The advisory text recommends setting an explicit bound (e.g. `10GB`) and
explicitly notes that subscribers fall back to polling on slot invalidation, so
operators can reason about the trade-off without leaving the log line.

---

## Summary Table

| # | Item                                          | Status   | Effort       | Risk reduced                                                 |
| - | --------------------------------------------- | -------- | ------------ | ------------------------------------------------------------ |
| 1 | Slot-health metrics gauge                     | ✅ done   | half-day     | Disk overflow from unmonitored slot growth.                  |
| 2 | Fail-fast on degraded slot                    | ✅ done   | ~1 hour      | Opaque runtime failure on invalidated slot.                  |
| 3 | Inbox backlog + poison gauges                 | ✅ done   | half-day     | Silent dispatcher fall-behind; silent decode-failure stream. |
| 4 | Configurable idle LSN push interval           | ✅ done   | ~30 min      | Tight `wal_sender_timeout` hitting the hardcoded 30s.        |
| 5 | Advisory log for `max_slot_wal_keep_size`     | ✅ done   | ~30 min      | Operator forgets the server-side disk safety net.            |

All five items now ✅ delivered. The framework's slot-growth risk is now
observable (P1 + P3), fail-fast on existing-slot startup (P2), tunable for
non-default `wal_sender_timeout` setups (P4), and self-advisory about the
server-side backstop (P5).

---

## Explicitly out of scope

These were considered and deliberately left in [cdc.md](cdc.md) §13 rather than
added here:

- **Orphaned-slot auto-cleanup** (§13.1) — fundamentally requires human judgement; auto-drop is too dangerous.
- **Eliminating the SIGKILL failover gap** (§13.2) — requires external coordinator; defeats the architecture.
- **Federating CdcEventBus across the cluster** (§13.4) — solved by the inbox; federation would add complexity for no gain.
- **SKIP LOCKED contention metric** (§13.3) — nice-to-have but not load-bearing for any operational decision.
