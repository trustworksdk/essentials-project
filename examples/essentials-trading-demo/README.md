# Essentials Trading Demo

A Spring Boot application built on Essentials, growing into a realistic retail brokerage (the plan:
[docs/EXPANSION_PLAN.md](docs/EXPANSION_PLAN.md)). It has two purposes, weighed equally: **showcase Essentials** in a
realistic domain, and **push it under realistic load** to drive new features, find performance improvements and flush
out bugs. It is not part of the release.

Customers open accounts, link a bank account, deposit and withdraw, and buy and sell; the brokerage holds funds,
books fills, settles trades, values accounts at the day's closing prices and reconciles the bank's statements. It runs
against in-process stubs, or - under the `simulators` profile - against an exchange simulator and a payment gateway
simulator.

## What It Shows Of Essentials

- **Event-sourced aggregates** on the aggregate write style (`AggregateRoot` behind a `StatefulAggregateRepository`):
  every context.
- **Snapshots, closing books and generation archival** on the account ledger (`accounts.Account`): a generation per
  business day, its open state carried into the next, sealed generations archived.
- **Event processors and view processors**, **Inbox** for every external system's events (signed webhooks, deduplicated,
  with an event-log catch-up reader), and `UnitOfWorkMode.NONE` for every blocking external call (risk approval,
  exchange submission, payment initiation).
- **The shard-owned queue engine** as the application's `DurableQueues`, across several instances, plus its own load
  generator; **CDC** subscriptions; **event causation** recorded and indexed.
- **The Essentials admin console and admin API**: aggregates and their lifecycle, snapshots, closing books, archives,
  queues, subscriptions, causation.

## How The Module Is Laid Out

Packaged by **vertical slice** under `dk.trustworks.essentials.examples.trading` - see the module's
[CLAUDE.md](CLAUDE.md) and each context's own:

| Package | What it holds |
|---|---|
| `identity/` | `User`, login, roles `CUSTOMER` and `OPERATOR` |
| `accounts/` | `Account`: the ledger - cash buckets, positions, holds, buying power, withdrawable cash; deposit clearing; valuation |
| `market_data/` | `Instrument` (the brokerage's decision to offer one), live quotes, closing prices |
| `orders/` | `Order`: placement with its hold, the exchange's answers, fills, cancels, expiry |
| `funding/` | `BankAccountLink`, `Deposit`, `Withdrawal`: payments through the gateway |
| `settlement/` | `TradeSettlement`: each execution's settlement, booked on the ledger |
| `reconciliation/` | the bank's statements against our payments |
| `integration/` | shared receiving mechanics for the simulators, the business calendar, the generated simulator clients |
| `config/` | app-level wiring only |
| `_demo_harness/` | deliberately **not** a slice - load generators, demo users, measurement processors |

Design notes per phase: [docs/design/](docs/design/). What the simulators must provide:
[docs/SIMULATOR_REQUIREMENTS.md](docs/SIMULATOR_REQUIREMENTS.md).

## Run Locally

From the repo root, PostgreSQL started by Spring Boot's Docker Compose support:

```bash
./examples/essentials-trading-demo/run-instance.sh 1                 # port 8080, instance demo-1
./examples/essentials-trading-demo/run-instance.sh 2                 # port 8081, instance demo-2 (optional)
./examples/essentials-trading-demo/run-instance.sh 1 simulators      # with the exchange and payment gateway simulators
./examples/essentials-trading-demo/run-instance.sh 1 observability   # with Grafana, Prometheus, Tempo, Loki
```

or `mvn -q -pl examples/essentials-trading-demo -Dspring-boot.run.profiles=compose spring-boot:run`. Log in as
`operator@demo.local` / `operator-demo-password` (seeded; `alice@demo.local` / `alice-demo-password` is a customer).
The Essentials admin console is at [http://localhost:8080/essentials/admin](http://localhost:8080/essentials/admin).

A demo database written by an older version may not be readable: event classes are persisted by name and the demo
carries no upcasters. `docker compose -f examples/essentials-trading-demo/compose.yml down -v` starts over.

### With the simulators

The `simulators` profile starts `exchange-simulator:dev` and `payment-gateway:dev` from
[compose.yml](compose.yml), configured by [simulators/](simulators/). The images are built from the simulators' own
repository; nothing here builds them.

- **Reset the demo's database and the exchange together** (`down -v`, or `POST /test/reset` on the exchange): every
  consumer reads the simulators' event logs from the start on a fresh database.
- **Live prices** need a Finnhub key in `simulators/secrets/finnhub.env` (copy `finnhub.env.example`; gitignored),
  plus `EXCHANGE__PRICE_SOURCES__DEFAULT__KIND=finnhub` there. Without it prices are synthetic.
- The simulators run a virtual clock, a business day per real minute; business dates always come from them.

## Metrics, Traces And Logs In Grafana

Add the `observability` profile to get the same stack as `essentials-spring-examples` — an OpenTelemetry
collector, Prometheus, Tempo, Loki and Grafana — with dashboards for this demo:

```
./examples/essentials-trading-demo/run-instance.sh 1 observability
./examples/essentials-trading-demo/run-instance.sh 2 observability   # optional second instance
```

or `-Dspring-boot.run.profiles=compose,observability` directly. Then open Grafana at
[http://localhost:3000](http://localhost:3000) (no login) and Prometheus at [http://localhost:9090](http://localhost:9090).

| Dashboard | Shows |
|---|---|
| Shard-owned queues | backlog per queue and lane, dead letters, **blocked keys** and **parked** messages right now, unowned shards and live instances, enqueue/delivery rates and latency, failures/retries, work per instance, ownership changes |
| Event store and aggregates | appends and stream reads per aggregate type, subscription handling, snapshot load/save |
| Commands and message handlers | command-bus latency per command, handler invocations, the DurableQueues operations behind Inbox and the projections |
| Logs, Traces, Metrics | HTTP latency, a trace, and the log lines that carry its trace id |

How it is wired:

- The observability services are behind a compose profile, so a run **without** the Spring profile still
  starts PostgreSQL only. Their configuration and the dashboards live in [observability/](observability/).
- Metrics and traces are pushed over OTLP to the collector (`localhost:4318`), which remote-writes metrics
  into Prometheus and forwards traces to Tempo; logs are pushed to Loki by `logback-spring.xml`. Every
  series and log line is labelled with the instance id (`demo-1`, `demo-2`).
- The shard-owned queue's depth and health gauges are turned on in
  [application-observability.yml](src/main/resources/application-observability.yml); they are off by default
  because each refresh is a query.
- Traces start at HTTP requests — the admin API, the console and the load-generator endpoints. The
  generators' background work does not produce spans.
- The containers use the same host ports as `essentials-spring-examples`' stack, so only one of the two can
  run at a time. `docker compose -f examples/essentials-trading-demo/compose.yml --profile observability down`
  stops them.

## Useful Endpoints

Every slice's endpoint is in its context's `CLAUDE.md`. The ones to start with:

| | |
|---|---|
| `POST /api/accounts`, `GET /api/accounts/{accountId}` | open an account; its balances, buying power, withdrawable cash |
| `GET /api/accounts/{accountId}/positions`, `/activity`, `/valuation` | positions, statement lines, value at the latest closing prices |
| `PUT /api/accounts/{accountId}/bank-account`, `POST .../deposits`, `POST .../withdrawals`, `GET .../payments` | funding |
| `POST /api/accounts/{accountId}/orders`, `GET .../orders` | orders |
| `GET /api/market-data/quotes` | live quotes |
| `GET /api/admin/settlements`, `/exceptions` | settlements, and the ones the exchange abandoned |
| `GET /api/admin/reconciliation/bank-statements` | bank statements and their breaks |
| `POST /api/admin/order-flow/start`, `GET /api/admin/order-flow` | the order-flow load scenario |
| `POST /api/admin/ledger-load/start` | the ledger load scenario |
| `/api/admin/queue-load` | the shard-owned queue load generator |

## Tests

```bash
mvn verify -pl :essentials-trading-demo       # unit + integration (needs Docker)
```

The integration tests against the real simulators skip themselves when the images are not built locally - a skip
there is not a pass.
