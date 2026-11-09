# Running the trading demo - a guide

How to start the demo, what each page is for, and how to show or test each part of it: a trading day played at any
time of day, the customer's side, background traffic, two instances, and the framework underneath. For how the demo
is built, see [`../README.md`](../README.md) and the module's `CLAUDE.md`; for why, [`EXPANSION_PLAN.md`](EXPANSION_PLAN.md).

---

## 1. Before the first start

- **JDK 25** and **Docker** (Docker Desktop on macOS). The run scripts default `JAVA_HOME` to the devcontainer's JDK; on
  a Mac, `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` first.
- **The simulator images**, `exchange-simulator:dev` and `payment-gateway:dev`, built from the simulators' own
  repository. Nothing in this repository builds them; without them only the `simulators`-free mode runs.
- **The Essentials modules installed in your local Maven repository.** The run scripts start the demo offline (`-o`)
  against `~/.m2`, so build once from the repository root, and again after pulling library changes:

  ```bash
  ./mvnw -DskipTests install -pl examples/essentials-trading-demo -am
  ```

Everything below is run from `examples/essentials-trading-demo`.

## 2. Start, stop, reset

| | |
|---|---|
| `./run-instance.sh 1 simulators` | Instance 1 on [localhost:8080](http://localhost:8080), with PostgreSQL, the exchange and the payment gateway |
| `./run-instance.sh 2 simulators` | A second instance on [localhost:8081](http://localhost:8081), in another terminal (optional) |
| `./run-instance.sh 1 observability,simulators` | Also Grafana ([localhost:3000](http://localhost:3000)), Prometheus, Tempo, Loki |
| `./stop-instances.sh` | Stops the instances, the load driver and the containers; **keeps the data** |
| `./stop-instances.sh --apps` | Stops only the instances and the load driver |
| `./reset-stack.sh` | **Deletes** the database and both simulators' state, together - a fresh start |

- **Reset the database and the simulators together, always with `reset-stack.sh`.** The simulators remember every
  order and payment; a new database next to simulators that remember an earlier run receives that run's fills as
  orders it never had (dead letters, "unknown order" warnings). The demo warns once at startup when it sees this.
  All three live in named volumes (`postgres-data`, `exchange-data`, `gateway-data`), kept and removed together.
- The exchange's clock starts on **Monday 2030-01-07, 08:00 New York**, at 1440x - a business day per real minute -
  from the moment the containers start.

## 3. Who logs in where

| Page | Who | What it is |
|---|---|---|
| [`/ops`](http://localhost:8080/ops) | `operator@demo.local` / `operator-demo-password` | Demo control: the exchange's clock, background traffic, the system at a glance |
| [`/app`](http://localhost:8080/app) | `alice@demo.local` / `alice-demo-password` | The customer UI: Portfolio, Trade, Funding. Alice starts with `ACC-ALICE-1`, 100 000 USD |
| [`/essentials/admin`](http://localhost:8080/essentials/admin) | operator | The framework: queues and dead letters, subscriptions, fenced locks, closing books, snapshots, CDC |

Each instance has its own session cookie, so one browser can be logged in to both instances at once. Use separate
browser profiles (or a private window) to be operator and Alice side by side.

## 4. What to show, and what to look at

### 4.1 Play a trading day, at any time of day

The exchange's clock is the system's business time; `/ops` moves it forward (never back).

1. On `/ops`, **Pause**, choose **60x** (an exchange hour per real minute), **Skip to the next open**. The clock stops
   at 09:25 New York.
2. Open Alice's **Trade** page: the ticket says *Market closed* and will not place an order.
3. **Resume**. At 09:30 the market opens: Alice's ticket switches to *Market open*. Place a market buy and watch the
   order's row go *placed - submitted - filled*; her Portfolio shows the position and the cash held, then booked.
4. **Skip to the next close** (15:55). At 16:00 the exchange stops taking orders; a DAY order still open expires.
5. **Advance 1 business day**. The page plays the close and stops before the next open. What happened at the close:
   - the account's books roll into the next business day the next time it changes - Alice's **activity** shows
     *Books closed*, and the account's closing-books generations in the admin console gain one;
   - the day's trades settle the next business day (Portfolio: pending turns settled);
   - deposits due clear; withdrawals complete;
   - the clearinghouse's and the bank's statements arrive and are reconciled (`GET /api/admin/reconciliation/...`).

At **1440x** a whole trading session lasts 16 real seconds - good for showing many days, too fast to follow one.

### 4.2 The customer's side

On `/app` as Alice: **Funding** - link a bank account (pre-linked for Alice), deposit, withdraw, and watch each
payment's status move (*requested - initiated - completed*). **Trade** - market and limit orders, cancel an open limit
order. **Portfolio** - cash buckets (settled, pending, held), buying power, positions and their value at the latest
closing prices.

### 4.3 Background traffic

On `/ops`, **Start** runs 20 simulated customers (`BG-<instance>-<n>`) using the same commands as Alice: buys, sells,
resting limit orders and cancels while the market is open, deposits and withdrawals all business day.

- The rate is per **exchange** minute, so it follows the clock: 2 (the default) is 2 orders a real second at 60x and
  48 at 1440x. An instance caps it at 10 real orders a second (`trading-demo.demo-control.max-orders-per-second`); the
  page says when the cap applies. Two instances at a high rate make a laptop work hard - stay low.
- **Fire a burst**: four times the rate for 15 exchange minutes, like the rush at the open.
- What to watch: *Sent / Outcomes in the last minute* on `/ops`; the **System at a glance** counts; in the admin
  console, the queues' throughput and the subscriptions' progress.

### 4.4 Two instances

Start `./run-instance.sh 2 simulators` beside instance 1.

- **Fenced locks spread over the instances**: `/ops` - *System at a glance - Fenced locks* shows how many each holds.
  A second instance gets its share one lock at a time, about every 10 s; it settles within ~5 minutes.
- **A crash**: stop one instance (Ctrl-C, or `kill -9` its JVM for a hard crash). Its locks move to the survivor;
  the queues' units too. Nothing is lost: every order still reaches its outcome.
- Queue work is shared by both instances whatever the locks: the admin console's shard-owned queues show the
  owners per queue.

### 4.5 The framework underneath

In the admin console (`/essentials/admin`), with traffic running:

- **Shard-owned queues** - every inbox and view queue, their depth, owners and **dead letters** (each with the error
  that parked it; resurrect or delete). The order flow's inboxes are `Inbox:ExchangeOrderRouter`,
  `Inbox:OrderLedgerProcessor`, `Inbox:Orders:ExchangeReports`.
- **Subscriptions** - each event processor's position in its event stream.
- **Closing books** - `Accounts` generations: one per business day an account was touched.
- **CDC** - the replication slot and what it delivers. CDC carries new events to the subscriptions; it can be swapped
  for polling (§5).

### 4.6 Measure

`./run-load.sh` is a separate load driver: customers in HTTP sessions, a burst at each open, a pass/fail report at the
end (`target/load-driver/load-<run>.json`). Defaults: 200 customers, 10 orders/s, 5 business days. Settings:
`LoadDriverSettings`. Run it on a fresh stack (`reset-stack.sh` first), without background traffic, and measure
with plain instances, not with the observability stack attached.

## 5. Polling instead of CDC

The demo delivers new events to its subscriptions over CDC (PostgreSQL logical replication). To run it on polling:

```bash
ESSENTIALS_EVENTSTORE_CDC_ENABLED=false ./run-instance.sh 1 simulators
# polling woken by PostgreSQL NOTIFY on every insert:
ESSENTIALS_EVENTSTORE_CDC_ENABLED=false ESSENTIALS_EVENTSTORE_SUBSCRIPTION_MANAGER_NOTIFY_POLLING_ENABLED=true \
  ./run-instance.sh 1 simulators
```

Use the same mode on every instance, and `reset-stack.sh` between modes. Measured comparison: §7.

## 6. When something looks wrong

| You see | Why | Do |
|---|---|---|
| "This database is new, but the exchange ... remember(s) an earlier run" | The database was reset without the simulators | `./stop-instances.sh`, `./reset-stack.sh` |
| "unknown order" warnings, dead letters in `Inbox:Orders:ExchangeReports` and `Settlement:Reports` | Same: fills for orders this database never had | Reset as above |
| Background traffic orders `REJECTED MARKET_CLOSED` | The market closed; the traffic stops 10 exchange minutes before | Normal near the close |
| "Fell more than 100 events behind the CDC bus N time(s)" (INFO, at most once a minute) | The exchange settles a day's trades at one instant; a subscription catches up from the database in milliseconds | Nothing |
| The machine runs hot | Too much traffic, or two instances at 1440x | Lower the rate, a slower clock, one instance |
| `reset-stack.sh` refuses | An instance or the load driver still runs | `./stop-instances.sh` first |

## 7. Polling or CDC - measured

2026-11-04, devcontainer (8 cores shared by PostgreSQL, the simulators, the instance and the driver), one instance,
`./run-load.sh` defaults (200 customers, 10 orders/s with 40/s at the open, 5 business days with trading sessions:
~1 670 orders), a fresh stack per mode, one run each. Every correctness check passed in all three.

| | CDC (the demo's default) | Polling, every 200 ms | Polling woken by NOTIFY |
|---|---|---|---|
| Placed to booked, p50 / p99 | **151 / 271 ms** | 297 / 1 573 ms | 312 / 1 228 ms |
| Placed to submitted, p99 | 146 ms | 1 501 ms | 742 ms |
| Filled to booked, p99 | 136 ms | 349 ms | 446 ms |
| SQL statements / their database time | 824 000 / 11.6 s | 784 000 / 11.5 s | **1 553 000 / 49.7 s** |
| Database CPU, median / p95 (5 s samples) | 7 / 44 % | 8 / 57 % | 9 / 107 % |
| Application CPU, median / p95 | 10 / 80 % | 10 / 110 % | 10 / 130 % |

- **CDC is the fastest and the cheapest here.** An order passes three processors (the router, the reports' inbox, the
  ledger); on polling each hop waits for its subscription's next poll, which is where the p99 goes.
- **NOTIFY wakes every subscription of the aggregate type on every insert.** Each wake-up is a poll whether or not
  that subscription has anything to do, so at this load it doubled the statements and quadrupled the database time
  without beating plain polling's median. It pays off where events are rare and a 200 ms poll would be wasted.
- At this low load the three are within the instance's capacity; a heavier profile (`--orders-per-second`,
  `--burst-orders-per-second`) would separate them further. One run each: treat differences under ~20 % as noise.
