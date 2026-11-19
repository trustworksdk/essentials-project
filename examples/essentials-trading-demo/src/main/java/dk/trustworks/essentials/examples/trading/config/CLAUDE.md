# config — processor base classes on both engines

`trading-demo.engine=classic` (default) or `event-log` picks the event store engine. `@ClassicEngine` / `@EventLogEngine`
gate every bean that exists on only one of them. Each classic processor base class has a new-engine twin here:

| Classic                                   | New engine (twin)                              | Handler writes through |
|-------------------------------------------|------------------------------------------------|------------------------|
| `TradingDemoViewEventProcessor`           | `TradingDemoEventLogViewProcessor`             | `pageHandle()`         |
| `TradingDemoEventProcessor` (inbox)       | `EventLogInboxProcessor` (framework)           | its own unit of work, as on classic |
| `TradingDemoPartitionedViewEventProcessor`| `TradingDemoEventLogPartitionedViewProcessor`  | `messageHandle()`      |

## One handler body, two engine wrappers

Porting a processor (pilot: `accounts/views/uncleared_deposits/`):

1. **Engine-neutral bean keeps the class name** (`UnclearedDepositsProjection`): plain `@Service`, no processor base
   class, no engine annotation. It keeps the DDL, the queries other code calls, and one package-private
   `on(Handle, <Event>)` per handled event holding the old handler body. It never opens a transaction.
2. **`<Name>ClassicView`** (`@ClassicEngine @Service`, extends the old base class): carries `@IgnoresEventTypes` and
   every other classic-only annotation; each `@MessageHandler` delegates with `getRequiredUnitOfWork().handle()`.
3. **`<Name>EventLogView`** (`@EventLogEngine @Service`, extends the twin): same `@MessageHandler`s, delegating with
   `pageHandle()` (view) or `messageHandle()` (partitioned view). No `@IgnoresEventTypes`: unhandled events are skipped.
4. **The processor name stays the classic one** — pass the old processor's name (for a view: the old class name,
   e.g. `"UnclearedDepositsProjection"`) to the twin's constructor, so admin views, logs, inbox and queue names match.
5. **Reactions (inbox kind)** (pilot: `market_data/use_cases/risk_approve_instrument/`): the engine-neutral bean keeps
   the class name (`InstrumentRiskApprovalProcessor`), drops the base class, and holds package-private `on(<Event>)`
   bodies plus `PROCESSOR_NAME` / `AGGREGATE_TYPES` constants the wrappers share. `getCommandBus()` becomes an injected
   `CommandBus`; `usingUnitOfWork` / `withUnitOfWork` go through an injected `EventStoreUnitOfWorkFactory`. The wrappers
   `<Name>ClassicReaction` (extends `TradingDemoEventProcessor`) and `<Name>EventLogReaction` (extends
   the framework's `EventLogInboxProcessor`, in `postgresql-event-log-processor`) carry the `@MessageHandler`s with the
   classic `UnitOfWorkMode`, so the transaction boundary stays on the wrapper. The twin's constructor takes the aggregate
   types as names (`AGGREGATE_TYPES.stream().map(AggregateType::toString).toList()`). A parallel-consumers override is
   `getNumberOfParallelInboxMessageConsumers()` on classic, `numberOfParallelInboxMessageConsumers()` on the twin.
   The framework class forwards a whole page per transaction (`CONTIGUOUS`, from the beginning as classic's
   subscription): it enqueues every event of the page inside the page's transaction, so the forward commits atomically
   with the progress - never open a unit of work per event there. The page size is `forwardPageSize()` (default
   `essentials.event-log.page-size`); a classic `getForwardBatchSize()` override becomes a `forwardPageSize()` override
   returning the same property (`OrderLedgerEventLogReaction`, `ExchangeOrderRouterEventLogReaction`).
6. **Partitioned views** (pilot: `accounts/views/account_overview/`): same split, wrappers `<Name>ClassicView` (extends
   `TradingDemoPartitionedViewEventProcessor`) and `<Name>EventLogView` (extends the twin, which also takes
   `DurableQueues`). Handlers keep the `(<Event>, OrderedMessage)` shape on both; the projection gets
   `on(Handle, <Event>, long eventOrder)` and each wrapper passes `message.getOrder()` - on the twin the message's key is
   the plain aggregate id and its order the event's `EventOrder`, as on classic. The twin writes through
   `messageHandle()`, the unit of work each queued message is handled in. It forwards every event it has a handler for
   to `<name>:queue` keyed by `<aggregateType>|<id>` (the classic queue name and key), consumed on every instance with
   `trading-demo.views.partitioned-consumers` threads, so per-account order and the spread over instances match classic.
   `trading-demo.views.partitioned-forward-payloads` is classic-only: the twin always forwards the payload.
   `UnitOfWorkMode.NONE` handlers are refused on both.
7. Tests: an `@ActiveProfiles("event-log")` IT per ported processor. `UnclearedDepositsEventLogViewIT` is the template;
   no processor needs mocking any more. `PaymentRouterEventLogReactionIT` is the reaction template (event log ->
   forwarder -> inbox -> handler, spying on the external gateway).
8. Classic-only hooks such as `onSubscriptionsReset` stay on the `ClassicView`, delegating to a package-private
   projection method (`UsersProjection.truncate()`). The event-log twin has no such hook.
9. **A handler that reads `OrderedMessage#getOrder()`** (the event's `EventOrder` in its stream) gets it as a `long`
   parameter on the projection: `on(Handle, <Event>, long eventOrder)`. The classic wrapper passes `message.getOrder()`,
   the twin `DeliveredEvent#eventOrder().longValue()` from its optional second parameter (`OrdersProjection`).

Unit of work on the view twin (proven by `UnclearedDepositsEventLogViewIT`): inside a page a Spring transaction is
active, `pageHandle()`'s connection is the page connection (`DataSourceUtils.getConnection`), auto-commit is off,
and the write commits with the page, together with the subscription's progress. No extra transaction code needed.

## Classic-only by decision

The three EF-2 measurement processors in `_demo_harness/` (`NoOpAccountsEventProcessor`, `NoOpAccountsViewProcessor`,
`BatchedAccountsForwarder`) stay `@ClassicEngine` and are not ported: they measure the classic engine's forwarding
paths and only exist behind a `trading-demo.ef2.*` flag (`docs/plans/event-store-engine-phase10.md` §6.3). Every other
processor has both wrappers; `TradingDemoEventLogIT` starts the whole demo on the `event-log` profile and asserts that
no classic processor bean and no `EventStoreSubscriptionManager` exist there.
