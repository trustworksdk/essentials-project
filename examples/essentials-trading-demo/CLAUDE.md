# essentials-trading-demo

Spring Boot demo for the PostgreSQL EventStore: snapshots, closing books, generation archival, and a
price-path benchmark. Not part of the release.

Packaged by **vertical slice**, following the same law as `postgresql-cqrs` — see that module's
`CLAUDE.md` and the essentials plugin's `rules/slice-design.md`. `REFACTORING_PLAN.md` records the
decisions taken when this module was converted from layered packages, and the open questions.

```bash
mvn verify -pl :essentials-trading-demo                 # unit + ITs (needs Docker)
mvn -Pjackson2 verify -pl :essentials-trading-demo -am  # other Jackson flavour; -am is required
mvn spring-boot:run -pl :essentials-trading-demo        # after `docker compose up -d`
```

## Bounded contexts

| BC | Aggregates | Slices |
|---|---|---|
| `brokerage` | `TradingAccount`, `Trade`, `Settlement` | 19 command, 6 view |
| `market_data` | `Instrument`, `InstrumentPrice` | 5 command, 2 view |

Both on the **aggregate write style** (§R5) — `AggregateRoot` + `StatefulAggregateRepository`. Sanctioned
lane. Do **not** convert to `Decider`s.

`_demo_harness/` is not a slice — load generator, bootstrap runner, dashboard, benchmark price store.
`_`-prefixed, excluded from slice enumeration. Has its own `CLAUDE.md`.

## Gotchas

- **Event-type FQCNs changed in the slice refactor.** Essentials persists the concrete class name; no
  upcasting is provided. An existing demo database is unreadable — `docker compose down -v` first.
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
- **`EssentialsWebMvcConfigurer` + `EssentialTypesJacksonModule` are registered in
  `config/TradingDemoWebConfiguration`.** Neither is auto-configuration. Without the first, a typed
  `@PathVariable` is an HTTP 500; without the second, a semantic type in a request/response body has no
  serializer.
- **Command bus and handler registration are free.** `spring-boot-starter-postgresql` supplies
  `essentialsCommandBus` and `ReactiveHandlersBeanPostProcessor`; `@Service extends AnnotatedCommandHandler`
  is the whole wiring. No `@Transactional` on handlers — the bus owns the UnitOfWork.

## Admin UI

`src/main/resources/static/admin/index.html`, vanilla JS, no build step. Its select values are enum
constants (`END_OF_MONTH`), converted from the dashboard's hyphenated display form on load. Closing-books
settings are one atomic `POST /api/admin/trading-accounts/closing-books` with a JSON body; null field means
unchanged.
