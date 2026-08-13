# Trading demo — slice refactoring plan

Turns this module from feature-named-but-layered packages into the Essentials slice structure
(`rules/slice-design.md`), following the house template already established in
`examples/essentials-spring-examples/postgresql-cqrs`.

Produced by `/essentials:slice-discover` at `f5d5cc2a`, then applied. This file is the record of
**decisions taken in isolation** — the judgement calls that were made without asking, and the ones
that are still open. Read § Open questions before extending anything here.

---

## The lane does not change

The module is on the **aggregate write style** (§R5): five `AggregateRoot`s reached through
`StatefulAggregateRepository`. That is a sanctioned lane. Nothing here converts an aggregate into a
`Decider`, and nothing should later.

What changes is everything *around* the decision: where it lives, who may read it, and how a caller
reaches it.

---

## ⚠️ This refactor is not backward compatible with an existing demo database

Essentials persists an event's concrete **FQCN** in the event-type column; it does not use Jackson
polymorphic typing (there is no `@JsonTypeInfo` anywhere in this module). Moving the event classes to
new packages *and* flattening them from nested static classes into top-level records changes every
event type name:

```
…trading.accounts.TradingAccountEvent$TradingAccountOpened   →   …trading.trading.events.TradingAccountOpened
```

Stored events therefore become unreadable. **Drop the local volume and let the bootstrap reseed:**

```bash
docker compose down -v && docker compose up -d
```

The per-event JSON *payload* is unchanged (records keep the same component names, and every semantic
type introduced below serializes as the same scalar), so this is purely an event-type rename. No
upcasting is provided — this is a demo, and `TradingSimulationRunner` already detects partial seed
data and tells the operator to wipe.

---

## Target structure

```
examples/trading/
  TradingDemoApplication.java
  config/                                app-level only: security, Clock, error handler, web MVC
  brokerage/                             BC — accounts, trades, settlements
    aggregates/                          TradingAccount, Trade, Settlement + one repository wrapper each
    events/                              3 sealed parents, one variant per file (16 variants)
    types/                               ids + the new semantic types
    use_cases/<slice>/                   command slices: <Command>, <Command>Handler, <Command>API
    views/<slice>/                       view slices: projector (where owned), query, read shape, API
    config/                              BC wiring — aggregate types, repositories, closing books
  market_data/                           BC — instruments and prices
    aggregates/  events/  types/  use_cases/  views/  config/
  _demo_harness/                         NOT a slice — load generator, dashboard, benchmark store
    CLAUDE.md
```

`_demo_harness/` is the law's `_`-prefixed escape hatch (§ Directory vocabulary): excluded from slice
enumeration and from the §R4 boundary check, and carrying a `CLAUDE.md` saying why. The load
generator and the dashboard are demo scaffolding, not domain — classifying them as automations and
views would have been the bigger lie.

---

## Bounded contexts

### `brokerage` — accounts, trades, settlements

**Named `brokerage`, not `trading`.** The module's base package already ends in `.trading`
(`dk.trustworks.essentials.examples.trading`), so a context called `trading` would read
`…examples.trading.trading.aggregates.Trade`. `brokerage` is the standard name for the
account/trade/settlement side of this domain and keeps the package legible. This is a naming call
made in isolation — renaming it back is one `git mv` plus a package-statement sweep.

One context, three aggregates. The same call already spans all three in sequence
(`createPendingTradeAndSettlement`), they share the trading ubiquitous language, and no transaction
writes two of them.

**Decided against splitting `accounts` out**, though it has a real claim: the closing-books /
generation / archive lifecycle is self-contained, has its own admin surface, and shares no enforced
invariant with trades. It stays because `apply_trade_settlement` is driven by the settlement
lifecycle, so a split would put an automation across the boundary on day one for no gain.
Revisit if accounts grows a second reason to change.

### `market_data` — instruments and prices

Separate lifecycle, separate write cadence (high-frequency ticks), no shared invariant with trading.
`trading` imports `market_data`'s `InstrumentId` from `types/` — a legal cross-BC import (§R4).

---

## Semantic types

The demo was stringly-typed across its seams: ids crossed service boundaries as `String`, trade side
was the literal `"BUY"`, and every one of the 20 view records carried raw `String` ids. New types
live in their BC's `types/`.

| New type | Base | Replaces |
|---|---|---|
| `OwnerId` | `CharSequenceType` | `String ownerId` on `TradingAccount`, its event, the open command |
| `PeriodId` | `CharSequenceType` | `String periodId` / `nextPeriodId` throughout accounts |
| `TradeSide` | `enum { BUY, SELL }` | `String side` on `Trade`, `TradePlaced`, the place command |
| `Quantity` | `BigDecimalType` | `BigDecimal quantity` on trades |
| `SettlementStatus` | `enum` | the projection's `settlement_status` text column |
| `Symbol` | `CharSequenceType` | `String symbol` on `Instrument` |

Existing ids stop being stringified at seams: `Settlement` now holds `TradeId` and
`TradingAccountId` rather than `String`; `Trade` holds `SettlementId`; `TradeSettlementApplied`
holds `TradeId`.

**Money uses `dk.trustworks.essentials.types.Amount`** (a `BigDecimalType`, with `add`/`subtract`/
`multiply`/`negate`/`compareTo` inherited) for cash balances, reserved funds, realized P&L, gross
amounts, prices and deltas. **Not `Money`** — that carries a `CurrencyCode` the demo does not model,
and inventing a currency to satisfy a type would be worse than the raw `BigDecimal` it replaces.

All of these serialize as the same JSON scalar as the primitive they replace, so the event payload
format is unchanged (see the compatibility note above — only the *type name* moves).

### The one place a semantic type cannot flow

`BuiltInClosingBooksPolicyEvaluator` hardcodes `String` for the period:
`Function<AGGREGATE, String> currentPeriodIdProvider` and `String nextPeriodId(AGGREGATE)`. So
`PeriodId` is converted to and from `String` at exactly two call sites, both in the closing-books
policy component, and nowhere else. `TypedClosingBooksNextGenerationFactory<…, HINT>` *is* generic,
so the factory takes `PeriodId` directly.

---

## API-level semantic types

`types-spring-web` was **already a dependency and never registered**, which is the failure mode the
law calls out: a `CharSequenceType` `@PathVariable` surfaces as **HTTP 500**, not 400. Every existing
controller worked around it by taking `String` and calling `TradingAccountId.of(...)` in the handler.

`config/TradingDemoWebConfiguration` now `@Import`s `EssentialsWebMvcConfigurer`, so endpoints take
the semantic type directly:

```java
@GetMapping("/{accountId}")
public AccountOverview get(@PathVariable TradingAccountId accountId) { … }
```

Command slices take the command record as `@RequestBody` and send it on the command bus, per §R2's
"the command *is* the request body".

---

## Write path: the command bus

Every command slice is a `@Service` extending `AnnotatedCommandHandler` with one `@CmdHandler`
method, reached through the autoconfigured `DurableLocalCommandBus`. No new dependency and no new
wiring: `spring-boot-starter-postgresql-event-store` pulls `spring-boot-starter-postgresql`, whose
`EssentialsComponentsConfiguration` declares both the `essentialsCommandBus` bean and the
`ReactiveHandlersBeanPostProcessor` that auto-registers every `CommandHandler` bean. A
`UnitOfWorkControllingCommandBusInterceptor` is installed by default, so handlers need no
`@Transactional`.

This replaces the five `@Service` application services (`TradingAccountService`, `TradeService`,
`SettlementService`, `InstrumentService`, `InstrumentPriceService`), each of which had grown one
method per intent — R1's router one level above the aggregate.

The repository wrappers (`TradingAccounts`, `Trades`, `Settlements`, `Instruments`,
`InstrumentPrices`) follow the house rule from `postgresql-cqrs`: **a wrapper never constructs an
aggregate.** It persists one already built by the slice that decided to build it.

---

## Read path: off the write model

Every aggregate's state fields become `private`. This is safe for snapshots —
`EssentialsObjectMappers` sets `withFieldVisibility(ANY)` and `withGetterVisibility(NONE)`, so
private fields serialize and getters are ignored.

The three admin query services were rehydrating aggregates to answer reads, while two projections
already held the same data:

| Was | Now reads |
|---|---|
| `TradingAccountAdminQueryService` → `TradingAccount` fields | `projection_trading_account_statement` |
| `TradeAdminQueryService` → `Trade` fields | `projection_trade_settlement` |
| `SettlementAdminQueryService` → `Settlement` fields | `projection_trade_settlement` |

`trade_valuation` keeps its own read shape because it is a genuinely different model — it joins the
latest market price and computes market value and unrealized P&L over the same rows.

---

## Findings this refactor closes

Ranked as reported by `slice-discover`.

1. **Sole writer — the closing-books policy.** `TradingAccountClosingBooksPolicy` was mutated by four
   unguarded controller endpoints *and* by the harness's comparison scenario, which captured all five
   values and restored them in a `finally`. A POST landing mid-scenario returned 200 and was then
   silently reverted. Now: the settings are one immutable `ClosingBooksSettings` value behind a
   single `updateAndGet`-style owner, the harness's scenario overrides them through the same owner
   under the same lock the endpoints take, and a concurrent update during a scenario is rejected
   rather than lost.
2. **Sole writer — two price stores, no declared owner.** Left in place deliberately (it is the
   demo's whole point), but the aggregate store is now named the authoritative one, the benchmark
   store moves to `_demo_harness/`, and the trade path's dependence on the aggregate store is
   explicit instead of incidental. See § Open questions.
3. **Cohesion.** Five application services → 25 command slices. `TradingAccountAdminController`'s
   nine endpoints → four view slices and five command slices.
4. **Boundary.** Query services no longer read aggregate internals; `TradeAdminQueryService` no
   longer injects `market_data`'s write-side service; the dashboard no longer reaches into another
   slice's query service.
5. **R3.** Five god event files → five sealed parents with one variant per file.
6. **R2.** `archiveGeneration` moves out of a class named `QueryService` into a command slice.

Also fixed in passing: the hardcoded FQCN string
`"dk.trustworks.essentials.examples.trading.accounts.TradingAccount"` used as a Micrometer tag, which
would have silently stopped matching the moment the class moved. It is now
`TradingAccount.class.getName()`.

---

## Behaviour that changed

Structure was the goal; these are the places behaviour moved with it. Nothing here is a bug, but a reader
comparing before and after should know.

**Reads that were synchronous are now eventually consistent.** Both came off rehydrated aggregates and now
read `projection_trading_account_statement`:

- `GET /api/admin/trading-accounts/{accountId}` can 404 for an account that exists but is not yet projected.
  The generation lookup runs first, so a genuinely unknown account still fails the way it always did.
- The dashboard's account balances, and the load generator's seed-data check. `accountsPresent` can briefly
  lag `configuredAccountCount` after bootstrap.

`GET /api/admin/settlements/{id}` and `GET /api/admin/trades/{id}` now return **404** for an unknown id where
the aggregate-loading versions raised a 500.

**`TradeValuation` is priced as of the last projected price event**, not as of now.

That sentence originally continued "a price tick arriving before any trade exists on that instrument matches
no rows and is a correct no-op; the trade shows a null market price until the next tick." **That was wrong,
and it shipped as a bug in `TradeValuationProjection`.** `Trades` and `InstrumentPrices` are two independent
subscriptions and `GlobalEventOrder` sequences within one aggregate type, not across two — so the price can
be projected first. When it was, the `UPDATE` matched no rows, the row was then inserted with a `null` market
price, and nothing ever filled it in, because no mechanism replays an already-consumed tick. "Until the next
tick" quietly assumed a next tick, which continuous demo traffic always supplied.

It surfaced as an intermittent integration-test failure, and was initially mis-diagnosed twice: once as a
Jackson-flavour problem, and once by this refactor's own test pass, which raised the Awaitility ceiling from
30 s to 60 s and moved on. A projection that is wrong in one interleaving does not become right with a longer
timeout.

The slice now owns a second table, `projection_trade_valuation_price` — every tick upserts the latest price
per instrument, and `TradePlaced` seeds its row from it — so both interleavings produce the same row.
`a_trade_projected_after_its_instruments_price_ticks_is_still_valued` places a trade with no tick after it and
fails if the seeding is removed (verified by reverting it).

**The generalisable lesson:** a projection subscribing to more than one `AggregateType` gets no ordering
guarantee between them. Any handler that only *updates* rows another aggregate type is responsible for
creating has to cope with arriving first — usually by recording what it knows somewhere the other handler can
read.

**The aggregate price path is measured marginally heavier.** `commandBus.send(new UpdatePrice(…))` puts
command dispatch, handler lookup and the bus's `UnitOfWork` interceptor inside the timed window, where the
old code called a `@Transactional` service method directly. The direct-write path is unchanged, so the
price-path comparison is now slightly biased *against* event sourcing. Transaction count per step is
unchanged. Worth stating because that benchmark's whole point is the comparison.

**The closing-books benchmark still loads the aggregate**, deliberately — see `_demo_harness/CLAUDE.md`
§ Exception 2. Routing it through a projection would have zeroed every snapshot metric it reports.

**`SettlementStatusView`'s five booleans are now derived** from the `SettlementStatus` enum by lifecycle
order rather than read off the aggregate's own flags. Sound only because the enum is declared in order and
the aggregate's guards enforce that order — both facts are recorded on the record and in its slice
`CLAUDE.md`.

**`register_instrument` is not idempotent.** Re-sending it for an existing instrument fails on optimistic
concurrency rather than being a silent no-op, unlike the canonical `open_account` template which guards with
an existence check. The bootstrap never re-sends it, so this is latent rather than live.

**Admin UI.** The closing-books selects now carry enum constants (`END_OF_MONTH`) and the three sequential
POSTs became one. `postAndRefresh` grew an optional JSON body. The hyphenated form is *reported* by
`views/closing_books_configuration` and converted in the browser, rather than parsed on the write side — the
command is the wire contract (§R2), so a normalising layer in front of it would be the adapter that rule
forbids.

**A new `BigDecimalType` failed to deserialize, and only Jackson 2 said so — then the framework fixed it.**
`Quantity` was written with the obvious `(BigDecimal)` constructor. Four integration tests went red under
`-Pjackson2` while the Jackson 3 default stayed green: `types-jackson` registered a *serializer* for
`NumberType` but no matching deserializer, so Jackson fell back to creator detection, which picks by the JSON
token's own type and would not widen `"quantity":2` to `BigDecimal`. Every `TradePlaced` then failed **on
replay** — it serialized fine, so nothing showed until an existing stream was read back.

The workaround here was extra constructors. **That is no longer the fix, and this section is kept only as the
trail.** `0727f318` (*Deserialize NumberType instead of leaning on constructor shape*) added
`NumberTypeJsonDeserializer` / `NumberTypeJsonDeserializers`, a `Deserializers` SPI that resolves per concrete
`NumberType`, reads each value at its own width, and constructs through `SingleValueType.from(...)`. It also
owns the coercion rules — a fraction is refused by the integral bases rather than truncated, quoted numbers
stay readable. `NumberTypeCreatorRequirementTest` pins that a value-typed creator **alone** reads every
numeric token, and that a decimal is read at full precision rather than through a `double`.

So the current rule is the opposite of what this refactor concluded: **a `NumberType` subclass needs only its
value-typed constructor**, and `(long)` / `(double)` overloads are convenience, never part of the wire
contract. `Quantity` keeps `(long)` on that basis alone. `CharSequenceType` subclasses never had the problem —
their `(String)` constructor was already the delegating creator Jackson looked for.

## Two structures the discovery report proposed and this refactor did not build

Both were reconsidered against the code and dropped. Recording them so the next reader does not
"restore" them.

**No `automations/close_books_on_access/` slice.** The report proposed lifting the policy-driven
rollover out of `TradingAccountService.loadForMutation()` into an automation. An automation slice
reacts to an event and issues a command; this fires on *load*, before any event exists, and there is
nothing for it to subscribe to. It is `ClosingBooksTriggerMode.ON_ACCESS` — a load-time lifecycle
property of the aggregate — so it now lives on the repository wrapper as
`TradingAccounts.getAccountForMutation(…)`, beside the aggregate whose generations it rolls. Every
mutating account command goes through it, exactly as every one of them went through `loadForMutation`
before.

**No automation slices at all.** Nothing in this demo reacts to a domain event by issuing a command.
The trade → settlement → account chain that looks like a saga is driven by the load generator, in one
synchronous sequence. Turning it into a real automation (react to `SettlementClosed`, issue
`MarkTradeSettled` and `ApplyTradeSettlement`) would be the more faithful model and is the obvious
next step, but it makes the chain eventually consistent and changes what the demo demonstrates — a
behaviour change, not a restructuring, so it is out of scope here. Zero automations is the honest
count today.

## Open questions

Written down rather than guessed at. None of these blocks the refactor.

1. **Should `accounts` be its own bounded context?** Argued above; kept inside `trading`. The
   evidence is genuinely split and a reviewer who knows the intended domain should overrule this
   freely.
2. **Is the direct-write price store domain or harness?** It was moved to `_demo_harness/` because
   its only purpose is to be benchmarked against the aggregate path, and because
   `TradingSimulationRunner` seeds it purely so the comparison has something to compare. But
   `TradingSimulationRunner` is bootstrap, not benchmark, so an argument exists for it being a
   second, deliberately non-event-sourced write path inside `market_data`. If it stays in the
   harness, the trade path can never accidentally read it — which is the safer default and why it
   went there.
3. **Should every command slice expose an endpoint?** §R2 says a command slice has one endpoint.
   Most of these commands were previously reachable only from the bootstrap and the load generator.
   They now all have one, which enlarges the demo's public surface considerably but makes each slice
   independently exercisable — the point of the structure. If that surface is unwanted, the endpoint
   is the part to delete, not the slice.
4. **`SettlementStatus` duplicates the trade lifecycle booleans.** `projection_trade_settlement`
   carries both `settled boolean` and `settlement_status text`, which can disagree. Left as-is; a
   single status enum for both sides would be the better model but changes the projection's shape.
5. **The `#` stream-id convention is written in two places** — the coordinator lambda in the BC's
   config builds `<logicalId>#<generation>`, and the statement projection parses it back out with
   `lastIndexOf('#')`. Now a shared constant in `trading/types/`, but the parse is still positional.
6. ~~**`InstrumentPrice`'s `@AggregateSnapshotPolicy` does not actually take snapshots.**~~ **Resolved on
   `main`, and merged in:** the annotation was meant to be live. `InstrumentPrices` now resolves the
   `AggregateSnapshotRepositoryProvider` the same way `BrokerageConfiguration` does for `TradingAccount`,
   and `everyNEvents` dropped 1000 → 100 so a stress run actually crosses the threshold (1000 updates over
   2 instruments is only ~500 events per stream, so 1000 never fired and the console's snapshot metrics
   stayed empty). Note the consequence this entry originally warned about still holds and is now accepted:
   the price path benchmarks a *snapshotting* aggregate, so its numbers are not comparable to runs taken
   before this change.
7. **`reserveFunds` / `releaseFunds` have no caller outside bootstrap and one test.** Kept as slices
   because they carry real invariants (available-cash check, over-release check) and are the most
   interesting thing `TradingAccount` does. They are not dead code, but they are demo-only.
