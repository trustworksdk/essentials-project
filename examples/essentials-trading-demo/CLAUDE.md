# essentials-trading-demo

Spring Boot demo for the PostgreSQL EventStore: snapshots, closing books, generation archival, and a
price-path benchmark. Not part of the release.

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
| `brokerage` | `TradingAccount`, `Trade`, `Settlement` | 19 command, 6 view |
| `market_data` | `Instrument`, `InstrumentPrice` | 5 command, 1 automation, 2 view |

Both on the **aggregate write style** (§R5) — `AggregateRoot` + `StatefulAggregateRepository`. Sanctioned
lane. Do **not** convert to `Decider`s.

`_demo_harness/` is not a slice — load generator, bootstrap runner, dashboard, benchmark price store.
`_`-prefixed, excluded from slice enumeration. Has its own `CLAUDE.md`.

## Gotchas

- **Event-type FQCNs changed in the slice refactor.** Essentials persists the concrete class name; no
  upcasting is provided. An existing demo database is unreadable — `docker compose down -v` first.
- **A policy annotation only takes effect if the aggregate is declared.** Each context has one
  `EssentialsAggregateDeclarations` bean (`brokerageAggregates`, `marketDataAggregates`); the starter's registrar reads
  `@AggregateSnapshotPolicy` / `@AggregateClosingBooksPolicy` off the declared classes. Undeclared → the annotation is
  inert, the admin console shows no policy, and nothing errors. This replaced two hand-written `InitializingBean`s.
- **`BrokerageConfiguration` sets an explicit `setStreamIdGenerator(...)` on purpose.** `ClosingBooksSetup`'s default
  would concatenate `#` itself and produce the same string, but the convention lives on
  `TradingAccountGenerationId` so the projection that parses it back cannot drift from the writer.
- **`TradingAccount` has two ids.** `TradingAccountGenerationId` is the *stream* id and the aggregate id;
  `TradingAccountId` is the logical business id spanning generations. Reached through
  `ClosingBooksLogicalAggregateRepository`, not a plain `StatefulAggregateRepository`.
- **Stream-id convention `<logicalId>#<generation>` lives on `TradingAccountGenerationId`** (`of(id, gen)` /
  `generation()`). It used to be written in the coordinator lambda and re-parsed in the projection with
  nothing tying the two together.
- **`TradingAccounts.getAccountForMutation` is the ON_ACCESS closing-books trigger.** Every mutating account
  command goes through it; `close_books` and `close_books_and_open_next_period` deliberately do not, or one
  requested rollover would become two.
- **`TradingAccountClosingBooksPolicy` holds one immutable `ClosingBooksSettings` behind a lock.** Use
  `update(...)` or `withTemporarySettings(...)` — never reintroduce per-field setters. Four independent
  mutators are what let the benchmark scenario silently revert an admin change.
- **`InstrumentPrice.latestPrice()` is the only public accessor on any aggregate here.** Only
  `market_data.views.latest_price` calls it, and only because the bootstrap's idempotency probe needs a
  strongly-consistent answer. Everything else projects.
- **Aggregate state fields are private**, including on the snapshotted `TradingAccount` —
  `EssentialsObjectMappers` sets `withFieldVisibility(ANY)` / `withGetterVisibility(NONE)`, so snapshots
  round-trip fine. The `protected` no-arg constructors are load-bearing: without them Jackson 3 would pick a
  public constructor as an implicit properties creator and half-populate a snapshot.
- **`brokerage.trade_valuation` projects `market_data`'s price events** into its own table rather than
  calling that context. Cross-BC `events/` import is legal; injecting its write side was the §R4 violation
  this replaced.
- **Two projections are eventually consistent** (`account_statement`, `trade_settlement_status`). Tests must
  await them. `trade_valuation` likewise.
- **`market_data.risk_approve_instrument` is the only `UnitOfWorkMode.NONE` handler here**, and the demo's
  worked example of one. It blocks on a stubbed external risk service with no `UnitOfWork` — hence no pooled
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

## Running more than one instance

`./run-instance.sh 1` and `./run-instance.sh 2` — ports 8080/8081, instance ids `demo-1`/`demo-2`,
trading load generator on the first only. This is the configuration the shard-owned engine exists
for; one instance exercises none of its ownership, rebalancing or fencing.

- **Two generators, two prefixes, and the script gates only one.** `--trading-demo.load.enabled`
  is `TradingLoadGeneratorManager`; the queue load generator is `trading-demo.queue-load.enabled`
  and runs on **every** instance by design — it is also the queue's consumer, so disabling it on
  instance 2 would take that instance out of the exercise entirely. It is safe to run everywhere
  because each instance produces under its own ordered key prefix; see `_demo_harness/CLAUDE.md`.

- **A distinct `instance-id` is not optional, and getting it wrong is silent.** The engine defaults
  it to the hostname — right on a container platform, wrong for two processes on one machine. They
  would register as *one* instance, and since `acquireLease` matches when `owner` already equals the
  asking instance *without bumping the fence*, both processes would own every unit and deliver the
  same messages. Per-key ordering is gone at that point and nothing reports it.
- **A second instance looks idle, and is not.** Measured with two: `trading-events` splits 34 units
  each, nothing unowned. But the four projection queues are consumed by `demo-1` alone, because
  `EventProcessor`/`ViewEventProcessor` take *exclusive* subscriptions behind fenced locks — one
  instance runs each projection cluster-wide, by design.
- **The status endpoint is cluster-wide; the statistics endpoint is per-instance.** Both instances
  report `ordered 64/64` because all 64 units have an owner *somewhere*, not because each holds 64.
  The console says so on each card now.
- **Ctrl-C is graceful, and that is worth knowing rather than assuming.** SIGINT reaches the forked
  application JVM through the foreground process group, so the shutdown hook runs: every lease
  released and the instance deregistered. Measured — 408 units held, 0 owned and no membership rows
  seconds later. Maven then prints `Failed to execute goal ... Process terminated`, which is the
  plugin reporting a child killed by a signal, not the application failing.
- **Instances may be stopped in any order, and that took a setting.** Spring Boot's Docker Compose
  support defaults to `start-and-stop`, so whichever instance started PostgreSQL took it down with
  it — every survivor then logged connection refusals forever, because each layer is built to ride
  out a transient outage and none can tell this one is permanent. `application-compose.yml` sets
  `spring.docker.compose.lifecycle-management: start-only`; the database outlives the demo and
  `docker compose down` stops it.

## Admin UI

`src/main/resources/static/admin/index.html`, vanilla JS, no build step. Its select values are enum
constants (`END_OF_MONTH`), converted from the dashboard's hyphenated display form on load. Closing-books
settings are one atomic `POST /api/admin/trading-accounts/closing-books` with a JSON body; null field means
unchanged.
