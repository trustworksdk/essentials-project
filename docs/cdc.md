# CDC EventStore — Design & Operations

This package implements Change Data Capture (CDC) over the PostgreSQL EventStore. It
combines low-latency live ingestion via PostgreSQL **logical replication** with the
EventStore's existing polling guarantees, so that subscribers get sub-second event
delivery without giving up the deterministic ordering and backfill that polling
provides.

> ⚠️ The previous design document `hybrid-cdc-eventstore.md` in this directory is
> outdated (wal2json-only, no publications, no slot-growth handling). This document
> supersedes it.

---

## 1. Overview

What CDC delivers:

- **Live**: WAL → tailer → in-memory event bus → subscribers (low ms latency).
- **Backfill**: standard EventStore polling against `event_stream_*` tables, used both
  to bootstrap a subscription's resume point and as a fallback whenever CDC is unhealthy.
- **Ordered handover**: `BackfillThenLiveOrdered` snapshots the head global-order at
  subscription time, polls `[resume … head]`, then gates live emissions until the
  backfill catches up — so subscribers see everything up to the head before any live
  event, and the live events that arrived meanwhile in global order. From then on live
  events are delivered as the bus delivers them, in commit order: an event whose
  transaction commits *after* one holding a higher global order is delivered when it
  commits, after the higher one (see "Commit order, not global order" in §2).

What CDC does **not** change:

- The EventStore is still the source of truth — CDC is a delivery accelerator, not a
  replacement. If CDC is `INACTIVE` / `FAILED`, subscribers automatically fall back to
  polling and stay correct.
- Subscription resume points are durable in the EventStore, not in CDC state.

### 1.1 Enabling CDC (opt-in)

**CDC is disabled by default.** Nothing in the CDC pipeline is wired unless you ask for
it: no replication slot is created, no publication is touched, no tailer connects, and
no inbox table is used. An application that says nothing about CDC polls, exactly as it
did before CDC existed.

Turn it on with a single property:

```yaml
essentials:
  eventstore:
    cdc:
      enabled: true      # opt in — no CDC bean is created without this
```

This is deliberate. CDC needs server-side configuration and a replication-capable role,
and those are decisions for whoever owns the database — not something a library should
assume on an application's behalf. Enabling it should be a conscious act.

#### What CDC needs before it will work

| Requirement | Why | How to check |
| --- | --- | --- |
| `essentials.eventstore.cdc.enabled=true` | Gates every CDC bean | — |
| `wal_level = logical` | Logical decoding cannot run without it. Requires a server restart | `SHOW wal_level;` |
| `max_replication_slots` ≥ slots + headroom | Each CDC pipeline holds one slot | `SHOW max_replication_slots;` |
| `max_wal_senders` ≥ slots + headroom | One walsender per active tailer | `SHOW max_wal_senders;` |
| DB role with `REPLICATION` (or superuser) | The tailer opens a replication connection, separate from the pooled one | `\du` |
| Decoding plugin available | `pgoutput` is built into PostgreSQL; `wal2json` is an extension that must be installed | `SELECT * FROM pg_get_replication_slots();` / see §2 |
| A publication covering the event-stream tables (pgoutput only) | pgoutput streams only what the publication includes | `SELECT * FROM pg_publication_tables;` — see §4 |

The publication is the one that catches people out. Either create it yourself, or let
the framework own it:

```yaml
essentials:
  eventstore:
    cdc:
      enabled: true
      pg-output:
        publication:
          auto-manage: true
          mode: FOR_TABLE_LIST   # explicit list; needs table ownership, not superuser
```

`FOR_TABLE_LIST` adds the registered event-stream tables to the publication at tailer
startup. `FOR_ALL_TABLES` is the alternative and requires superuser. See §4.3 for the
full semantics, including what happens to aggregates registered after startup.

Managed PostgreSQL usually needs an extra step to get `wal_level=logical` — on AWS RDS
set the `rds.logical_replication` parameter and reboot; on Azure and GCP enable logical
decoding on the instance.

#### If a requirement is missing

The behaviour is governed by `cdc.mode` (§6.1): in `AUTO` (the default once CDC is
enabled) the application starts and subscribers silently keep polling; in `REQUIRE`
startup fails. `AUTO` means a missing prerequisite costs you latency, not correctness —
which also means it can go unnoticed, so verify after enabling.

#### Verifying it actually came up

- `GET /actuator/health/cdc` — `CdcHealthIndicator`, registered only when CDC is enabled. The
  per-component path is itself gated by Actuator: without
  `management.endpoint.health.show-components: always` (or `show-details: always`) it returns
  **404 even though the indicator is wired**, because Spring defaults both to `never`.
- `GET <admin-api>/event-store/cdc/status` — availability state, slot state, tailer and
  dispatcher counters.
- Startup log: `⚙️ Starting CDC dispatcher …` followed by `CDC dispatcher started`.

A pipeline that is enabled but not delivering shows as `availability.state=ACTIVE` with
`dispatcher.publishedEvents` stuck at zero; §6.3's effectiveness monitor is what catches
that and cuts back to polling.

---

## 2. Architecture

```
                 (writer commits)
PostgreSQL ──────> WAL ──> logical replication slot ──> WalReplicationTailer
                                                                │
                                          [INBOX delivery mode] │ [DIRECT delivery mode]
                                                                │
                                eventstore_cdc_inbox  <─────────┘
                                  (durable buffer,
                                   idempotent on
                                   (slot_name, lsn))
                                                │
                                                ▼
                                        CdcDispatcher
                                  (poll → decode → publish)
                                                │
                                                ▼
                                          CdcEventBus
                                  (per-aggregate Reactor sinks)
                                                │
                                                ▼
                                       CdcEventStore subscribers
                                  (BackfillThenLiveOrdered)
```

In **INBOX mode** (default) the tailer persists raw WAL payloads to a table; the
dispatcher polls that table, decodes, and publishes. The inbox decouples WAL ingestion
from event conversion — the tailer never blocks on a slow subscriber, and decode
failures stay contained to a single row.

In **DIRECT mode** the tailer decodes inline and pushes straight to the bus. Lower
latency, no inbox table, but no durable buffer between WAL ingestion and dispatch:
backpressure on the bus directly throttles WAL acks.

In both modes `CdcEventStore` hands each subscription's live events over from the bus
to a single thread owned by that subscription (`Cdc-<subscriber>-<aggregateType>`),
so the subscriber's handler - including a synchronous `SubscriptionErrorPolicy` retry
backoff - never runs on the shared `cdc-dispatcher-<slot>` thread (or the tailer's
thread in DIRECT mode) and a slow or retrying subscription does not hold up the other
subscriptions on the slot. Events stay in order.

A subscription never back-pressures the bus. The per-aggregate-type multicast sink is
paced by its slowest subscriber, so each subscription takes events off it without limit
and holds the ones its handler has not reached yet in a buffer of its own, one polling
page deep (the subscription manager's `eventStorePollingBatchSize`). When a subscription
falls further behind than that - a handler in a long `SubscriptionErrorPolicy` backoff,
or a slow call - only that subscription leaves the bus: it is handed what it buffered,
then catches up from the event store from the event after the highest one it was handed
(and the gaps below it), and rejoins the bus (see the catch-up below). Nothing is lost
or repeated.
Each such overflow logs one WARN naming the subscriber and aggregate type and counts
`essentials.cdc.eventstore.live_source.overflow.count`, and the catch-up logs one INFO
once the subscription is back on the bus; it is not a CDC fallback and does not touch
`fallback_total` or `live_source.switch.count`. The dispatcher, and every other
subscription of the aggregate type, carry on unaffected - under either
`eventBus.overflowPolicy`. A subscription that cannot keep up with the bus at all
overflows again during or after its catch-up and catches up again from where it got
to: in effect it polls, at its handler's pace, until it can keep up.

`eventStorePollingBatchSize` still sizes that buffer: a subscription that overflows on
every ordinary burst (a large transaction is published to the bus in one go) spends its
time catching up from the event store instead of being served from the bus.

**Moving onto the bus is gap-free.** The bus replays nothing to a late subscriber, so
every time a running subscription moves onto it - the switch to `ACTIVE` at warm-up,
the switch back after an outage, and the recovery from an overflow - it:

1. attaches to the bus, holding what the bus delivers in its own buffer;
2. only then reads the highest persisted global order (the head);
3. loads everything from the event after the highest one it delivered up to that head,
   page by page, on its `Cdc-<subscriber>-<aggregateType>` thread, gap handler included -
   and, on the first page, the gaps below it that it still waits for, by order;
4. then drains its buffer and carries on from the bus.

An event the bus published before the attach was committed before it, so the catch-up
loads it; one published after the attach is in the buffer; what both hold is delivered
once. The catch-up ends at the head rather than at the first buffered bus event, since
global order has holes (a rolled-back `IDENTITY` value is never persisted or published).
Before, the subscription attached to the bus on its own and silently lost whatever the
bus had published that polling had not yet fetched - since polling's last fetch, during
the `activeCutbackDebounce` window, and while the switch waited for an event still in
the handler. A catch-up that fails (database unreachable) is retried a second later,
logged at WARN; it does not end the subscription.

**Commit order, not global order.** A transaction takes its `global_event_order` when it
inserts, and the bus delivers events in the order their transactions *commit*. Two
transactions appending to the same aggregate type can therefore reach the bus out of
global order: the one holding the lower order commits second. The polling path sees the
same thing as a transient gap that its gap handler re-queries and fills later. Each CDC
subscription keeps a delivery tracker (`CdcDeliveryTracker`) rather than a high-water
mark:

- a contiguous **watermark** - everything at or below it was delivered or is known not
  to be coming - and the global orders delivered **above** it; the orders in between are
  **gaps**;
- an event passes if its order is above the watermark and was not delivered yet, so a
  lower order that commits late is delivered when it arrives - after the higher one, out
  of global order, exactly as the polling path delivers a gap-filled event. That is why
  a subscriber's resume point only ever advances (`advanceResumeFromAndIncluding`);
- a gap is waited for until it is **120 s** old - the default permanent-gap threshold of
  `PostgresqlEventStreamGapHandler` (`thresholdBased(120)`); a customised promotion
  strategy is not reflected, and with a `NoEventStreamGapHandler` the same 120 s applies.
  Then the watermark moves past it: a rolled-back `IDENTITY` value is a gap that never
  fills. An event for a gap given up on is dropped, as polling drops one whose gap was
  promoted to permanent. At most 10 000 gaps are held at once; beyond that the oldest is
  given up at once and one WARN is logged;
- polling resumes right after the watermark, so it reads the gaps again; a catch-up
  reads forward from the highest order delivered and asks for up to 1 000 of the gaps by
  order. What was delivered already is dropped.

A high-water mark used to drop such an event for good - on the bus, and when
polling's gap handler fetched it late (the gap handler then resolved the gap as found).
A subscription served by `BackfillThenLiveOrdered` parked its strict drain on it until
`liveDrainStallThreshold` (three minutes by default) instead, or - for an event at or
below the head its backfill read - dropped it as already back-filled.

Per aggregate, commit order is event order: an aggregate's next event is appended by a
transaction that read the previous one committed, so it commits - and reaches the bus -
after it (and takes a higher global order too).

*Restarts.* A subscriber's resume point moves past a gap, which is safe only while the
gap is recorded durably. The polling path and every back-fill page record the gaps in
what they load as transient gaps with the gap handler; an event from the bus is not
loaded, so a delivered event that opens a gap records it - synchronously, before the
event reaches the handler - and one that fills a gap resolves it, each in a short unit
of work of its own (reported as a gap reconciliation, like a poll's). A gap is resolved
only once the subscriber is done with the event filling it. The transient gap is the
only durable record that the fill is still owed, as the resume point lies above it;
resolved first (as the gate and every back-fill page used to), a subscriber stopped or
crashed before it had handled the fill resumed above it and never saw it. Done is:

- for a subscriber that acknowledges what it handled (`SubscriberAcknowledgement`,
  passed to `pollEvents`; the subscription manager's `PersistedEventSubscriber` and
  `BatchedPersistedEventSubscriber` do): when it acknowledges the event - inside the
  unit of work that handled it, so the gap is resolved atomically with the handling,
  and stays open if that unit of work rolls back. A fill waiting for a batch, for an
  I/O retry, or in the `limitRate` queue in front of a subscriber that withholds
  demand is not handled yet, and keeps its gap. Until it is acknowledged the tracker
  holds it as delivered, so the subscription does not deliver it again, although the
  polling leg and catch-ups read it again while its gap is open; a restarted
  subscription is seeded with the gap and is handed it again. Events the subscriber
  skips or hands off are acknowledged too; one it stops at, or ignores after a stop,
  is not;
- for any other subscriber: once the event was handed on - its `onNext` returned.

Back-fill and catch-up pages therefore leave the gaps their events fill open, and the
delivery gate each event passes resolves them; a subscription cancelled before a fill was
done with leaves its gap open, and the next one waits for it again (at the cost of
delivering a fill it may already have handled twice). The delegate's polls are handed an
acknowledgement the gate acknowledges to, so they leave the gaps of the fills they hand
on to the gate. The plain polling path does the same, per poll. When a subscription
starts, its tracker is seeded with the transient gaps the gap handler still has for the
subscriber, so an event filling one of them below the resume point is still delivered
after a restart or a fenced-lock hand-over to another node. Only with a subscriber id and
a gap handler that records gaps; an in-order event costs nothing extra.

*Tenants.* The tracker has to see every global order, so a CDC subscription's polling,
catch-ups and backfill load all tenants and filter by the subscriber's tenant on the way
out. Filtered in SQL, another tenant's events would be gaps it waits for. The plain
polling path (`PostgresqlEventStore.pollEvents` / `unboundedPollForEvents`) loads all
tenants for the same reason, so that its gap handler does not record them as gaps.

*`BackfillThenLiveOrdered`.* Once its backfill is done, it hands on the live events that
arrived meanwhile in global order, and from then on each live event as it arrives - the
tracker drops what was delivered and lets a gap's event through when it commits, as on
the bus leg. It used to advance strictly by one past the head, so a global order that
never arrives - a rolled-back `IDENTITY` value, which writes no WAL - parked it, holding
back every later event, until `liveDrainStallThreshold` (three minutes by default) raised
`CdcLiveDrainStalledException` and re-subscribed it through its backfill. Rollbacks are
routine (an optimistic concurrency conflict is one), so that stalled such subscriptions
for minutes. Nothing waits for a hole any more: `liveDrainStallThreshold` has no effect,
`CdcLiveDrainStalledException` is never raised, and `essentials.cdc.backfill_live.stall_detected`
stays at 0 - all three kept so existing configuration, code and dashboards still work. An
event at or below the head that the backfill could not see is delivered once the backfill
is done.

### Logical decoding plugins

CDC abstracts the WAL plugin behind `LogicalDecodingPlugin`. Two implementations:

| Plugin            | Class                              | Notes                                                                                                          |
| ----------------- | ---------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `pgoutput`        | `PgOutputLogicalDecodingPlugin`    | **Default**. Built into PostgreSQL. Binary protocol. Requires a **publication**. Recommended for production.   |
| `wal2json`        | `Wal2JsonLogicalDecodingPlugin`    | JSON text protocol. Requires the `wal2json` extension to be installed on the server. Easier to debug visually. |

Plugin choice affects:

- **Slot creation options** (`slotOptions()`): pgoutput needs `proto_version` /
  `publication_names` / `binary` / `messages`; wal2json needs `include-xids` /
  `include-timestamp` / etc.
- **Whether a publication is required**: pgoutput **yes**, wal2json **no**.
- **Pre-filtering**: pgoutput peeks at the binary message header to drop unrelated
  rows before they hit the inbox; wal2json filters via regex against the JSON payload.

---

## 3. Replication Slot — Configuration & Management

Logical replication in PostgreSQL hangs off a per-database **replication slot**. The
slot is what tells PostgreSQL "this consumer hasn't acknowledged WAL up to position
X yet, retain everything ≥ X." Everything operationally interesting about CDC traces
back to the slot.

### 3.1 Slot naming

Slot names are generated by [`CdcSlotNameProvider`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcSlotNameProvider.java).
The default implementation [`DefaultCdcSlotNameProvider`](DefaultCdcSlotNameProvider.java:42)
produces:

```
essentials_<consumer-group>_<database-name-or-alias>
```

So with `cdc.slot.group=default` and database `mydb`, the slot is named
`essentials_default_mydb`. Hyphens are normalised to underscores; the result is
validated against PostgreSQL's identifier rules.

Override the consumer-group via [`CdcProperties.slot.group`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java) when
you want multiple independent CDC pipelines (e.g. one per bounded context) sharing the
same database. See the next section for what that looks like in practice.

### 3.2 Consumer groups — multiple independent CDC pipelines on one database

A **consumer group** is an opaque label that scopes one CDC pipeline end-to-end. The
group name flows into the replication-slot name (§3.1), the advisory lock that fences
the tailer (§3.3), and the `slot_name` column every dispatcher uses to claim its own
inbox rows. Different groups have different slot names → different advisory locks →
different tailers → genuinely independent delivery. They can share the same PostgreSQL
database, the same pgoutput publication, and the same physical inbox table without
coordination.

The pattern fits when you want one of:

- **One pipeline per bounded context.** `orders` and `billing` deployments each
  consume their own slot. A backlog in `orders` (slow dispatcher, paused for
  maintenance, etc.) only retains WAL behind the `orders` slot — `billing`'s slot
  keeps draining. **Blast-radius isolation** at the WAL-retention level.
- **Independent deployment lifecycles.** Roll out a new version of the `analytics`
  pipeline without touching `orders`. Each group has its own
  `CdcEffectivenessMonitor`, its own backoff state, its own `essentials.cdc.slot.*`
  metrics.
- **Read-replica style: one fast pipeline, one slow.** A real-time projection
  pipeline alongside a daily-batch analytics pipeline. They consume the same WAL,
  at independent rates, without one starving the other.

What's per-group vs shared:

| Resource | Scope | Why |
|---|---|---|
| Replication slot | **per group** | Slot name = `essentials_<group>_<db>`; each group's tailer holds its own slot's lock and ack cycle |
| Advisory lock (tailer leadership) | **per group** | Lock key derived from slot name; multiple groups never contend |
| `CdcEffectivenessMonitor` | **per group** | Each group's monitor watches its own slot; one stuck group doesn't fail the others |
| `essentials.cdc.slot.*` / `inbox.*` metrics | **per group** (tagged by slot) | Dashboards filter by `slot=essentials_<group>_<db>` |
| pgoutput publication | **shared by default** | Publication just enumerates tables; both groups can subscribe to the same `essentials_cdc_publication` |
| `eventstore_cdc_inbox` table | **shared physical table, partitioned by `slot_name` column** | Every row carries its `slot_name`; dispatchers filter to their own slot via `WHERE slot_name = ?`. The `(slot_name, status, inbox_id)` index makes this an index-only scan. |
| Event-stream tables (`*_events`) | **shared** | The database's source of truth — every group consumes the same WAL, sees the same INSERTs |
| `CdcEventBus` (in-memory) | **per JVM** | Each deployment has its own bus; cross-group fan-out goes through the WAL, not the bus |

Each consumer group **requires its own deployment** — Spring autoconfig wires
exactly one `CdcConsumerGroup` bean per application context. Two groups in the
same JVM isn't supported; spin up two deployments instead.

#### Config example — `orders` and `billing` side by side

Both pipelines target the same PostgreSQL `events_db`. Each runs as its own
deployment / Helm chart / docker-compose service, with only `cdc.slot.group`
differing.

`orders` deployment (`application.yml`):

```yaml
essentials:
  eventstore:
    cdc:
      enabled: true
      mode: AUTO
      plugin: pgoutput
      slot:
        group: orders            # → slot 'essentials_orders_events_db'
      pg-output:
        publication-name: essentials_cdc_publication  # shared
```

`billing` deployment (`application.yml`):

```yaml
essentials:
  eventstore:
    cdc:
      enabled: true
      mode: AUTO
      plugin: pgoutput
      slot:
        group: billing           # → slot 'essentials_billing_events_db'
      pg-output:
        publication-name: essentials_cdc_publication  # same publication — fine
```

Result on the server side:

```sql
SELECT slot_name, plugin, active, active_pid FROM pg_replication_slots WHERE slot_name LIKE 'essentials_%';
--   essentials_orders_events_db   | pgoutput | t | 12345
--   essentials_billing_events_db  | pgoutput | t | 12346
```

Both slots advance independently. The inbox table:

```sql
SELECT slot_name, count(*) FROM eventstore_cdc_inbox GROUP BY slot_name;
--   essentials_orders_events_db   | 42  -- orders backlog
--   essentials_billing_events_db  | 0   -- billing keeps up
```

#### Tuning the membership scope

By default both deployments share the same `essentials_cdc_publication` covering
every event-stream table the framework knows about. The pgoutput plugin filters
per-row on the *client* side — each deployment's tailer ignores rows for tables
it doesn't care about (the `PgOutputRawPayloadFilter` from §2 uses the live
`eventStreamTableNamesSupplier` of the JVM's registered aggregates).

If you'd rather move that filter to the *server* side, give each group its own
publication and register only the relevant tables:

```yaml
# orders deployment
essentials:
  eventstore:
    cdc:
      pg-output:
        publication-name: essentials_orders_publication
        publication:
          auto-manage: true
          mode: FOR_TABLE_LIST    # only orders_events lands in this publication
```

Trade-off: per-group publications cost a bit more on the PostgreSQL side
(WAL-sender filters twice — once per slot's publication, once per row) but
shrink the network bytes per slot and avoid the client doing the discard work.
Useful if your `billing` deployment is on a slow link or if you need
fine-grained pgoutput-level isolation. For typical multi-bounded-context
deployments on the same cluster, the shared publication is fine.

#### Gotchas

- **A decommissioned group leaves an orphaned slot.** If `analytics` deployment
  is removed without first dropping its slot, `essentials_analytics_events_db`
  persists in `pg_replication_slots` and grows WAL forever. Same orphaned-slot
  story as §13.1; the `essentials.cdc.slot.inactive_since_seconds` gauge plus
  the runbook drop-slot SQL apply per group. The
  [`run-orphaned-slot.sh`](../../../../examples/essentials-performance-lab/scripts/run-orphaned-slot.sh)
  perf-lab scenario validates the lifecycle for a single group; the same shape
  works per group.
- **Subscriber resume-points are NOT per-group.** The `durable_subscriptions`
  table is keyed on `(subscriber_id, aggregate_type)`, not on the consumer
  group. Two deployments that share a `SubscriberId` will collide on resume-
  point writes — they overwrite each other's positions and one mysteriously
  rewinds. **Always namespace subscriber IDs by group name in multi-group
  deployments.** See "Namespacing subscriber IDs" below for the recommended
  pattern; framework-level scope-by-group is tracked as **P7** in
  [cdc-improvements.md](cdc-improvements.md).
- **Backfill polls the same `*_events` table.** Both groups doing initial
  backfill at the same time means duplicate read load on the source table. For
  large historical windows, stagger group starts or use replica reads — but
  steady-state delivery has no read amplification on the source.
- **Slot-recreate-on-start is per-group.** Setting `cdc.slot.recreate-on-start=true`
  in the `orders` deployment only drops `essentials_orders_events_db`, not
  `essentials_billing_events_db`. Each group manages its own slot lifecycle.

#### Namespacing subscriber IDs

The `durable_subscriptions` table is keyed on `(subscriber_id, aggregate_type)`
— **no consumer group dimension**. Two deployments sharing the same database
that both register a `SubscriberId.of("realtime-projector")` for the same
`AggregateType` will collide on the same row. They overwrite each other's
resume points. Symptoms: one deployment's projector mysteriously rewinds to an
older position every time the other deployment writes its resume point.
Typically caught in week 3 of multi-group production after an unusual restart
ordering.

The framework provides [`CdcConsumerGroup.namespaced(SubscriberId)`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcConsumerGroup.java)
to prefix any subscriber ID with the active consumer group's name. Use it on
every subscription in any deployment that *could ever* run alongside another
consumer group (i.e. always — the cost is zero, the safety is real):

```java
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcConsumerGroup;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

@Component
class RealtimeProjector {
    private final EventStoreSubscriptionManager subscriptionManager;
    private final CdcConsumerGroup              consumerGroup;

    public RealtimeProjector(EventStoreSubscriptionManager subscriptionManager,
                             CdcConsumerGroup consumerGroup) {
        this.subscriptionManager = subscriptionManager;
        this.consumerGroup       = consumerGroup;
    }

    @PostConstruct
    void start() {
        subscriptionManager.subscribeToAggregateEventsAsynchronously(
            // "realtime-projector" → "orders.realtime-projector" in the orders deployment,
            // "billing.realtime-projector" in the billing deployment. Two deployments,
            // two rows in durable_subscriptions, zero collision.
            consumerGroup.namespaced(SubscriberId.of("realtime-projector")),
            ORDERS,
            GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
            this::onEvent);
    }
}
```

What it produces, with a default `cdc.slot.group=default`:

| Application call | Stored as |
|---|---|
| `consumerGroup.namespaced(SubscriberId.of("realtime-projector"))` | `SubscriberId.of("default.realtime-projector")` |
| `consumerGroup.namespaced(SubscriberId.of("audit-stream"))` | `SubscriberId.of("default.audit-stream")` |

And in `orders` deployment (`cdc.slot.group=orders`):

| Application call | Stored as |
|---|---|
| `consumerGroup.namespaced(SubscriberId.of("realtime-projector"))` | `SubscriberId.of("orders.realtime-projector")` |

If you're migrating an existing single-group deployment to start using
`namespaced`, the change is **breaking for resume points**: the new prefixed
subscriber ID won't find the old row in `durable_subscriptions` and will start
fresh. Two safe migration options:

1. **Replay-tolerant migration**: switch to `namespaced` IDs, accept that
   subscribers replay from `FIRST_GLOBAL_EVENT_ORDER` on the first deploy.
   Fine for idempotent projectors.
2. **One-time SQL backfill**: before switching code, copy the existing rows
   to the namespaced form:
   ```sql
   INSERT INTO durable_subscriptions (subscriber_id, aggregate_type,
                                      resume_from_and_including_global_eventorder,
                                      last_updated)
   SELECT 'default.' || subscriber_id, aggregate_type,
          resume_from_and_including_global_eventorder, last_updated
   FROM durable_subscriptions
   WHERE subscriber_id NOT LIKE '%.%';
   ```
   Replace `'default.'` with your actual group name.

### 3.3 Slot ownership & contention

A logical replication slot can have **at most one active reader** at a time. With
multiple JVMs in a cluster, the framework picks one tailer per slot via a PostgreSQL
**advisory lock** keyed on a deterministic hash of the slot name:

- Acquired via `pg_try_advisory_lock(...)` at tailer start.
- Held for the tailer's lifetime; released explicitly on stop and implicitly on
  connection loss.
- Other JVMs back off (logged at INFO once, WARN every 20 attempts) and retry —
  the moment the holder dies, another node takes over.

The slot's `active_pid` column is **not** used for ownership — only for diagnostics.

### 3.4 Slot lifecycle modes (`PgSlotMode`)

Configured via [`CdcProperties.slot.mode`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java):

| Mode                | Behaviour                                                                                                                            | Use case                            |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------- |
| `CREATE_IF_MISSING` | Create slot at first start if absent; validate it on every subsequent start. Default.                                                | Most production deployments.        |
| `REQUIRE_EXISTING`  | Fail startup if slot is missing. Useful when DBA/migration owns slot creation.                                                       | Strictly DBA-managed environments.  |
| `RECREATE`          | Drop + create at startup. Refuses to drop an active slot.                                                                            | Tests, ephemeral envs.              |
| `EXTERNAL`          | Never touch the slot. Just validate that it exists, is logical, and uses the expected plugin.                                        | Slot managed entirely out-of-band.  |

In all non-`EXTERNAL` modes, [`PgReplicationSlots.ensureSlot`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/PgReplicationSlots.java)
runs at startup and will **fail fast** with a clear `SQLException` if:

- **identity check** — the slot exists but is **physical** (wrong type), uses an
  **unexpected plugin** (e.g. wal2json slot but `cdc.plugin=pgoutput`), is
  **temporary** (expected to be persistent), or is currently **active** (some other
  process is streaming from it);
- **health check** — the slot exists but is **degraded** at the server side:
  `wal_status` is `extended`/`unreserved`/`lost` (PG ≥ 13), or `conflicting=true`
  (PG ≥ 16), or `invalidation_reason` is set (PG ≥ 16). The exception message
  includes ready-to-run remediation SQL.

`RECREATE` mode skips the health check (it's about to drop the slot anyway), but
still runs the identity check so the framework doesn't accidentally drop a slot
owned by another tool. Older PostgreSQL versions that don't expose
`wal_status`/`conflicting`/`invalidation_reason` columns pass the health check
unchanged — there is no false-positive on unsupported servers.

There is also a separate opt-in `cdc.slot.recreateOnStart=true` that combines RECREATE
with **forcibly terminating** any backend currently attached to the slot, then
dropping and re-creating it. Destructive — appropriate only for dev/test/perf-lab
environments. Never enable in production: any unacknowledged WAL changes are silently
discarded.

### 3.5 Slot validation diagnostics

`SlotInfo` (returned by `PgReplicationSlots.findSlot`) exposes everything from
`pg_replication_slots`: `slotType`, `plugin`, `database`, `activePid`,
`restartLsn`, `confirmedFlushLsn`, `walStatus`, `safeWalSize`, `inactiveSince`,
`conflicting`, `invalidationReason`. The tailer logs a snapshot at every
connect-attempt for operator triage.

The tailer also exposes a live snapshot via
[`WalReplicationTailer.getSlotStateSnapshot()`](WalReplicationTailer.java:1164),
returning `SlotState(slotName, active, confirmedFlushLsn, lagBytes)` where
`lagBytes = pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)`. The
effectiveness monitor includes this in its failure logs so the root cause of a stuck
CDC pipeline is visible without anyone running SQL by hand.

---

## 4. Publication — Configuration & Management (pgoutput only)

When using the `pgoutput` plugin, the tailer subscribes to a **publication** —
a server-side declaration of "which tables' DML changes should appear on this slot's
stream." `wal2json` does not use publications; skip this section if you're on wal2json.

### 4.1 Publication name

Configured via `cdc.pgOutput.publicationName` (default: `essentials_cdc_publication`).
A single publication can serve multiple slots — naming is independent of slot naming.

### 4.2 Publication scope: `FOR ALL TABLES` vs `FOR TABLE <list>`

PostgreSQL supports two publication shapes:

| Mode             | Coverage                                                          | Required privilege  | Trade-off                                                                                                                                 |
| ---------------- | ----------------------------------------------------------------- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `FOR ALL TABLES` | Every current and future table in the database.                   | **Superuser**       | Simplest. Picks up new aggregates without intervention. Operationally too broad for shared/multi-tenant prod databases.                   |
| `FOR TABLE …`    | Explicit list of `event_stream_*` tables, extended via `ALTER`.   | Table ownership     | Tightly scoped. Picks up new aggregates only after the framework runs `ALTER PUBLICATION ... ADD TABLE` on subsequent startup.            |

### 4.3 Auto-management

Configured via `cdc.pgOutput.publication.autoManage` (default `false`) and
`cdc.pgOutput.publication.mode` (default `FOR_TABLE_LIST`).

When `autoManage=true`, on tailer startup
[`PgOutputLogicalDecodingPlugin.prepare`](PgOutputLogicalDecodingPlugin.java:119) will:

1. Look up the publication via `pg_publication`.
2. If missing: `CREATE PUBLICATION <name> FOR <ALL TABLES | TABLE <list>>`.
3. If present and `mode=FOR_TABLE_LIST`: `ALTER PUBLICATION ... ADD TABLE …` for any
   registered event-stream tables not already in the publication's member list.
4. If present and `forAllTables()`: nothing to do; FOR-ALL-TABLES already covers
   everything.
5. **Never converts** an explicit-list publication into FOR-ALL-TABLES (that requires
   destructive DROP+CREATE).

If auto-manage hits a permission error (most commonly: not superuser when trying
FOR-ALL-TABLES, or not the table owner for FOR-TABLE), it logs a **WARN with
ready-to-run remediation SQL** and continues without managing the publication. The
tailer will still attempt to stream; if the publication really is unusable
[`unusableReason`](PgOutputLogicalDecodingPlugin.java:76) will surface it loudly.

Most production deployments leave `autoManage=false` and provision via migration:

```sql
-- Option A: explicit list (recommended for shared/multi-tenant DBs)
CREATE PUBLICATION essentials_cdc_publication
  FOR TABLE public.events,            -- one row per registered event_stream_table
            public.orders_events,
            public.billing_events;

-- Option B: FOR ALL TABLES (requires superuser; simplest)
CREATE PUBLICATION essentials_cdc_publication FOR ALL TABLES;
```

Adding a new aggregate later means a corresponding migration:

```sql
ALTER PUBLICATION essentials_cdc_publication ADD TABLE public.shipments_events;
```

(Auto-manage handles this for you when enabled.)

### 4.4 Verifying a publication

```sql
SELECT pubname, puballtables FROM pg_publication WHERE pubname = 'essentials_cdc_publication';
SELECT * FROM pg_publication_tables WHERE pubname = 'essentials_cdc_publication';
```

The tailer's startup logs include a "publication membership vs registered tables"
diff so any missing coverage is plain in the logs.

---

## 5. WAL Retention — The Real Operational Risk

This is **the** failure mode to design for. Everything else is recoverable; an
unbounded slot is not.

### 5.1 Why slots can grow unbounded

A logical slot retains every WAL segment back to its `restart_lsn` so that a
disconnected consumer can resume without data loss. PostgreSQL will refuse to
recycle WAL files past that point — even if `max_wal_size` is exceeded. The disk
fills, and the database eventually goes read-only or worse.

The slot's `confirmed_flush_lsn` advances **only** when a consumer acknowledges
back. For us that means: **only the live WalReplicationTailer advances the slot.**

### 5.2 How CDC keeps the slot moving

Three mechanisms in [`WalReplicationTailer`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/WalReplicationTailer.java):

1. **Per-message ack** ([`acknowledge`](WalReplicationTailer.java:1096)): every WAL
   message that's successfully written to the inbox (or, in DIRECT mode, handed to
   the bus) is followed by `stream.setFlushedLSN(lsn)` + `forceUpdateStatus()`. The
   server moves `confirmed_flush_lsn` forward immediately.

2. **Idle LSN push** ([`forceIdleLsnPush`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/WalReplicationTailer.java)): every
   `cdc.walReplicationTailer.idleLsnPushInterval` (default 30s), if the stream
   produced no messages the tailer still pushes the current receive LSN as a
   flushed ack. This matters because pgoutput with `FOR TABLE <list>` (or
   pre-filtering) can cause the server to read-and-skip large amounts of WAL
   without ever delivering a message to the client. Without the idle push,
   `confirmed_flush_lsn` would stick at the slot's start position and WAL would
   accumulate even on a perfectly healthy slot.

3. **Stale-stream reconnect** (`maxIdleDuration`, default 5 min): if no message at
   all has arrived in that window — including the keep-alives that PostgreSQL sends
   every `wal_sender_timeout / 2` — the tailer assumes the connection has gone dark
   (half-open TCP, server-side state lost) and forces a reconnect, which restarts
   the slot's `restart_lsn` ack cycle from scratch.

### 5.3 What can still cause the slot to grow

| Cause                                                      | Detection                                                                                              | Remediation                                                                          |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------ |
| **Tailer is dead** (JVM crash, deployment removed CDC)     | `pg_replication_slots.active = false` for the slot, growing `lag_bytes`.                               | Restart the app. If the app is permanently gone: `SELECT pg_drop_replication_slot('…')`. |
| **Slot is orphaned** (renamed group, abandoned slot)       | Slot exists in `pg_replication_slots` but no app references its name.                                  | Manually drop the slot.                                                              |
| **Tailer connected but stuck in retry loop**               | Tailer `connect.failures` metric climbs; `essentials.cdc.active` gauge = 0.                            | Investigate logs; common causes: revoked replication privilege, plugin missing.       |
| **Effectiveness monitor flips FAILED but slot still alive**| `essentials.cdc.fallback_total` climbs (a real fallback — CDC had been active); subscribers fall back to polling. | Subscribers stay correct. Investigate dispatcher/decode failures in logs.            |
| **Long downtime + high write rate**                        | Predictable on restart: slot lag = bytes written during downtime.                                      | Either let the tailer catch up (WAL is bounded by your write volume × downtime), or recreate the slot if the backlog isn't worth replaying (you'll get backfill from polling). |
| **PostgreSQL restart with replication slot left active**   | `wal_status = 'lost'` or `'unreserved'`; `conflicting = true`.                                         | Slot is unrecoverable — drop and recreate. Backfill picks up the missed events.       |

### 5.4 Monitoring queries

Run these as part of routine DB health checks:

```sql
-- Per-slot lag and health
SELECT slot_name,
       active,
       wal_status,                -- 'reserved' (good), 'extended' (over max_slot_wal_keep_size),
                                  -- 'unreserved' (lost), 'lost' (recycled, slot dead)
       safe_wal_size,             -- estimated bytes of WAL still safely retained
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS lag,
       confirmed_flush_lsn,
       restart_lsn,
       inactive_since,
       conflicting,
       invalidation_reason
FROM pg_replication_slots
WHERE slot_name LIKE 'essentials_%';

-- Total disk usage for WAL
SELECT pg_size_pretty(SUM(size)) AS total_wal_size FROM pg_ls_waldir();
```

### 5.5 Alerting recommendations

- `wal_status != 'reserved'` → page immediately (slot is in or near loss).
- `pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) > <X> GB` → warn.
  Pick `X` based on your `max_wal_size` and free disk; a sensible default is
  10–20% of disk free space.
- `active = false AND inactive_since > 1 hour` → warn (orphaned slot risk).
- `essentials.cdc.fallback_total` rising → CDC was working and stopped. This counter excludes startup
  warm-up polls, so any increase is real and worth paging on.
- `essentials.cdc.interruptions_total` rising steadily → the replication connection keeps dropping, even though
  each drop recovers on its own. Look at what sits between the application and PostgreSQL: a proxy or load
  balancer idle timeout, `wal_sender_timeout`, or a host that is being suspended.
- `essentials.cdc.eventstore.fallback.poll.count` rising while `essentials.cdc.active = 0`
  for an extended period → CDC isn't recovering on its own, investigate. Note this counter also ticks
  during normal startup; the "for an extended period" qualifier is what keeps it from firing on every boot.
- `essentials.cdc.warmup_poll_total > 0` with `essentials.cdc.active = 0` well past startup → CDC never
  became active at all. Check the slot, the publication and the tailer.

### 5.6 Belt-and-braces: bound retention server-side

Set `max_slot_wal_keep_size` in `postgresql.conf` (e.g. `10GB`). PostgreSQL will
invalidate slots that exceed this rather than letting WAL fill the disk. Slot
invalidation **causes data loss for that slot's consumer**, so the framework's
fallback to polling becomes load-bearing — but it's preferable to a downed
database. The CDC pipeline detects an invalidated slot at next reconnect and
either recreates (per `PgSlotMode`) or fails startup (per `cdc.mode`).

The tailer logs an INFO advisory once per JVM start when the server has
`max_slot_wal_keep_size = -1` (unbounded — the default), so this isn't easy to
forget. The check is purely informational: it never fails startup.

---

## 6. CDC Modes & Failure Semantics

### 6.1 `cdc.enabled` and `cdc.mode` (`CdcMode`)

| `cdc.enabled` | `cdc.mode` | Behaviour                                                                                                              |
| ------------- | ---------- | ---------------------------------------------------------------------------------------------------------------------- |
| `false`       | (n/a)      | **Default.** CDC is off — no beans are created, no slot, no publication changes. Subscribers always poll.              |
| `true`        | `AUTO`     | **Default once enabled.** CDC starts up; on failure, subscribers transparently fall back to polling. Recommended.      |
| `true`        | `REQUIRE`  | CDC startup failures **fail application startup**. Use when you need strict guarantees that CDC is the delivery path.  |

See §1.1 for the full opt-in checklist.

### 6.2 `CdcAvailability` state machine

| State      | Meaning                                                                                  | Subscriber behaviour                          |
| ---------- | ---------------------------------------------------------------------------------------- | --------------------------------------------- |
| `INACTIVE` | Tailer not yet handshaken (booting or in reconnect backoff).                             | Polling.                                      |
| `ACTIVE`   | Tailer handshaken successfully; pipeline considered healthy.                             | CDC live bus + backfill (BackfillThenLiveOrdered). |
| `FAILED`   | Tailer error OR effectiveness monitor detected stuck delivery.                           | Polling (cutback debounced — see below).      |

`CdcEventStore` watches availability transitions and switches the live source
mid-subscription:

- **ACTIVE → FAILED / INACTIVE**: cuts back to polling **immediately** (subscribers
  shouldn't stall on a dead live stream).
- **FAILED → ACTIVE**: waits `activeCutbackDebounce` (default = monitor interval,
  60s) of steady ACTIVE before switching back. Prevents thrash during oscillation.
  Polling keeps delivering meanwhile, and the switch back catches up on whatever polling
  had not fetched before it hands over to the bus (see [§2](#2-architecture)).

### 6.3 `CdcEffectivenessMonitor`

Background probe that runs every `cdc.healthCheck.interval` (default 60s), only in
INBOX mode. Detects two failure modes that look "ACTIVE" from the tailer's
perspective but aren't actually delivering events:

1. **Stuck delivery**: tailer sees ≥`messagesReceivedThreshold` (default 1000)
   messages in a window but the dispatcher published zero events. Common cause:
   pgoutput pre-filter dropping everything because the publication doesn't include
   any event-stream tables (misconfiguration), or the dispatcher silently dropping
   rows.
2. **Dispatcher dead**: dispatcher tick counter didn't move within
   `dispatcherIdleGracePeriod` (default 120s) — scheduler crashed or stuck.

When a fire happens the monitor flips availability to `FAILED` (subscribers fall
back to polling). The fail message includes the live `SlotState` snapshot so the
slot's lag and confirmed_flush_lsn are visible without running SQL.

Optional `autoRecreateSlotOnStuck=true` (default `false`): after
`recreateSlotAfterConsecutiveFires` consecutive fires (default 3) without a
recovery, the monitor drops and recreates the slot. The fresh slot starts at
`pg_current_wal_lsn()` so any historical backlog is intentionally discarded —
backfill via polling handles continuity. Logged loudly. Treat as a last-resort
self-heal.

### 6.4 Poison handling

When [`CdcDispatcher`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcDispatcher.java) fails to decode a row, the
`PoisonPolicy` decides:

- `QUARANTINE_AND_CONTINUE` (default):
  1. Mark the inbox row `POISON` with the decode error.
  2. Best-effort extract the affected global-orders from the WAL payload via
     `LogicalDecodingPlugin.extractGaps()`.
  3. Register those as **permanent gaps** with the EventStore's gap handler.
  4. Notify [`CdcPoisonNotifier`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcPoisonNotifier.java) — the default
     [`SubscriptionResetOnPoisonNotifier`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/SubscriptionResetOnPoisonNotifier.java)
     resets affected subscribers so they re-fetch from polling.
  5. Continue with the next row.
- `STOP`: dispatcher stops; in `cdc.mode=AUTO` everything falls back to polling.

Resume points are **never moved past a poison row** by polling either, so manual
operator action is required to actually drop a poison row from delivery
expectations (this is intentional — silently skipping is worse than stalling).

---

## 7. Configuration Reference

All keys live under `cdc.*` in [`CdcProperties`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java).

### 7.1 Top-level

| Property                              | Default                          | Purpose                                                              |
| ------------------------------------- | -------------------------------- | -------------------------------------------------------------------- |
| `cdc.enabled`                         | `true`                           | Master switch.                                                       |
| `cdc.mode`                            | `AUTO`                           | `AUTO` (fall back to polling) or `REQUIRE` (fail startup).           |
| `cdc.plugin`                          | `pgoutput`                       | `pgoutput` or `wal2json`.                                            |
| `cdc.deliveryMode`                    | `INBOX`                          | `INBOX` (durable buffer) or `DIRECT` (no inbox).                     |
| `cdc.walParserMode`                   | `STRING`                         | wal2json only: `STRING` or `BYTES`.                                  |
| `cdc.cdcEventStoreBackfillBatchSize`  | `1000`                           | Backfill page size during BackfillThenLiveOrdered.                   |
| `cdc.inboxTableName`                  | `eventstore_cdc_inbox`           | Inbox table name.                                                    |
| `cdc.inboxTtlDurationDays`            | `90`                             | Auto-purge dispatched/poison rows older than this.                   |

### 7.2 `cdc.slot` ([`CdcSlotProperties`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java))

| Property                       | Default              | Purpose                                                                  |
| ------------------------------ | -------------------- | ------------------------------------------------------------------------ |
| `cdc.slot.group`               | `default`            | Consumer-group name; feeds slot-name generation.                         |
| `cdc.slot.name`                | (derived)            | Optional override of generated name.                                     |
| `cdc.slot.mode`                | `CREATE_IF_MISSING`  | See §3.4.                                                                |
| `cdc.slot.recreateOnStart`     | `false`              | **Destructive.** Force-drop and recreate at every startup. Dev/test only.|
| `cdc.slot.metricsEnabled`      | `true`               | Master switch for `CdcSlotMetrics` (publishes `essentials.cdc.slot.*` gauges).|
| `cdc.slot.metricsInterval`     | `30s`                | Cadence at which `pg_replication_slots` is re-sampled to refresh slot gauges.|

### 7.3 `cdc.pgOutput` ([`PgOutputProperties`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java))

| Property                                      | Default                          | Purpose                                                              |
| --------------------------------------------- | -------------------------------- | -------------------------------------------------------------------- |
| `cdc.pgOutput.publicationName`                | `essentials_cdc_publication`     | Publication subscribed to.                                           |
| `cdc.pgOutput.protoVersion`                   | `1`                              | pgoutput protocol version (negotiated with server).                  |
| `cdc.pgOutput.binary`                         | `false`                          | Use binary protocol.                                                 |
| `cdc.pgOutput.messages`                       | `false`                          | Include logical-message envelopes.                                   |
| `cdc.pgOutput.publication.autoManage`         | `false`                          | Have framework CREATE/ALTER the publication.                         |
| `cdc.pgOutput.publication.mode`               | `FOR_TABLE_LIST`                 | `FOR_TABLE_LIST` or `FOR_ALL_TABLES`.                                |

### 7.4 `cdc.walReplicationTailer` ([`WalReplicationTailerProperties`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java))

| Property                                                  | Default          | Purpose                                                                                              |
| --------------------------------------------------------- | ---------------- | ---------------------------------------------------------------------------------------------------- |
| `cdc.walReplicationTailer.pollInterval`                   | `25ms`           | How often `readPending()` is called when the stream is active.                                       |
| `cdc.walReplicationTailer.pollBackoffInterval`            | `250ms`          | Initial backoff after connect failures.                                                              |
| `cdc.walReplicationTailer.maxPollBackoffInterval`         | `5s`             | Ceiling on exponential reconnect backoff.                                                            |
| `cdc.walReplicationTailer.replicationStatusInterval`      | `1s`             | Heartbeat interval to server.                                                                        |
| `cdc.walReplicationTailer.jitterRatio`                    | `0.2`            | Jitter applied to backoff sleeps.                                                                    |
| `cdc.walReplicationTailer.backOffFactor`                  | `2`              | Exponential factor.                                                                                  |
| `cdc.walReplicationTailer.maxIdleDuration`                | `5min`           | Force reconnect if no message arrives in this window. `Duration.ZERO` disables.                      |
| `cdc.walReplicationTailer.idleLsnPushInterval`            | `30s`            | Cadence at which the tailer force-acks the current receive LSN on an idle stream so `confirmed_flush_lsn` keeps advancing. Tighten only if `wal_sender_timeout` is below 60s; cannot be disabled. |
| `cdc.walReplicationTailer.includeXids` / `Timestamp` / `Lsn` / `prettyPrint` | (varies) | wal2json slot options.                                                                          |

### 7.5 `cdc.cdcDispatcher` ([`CdcDispatcherProperties`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java)) — INBOX mode only

| Property                                  | Default                     | Purpose                                                                                  |
| ----------------------------------------- | --------------------------- | ---------------------------------------------------------------------------------------- |
| `cdc.cdcDispatcher.pollInterval`          | `20ms`                      | How often the dispatcher polls the inbox.                                                |
| `cdc.cdcDispatcher.batchSize`             | `500`                       | Max rows per dispatcher tick.                                                            |
| `cdc.cdcDispatcher.poisonPolicy`          | `QUARANTINE_AND_CONTINUE`   | `QUARANTINE_AND_CONTINUE` or `STOP`.                                                     |
| `cdc.cdcDispatcher.dispatchedRowPolicy`   | `MARK_DISPATCHED`           | `MARK_DISPATCHED` (rely on TTL purge) or `DELETE` (immediate row removal after dispatch).|
| `cdc.cdcDispatcher.queryTimeout`          | `PT0S` (no timeout)         | Per-statement timeout on the dispatcher's `fetchNextBatch` poll query. `Duration.ZERO` defers to PG/JDBC/pool defaults; a positive value applies `Statement.setQueryTimeout(seconds)` and bounds the per-tick latency at the framework level. |
| `cdc.cdcDispatcher.inboxMetricsEnabled`   | `true`                      | Master switch for the `essentials.cdc.inbox.*` backlog/poison gauges. INBOX delivery only. Gauges sample on demand at metrics scrape time. |

### 7.6 `cdc.eventBus` ([`CdcEventBusProperties`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java))

| Property                                          | Default        | Purpose                                                              |
| ------------------------------------------------- | -------------- | -------------------------------------------------------------------- |
| `cdc.eventBus.backpressureBufferSize`             | `8192`         | Per-aggregate Reactor sink buffer size (fills only while an aggregate type has no subscriber), and the per-subscription `BackfillThenLiveOrdered` live events (buffered during backfill, queued for the subscriber, and asked for - together; it asks for more only as the subscriber takes them). Not the per-subscription live hand-over buffer: that is one polling page (see [§2](#2-architecture)). |
| `cdc.eventBus.nonSerializedMaxRetries`            | `16`           | Spin-retry count on `FAIL_NON_SERIALIZED` emit failures.             |
| `cdc.eventBus.overflowMaxRetries`                 | `20`           | Backoff retry count on `FAIL_OVERFLOW`.                              |
| `cdc.eventBus.liveDrainStallThreshold`            | `180s`         | **No effect** - kept so existing configuration still binds (must not be negative). `BackfillThenLiveOrdered` no longer waits for a missing global order, so there is no stall to detect (see "Commit order, not global order" in §2). |
| `cdc.eventBus.overflowPolicy`                     | `FAIL_FAST`    | `FAIL_FAST` (throw `CdcBusOverflowException`) or `LOG_AND_DROP`, for an event the bus cannot emit. A slow subscription does not lead here - it leaves the bus, catches up from the event store and rejoins it. |

### 7.7 `cdc.healthCheck` ([`CdcHealthCheckProperties`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java))

| Property                                                | Default     | Purpose                                                                            |
| ------------------------------------------------------- | ----------- | ---------------------------------------------------------------------------------- |
| `cdc.healthCheck.enabled`                               | `true`      | Master switch for the effectiveness monitor.                                        |
| `cdc.healthCheck.interval`                              | `60s`       | Evaluation window.                                                                 |
| `cdc.healthCheck.messagesReceivedThreshold`             | `1000`      | Minimum tailer messages-per-window before "stuck delivery" can fire.                |
| `cdc.healthCheck.dispatcherIdleGracePeriod`             | `120s`      | Grace period before declaring dispatcher dead on zero ticks.                        |
| `cdc.healthCheck.autoRecover`                           | `true`      | Keep monitoring after a fire, allow ACTIVE recovery.                                |
| `cdc.healthCheck.activeCutbackDebounce`                 | `60s`       | Steady-ACTIVE window before cutting back from polling to CDC.                       |
| `cdc.healthCheck.autoRecreateSlotOnStuck`               | `false`     | Opt-in self-heal: drop+recreate slot after N consecutive fires.                     |
| `cdc.healthCheck.recreateSlotAfterConsecutiveFires`     | `3`         | N for the rule above.                                                              |

#### Tuning `messagesReceivedThreshold` and `dispatcherIdleGracePeriod`

The two thresholds that gate the effectiveness monitor's two detectors deserve
a longer explanation — they're the difference between a useful "page me when
CDC is genuinely broken" signal and a noisy "page me whenever traffic is
quiet" signal.

##### `messagesReceivedThreshold` (default `1000`)

Used by the **stuck-delivery** detector ([`checkStuckDelivery`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcEffectivenessMonitor.java)).
At each evaluation tick the monitor computes two deltas over the elapsed window:

- `messagesReceivedDelta` — count of raw pgoutput/wal2json messages the tailer
  pulled off the replication stream since the previous tick. **Note: this is
  pgoutput protocol messages, not application events.** A single committed
  transaction with one row insert produces 3 messages (`B` Begin, `I` Insert,
  `C` Commit). 1 000 messages ≈ ~300 small transactions of WAL activity.
- `publishedDelta` — count of `PersistedEvent`s the dispatcher emitted to the
  CDC bus in the same window.

The detector fires only when **both** conditions hold:

```
messagesReceivedDelta >= messagesReceivedThreshold   AND   publishedDelta == 0
```

The threshold is the "is the system busy enough that I trust this signal?"
gate. Without it, the second condition (`publishedDelta == 0`) would fire
constantly on idle systems where the tailer legitimately has nothing to do —
the alarm would say "stuck" every time you go to lunch.

**A quiet WAL stream cannot trigger this detector.** If the tailer received
fewer than `messagesReceivedThreshold` messages in the window, the check
returns early — regardless of how long the quiet period lasts, and regardless
of whether the dispatcher published anything. The detector is *only* meaningful
when there's enough recent traffic to expect dispatcher activity.

| Workload character | Default `1000` behaviour | Suggested tuning |
|---|---|---|
| High-volume (≥ 100 events/s sustained) | Fires within seconds of a real outage | Leave at default |
| Medium (10–100 events/s) | Fires within tens of seconds | Leave at default; consider `200`–`500` if you want faster detection |
| Low (1–10 events/s) | Can take minutes to accumulate 1000 messages; outage detected late | Lower to `100`–`200` so the detector is responsive when there *is* something to deliver |
| Spiky / mostly idle (< 1 event/s for long periods) | Almost never fires — alarm only on the rare burst | Either accept the late detection OR rely on the **dispatcher-dead** detector instead (see below) |

**Failure modes**:

- *Too low* (say `10`): false positives during dispatcher backpressure. A
  legitimate ten-second pause in publishing during a GC pause or DB hiccup
  looks like "stuck delivery" to the monitor and trips a fallback-to-polling
  cycle. Visible as oscillating `essentials.cdc.fallback.poll.count` even
  when CDC is fundamentally healthy.
- *Too high* (say `100_000`): real CDC outages go undetected on low-volume
  systems. The slot lag grows silently until either an operator notices or
  `wal_status` flips degraded.

The default `1000` is calibrated for typical event-sourced workloads
(dozens to hundreds of events per second). Adjust based on the table above.

##### `dispatcherIdleGracePeriod` (default `120s`)

Used by the **dispatcher-dead** detector ([`checkDispatcherDead`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcEffectivenessMonitor.java)).
Catches the failure mode where the dispatcher's `ScheduledExecutorService`
has stalled — thread died, blocked indefinitely on a DB call, executor was
shut down by another component.

The check at each evaluation tick:

```
effectiveGrace = max(healthCheck.interval, dispatcherIdleGracePeriod)
if  elapsedMs >= effectiveGrace  AND  ticksDelta == 0:
    declare dispatcher dead
```

Two things to notice:

1. **`max(interval, …)`**: setting `dispatcherIdleGracePeriod` *below* the
   evaluation `interval` is a no-op. The framework refuses to evaluate
   dispatcher-dead more aggressively than the monitor's own tick cadence.
   With default `interval=60s` and `dispatcherIdleGracePeriod=120s`, the
   effective grace is `120s` — meaning two consecutive monitor evaluations
   must both observe zero ticks before flagging.
2. **`ticksDelta == 0`**: the dispatcher's tick counter increments **on a
   timer, not in response to WAL activity**. The dispatcher's scheduled task
   runs every `cdc.cdcDispatcher.pollInterval` (default 20 ms) and increments
   the tick counter on every iteration — whether the inbox had rows to fetch
   or returned zero. On a fully idle system with no WAL traffic the dispatcher
   still ticks ~50 times per second, doing empty-result fetches each time. So
   "zero ticks for two whole minutes" is **not** "no work to do" — it's
   "the executor itself stopped running."

The grace period exists to tolerate scheduler jitter — one health-check
window might legitimately see zero ticks if the dispatcher was blocked on a
long DB call exactly across the boundary. Requiring two consecutive windows
of total silence is what makes this signal reliable.

**A quiet WAL stream cannot trigger this detector either.** The tick counter
keeps incrementing on the dispatcher's internal timer regardless of WAL
volume. Zero ticks means the timer itself stopped firing — thread died,
executor was shut down externally, GC pause indefinitely long — not "the
inbox was empty."

| Operational scenario | Default `120s` behaviour | Suggested tuning |
|---|---|---|
| Production with on-call alerting | Detects dispatcher death within 1–2 monitor windows (60–120s) — fast enough that automated fallback to polling means no subscriber notices | Leave at default |
| Latency-sensitive deployment | Slightly faster detection wanted | Lower to `60s` (= interval); single bad window flags. Accept slightly higher false-positive rate |
| Lab / chaos testing | Want to *force* the detector to fire | Lower to `10s` + lower `interval` to `5s` to compress test runtime |
| Slow / contended database | Long-running queries occasionally pause the dispatcher for tens of seconds | Raise to `180s`–`300s` so transient slowness doesn't trip the alarm |

**Failure modes**:

- *Too low* (say `30s`): one slow DB query that blocks the dispatcher tick
  for half a minute trips the alarm. Subscribers cut to polling; CDC then
  comes back; bus reconnects; rinse-repeat. Manifests as oscillation in
  `essentials.cdc.eventstore.live_source.switch.count`.
- *Too high* (say `1800s`): genuine dispatcher death goes undetected for half
  an hour. Subscribers don't notice (polling fallback works) but
  CDC-bus-bound dashboards go dark. Slot lag is still bounded as long as the
  *tailer* is healthy.

The default `120s` is the conservative midpoint: long enough to absorb
multi-second scheduler hiccups, short enough that a real failure surfaces
within an alerting cycle.

##### How the two detectors complement each other

| Detector | Catches | Doesn't catch |
|---|---|---|
| Stuck delivery (`messagesReceivedThreshold`) | "Bytes are flowing but events aren't being published" — publication misconfig, pgoutput silently dropping inserts, conversion dropping every row | "Dispatcher isn't running" (no bytes flowing through it either, so `messagesReceivedDelta` is 0 and the threshold gate skips) |
| Dispatcher dead (`dispatcherIdleGracePeriod`) | "Dispatcher executor stopped ticking" — thread died, executor shut down, blocked indefinitely | "Dispatcher is ticking but dropping events" — ticks > 0, threshold gate fires, but the OTHER detector catches it |

Running both means you cover the two distinct ways the dispatcher path can
fail. They're cheap (run once per `healthCheck.interval`, two small subtractions
each) and orthogonal — keep both on.

##### Common misconception: "won't these fire when the WAL is quiet?"

No — and the design is deliberate.

- **Stuck delivery** is *gated* by `messagesReceivedThreshold`. If the tailer
  received fewer than the threshold's worth of pgoutput/wal2json messages in
  the window, the check returns early. The detector only fires when there's
  enough recent WAL traffic to make "dispatcher published 0" a meaningful
  contradiction.
- **Dispatcher dead** watches the dispatcher's **scheduled tick counter**,
  which is independent of WAL volume. The dispatcher's executor runs every
  ~20 ms regardless of inbox contents and bumps the counter each time —
  empty-result fetches still count. A quiet WAL means the dispatcher does
  thousands of `LIMIT 0` fetches per minute, all of which increment the tick
  counter. The detector fires only when the counter genuinely stops moving
  for two-plus monitor windows, which only happens if the executor itself
  has died.

If you've configured CDC for a low-volume / mostly-idle workload and want
*some* form of "is CDC even alive?" signal, the right metric isn't the
effectiveness monitor — it's the slot-health gauges from §8 (`essentials.cdc.slot.active`
should be `1`, `essentials.cdc.slot.lag_bytes` should be bounded). Those
work regardless of traffic level. The effectiveness monitor is for "CDC
*looks* active but isn't actually delivering" — a different failure mode
that, by definition, needs traffic to detect.

#### Bounding the dispatcher's per-tick query latency

The detectors above react *after the fact* — they notice that the dispatcher
hasn't ticked in a while. They don't *bound* how long a single tick can run.
For that, set `cdc.cdcDispatcher.queryTimeout`.

Default is `PT0S` ("no framework-imposed timeout"), in which case the
dispatcher's `fetchNextBatch` query inherits whatever the deployment's lower
layers provide:

| Layer | Default if you do nothing | Affects `fetchNextBatch`? |
|---|---|---|
| `Statement.setQueryTimeout` | 0 (unlimited) | Not set by framework when `queryTimeout=PT0S` |
| PostgreSQL `statement_timeout` GUC | 0 (unlimited) | Yes if set per-role / per-DB / `postgresql.conf` |
| pgjdbc URL `socketTimeout` param | 0 (wait forever) | Yes if set on the JDBC URL |
| HikariCP `connectionTimeout` | 30s | No — only governs *acquiring* a pool connection, not a query already in flight |
| HikariCP `validationTimeout` | 5s | No — only the connection-validation query |

For most Spring Boot + Hikari deployments the implicit defaults are fine —
`SELECT … FOR UPDATE SKIP LOCKED` is non-blocking by design (SKIP LOCKED
avoids row-lock waits), so the realistic ways for this query to hang are
"PG itself hung" or "network partitioned silently". Both are rare; both are
caught by the dispatcher-dead detector within ~2 minutes, after which
subscribers fall back to polling and no events are lost.

Set `queryTimeout` when one of the following applies:

- **You want a known per-tick SLA.** With `cdc.cdcDispatcher.queryTimeout=PT5S`,
  a hung dispatcher query is cancelled by PG after 5 seconds; the dispatcher's
  next tick fires on the configured `pollInterval` and tries again. Tail
  latency stays bounded regardless of PG-side hiccups.
- **You can't set `statement_timeout` server-side.** Managed-PG deployments
  sometimes restrict GUCs at the database level; the framework property gives
  you the same protection from the client side.
- **Defence-in-depth against thread leakage.** Without a timeout, a hung
  query holds the dispatcher's pool connection until the JVM restarts. A
  per-statement timeout caps that pinning.

When the timeout fires:

- PostgreSQL cancels the statement server-side (`PSQLException`, SQL state
  `57014` — "query canceled").
- The dispatcher's tick-error path catches it and treats it as a normal tick
  failure: increments `essentials.cdc.dispatcher.tick.failures`, logs a WARN,
  and retries on the next `pollInterval`.
- No subscriber impact — the dispatcher just tries again in 20 ms (default
  `pollInterval`).

Granularity is **seconds** (the framework rounds sub-second durations *up* to
1 second, since PG's statement-timeout machinery doesn't resolve below
seconds anyway). Setting `PT0.5S` gives you a 1-second effective timeout, not
500 ms.

| Workload character | Suggested `queryTimeout` |
|---|---|
| Latency-sensitive (real-time projections) | `PT5S` — strict SLA, willing to retry frequently |
| Standard (default Spring Boot + Hikari) | `PT0S` — rely on `statement_timeout` GUC + pgjdbc defaults |
| Hostile environment (slow / flaky network to PG) | `PT15S`–`PT30S` — bounded but tolerant |
| Lab / chaos testing | `PT2S` — surface timeout behaviour during fault injection |

---

## 8. Observability

All metrics under `essentials.cdc.*` (Micrometer).

### Tailer

- `essentials.cdc.<plugin>.connect.attempts` / `.success` / `.failures` (counters)
- `essentials.cdc.<plugin>.messages` (counter — raw messages received)
- `essentials.cdc.<plugin>.inbox.writes` / `.write_failures` / `.duplicates`
- `essentials.cdc.<plugin>.last_message_age_ms` (gauge)
- `essentials.cdc.<plugin>.null_polls` (counter)
- `essentials.cdc.<plugin>.slot_lock_acquired` (gauge: 0/1)

### Dispatcher

- `essentials.cdc.dispatcher.ticks` / `.tick.failures`
- `essentials.cdc.dispatcher.conversion.failures` / `.poison.rows` / `.gap_extraction.failures`
- `essentials.cdc.dispatcher.published.events`
- `essentials.cdc.dispatcher.poll.latency` / `.convert.latency` / `.publish.latency`
- `essentials.cdc.dispatcher.poll.batch_size` (gauge)

### Inbox

Registered on `CdcInboxRepository` at startup; sample on demand at metrics scrape time (no separate sampler). INBOX delivery only. Tagged `slot=<slotName>`.

- `essentials.cdc.inbox.received_backlog` (gauge — rows in `RECEIVED` status; growing value = dispatcher falling behind tailer)
- `essentials.cdc.inbox.poison_rows` (gauge — rows in `POISON` status; non-zero value warrants investigation)

### Replication Slot

Sampled every `cdc.slot.metricsInterval` (default 30s) by `CdcSlotMetrics`. Tagged `slot=<slotName>`.

- `essentials.cdc.slot.lag_bytes` (gauge — bytes of WAL retained past `confirmed_flush_lsn`; growing value = stuck slot)
- `essentials.cdc.slot.active` (gauge: 0/1 — streaming consumer attached)
- `essentials.cdc.slot.wal_status` (gauge — `0=UNKNOWN`, `1=RESERVED`, `2=EXTENDED`, `3=UNRESERVED`, `4=LOST`; alert `>1` = warn, `>2` = page)
- `essentials.cdc.slot.inactive_since_seconds` (gauge — seconds the slot has been inactive; `0` while active; combine with `slot.active=0` to detect orphaned slots)

### EventStore / Bus

- `essentials.cdc.active` (gauge: 0/1)
- `essentials.cdc.eventstore.fallback.poll.count` (counter — every poll that took the polling branch, **including
  startup warm-up**; see [Warm-up polls vs fallbacks](#warm-up-polls-vs-fallbacks))
- `essentials.cdc.eventstore.live_source.switch.count` (counter — mid-stream cutover on an availability change)
- `essentials.cdc.eventstore.live_source.overflow.count` (counter — a subscription fell more than one polling page
  behind the bus, left it, caught up from the event store and rejoined it; one per occurrence, with a WARN naming the
  subscriber and an INFO once it is back on the bus. A slow subscriber, not a CDC problem. A steadily climbing value
  for one subscriber means it cannot keep up with the bus and is served by catch-ups)
- `essentials.cdc.eventstore.backfill.page.latency` / `.loaded` / `.query_range`
- `essentials.cdc.backfill_live.buffer.size` (gauge — backfill→live handover buffer)
- `essentials.cdc.fallback_total` (counter — times a subscription started on, or switched to, polling **after**
  CDC had been active, i.e. a real regression; one per subscription per interruption. This is the one to alert on)
- `essentials.cdc.interruptions_total` (counter — times CDC stopped being active other than by a requested stop.
  One per outage, however many subscriptions it affected)
- `essentials.cdc.warmup_poll_total` (counter — subscriptions that started on polling because CDC had not
  become active yet. Expected on every startup; **not** an error)
- `essentials.cdc.start_failures_total` (with reason tag)

### Warm-up polls vs fallbacks

Availability starts `INACTIVE` and only becomes `ACTIVE` once the WAL tailer has connected and taken the slot.
The lifecycle starts subscriptions *before* that, so every subscription that comes up during boot legitimately
begins on the polling path and switches to the CDC bus once `CdcEventStore`'s adaptive live source has seen
`ACTIVE` last for `activeCutbackDebounce` - catching up first on what polling had not fetched (see
[§2](#2-architecture)).

Those startup polls are **warm-up polls**, counted by `essentials.cdc.warmup_poll_total` and reported as
`warmupPollCount`. They are not failures, and how many you get depends on a race between subscription startup
and tailer connection — typically one per subscription, but fewer if a subscription is slow to acquire its
fenced lock. A healthy application therefore reports a non-zero `warmupPollCount` and a **zero**
`fallbackCount` on every start.

`fallbackCount` / `essentials.cdc.fallback_total` count only polls that happen *after* CDC has been active at
least once — a genuine loss of CDC. Alert on that one; ignore warm-up. That covers both ways a subscription gets
there: starting while CDC is down, and a running subscription switching off the CDC bus when CDC stops being
active. The second used to go uncounted, so an outage every subscription polled through left `fallbackCount` at
zero.

### Interruptions

A fallback is counted per subscription; an **interruption** is counted once per outage: any time CDC stops being
`ACTIVE` other than by a requested stop — a dropped replication connection, a stream error, or the slot taken over
by another instance. The CDC status's `interruptions` section (health details `interruptions.*`, the admin UI's
CDC page) keeps the count, whether the latest one is still ongoing, and when and why it began and when CDC was
active again. That record outlives recovery on purpose: the availability `reason` describes the current state only
and is cleared when CDC becomes active again, so before this a dropped connection that reconnected within a second
left no trace in the status.

A typical interruption is a replication connection the server closed because the client went quiet, for example a
suspended laptop that outlived `wal_sender_timeout` (60 s by default). The tailer logs one WARN with the stack trace
(`CDC streamOnce failed`), reconnects within its backoff, and resumes from `confirmed_flush_lsn`; subscriptions poll
in between, so the cost is latency, not events.

`everActive` (health detail `everActive`) disambiguates the third case: `fallbackCount = 0` with
`everActive = false` and a non-zero `warmupPollCount` means CDC never came up at all and everything is being
served by polling. Zero fallbacks alone cannot tell that apart from a healthy run.

---

## 9. Operational Runbook

### "Slot is growing — disk usage climbing"

1. Check the `essentials.cdc.slot.*` gauges first (lag_bytes, active, wal_status, inactive_since_seconds) — they're tagged by slot name and updated every `cdc.slot.metricsInterval`. If you want raw detail beyond the gauges:
   `SELECT slot_name, active, wal_status, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS lag, inactive_since FROM pg_replication_slots WHERE slot_name LIKE 'essentials_%';`
2. If `active = false`:
   - **The app is running**: tailer didn't acquire the slot — check the JVM and its
     logs. The `slot_lock_acquired` gauge will be 0 too.
   - **The app isn't running anymore**: orphaned slot. `SELECT pg_drop_replication_slot('<name>');`
3. If `active = true` and `lag` is large but holding steady: tailer is alive, the
   server just hasn't been told to ack. Check tailer logs for the heartbeat line —
   no "CDC heartbeat" log every 15s ⇒ tailer is wedged. Check
   `essentials.cdc.<plugin>.last_message_age_ms`; a large value plus no idle-LSN-push
   logs at DEBUG ⇒ idle push is failing. Restart the app.
4. If `wal_status` is `'extended'` or `'unreserved'`: you're past `max_slot_wal_keep_size`.
   PostgreSQL will invalidate the slot soon; that's recoverable (subscribers fall back
   to polling) but the slot will need to be recreated.

### "CDC isn't delivering events"

1. Check `essentials.cdc.active` gauge. `0` ⇒ availability is not ACTIVE.
   Then check the `everActive` health detail: `false` means CDC has never come up in this JVM at all
   (look at the slot, the publication and the tailer), `true` means it came up and was lost.
2. Check `essentials.cdc.eventstore.fallback.poll.count` — if it's climbing, polling
   is keeping subscribers afloat, so this is a CDC pipeline issue, not a data-loss issue.
3. Look at the most recent effectiveness-monitor log line (it includes a `SlotState`
   snapshot).
4. Common causes:
   - **pgoutput**: publication doesn't include the event-stream tables. Run the
     `pg_publication_tables` query in §4.4.
   - **wal2json**: extension not installed. `SELECT * FROM pg_available_extensions WHERE name = 'wal2json';`
   - **Replication privilege missing** for the JDBC user.
   - **`max_replication_slots`** exhausted on the server.

### "App was decommissioned but the slot still exists"

```sql
SELECT pg_drop_replication_slot('essentials_<group>_<db>');
```

Do this **before** disk fills. There is no auto-cleanup — the framework cannot tell
"the app was removed" apart from "the app is temporarily down."

### "I want a clean slate without losing subscribers"

`cdc.slot.recreateOnStart=true` (or `cdc.healthCheck.autoRecreateSlotOnStuck=true`).
Both drop+recreate the slot, starting at `pg_current_wal_lsn()`. Subscribers continue
via polling fallback during the cutover and resume from CDC once the new slot is
live; backfill picks up any events written between drop and recreate.

---

## 10. Multi-Node Behaviour

CDC is designed to run unchanged on a single JVM or across an N-node cluster. There
is no separate cluster-mode configuration. All coordination happens through PostgreSQL
itself — advisory locks for tailer ownership, `FOR UPDATE SKIP LOCKED` for dispatcher
parallelism, and the existing subscription-fencing lock for exclusive handlers.

### 10.1 Tailer — exactly one per slot, cluster-wide

A logical replication slot supports at most one streaming reader at a time. The
framework enforces this with a PostgreSQL advisory lock keyed on a deterministic
hash of the slot name. Every JVM in the cluster runs a `WalReplicationTailer` for
each configured slot, but only one acquires the lock and streams; the rest sit in
a low-frequency retry loop and take over the moment the holder dies.

Implications:

- **No leader election framework needed.** The advisory lock *is* the election.
- **Deployment is uniform.** Every node runs the same configuration; ownership is
  decided at runtime.
- **Failover latency on graceful shutdown** is essentially immediate — `stop()`
  releases the advisory lock, the next retry on another node acquires it.
- **Failover latency on ungraceful shutdown** (SIGKILL, OOM, network partition)
  is bounded by the PostgreSQL TCP keepalive / `wal_sender_timeout` cycle —
  typically 30–60 seconds. During the gap, subscribers automatically fall back to
  polling, so no events are lost.
- **Slot-lock contention logs** escalate from INFO (first miss) to WARN (every 20th
  miss) so prolonged contention is visible without spamming.

### 10.2 Multiple slots — sharded tailers across nodes

When you run multiple **consumer groups** (see §3.2 — typically one per bounded-
context deployment, e.g. `orders`, `billing`, `analytics`), each group's slot has
its own advisory lock. In a multi-node cluster the slots distribute naturally:
each slot is grabbed by whichever node of *its own deployment* acquires the lock
first. Different groups' slots don't compete with each other, so two slots can
end up on the same node or on different nodes — race-driven, no coordination.

This is **not** a load-balancing primitive across nodes of the same deployment:
all nodes of `orders` share one slot (one tailer wins, the rest are standbys).
What it distributes is *the set of distinct slots across the cluster*. If you
want true tailer parallelism within one deployment you'd need separate consumer
groups for the same data — not a supported pattern.

There is **no built-in load balancer** that tries to even out slots-per-node. In
practice the spread is good enough for two or three groups; a deliberate
operator can pin a group's tailer to specific nodes via deployment-level
constraints (k8s node-affinity, etc.) if needed.

### 10.3 Dispatcher — runs per node, parallel-safe

In INBOX delivery mode, every node runs its own `CdcDispatcher` instance. All
instances poll the shared `eventstore_cdc_inbox` table concurrently. Safety relies
on:

- `SELECT … FOR UPDATE SKIP LOCKED` in the fetch query — competing dispatchers
  pick disjoint row sets without blocking.
- The unique `(slot_name, lsn)` constraint that already exists for tailer
  idempotency — even if a row were processed twice, the dispatcher's status
  transition is the serialisation point.

Throughput scales near-linearly with node count up to the point where row-lock
contention or the inbox table's index hot-spots dominate. In practice the ceiling
is tens of thousands of rows/second on a healthy PostgreSQL — well beyond what
most event-sourced workloads produce.

### 10.4 CdcEventBus — per-JVM, not a cluster bus

The in-memory event bus is local to its JVM. It does not federate across nodes.
This is correct, not a limitation, because of how the framework wires subscribers:

- **Non-exclusive subscribers** run on every node. Each node's dispatcher publishes
  to its own bus, each node's subscriber instance receives. Idempotency is
  required by design — duplicates across nodes are expected.
- **Exclusive subscribers** run on a single node fenced via a separate
  subscription-level lock (independent of the CDC tailer lock). The active node's
  bus delivers; other nodes' buses receive the events too but the handler is
  inactive there.

Because the inbox is the durable shared source of truth, every node's dispatcher
will eventually publish every event to its local bus. There is no scenario where
"only the tailer's node sees the events" — that would only be true in DIRECT mode.

### 10.5 DIRECT delivery mode in a cluster

In `cdc.deliveryMode=DIRECT` the inbox is bypassed. Only the node currently
holding the tailer lock decodes the live stream and emits to its local
`CdcEventBus`. Subscribers on other nodes still receive every event — they just
get it via classic polling at poll-interval latency rather than via the live bus.

This is **not** a "don't use in clusters" mode. It's a valid choice with
specific trade-offs:

| Aspect                         | INBOX (default)                                              | DIRECT                                                                 |
| ------------------------------ | ------------------------------------------------------------ | ---------------------------------------------------------------------- |
| Live latency on tailer node    | ~50ms p50                                                    | ~25ms p50 (no inbox roundtrip)                                         |
| Live latency on non-tailer nodes | ~50ms p50 (via shared inbox)                                | poll-interval (typically 0.5–5s)                                       |
| Operational footprint          | `eventstore_cdc_inbox` table + TTL purge + dispatcher        | None beyond the tailer                                                 |
| Inbox disk growth              | Yes — see §11.4                                              | None                                                                   |
| Tailer-node failure impact     | Subscribers everywhere keep getting CDC via inbox            | Subscribers everywhere fall back to polling until tailer relocates     |

When DIRECT in a cluster makes sense:

- **Latency-sensitive workloads can be pinned** to the tailer-holding node (e.g.
  via a deployment label that biases tailer ownership), and the rest of the
  cluster is fine with polling-interval latency for live tail.
- **Mixed-tier subscribers**: some need ms latency (real-time UI/alerting),
  others tolerate seconds (analytics, audit). Pin the former; let the latter
  poll.
- **Very high write rates** where the inbox table becomes the bottleneck — the
  inbox `INSERT` rate caps at the slowest writer in the cluster, while DIRECT
  scales with the tailer node's local CPU.
- **Operational simplicity**: no extra table, no TTL purge job, no decode-failure
  rows accumulating. The trade is that the durable buffer between WAL ingestion
  and dispatch is gone — backpressure on the bus directly throttles WAL acks.

When INBOX wins:

- Every node's subscribers need low latency (uniform deployment).
- You want a forensic audit trail of every WAL message the tailer received.
- Decode failures should be quarantined for inspection rather than potentially
  back-pressuring the tailer.

Both modes are correct in any cluster size. **INBOX is the default** because
"every node gets CDC latency" is the more common requirement; DIRECT is a
deliberate optimisation for the patterns above.

### 10.6 Subscription handler scaling

Subscription handler execution model is orthogonal to CDC. The same rules apply
as in the polling-only case:

- `EXCLUSIVE_ASYNCHRONOUS`: one active handler instance cluster-wide, fenced by a
  subscription lock.
- `IN_PROCESS_NON_PERSISTENT` and friends: fan out to every node, idempotency
  required.

CDC does not change cardinality — it changes latency. A handler that processed
events with 2-second polling latency now processes them with sub-100ms CDC
latency, but the count of handler instances is unchanged.

---

## 11. Performance

The numbers below are order-of-magnitude reference values from typical workloads
on commodity PostgreSQL (16-core, NVMe, default `wal_buffers`/`max_wal_size`).
Measure your own workload before designing capacity.

### 11.1 Live-event latency budget

End-to-end latency from `INSERT … RETURNING` on the writer to the event landing in
a subscriber's `onEvent` callback, INBOX mode, `pgoutput`:

| Stage                                                | Typical | Configuration knob                                   |
| ---------------------------------------------------- | ------- | ---------------------------------------------------- |
| WAL flush + replication-stream delivery to tailer    | 5–25ms  | (PostgreSQL-side; `synchronous_commit` etc.)         |
| Tailer `readPending()` poll cadence                  | ≤25ms   | `cdc.walReplicationTailer.pollInterval`              |
| Inbox `INSERT`                                       | 1–5ms   | (DB latency)                                         |
| Dispatcher `SELECT … FOR UPDATE SKIP LOCKED` cadence | ≤20ms   | `cdc.cdcDispatcher.pollInterval`                     |
| Decode + emit to bus                                 | <1ms    | (CPU-bound; per-event)                               |
| Subscriber notification                              | <1ms    | (in-process)                                         |
| **End-to-end p50**                                   | **~50ms** |                                                    |
| **End-to-end p99**                                   | **~200ms** | (under GC + DB load)                              |

DIRECT mode shaves ~25–40ms by removing the inbox roundtrip — useful when
sub-50ms is required and the durable buffer's safety net isn't needed.

### 11.2 Throughput limits

| Resource                    | Practical ceiling                 | Bottleneck                                              |
| --------------------------- | --------------------------------- | ------------------------------------------------------- |
| WAL → tailer ingestion      | 10k+ msg/s per slot               | Replication-stream serialisation, network bandwidth.    |
| Inbox writes                | 5–20k rows/s on healthy PG        | Inbox table inserts; bound by index maintenance.        |
| Dispatcher publishes        | Scales linearly with #nodes       | `FOR UPDATE SKIP LOCKED` row-lock contention at high N. |
| CdcEventBus emit            | >100k events/s/JVM                | Reactor sink contention; `backpressureBufferSize`.      |

For workloads above ~5k events/s sustained, prefer DIRECT mode or shard via
`cdc.slot.group`.

### 11.3 Memory

Per JVM:

- Per-aggregate Reactor sink buffer: `cdc.eventBus.backpressureBufferSize` × ~1 KB
  per buffered event. With the default `8192` and ~10 aggregate types, ≈80 MB
  worst case - reached only while an aggregate type has no subscriber, since no
  subscription back-pressures the bus.
- Per-subscription live hand-over buffer: one polling page (`eventStorePollingBatchSize`)
  per CDC subscription; a subscription further behind leaves the bus and catches up from
  the event store, one page at a time.
- Backfill→live handover buffer: `cdc.cdcEventStoreBackfillBatchSize` rows per
  active subscription, transient.
- Tailer connection: a single replication connection per slot; negligible.

### 11.4 Inbox sizing

The inbox table grows in proportion to event volume × TTL:

```
rows ≈ events_per_day × cdc.inboxTtlDurationDays
disk ≈ rows × (~payload_size + ~200 B overhead)
```

Worked example: 1M events/day × 90 days × 2 KB/row ≈ **180 GB**.

Sizing levers:

- Drop `cdc.inboxTtlDurationDays` (default 90) — shorter retention, smaller table,
  shorter forensic window for debugging.
- Switch `cdc.cdcDispatcher.dispatchedRowPolicy=DELETE` — rows go away
  immediately on dispatch; only `POISON` rows accumulate. Loses the audit trail
  but is the right call on high-volume systems.
- Move to DIRECT mode — no inbox at all.

### 11.5 PostgreSQL-side considerations

- `max_wal_senders` ≥ (number of slots + headroom). Default is usually 10.
- `max_replication_slots` ≥ (number of slots + headroom).
- `wal_level = logical` (mandatory).
- `wal_sender_timeout` — keep at default (60s); the tailer's idle LSN push every
  30s comfortably stays under this.
- `max_slot_wal_keep_size` — set as a backstop (see §5.6).

---

## 12. Liveliness — CDC vs Polling vs Hybrid

The framework's value is that you don't have to choose between CDC and polling —
the hybrid model uses both, transparently. This section makes the trade-offs
explicit so the choice of `cdc.enabled` and `cdc.mode` is informed.

### 12.1 Side-by-side comparison

| Property                                    | CDC only                                          | Polling only                                  | Hybrid (this framework)                                                                  |
| ------------------------------------------- | ------------------------------------------------- | --------------------------------------------- | ---------------------------------------------------------------------------------------- |
| **Live latency**                            | ms                                                | poll interval (typically 0.5–5s)              | ms when ACTIVE; falls back to poll interval on FAILED                                    |
| **Correctness if consumer offline 1h**      | WAL retained on slot — backlog replayed on resume | Resume from durable cursor, no DB-side state  | Subscribers resume from cursor via polling; CDC catches up live-tail only                |
| **Correctness if network/broker partition** | Slot stalls, WAL accumulates                      | Unaffected                                    | CDC degrades to FAILED, polling continues; slot may grow but subscribers stay correct    |
| **Steady-state DB load**                    | 1 long-lived replication conn + N inbox queries   | N×polling queries (per subscription, per JVM) | Both — but CDC's bus drastically reduces the per-subscription poll amplification         |
| **Operational footprint**                   | Slot, publication, plugin, inbox table            | None beyond `event_stream_*` tables           | Both — but degrades gracefully if any CDC component fails                                |
| **Failure visibility**                      | Silent stalls possible without monitoring         | Slow but visible (poll latency rises)         | `CdcEffectivenessMonitor` + `CdcAvailability` surface stalls; metrics expose fallback    |
| **Disk-overflow risk**                      | High if slot orphaned (see §5)                    | None                                          | Inherits CDC's slot-growth risk; polling keeps subscribers alive while operator responds |
| **Operational complexity**                  | High                                              | Low                                           | Medium — CDC is opt-out                                                                  |

### 12.2 When CDC wins

- Latency-sensitive read models (UIs reading from projections, real-time
  dashboards, alerting pipelines).
- High-fan-out subscriptions where polling amplification would dominate
  (`N subscriptions × M JVMs × poll_rate` becomes heavy fast).
- Workloads where event-write rate is high enough that "next poll" is too slow
  but a slot can keep up.

### 12.3 When polling-only wins

- Single-process embedded use (CDC overhead not worth it).
- Environments where you don't control the database (managed service without
  logical replication, no `wal_level=logical`).
- Workloads where seconds of latency are fine and operational simplicity matters.

### 12.4 Why hybrid is the recommended configuration

CDC ships **off** (`cdc.enabled=false`) because it depends on database-side
configuration the framework cannot assume — see §1.1. Once you enable it, the default
`cdc.mode=AUTO` gives you the hybrid model, and that is the configuration to aim for:

- When CDC is healthy, subscribers get sub-100ms latency.
- When CDC fails (slot lost, plugin missing, replication privilege revoked,
  effectiveness monitor flips FAILED), subscribers transparently fall back to
  polling — **no subscriber code change, no events lost**, just higher latency.
- When the operator fixes CDC, the framework cuts back to the live bus
  automatically (debounced via `activeCutbackDebounce`).

The mental model: **polling is the floor of correctness; CDC is the ceiling of
speed.** Subscribers always see correct, ordered events; CDC determines how fast.

### 12.5 Liveliness guarantees

- **At-least-once delivery** to every subscriber. Idempotency is the subscriber's
  responsibility. CDC and polling both rely on the EventStore's `globalOrder`
  cursor for resume.
- **Backfill before live** within a single subscription, across the backfill→live
  handover, courtesy of `BackfillThenLiveOrdered`. Global order is not strictly
  monotonic: live events follow commit order, and an event whose transaction commits
  after one holding a higher global order is delivered late (the resume point only
  advances). Per aggregate, event order is kept.
- **No silent loss on CDC failure.** A FAILED CDC pipeline cuts subscribers over
  to polling at the position they last consumed; no events are skipped.
- **No silent loss on slot recreation** (manual or via
  `autoRecreateSlotOnStuck`). The new slot starts at `pg_current_wal_lsn()`;
  events written during the recreate window are picked up by polling. Subscribers
  reconnect via the standard backfill→live sequence on the new slot.
- **Bounded staleness.** Even if CDC silently stalls and the effectiveness
  monitor takes an interval to detect it, polling keeps subscribers within
  `pollInterval` of head.

The one case where liveliness can degrade: **CDC stuck AND polling disabled or
broken**. The framework does not make this configuration possible — polling is
always available as the fallback, by construction. Don't disable polling.

### 12.6 Choosing a delivery mechanism

§12.1–12.4 compare CDC / polling / hybrid at a high level. This section folds in the
finer choices — the **polling sub-variants** (plain, jittered, notify-driven) and the
**CDC delivery modes** (INBOX vs DIRECT) — into one place, with indicative numbers and a
need-based recommendation. For the polling-side tuning detail see
[subscription-improvements.md](subscription-improvements.md); for the INBOX/DIRECT delivery
guarantees see the `CdcDeliveryMode` javadoc.

> **The numbers below are *indicative* (a 10 Hz / 1 s-window smoke run, plus the S1
> 0.1–1 Hz perf-lab runs), not a rigorous benchmark.** They show the *shape* of the
> trade-off; absolute latency depends heavily on event inter-arrival rate, batch size, and
> hardware. Measure your own workload before committing to an SLA.

| Mechanism | Live p95 (indicative) | Idle DB load | Best for | Main cost |
|---|---|---|---|---|
| **Plain fixed polling** | ≈ poll interval (idle ~1 s, 1 Hz ~334 ms) | N subs × M JVMs × poll-rate | simplest possible; no infra; works on any Postgres | latency = poll interval; load grows with subscriber count |
| **Jittered polling** (the polling default) | same as plain | same total, **de-synchronized** | **the safe default** — many subscribers/JVMs sharing one DB without lock-step poll spikes | no latency gain — load-smoothing only |
| **Notify-polling (S1)** | idle ~898 ms @ `max-delay=1s`; ~222 ms @ `max-delay=200ms` (1 Hz) | **near-zero at idle** (−32% @ 0.1 Hz) | low op-cost path that cuts idle DB load *and* beats plain polling on latency; great polling fallback | `pg_notify` trigger (~10–50 µs/INSERT); latency floor ≈ `max-delay` |
| **CDC INBOX** | ~159 ms (dispatcher hop) | 1 replication conn + inbox queries; **no per-subscriber poll amplification** | audit trail, replica-offload, server-side filtering, durable buffer, high fan-out | slot + publication + plugin ops; WAL-retention risk (§5) |
| **CDC DIRECT** | **~33 ms (~4–5× below polling)** | 1 replication conn, no inbox | **lowest latency** push delivery | weaker re-delivery under `LOG_AND_DROP` (see `CdcDeliveryMode`); no durable buffer |

Pick by need:

- **Defaults / simplest correct setup, any Postgres** → **jittered polling** (no
  `wal_level=logical`, no slot, no triggers). Add **notify-polling** when idle DB load or
  quiet-system latency matters and you can afford a per-INSERT trigger.
- **Latency-sensitive read models, high fan-out, want an audit trail or replica-offload**
  → **CDC** (hybrid). Use **INBOX** (the default) for the durable buffer + replica-offload;
  use **DIRECT** only when you need the absolute lowest latency and accept the weaker
  overflow guarantee.
- **You don't control the database** (managed service without logical replication, or
  `wal_level` ≠ `logical`) → polling (jittered ± notify). CDC in `AUTO` mode also degrades
  to exactly this automatically.

Polling is always the floor of correctness; CDC is the ceiling of speed (§12.4). You are
never choosing *between* correctness and speed — only how fast the fast path is.

---

## 13. Known Limitations

These are deliberate trade-offs or accepted gaps. The
[CDC improvements list](cdc-improvements.md) tracks the items considered worth
fixing; the items below are documented because they're either unfixable in this
architecture or low enough impact to live with.

### 13.1 Orphaned slot cleanup is manual

If an application is decommissioned without first dropping its replication slot,
the slot persists indefinitely and accumulates WAL. The framework cannot
distinguish "the app is permanently gone" from "the app is briefly down" — both
look identical to PostgreSQL.

**Mitigation**: operator runbook (§9) + alerting on
`pg_replication_slots.inactive_since > <threshold>`. A potential future
improvement would emit a warning metric for slots inactive beyond a configurable
threshold, but the actual drop will always require human judgement.

### 13.2 Failover gap on ungraceful shutdown

When the JVM holding the tailer's advisory lock is killed without running
shutdown hooks (SIGKILL, OOM, host crash), the lock is held until PostgreSQL
detects the dead connection — typically 30–60 seconds depending on
`wal_sender_timeout` and TCP keepalive settings. During the gap no other node
can take over the tailer; subscribers fall back to polling and resume CDC once
another node acquires the slot.

**Inherent to advisory locks.** Not fixable without a higher-coordination layer
(Zookeeper, etcd, etc.) which would defeat the "PostgreSQL is the only
coordinator" design choice.

### 13.3 Dispatcher SKIP LOCKED contention not metered

When multiple dispatcher instances poll the inbox concurrently, rows skipped due
to lock contention on other workers' picks are not exposed as a metric. The
behaviour is correct (dispatchers self-distribute), but operators sizing a
cluster cannot easily see whether SKIP LOCKED is actually balancing well.

### 13.4 CdcEventBus is per-JVM

Subscribers on a node only receive events from that node's dispatcher's bus.
This is **correct**, not broken — it works because the inbox is the shared
durable buffer and every node's dispatcher will publish every event. But it can
surprise operators who assume the bus federates across the cluster. See §10.4.

Federating the bus across nodes (rsocket, Hazelcast, Redis, NATS, …) was
considered and rejected — the inbox already provides durable, ordered,
multi-node fan-out with no service-discovery or leader-election layer. For
teams that find CDC operationally heavy *or* that see high DB load from plain
polling, the relevant alternative isn't bus federation — it's
[`NOTIFY-driven polling wake-up`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/subscription/subscription-improvements.md)
(S1), which keeps polling's operational simplicity while making it
event-driven (near-zero idle query load, no slot, no `wal_level=logical`).

---

## 14. Appendix — File Map

| File                                                                                       | Purpose                                                                          |
| ------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------- |
| [`CdcEventStore.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcEventStore.java)                                                 | Decorates the EventStore; switches live source between CDC bus and polling.      |
| [`WalReplicationTailer.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/WalReplicationTailer.java)                                   | Owns the replication stream; writes inbox or pushes to bus directly.             |
| [`CdcInboxRepository.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcInboxRepository.java) / [`CdcSql.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcSql.java)        | `eventstore_cdc_inbox` table DDL/queries.                                        |
| [`CdcDispatcher.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcDispatcher.java)                                                 | Polls the inbox, decodes, publishes to the bus.                                  |
| [`CdcEventBus.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcEventBus.java) / [`CdcSinkEmitter.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcSinkEmitter.java)       | Per-aggregate Reactor sinks + emit retry policy.                                 |
| [`CdcAvailability.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcAvailability.java)                                             | INACTIVE / ACTIVE / FAILED state machine.                                        |
| [`CdcEffectivenessMonitor.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcEffectivenessMonitor.java)                             | Background "is CDC actually delivering" probe.                                   |
| [`CdcPoisonNotifier.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcPoisonNotifier.java) / [`SubscriptionResetOnPoisonNotifier.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/SubscriptionResetOnPoisonNotifier.java) / [`PoisonPolicy.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/PoisonPolicy.java) | Poison-row handling. |
| [`PgReplicationSlots.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/PgReplicationSlots.java) / [`PgSlotMode.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/PgSlotMode.java) | Slot lifecycle + validation.                                                     |
| [`CdcSlotNameProvider.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcSlotNameProvider.java) / [`DefaultCdcSlotNameProvider.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/DefaultCdcSlotNameProvider.java) | Slot naming.                                                |
| [`LogicalDecodingPlugin.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/LogicalDecodingPlugin.java)                                 | Plugin abstraction.                                                              |
| [`PgOutputLogicalDecodingPlugin.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/PgOutputLogicalDecodingPlugin.java) + [`PgOutputMessageDecoder.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/PgOutputMessageDecoder.java) + [`PgOutputRowChangeDecoder.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/PgOutputRowChangeDecoder.java) + [`PgOutputRowChange.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/PgOutputRowChange.java) | pgoutput plugin + decoder. |
| [`Wal2JsonLogicalDecodingPlugin.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/Wal2JsonLogicalDecodingPlugin.java)                 | wal2json plugin.                                                                 |
| [`CdcProperties.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcProperties.java) / [`CdcMode.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcMode.java) / [`CdcConsumerGroup.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcConsumerGroup.java) | Configuration.                                                  |
| [`CdcBusOverflowException.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/CdcBusOverflowException.java) / [`StaleReplicationStreamException.java`](../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/StaleReplicationStreamException.java) | Exception types.                                                |
| [`converter/`](converter/)                                                                 | WAL payload → `PersistedEvent` converters; gap extractors.                       |
| [`filter/`](filter/)                                                                       | Pre-persistence WAL message filters (regex for wal2json, header-peek for pgoutput).|
| [`handler/`](handler/)                                                                     | `WalReplicationTailerErrorHandler` strategies.                                   |
