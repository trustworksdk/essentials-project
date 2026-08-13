# Slice: brokerage.trade_settlement_status

**Kind:** view   **Status:** live   **Owner:** brokerage-team
**Purpose:** Join each trade to its settlement's lifecycle state in one read model, and serve one settlement
from it.

## The demo's multi-stream join

A `Trade` and its `Settlement` are two consistency boundaries with two event streams, and no transaction
writes both. Their *combined* state exists nowhere on the write side — this projection is where it comes
into being. That is why it subscribes to two `AggregateType`s, and why it is the one place in the context
where a single row is written from two independent streams.

Consequences, all deliberate:
- **Either side may arrive first.** Both `TradePlaced` and `SettlementCreated` upsert, and the settlement
  side sets only the columns a settlement knows about — hence a row where instrument, side, quantity and
  price are still `NULL`.
- **`settlement_id` gets a partial unique index**, not a second primary key: unique when present, absent for
  a trade with no settlement yet.
- **`onSubscriptionsReset` truncates the whole table** on each of the two aggregate types. A row half-owned
  by trades and half by settlements cannot be deleted per aggregate type.

## Invariants
- Redelivery restates the row rather than compounding: every statement is an upsert or an idempotent flag or
  status assignment. Nothing here is a `+= delta` (enforced by `TradeSettlementStatusProjection`).
- `settlement_status` round-trips through the `SettlementStatus` enum — written as `name()`, read with
  `valueOf`. The pre-slice version passed bare string literals in both directions.
- The five booleans on `SettlementStatusView` are **derived** from the status by ordinal, never stored.
  That is only sound because `SettlementStatus` is declared in lifecycle order and the aggregate's guards
  enforce that order — reordering the enum silently rewrites them.

## Boundaries
**Reacts to / reads:** all four `TradeEvent`s and all six `SettlementEvent`s.
**Publishes:** nothing — a view slice never produces events.
**Forbidden:**
  - Never import another slice's internals — only `brokerage/events/`, `brokerage/types/`,
    `market_data/types/` and the `AggregateType` constants off `Trades` / `Settlements`.
  - Never rehydrate the `Settlement` aggregate to answer the single-settlement query. That is what this
    slice replaced.

## Consistency

Reads are **eventually consistent**: `TradeSettlementStatusProjection` is an asynchronous `EventProcessor`.
`GET /api/admin/settlements/{settlementId}` returns **404** for both "no such settlement" and "created, not
projected yet" — from here the two are indistinguishable, and the pre-slice version, which loaded the
aggregate, could not express the second state at all.

## Data
**Owns (writes):** `projection_trade_settlement` and its partial unique index, both created by the
projection's constructor. The table name is hardcoded in both files — never concatenate it.
**Reads:** its own read model only. The read model *is* the response body.

## Processor name is load-bearing

`getProcessorName()` returns `"TradeSettlementProjection"` — the pre-slice class name, not the new one. It is
the key subscription progress is stored under; renaming it silently replays from the beginning of time.

## Files
- `TradeSettlementStatus.java` — one row of the read model / the list response body
- `SettlementStatusView.java` — one settlement, with the derived booleans
- `TradeSettlementStatusProjection.java` — the `EventProcessor`; owns the DDL, the index and the truncate
- `TradeSettlementStatusQuery.java` — the two `JdbcTemplate` queries and the null-tolerant row mapper
- `TradeSettlementStatusAPI.java` — the slice's two endpoints
