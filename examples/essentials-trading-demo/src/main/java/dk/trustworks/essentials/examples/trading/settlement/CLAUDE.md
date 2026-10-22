# Bounded context: settlement

A booked trade's settlement, as the exchange's settlement simulator reports it, booked on the `accounts` ledger.
Design: `docs/design/phase-4-settlement.md` (decisions §8). Simulators profile only: without the exchange nothing is
reported, and trades settle only through the ledger's operator endpoint.

**Write style: aggregate (§R5)** — `TradeSettlement` (stream `TradeSettlements`, id = `ExecutionId`) behind
`TradeSettlements`.

## Rules that are easy to break

- **The exchange's lifecycle** (§1, measured): pending → (delayed)* → completed; failed, cancelled and rejected are
  final (rejected comes without a pending); a correction follows a completion. Settled and abandoned are final here
  too, and the first wins.
- **An abandoned settlement reverses the booking** (`ReverseTradeBooking`, Q-1) and is an operator case
  (`GET /api/admin/settlements/exceptions`): the order still shows `FILLED`.
- **A corrected amount is `CorrectSettlementAmount` with new − previous** (Q-2); the ledger books cash, cost basis or
  realized P&L.
- **The account comes from `settlement`'s own projection of `orders`' `OrderFilled`** (`views/fills`). A report for a
  fill not projected yet - or a correction before its completion - throws `SettlementNotYetRecordable` (not an
  `IllegalArgumentException`), so `Settlement:Reports` redelivers it. A report for an execution this database never had
  (the exchange kept an older run's state) ends as a dead letter after 50 attempts: reset both together.
- **Subscription `demo-settlement`** in `simulators/exchange.yaml`, participant-filtered to the firm (SR-19): either
  side of a settlement involving us. Keep its `event_types` and `SettlementEventTranslation.EVENT_TYPES` in step.

## Slices

| Slice | Kind | Driven by |
|---|---|---|
| `external_systems/exchange` | external system | the exchange's `Settlement*` events (`POST /webhooks/exchange/settlements`, event log) |
| `record_settlement_report` | command | `external_systems/exchange` |
| `apply_settlement_to_account` | automation | settled / abandoned / corrected → `SettleTrade`, `ReverseTradeBooking`, `CorrectSettlementAmount` |
| `fills` | view | `record_settlement_report` |
| `settlements` | view | operator (`GET /api/admin/settlements[?accountId=]`, `/exceptions`) |
