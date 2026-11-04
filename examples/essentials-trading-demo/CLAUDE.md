# essentials-trading-demo

Spring Boot demo for the PostgreSQL EventStore, growing into a retail brokerage (`docs/EXPANSION_PLAN.md`): an
account ledger with snapshots, closing books and generation archival, orders, funding, settlement and reconciliation,
against stubs or - under the `simulators` profile - the exchange-simulator and payment-gateway. Not part of the release.

Packaged by **vertical slice**, following the same law as `postgresql-cqrs` — see that module's
`CLAUDE.md` and the essentials plugin's `rules/slice-design.md`. `REFACTORING_PLAN.md` records the
decisions taken when this module was converted from layered packages, and the open questions.

```bash
mvn verify -pl :essentials-trading-demo                 # unit + ITs (needs Docker)
mvn spring-boot:run -pl :essentials-trading-demo        # after `docker compose up -d`
```

## Bounded contexts

| BC | Aggregates | Slices |
|---|---|---|
| `market_data` | `Instrument` (+ in-memory `market_quotes`, stored `closing_prices`) | 3 command, 1 external system, 1 automation, 3 view |
| `identity` | `User` (+ `user_credentials` table) | 3 command, 1 view |
| `accounts` | `Account` (the ledger, stream `Accounts`) | 17 command, 1 automation, 7 view |
| `orders` | `Order` (stream `Orders`), `UnknownExchangeOrder` (operator cases) | 7 command, 1 external system, 1 automation, 4 view |
| `funding` | `BankAccountLink`, `Deposit`, `Withdrawal` | 4 command, 1 external system, 1 automation, 2 view |
| `reconciliation` | `ReconciliationCase` (operator cases; statements stored as reference data) | 3 command, 2 external system, 1 automation, 6 view |
| `settlement` | `TradeSettlement` (stream `TradeSettlements`) | 1 command, 1 external system, 1 automation, 2 view |

The original showcase context, `brokerage` (`TradingAccount`, trades, settlements, its bootstrap, load generator,
dashboard and admin page), was deleted in phase 4 (`docs/design/phase-4-settlement.md` Q-3): `accounts`, `orders` and
`settlement` replaced it, and generation archival moved to `accounts`. The Essentials admin console shows closing
books, snapshots, archives and lifecycle for every aggregate; customers have their own UI at `/app` (phase 7).

All on the **aggregate write style** (§R5) — `AggregateRoot` + `StatefulAggregateRepository`. Sanctioned
lane. Do **not** convert to `Decider`s.

`_demo_harness/` is not a slice — load generators (queue, ledger, order flow), demo users, EF-2 measurement processors,
and the operator page `/ops` (phase 8, `simulators` profile only): play a trading day at any wall-clock time (pause,
speed, skip to the open or close, advance business days through every close) and run background traffic. Log in as
`operator@demo.local` / `operator-demo-password`. `_`-prefixed, excluded from slice enumeration. Has its own `CLAUDE.md`.

`_customer_ui/` is not a slice either: the customer's browser UI (phase 7) at `/app` - Portfolio, Trade, Funding -
Thymeleaf shells whose scripts call the customer JSON API. Log in as `alice@demo.local` / `alice-demo-password`: her account
`ACC-ALICE-1` is opened and funded with 100,000 USD at startup (`_demo_harness/DemoAccountsSeeder`). Own `CLAUDE.md`.

## Gotchas

- **The session cookie is per instance**: `TRADING_DEMO_SESSION_<instance-id>` (or `_<port>`), not `JSESSIONID`
  (`application.yml`). Browsers share cookies across ports, so with one name two local instances logged each other out
  in the same browser. Anything that picks the session cookie by name must match the prefix (`BrokerageHttp`).

- **Event-type FQCNs changed in the slice refactor.** Essentials persists the concrete class name; no
  upcasting is provided. An existing demo database is unreadable — `docker compose down -v` first.
- **A policy annotation only takes effect if the aggregate is declared.** Each context has one
  `EssentialsAggregateDeclarations` bean (`accountsAggregates`, `ordersAggregates`, ...); the starter's registrar reads
  `@AggregateSnapshotPolicy` / `@AggregateClosingBooksPolicy` off the declared classes. Undeclared → the annotation is
  inert, the admin console shows no policy, and nothing errors. This replaced two hand-written `InitializingBean`s.
- **`Account` has two ids.** `AccountGenerationId` (`<AccountId>#<generation>`, convention on that type only) is the
  *stream* id; `AccountId` the logical id spanning generations. Reached through `Accounts.getAccountForMutation`, the
  ON_ACCESS closing-books trigger; `roll_account_books` deliberately is not, or one roll would become two.
- **Aggregate state fields are private**, including on the snapshotted `Account` —
  `EssentialsObjectMappers` sets `withFieldVisibility(ANY)` / `withGetterVisibility(NONE)`, so snapshots
  round-trip fine. The `protected` no-arg constructors are load-bearing: without them Jackson 3 would pick a
  public constructor as an implicit properties creator and half-populate a snapshot.
- **Start-up DDL goes through `config/TradingDemoSchema.ddl`** (handle or `JdbcTemplate`), which takes `PostgresqlUtil.acquireBootstrapLock` in the statement's own transaction. `CREATE ... IF NOT EXISTS` is not safe against itself: two instances starting together on an empty DB both see the table missing and the second fails on `pg_type_typname_nsp_index` (hit by `UnclearedDepositsProjection`). A plain `JdbcTemplate.execute` is autocommit - a transaction-scoped lock would be gone before the statement, hence the helper's own transaction. New DDL in a constructor → use the helper
- **Every projection is eventually consistent.** Tests must await them.
- **Every `@SpringBootTest` here carries `@DirtiesContext`.** Each class owns a static Postgres container, so a cached
  context outlives its database; on the next context switch Spring pauses it, and every event-processor subscription's
  lock release waits out a Hikari connection timeout. Three such classes turned a 30s suite into 25+ minutes.
- **`market_data.risk_approve_instrument` is the demo's worked example of a `UnitOfWorkMode.NONE` handler**
  (`orders`' `ExchangeOrderRouter` and `funding`'s `PaymentRouter` follow it). It blocks on a stubbed external risk service with no `UnitOfWork` — hence no pooled
  connection — and wraps its transactional tail in `usingUnitOfWork(...)`. Three constraints travel with the
  mode: the handler must be idempotent (the aggregate's risk methods no-op once a decision exists), the
  blocking call must finish well inside `essentials.durable-queues.message-handling-timeout`
  (`trading-demo.risk-approval.latency` is 500ms against a 30s default), and it cannot be a
  `ViewEventProcessor`, which rejects `NONE` outright. Read that slice's `CLAUDE.md` before copying the shape.
- **`EssentialsWebMvcConfigurer` + `EssentialTypesJacksonModule` are registered in
  `config/TradingDemoWebConfiguration`.** Neither is auto-configuration. Without the first, a typed
  `@PathVariable` is an HTTP 500; without the second, a semantic type in a request/response body has no
  serializer.
- **Command bus and handler registration are free.** `spring-boot-starter-postgresql` supplies
  `essentialsCommandBus` and `ReactiveHandlersBeanPostProcessor`; `@Service extends AnnotatedCommandHandler`
  is the whole wiring. No `@Transactional` on handlers — the bus owns the UnitOfWork.

## Login (phase 1)

Spring Security guards everything (`config/TradingDemoSecurityConfiguration`): `OPERATOR` for the admin API and
pages, the harness endpoints and the Essentials admin console; `CUSTOMER` or `OPERATOR` for `/api/accounts/**`,
`/api/market-data/**` and the customer UI (`/`, `/app/**`); open for health, login, the UI's static assets and
`/webhooks/**` (signed instead). Seeded users are in `application.yml`
(`trading-demo.identity.seed-users`, demo passwords) — `operator@demo.local` / `operator-demo-password`.

- **Tests calling HTTP must authenticate** — `restTemplate.withBasicAuth(...)` as the seeded operator. An
  unauthenticated `/api/**` call answers `401`; elsewhere it redirects to `/login`, which an HTTP client follows to a
  misleading `200`.
- **CSRF is required for a session-authenticated write to the customer API** (`/api/accounts/**`,
  `/api/market-data/**`, phase 7 design §5): the customer pages render the token in `<meta name="_csrf">` and send it
  back in that header; the load driver reads it from `/app` after each login. A request with HTTP Basic credentials
  needs none. The operator API (every other `/api/**`) is still exempt, because the Essentials admin console calls it
  on the operator's session without a token - a demo simplification, not a pattern.
- **The Essentials admin API's SPIs stay all-access**; the filter chain is what guards them.

## Running more than one instance

`./run-instance.sh 1` and `./run-instance.sh 2` — ports 8080/8081, instance ids `demo-1`/`demo-2`. This is the configuration the shard-owned engine exists
for; one instance exercises none of its ownership, rebalancing or fencing.

- **The queue load generator, when on, runs on every instance** (`trading-demo.queue-load.enabled`, off by default since
  the real flows run on the engine) by design — it is also the queue's consumer. Safe because each instance produces under its own ordered key prefix; see `_demo_harness/CLAUDE.md`.

- **A distinct `instance-id` is not optional.** The engine defaults it to `<hostname>:<pid>` since 0.60.1 (the bare
  hostname before - what this note was written against), wrong only where processes share both. They would register as *one* instance,
  and `acquireLease` accepts a unit whose `owner` already equals the asking instance. It used to do so
  *without bumping the fence*, so both processes owned every unit and delivered the same messages
  silently. It now always bumps, so each takeover fences the other process out: ownership churns
  between the two, in-flight messages are redelivered on every flip, and the `acknowledgement rejected`
  WARNs are the symptom. Safer, still wrong — give each process its own id.
- **Every instance handles the event processors' inboxes; the views stay on one.** An `EventProcessor`'s
  subscription is exclusive (fenced lock), but its inbox - where its `@MessageHandler`s run - defaults to
  `SingleGlobalConsumer`, a second lock, so with two instances every handler ran on whichever started first. The
  demo's six processors (`ExchangeOrderRouter`, `OrderLedgerProcessor`, `PaymentRouter`, the funding and settlement
  ledger processors, `InstrumentRiskApprovalProcessor`) extend `config/TradingDemoEventProcessor`, which uses
  `GlobalCompetingConsumers` where the queues keep key order across instances (the `compose` profile's shard-owned
  engine) and `SingleGlobalConsumer` otherwise (the tests, and the A/B on `PostgresqlDurableQueues`) - measured 6 194 /
  6 124 router and 10 679 / 10 509 ledger deliveries, 0 order violations (`_demo_harness/CLAUDE.md`, F-3 on two
  instances). **A test aid, not a pattern:** an application picks `PartitionedEventProcessor` (every instance, refuses to
  start without the capability) or `EventProcessor` in its code (`docs/event-processor-specializations.md`).
  `ViewEventProcessor`s (the projections) remain one instance cluster-wide, by design.
- **The status endpoint is cluster-wide; the statistics endpoint is per-instance.** Both instances
  report `ordered 64/64` because all 64 units have an owner *somewhere*, not because each holds 64.
  The console says so on each card now.
- **Ctrl-C is graceful, and that is worth knowing rather than assuming.** SIGINT reaches the forked
  application JVM through the foreground process group, so the shutdown hook runs: every lease
  released and the instance deregistered. Measured — 408 units held, 0 owned and no membership rows
  seconds later. Maven then prints `Failed to execute goal ... Process terminated`, which is the
  plugin reporting a child killed by a signal, not the application failing.
- **`run-instance.sh` bypasses Docker's port proxy for PostgreSQL where it can.** When the PostgreSQL container's IP
  answers (a Linux host, the devcontainer), it starts the compose services itself and runs the application with
  `--spring.docker.compose.enabled=false` and the datasource at that IP. Through `localhost:5432` every statement
  crosses `docker-proxy`, which cost about half the burst latency (`_demo_harness/CLAUDE.md`, "PostgreSQL through the
  port proxy"). On Docker Desktop (macOS, Windows) the IP does not answer and it falls back to `localhost:5432`, the
  same path as `PG_DIRECT=0`. A run of the application started any other way (an IDE, plain `spring-boot:run`) still
  goes through the proxy.
- **Instances may be stopped in any order, and that took a setting.** Spring Boot's Docker Compose
  support defaults to `start-and-stop`, so whichever instance started PostgreSQL took it down with
  it — every survivor then logged connection refusals forever, because each layer is built to ride
  out a transient outage and none can tell this one is permanent. `application-compose.yml` sets
  `spring.docker.compose.lifecycle-management: start-only`; the database outlives the demo and
  `docker compose down` stops it.

## Whole demo in Docker (`compose.app.yml`)

`./run-app-stack.sh [1|2] [observability]` builds the application image (`app.Dockerfile`, classes + runtime and
provided jars copied from the module, compiled against `~/.m2`'s Essentials jars) and starts it beside PostgreSQL and
the simulators: `app-1` on `localhost:8080`, `app-2` (compose profile `app2`) on `8081`. `./run-app-stack.sh down`
stops the instances. The way to run the demo on Docker Desktop, where `run-instance.sh` can only reach PostgreSQL
through a published port and so pays the relay's cost on every statement.

- **Inside, everything is by service name** (`postgresql`, `exchange`, `payment-gateway`, `collector`, `loki`), set as
  environment in `compose.app.yml`; Spring's compose support is off. `logback-spring.xml`'s Loki URL reads `LOKI_URL`.
- **Webhooks reach `app-1` by a network alias.** The simulators post to `http://host.docker.internal:8080`; the
  override drops their host mapping (`extra_hosts: !reset []`, compose 2.24+) and gives `app-1` that name.
- **`provided` jars must be copied too** - Essentials declares slf4j and its other integrations provided; a
  runtime-scope copy failed at start with `ClassNotFoundException: org.slf4j.LoggerFactory`.
- Verified 2026-11-03: 61 connections from the app container's own address, a 2-day load run with every order, fill
  and payment check passing (booked p99 ~400 ms), and two instances sharing the shard units. At the phase 6 load (one
  instance, 10 business days) it matches the host on the direct connection: booked p99 4.7 / 3.7 / 3.2 / 3.0 s on days
  2 / 5 / 8 / 10 against 4.5 / 3.3 / 3.1 / 2.9 s, every check passing (`_demo_harness/CLAUDE.md`, "Inbox consumers at
  the opens", run K).

## Load driver

`./run-load.sh` (phase 6, `docs/design/phase-6-realistic-load.md`) drives running instances over HTTP as simulated
customers, from a JVM of its own; details in `_demo_harness/CLAUDE.md`, "The load driver". Needs the `simulators`
profile - it follows the exchange's business days. `./run-faults.sh <none|F-1|F-2|F-3|F-4|F-5>` runs one phase 6 fault on a
database (`trading_faults`) and simulators of its own, with one instance it starts and stops itself (two with
`--instances=2`).

- **`./reset-stack.sh` before every run.** Neither PostgreSQL nor the exchange-simulator prunes: one hour-long run left
  ~6 GB in the database and ~8 GB in the exchange's volume, and a full disk crash-loops PostgreSQL mid-run. The script
  refuses while an instance or the driver runs, removes the stack's volumes (PostgreSQL's anonymous one included) and
  warns under 15 GB free. Under `compose` the driver's customers (`@load.local`) are stored `{noop}`
  (`trading-demo.identity.unhashed-password-email-suffix`), so registering 10 000 again takes seconds, not minutes.

## Simulators profile

`run-instance.sh N simulators` (or profile `compose,simulators`): the exchange-simulator (`localhost:18080`) and the
payment-gateway (`localhost:18081`) from `compose.yml`'s `simulators` compose profile; their configuration in
`simulators/`. The demo is being expanded to trade against them — `docs/EXPANSION_PLAN.md`, and what the simulators
must add in `docs/SIMULATOR_REQUIREMENTS.md`. Shared receiving mechanics in `integration/` (its own `CLAUDE.md`).

- **The images are built locally** (`exchange-simulator:dev`, `payment-gateway:dev`) from the simulators' own
  repository; nothing here builds or pulls them. Without them the profile fails at `docker compose up`, and the
  real-simulator ITs skip.
- **`spring.docker.compose.profiles.active` is a list, and Spring profiles replace it rather than merge it.** With
  `observability,simulators` the last profile's YAML wins and one stack never starts. `run-instance.sh` passes the
  union on the command line; an IDE run configuration combining both must do the same.
- **The exchange lists the demo's instruments itself** (`simulators/exchange.yaml`, D18): the ten seed instruments,
  synthetic prices, and the liquidity provider quoting them. Trading sessions 09:30-16:00 New York on both markets
  (D32): outside them the exchange rejects orders `MARKET_CLOSED` and pulls its quotes; the order ticket reads
  `GET /api/market-data/sessions` and disables placing. ITs run without the `sessions` block
  (`SimulatorImages.exchangeAsTheDemoRunsIt`; `exchangeWithTradingSessions` keeps it). The app's quotes are the
  exchange's own (`market_data/CLAUDE.md`).
- **Accounts roll their books at the exchange's close** (EF-1, D32), not at midnight: `ExchangeClosingBooksCalendar`
  keeps `business_day_closed` in memory (loaded at start, updated after commit, polled every 1 s for other instances).
- **Under this profile orders go to the exchange-simulator**, not the stub (`orders/CLAUDE.md`, "Exchange-simulator
  adapter").
- **Fenced locks are spread over the instances** (`spread-locks: true`, `compose` profile): a second instance gets its
  share of the ~35 exclusive processors' locks one at a time, every 10 s; measured with two instances started one
  after the other: 35/0, then 17/18 after 3 minutes, settled at 13/22 (each lock's preferred instance) after ~4.5.
  Each move pauses that processor a few seconds. `/ops` shows locks per instance.
- **`stop-instances.sh` stops what the run scripts started, keeping the data**: load driver, then every
  `TradingDemoApplication` JVM (SIGTERM, SIGKILL after 30 s), then `docker compose down` over every profile (no `-v`);
  `--apps` leaves the containers. `reset-stack.sh` is the one that deletes. User guide: `docs/DEMO_GUIDE.md`.
- **Scripts run on macOS too**: POSIX `df` in `reset-stack.sh`; `timeout` (absent on macOS) only guards the direct
  container-IP probe, which Docker Desktop could not reach anyway.
- **Reset the demo's database and the exchange together.** The exchange keeps its state in the `exchange-data`
  volume, and every consumer's catch-up reader reads the exchange's event log from the start on a fresh database. A
  fresh database against an exchange that remembers turns the old run's events into reports for orders this database
  never had; acceptances and cancellations for an unknown order end as dead letters in `Inbox:Orders:ExchangeReports`
  (seen: 100 after one earlier run; `EarlierRunCheck` WARNs once when it sees it). All three live in named volumes -
  `postgres-data`, `exchange-data`, `gateway-data` - so recreating containers keeps them together and `./reset-stack.sh`
  (`down -v`) removes them together. PostgreSQL's used to be anonymous (the image's `VOLUME`): a recreated container got
  an empty database next to simulators that remembered, which is how a run ended with `BG-local` fills for orders it never had.
- **The Finnhub API key is never committed.** It lives in `simulators/secrets/finnhub.env` (gitignored; template
  `finnhub.env.example`), which both compose files load as an optional `env_file` for the exchange, so a run without
  it still starts - with no live price feed (D15, D16).
- **The gateway's clock follows the exchange's** (`clock.mode: follow`, SR-27), so it refuses clock changes of its
  own: move the exchange's clock, and the gateway follows within its poll interval.
- **One webhook secret for both simulators**, `simulators/secrets/webhook-secret.txt`, a demo value. Docker secret
  for the containers, `trading-demo.simulators.webhook-secret-file` for the app.
- **The exchange has the 2030 US market holidays** (`clearing.holidays`, SR-35) and the gateway follows its calendar
  (`calendar.mode: follow`): no close, no statement, markets shut on a holiday, and T+1, return windows and `DAY` orders
  span it. The gateway reads the calendar at startup - restart it after changing the holidays.
- **Business dates come from the simulators' events**, never the app's clock: they run a virtual clock, a simulated
  day per real minute (`simulators/*.yaml`, `clock.mode: system` turns it off).

## Observability profile

`run-instance.sh N observability` (or profile `compose,observability`): collector, Prometheus, Tempo, Loki,
Grafana from `compose.yml`'s `observability` compose profile; config and dashboards in `observability/`.

- **Two compose files, one config dir.** The classpath copy runs from `target/classes`, so it mounts
  `../../observability/...`; the module-root copy mounts `./observability/...`. Keep both in step. Config
  stays out of `src/main/resources` so it is not packaged.
- **`spring.docker.compose.start.skip: never` is load-bearing.** Boot skips `docker compose up` when any
  service of the project is running — PostgreSQL almost always is — so the profiled services never start.
- **Export is off in `application.yml`** (`management.otlp.metrics.export.enabled`,
  `management.tracing.export.enabled`) and on only in `application-observability.yml`; otherwise every test
  and plain run spends each export step failing to reach a collector that is not there.
- **`management.tracing.enabled: true` is an Essentials switch, not a Boot one, and must stay.** The starters
  register their tracing interceptors (DurableQueues, event store), the DurableQueues operation metrics and the
  subscriber global-order gauges only when it is `true`. Without it Tempo holds nothing but HTTP-request traces,
  which the background load never makes, and no log line carries a trace id - so traces and logs look empty.
- **The Logs/Traces/Metrics dashboard needs a trace id typed into its `traceId` textbox** - copy one from Tempo
  search. A `custom` variable with no options, which it was, offers an empty dropdown and nothing can be entered.
- **loki4j 2.x labels are one per line.** The comma-separated form 1.x accepted fails the whole logging
  configuration at start-up (`Unable to split ... to key-value pairs`).
- **Every store the demo writes to is size- or time-capped, because the load generators never stop.** Prometheus
  `retention.time=2d` + `retention.size=1GB` (uncapped it reached 4 GB+), Tempo `block_retention: 24h`, Postgres
  `max_slot_wal_keep_size=2GB`. The last one is the CDC slot: unread, it retained WAL until the disk filled and Postgres
  could not restart. Past the cap Postgres invalidates the slot instead and the database survives; subscriptions fall
  back to polling (`CdcMode.AUTO`), and CDC stays down until the slot is dropped and recreated — by hand, or by
  `CdcEffectivenessMonitor`'s opt-in recreate-on-stuck. Keep all of these in both compose copies.
- **Dashboards query OTLP names** (`..._milliseconds_bucket`, `..._total`), not the Prometheus registry's
  `_seconds`. Queue gauges are cluster-wide and reported by every instance: `max by (queue)`, never `sum`.
