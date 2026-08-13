# Essentials Trading Demo

Small Spring Boot example app for validating:

- snapshots
- closing books
- typed logical ids vs internal generation ids
- starter auto-configuration in a realistic app

## ⚠️ Wipe An Existing Local Demo Database First

This release repackaged the module into vertical slices, which moved every event class and flattened the
nested event types into top-level records. Essentials persists an event's concrete FQCN as its event type,
and no upcasting is provided here, so **every event written by an earlier version of this demo is
unreadable**:

```
…trading.accounts.TradingAccountEvent$TradingAccountOpened  →  …trading.brokerage.events.TradingAccountOpened
```

Drop the local volume and let bootstrap reseed:

```bash
docker compose down -v && docker compose up -d
```

The per-event JSON *payload* is unchanged — records kept the same component names and every semantic type
introduced by the refactor serializes as the scalar it replaced — so this is purely an event-type rename.

If you skip the wipe, nothing corrupts: `TradingSimulationRunner` detects the partial/legacy seed data,
logs a warning telling you to remove the volume, and refuses to seed. The symptom is a half-empty demo,
not a broken one.

## How The Module Is Laid Out

Packaged by **vertical slice**, not by layer. Two bounded contexts under
`dk.trustworks.essentials.examples.trading`:

| Package | What it holds |
|---|---|
| `brokerage/` | `TradingAccount`, `Trade`, `Settlement` — 19 command slices, 6 view slices |
| `market_data/` | `Instrument`, `InstrumentPrice` — 5 command slices, 2 view slices |
| `config/` | app-level wiring only: web MVC, error handling |
| `_demo_harness/` | deliberately **not** a slice — bootstrap runner, load generator, dashboard, benchmark price store |

Both contexts are on the **aggregate write style** (§R5 of the slice-design law): `AggregateRoot` reached
through a `StatefulAggregateRepository` wrapper. That is a sanctioned lane — nothing here is a `Decider`
and nothing should be converted into one.

Inside a context, `use_cases/<slice>/` holds a command slice (the command record, its `@CmdHandler`, its
one API file) and `views/<slice>/` holds a read model (projector, query, read shape, API). **Writes go
through the command bus** — every API file does `commandBus.send(new SomeCommand(…))`, and the command
record *is* the request body. **Reads go through a view slice**, never by rehydrating an aggregate.

`_demo_harness/` is the law's `_`-prefixed escape hatch: the load generator manufactures traffic a real
deployment would receive from users, and the dashboard reports on the harness as much as on the domain, so
calling them automations and views would be the bigger lie. It obeys the same write/read rules with two
documented exceptions.

`REFACTORING_PLAN.md` records the decisions taken during the conversion and the questions left open; each
context and slice carries its own `CLAUDE.md` with the detail.

## What Bootstrap Leaves Behind

On a clean database the startup runner seeds three accounts and deliberately leaves each in a different state,
one per closing-books mechanism, so they can be compared side by side in the admin UI under
**Aggregates → Aggregate lookup**:

| Account | Rolled by | Ends up as |
|---|---|---|
| `ACC-DEMO-001` | The **closing-books policy**, with no application involvement | Generation 2, and the only account with snapshots — crossing the closing-books event threshold also crosses the snapshot policy's `everyNEvents` |
| `ACC-DEMO-002` | An **explicit application command** (`CloseBooksAndOpenNextPeriod`, sent on the command bus), with no policy involvement | Generation 2, no snapshots |
| `ACC-DEMO-003` | Nothing — the baseline | Generation 1, still open |

`ACC-DEMO-001` gets ordinary deposits written to it until the policy decides to roll; nothing in the runner asks
for that rollover, which is the point. The log says which mechanism acted on which account.

Accounts are opened in the **current** period, derived from the configured time boundary via
`ClosingBooksTimeBoundaryCalculator.currentPeriodId(...)`. Don't replace this with a hardcoded period id: it
ages into the past, and every later policy evaluation then reports skipped periods and trips gap detection.

Thresholds are deliberately production-shaped (snapshot every 100 events, closing-books threshold 100) rather
than shrunk for the demo, so bootstrap writes ~100 events into `ACC-DEMO-001` to cross them.

## Run Locally

From the repo root:

```bash
mvn -q -pl examples/essentials-trading-demo -Dspring-boot.run.profiles=compose spring-boot:run
```

This uses:

- [compose.yml](src/main/resources/compose.yml)
- [application-compose.yml](src/main/resources/application-compose.yml)

Spring Boot Docker Compose will start PostgreSQL automatically when the `compose` profile is active.

## Run From IntelliJ

Create a Spring Boot run configuration for:

- main class: `dk.trustworks.essentials.examples.trading.TradingDemoApplication`
- module: `examples/essentials-trading-demo`
- active profiles: `compose`

Then use Run or Debug as usual.

If Spring Boot Docker Compose integration does not automatically start PostgreSQL from IntelliJ in your setup, start [compose.yml](src/main/resources/compose.yml) manually first and keep the `compose` profile active in the run configuration.

## Useful Endpoints

Every command slice exposes exactly one endpoint and every view slice one or two, so the list below is the
module's whole HTTP surface, grouped by bounded context. Most command endpoints take their slice's command
record straight as the request body — there is no DTO in between — and where a path variable repeats a body
field, a mismatch between the two is rejected with `400`. The few commands carrying a single extra value
take it as a query parameter instead; the ones carrying nothing but an id are a bare `POST`.

Semantic types are registered for web binding, so `{accountId}`, `{tradeId}`, `{settlementId}` and
`{instrumentId}` are the typed ids, and a malformed one is a `400` rather than a `500`.

### App and harness

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /admin` — the dashboard page (`GET /` redirects here)
- `GET /api/admin/dashboard`
- `GET /api/admin/dashboard/stream` — SSE feed the dashboard uses for live KPI and ticker updates
- the load-generator endpoints, listed under [Runtime Load Generator](#runtime-load-generator)

### `brokerage` — commands

Trading accounts:

- `POST /api/admin/trading-accounts` — `open_trading_account`, body `OpenTradingAccount`
- `POST /api/admin/trading-accounts/{accountId}/deposits` — `deposit_cash`, body `DepositCash`
- `POST /api/admin/trading-accounts/{accountId}/fund-reservations` — `reserve_funds`, body `ReserveFunds`
- `POST /api/admin/trading-accounts/{accountId}/fund-releases` — `release_funds`, body `ReleaseFunds`
- `POST /api/admin/trading-accounts/{accountId}/trade-settlements` — `apply_trade_settlement`, body `ApplyTradeSettlement`
- `POST /api/admin/trading-accounts/{accountId}/books-closures` — `close_books`, body `CloseBooks`
- `POST /api/admin/trading-accounts/{accountId}/generations` — `close_books_and_open_next_period`, body `CloseBooksAndOpenNextPeriod`
- `POST /api/admin/trading-accounts/{accountId}/generations/{generation}/archive` — `archive_generation`
- `POST /api/admin/trading-accounts/closing-books` — `update_closing_books_settings`, body `UpdateClosingBooksSettings`

Trades:

- `POST /api/admin/trades` — `place_trade`, body `PlaceTrade`
- `POST /api/admin/trades/{tradeId}/execution` — `execute_trade`
- `POST /api/admin/trades/{tradeId}/settlement-requests?settlementId=…` — `request_settlement`
- `POST /api/admin/trades/{tradeId}/settlement` — `mark_trade_settled`

Settlements:

- `POST /api/admin/settlements` — `create_settlement`, body `CreateSettlement`
- `POST /api/admin/settlements/{settlementId}/clearing-requests` — `request_clearing`
- `POST /api/admin/settlements/{settlementId}/clearing-confirmations` — `confirm_clearing`
- `POST /api/admin/settlements/{settlementId}/reconciliation` — `reconcile_settlement`
- `POST /api/admin/settlements/{settlementId}/settlement` — `mark_settlement_settled`
- `POST /api/admin/settlements/{settlementId}/closure` — `close_settlement`

### `brokerage` — views

Projection-backed, so **eventually consistent** — a read taken immediately after the write that feeds it can
still show the previous state, and an account that exists but is not yet projected answers `404`:

- `GET /api/admin/trading-accounts/{accountId}` — `account_statement`, one account's overview
- `GET /api/admin/projections/account-statements` — `account_statement`, all of them
- `GET /api/admin/settlements/{settlementId}` — `trade_settlement_status`, one settlement's lifecycle
- `GET /api/admin/projections/trade-settlements` — `trade_settlement_status`, the combined trade/settlement read model
- `GET /api/admin/trades/{tradeId}` — `trade_valuation`, priced as of the last *projected* price event, not as of now

Read straight off the event store and the archive registry, so **strongly consistent**:

- `GET /api/admin/trading-accounts/{accountId}/generations/{generation}/events` — `account_generation_events`
- `GET /api/admin/trading-accounts/{accountId}/archives` — `account_generation_archives`
- `GET /api/admin/trading-accounts/{accountId}/generations/{generation}/archive-content` — the archived JSONL, `text/plain`
- `GET /api/admin/trading-accounts/closing-books` — `closing_books_configuration`, the live policy settings

### `market_data` — commands

- `POST /api/admin/instruments` — `register_instrument`, body `RegisterInstrument`
- `POST /api/admin/instruments/{instrumentId}/name?displayName=…` — `rename_instrument`
- `POST /api/admin/instruments/{instrumentId}/suspension?reason=…` — `suspend_instrument`
- `POST /api/admin/instrument-prices` — `initialize_price`, body `InitializePrice`
- `POST /api/admin/instrument-prices/{instrumentId}?price=…` — `update_price`

### `market_data` — views

- `GET /api/admin/instruments` — `instrument_details`, all instruments, **eventually consistent** (projection-backed)
- `GET /api/admin/instruments/{instrumentId}` — `instrument_details`, likewise
- `GET /api/admin/instrument-prices/{instrumentId}` — `latest_price`, **strongly consistent**: it is the one
  read in the module that goes to the aggregate, because the bootstrap's idempotency probe needs an answer
  that cannot lag

## What The Simulator Does

On startup, the simulator seeds a small but realistic demo dataset unless it detects that the seed data already exists.

It currently:

- registers demo instruments from a pool of realistic ticker symbols — `AAPL`, `MSFT`, `NVDA`, `AMZN` …
  `NOVO-B` — taking as many as `instrument-count` asks for
- opens one or more demo trading accounts like `ACC-DEMO-001`
- deposits cash, reserves and releases funds, and applies a few settled trades
- creates and completes settlement lifecycles for those trades
- initializes market prices per instrument and updates them while processing demo trades
- rolls one account's books through the policy and one through an explicit command, leaving the third alone
  (see [What Bootstrap Leaves Behind](#what-bootstrap-leaves-behind))

Every one of those mutations goes through the command bus as the corresponding slice's command — the runner
holds no application service and constructs no aggregate. Its only direct use of a repository is the
idempotency probe, which has to be strongly consistent so a restart against a populated database does not
seed on top of existing data.

The knobs live under `trading-demo.simulation`:

- `enabled`
- `account-count` (default 3)
- `instrument-count` (default 2)
- `deposits-per-account`
- `settlements-per-account`
- `max-policy-driven-events` — safety cap on the deposits fed to the policy-driven account while waiting for
  the closing-books event threshold to be crossed; the loop stops as soon as the policy rolls

## TradingAccount Closing-Books Trigger

`TradingAccount` supports multiple demo rollover trigger styles.

The active demo config lives under:

- `trading-demo.accounts.closing-books.mode`
- `trading-demo.accounts.closing-books.event-threshold`
- `trading-demo.accounts.closing-books.time-boundary`
- `trading-demo.accounts.closing-books.zone-id`
- `trading-demo.accounts.closing-books.interval-days`

Supported modes:

- `manual-only`
  only the startup seed rollover happens, so generation counts will usually stay flat after bootstrap
- `event-count`
  open a new generation once the account stream reaches the configured event threshold
- `time-boundary`
  open a new generation when the configured clock-based business period advances
- `event-count-or-time-boundary`
  roll over when either condition is met

Current default:

```yaml
trading-demo:
  accounts:
    closing-books:
      mode: event-count-or-time-boundary
      event-threshold: 100
      time-boundary: end-of-month
      zone-id: Europe/Copenhagen
```

That means the demo now supports both of the realistic shapes we discussed:

- time-based rollover for account-period boundaries
- `event-count` rollover for pressure-testing and easier local demos

If you want to simulate a weekly or fixed-interval cutover locally, switch to:

```yaml
trading-demo:
  accounts:
    closing-books:
      mode: time-boundary
      time-boundary: end-of-week
      zone-id: Europe/Copenhagen
```

For fixed-interval rollovers you can also use:

```yaml
trading-demo:
  accounts:
    closing-books:
      mode: time-boundary
      time-boundary: every-n-days
      interval-days: 7
      zone-id: Europe/Copenhagen
```

The `/admin` dashboard now shows the active closing-books policy description, so it is easier to see whether you are looking at manual-only rollover, threshold-based rollover, or time-boundary rollover.

### Retuning The Policy At Runtime

To demo time-based rollover without restarting the app, update the settings through the single atomic
endpoint:

```bash
curl -X POST 'http://localhost:8080/api/admin/trading-accounts/closing-books' \
     -H 'Content-Type: application/json' \
     -d '{"mode":"TIME_BOUNDARY","timeBoundary":"END_OF_WEEK","zoneId":"Europe/Copenhagen"}'
```

Read the current settings back with:

```bash
curl 'http://localhost:8080/api/admin/trading-accounts/closing-books'
```

Two things to know about the body:

- **Every field is optional and `null` means "leave this one unchanged".** The five fields are `mode`,
  `eventThreshold`, `timeBoundary`, `zoneId` and `intervalDays`; omit the ones you are not retuning.
- **The two enums take their exact constant names** — `END_OF_MONTH`, not `end-of-month`, and
  `EVENT_COUNT_OR_TIME_BOUNDARY`, not `event-count-or-time-boundary`. The hyphenated spelling still works
  in YAML, where Spring's relaxed binding handles it; it does not work over HTTP.

So a fixed-interval cutover is:

```bash
curl -X POST 'http://localhost:8080/api/admin/trading-accounts/closing-books' \
     -H 'Content-Type: application/json' \
     -d '{"mode":"TIME_BOUNDARY","timeBoundary":"EVERY_N_DAYS","intervalDays":7}'
```

**Why one endpoint and not four.** This used to be four independent `POST …/closing-books/{field}?value=…`
mutators, one per setting. The load generator's `comparisons/trading-account` scenario captures all five
values, swaps in its own, and restores them in a `finally`, so an admin change that landed mid-scenario
returned `200` and was then silently reverted — and a reader could see a new `mode` against the old
`timeBoundary` in between. The settings are now one immutable value swapped under one lock, the benchmark
scenario overrides them through that same lock, and a concurrent update during a scenario is rejected
instead of lost.

The dashboard's closing-books controls post this same body.

## Runtime Load Generator

The demo now also has an opt-in runtime load generator that can continue creating activity after startup.

When enabled it will:

- generate periodic price updates for the seeded demo instruments
- generate periodic trade lifecycles
- generate matching settlement lifecycles
- apply settled trade effects back to the seeded trading accounts

The status endpoint is:

- `GET /api/admin/load-generator`

Control endpoints:

- `POST /api/admin/load-generator/start`
- `POST /api/admin/load-generator/stop`

Burst endpoints:

- `POST /api/admin/load-generator/burst/trades?count=100`
  creates pending trades plus pending settlements, but does not complete settlement yet
- `POST /api/admin/load-generator/burst/settlements?count=100`
  settles pending generated trades and closes their settlements
- `POST /api/admin/load-generator/burst/price-updates?count=500`
  pushes a burst of market price updates
- `POST /api/admin/load-generator/burst/trade-lifecycles?count=100`
  runs the full create-and-settle path in one call
- `POST /api/admin/load-generator/price-stress/start?count=500&intervalMs=100&mode=aggregate-event-sourced`
  starts an asynchronous high-frequency price stress run and returns immediately; `mode` also accepts
  `direct-write`
- `POST /api/admin/load-generator/price-stress/stop`
  stops the current asynchronous price stress run
- `POST /api/admin/load-generator/comparisons/price-path?count=100`
  runs the same number of price updates through `aggregate-event-sourced` and `direct-write` back-to-back and stores the latest comparison result on `/admin`
- `POST /api/admin/load-generator/comparisons/trading-account?count=90&readPasses=25&eventThreshold=20`
  runs the same TradingAccount mutation and repeated-read workload twice, once with `manual-only` and once with `event-count`, and stores the latest comparison result on `/admin`

Two caveats when reading those two comparisons:

- **The aggregate price path is measured marginally heavier than before.** It now sends `UpdatePrice` on the
  command bus, which puts command dispatch, handler lookup and the bus's `UnitOfWork` interceptor inside the
  timed window, where the old code called a transactional service method directly. The direct-write path is
  unchanged and the transaction count per step is the same, so the comparison is now slightly biased
  *against* event sourcing.
- **The trading-account scenario's read pass still loads the aggregate on purpose.** That is the one place in
  the harness that does — see `_demo_harness/CLAUDE.md` § Exception 2. The scenario exists to measure how
  expensive rehydration is under two rollover policies; routing it through the `account_statement` projection
  would time a single-row `SELECT` and report zero for every snapshot delta.

Dashboard presets:

- `Realistic Feed`
  500 updates at 100 ms, useful for a live-ticker style demo
- `Fast Stress`
  500 updates at 10 ms, useful for a quicker pressure run
- `Max Throughput`
  1000 updates at 0 ms, useful for seeing the no-delay limit of the current aggregate path

Price stress mode selector:

- `aggregate-event-sourced`
  uses the current `InstrumentPrice` aggregate path, which is intentionally heavier and better for illustrating the cost of event sourcing on very frequent market data
- `direct-write`
  uses a direct latest-price upsert table, which is closer to how a lightweight market-data path would normally be modeled

The dashboard includes a `Price Path Comparison` panel that shows operation counts and latency per mode so it is easier to see why the aggregate path feels slower.

The `Trading Accounts` panel now also shows the latest `TradingAccount` comparison result, which is useful for seeing how event-count rollover changes generation growth and snapshot reuse compared to a no-rollover baseline.

Manual bursts are coordinated with the live generator, so a large burst will temporarily block the periodic generator instead of interleaving writes into the same demo streams.

The dashboard also exposes a live SSE feed at `GET /api/admin/dashboard/stream`, which the `/admin` page uses to update KPI cards and the latest price ticker without waiting for full-page refreshes.

## Closed-Generation Archival

The compose profile now also enables archiving:

- `essentials.eventstore.archives.enabled=true`

That gives the demo a default filesystem-backed archive pipeline for closed generations.

Useful endpoints:

- `POST /api/admin/trading-accounts/{accountId}/generations/{generation}/archive` — the `archive_generation`
  command slice
- `GET /api/admin/trading-accounts/{accountId}/archives` and
  `GET /api/admin/trading-accounts/{accountId}/generations/{generation}/archive-content` — the
  `account_generation_archives` view slice

Example:

```bash
curl -X POST 'http://localhost:8080/api/admin/trading-accounts/ACC-DEMO-001/generations/1/archive'
curl 'http://localhost:8080/api/admin/trading-accounts/ACC-DEMO-001/archives'
curl 'http://localhost:8080/api/admin/trading-accounts/ACC-DEMO-001/generations/1/archive-content'
```

What happens:

- the framework resolves the closed generation metadata
- reads the full event stream for that generation stream id
- exports the persisted events as JSONL
- writes the artifact to the configured filesystem archive root
- registers the archived generation in `aggregate_archives`

The default location root is the starter property:

- `essentials.eventstore.archives.filesystem-root-directory`

The default format is JSONL using Jackson-backed serialization of archive lines, while preserving the original persisted event and metadata JSON payloads.

Two projection endpoints are also available:
- `GET /api/admin/projections/account-statements`
  the `brokerage.account_statement` view slice, a durable `ViewEventProcessor` projection of the latest
  trading-account statement state
- `GET /api/admin/projections/trade-settlements`
  the `brokerage.trade_settlement_status` view slice, an `EventProcessor` that combines trade and settlement
  lifecycle state into one read model

Important properties:

- `trading-demo.load.enabled`
- `trading-demo.load.trade-interval`
- `trading-demo.load.price-update-interval`
- `trading-demo.load.max-generated-trades`
- `trading-demo.load.price-jitter.min`
- `trading-demo.load.price-jitter.max`

Defaults:

- in the default profile, runtime load generation is disabled
- in the `compose` profile, runtime load generation is enabled

If the app starts before the seed data exists, the runtime generator will wait for the bootstrap dataset instead of failing immediately.
The status payload also includes `pendingSettlementCount`, which is useful when you use the separate `trades` and `settlements` bursts.

## Verifying Snapshots And Closing Books

The demo is now in a good place to verify both behavior and operational value.

Suggested starting point:

- open `GET /admin` in a browser
- use the burst controls and live generator controls there
- watch account generations, pending settlements, and selected snapshot metrics update together

Functional checks — all three are projection-backed, so give them a moment to catch up after a burst:

- use `GET /api/admin/trading-accounts/{accountId}` to confirm that `TradingAccount` rolls across generations
- use `GET /api/admin/settlements/{settlementId}` to confirm that `Settlement` reaches its explicit closed lifecycle
- use `GET /api/admin/trades/{tradeId}` to inspect price-driven valuation after trade bursts and price-update bursts

For a strongly-consistent look at the same account, read its raw stream instead:
`GET /api/admin/trading-accounts/{accountId}/generations/{generation}/events`.

Useful traffic patterns:

- `POST /api/admin/load-generator/burst/trades?count=100`
- `POST /api/admin/load-generator/burst/settlements?count=100`
- `POST /api/admin/load-generator/burst/price-updates?count=1000`
- `POST /api/admin/load-generator/burst/trade-lifecycles?count=100`

Snapshot-related metrics to inspect through `/actuator/metrics`:

- `essentials.aggregate_snapshot.load_snapshot`
- `essentials.aggregate_snapshot.save_snapshot`
- `essentials.aggregate_snapshot.serialize_snapshot`
- `essentials.aggregate_snapshot.deserialize_snapshot`

Closing-books signals to inspect:

- `TradingAccount` generation count and current generation in the admin endpoint
- closing-books manager logs when scheduled processing is enabled elsewhere
- stream length differences between older and newer generations if you inspect the event tables directly

A practical comparison flow:

1. Run with snapshots and closing books enabled in the `compose` profile.
2. Generate load using the burst endpoints.
3. Record account generation count, snapshot metrics, and load-generator counters.
4. Re-run with snapshots disabled for `TradingAccount`.
5. Re-run with closing books disabled for `TradingAccount`.
6. Compare:
   - aggregate load behavior
   - snapshot save/load activity
   - number of account generations
   - how long each stream grows before rollover

Recommended concrete test recipe from a clean database:

1. Wipe the volume (`docker compose down -v`, see the note at the top), start the app with the `compose`
   profile, and wait for bootstrap to finish.
2. Open `/admin` and note the initial values for:
   - rolled over
   - total generations
   - max generation
   - `TradingAccounts` snapshot saves and loads
3. Stop the live generator:
   - `POST /api/admin/load-generator/stop`
4. Run a controlled trade workload:
   - `POST /api/admin/load-generator/burst/trade-lifecycles?count=500`
5. Refresh `/admin` and record:
   - generated trades
   - generated settlements
   - pending settlements
   - `TradingAccounts` snapshot saves
   - `TradingAccounts` snapshot loads
   - total generations and average generations/account
6. Run a controlled price workload:
   - `POST /api/admin/load-generator/burst/price-updates?count=500`
7. Refresh `/admin` again and compare:
   - whether snapshot loads continue to rise
   - whether generation stats remain stable or grow
   - whether the dashboard stays responsive while account/trade views still load quickly
8. Inspect one account endpoint:
   - `GET /api/admin/trading-accounts/ACC-DEMO-001`
   - compare current generation, prior generations, and balances
9. Optionally restart with one feature disabled and repeat the same sequence.

What to compare after each run:

- With snapshots enabled:
  - `essentials.aggregate_snapshot.load_snapshot` and `save_snapshot` should move upward under settlement-heavy load
  - snapshot loads should usually outnumber snapshot saves after repeated bursts
- With closing books enabled:
  - rolled over account count and total generations should be above the baseline seed state
  - accounts should not stay in a single forever-growing stream
- With snapshots disabled:
  - snapshot metrics should stay flat or disappear for `TradingAccounts`
  - account reads may feel slower after repeated bursts
- With closing books disabled:
  - generation counts should stay flat
  - account history should accumulate into fewer, longer streams

For deeper replay-cost comparison, the repository also already has the test-only profiling harness in the event-sourced aggregates module, which is a good complement to the runtime demo.

## Planned Next Steps

Useful next additions for the demo would be:

- richer account-level valuation and position tracking
- automation slices for the trade → settlement → account chain, which today is driven synchronously by the
  load generator rather than by reacting to `SettlementClosed` — the more faithful model, but it makes the
  chain eventually consistent and changes what the demo demonstrates (see `REFACTORING_PLAN.md`)
- easier toggles for snapshotting and closing-books scenarios in the local demo configuration

Time-travel style simulation could also be interesting, but it is probably better saved for a later iteration once the live usage and burst controls are in place.

## Notes

- The default profile stays test/container friendly and does not hardcode datasource settings.
- The `compose` profile is intended for local development runs against a local Docker daemon.
