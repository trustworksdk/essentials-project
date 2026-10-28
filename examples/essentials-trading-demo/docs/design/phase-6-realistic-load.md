# Phase 6 design — realistic load

**Status:** decided (§9, 2026-10-23). Decisions are marked **(Q-n)** in the text.
**Plan:** [`../EXPANSION_PLAN.md`](../EXPANSION_PLAN.md) phase 6; decisions D1, D11; simulator requirements SR-4,
SR-15, SR-20, SR-23, SR-34, SR-39; Essentials feature EF-2.
**Builds on:** the per-phase load scenarios in `_demo_harness` (ledger, order flow, funding), each measured alone, and
the reconciliation of phase 5, which checks a run's outcome against both simulators.

Phases 1 to 5 each measured one flow, in process, on one instance. Phase 6 runs the brokerage the way it would be
used: many customers in sessions, every flow at once, a burst at each business day's open, on more than one instance,
with faults, for long enough that the stores grow. The demo's second purpose (EXPANSION_PLAN, intro) is to push
Essentials under realistic load to find bugs, performance work and missing features; this phase is where that
purpose is served end to end, and each finding becomes a library change measured in its own right.

---

## 1. What exists, and what it does not show

| Scenario | Drives | Measured (one instance, devcontainer) |
|---|---|---|
| Ledger (`/api/admin/ledger-load`) | `accounts` commands, in process | ~1 300-1 650 commands/s; views trail at 700-800 events/s |
| Order flow (`/api/admin/order-flow`) | `PlaceOrder`/`CancelOrder`, in process | ~366 completed orders/s (compose settings), end to end p50 ~16 s |
| Funding (`/api/admin/funding-load`) | deposits/withdrawals, in process | 2 000 payments, 102 completed/s, 0 breaks |

None of them goes through HTTP, authentication or JSON; none runs two flows at once; each runs for seconds, on fresh
accounts, with the simulators' fault injection off. Two things they cannot see by construction:

- **The web edge.** The API authenticates with HTTP Basic against a BCrypt hash (`PasswordEncoderFactories`'
  default), so every request pays a BCrypt check - tens of milliseconds of CPU. A load over HTTP as the API stands
  would measure mostly that.
- **The long run.** `external_inbound_event` grows by one row per event per consumer and is never pruned
  (`integration/CLAUDE.md`); closing books roll every account at each business day; snapshots, archives and the
  shard-owned queues' tables accumulate. None of that shows in a 20-second run.

## 2. The session load model (Q-1, Q-2)

A **load driver** simulates customers. Each simulated customer is a session: it logs in, then over the business day
does what a retail customer does, with think time between steps:

| Step | Share of sessions | Through |
|---|---|---|
| Look at the account, positions, orders | every session, several times | `GET /api/accounts/{id}`, `/orders`, `/valuation` |
| Buy: market or marketable limit | most sessions, 1-3 | `POST /api/accounts/{id}/orders` |
| Sell part of a settled position | some | same |
| Resting limit, cancelled later or left to expire (`DAY`) | some | `POST .../orders`, `DELETE .../orders/{orderId}` |
| Deposit | few | `POST /api/accounts/{id}/deposits` |
| Withdraw | fewer | `POST /api/accounts/{id}/withdrawals` |

The shares, think times and population are configuration, with a default profile (§4). Customers are a fixed
population (`LD-<n>`, registered and funded once, then reused run after run), so accounts carry history - positions,
closed books, snapshots - as real ones do.

- **Through HTTP, as a customer would (Q-1).** The driver calls the public API with each customer's own login, so the
  web edge, Spring Security, JSON (Jackson 3, the wire-format rules) and the controllers are under load too, and the
  API the phase 7 UI will use is exercised before it exists. The in-process scenarios stay for isolating one flow.
- **Logged-in sessions, not Basic per request (Q-1).** A session logs in once and carries its cookie, so BCrypt runs
  once per session, as with a browser. With several instances, sessions need either stickiness (the driver pins each
  customer to one instance, as a load balancer with affinity would) or a shared session store. Proposed: pinning in
  the driver, no session store - the demo has no load balancer, and Spring Session would be a dependency for the demo
  alone.
- **A separate process (Q-2).** The driver runs in its own JVM - a `load-driver` Spring profile of this module with no
  web server and no contexts, started by `run-load.sh` - so its CPU is not the instance's. On this devcontainer they
  still share the machine (§6).

## 3. Business-day shape and the market-open burst

The exchange's clock runs a business day per real minute. Without trading sessions (they reject orders
`MARKET_CLOSED` on the accelerated clock) there is no open; the driver makes one: on each `BusinessDayClosed` (read
from the exchange's event log, as the app does), the next day's sessions start, and a share of the day's orders -
default 30 % - is placed in its first tenth (6 real seconds). That is the burst the shard-owned engine, the inboxes
and the `EventProcessor` forward must absorb; between bursts the load is steady.

## 4. Targets (Q-3)

A target makes "realistic" testable. Proposed default profile and pass criteria, to be met on one instance first,
then on two:

| | |
|---|---|
| Population | 10 000 customers, 2 000 active per business day |
| Steady | 100 orders/s, 5 payments/s, 400 reads/s |
| Open burst | 600 orders/s for 6 s, each business day |
| Duration | 60 business days (an hour of real time) |
| Pass | every order and payment reaches its outcome and is booked; the next close's reconciliation finds 0 breaks and opens 0 cases; no dead letters; order end to end p99 < 10 s outside bursts, and recovers to that within 30 s after each burst; no store grows without bound (§5) |

The orders figure is under a third of the measured in-process ceiling (~366/s), so the HTTP edge, the concurrent
flows and the burst are what is tested, not the known `EventProcessor` limit. The burst is above that ceiling on
purpose: a backlog that builds and drains is the realistic case, and the drain time is the number.

## 5. Long-run hygiene

What an hour-long run makes visible, and what is proposed for each:

- **`external_inbound_event` pruning.** Delete rows older than a retention by `received_at`, on a schedule behind a
  fenced lock. The retention must outlast every way a copy can still arrive: the simulators' webhook retry span and a
  catch-up read from a cursor that went back (it only ever re-reads what it accepted, but a reset cursor reads from 0).
  Proposed: 7 days real time, configurable; a reset cursor then reads from the log's start and dedupe covers only the
  last week, which the receivers tolerate because every consumer is idempotent on the source's ids (D11). Measured
  before and after: the intake's insert time at a few million rows.
- **The rest is watched, not changed in advance**: events, snapshots and archives per account; closing-book rolls
  per close (every active account at every close); the shard-owned queues' tables and dead letters; the CDC slot's WAL.
  The run reports their sizes at its start and end; a store that grows without bound is a finding.

## 6. More than one instance, and this machine (Q-4)

Two and three instances, the configuration the shard-owned engine is for (D1). This devcontainer runs PostgreSQL,
both simulators, every instance and the driver on 8 shared cores; `_demo_harness/CLAUDE.md` already records that
horizontal results here "are not evidence". Proposed: phase 6 uses more instances here for **correctness under load**
- ownership and rebalancing, no message delivered twice for a key, nothing lost, reconciliation clean - and reports
throughput per instance count as indicative only. Scaling evidence needs PostgreSQL and the simulators on hosts of
their own; that is a run on other hardware when the user has it, with the same driver and profile.

## 7. Faults under load

The scenarios so far ran with every fault switched off. Under the steady load, one at a time, each with the pass
criteria of §4 still holding:

| Fault | How |
|---|---|
| Webhook duplicates, missing fields, out-of-order | the simulators' fault injection, at the rates `ExchangeSimulatorWebhookIT` uses |
| Webhook endpoint unreachable | stop answering for a minute; the catch-up readers must cover it |
| An instance killed (`SIGKILL`) and restarted | its shards rebalance; in-flight messages are redelivered once |
| A simulator restarted | the routers' retries and the catch-up readers ride it out |
| Seeded statement breaks | phase 5's: every one found, one for one, under load |

## 8. Measuring, and "benchmarks against `main`" (Q-5)

The plan says phase 6 benchmarks against `main`. `main` has none of this demo's contexts, so the same scenario cannot
run there. Proposed reading: **a phase 6 baseline** - the profile of §4 run at the start of the phase on this branch,
recorded (driver report plus Grafana snapshots) in `_demo_harness/CLAUDE.md` - and every library change the phase
leads to is measured before and after against it, from worktrees, under the existing rule for queue changes. Changes
expected: EF-2 or the parked batched view handling (`docs/view-event-processor-batched-handling.md`), once the release
is merged in, since the views are the known bottleneck.

The driver reports per run: rates and latencies per step and per flow (placement, end to end, by stage from the event
tables as the order-flow scenario does), backlog and drain per burst, errors and refusals by status, and the store
sizes of §5. The observability profile adds a dashboard of the same.

Simulator side, for the record: the exchange already reports per-instrument market-data and command metrics on
`/metrics` (SR-15) and runs in memory (SR-23); a replayed price path (SR-4) and the provider load profile (SR-20) are
not needed for the targets above - synthetic prices give the burst - and stay optional.

---

## 9. Decisions

Decided 2026-10-23.

| | Question | Decided |
|---|---|---|
| **Q-1** | How the load reaches the brokerage | Over HTTP, through the public API, each customer logged in once per session (cookie), pinned to one instance by the driver; the in-process scenarios stay |
| **Q-2** | Where the driver runs | A separate JVM, `run-load.sh` |
| **Q-3** | Targets | §4's profile is the goal, not the start: **start smaller, everything configurable** (population, active customers, steady and burst rates, mix, duration) |
| **Q-4** | Instances on this machine | **One instance is the baseline.** Two or three are a stretch in the devcontainer; the user runs them on other hardware once the driver is more complete |
| **Q-5** | "Benchmarks against `main`" | **Not yet** - not before the next release is merged into this branch |
| **Q-6** to **Q-10** | Faults under load (§7) | §10.6 (2026-10-27) |

---

## 10. Faults under load, in detail (§7)

**Status:** decided, 2026-10-27 (§10.6: Q-6 to Q-10 as proposed; Q-8 starts with one instance).

§7 lists five faults to run one at a time under the steady load, with §4's pass criteria still holding. This section
works out how each one is switched on, what proves it really happened, and what "it held" means for it.

### 10.1 What the pieces already give us

- **Both simulators have a per-subscription delivery fault policy** (`PUT /test/webhooks/{id}/faults`, `DELETE` to
  clear): `duplicateProbability`; `missing`, `nulls` and `invalid`, each damaging the listed fields or one random leaf;
  `outOfOrder` (`holdMs`); `delay`; and a `seed`. It damages delivered copies only, never the canonical event.
  `GET /test/attempts?subscriptionId=` lists every attempt with the faults applied to it, which is the evidence that a
  fault happened. `ExchangeSimulatorWebhookIT` already uses duplicates, missing fields and out-of-order at 0.3 each.
- **Subscriptions are configuration** (`simulators/*.yaml`, `source: config`) and are found by name through
  `GET /api/webhooks`. Every one of them points at instance 1 (`:8080`); other instances receive only through the
  catch-up readers.
- **Anything other than a 2xx is retried with backoff until the delivery is dead-lettered** at the source. A refused
  copy (`422` from a validator) and an unavailable endpoint look the same to the simulator.
- **The catch-up readers are the guarantee** (`integration/CLAUDE.md`): a delivery dead-lettered at the source must
  still arrive through `/api/events`. No fault run may call the simulators' dead-letter retry; that would hide a gap.
- **Placing an order is idempotent on the client's `orderId`** (`PlaceOrderHandler` returns if the order exists), and
  the driver chooses the ids (`LD-<run>-O<n>`), so the driver can retry a placement whose answer it never got.
- **Seeded reconciliation breaks are simulator configuration** (`EXCHANGE__CLEARING__BREAKS__*`, the gateway's
  `statement.breaks.*`), as the phase 5 ITs set them; they need the simulators started with them.
- **The load report** already gives per-10 s placement buckets, stages, dead letters, open cases and missing
  statements, and the driver's checks.

### 10.2 The faults

One fault per run. Load: the one-instance baseline (2 000 customers, 1 000 active, 100 orders/s, 300/s for 6 s at each
open), on a fresh database and fresh simulators, for 10 business days; a fault with a window opens it in business day
4 and closes it after the window, leaving at least 5 days to recover.

| | Fault | Switched on by | Proof it happened | Holds when |
|---|---|---|---|---|
| **F-1** | Damaged, duplicated and reordered webhook copies, the whole run | The driver (`--fault=F-1`), on every subscription of both simulators at load start: duplicates 0.3, `missing` 0.2 and `nulls` 0.1 (one random leaf, so optional fields too), `outOfOrder` 0.3 (`holdMs` 500), seed from the run token; cleared after the drain. No `delay`: it holds the simulator's delivery worker, which would measure the simulator | `/test/attempts` counts per fault kind, per subscription | §4's pass, no difference in outcomes; the latency cost reported, not judged |
| **F-2** | Receiver unavailable for 60 s | The driver, through a harness switch (Q-6) | Source-side failed attempts and dead letters over the window | §4's pass with every dead-lettered delivery arriving through catch-up; end-to-end p99 back under 10 s within 30 s of the window closing |
| **F-3** | Instance `SIGKILL`ed during an open, restarted 10 s later | The run script (Q-9) | The kill and restart times; the process's exit status | §4's pass; in-flight messages redelivered, none lost, no per-key ordering violation reported by the engine beyond the network's; customers' sessions re-established (Q-7) |
| **F-4** | A simulator restarted (`docker restart`) during a business day, exchange and gateway in separate runs | The run script (Q-9) | Container restart time; the routers' failed calls | §4's pass; market buys refused for want of a quote during the window are counted, not failures |
| **F-5** | Seeded reconciliation breaks under load | Simulators started with the phase 5 IT settings (clearing: omit 0.3, alter 0.3, phantom 1.0; the bank's equivalents) | `/test/clearing/breaks`, `/test/statements/breaks` | Every seeded break found one for one, none invented, a case for each one still present at the next close; the rest of §4's pass |

Built in this order: F-1 (driver only), F-5 (configuration, plus a one-for-one check in the driver), F-2 (one harness
switch), F-4 and F-3 (process control). Each lands with a run recorded in `_demo_harness/CLAUDE.md`.

### 10.3 The driver

- `--fault=<F-n>` with `--fault-at-day` and `--fault-duration`; the driver applies the HTTP-level faults itself
  (F-1, F-2) and, for the others, prints a `fault window open` / `closed` line the run script acts on and times.
- **Riding out an outage as a client would.** A request that fails without an answer (connection refused or reset,
  timeout) is counted and, for a placement, retried with the same `orderId` until it answers or the drain deadline
  passes (Q-7). A `401` already makes a session log in again, which covers a restarted instance's lost sessions.
- **The report gains a fault block**: the window's times, the evidence counts of §10.2, the source-side attempts and
  dead letters per subscription, refusals and failed requests by step inside and outside the window, and the per-10 s
  end-to-end p99 from the window's opening until it is back under 10 s.

### 10.4 The run script (Q-9)

F-3 and F-4 need process control the driver should not have. A `run-faults.sh <F-n>` beside `run-load.sh`: recreates
the database and the simulators (with F-5's settings when asked), starts the instance with the full JIT and a pid file
(`spring.pid.file`, Spring Boot's own), starts the driver, and on its `fault window open` line kills and restarts the
instance or restarts a simulator. It leaves the user's demo database alone, as the profiling runs do.

### 10.5 Not in this section

Two or three instances (§6) - F-3 against a second instance, which exercises ownership moving between processes, is
the first two-instance run once these pass on one (Q-8). Long runs (§4's hour) - separate. Faults in the brokerage's
own outbound calls beyond a simulator restart (slow responses, `5xx` from the exchange) - the simulators would need a
fault mode for their own API; noted as a candidate simulator requirement if F-4 shows the need.

### 10.6 Decisions

Decided 2026-10-27, as proposed.

| | Question | Decided |
|---|---|---|
| **Q-6** | How F-2 makes the receiver unavailable | A harness switch (`/api/admin/webhook-outage?seconds=`) that makes every `SignedWebhookReceiver` answer `503` before doing anything. Alternatives: damage every copy (`missing` on a required field at probability 1, answered `422`) - no app change, but a validation refusal rather than an outage; pause the subscriptions - the simulator then holds deliveries instead of failing them, so retries and dead letters are never exercised |
| **Q-7** | A placement whose answer was lost | The driver retries it with the same `orderId` (idempotent) until it answers; alternative: count it as failed and rely on the brokerage's own record. Retrying is what a real client does and exercises the idempotency |
| **Q-8** | F-3 on one instance or two | One first: the restarted instance comes back under the same instance id and takes its leases back at once. Two (kill the instance that is not the webhook target, so shards move to the survivor) as the first §6 run |
| **Q-9** | Who controls processes | `run-faults.sh`, coordinated by the driver's printed window lines; the driver stays HTTP-only |
| **Q-10** | Load during the faults | The one-instance baseline above, 10 business days, the fault in day 4; not §4's full profile, so a failure is the fault's and not the load's |
