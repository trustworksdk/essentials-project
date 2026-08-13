# Bounded context: brokerage

The client-facing side of the trading demo: a customer's trading account, the trades placed against it, and the
post-trade settlement that books the result back onto the account. Instruments and prices are the *other* context
(`market_data`); the only thing that crosses is `InstrumentId`.

**Write style: aggregate (`rules/slice-design.md` §R5).** Decisions live in `aggregates/`, on `TradingAccount`,
`Trade` and `Settlement` — all three `AggregateRoot`s reached through a repository wrapper. This is a sanctioned
lane, not a legacy one — do **not** convert these to `Decider`s. Command slices still own everything else: their
command type, their one API file, their handler and their test.

## Slices

19 command slices and 6 view slices. **No automations** — nothing in this demo reacts to an event by issuing a
command; the trade → settlement → account chain is driven synchronously by the demo harness. See
`REFACTORING_PLAN.md` for why that was left alone.

| Group | Slices |
|---|---|
| Account commands | `open_trading_account`, `deposit_cash`, `reserve_funds`, `release_funds`, `apply_trade_settlement`, `close_books`, `close_books_and_open_next_period`, `archive_generation`, `update_closing_books_settings` |
| Trade commands | `place_trade`, `execute_trade`, `request_settlement`, `mark_trade_settled` |
| Settlement commands | `create_settlement`, `request_clearing`, `confirm_clearing`, `mark_settlement_settled`, `reconcile_settlement`, `close_settlement` |
| Views | `account_statement` (2 queries), `trade_settlement_status` (2), `trade_valuation`, `account_generation_events`, `account_generation_archives`, `closing_books_configuration` |

`trade_valuation` projects `market_data`'s `PriceInitialized`/`PriceUpdated` into its own table so a trade can be
valued at the current market price. Projecting a foreign context's events is the law's answer to a cross-context
read; injecting that context's write side was the §R4 violation it replaced.

## Why three aggregates, one context

Each is its own consistency boundary — no transaction writes two of them. What binds them into one context is the
trade lifecycle: a `Trade` names a `SettlementId`, the `Settlement` under it carries the trade's gross amount, and
`TradeSettlementApplied` books the result onto the `TradingAccount`. The ids flow through all three event streams so
each side can correlate without sharing a transaction.

## `TradingAccount` is keyed on a generation, not on the account

The one thing to understand before touching this context. A trading account **closes its books**: rather than
mutating into a new accounting period, it seals its event stream and opens the next one.

- `TradingAccountId` — the stable business id a caller, projection or endpoint holds. Spans every generation.
- `TradingAccountGenerationId` — `<accountId>#<generation>`, the id one generation's stream is keyed on.

`TradingAccounts` wraps a `ClosingBooksLogicalAggregateRepository`, which is what maps one to the other, so callers
never see a generation id. `TradingAccountNextGenerationFactory` decides what carries across a rollover (owner and
cash carry, realized P&L resets, reserved funds are zeroed by `AccountBooksClosed` on the way out) — it is a
required bean, not decoration: without it a rollover opens the next generation from nothing.

The `#` convention lives in `TradingAccountGenerationId.of(logicalId, generation)` / `.generation()` and nowhere
else. It used to be open-coded in two places — the coordinator concatenated it, a projection re-parsed it — which
could drift apart silently. Same reason `SettlementId.forTrade(tradeId)` exists: `tradeId + "-SET"` was written out
by hand in three places.

## Settings are one value behind one lock

`TradingAccountClosingBooksPolicy` holds a single immutable `ClosingBooksSettings` guarded by a `ReentrantLock`, not
five `volatile` fields with a setter each. Five independent writes are not one write: a reader could see a new
`mode` against the old `timeBoundary`, and the load harness's policy-comparison scenario and the admin endpoints
could interleave into a combination neither asked for. `withTemporarySettings` holds the lock for the *whole*
action, which is what makes an override actually exclusive. Do not reintroduce per-field setters.

**The hyphenated string form is gone from the write side, on purpose.** The old endpoints accepted
`end-of-month` and normalised via `valueOf(s.trim().replace('-','_').toUpperCase())`.
`use_cases/update_closing_books_settings` takes the enums directly instead — the command *is* the wire contract
(§R2), so a normalising layer in front of it would be the adapter that rule forbids. Callers send the exact
constant (`END_OF_MONTH`); the admin UI converts on load, since `views/closing_books_configuration` still
*reports* the hyphenated form for display. Do not reintroduce string parsing here.

## The period is `PeriodId`, except at two seams

`BuiltInClosingBooksPolicyEvaluator` is `String`-typed for the period. The conversion happens at exactly two points
in `TradingAccountClosingBooksPolicy`: the `currentPeriodIdProvider` lambda handed to the evaluator, and the return
of `nextPeriodId`. Nowhere else in this context is a period a bare `String`.

## Boundaries

The importable surface of this context is `events/` and `types/`, and nothing else. `aggregates/` is BC-private —
`TradingAccount`'s `ownerId()`, `periodId()` and `cashBalance()` are deliberately package-private, which is why the
next-generation factory and the closing-books policy live in `aggregates/` beside it. The aggregate has no public
getters at all.

Into this context, `market_data.types.InstrumentId` is the only foreign import.

## Directories beside the slices

- `events/` — the three sealed hierarchies, `TradingAccountEvent`, `TradeEvent` and `SettlementEvent`, one variant per file
- `types/` — ids, value objects and enums, plus the immutable `ClosingBooksSettings`
- `aggregates/` — the three aggregates, their repository wrappers, and the two closing-books collaborators
- `config/` — Spring wiring; currently only `TradingAccountClosingBooksProperties`

## Money is `dk.trustworks.essentials.types.Amount`

Do not add a `Money` type. `Quantity` is separate from `Amount` on purpose — multiplying the two is what produces a
gross amount, and keeping them distinct is what stops the two being added.

**`Quantity` needs only its value-typed `(BigDecimal)` constructor.** `NumberTypeJsonDeserializers` resolves a
deserializer for every concrete `NumberType` on both flavours, reads the number at the width the type wraps, and
constructs through `SingleValueType.from(...)`, so convenience overloads are never part of the wire contract. The
`(long)` overload is there for call sites and nothing else.

This was a genuine trap before that SPI landed: a `BigDecimalType` with only the natural constructor serialized fine
and failed on replay, because Jackson picked a creator by JSON token type and would not widen `"quantity":2` to
`BigDecimal`. The demo briefly carried extra constructors and a long comment about it. See
`LLM/LLM-types-jackson.md` → *NumberType deserialization* for the current rules, including the coercion table.
