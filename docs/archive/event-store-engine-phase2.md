# Event store redesign, phase 2: engine design

> **ARCHIVED (2026-11-19).** The new event store engine is built and ships as a preview in 0.61.0. The engine as built is described in [`../design/event-log.md`](../design/event-log.md); this document is kept for its decisions and gate results. Section references of the form "phase N §x" in source comments point here.

**Status:** reviewed with the project lead on 2026-11-15; the open questions are decided (§5). Nothing here is built yet.
When phase 2 is done, this file moves to `docs/archive/` or is folded into the redesign's record (`docs/plans/README.md`).
**Scope:** phase 2 of `docs/archive/event-store-subscription-redesign.md` (§18, "Phases 1 onward"): the engine's own
schema (§12.4), append (§5), stream load, tail reader with watermark and combined probe (§4), progress table (§8.2),
one `FRESH` subscription, backstop wake-up only. Gate: I1–I4, I10, I11 pass; statements per event at or below the
P0.0 baseline. Section references (§n) are to the plan unless marked "phase-0 record".

Everything below that the plan has already decided is cited. Where this draft proposes something the plan does not
say, it is marked **Proposed**.

---

## 1. Module

### 1.1 Name, dependencies, reactor position

| Item | Proposal |
|---|---|
| Module | `components/postgresql-event-log`, artifactId `postgresql-event-log` (the plan's working name, §12.2; naming is risk 10, see Q5) |
| Package root | `dk.trustworks.essentials.components.eventlog` |
| Compile dependency | `foundation` (not just `shared`, see below) |
| `provided` | `org.postgresql:postgresql`, `slf4j-api`, `micrometer-core` (as the shard-owned queue, root `CLAUDE.md` "provided scope") |
| Test | `foundation-test`, JUnit 5, AssertJ, Awaitility, Testcontainers PostgreSQL, HikariCP, ArchUnit, logback (the queue's test set, minus `jdbi3-core`) |
| Reactor | Next to `components/postgresql-event-store` and `components/postgresql-queue-shard-owned` in the root `pom.xml` `<modules>`. No module depends on it in phase 2 |
| Not allowed | JDBI, Reactor, Jackson, `postgresql-event-store` (§12.2). Enforced by an ArchUnit test, as the queue does |

§12.2 lists the engine's dependency as `shared`. The queue's engine depends on `foundation` instead, because the
schema harness (`EssentialsSchemaContributor`, `DynamicSchemaContributor`, `SchemaChange`, `SchemaOwnership`,
`SchemaOrder`) and `PostgresqlUtil` (bootstrap lock key, `checkIsValidTableOrColumnName`) live there, and §12.2 itself
requires the engine's DDL to go through that harness. **Proposed:** depend on `foundation`, as the queue does.

Payloads stay opaque: the engine takes and returns payload and metadata as `String` (JSON text, bound as `text[]`
and cast to `json`, §5.1), never parses them (§12.2).

### 1.2 Package layout (phase 2 types only)

Root package, all engine-internal unless noted:

| Type | One line |
|---|---|
| `EventLog` | Public facade: `append(Connection, …)`, `loadStream(…)`, `registerAggregateType(…)`, `subscribe(…)` for the phase-2 subscription, `start()`/`stop()` |
| `EventLogBuilder` | Builds `EventLog` from a `DataSource`, settings and a `SchemaOwnership`, as `ShardOwnedQueueBuilder` does |
| `EventLogSettings` | Record: backstop interval, tail page size, probe back-off bounds, periodic-save interval, plan-mode switches |
| `EventLogSchema` | Fixed table names, `schemaStatements()`, per-aggregate-type statements, `verifyWatermarkPrerequisites`, `verifyIdentityCache` |
| `EventLogSchemaContributor` | `DynamicSchemaContributor`, `SchemaOrder.ORDER_EVENT_STORE`; fixed schema as one repeatable change, each aggregate type's tables as its own change (the queue's per-queue-sequence pattern) |
| `AggregateTypeTables` | Validated table and sequence names for one aggregate type, plus the SQL text generated once per table (§5.1) |
| `EventTypeRegistry` | `smallint` interning of event-type names: cache, register-on-miss in its own transaction (Q2) |
| `EventLogStorage` | Every SQL statement as a constant or per-table template; nothing else holds SQL (the queue's `ShardOwnedStorage` rule) |
| `AppendRequest` / `AppendedEvent` / `AppendResult` | Per-aggregate input (id, expected order or none, column arrays), per-event output (`global_order`, `event_order`) |
| `StreamAppendConflictException` | Zero rows from an append; the phase-8 adapter maps it to `OptimisticAppendToStreamException` with today's message (§1, §5.1) |
| `StoredEvent` | Record of one row as read (orders, ids, type name, revision, timestamp, payload text, metadata text, tenant) |
| `TailReader` | One per aggregate type: cursor, hole set, candidate list, watermark; single-threaded (§6) |
| `Candidate` | `(S, set of backend_xid)`; retires on set disjointness (§4.2) |
| `ProgressStore` | Progress row create/read/periodic save with reset-epoch compare-and-set (§8.2) |
| `FreshSubscription` | The phase-2 subscription: one handler, `FRESH`, periodic progress, non-exclusive. Internal; replaced by the phase-8 API (§12.3) |
| `EventLogObserver` | Callback SPI for metrics (candidate age, holes, probe cost); a Micrometer binding in `observability.micrometer` |

Waits for later phases: shared reader with attach/detach and bridge (phase 3); `CONTIGUOUS`, page per transaction,
shrinking and parking logic (phase 4; the parked table's DDL is phase 2, §2.6 below); filtered catch-up and the
catch-up semaphore (phase 5); tier-1 notifier and listener, tier-2 local wake (phase 6); lease claim and renewal
(phase 7); the subscription API, processors, admin API and the `EventStore` adapter (phase 8); migration copy
(phase 9); starter (phase 10). The watermark code should be shared with the queue if its version is stable (§19
risk 12); phase 2 copies nothing from `OrderedShardOwner` without checking that.

---

## 2. DDL

All names are fixed constants or derived from a registered aggregate type and validated with
`PostgresqlUtil.checkIsValidTableOrColumnName()` before use (root `CLAUDE.md`, "SQL/NoSQL injection"). Aggregate-type
derived names: `el_<aggregate type, lower-cased>_events` and `el_<…>_heads`; a name that fails validation or would exceed
63 bytes is refused at registration, not truncated.

### 2.1 Event table, one per aggregate type

One table per aggregate type, because `GlobalEventOrder` is an independent space per aggregate type (§1, "Must keep").
§13.3 does not change that. The layout is P0.7's COMBO_ALL (§19 item 23), taken from `TableDesignOptionsSpikeIT`:

```sql
CREATE TABLE IF NOT EXISTS el_order_events (
    global_order       bigint      GENERATED BY DEFAULT AS IDENTITY (CACHE 1) PRIMARY KEY,
    event_order        bigint      NOT NULL,
    timestamp          timestamptz NOT NULL,
    event_id           uuid        NOT NULL,
    caused_by_event_id uuid,
    correlation_id     uuid,
    event_type         smallint    NOT NULL,
    event_revision     smallint    NOT NULL,
    aggregate_id       text        NOT NULL,
    event_payload      json        NOT NULL,
    event_metadata     json        NOT NULL,
    tenant             text,
    CONSTRAINT el_order_events_stream_order UNIQUE (aggregate_id, event_order)
);
CREATE INDEX IF NOT EXISTS el_order_events_event_id ON el_order_events (event_id);
```

Deviations from the spike's DDL, each **Proposed**:

- `(CACHE 1)` written out. It is the identity default, but §4.3 prerequisite 3 requires it and start-up verifies it
  (`SELECT cache_size FROM pg_sequences` on `pg_get_serial_sequence(table, 'global_order')`), so the DDL says it.
- `GENERATED BY DEFAULT`, kept from the spike: the phase-9 copy must insert existing `global_order` values (§12.4).
- The `UNIQUE(aggregate_id, event_order)` constraint is named, so a unique violation is mapped by constraint name,
  never by parsing the message (§3.2). It stays as the backstop even with the head table (§1, "Must keep").
- A plain btree on `event_id`. The spike had **no** index on `event_id`; §19 item 23 says "a plain index serves lookups
  by event id". P0.7 measured only "no `UNIQUE`" against "`UNIQUE`", so how much of the −26 % execution and −110 to
  −130 B WAL per event survives a non-unique index is not known (Q1).
- No tenant index. Tenants are filtered in memory after the tail read (§1); SQL tenant filtering is phase 5 (§10.1).
- `event_revision smallint` as in COMBO_ALL; the engine refuses a revision above 32 767 at append rather than overflow.
- Column order is the spike's alignment order (8-byte, `uuid`, `smallint`, varlena; §13.3).
- `autovacuum_vacuum_insert_scale_factor` is documented, not set (§13.3).

### 2.2 Stream head table, one per aggregate type

```sql
CREATE TABLE IF NOT EXISTS el_order_heads (
    aggregate_id text   PRIMARY KEY,
    version      bigint NOT NULL
) WITH (fillfactor = 50);
```

Unchanged from the spike. `version` is the highest `event_order`, −1 never stored (a new stream's first append inserts
its final version). P0.7: head updates were almost all HOT at `fillfactor = 50` (phase-0 record, P0.7). The head is
the authority for a stream's version: every write path, the migration and any repair tool must keep it equal to
`max(event_order)` (phase-0 record, P0.7, "Head table").

### 2.3 Event-type intern table, one per schema

```sql
CREATE TABLE IF NOT EXISTS el_event_types (
    id   smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name text     NOT NULL UNIQUE
);
```

The spike used a fixed id 1. **Proposed:** one table for all aggregate types (a type name means the same everywhere),
ids assigned by identity, register-on-miss with `INSERT … ON CONFLICT (name) DO NOTHING RETURNING id` followed by a
`SELECT` when nothing returned, in its own committed transaction, and cached only after commit (Q2). 32 767 types
per schema is the limit; the engine fails registration loudly beyond it.

### 2.4 Aggregate-type registry

```sql
CREATE TABLE IF NOT EXISTS el_aggregate_types (
    aggregate_type text        PRIMARY KEY,
    event_table    text        NOT NULL UNIQUE,
    head_table     text        NOT NULL UNIQUE,
    created_at     timestamptz NOT NULL DEFAULT now()
);
```

**Proposed**, after `shard_queue_registry`: lets the contributor, the admin surface and the phase-9 copy enumerate
the engine's tables without guessing names.

### 2.5 Progress table and fence sequence

Columns as decided in §19 item 17 and §8.2/§9; names are this draft's.

```sql
CREATE SEQUENCE IF NOT EXISTS el_lease_fence_seq;

CREATE TABLE IF NOT EXISTS el_progress (
    subscriber        text        NOT NULL,
    aggregate_type    text        NOT NULL,
    instance_name     text        NOT NULL,   -- '' for an exclusive subscription (one row cluster-wide)
    exclusive         boolean     NOT NULL,
    consistency_mode  text        NOT NULL CHECK (consistency_mode IN ('FRESH', 'CONTIGUOUS')),
    progress_model    text        NOT NULL CHECK (progress_model IN ('PERIODIC', 'IN_HANDLER_TRANSACTION')),
    resume_from       bigint      NOT NULL,   -- GlobalEventOrder to resume from, inclusive (§4.7)
    reset_epoch       bigint      NOT NULL DEFAULT 0,
    lease_owner       text,
    lease_until       timestamptz,
    lease_fence       bigint,                 -- nextval('el_lease_fence_seq') at claim (§9, phase 7)
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subscriber, aggregate_type, instance_name)
) WITH (fillfactor = 70);
```

- No index besides the primary key, so resume-point and lease updates stay HOT (§3.2, §8.2). Fillfactor below 100 as
  §8.2 asks; 70 is a starting value, not measured.
- No open-page columns (§19 item 17). No `delivered_above_watermark` (§8.4 is later).
- Mode and progress model are checked at start against the subscription's configuration; a mismatch is logged and
  the row's values are updated in the same statement as the start, not silently resumed (§19 item 17).
- Fences from one sequence, so they never repeat even across a deleted row (§19 item 17). Phase 2 creates the sequence
  and columns but never claims; the lease columns stay null (§3.5 below).

### 2.6 Parked-events table

DDL only in phase 2; the logic that parks, holds later events of the aggregate behind the head, retries and discards
is phase 4 (§8.3, §18), and the admin surface phase 8.

```sql
CREATE TABLE IF NOT EXISTS el_parked_events (
    subscriber      text        NOT NULL,
    aggregate_type  text        NOT NULL,
    instance_name   text        NOT NULL,
    global_order    bigint      NOT NULL,
    aggregate_id    text        NOT NULL,
    blocked_by      bigint,                 -- null: this row failed; else the global_order of the parked head it waits behind
    attempts        integer     NOT NULL,
    error_type      text,
    error_message   text,
    parked_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subscriber, aggregate_type, instance_name, global_order)
);
CREATE INDEX IF NOT EXISTS el_parked_events_aggregate
    ON el_parked_events (subscriber, aggregate_type, instance_name, aggregate_id, global_order);
```

References the event by `(aggregate_type, global_order)` without a copy (§8.3, §19 item 18). No foreign key: the
event tables are per aggregate type and created at runtime. The second index answers "is this aggregate held for this
subscription" in `global_order` order, which phase 4's delivery check needs; the exact columns may still change in
phase 4.

### 2.7 How the DDL is contributed

| Change | Statements | Idempotent | Ownership |
|---|---|---|---|
| `engine-schema` (repeatable) | 2.3, 2.4, 2.5, 2.6 | Yes: every statement `IF NOT EXISTS` | `SchemaOwnership` from the caller; in Spring the starter passes `properties.getSchema().getMode().schemaOwnership()` (root `CLAUDE.md`, "Schema DDL lives in schema contributors") |
| `aggregate-type:<name>` (dynamic, per registration) | 2.1, 2.2, the registry insert (`ON CONFLICT DO NOTHING`) | Yes | Same; created through the harness when it creates the schema, otherwise directly under `PostgresqlUtil`'s bootstrap lock, as the queue creates its per-queue sequences (`ShardOwnedSchemaContributor.attach`) |

Every statement must be safe against an existing object, and there is no adoption path (root `CLAUDE.md`). The
`ALTER … SET (fillfactor)` form is not used: fillfactor is set at `CREATE` only, so a deployment that tuned it is not
overwritten.

---

## 3. Statements

### 3.1 Append (§5.1 with the head table)

One statement per aggregate, sent as one pgjdbc batch per unit of work (§5.2), on the caller's `Connection`, which the
engine never commits (§12.2, as `ShardOwnedQueue.enqueue(Connection, …)`).

With an expected order (`$3`):

```sql
WITH xid AS MATERIALIZED (SELECT pg_current_xact_id() AS x),
     h   AS (UPDATE el_order_heads SET version = version + $1::bigint
              WHERE aggregate_id = $2::text AND version = $3::bigint
                AND (SELECT true FROM xid)
              RETURNING version - $1::bigint AS v)
INSERT INTO el_order_events (aggregate_id, event_order, event_id, caused_by_event_id, correlation_id,
                             event_type, event_revision, timestamp, event_payload, event_metadata, tenant)
SELECT $2, h.v + e.n, e.id, e.caused_by, e.correlation, e.type, e.revision, e.ts,
       e.payload::json, e.metadata::json, $4
FROM h CROSS JOIN unnest($5::uuid[], $6::uuid[], $7::uuid[], $8::int2[], $9::int2[], $10::timestamptz[],
                         $11::text[], $12::text[])
         WITH ORDINALITY AS e(id, caused_by, correlation, type, revision, ts, payload, metadata, n)
RETURNING global_order, event_order;
```

New stream (`$3` = −1): `h` becomes `INSERT INTO el_order_heads SELECT $2, $1 - 1 FROM xid ON CONFLICT (aggregate_id) DO
NOTHING RETURNING -1::bigint AS v`, the spike's statement with its `VALUES` replaced by a `SELECT … FROM xid` so the
xid is taken first on this path too. In both paths the `xid` reference sits inside `h`, which is evaluated before any
row reaches the event insert. No expected order: **Proposed** `INSERT … VALUES ($2, $1 - 1) ON
CONFLICT (aggregate_id) DO UPDATE SET version = el_order_heads.version + $1 RETURNING version - $1 AS v`; it waits for
a concurrent writer's row lock and never conflicts, so it never burns a value. P0.7 always passed an expected order,
so this shape is not measured.

- **Zero rows = conflict**, both for a competitor that committed before and, through the head row's lock and
  EvalPlanQual re-check, for one still in flight; no `global_order` is taken (phase-0 record, P0.7: 0 burned values in
  every head-table arm). The engine throws `StreamAppendConflictException(aggregateType, aggregateId, expected,
  actual)` per aggregate in the batch (§5.2); the phase-8 adapter maps it to `OptimisticAppendToStreamException` with
  today's message (§1, §19 item 19).
- A **unique violation** on `el_order_events_stream_order` (only possible if the head table is out of step, e.g. an
  external writer, §19 risk 7) is mapped by constraint name to the same exception and logged as a head-table
  inconsistency.
- **xid before value.** P0.7 found the xid assigned before the first `global_order` in the head-table arms without an
  `xid` CTE, because the head `UPDATE` writes first. **Proposed deviation:** keep the materialised `xid` CTE anyway, so
  I4 does not rest on a side effect of the head write and the create path, where `ON CONFLICT DO NOTHING` writes nothing
  on a conflict, is covered by the same argument as P0.1 (Q4).
- The event type is bound as interned `smallint` ids from `EventTypeRegistry`, which has registered any new name in
  its own transaction before this statement is sent (Q2).
- One-event appends with scalar binds (COMBO_ALL_SCALAR, +12 % on 17.12, phase-0 record P0.7) are a later
  optimisation, not phase 2.
- An append the engine commits itself (a convenience entry point, not the adapter's path) goes out as one explicit
  `BEGIN … COMMIT` flush (P0.14, §18 "Phase 0 decisions"). Appends inside the caller's unit of work cannot use that.

### 3.2 Stream load

```sql
SELECT global_order, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision,
       timestamp, event_payload::text, event_metadata::text, tenant
  FROM el_order_events
 WHERE aggregate_id = $1 AND event_order >= $2 AND event_order <= $3
 ORDER BY event_order;
```

Index range on the unique constraint. Event-type names are resolved from the registry cache (miss: reload the table),
not by a join. No fetch size is set (P0.6: buys nothing raw for short reads; streaming is §10.1, phase 5). Also
`… WHERE event_id = $1` through the plain index for single-event lookups (§12.3's adapter list).

### 3.3 Tail read and combined probe

The reader owns one connection for its lifetime (the queue's rule, module `CLAUDE.md`), opened with
`options=-c plan_cache_mode=force_generic_plan -c jit=off`, `prepareThreshold=1`, autocommit on (P0.2, P0.16).
Phase 2 reads one aggregate type per wake; reading all woken types in one flush (P0.16 decision) is phase 3, when a
JVM has several readers.

Plain tail, when no candidate is outstanding and the last read was contiguous (§4.10):

```sql
SELECT <cols> FROM el_order_events WHERE global_order > $1 ORDER BY global_order LIMIT $2;
```

Tail with hole re-read and running set, otherwise (§4.2 P0.11 revision, §4.10, P0.12 revision):

```sql
SELECT * FROM (
    (SELECT <cols>, NULL::text[] AS running FROM el_order_events
      WHERE global_order > $1 ORDER BY global_order LIMIT $2)
    UNION ALL
    (SELECT <cols>, NULL::text[] FROM el_order_events WHERE global_order = ANY($3::bigint[]))
    UNION ALL
    (SELECT <null cols>, ARRAY(SELECT backend_xid::text FROM pg_stat_get_activity(NULL)
                                WHERE backend_xid IS NOT NULL))
) u
ORDER BY u.global_order NULLS LAST;
```

- The last branch returns the running set as one row **even when the tail is empty** (P0.2's untested case, §4.10).
  Never the `OR global_order = ANY` form (§18 "Phase 0 decisions").
- A sequence jump's ranges get a branch of their own, `… JOIN unnest($4::bigint[], $5::bigint[]) r(lo, hi) ON
  global_order BETWEEN r.lo AND r.hi`, only while a jump range is outstanding. P0.11 found that branch JIT-compiled
  through its row estimate; `jit=off` on the connection removes that, and if its generic plan fails the CI `EXPLAIN`
  check it runs unnamed (`prepareThreshold=0`), which always plans custom (P0.16 decision: generic only where the
  generic plan is index-only).
- A CI test runs `EXPLAIN (GENERIC_PLAN)` on every reader statement and fails on a primary-key walk (P0.16 decision).
- Hole read at retirement (§4.2, §13.2): `… WHERE global_order = ANY($1)` plus the range form; its rows are published
  before the watermark moves (F2).
- Running set alone (re-probe without a read, §13.2): `SELECT ARRAY(SELECT backend_xid::text FROM
  pg_stat_get_activity(NULL) WHERE backend_xid IS NOT NULL)`.
- Restart: the reader starts at the head (`SELECT max(global_order)`, index-only) with one initial candidate `(head,
  running set)` and an unknown watermark (F4). The phase-2 subscription starts from its stored `resume_from`; while the
  watermark is unknown it may read up to the head but its saved resume point stays at its own §4.7 value.
- Probe rate: re-probe only after a wake-up, backing off with the probe's measured cost (P0.12 decision). With
  backstop wake-up only, that is the backstop interval while a candidate is outstanding; tuned against P0.12's
  400-connection case in phase 2 (§18 "Phase 0 decisions").

### 3.4 Progress write (§8.2) and start

Periodic save, its own autocommit statement (§8.1):

```sql
UPDATE el_progress
   SET resume_from = $1, updated_at = now()
 WHERE subscriber = $2 AND aggregate_type = $3 AND instance_name = $4
   AND reset_epoch = $5
   AND ($6::bigint IS NULL OR lease_fence = $6);
```

`$6` is null in phase 2 (non-exclusive writes without a fence, §9 last bullet); phase 7 binds it for exclusive
subscriptions. Zero rows: one primary-key read decides between a superseding reset (restart from the reset point) and
lost ownership (phase 7) (§8.2, §9). The value written is §4.7's `min(watermark + 1, lowest delivered-but-unhandled)`,
never the highest handled order (§8.3 last bullet).

Start: `INSERT … ON CONFLICT (subscriber, aggregate_type, instance_name) DO UPDATE SET consistency_mode =
EXCLUDED.consistency_mode, progress_model = EXCLUDED.progress_model RETURNING resume_from, reset_epoch,
(xmax <> 0) AS existed, <old mode, old model via a CTE>` so a changed mode or model is logged (§19 item 17).

### 3.5 Leases

Not in phase 2: leases, claims and renewal are phase 7 (§9, §18). The phase-2 subscription is non-exclusive: one row
per `(subscriber, aggregate type, instance name)` with the instance name from configuration (§19 item 17), no lease,
no fence asserted. Two JVMs with the same instance name would overwrite each other's progress; phase 2 documents that
and does not guard it.

---

## 4. Test plan for the phase-2 gate

Every invariant test must have been seen to fail against a broken variant (§16, history §9.13). The must-fail variants
reuse P0.5's mutants (§21 P0.5 row) where they apply to phase 2, built as package-private switches on the engine
types that only tests can set. ITs run against PostgreSQL 16, 17 and 18 in CI, because I4 and I11 rest on executor
behaviour (§19 risk 2, §21 P0.1).

| # | Invariant (§16) | IT | Must-fail variant |
|---|---|---|---|
| I1 | "No loss: every committed event reaches every active subscription at least once, in both modes" (phase 2: `FRESH` only) | `EventLogNoLossIT`: 8 writers, random commit order, 10 % rollbacks; a writer held open for 5 minutes (`@Tag("long")`, a 30 s variant in the default run); a `setval` jump of 1 000 000; reader restart while a writer holds a value below the head | P0.5 "empty tail without running set", "restarted reader at the head" (F4) |
| I2 | "Per-aggregate order: within one subscription incarnation, A#n is first delivered before A#n+1" | `EventLogPerAggregateOrderIT`: §4.5's scenario; F1's shape (A#n late into a hole, A#n+1 after, a long writer elsewhere holds the candidate), with one and several events per transaction | F1 control: a reader that delivers late commits only from the hole read; a per-row-xid cursor (§4.5); P0.5 "value before event order" |
| I3 | "A resume point never passes an order that could still be delivered" | `EventLogResumePointIT`: crash (connection kill + engine stop) between delivery and handling, and between a candidate's retirement and its hole read; restart and assert every event at or above the saved point is redelivered | P0.5 "watermark before hole read", "resume point at highest handled", "resume point at cursor" |
| I4 | "xid before value: no writer holds a `global_order` while its xid is null — for the single-statement append, under every join order" | `EventLogAppendXidWindowIT`, after `ShardOwnedWatermarkXidWindowIT` and P0.1: `BEFORE INSERT` trigger holds the first row; asserts `backend_xid` non-null; all three head paths (guarded update, new stream, no expected order); simple, unnamed, named and `force_generic_plan` protocols; `enable_nestloop=off`, `join_collapse_limit=1` and the `LATERAL` rewrite forcing `unnest` outer | P0.5 "value before xid": the insert without the `xid` CTE and without the head CTE (P0.1's control, which showed `backend_xid` null) |
| I10 | "A lost optimistic-concurrency check consumes no `global_order`" | `EventLogOptimisticConcurrencyIT`: two writers, stale expected order, both against a committed competitor and racing an in-flight one (held by a trigger); assert sequence `last_value` unchanged, no hole, `StreamAppendConflictException` naming the right aggregate in a five-aggregate batch; plus 8 writers on 10 aggregates for 20 s asserting 0 burned values | §5.1 without the head table (COMBO_ALL_NO_HEAD), which burns values on the in-flight race (P0.4, P0.7) |
| I11 | "Combined probe: the running-writer set is read after the statement's snapshot" | `EventLogCombinedProbeIT`, after P0.2: writer held between value and commit; the probe must not retire its candidate; the hold-inside-scan ordering proof; the empty-tail case; inside an explicit transaction only after `pg_stat_clear_snapshot()` | P0.5 "running set before tail read" (two statements, `X` first), "a cached `pg_stat_activity`" (probe inside a transaction that read it earlier) |

Also in phase 2, not gate rows: `EventLogPrerequisitesIT` (start refuses when a role without `pg_read_all_stats`
cannot see another role's `backend_xid`, using `pg_stat_get_activity(NULL)`, the function the probe reads, rather than
the queue's `pg_stat_activity`; refuses an identity with `CACHE` > 1); `EventLogSchemaIT` (each contributor change runs
twice without error, under each `SchemaOwnership`); `EventLogGenericPlanIT` (the `EXPLAIN` check of §3.3); a
head/`max(event_order)` consistency check after every concurrent IT.

**"Statements per event at or below the P0.0 baseline."** Rerun P0.0's steady shape (`CurrentEngineBaselineSpikeIT`'s
harness: `pg_stat_statements`, separate DB roles for write and read paths, fresh container, 10 and 100 events/s)
against the new engine, and sum `calls` per role divided by delivered events. Baseline (§21 P0.0, phase-0 record
"Reading and consequences"): ≤ 6.8 reader statements per delivery at 10 ev/s, ≤ 1.04 at 100 ev/s; write path 2-3
statements per unit of work. P0.0 recorded `BEGIN` but almost no `COMMIT`, so counts include utility statements and
are compared like for like. Phase 2 has no shared reader, so the subscription count changes the result (Q3).

---

## 5. Decisions (2026-11-15)

Taken with the project lead, one at a time, after reviewing this design. Where the sections above say "Proposed"
for one of these, the decision below applies.

| # | Question | Decision |
|---|---|---|
| 1 | Index on `event_id` | A plain btree index. Phase 2's append measurement adds one P0.7-style arm (the full combination plus that index); if it costs most of P0.7's saving, keeping it or an opt-out is decided then |
| 2 | Where new event types are interned | In their own committed transaction on the engine's `DataSource`, before the append; the id is cached only after commit. Appends stay on the caller's `Connection` |
| 3 | How the gate's "statements per event" is measured | One subscription per aggregate type, against today's engine with one subscription (rerun if P0.0 has no such case). The 10-subscription comparison is phase 3's gate, where one reader serves many subscriptions |
| 4 | Explicit `xid` step in the head-table append | Kept: `pg_current_xact_id()` first, inside the same statement, so I4 rests on the argument P0.1 proved; the I4 test covers the head-table append explicitly |
| 5 | Module and package name | `components/postgresql-event-log`, package `dk.trustworks.essentials.components.eventlog`, tables prefixed `el_` |

The plan inconsistencies found while drafting this design were corrected in the plan the same day.

## 6. Results (2026-11-15)

Measured by `spike/EventLogGateSpikeIT` (`scripts/run-phase0-spikes.sh --suite p2-gate`) and today's engine by
`CurrentEngineBaselineSpikeIT` (`--suite p2-gate-old`), on the development VM: PostgreSQL 18.4 in a container with
`fsync=off`. Server CPU and absolute probe costs still need the dedicated server; the VM's probe costs (245-440 µs per
probe at 10 connections, against 17 µs in a tight loop) look like cold, low-rate effects.

### 6.1 Gate: statements per delivered event, one subscription

Reader statements per delivered event, at 10 and 100 events per second:

| Engine | 10 ev/s | 100 ev/s | Idle | Writer statements per event |
|---|---|---|---|---|
| Today's engine (P0.0 shape) | 6.74-6.85 | 1.03-1.04 | 1.5 statements/s | 3.0 |
| New engine, 100 ms backstop (p50 about 50 ms, today's latency) | 1.06 | 0.107 | 9.6 statements/s | 2.00 |
| New engine, default 1 s backstop (p50 about 505 ms) | 0.200 | 0.029 | 1.00 statement/s | 2.00 |

**The gate passes.** With ten subscriptions (not gated in phase 2) the per-delivery figures stay the same
(new 1.076 / 0.108 at 100 ms, old 6.79-6.91 / 1.04), but idle cost grows with the number of subscriptions: 9.9
statements/s at 1 s and 98/s at 100 ms, against today's 15/s. Phase 3's shared reader is what flattens it. Phase 2 also
holds one pooled connection per subscription, so the pool must be larger than the number of subscriptions.

### 6.2 Decision 1: the `event_id` index

| Events per append | WAL bytes per event, with index | Without | Execution per append (indicative) |
|---|---|---|---|
| 1 | 1 012 | 922 | 15-16 µs vs 15 µs |
| 10 | 700 | 609 | 55 µs vs 47.5 µs |
| 100 | 684 | 590 | 390-460 µs vs 318-362 µs |

The index costs about 92 bytes of WAL per event and 15-20 % execution time at ten or more events per append, about
three quarters of P0.7's `UNIQUE(event_id)` saving of 110-130 bytes. **Outcome: the index stays, with no opt-out for
now.** Event-id lookup (§12.3's adapter, causation chains) needs it, and the rest of P0.7's layout saving is untouched.
An opt-out is a schema-contributor setting that can be added later without a migration if a user needs the WAL back.

### 6.3 Probe back-off

The unit-B rule (wait = probe cost × 100, floored at the backstop) assumed one reader. At 400 connections with 40
readers, the 1 s floor alone let the probes take 6.4 % of one core, and cost × 100 without the floor 28.9 %. The rule is
now `cost × 100 × max(1, backends / 10)`, still between the backstop floor and `probeBackoffMax`. The backend count comes
from the probe's existing `pg_stat_get_activity` scan, so it costs no extra statement. Remeasured, the probes take
0.04-0.10 % of one core at 10 connections, 0.38-0.42 % at 100 and 0.82-0.91 % at 400.

The cost is latency while a candidate is outstanding and nothing new arrives: about 9 s at 400 connections on the VM,
where a probe costs about 2.5 ms. At P0.12's server cost (0.55-1.4 µs per backend) the same rule waits about 1-2 s. Above
roughly 400 connections `probeBackoffMax` (10 s) caps the wait and the 1 % budget is exceeded. Both figures need the
dedicated-server run before the default is final; phase 3's shared reader lowers the number of probing readers per JVM.
