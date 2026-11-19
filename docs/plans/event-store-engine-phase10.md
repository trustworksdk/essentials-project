# Event store redesign, phase 10: starter, admin UI, documentation and the trading demo

**Status:** decided 2026-11-18 (§11). Built on `feature/event-store-engine-phase9` (from `a0560df3c`).
When phase 10 is done, this file and its siblings (phases 2-9) move to `docs/archive/` or are folded into the
redesign's record.
**Scope:** phase 10 of `docs/plans/event-store-subscription-redesign.md` (§18, "Phases 1 onward"): a Spring Boot starter
for the new engine, the admin UI page for its subscriptions, the reference and LLM documentation, the release notes, and
the trading demo running on the new engine.
**Gate (plan §18):** the trading demo runs on the new engine at or above its baseline (§8). Every phase-2 to phase-9 IT
keeps passing.

Section references (§n) are to the plan. "Phase-8 §n" and similar refer to `docs/plans/event-store-engine-phase8.md`
and its siblings. Where this draft proposes something the plan does not say, it is marked **Proposed**.

The existing engine is not changed (decided 2026-11-15). Phase 10 adds new modules and pages; the touches to existing
modules are listed in §10, and each one needs confirmation.

---

## 1. What exists, and what phase 10 adds

| Piece | State on `a0560df3c` | Phase 10 |
|---|---|---|
| Engine `postgresql-event-log` | Built (phases 2-7), plain Java, raw pgjdbc | No change expected |
| Processor `postgresql-event-log-processor` | `ViewProjection`, `EventReactor`, `EventLogEventStore` adapter, page sources, `EventLogCommits`, admin SPI `EventLogSubscriptionsApi` (phase 8) | Annotated base classes `EventLogViewProcessor` and `EventLogReactor` (§3.5) |
| Migration `postgresql-event-log-migration` | Copy, verify, import, CLI (phase 9) | Documentation only |
| Admin REST | `EssentialsAdminApiSpec` rows and `EventLogSubscriptionsController`, conditional on an `EventLogSubscriptionsApi` bean (8b-C, release notes §2.19) | The starter declares the bean (§3.4) |
| Admin UI | No page for the event log | A view beside the shard-owned queue view (§4) |
| Spring Boot starter | None; the performance lab builds the engine by hand (`EventLogBaselineScenario`) | `spring-boot-starter-postgresql-event-log` (§3) |
| `LLM/`, `docs/` | Nothing mentions the event log | §5 |
| Trading demo | Old engine, CDC, 25 processors on the old API | Runs on the new engine (§6), measured against the old (§8) |

---

## 2. Release status

The three engine modules are in the root reactor and in no exclusion list, and `admin-api-spec` already depends on the
processor module, so they publish with the next release as things stand. **Proposed:** they ship in 0.61.0 marked
**preview**: the API may change in a minor release, every public type says so in its Javadoc, and the release notes and
the LLM doc lead with it. The old engine stays the default and fully supported. (Open question 4.)

---

## 3. The starter

### 3.1 Module

**Proposed:** `components/spring-boot-starter-postgresql-event-log`, package
`dk.trustworks.essentials.components.boot.autoconfigure.eventlog`, following the shard-owned queue starter
(`spring-boot-starter-postgresql-queue-shard-owned`):

- Off by default: `@ConditionalOnProperty(prefix = "essentials.event-log", name = "enabled", havingValue = "true")`, no
  `matchIfMissing`.
- `@AutoConfiguration(after = DataSourceAutoConfiguration.class, beforeName = "…postgresql.EssentialsComponentsConfiguration")`,
  ordered by name so there is no compile dependency on the base starter.
- Depends on `spring-boot-starter-postgresql` (Jdbi, the unit-of-work factory, `EssentialsSecurityProvider`, the
  `LifecycleManager`) and on the processor module; the admin-api starter is `provided`, as for the queue starter
  (otherwise it drags in the old event-store starter).
- Properties `EssentialsEventLogProperties` (`essentials.event-log.*`) mapping 1:1 onto `EventLogSettings` and
  `ErrorSettings`, with Spring's `Duration` binding. `-proc:full` keeps the metadata file (root `CLAUDE.md`).
- `META-INF/spring/…AutoConfiguration.imports`; `MissingDependencyFailureAnalyzer` registered as the queue starter does.

### 3.2 Beans

| Bean | Condition | Built from |
|---|---|---|
| `EventLog` (`destroyMethod = "stop"`) | `@ConditionalOnMissingBean` | `EventLog.builder().setDataSource(dataSource).setSettings(properties.toSettings()).setSchemaOwnership(properties.getSchema().getMode().schemaOwnership())` (the base starter's schema mode, `EventLogBuilder.java:53`) |
| `EventLogSchemaContributor` | `initialize-schema`, `matchIfMissing = true` | As the queue starter's contributor bean |
| `EventLogEventStore` as `ConfigurableEventStore` (`@Bean("essentialsEventStore")`) | `@ConditionalOnMissingBean(EventStore.class)` | `EventLog`, the `EventStoreUnitOfWorkFactory` (Spring-transaction-aware, as the old starter builds it), `JSONEventSerializer` (`EssentialsJSONEventSerializers`), `PersistableEventMapper`, optional `TenantSerializer`, enrichers, the shared `EventStoreEventBus` |
| `EventStoreUnitOfWorkFactory`, `EventStoreEventBus`, `JSONEventSerializer` | `@ConditionalOnMissingBean` | Same construction as the old starter's (so `SnapshotConfiguration` and `ClosingBooksConfiguration` keep working, §3.3) |
| `UnitOfWorkPageTransactions` / `SpringPageTransactions` as `PageTransactionSource` | `@ConditionalOnMissingBean`; Spring source when a `PlatformTransactionManager` exists | Processor module |
| `EventLogSubscriptionsApi` | `@ConditionalOnBean(EssentialsSecurityProvider.class)`, `@ConditionalOnMissingBean`, in a separate admin auto-configuration ordered `beforeName` `EssentialsAdminApiAutoConfiguration` (as `ShardOwnedQueuesAdminApiAutoConfiguration` is) | `DefaultEventLogSubscriptionsApi` |
| Processor lifecycle | — | `ViewProjection` and `EventReactor` beans built by the application; the starter starts every one in the context after the `EventLog`, stops them before it (a `SmartLifecycle` adapter, **Proposed**), and not in schema mode `EMIT` |

One `EventLog` per `DataSource`, so the notifier, listener and lease keeper stay per `EventLog` (phase-8 open question 10
settled: nothing in phase 10 needs several).

~~Proposed: builder helpers `EventLogProcessors.viewProjection(name, type)` / `.eventReactor(...)`.~~ **Dropped** (unit
10-A2): superseded by the starter's `EventLogProcessorDependencies` bean (event log, serializer, page source), which the
§3.5 base classes take and a lambda-builder bean can read its three values from.

### 3.3 Living beside the old event-store starter

An application that already has `spring-boot-starter-postgresql-event-store` on the class path gets
`EventStoreConfiguration`, whose beans are `@ConditionalOnMissingBean`. The adapter bean displaces the old `EventStore`,
but nothing displaces `EventStoreSubscriptionManager` (`EventStoreConfiguration.java:194`, unconditional apart from
`@ConditionalOnMissingBean`), `DurableSubscriptionRepository`, the gap handler or, with `essentials.eventstore.cdc.enabled`,
the CDC beans. They would be built on top of the adapter, whose polling methods throw.

`SnapshotConfiguration` and `ClosingBooksConfiguration` (same starter) are wanted: they need only a
`ConfigurableEventStore`, the unit-of-work factory and the serializer, all of which the adapter side provides.

Options (open question 1):

- **(a) Proposed:** the new starter registers an `AutoConfigurationImportFilter` (Spring Boot's own mechanism, in its
  `META-INF/spring.factories`) that drops `EventStoreConfiguration` from the candidates while `essentials.event-log.enabled`
  is true, and keeps `SnapshotConfiguration`, `ClosingBooksConfiguration` and the aggregate admin configurations. No
  existing module changes. Old processors (`EventProcessor`, `ViewEventProcessor`) then fail to start for want of their
  `*Dependencies` beans, which is the intended message: they must be ported. The failure analyzer names it.
- (b) A switch on the old starter, `essentials.eventstore.engine=classic|event-log`, guarding `EventStoreConfiguration`.
  Explicit, but it changes an existing module's main code.
- (c) The new starter is not meant to share a class path with the old one: an application swaps the dependency and
  declares the snapshot and closing-books beans itself. No coupling, but every adopter rewrites that wiring.

### 3.5 Annotated base classes (processor module)

Decided 2026-11-18 (§11, question 2): existing `EventProcessor` and `ViewEventProcessor` code does not run on the new
engine unchanged (D3 stands: no `EventStoreSubscriptionManager` adapter), but porting one is a superclass swap. Two
abstract classes in `dk.trustworks.essentials.components.eventlog.processor`, each a thin shell over the phase-8
processor it names, so every engine rule (I14, leases, shrink and park, page transactions) is the processor's:

| Class | Built on | Shape |
|---|---|---|
| `EventLogViewProcessor` | `ViewProjection` (`CONTIGUOUS`, exclusive, page per transaction) | `@MessageHandler` methods taking the payload, optionally a second `DeliveredEvent` parameter; the page's unit of work is bound, so `unitOfWorkFactory.getRequiredUnitOfWork()` and the `Handle` work in a handler body as they do on the old `ViewEventProcessor` (`UnitOfWorkPageTransactions` binds it) |
| `EventLogReactor` | `EventReactor` (`FRESH`, exclusive, periodic, at-least-once) | Same handler shape; no transaction around the handler |

- Constructor: processor name, the aggregate types (a list: one subscription per type, all with the same handlers,
  started and stopped together), and an `EventLogProcessorDependencies` bean from the starter (event log, serializer,
  page source). Overridable hooks mirror the builders: `consistency()`, `startFrom()`, `errorSettings()`, `tenant()`.
- Dispatch through foundation's `PatternMatchingMessageHandler` (the class the old processors use, in `foundation`, not
  in an old subscription package, so `DependencyRulesTest` allows it), built once per class. A payload with no handler is
  counted as handled; with an event-type filter derived from the handlers' parameter types the engine skips it in SQL
  (**Proposed**, phase-5 filter; off when a handler takes `Object`).
- Not carried over: `@MessageHandler` interceptors, the old built-in permanent-error list, inbox redelivery, Reactor,
  `FencedLock`. A failure is the engine's: retried, shrunk, parked (`ParkEventException` parks at once).
- `Lifecycle`: started and stopped by the starter (§3.2), or by hand.
- Tests: an IT per class through the starter's dependencies (handler dispatch by type, the second parameter, multi-type,
  a handler failure parks, the unit of work visible in a view handler), and one must-fail arm (a dispatcher that swallows
  a handler failure, as phase-8 `PROCESSOR_SWALLOWS_FAILURE`).

phase-8 open question 4 ("annotated methods later if porting asks for it") is closed by this.

### 3.4 Tests

`EssentialsEventLogPropertiesTest` (every property reaches `EventLogSettings`, defaults equal `EventLogSettings.defaults()`),
`EventLogAutoConfigurationIT` (context starts, append through the adapter, a `ViewProjection` bean delivers, the import
filter drops the old configuration and keeps snapshots; a must-fail variant without the filter shows the old
subscription manager present), `EventLogSchemaModeIT` (`create`, `validate`, `emit`, as the queue starter's),
`EventLogAdminApiIT` (REST through the controller), `MissingDependencyFailureAnalyzerRegistrationTest`. One static
container, schema recreated in `@BeforeEach`, as the queue starter's tests.

---

## 4. Admin UI page

The admin UI is one Thymeleaf page and a vanilla-JS app (`spring-boot-starter-admin-ui`, `index.html`, `admin.js`); each
engine is a view in `const views`, and an absent engine shows the API's 404 through `errorState`. **Proposed:** a view
`eventLogSubscriptions`, beside `shardOwnedQueues`:

- Nav button `data-view="eventLogSubscriptions"`, disabled without `canReadSubscriptions`.
- List: subscriber, aggregate type, instance, mode (`FRESH`/`CONTIGUOUS`), progress model, status, resume point, lag,
  lease owner and fence, parked count; filter by aggregate type.
- Detail: the row, its parked events page by page (`after`, `limit`), with retry and discard per parked head, and reset
  to a global order or head, each behind the page's own confirmation (the viewer shows no `confirm()`), and only with
  `canWriteSubscriptions`.
- Delete a retired instance's row.
- Every call is one of the seven `event-log-subscriptions` operations; `AdminUiContractParityTest` checks the paths
  against the spec.

No Node or JavaScript build (root `CLAUDE.md`). Existing-module change E2 (§10).

---

## 5. Documentation

| Target | Content |
|---|---|
| `LLM/LLM-postgresql-event-log.md` (new) | The engine for a consumer: when to choose it, preview status, the starter and properties, `EventLogEventStore` for aggregates, `ViewProjection` and `EventReactor` (consistency modes, progress, ownership, filters, error handling: retry, shrink, park; no `@MessageHandler`, no permanent-error list), the admin operations, the start-up refusal (P0.3 probe), the migration tool and its procedure (phase-9 §5) with `trustOldResumePoint`, what is not offered (CDC, Reactor polling, in-transaction subscribers, multi-type subscriptions) |
| `LLM/LLM.md` | Index row; the "which event store" decision |
| `LLM/LLM-spring-boot-starter-modules.md`, `LLM/LLM-admin-api.md` | The starter; the seven operations |
| `LLM/LLM-traps.md` | New `ESS-NNN` ids, appended, never renumbered: `EventReactor` handlers must be idempotent (at-least-once, `FRESH`); a `ViewProjection` writes only through `ProjectionContext` (its own commit is refused); an I/O-classified exception from a handler retries forever; `json` columns keep compact payload text (hashing code notices, phase-9 §2.3); old processors do not run on the new engine |
| `essentials-plugin/` | Generated from `LLM/` by `scripts/sync-plugin-llm.sh`; a new `plugin.json` version (`scripts/plugin-check.sh changed`, then `quick`) |
| `docs/` reference | **Proposed:** `docs/design/event-log.md`, the built engine's reference (mechanism, invariants, statements, settings), condensed from the plan and phase docs 2-9; the plan and phase docs then move to `docs/archive/` |
| Module `README.md`s | One per new module, as the existing modules have |
| `docs/releases/NEXT_VERSION_RELEASE.md` | §2 entries: the engine, the processor module, the migration tool, the starter, the admin UI page; §5 the demo; the compact-JSON note (phase-9 §2.3) |
| Module `CLAUDE.md`s | Drop "unreleased, nothing depends on it" |

---

## 6. The trading demo on the new engine

### 6.1 What it uses today

Starters `spring-boot-starter-postgresql-event-store` and `…-queue-shard-owned`; CDC on in the `compose` profile; six
aggregate repositories (Accounts with snapshots and closing books); 25 processors on the old API:

| Kind | Processors | Today |
|---|---|---|
| Exclusive SQL projections (15) | `KnownPayments`, `Cases`, `KnownExecutions`, `Users`, `Settlements`, `SettlementFills`, `InstrumentDetails`, `UnclearedDeposits`, `Positions`, `LateFills`, `UnknownExchangeOrders`, `AccountOwners`, `Orders`, `Payments`, `FundingAccountOwners` | `TradingDemoViewEventProcessor`: direct handling, batch size 1 |
| Partitioned views (2) | `AccountOverviewProjection`, `AccountActivityProjection` (Accounts) | `PartitionedViewEventProcessor`: every instance, per-key order through the shard-owned queue |
| Command reactions (3) | `OrderLedger`, `FundingLedger`, `SettlementLedger` | `TradingDemoEventProcessor`: every event through a durable-queue inbox, parallel consumers, queue redelivery |
| External effects (3) | `ExchangeOrderRouter`, `PaymentRouter`, `InstrumentRiskApproval` | Same inbox base |
| EF-2 measurement (2 + 1) | `NoOpAccountsEventProcessor`, `NoOpAccountsViewProcessor`, `BatchedAccountsForwarder` | Off unless a `trading-demo.ef2.*` flag is set |

Four of them (`KnownPayments`, `Payments`, `PaymentRouter`, `FundingLedger`) take two aggregate types (Deposits and
Withdrawals); the new builders take one type per subscription.

### 6.2 Porting (decided, §11 question 2)

Every processor keeps its `@MessageHandler` bodies and swaps its superclass (§3.5):

- The demo's three base classes get a new-engine twin: `TradingDemoViewEventProcessor` → a twin on
  `EventLogViewProcessor`; `TradingDemoEventProcessor` → a twin on `EventLogReactor` whose handlers put the event into
  the same durable-queue inbox as today, so parallel consumers and redelivery stay exactly as measured now;
  `TradingDemoPartitionedViewEventProcessor` → a twin on `EventLogReactor` forwarding to the shard-owned queue, consumed
  on every instance as today. The event store and its subscriptions change; the queues do not, which keeps the A/B
  comparison about the event store.
- The two-type processors (Deposits and Withdrawals) pass both types (§3.5: one subscription per type).
- A class has one superclass, so while both engines are kept (§6.3) each ported processor is split into its handler
  body (a plain class, shared) and two thin wrappers, one per engine, each `@ConditionalOnProperty(trading-demo.engine)`.
  The wrappers carry only the `@MessageHandler` methods delegating to the shared body. An application that moves for
  good just swaps the superclass.

### 6.3 Engine switch in the demo (decided, §11 question 3)

A property `trading-demo.engine=classic|event-log` (default `classic`); with `event-log` the demo also sets
`essentials.event-log.enabled=true` and turns CDC off (an `event-log` profile). The same build runs both arms of the gate
(§8), and the old arm stays the reference for later work.

The harness SQL that reads the old event tables (`AccountEventCounter`, `LoadRunReport`'s placed→booked latencies) gets an
`el_`-table variant chosen by the same switch. EF-2 measurement processors stay `classic`-only.

### 6.4 Data

A fresh database per arm (the gate measures steady-state behaviour, not migration). The migration path is already
proven on the demo's data by phase 9 §17.3.

---

## 7. Against the invariants

Phase 10 adds no engine behaviour. The starter ITs re-run one append-and-deliver path through Spring; the demo port uses
only the phase-8 processors. Every phase-2 to phase-9 IT must keep passing.

---

## 8. Gate measurement

**Baseline:** the same demo build with `trading-demo.engine=classic` (CDC on, as the `compose` profile ships), against
the same PostgreSQL through the port proxy (demo harness `CLAUDE.md`, 2026-11-03: earlier numbers are not comparable).

**Runs:** `run-load.sh` with the simulators profile, configuration J of "Inbox consumers at the opens", one and two
instances, three runs per arm per instance count, palindrome order (classic, event-log, event-log, classic, …). Only runs
with equal `steadyState.bursts` are compared.

**Pass (Proposed):** for each instance count, the median over runs of
- completed business operations (orders placed→booked, deposits, withdrawals) not below the classic arm's,
- booked p99 outside bursts not above 1.1× the classic arm's (p99 is noisy; 10 % is the noise band seen in earlier A/Bs),
- zero lost or duplicated view rows (row counts and an end-of-run consistency query per projection against the events),
- the steady-state check passing.

Also recorded, not gating: statements per event (`pg_stat_statements`), server CPU, connections held, the admin page
under load.

---

## 9. Build split

| Unit | Content | Depends on |
|---|---|---|
| 10-A | Starter, import filter, properties, admin SPI bean, processor lifecycle, ITs | — |
| 10-A2 | Annotated base classes (§3.5) and their ITs; the starter's `EventLogProcessorDependencies` | 10-A |
| 10-B | Admin UI view | 10-A (for a demo context; the view itself needs only the REST API) |
| 10-C | Demo port: processors, engine switch, harness SQL, configuration | 10-A2 |
| 10-D | Gate measurement (§8), results in §12 | 10-C |
| 10-E | Documentation, LLM docs, plugin version, release notes, archive move | 10-A to 10-D |

Each unit is one commit with its release-note entry; 10-E's release notes collect the preview framing.

---

## 10. Existing modules touched

| # | Module | Change | Avoidable? |
|---|---|---|---|
| E1 | Root `pom.xml` | `<module>` for the starter; BOM entry if listed | No |
| E2 | `spring-boot-starter-admin-ui` | The view (§4): `index.html`, `admin.js`, the controller's role flags if a new one is needed | No (the plan puts the UI in phase 10) |
| E3 | `examples/essentials-trading-demo` | The port (§6) | No (the gate) |
| E4 | `LLM/`, `essentials-plugin/`, `docs/` | §5 | No |
| E5 | `spring-boot-starter-postgresql-event-store` | `SnapshotConfiguration`: the snapshot store, snapshot job processor and snapshot repository factory bean methods take `ConfigurableEventStore<? extends AggregateEventStreamConfiguration>` instead of `<SeparateTablePerAggregateEventStreamConfiguration>`, and so does `DefaultAggregateSnapshotRepositoryFactory` (field, both constructors, its builder's field and `setEventStore`; it only passes the store on), so snapshots work on the adapter (`PostgresqlAggregateSnapshotRepository` already accepts the wide type). Source- and behaviour-compatible | Approved 2026-11-18 (`QP-de09f3313b`); found by 10-A |
| E6 | `spring-boot-starter-admin-api` | Javadoc at `EssentialsAdminApiAutoConfiguration.java:214` ("no auto-configuration declares the bean yet") | Comment only |

---

## 11. Decisions (2026-11-18)

| # | Question | Decision | By |
|---|---|---|---|
| 1 | How the starter lives beside the old event-store starter (§3.3) | **(a) `AutoConfigurationImportFilter`** dropping `EventStoreConfiguration` while `essentials.event-log.enabled`; no existing module changed | Maintainer (`QP-a5e80f9c81`) |
| 2 | How existing processors reach the new engine (§3.5, §6.2) | **Annotated base classes** `EventLogViewProcessor` / `EventLogReactor` with `@MessageHandler` dispatch; D3 stands (no `EventStoreSubscriptionManager` adapter). The demo ports by superclass swap; reactions and partitioned views keep forwarding to the existing queues. Rejected: a full old-API adapter (reverses D3), hand-porting to lambda builders | Maintainer (`QP-b3c157d767`) |
| 3 | One demo build with an engine switch, or a permanent move (§6.3) | **Switch**, `trading-demo.engine`, default `classic` | Maintainer (`QP-45f6d26025`) |
| 4 | Release status (§2) | **Ship in 0.61.0 as preview** | Maintainer (`QP-fe4f361328`) |
| 5 | Module name | **Module renamed to `postgresql-event-log-processor`**, package `dk.trustworks.essentials.components.eventlog.processor` (was `postgresql-event-log-integration` / `…eventlog.integration`) | Maintainer, 2026-11-19 |

Settled by Claude, unless the maintainer says otherwise: one `EventLog` per `DataSource` (phase-8
question 10); the gate's run plan and pass lines (§8); the documentation layout (§5); the admin UI view shape (§4); a
fresh database per gate arm (§6.4).

---

## 12. Gate results (10-D)

Measured 2026-11-18 on `cae69a1c8`, in this devcontainer (8 cores, 15 GB; PostgreSQL, the simulators, the demo JVMs and
the load driver share the machine).

### Setup

- **Arms.** *classic*: Spring profiles `compose,simulators` (`trading-demo.engine=classic`, CDC on). *event-log*: the same
  plus `event-log` (`trading-demo.engine=event-log`, CDC off, backstop 200 ms, pages of 100). Both with configuration J
  as `application-compose.yml` now ships it: order-flow inbox consumers 16 / 16 / 16, pool 60.
- **Per run:** `reset-stack.sh` (fresh PostgreSQL, exchange and gateway volumes), `compose.yml --profile simulators up`,
  the instances started from `target/classes` on the runtime classpath (as `run-faults.sh` does: full JIT, PostgreSQL at
  the container's IP, not through the port proxy; `-Xmx3g` each with two instances), `pg_stat_statements_reset()` once
  they answer, then `run-load.sh --population=2000 --active-per-day=1000 --orders-per-second=100
  --burst-orders-per-second=600 --business-days=10 --max-in-flight=2048`, then the extracts below, then the instances
  stopped. Palindrome order classic, event-log, event-log, classic, classic, event-log, for one and then two instances.
  A smoke pair (2 business days, 200 customers) ran first; both arms completed with every check passing.
- **Per-order cost** is over the whole driver run, the 2 000 customers' registration and funding included (the same
  in both arms), divided by orders placed: `pg_stat_statements` calls, `pg_current_wal_lsn()` difference, PostgreSQL
  container CPU (cgroup `usage_usec`) and the demo JVMs' process CPU.
- **Booked latency outside bursts** is computed from the events, not taken from the report: per fill, `OrderPlaced` to
  the first `TradeBooked` with its execution id. A business day's open is the first placement after a gap of over 10 s;
  its burst is the driver's own window, the first `burst-fraction` (6 s) from it. "Outside" is every fill placed outside
  those windows; the first two minutes of the run are dropped. These orders still queue behind the burst's backlog. The
  report's own `placedToBooked` p50 / p99 (all fills, whole run) is shown beside it.
- **View rows** checked at the end of every run, against the events: `projection_orders` (one row per placed order,
  none without events, no duplicates, `last_event_order` equal to the stream's last event), `projection_funding_payment`
  (one row per deposit and withdrawal aggregate), `projection_account_overview` (`last_event_order` equal to the
  account stream's last event, one row per account), `projection_account_activity` (no duplicate or phantom
  `(stream, event_order)`). The other projections were not checked.

### One instance

| Run | Arm | Bursts | Booked p50 / p99, all (ms) | Booked p50 / p99 outside bursts (ms) | Completed ops | Statements / order | WAL / order | PG CPU / order | JVM CPU / order | Steady state |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | classic | 10 | 620 / 6 293 | 171 / 446 | 39 269 | 246 | 60.3 kB | 12.9 ms | 21.4 ms | pass |
| 2 | event-log | 9 | 1 734 / 5 246 | 20 / 2 129 | 35 483 | 301 | 51.3 kB | 15.7 ms | 24.2 ms | pass |
| 3 | event-log | 10 | 2 083 / 5 522 | 21 / 2 661 | 39 475 | 304 | 51.4 kB | 15.7 ms | 23.0 ms | pass |
| 4 | classic | 10 | 691 / 5 660 | 172 / 1 020 | 39 178 | 247 | 60.8 kB | 13.2 ms | 22.1 ms | pass |
| 5 | classic | 10 | 642 / 5 347 | 174 / 442 | 39 317 | 246 | 60.7 kB | 13.0 ms | 21.7 ms | pass |
| 6 | event-log | 9 | 1 884 / 5 802 | 21 / 3 079 | 35 385 | 301 | 50.9 kB | 15.8 ms | 24.2 ms | pass |

Completed operations: fills booked plus deposits and withdrawals completed. Two of the three event-log runs traded 9
business days, so the comparable set at 10 bursts is classic n = 3 against event-log n = 1 (run 3). The per-order costs
barely depend on the day count, and the 9-day runs agree with run 3 on them.

| Median, 10 bursts | classic (n = 3) | event-log (n = 1) | event-log / classic |
|---|---|---|---|
| Completed ops | 39 269 | 39 475 | 1.01 |
| Booked p50 / p99, all | 642 / 5 660 ms | 2 083 / 5 522 ms | 3.2 / 0.98 |
| Booked p50 / p99, outside bursts | 172 / 446 ms | 21 / 2 661 ms | 0.12 / **6.0** |
| Statements / order | 246 | 304 | 1.24 |
| WAL / order | 60.7 kB | 51.4 kB | 0.85 |
| PostgreSQL CPU / order | 13.0 ms | 15.7 ms | 1.21 |
| JVM CPU / order | 21.7 ms | 23.0 ms | 1.06 |

### Two instances

| Run | Arm | Bursts | Booked p50 / p99, all (ms) | Booked p50 / p99 outside bursts (ms) | Completed ops | Statements / order | WAL / order | PG CPU / order | JVM CPU / order | Steady state |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | classic | 10 | 1 717 / 9 673 | 241 / 2 060 | 39 222 | 271 | 62.3 kB | 15.8 ms | 24.9 ms | pass |
| 2 | event-log | 10 | 5 253 / 11 365 | 3 179 / 8 141 | 39 350 | 349 | 52.2 kB | 21.2 ms | 27.3 ms | pass |
| 3 | event-log | 10 | 5 008 / 11 677 | 2 837 / 7 823 | 39 312 | 340 | 52.2 kB | 20.6 ms | 27.1 ms | pass |
| 4 | classic | 10 | 1 411 / 10 170 | 232 / 2 502 | 39 185 | 269 | 61.9 kB | 15.3 ms | 24.3 ms | pass |
| 5 | classic | 10 | 1 537 / 9 128 | 231 / 1 513 | 39 129 | 271 | 62.0 kB | 15.4 ms | 24.3 ms | pass |
| 6 | event-log | 10 | 4 833 / 11 224 | 2 574 / 6 744 | 39 253 | 352 | 51.7 kB | 20.8 ms | 27.1 ms | **fail** (one burst took 51 s to recover) |

| Median, 10 bursts | classic (n = 3) | event-log (n = 3) | event-log / classic |
|---|---|---|---|
| Completed ops | 39 185 | 39 312 | 1.00 |
| Booked p50 / p99, all | 1 537 / 9 673 ms | 5 008 / 11 365 ms | 3.3 / **1.17** |
| Booked p50 / p99, outside bursts | 232 / 2 060 ms | 2 837 / 7 823 ms | 12 / **3.8** |
| Statements / order | 271 | 349 | 1.29 |
| WAL / order | 62.0 kB | 52.2 kB | 0.84 |
| PostgreSQL CPU / order | 15.4 ms | 20.8 ms | 1.35 |
| JVM CPU / order | 24.3 ms | 27.1 ms | 1.12 |

### Verdict per pass line

| Pass line (§8) | One instance | Two instances |
|---|---|---|
| Completed operations not below classic | **Pass** (1.01×; n = 1 at equal bursts) | **Pass** (1.00×) |
| Booked p99 outside bursts ≤ 1.1× classic | **Fail** (2 661 against 446 ms, 6.0×; the whole-run p99 is 0.98×) | **Fail** (7 823 against 2 060 ms, 3.8×; whole-run p99 1.17×) |
| Zero lost or duplicated view rows | **Pass** (four projections, every run of both arms) | **Pass** (the same) |
| Steady-state check passing | **Pass** (every run) | **Fail** in one of three event-log runs (burst recovery 51 s); every classic run passed |

**The gate does not pass.** The new engine is correct under this load (every order, fill and payment reached its
outcome, no dead letters, no reconciliation cases, every checked view row matches its events, no `ERROR` in any
application log) and completes the same work, but the order flow is slower at the opens, and more so with two instances.

### Where the time goes

The report's stage split (p50 / p99, ms, medians of the runs above):

| Stage | classic, 1 instance | event-log, 1 instance | classic, 2 instances | event-log, 2 instances |
|---|---|---|---|---|
| Placed → submitted | ~410-510 / 3 000-4 800 | ~120-240 / 2 300-2 900 | ~620-790 / 2 500-5 100 | ~1 700-2 000 / 6 000-6 500 |
| Submitted → filled | 2-3 / 2 400-3 100 | 33-39 / 160-240 | 55-99 / 5 200-7 400 | 18-21 / 580-2 500 |
| Filled → booked | 165-179 / 465-777 | 1 530-1 740 / 2 700-3 100 | 233-246 / 1 200-2 000 | 2 700-3 200 / 4 000-5 500 |

- **Filled → booked is the regression.** On the classic engine the ledger leg takes ~0.2 s at the median; on the new
  engine 1.6 s with one instance and ~3 s with two. Placement to submission is faster on the new engine with one
  instance and slower with two. Away from the bursts the new engine is much faster at the median (21 ms against
  172 ms with one instance): the latency is in draining the open's backlog, not in the per-event path when idle.
- **More statements, less WAL.** Per order, 24-29 % more statements and 21-35 % more PostgreSQL CPU, but 15 % less
  WAL (no CDC inbox rows). Among the statements only the new engine issues: an `el_progress` `UPDATE` plus a
  `set_config('idle_in_transaction_session_timeout', …)` per handled batch (~580 000 each in a one-instance run,
  ~480 000 with two), and with two instances ~530 000 `pg_notify` calls. The shard-owned queue's
  `pg_stat_activity` `backend_xid` probe ran 2.3× (one instance) to 2.7× (two instances) as often as on the classic
  arm, at 120 s of total execution time in one two-instance run.
- Not investigated further here (10-D measures; it does not tune).

### Not measured

Connections held, the admin page under load, whole-machine CPU (only the PostgreSQL container's and the demo JVMs'),
and the projections other than the four listed. Latency percentiles come from three runs per arm at most and, at one
instance, from a single event-log run at equal burst count; p99 moved by up to 2× between runs of the same arm
(classic, one instance: 442-1 020 ms outside bursts).

### Gate fixes and 1-instance re-measurement

The failed pass line above led to three fixes, each committed on its own:

- **`0f6506116`: the demo's inbox forwards a page per transaction.** The trading demo, on the new engine, now forwards
  one page of events into the inbox per transaction instead of one event at a time. This is correct and stays, but it
  was not the cause of the tail.
- **`25ec28bce`: a multi-type flush no longer counts as the probe cost.** The hole probe sized its wait from the cost
  of the last read; a flush that read several event types at once was being counted as that cost, inflating the wait.
- **`16a3cbdb7`: the probe wait is capped while holes wait.** `holeProbeWaitMax` (property
  `essentials.event-log.hole-probe-wait-max`, default 100 ms) caps the wait between probes while at least one hole is
  waiting to be retired. While the cap is in force the ~1 % probe budget is a soft target rather than a hard limit.

**Root cause.** Appends to Orders that roll back leave holes in the global order, 20–35 of them per burst. A hole was
retired only by a probe, and the probe's wait was the probe cost × 100 × max(1, backends / 10). Under burst load that
came to seconds, so every `CONTIGUOUS` Orders subscriber stalled for 0.5–6 s behind a hole that was already known to be
a rollback. The flush-cost fix shortened the wait but left it unbounded; the cap bounds it.

The one-instance gate was re-run after each fix, on the same setup as above, ten bursts each. Booked latency outside
bursts, in ms:

| Runs | Arm | Code | Booked p99 / p50 outside bursts, per run (ms) |
|---|---|---|---|
| 1–3 | classic | `cae69a1c8` | 446 / 171, 1 020 / 172, 442 / 174 |
| 8–10 | event-log | after the flush-cost fix (`25ec28bce`) | 2 415 / 22, 491 / 20, 4 034 / 22 |
| 11–13 | event-log | after the cap (`16a3cbdb7`) | 365 / 20, 677 / 22, 507 / 20 |

With the cap the event-log median out-of-burst p99 is 507 ms against classic's 446 ms, 1.14×; the p50 stays at about a
tenth of classic's. Completed operations are equal between the arms, every run has zero lost or duplicated view rows,
and the steady-state check passes. PostgreSQL CPU per order is 15.8–16.4 ms on the new engine against 13.0 ms on the
classic one.

**Where the remaining tail is (run 13).** What is left is the filled → booked leg of orders placed in the first second
after a burst window closes. In that second the p99 is 703 ms against classic's 568 ms; in the seconds after it the new
engine is at 71–185 ms against classic's 277–346 ms. Leaving that second out, the new engine's out-of-burst p99 is
119 ms against classic's 308 ms. The tail is not the probe cadence and not the simulator. The suspected cause is that
the new engine drains the backlog left at the end of a burst more slowly, but that rests on 3 of the 10 bursts and is
not established.

**Verdict.** The maintainer accepted the one-instance gate as passed on 2026-11-19.

**Measurement note.** Event type ids are registered at runtime and differ from one database to the next, so a query
over the event log has to look them up by name, never reuse an id seen in another run.

**Two instances, after the cap.** Measured 2026-11-19 on `16a3cbdb7` (the demo classes and the installed
`postgresql-event-log` jar built from it), same setup and driver arguments as above, two instances. The classic
baseline is the three two-instance classic runs above; the classic code path is unchanged since `cae69a1c8`, so they
were not repeated.

| Run | Arm | Bursts | Booked p50 / p99, all (ms) | Booked p50 / p99 outside bursts (ms) | Completed ops | Statements / order | WAL / order | PG CPU / order | JVM CPU / order | Burst recovery (max s) | Steady state |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 11 | event-log | 10 | 1 143 / 9 096 | 31 / 1 878 | 39 473 | 319 | 50.1 kB | 19.2 ms | 25.8 ms | 9 | pass |
| 12 | event-log | 10 | 1 390 / 9 742 | 34 / 2 432 | 39 429 | 330 | 50.0 kB | 20.5 ms | 27.1 ms | 2 | pass |
| 13 | event-log | 10 | 1 238 / 9 051 | 30 / 1 581 | 39 553 | 332 | 50.6 kB | 20.1 ms | 26.1 ms | 4 | pass |

| Median, 10 bursts | classic (n = 3, runs 1, 4, 5 above) | event-log (n = 3, runs 11–13) | event-log / classic |
|---|---|---|---|
| Completed ops | 39 185 | 39 473 | 1.01 |
| Booked p50 / p99, all | 1 537 / 9 673 ms | 1 238 / 9 096 ms | 0.81 / 0.94 |
| Booked p50 / p99, outside bursts | 232 / 2 060 ms | 31 / 1 878 ms | 0.13 / **0.91** |
| Statements / order | 271 | 330 | 1.22 |
| WAL / order | 62.0 kB | 50.1 kB | 0.81 |
| PostgreSQL CPU / order | 15.4 ms | 20.1 ms | 1.30 |
| JVM CPU / order | 24.3 ms | 26.1 ms | 1.08 |

Against the pass lines of §8, with two instances:

| Pass line (§8) | Two instances, after the cap |
|---|---|
| Completed operations not below classic | **Pass** (1.01×; every event-log run above the classic median) |
| Booked p99 outside bursts ≤ 1.1× classic | **Pass** (median 1 878 against 2 060 ms, 0.91×; run 12 alone is 1.18×, runs 11 and 13 are below classic) |
| Zero lost or duplicated view rows | **Pass** (four projections, every run) |
| Steady-state check passing | **Pass** (every run; burst recovery 2–9 s against classic's 0–9 s) |

Every driver check passed in all three runs (every order, fill and payment reached its outcome, no dead letters, no
open reconciliation case, no missing statement, the driver never saturated), and no application log has an `ERROR`.
The cost line is unchanged in kind: about 22 % more statements and 30 % more PostgreSQL CPU per order than classic,
for 19 % less WAL. Run 12 took 1 429 s of wall clock against ~1 035 s for every other run; the whole difference is
one business day (the fifth) taking 452 s instead of ~180 s, with no fill left unbooked at its close and its latency
inside the figures above. Its cause was not investigated.
