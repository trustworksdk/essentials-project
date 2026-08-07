# Essentials Trading Demo

Small Spring Boot example app for validating:

- snapshots
- closing books
- typed logical ids vs internal generation ids
- starter auto-configuration in a realistic app

## Run Locally

From the repo root:

```bash
mvn -q -pl examples/essentials-trading-demo -Dspring-boot.run.profiles=compose spring-boot:run
```

This uses:

- [compose.yml](/Users/lassecramon/git/trustworks/essentials-project/examples/essentials-trading-demo/src/main/resources/compose.yml)
- [application-compose.yml](/Users/lassecramon/git/trustworks/essentials-project/examples/essentials-trading-demo/src/main/resources/application-compose.yml)

Spring Boot Docker Compose will start PostgreSQL automatically when the `compose` profile is active.

## Run From IntelliJ

Create a Spring Boot run configuration for:

- main class: `dk.trustworks.essentials.examples.trading.TradingDemoApplication`
- module: `examples/essentials-trading-demo`
- active profiles: `compose`

Then use Run or Debug as usual.

If Spring Boot Docker Compose integration does not automatically start PostgreSQL from IntelliJ in your setup, start [compose.yml](/Users/lassecramon/git/trustworks/essentials-project/examples/essentials-trading-demo/src/main/resources/compose.yml) manually first and keep the `compose` profile active in the run configuration.

## Useful Endpoints

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /admin`
- `GET /api/admin/dashboard`
- `GET /api/admin/dashboard/stream`
- `GET /api/admin/trading-accounts/{accountId}`
- `GET /api/admin/trading-accounts/{accountId}/archives`
- `POST /api/admin/trading-accounts/{accountId}/generations/{generation}/archive`
- `GET /api/admin/trading-accounts/closing-books`
- `POST /api/admin/trading-accounts/closing-books/mode?value=time-boundary`
- `POST /api/admin/trading-accounts/closing-books/time-boundary?value=end-of-month`
- `POST /api/admin/trading-accounts/closing-books/zone-id?value=Europe/Copenhagen`
- `POST /api/admin/trading-accounts/closing-books/interval-days?value=7`
- `GET /api/admin/trades/{tradeId}`
- `GET /api/admin/settlements/{settlementId}`
- `GET /api/admin/projections/account-statements`
- `GET /api/admin/projections/trade-settlements`
- `GET /api/admin/load-generator`
- `POST /api/admin/load-generator/start`
- `POST /api/admin/load-generator/stop`
- `POST /api/admin/load-generator/burst/trades?count=100`
- `POST /api/admin/load-generator/burst/settlements?count=100`
- `POST /api/admin/load-generator/burst/price-updates?count=500`
- `POST /api/admin/load-generator/burst/trade-lifecycles?count=100`
- `POST /api/admin/load-generator/price-stress/start?count=500&intervalMs=100`
- `POST /api/admin/load-generator/price-stress/stop`
- `POST /api/admin/load-generator/comparisons/price-path?count=100`
- `POST /api/admin/load-generator/comparisons/trading-account?count=90&readPasses=25&eventThreshold=20`

## What The Simulator Does

On startup, the simulator seeds a small but realistic demo dataset unless it detects that the seed data already exists.

It currently:

- registers demo instruments using realistic ticker symbols such as `AAPL`, `MSFT`, `NVDA`, `AMZN`, and `NOVO-B`
- opens one or more demo trading accounts like `ACC-DEMO-001`
- deposits cash, reserves and releases funds, and applies a few settled trades
- creates and completes settlement lifecycles for those trades
- initializes market prices per instrument and updates them while processing demo trades
- closes books for each account

If `rolloverAccounts=true`, the simulator also opens the next statement-period generation after closing books.
That means the admin endpoint may immediately show the current `TradingAccount` generation as `2`.

If `rolloverAccounts=false`, the simulator only closes generation `1` and does not open the next one.

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

To demo time-based rollover without restarting the app, you can also update the mode and boundary at runtime:

- `POST /api/admin/trading-accounts/closing-books/mode?value=time-boundary`
- `POST /api/admin/trading-accounts/closing-books/time-boundary?value=end-of-week`
- `POST /api/admin/trading-accounts/closing-books/zone-id?value=Europe/Copenhagen`

The dashboard includes a small control for this too.

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
- `POST /api/admin/load-generator/price-stress/start?count=500&intervalMs=100`
  starts an asynchronous high-frequency price stress run and returns immediately
- `POST /api/admin/load-generator/price-stress/stop`
  stops the current asynchronous price stress run
- `POST /api/admin/load-generator/comparisons/price-path?count=100`
  runs the same number of price updates through `aggregate-event-sourced` and `direct-write` back-to-back and stores the latest comparison result on `/admin`
- `POST /api/admin/load-generator/comparisons/trading-account?count=90&readPasses=25&eventThreshold=20`
  runs the same TradingAccount mutation and repeated-read workload twice, once with `manual-only` and once with `event-count`, and stores the latest comparison result on `/admin`

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

The compose profile now also enables the first archive slice:

- `essentials.eventstore.archives.enabled=true`

That gives the demo a default filesystem-backed archive pipeline for closed generations.

Useful endpoints:

- `GET /api/admin/trading-accounts/{accountId}/archives`
- `POST /api/admin/trading-accounts/{accountId}/generations/{generation}/archive`

Example:

```bash
curl -X POST 'http://localhost:8080/api/admin/trading-accounts/ACC-DEMO-001/generations/1/archive'
curl 'http://localhost:8080/api/admin/trading-accounts/ACC-DEMO-001/archives'
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
  backed by a durable `ViewEventProcessor` projection of the latest trading-account statement state
- `GET /api/admin/projections/trade-settlements`
  backed by an `EventProcessor` that combines trade and settlement lifecycle state into one read model

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

Functional checks:

- use `GET /api/admin/trading-accounts/{accountId}` to confirm that `TradingAccount` rolls across generations
- use `GET /api/admin/settlements/{settlementId}` to confirm that `Settlement` reaches its explicit closed lifecycle
- use `GET /api/admin/trades/{tradeId}` to inspect price-driven valuation after trade bursts and price-update bursts

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

1. Start the app with the `compose` profile and wait for bootstrap to finish.
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
- a small dashboard page tying together account, trade, settlement, and load-generator stats
- easier toggles for snapshotting and closing-books scenarios in the local demo configuration

Time-travel style simulation could also be interesting, but it is probably better saved for a later slice once the live usage and burst controls are in place.

## Notes

- The default profile stays test/container friendly and does not hardcode datasource settings.
- The `compose` profile is intended for local development runs against a local Docker daemon.
