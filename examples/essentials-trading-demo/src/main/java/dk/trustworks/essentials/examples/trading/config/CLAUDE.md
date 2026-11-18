# config — processor base classes on both engines

`trading-demo.engine=classic` (default) or `event-log` picks the event store engine. `@ClassicEngine` / `@EventLogEngine`
gate every bean that exists on only one of them. Each classic processor base class has a new-engine twin here:

| Classic                                   | New engine (twin)                              | Handler writes through |
|-------------------------------------------|------------------------------------------------|------------------------|
| `TradingDemoViewEventProcessor`           | `TradingDemoEventLogViewProcessor`             | `pageHandle()`         |
| `TradingDemoEventProcessor` (inbox)       | `TradingDemoEventLogInboxProcessor`            | its own unit of work, as on classic |
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
5. Reactions (inbox kind) are named `<Name>ClassicReaction` / `<Name>EventLogReaction`; handlers keep their classic
   shape (payload, optional `OrderedMessage`, `UnitOfWorkMode`), each in its own unit of work.
6. Partitioned views: the twin queues to `<name>:queue` keyed by aggregate; `UnitOfWorkMode.NONE` handlers are refused.
7. Tests: an `@ActiveProfiles("event-log")` IT per ported processor. `UnclearedDepositsEventLogViewIT` is the template;
   it `@MockitoBean`s the processors not yet ported that fail the event-log context (now only
   `InstrumentRiskApprovalProcessor`). Drop each mock once its processor is ported.
8. Classic-only hooks such as `onSubscriptionsReset` stay on the `ClassicView`, delegating to a package-private
   projection method (`UsersProjection.truncate()`). The event-log twin has no such hook.
9. **A handler that reads `OrderedMessage#getOrder()`** (the event's `EventOrder` in its stream) gets it as a `long`
   parameter on the projection: `on(Handle, <Event>, long eventOrder)`. The classic wrapper passes `message.getOrder()`,
   the twin `DeliveredEvent#eventOrder().longValue()` from its optional second parameter (`OrdersProjection`).

Unit of work on the view twin (proven by `UnclearedDepositsEventLogViewIT`): inside a page a Spring transaction is
active, `pageHandle()`'s connection is the page connection (`DataSourceUtils.getConnection`), auto-commit is off,
and the write commits with the page, together with the subscription's progress. No extra transaction code needed.
