# Subscription Improvements

Companion to the CDC improvements log ([cdc-improvements.md](cdc-improvements.md)),
scoped to the subscription / event-delivery layer rather than the CDC pipeline itself.

---

## S1 — NOTIFY-driven polling wake-up

### Motivation

Two recurring, seemingly-contradictory pieces of user feedback:

1. **"CDC is operationally heavy."** Even with the framework smoothing it (P1–P8 in
   cdc-improvements.md), logical-replication slots, `wal_level=logical`, publication
   management, and a replication-grant role are more moving parts than some teams want.
2. **"Polling subscribers cause high database load."** A pure-polling deployment runs
   `N_subscribers × N_jvms × poll-rate` queries per second against the event-stream
   tables — unconditionally, whether or not anything was written.

These look opposed because today's options force a binary choice: CDC (low DB load, high
op cost) or polling (low op cost, high DB load). **LISTEN/NOTIFY-driven polling wake-up**
dissolves both: keep polling's operational simplicity (no slot, no `wal_level=logical`,
no publication) while making the poll *event-driven* so a quiet system stops querying.

This is **not** CDC, **not** a federation layer, **not** a new broker. It's a wake-up
signal layered onto the existing polling path. The durable resume-point mechanism
(`durable_subscriptions`) remains the authority for correctness; NOTIFY is purely a
latency / load optimisation that's safe to miss.

### The key insight: it's a pluggable strategy, not a new subscription type

The framework already routes every subscription's poll cadence through
[`EventStorePollingOptimizer`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/EventStorePollingOptimizer.java):

```java
public interface EventStorePollingOptimizer {
    void eventStorePollingReturnedNoEvents();
    void eventStorePollingReturnedEvents();
    @Deprecated boolean shouldSkipPolling();
    long currentDelayMs();
}
```

`PostgresqlEventStore.pollEvents(...)` builds one per subscription via the optional
`Function<String, EventStorePollingOptimizer>` factory (keyed by event-stream log name),
defaulting to `EventStorePollingOptimizer.None()` (no backoff) when none is supplied. The
factory is already a first-class extension point —
`EventStoreSubscriptionManagerBuilder.setEventStorePollingOptimizerFactory(...)`.

**Therefore the entire feature can be delivered as a new `EventStorePollingOptimizer`
implementation + a shared NOTIFY listener feeding it.** No changes to
`NonExclusiveAsynchronousSubscription`, `ExclusiveAsynchronousSubscription`,
`PersistedEventSubscriber`, `DefaultEventStoreSubscriptionManager`, or the `pollEvents`
Flux machinery. That keeps the blast radius tiny and the back-compat story trivial
(don't set the factory → behaviour unchanged).

### Two-phase plan

The honest constraint: `currentDelayMs()` is a *pull* — the poll loop reads it and
sleeps that long. A NOTIFY that arrives mid-sleep can't interrupt the current sleep, only
shorten the *next* one. So there are two achievable targets, and we ship them in order.

#### Phase 1 (this iteration): NOTIFY-reset adaptive backoff

Behaviour:
- After a poll returns **no events**, the optimizer ramps `currentDelayMs()` up an
  exponential curve (e.g. 50 ms → 100 → 200 → … → cap, default cap 1 s).
- A shared NOTIFY listener bumps a per-aggregate epoch counter whenever an INSERT
  notification arrives for that aggregate's table.
- On each `currentDelayMs()` call the optimizer checks whether the epoch advanced since
  its last poll. If it did → return `0` (poll immediately on the next loop iteration) and
  reset the backoff. If not → return the current ramped delay.

Net effect:
- **Quiet system**: backoff climbs to the cap; DB query rate drops from
  `N × poll-rate` to `N / cap_seconds` — orders of magnitude fewer queries.
- **Active system**: epoch keeps advancing, delay stays near 0, cadence tracks writes.
- **Worst-case live latency**: the current backoff value at the moment of the NOTIFY
  (bounded by the cap). With a 1 s cap, a notify arriving right after the loop went to
  sleep waits up to ~1 s; typically much less. That's already a massive improvement over
  the "poll every 250 ms unconditionally" load profile, and it directly answers the
  *DB-load* complaint (the louder of the two).

This phase is **purely additive** — one new optimizer class, one new listener class, one
trigger, config + wiring. No Reactor-internals changes.

#### Phase 2 (later iteration): true reactive wake-up

For sub-10 ms live latency the sleep must be *interruptible* by a NOTIFY. That needs the
poll loop to merge a notify `Flux` into its delay (so the delay completes early when a
signal arrives) rather than reading a scalar `currentDelayMs()`. That's a change to
`PostgresqlEventStore`'s poll Flux — bigger, reactive-correctness-sensitive, and not
needed to address the load complaint. Deferred until/unless there's a concrete
sub-100 ms-latency requirement. Documented here so the Phase 1 class shapes don't paint
us into a corner (they don't — Phase 2 reuses the same listener + trigger).

### Reuse of existing infrastructure (the big architectural simplification)

The foundation module already has the LISTEN/NOTIFY plumbing this feature needs. The
design composes existing pieces rather than rebuilding them:

| Need | Provided by |
|---|---|
| Per-table NOTIFY trigger + sanitised DDL | [`ListenNotify.addChangeNotificationTriggerToTable(handle, tableName, List<SqlOperation>, …)`](../../../../../../../../foundation/src/main/java/dk/trustworks/essentials/components/foundation/postgresql/ListenNotify.java) |
| Per-table channel name resolution | [`ListenNotify.resolveTableChangeChannelName(tableName)`](../../../../../../../../foundation/src/main/java/dk/trustworks/essentials/components/foundation/postgresql/ListenNotify.java) |
| Single shared `LISTEN` connection per JVM | [`MultiTableChangeListener`](../../../../../../../../foundation/src/main/java/dk/trustworks/essentials/components/foundation/postgresql/MultiTableChangeListener.java) — already a `Lifecycle` bean, runs a single-threaded poll loop, multiplexes channels |
| Per-table subscription registration | `MultiTableChangeListener.listenToNotificationsFor(tableName, NotificationClass)` |
| Notification deduplication | `MultiTableChangeListener`'s `NotificationFilterChain` (built in) |
| Notification fan-out to consumers | `MultiTableChangeListener`'s `EventBus` (built in) |
| Payload structure (`{table_name, sql_operation}` JSON) | `TableChangeNotification` (extensible record class) |

The framework already uses `MultiTableChangeListener` (visible in the perf-lab logs:
*"Removing table change LISTENER for 'durable_queues'"* at shutdown). S1 reuses the
same instance — or wires its own scoped one — rather than introducing a parallel
listener.

### Classes (Phase 1)

The new-code surface is **one optimizer class plus wiring**. Everything else is
composition of existing components.

#### `NotifyAwareEventStorePollingOptimizer` (new — the only genuinely new class)

Lives in
`components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/subscription/notify/`.

Implements `EventStorePollingOptimizer`. Constructed per-subscription by the factory
with: a `NotifyEpochSource` (see below), the key (event-stream log name from the
factory parameter, matched against the underlying event-stream table name), and a
backoff config (initial delay, multiplier, cap).

State: `currentDelayMs`, `lastSeenEpoch`.

- `eventStorePollingReturnedEvents()`: reset `currentDelayMs` to `initialDelay`;
  `lastSeenEpoch = epochSource.currentEpoch(key)`.
- `eventStorePollingReturnedNoEvents()`: ramp `currentDelayMs = min(currentDelayMs × multiplier, cap)`.
- `currentDelayMs()`:
  ```
  long epoch = epochSource.currentEpoch(key);
  if (epoch != lastSeenEpoch) {                       // a notify landed since our last poll
      lastSeenEpoch = epoch;
      currentDelayMs = initialDelay;                  // poll now (with floor)
      return 0;
  }
  return currentDelayMs;                              // keep backing off
  ```
- `shouldSkipPolling()`: deprecated; returns `false`.

#### `NotifyEpochSource` (new — small adapter, lives next to the optimizer)

Bridges between `MultiTableChangeListener`'s `EventBus`-based notification stream and
the per-table epoch counters the optimizer needs. Single tiny class:

- Subscribes to the `EventBus` on construction, filters `TableChangeNotification`
  events.
- Maintains `ConcurrentHashMap<String, AtomicLong>` keyed by table name; each
  `TableChangeNotification` bumps the relevant counter.
- Exposes `long currentEpoch(String tableName)` for the optimizer to read.

That's the entire bridge — ~30 lines.

#### Trigger installation (no new class)

Installed in
[`SeparateTablePerAggregateTypePersistenceStrategy`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/persistence/table_per_aggregate_type/SeparateTablePerAggregateTypePersistenceStrategy.java)'s
`addAggregateEventStreamConfiguration` path, immediately after the existing
`createEventStreamTable(...)`, guarded by the same
`PostgresqlUtil.acquireBootstrapLock(handle)` used for the P6 concurrent-startup fix:

```java
if (notifyPollingEnabled) {
    ListenNotify.addChangeNotificationTriggerToTable(
        handle,
        eventStreamConfiguration.eventStreamTableName,
        List.of(SqlOperation.INSERT)
    );
    multiTableChangeListener.listenToNotificationsFor(
        eventStreamConfiguration.eventStreamTableName,
        TableChangeNotification.class
    );
}
```

`ListenNotify` handles the function-or-replace, trigger-drop-and-recreate, and
identifier sanitisation. The persistence strategy never touches raw SQL for this — it
just delegates.

DDL is framework-managed, period — consistent with how the framework already manages
every other table, index, and gap-handler structure. No operator-toggle for trigger
management.

#### Configuration

New settings on `EventStoreSubscriptionManagerSettings` (and Spring properties):

| Property | Default | Meaning |
|---|---|---|
| `essentials.eventstore.subscription.notify-polling.enabled` | `false` | Master switch. `false` = today's behaviour exactly. |
| `essentials.eventstore.subscription.notify-polling.initial-delay` | `PT0.05S` | Backoff floor after a no-events poll. |
| `essentials.eventstore.subscription.notify-polling.max-delay` | `PT1S` | Backoff cap = worst-case live latency on a quiet system. |
| `essentials.eventstore.subscription.notify-polling.backoff-multiplier` | `2.0` | Exponential ramp factor. |

Default `enabled=false` makes the whole feature opt-in and the change non-breaking.
Channel naming is derived per-table via `ListenNotify.resolveTableChangeChannelName(...)` —
no operator-visible channel-name property.

#### Wiring (Spring autoconfig)

When `notify-polling.enabled=true`:
1. Ensure a `MultiTableChangeListener` bean is wired (the framework's autoconfig
   already creates one when other subscribers exist — reuse it; otherwise instantiate
   a scoped one for the event-store).
2. Construct a `NotifyEpochSource` subscribed to the listener's `EventBus`.
3. Set the subscription manager's `eventStorePollingOptimizerFactory` to
   `key -> new NotifyAwareEventStorePollingOptimizer(epochSource, key, backoffConfig)`.
4. Flip the persistence strategy's "install notify trigger" flag (internal, not
   operator-facing) so subsequent `addAggregateEventStreamConfiguration` calls invoke
   `ListenNotify.addChangeNotificationTriggerToTable(...)` and
   `multiTableChangeListener.listenToNotificationsFor(...)` alongside the table
   creation.

All four are conditional on the master switch; none fire when it's off.

### Interaction with CDC (coexistence, not mutual exclusion)

Re-traced: with CDC enabled, subscribers go through `CdcEventStore.pollEvents()` which
builds `BackfillThenLiveOrdered` — a polling backfill (resume → head) followed by an
adaptive live source (CDC bus when ACTIVE, polling fallback when not). `EventStore`-
level optimizers apply to the polling components (backfill + fallback). The bus path
is unaffected by the optimizer. **A subscriber always has exactly one delivery path
open at a time via `BackfillThenLiveOrdered`** — there is no double-delivery risk
from running CDC + notify-polling together.

Actual coexistence considerations:

| Concern | Reality |
|---|---|
| Double-delivery | No — single ordered path per subscription |
| Trigger overhead when CDC bus is the active path | Yes — ~10–50 µs per INSERT for notifications that aren't currently being consumed (optimizer is created but idle while bus delivers) |
| Backfill phase speedup | Net positive — notify-aware backfill skips unnecessary polls |
| Polling-fallback speedup when CDC degrades to FAILED | Net positive — fallback becomes notify-driven, not busy-poll |
| Operator confusion | Mild — harder to reason about which mechanism is delivering |

Net judgement: it's a coherent choice to run both (CDC for audit trail + replica-
offload + steady-state low-latency bus delivery, notify-polling to make the polling
fallback responsive when CDC degrades). The framework should **WARN at startup** if
both are enabled — pointing the operator at the operational guidance below — but not
hard-fail. Operators who genuinely want both should be able to opt in.

Operator decision tree:

| Goal | Pick |
|---|---|
| Lowest op cost + low DB load, write rate ≤ ~10k/s, no audit trail needed | **notify-polling only** |
| Want audit trail / replica-offload / very high write rate / sub-50 ms uniform live latency | **CDC INBOX only** |
| Want CDC's strengths PLUS notify-driven fallback when CDC degrades | **CDC + notify-polling** (accept the small trigger-overhead cost) |
| Already happy with plain polling and don't want any new triggers | **Neither** (today's default) |

### What this does NOT provide (vs CDC INBOX)

Be explicit so operators choose correctly:

| Capability | CDC INBOX | notify-polling |
|---|---|---|
| Audit trail of the live stream | Yes (inbox, 90d TTL) | No (only event-stream tables) |
| Server-side filtering before delivery | Yes (publication + plugin) | No (poll query already filters by aggregate) |
| Durable buffer decoupling write from dispatch | Yes | No (slow subscriber recovers via resume point) |
| Move read load to a replica | Yes | No (trigger fires on primary) |
| Zero write-side overhead | Yes | No (~10–50 µs/INSERT trigger) |
| No `wal_level=logical` required | No | Yes |
| No replication slot (zero WAL-retention risk) | No | Yes |
| Near-zero idle DB query load | Yes | Yes |

### Empirical measurements

Three perf-lab runs against a compose Postgres, varying `producer-rate-hz` and
`notify-polling.max-delay`. Common setup: 2 subscribers, 10 aggregates,
seed=42, comparison across 4 legs (plain polling / S1 notify-polling / CDC inbox /
CDC direct). Baseline column is **plain polling**, which itself uses
`JitteredEventStorePollingOptimizer` with a 100 ms → 2000 ms linear ramp — i.e. the
existing default already adapts. S1 is being measured against an already-adaptive
baseline, not naïve fixed-interval polling.

#### Run A — active workload, 1 event/sec, S1 `max-delay=1s` (framework default)

120 s measurement.

| Leg | p95 latency | SLA <500 ms | event-store SELECTs/s/sub | Δ DB load vs polling |
|---|---:|---:|---:|---:|
| polling | 320 ms | 100% | 6.95 | — |
| notify-polling | **779 ms** | **59%** | **4.97** | **−28%** |
| CDC inbox (active) | 332 ms | 100% | 6.93 | ~0% |
| CDC direct (active) | 319 ms | 100% | 6.94 | ~0% |

At this rate, **neither optimizer reaches its cap** — events arrive every 1 s, faster
than either ramp reaches its ceiling. S1's 1 s cap nonetheless clamps mid-sleep waits
longer than the baseline's slow linear ramp ever reaches, so NOTIFYs land mid-sleep
and inflate p95. Real DB-load reduction (−28%), but a real latency cost (+143% p95).

#### Run B — active workload, 1 event/sec, S1 `max-delay=200ms`

120 s measurement, same workload as Run A; only the cap differs.

| Leg | p95 latency | SLA <500 ms | event-store SELECTs/s/sub | Δ DB load vs polling |
|---|---:|---:|---:|---:|
| polling | 334 ms | 100% | 6.95 | — |
| notify-polling | **222 ms** | **100%** | **6.86** | **−1.3%** |
| CDC inbox (active) | 309 ms | 100% | 6.94 | ~0% |
| CDC direct (active) | 314 ms | 100% | 6.95 | ~0% |

At a 200 ms cap, mid-sleep waits never exceed 200 ms so NOTIFY-driven resets shave
latency (p95 −33% vs polling), but the cap is too tight to materially reduce DB load
at this rate — both optimizers stay in their ramps, observed delta is ~1 SELECT/sec/sub
(noise). **Safe to enable, small latency win, no meaningful DB-load change.**

#### Run C — IDLE workload, 1 event / 10 sec (0.1 Hz), S1 `max-delay=1s` (default)

180 s measurement, 20 s warmup. This is the workload S1 was designed for.

| Leg | p95 latency | SLA <500 ms | SLA <1000 ms | SELECTs/s/sub | Δ DB load vs polling |
|---|---:|---:|---:|---:|---:|
| polling | 1088 ms | 42% | 89% | 2.09 | — |
| **notify-polling** | **898 ms** | **55%** | **100%** | **1.41** | **−32%** |
| CDC inbox (active) | 992 ms | 42% | 97% | 2.09 | ~0% |
| CDC direct (active) | 1039 ms | 42% | 89% | 2.09 | ~0% |

**This is the result that validates S1.** At 0.1 Hz the baseline parks at its 2 s cap
for most of each 10 s window, doing ~2 SELECTs/sec/sub. S1's 1 s cap with NOTIFY reset
gives BOTH the lower DB load (−32%) AND **the best wake-up latency of any leg, including
CDC direct** (p95 898 ms; only leg to hit 100% SLA<1s). The reason CDC barely beats
polling here is that CDC's own dispatcher tick rate dominates at low arrival rates — a
NOTIFY signal hops straight from the WAL through `MultiTableChangeListener` to the
optimizer without that detour.

#### Why the 1 Hz "DB load" finding was misleading

The −1.3% in Run B initially looked like S1 doesn't reduce DB load. It does — but only
when **inter-arrival ≥ baseline's polling cap** (the default
`max-event-store-polling-interval`, 2 s). At 1 Hz, both optimizers spend the cycle
ramping; their caps are irrelevant. At 0.1 Hz, the baseline parks at its cap for most
of the window — that's where S1's tighter cap and NOTIFY-driven reset win the load
comparison.

In other words: S1 doesn't "make polling more adaptive than it already is" at active
rates. It makes polling adaptive in **a different shape** — exponential-and-cap with
NOTIFY reset — that wins specifically when the baseline's slow linear ramp has had
time to reach its 2 s cap.

#### Run D — IDLE workload, 1 event / 10 sec (0.1 Hz), S1 `max-delay=5s`

Same workload as Run C, only `max-delay` raised from 1 s to 5 s to explore the
upper end of the DB-load curve. 180 s measurement.

| Leg | p95 latency | SLA <500 ms | SLA <1000 ms | SELECTs/s/sub | Δ DB load vs polling |
|---|---:|---:|---:|---:|---:|
| polling | 1030 ms | 60.5% | 94.7% | 2.08 | — |
| **notify-polling** | **4893 ms** | **13.2%** | **23.7%** | **0.83** | **−60%** |
| CDC inbox (active) | 864 ms | 55.3% | 100% | 2.09 | ~0% |
| CDC direct (active) | 1012 ms | 36.8% | 94.7% | 2.08 | ~0% |

**This is the cautionary tale that bounds the `max-delay` curve.** DB-load savings
keep scaling: at `max-delay=5s` we're down to 0.83 SELECTs/s/sub (−60% vs polling,
nearly double the −32% from Run C). But latency collapses: p95=4893 ms is almost
exactly the 5 s cap, and SLA<1 s fell from 100% → 24%. The `timeToCatchUpMs=4987 ms`
metric is the smoking gun — when the producer stops, the subscriber waits out its
in-flight 5 s sleep before noticing the final event. That's the documented Phase 1
limitation (NOTIFY arriving mid-sleep can't interrupt the sleep) at full strength.

**The rule this exposes:** at idle, `p95 wake-up ≈ max-delay`. Picking `max-delay`
isn't "how much DB load can I trade away" — it's **"how long can I happily wait
for the next event?"** Going past that number gives you DB-load savings nobody asked
for at the cost of an SLA breach.

#### Summary curve (DB load vs latency at 0.1 Hz)

| max-delay | SELECTs/s/sub | Δ DB load | p95 latency | SLA<1s |
|---:|---:|---:|---:|---:|
| (polling baseline, 2 s cap) | 2.08 | — | 1030 ms | 95% |
| 1 s | 1.41 | −32% | 898 ms | **100%** |
| 5 s | 0.83 | **−60%** | 4893 ms | 24% |

`max-delay=1s` is the empirical sweet spot for 0.1 Hz idle workloads: best latency
of any tested configuration AND meaningful DB-load reduction. Going larger trades
real latency for marginal load savings. Going smaller (200 ms) reverts most of the
DB-load benefit.

### Decision matrix

Pick by the workload's typical inter-arrival rate. The cell highlighted **bold** is
the recommended `max-delay` for that row.

| Inter-arrival | Plain polling baseline | S1 `max-delay=200ms` | S1 `max-delay=1s` (default) | S1 `max-delay=5s` |
|---|---|---|---|---|
| ≥10 events/sec | ramp stays at floor (~100 ms) | ≈ baseline (ramp stays at initialDelay) | ≈ baseline | ≈ baseline |
| ~1 event/sec | 6.95 SELECT/s/sub, p95 334 ms, SLA<1s 100% | **6.86 SELECT/s/sub (−1%), p95 222 ms (−33%), SLA<1s 100%** | 4.97 SELECT/s/sub (−28%), p95 779 ms (+143%), SLA<1s 100% | (untested; expected worse than `1s` here) |
| ~1 event/10 sec | 2.09 SELECT/s/sub, p95 1088 ms, SLA<1s 89% | (untested; expected: small DB-load and latency wins) | **1.41 SELECT/s/sub (−32%), p95 898 ms (−17%), SLA<1s 100%** | 0.83 SELECT/s/sub (−60%), p95 **4893 ms (+375%)**, SLA<1s **24%** ❌ |
| ~1 event/min+ | (untested; expected ~2 SELECT/s/sub from baseline cap) | (untested) | (good DB-load win) | (acceptable here — inter-arrival ≫ max-delay) |

**What this matrix says about defaults:**

- **Keep `max-delay=1s` as the default.** It's the sweet spot at 0.1 Hz idle (where
  S1 was designed to help) AND acceptable at 1 Hz (with a real but bounded latency
  cost). Lowering to 200 ms gives back the idle DB-load win; raising to 5 s breaks
  latency for any workload more active than ~1 event/minute.
- **For active workloads (≥1 Hz) with a tight SLA**, drop to `max-delay=200ms`. Small
  latency win, no DB-load hurt.
- **For workloads at minute+ inter-arrivals** with relaxed wake-up tolerance, raising
  `max-delay` further is fine — but the rule of thumb is `max-delay ≤ acceptable p95
  wake-up`, not "as large as possible."

### Tuning recommendations

The headline knob is `essentials.eventstore.subscription-manager.notify-polling.max-delay`.
It controls **both** the worst-case wake-up latency on a quiet system **and** the
steady-state DB-load ceiling on workloads idle enough to reach the cap.

**The rule for picking `max-delay`:** set it to **the longest p95 wake-up latency
you can tolerate for this subscriber**. Phase 1's design pins p95 wake-up at
approximately `max-delay` on quiet systems (NOTIFYs arriving mid-sleep can't
interrupt the sleep — they only shorten the *next* one). Picking `max-delay` larger
than that doesn't buy you usable headroom; it buys you an SLA breach.

Picking `max-delay`:

- **`max-delay=PT1S` (1 s, framework default)** — **Recommended for idle/low-rate
  workloads** with inter-arrival ≥ ~5–10 s and acceptable p95 wake-up around 1 s.
  Empirical at 0.1 Hz: −32% DB load AND best-in-class latency (p95 898 ms, 100%
  SLA<1s, beating CDC direct's 1039 ms and polling's 1088 ms). The 1 s cap is short
  enough to stay invisible to most apps' tail-latency SLAs.
- **`max-delay=PT0.2S` (200 ms)** — **Use when the workload sustains ~1 Hz or higher
  and sub-300 ms p95 matters.** Empirical: p95 222 ms at 1 Hz (better than plain
  polling's 334 ms). DB-load reduction is negligible at this rate because neither
  optimizer reaches its cap, but the NOTIFY-driven reset still wins on latency.
  Safe to enable; never hurts.
- **`max-delay=PT5S` or higher** — **Only for workloads with inter-arrival in the
  minutes range AND wake-up SLA loose enough to accept ~`max-delay` p95.** At 0.1 Hz,
  this setting catastrophically degrades latency (p95 4.9 s, SLA<1s drops to 24%)
  while gaining only marginal extra DB-load savings (−60% vs the default's −32%).
  Empirical Run D documents the failure mode — do not pick this setting unless your
  workload genuinely is "audit tail or batch reprocessor, one event every few minutes,
  multi-second wake-up is fine."

`initial-delay` and `backoff-multiplier` rarely need tuning. The defaults
(50 ms / 2.0×) give a ~5-step exponential ramp from `initial-delay` to the
default `max-delay=1s`, short enough that even an active subscriber doesn't waste
many polls on the climb.

#### Open question: should the default change?

No. The 0.1 Hz run validates the existing `max-delay=PT1S` default as the right
setting for the workload S1 was designed to help — idle subscribers. Lowering the
default to 200 ms would give away the DB-load benefit (−32% → ~0%) without buying
anything operators with active workloads can't get themselves by configuring
explicitly. Raising it to 5 s breaks latency for any workload more active than ~1
event/minute. Document the trade-off; ship the default.

#### When NOT to use S1

- **Sustained throughput ≥10 events/sec/aggregate** — both S1 and the baseline stay
  at their floor (50 ms / 100 ms initial), so enabling S1 changes essentially nothing
  beyond paying the per-INSERT trigger overhead. Harmless but pointless.
- **Hard sub-200 ms wake-up SLA on quiet systems** — even `max-delay=200ms` has a
  ~200 ms worst-case in-flight sleep. Use CDC direct (where active), or wait for
  Phase 2.
- **The persistence strategy isn't `SeparateTablePerAggregateTypePersistenceStrategy`**
  — bootstrap is no-op with a WARN. Re-evaluate if a custom strategy is added.

#### The Phase 2 trigger

Concrete: a user with an active workload (≥1 Hz) who needs **both**
sub-`max-delay` wake-up AND the DB-load savings of a larger `max-delay`. Phase 1
forces a trade-off here — either lower `max-delay` and lose the load saving, or
keep it and pay the latency. Phase 2 (interruptible delays via reactive merge)
decouples the two. Until a user explicitly asks for both, Phase 1 is the right
shipping target — it covers idle workloads cleanly and active workloads acceptably.

### Risks & open questions

Resolved during design review:

- ~~Trigger install policy~~ — settled: framework-managed, consistent with how every
  other framework table/index is handled. No operator toggle.
- ~~Channel granularity~~ — settled: per-table channels via
  `ListenNotify.resolveTableChangeChannelName(...)`. `MultiTableChangeListener` already
  handles the multi-channel multiplex on one connection.
- ~~`global_order` column name~~ — n/a: trigger DDL is fully owned by
  `ListenNotify.addChangeNotificationTriggerToTable(...)`, which uses the row-as-JSON
  payload (`row_to_json(payload)::text` per the existing implementation). The optimizer
  only needs to know *that* a row landed, not the column name.
- ~~Coexistence guardrail~~ — settled: WARN at startup, not hard-fail. CDC + notify-
  polling is a coherent combination (CDC for steady-state bus delivery, notify-aware
  polling for the fallback path when CDC degrades).

Still open:

1. **Trigger overhead at high write rates.** Each INSERT fires `pg_notify`. Measure
   the breakeven where CDC INBOX becomes the operationally better choice — estimate
   ~10–20k events/s sustained. Document the guidance once measured.
2. **NOTIFY storms.** At high write rates `MultiTableChangeListener`'s poll loop
   receives large notification batches. The optimizer's "epoch advanced or not?" check
   collapses N notifies into a single state transition, so consumer-side cost is
   independent of notification volume. Confirm pgjdbc's notification buffer doesn't
   grow unbounded if `MultiTableChangeListener.pollForNotifications` falls behind under
   storm conditions (it shouldn't — buffer is drained on every scheduled poll).
3. **MultiTableChangeListener bean ownership.** Some deployments may already use
   `MultiTableChangeListener` for other purposes (e.g. durable queues). Autoconfig
   should reuse the existing bean if one is present, or create a scoped instance
   otherwise. Decide whether the listener's `pollingInterval` setting (separate from
   our `notify-polling.max-delay`) needs an event-store-specific override.

### Estimated effort (Phase 1)

Significantly smaller now that we're composing existing infrastructure rather than
building a parallel notify listener:

| Piece | Estimate |
|---|---|
| `NotifyAwareEventStorePollingOptimizer` + `NotifyEpochSource` (the only new code) | half-day |
| Persistence-strategy hook calling `ListenNotify.addChangeNotificationTriggerToTable` + `MultiTableChangeListener.listenToNotificationsFor` | half-day |
| Config properties + autoconfig wiring (incl. reuse-or-create `MultiTableChangeListener`, CDC-coexistence WARN) | half-day |
| Tests (Testcontainers: quiet-system query-rate drop, active-system latency, reconnect catch-up, coexistence WARN behaviour, concurrent-startup trigger idempotency) | 1 day |
| Docs (operator decision tree, tuning guide) | half-day |

Total ≈ **2.5–3 days** for Phase 1. Phase 2 (true reactive wake-up) is a separate,
larger effort gated on a concrete latency requirement.

### Test plan (Phase 1)

Testcontainers IT covering:
1. **Idle DB-load drop**: register a subscriber, no writes, assert poll-query count over
   30 s is ≪ the fixed-interval baseline (measure via `pg_stat_statements` or a query
   counter).
2. **Active tracking**: write at a steady rate, assert delivered events keep pace and
   end-to-end latency stays under the configured `max-delay`.
3. **Reconnect catch-up**: kill the `MultiTableChangeListener` connection mid-run
   (`pg_terminate_backend`), keep writing, assert no events are lost (resume-point poll
   catches them) and the listener reconnects (its existing logic handles this — verify
   we haven't broken it).
4. **Coexistence behaviour**: enable both CDC and notify-polling, assert startup
   succeeds with a WARN log line, assert CDC delivery still works, assert trigger
   overhead is paid but doesn't break anything.
5. **Trigger idempotency**: start two app instances concurrently (the P6 scenario),
   assert no DDL race on the trigger function or per-table triggers (the bootstrap
   lock + the `ListenNotify` idempotency together handle this).

---

## Backlog (not yet specced)

- **S2 — Phase 2 reactive wake-up** (sub-10 ms live latency; poll-loop change).
  Design-ready, awaiting user demand.

  **What Phase 2 fixes that Phase 1 can't.** Phase 1 forces a trade-off because
  `NOTIFY` arriving mid-sleep can't interrupt the sleep — only the *next* sleep
  shrinks. The empirical curve at 0.1 Hz idle (see "Empirical measurements"):

  | | DB load saving | p95 wake-up |
  |---|---|---|
  | Phase 1, `max-delay=1s` | −32% | 898 ms |
  | Phase 1, `max-delay=5s` | −60% | 4893 ms |
  | **Phase 2 target, `max-delay=5s`** | **−60%** | **~10 ms** |

  Phase 2 keeps `max-delay` as the *floor on DB query rate* while letting NOTIFY
  short-circuit the actual sleep. The two axes decouple.

  **Concrete design.** Replace the current `Thread.sleep(optimizer.currentDelayMs())`
  in the poll loop with a reactive wait that resolves on whichever fires first:

  ```java
  // instead of: Thread.sleep(optimizer.currentDelayMs());
  Mono.firstWithSignal(
      notifySignalForTable(tableName).next(),                    // resolves on NOTIFY
      Mono.delay(Duration.ofMillis(optimizer.currentDelayMs()))  // or on timeout
  ).block();
  ```

  `NotifyEpochSource` refactors from "AtomicLong per table" to "`Sinks.Many<Void>`
  per table" — instead of the optimizer polling a counter, the poll loop awaits
  the sink directly. The optimizer's `currentDelayMs()` still controls the
  timeout floor for the DB-load behaviour.

  **Scope estimate.** ~1 day poll-loop change, ~½ day `NotifyEpochSource` refactor
  (counter → sink), ~1 day tests (notify-arrives-during-sleep, multi-table fanout
  on a single connection, backpressure under notify-storm), plus a perf-lab run
  proving the latency claim against Runs C/D.

  **Trigger to actually build it.** A real user with workload at ≥1 Hz who needs
  **both** DB-load budget X **and** p95 wake-up < Y, where X requires `max-delay >
  Y`. Until then, Phase 1's tunable trade-off covers operator needs.
- **S3 — Consumer-group-scoped resume points.** Cross-reference cdc-improvements.md P7
  (`CdcConsumerGroup.namespaced(...)` already ships the cooperative half).
- **S4 — Trim the one-event crash-recovery overlap in the periodic resume-point checkpoint.**
  `DefaultEventStoreSubscriptionManager.saveResumePointsForAllSubscribers()` runs on a fixed
  schedule as a crash-safety net and persists each active subscriber's *current* resume point
  verbatim. A graceful `stop()`/`unsubscribe` records a precise resume boundary, but an
  **ungraceful** failure (node crash, or the subscription manager dying before `stop()` runs to
  completion) leaves recovery to resume from the last periodic checkpoint — which re-delivers
  **exactly one already-processed event** on resubscription.

  **This is safe today**, not a bug: delivery is at-least-once by contract, so a single duplicate
  on crash recovery is within spec, and the verbatim save is deliberately conservative with
  respect to resume-point resets (it never advances past an unprocessed event). The optimization
  is to advance the checkpointed resume point for *active* subscribers to a clean boundary — the
  same adjustment `stop()` makes — so crash recovery resumes with zero overlap.

  **Why it's deferred.** The win is one duplicate event per ungraceful failure, fully absorbed by
  idempotent handlers (the documented requirement). The risk is getting the "is this boundary
  clean?" decision wrong for an in-flight/mid-batch subscriber and *skipping* an event (turning a
  harmless duplicate into a loss), so it needs careful handling of the active/in-flight case — the
  open question the inline note at `saveResumePointsForAllSubscribers()` flags. Build it only if a
  user is sensitive to duplicate-on-crash-recovery and idempotency isn't sufficient.
