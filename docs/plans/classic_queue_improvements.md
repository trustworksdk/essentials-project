# Classic durable queue improvements

Branch: `classic_queue_improvements`. Scope: `components/postgresql-queue` (`PostgresqlDurableQueues`, the
"classic" engine) and, where the notification trigger lives, `components/foundation`
(`ListenNotify`, `MultiTableChangeListener`). The shard-owned engine is out of scope; it already does
everything listed here, and more (see `docs/design/durable-queue-shard-owned.md`).

Source of the ideas: a comparison of the classic engine with Wolverine's (.NET) PostgreSQL queue.
Wolverine itself has never been measured, so every gain below is a hypothesis until the measurement
named next to it has been run.

Constraints that apply to every step:

- `DurableQueues` is a stable central API: changes are additive, existing behaviour stays the default
  unless a step says otherwise and the release notes say so.
- No change may add work to the hot path (enqueue, claim, acknowledge) without a measurement that shows
  it pays for itself.
- Schema changes go through the schema contributor (`SchemaChange`), must be safe against an existing
  object, and must work in `essentials.schema.mode=validate`.
- Every user-visible change gets an entry in `docs/releases/NEXT_VERSION_RELEASE.md` in the same commit.

## What was not taken from Wolverine, and why

- **Delete on receive plus a per-node inbox table.** It moves the claim write to another table instead
  of removing it: two dead tuples per message either way, and the payload is written twice. Classic's
  partial indexes already keep in-flight rows out of the claim scans, which is the benefit Wolverine
  gets from it. Only ownership removes the claim write, and that is the shard-owned engine.
- **A separate scheduled-messages table.** Classic's ready index is ordered by `next_delivery_ts`, so the
  claim scan already stops at the first future-dated row.
- **Buffered (at-most-once) mode, best-effort ordering, a 5 s default poll.** Weaker than what classic
  already guarantees.

## Measured baseline (from earlier work)

`docs/design/durable-queue-measurements.md` §1, 200-byte payloads, one producer enqueueing 100 messages
per transaction: 1 904 WAL bytes, 1.98 dead tuples, 1.00 row updates and 1.07 commits per message.
That run had the change-notification trigger installed (the builder's default), so its cost is inside
those figures but has never been separated out.

## Steps

Each step lists the hypothesis, how it is measured, and what would make us drop it.

### Step 0 - Measurement harness (this branch, first)

`examples/essentials-performance-lab`, new `ClassicQueueCostIT`, gated by `-Dbenchmark.run=true` like
the other lab suites. Classic engine only; the arms vary one thing at a time and are interleaved by
`AbRunner`, so arms are comparable within a run and not across runs.

Arms:

| Arm | Notification trigger |
|---|---|
| `row-insert-update` | Today's default: `FOR EACH ROW`, `AFTER INSERT OR UPDATE`, JSON payload |
| `row-insert-only` | `FOR EACH ROW`, `AFTER INSERT` only |
| `statement-per-queue` | `FOR EACH STATEMENT`, `AFTER INSERT`, one `pg_notify` per distinct `queue_name` in the statement |
| `no-listener` | No trigger and no listener (floor; delivery falls back to plain polling) |

Load shapes:

- **batched**: one producer, 100 messages per transaction - the shape of the existing baseline, so
  `row-insert-update` must reproduce ~1 904 WAL bytes per message or the harness is wrong.
- **concurrent**: 8 producers, one message per transaction - the shape of an outbox written from
  business transactions, where the cluster-wide notify commit lock can actually serialize commits.

Per arm: WAL bytes, dead tuples, row updates and commits per message (database counters; these are
reliable inside the devcontainer), notifications received (counted on a separate `LISTEN` connection),
and the share of sampled `pg_stat_activity` backend time spent waiting on `Lock`/`object` and
`LWLock`/`WALWrite`. Throughput is printed but is only indicative in the devcontainer.

A second test measures the stuck-message reset query (step 3) with `EXPLAIN (ANALYZE, BUFFERS)` on a
table with a realistic backlog, with and without the proposed partial index.

### Step 1 - Stop the notification trigger from firing on claims

Today the trigger is per row and fires on INSERT **and UPDATE**, inside the transaction, with a payload
built by `row_to_json`. Every claim, retry and dead-letter update therefore sends a notification too,
and because each payload is different PostgreSQL cannot fold them together. A notifying transaction
takes a cluster-wide lock at commit and holds it through its WAL flush; the shard-owned engine measured
backends waiting on it for 20 % of active time (34 % at peaks) in the trading demo.

Options, cheapest first:

1. Drop `UPDATE` from the trigger. Check first what relies on update notifications: a retry or a
   resurrected dead letter becomes ready again through an UPDATE, so the consumer must still find it
   through polling (it does today when notifications are lost) or through a local in-JVM signal.
2. A statement-level trigger that sends one notification per distinct queue name per statement.
3. Notify from the application after commit, coalesced per queue name (what both Wolverine and the
   shard-owned engine do). Largest change; needs a `UnitOfWork` after-commit hook in every enqueue path.

Measured by: step 0, all arms. Drop it if `row-insert-only` and `statement-per-queue` do not reduce
commit-lock waits or notifications per message measurably in the concurrent shape.

### Step 2 - A handler that outlives `messageHandlingTimeout` is delivered twice at once (correctness)

Recovery of a claimed message is purely time-based: once `delivery_ts` is older than
`messageHandlingTimeout` (30 s default), any instance resets it and it is claimed again. Nothing appears
to refresh `delivery_ts` while the handler runs, so a slow handler on a live instance gets a concurrent
duplicate; on an ordered queue that also lets two messages of one key run at the same time.

First: a failing test that proves or disproves it (short `messageHandlingTimeout`, a handler that blocks
past it, assert no second concurrent delivery). Only if it fails, choose a fix:

- refresh `delivery_ts` for in-flight messages from the consumer (one batched UPDATE per interval,
  not per message), or
- stamp the claiming instance on the row and recover by instance liveness rather than elapsed time
  (Wolverine's model; a bigger change).

### Step 3 - Index the stuck-message reset

`getResetMessagesStuckBeingDelivered*Sql` filters on `is_being_delivered = TRUE AND delivery_ts <= ?`.
No index covers that (the ready indexes exclude in-flight rows), so every check on every instance scans
the whole shared table, dead letters included. Candidate: a partial index
`(delivery_ts) WHERE is_being_delivered`. It stays tiny, but every claim then inserts one more index
entry. Measured by: step 0's reset-query test (time and buffers) and the WAL per message of the
batched shape with the index present. Drop it if the scan is cheap at realistic table sizes.

### Step 4 - Bound the unbounded statements

The stuck-message reset UPDATE and `purgeQueue` DELETE have no LIMIT. Run them in bounded chunks
(`ctid IN (SELECT ctid ... LIMIT n)`) in a loop. Correctness-neutral; matters only for very large
tables, so it gets a unit test of the chunk loop rather than a benchmark.

### Step 5 - Opt-in autovacuum settings for the queue table

A builder option that applies `autovacuum_vacuum_scale_factor = 0` and a fixed
`autovacuum_vacuum_threshold` to the queue table through the schema contributor. Off by default: these
are operator settings. Measured by: dead tuples outstanding over a sustained run, if step 0 shows the
table bloating.

### Later, needs a schema migration (decide after steps 1-3)

- Move dead letters to their own table.
- Store the payload as `bytea` instead of `jsonb` (the JSON text stays identical; anything that queries
  the payload as JSON in SQL would break).
- `uuid` instead of `text` primary key; `ON CONFLICT DO NOTHING` on enqueue.
- Lock and update by `ctid` instead of `id = ANY(...)`.

Each of these moves classic towards the shard-owned engine's schema, so the question for each is
whether the effort belongs in classic at all.

## Results

### Run 1 - 2026-11-28, devcontainer, `ClassicQueueCostIT`

200-byte payloads, three interleaved repetitions per arm. The trading-demo containers were running on
the same Docker host, so throughput (msgs/s) is indicative only; the per-message counters are not
affected. Raw data: `examples/essentials-performance-lab/target/perf-lab-baseline/classic-queue-cost.json`
and `classic-queue-reset-query.json`.

**Harness check.** The default arm in the batched shape gave 1 566 WAL bytes per message against the
published 1 904 (−17.8 %), with dead tuples (2.00 vs 1.98) and updates (1.00 vs 1.00) matching. The
published figure is from 2026-09 on another run; only arms inside this run are comparable with each
other. Commits per message read slightly high in every arm because `xact_commit` never settled within
16 s after a run.

**Step 1, batched shape** (1 producer, 100 messages per transaction, 20 000 messages):

| Arm | WAL B/msg | Dead tuples/msg | Commits/msg | Notifications/msg | Lock/object wait | msgs/s |
|---|---|---|---|---|---|---|
| row-insert-update (today) | 1 566 | 2.00 | 1.23 | 2.000 | 0.8 % | 13 918 |
| row-insert-only | 1 565 | 2.00 | 1.13 | 1.000 | 0.0 % | 5 067 |
| statement-per-queue | 1 565 | 2.00 | 1.13 | 0.010 | 0.0 % | 13 193 |
| no-listener | 1 550 | 2.00 | 1.12 | 0.000 | 0.0 % | 9 062 |

**Step 1, concurrent shape** (8 producers, one message per transaction, 8 000 messages):

| Arm | WAL B/msg | Commits/msg | Notifications/msg | Lock/object wait | msgs/s |
|---|---|---|---|---|---|
| row-insert-update (today) | 1 310 | 3.21 | 2.000 | 67.1 % | 5 857 |
| row-insert-only | 1 353 | 3.10 | 1.000 | 69.0 % | 6 065 |
| statement-per-queue | 1 383 | 3.10 | 1.000 | 62.2 % | 6 211 |
| no-listener | 1 363 | 2.12 | 0.000 | 0.0 % | 11 331 |

What the numbers show:

- The trigger costs no measurable WAL in either shape.
- Under concurrent one-message transactions, every notifying arm spends 62-69 % of sampled active
  backend time waiting on `Lock/object` (the notify commit lock), against 0 % without a listener, and
  throughput roughly halves. Dropping UPDATE notifications (option 1) or notifying once per statement
  (option 2) halves the notification count but does **not** reduce the lock wait: each enqueue
  transaction still sends one notification, so each commit still takes the lock. Only taking
  `pg_notify` out of the enqueue transaction (option 3: after commit, coalesced across transactions,
  local in-JVM signal when the consumer is in the same process) can remove it.
- In the batched shape the lock wait is under 1 % in every arm; the notify cost only shows when many
  small transactions commit concurrently, which is the outbox shape.
- Option 2 needs a payload the consumer accepts: `DefaultQueuedMessage` rejects a notification without
  `added_ts` (`DefaultQueuedMessage.java:85`), so a bare `{table_name, sql_operation, queue_name}`
  payload would log an error per notification and wake nobody. The arm added `'added_ts', now()`.
- Unexplained: batched `row-insert-only` ran at 5 067 msgs/s against ~13 000 for the default. It may be
  noise from the shared host, or the consumer's polling backoff reacting to fewer notifications. Recheck
  before drawing anything from it.

**Step 3, stuck-message reset** (100 000 ready, 2 000 dead letters, 200 stuck rows, 3 queues, 43 MB
table; `EXPLAIN (ANALYZE, BUFFERS)` in a rolled-back transaction):

| | Plan | Buffer hits (scan) | Median time |
|---|---|---|---|
| No index | Seq Scan | ~4 100 | 8.73 ms |
| `(delivery_ts) WHERE is_being_delivered` | Index Scan | 9-10 | 1.21 ms |

The index costs +59 WAL bytes per message (+3.8 %, 1 625 vs 1 566, IQRs separated) on every claim, to
save ~7.5 ms in a query that runs about once per `messageHandlingTimeout` (30 s) per instance. Verdict:
not worth it as a default. Revisit only for tables far larger than this one, and then as an opt-in.

### Consequences for the plan

- Step 1: options 1 and 2 are dropped as fixes for the commit-lock cost (they reduce notification
  count only). Next: prototype option 3 as a harness arm (enqueue without trigger, one coalesced
  `pg_notify` per queue name from a separate after-commit transaction, batched across producers) and
  measure it in the concurrent shape before touching production code.
- Step 3: dropped as a default.
- Step 2 (correctness test) is independent of these numbers and is next in parallel.

### Run 2 - 2026-11-28, option 3 arm (`after-commit-coalesced`)

Same harness and conditions as run 1. The new arm has the listener but no trigger. After its unit of
work commits, a producer adds the queue name to a shared set. A single notifier thread drains the set
and sends one autocommit `SELECT pg_notify(...) FROM unnest(?)`, with the same payload as
`statement-per-queue`.

**Concurrent shape** (8 producers, one message per transaction):

| Arm | WAL B/msg | Commits/msg | Notifications/msg | Notifier statements/msg | Lock/object wait | msgs/s |
|---|---|---|---|---|---|---|
| row-insert-update (today) | 1 318 | 3.21 | 2.000 | - | 65.4 % | 5 908 |
| row-insert-only | 1 358 | 3.10 | 1.000 | - | 65.1 % | 6 056 |
| statement-per-queue | 1 383 | 3.10 | 1.000 | - | 64.7 % | 6 206 |
| after-commit-coalesced | 1 388 | 3.08 | 0.495 | 0.495 | 0.0 % | 11 364 |
| no-listener | 1 360 | 2.12 | 0.000 | - | 0.0 % | 13 605 |

- Taking `pg_notify` out of the enqueue transaction removes the commit-lock wait completely, and
  throughput doubles to within ~17 % of the no-listener floor. Coalescing halves the notifications
  even with only 8 producers.
- In the batched shape the arm costs the same WAL as every other arm (1 531-1 593 B/msg) and sends one
  notification per 100-message transaction.
- Batched throughput is **not usable** in this environment. In every arm, including today's default,
  the repetitions split into fast (~1.3-2 s) and slow (~5.2-5.4 s) runs. The slow runs are CPU-bound
  in the database (about three times as many CPU samples, fewer active backends), not client backoff.
  So run 1's "row-insert-only is slow" was this effect, not the arm. The cause is not identified; it is
  a candidate for later investigation.
- In-JVM consumers are already woken after commit without NOTIFY (`LocalEnqueueSignal` in
  `PostgresqlDurableQueues`). NOTIFY only serves consumers in other JVMs, which is what the coalesced
  notifier would have to keep doing.

Outcome for step 1: option 3 is the design to implement. Its production shape (where the notifier
lives, how it hooks into `LocalEnqueueSignal`, schema change to drop the trigger, compatibility with
older instances that still rely on the trigger during a rolling upgrade) is still to be designed.

### Step 2 outcome - 2026-11-28

Reproduced by `PostgresqlDurableQueuesSlowHandlerIT` (1 s timeout, 3 s handler) in all three cases:
unordered with the centralized fetcher, unordered with the traditional consumer, and ordered messages
across two instances (the same key in two handlers at once). The redelivery is a real reset:
`redelivery_attempts` goes from 0 to 1 and `delivery_ts` changes while the first handler still runs.
The second handler's acknowledgement then fails, because the first one has already deleted the row.

Decision (user, 2026-11-28): fix it with a claim heartbeat. Each instance refreshes `delivery_ts` for
its own in-flight messages in one batched UPDATE at an interval well inside `messageHandlingTimeout`,
and only for messages that have been in flight longer than that interval, so fast handlers cost
nothing. The refresh is fenced on the claim it refreshes, and a handler that runs past a cap stops
being refreshed and is logged at WARN, so a hung handler is still taken back eventually.
