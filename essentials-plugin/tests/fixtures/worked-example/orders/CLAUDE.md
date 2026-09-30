# Bounded Context: orders

> **Teaching example.** This `orders` BC is a reference skeleton that demonstrates the
> per-slice / anti-god-class structure and the **standard Essentials Decider/Evolver
> design**. Replace it with your real bounded context(s). It needs the
> `pg-event-sourced` profile (event store + PostgreSQL DocumentDB); for `pg-crud`/`mongo`
> adapt or remove it.

## What this BC owns
The `Order` aggregate and its lifecycle: place → (cancel).

## Slice index
| Slice | Kind | Owns | Public events |
|-------|------|------|---------------|
| `use_cases/place_order` | command | `PlaceOrderDecider`, `PlaceOrderAPI` | `OrderPlaced` |
| `use_cases/cancel_order` | command | `CancelOrderDecider`, `CancelOrderAPI` | `OrderCancelled` |
| `views/order_list` | view | `OrderListProjection`, `OrderListRepository`, `OrderListAPI` | — (reads events) |
| `automations/screen_order` | automation | `ScreenOrderProcessor`, `ScreenOrderTodo` | — (sends `CancelOrder`) |
| `external_systems/warehouse` | translation (outbound) | `WarehousePublisher`, `WarehouseTranslator`, `WarehouseClient` | — (calls the warehouse) |

## Structure & rules (rules/slice-design.md)
- **One Decider per command slice.** `place_order` and `cancel_order` each have their
  OWN `Decider<COMMAND, OrderEvent>` — there is no `OrderDecider` routing
  both commands. That god-Decider is the anti-pattern this layout prevents.
- **Split events.** `events/OrderEvent.kt` is the sealed parent only; each variant
  (`OrderPlaced`, `OrderCancelled`) is its own file in `events/`, logically owned by its
  emitting slice. No single god event-file.
- **One API file per slice, owned by that slice.** Three controllers here, never a multi-endpoint
  `OrderController`. A *command* slice's API holds exactly one method; a *view* slice's API may hold
  several queries over its **own** read model (filter, sort, paginate) — a query needing other
  events, or a different read-model shape, is a different slice.
- **State is per-slice by default.** `cancel_order/` folds the stream with its own
  `OrderStateEvolver`; `place_order/` needs no folded state at all. `use_cases/_shared/`
  does not exist here and should not until **three** Deciders need the same state, none
  needing a field the others do not — sharing is coupling, and a shared `State` drifts
  toward the union of everyone's needs. Promotion is then a plain move that keeps both
  type names. Slices never reach into each other's Deciders or Evolvers.
- **Standard Essentials design.** Deciders implement `Decider<COMMAND, EVENT>`; the
  evolver implements `Evolver<EVENT, STATE>`; `config/OrdersConfiguration` registers the
  `AggregateTypeConfiguration` and one `@Bean` per Decider, and the application's single
  `DeciderAndAggregateTypeConfigurator` (`DeciderWiring`, outside every BC) collects them. Do not hand-roll a
  custom aggregate/decider hub — consult the `essentials-docs` skill.

## Layout
```
orders/
  events/            OrderEvent.kt (sealed parent) + OrderPlaced.kt + OrderCancelled.kt
  routing/           OrderCommand.kt (aggregate routing interface, BC-private)
  types/             OrderId.kt, OrderStatus.kt
  use_cases/
    place_order/     PlaceOrder, PlaceOrderDecider, PlaceOrderAPI (+ CLAUDE.md, slice.yaml)
    cancel_order/    CancelOrder, CancelOrderDecider, CancelOrderAPI,
                     OrderState + OrderStateEvolver (per-slice) (+ CLAUDE.md, slice.yaml)
  views/
    order_list/      OrderListView, OrderListRepository, OrderListProjection, OrderListAPI
                     (+ CLAUDE.md, slice.yaml)
  automations/
    screen_order/    ScreenOrderProcessor, ScreenOrderTodo, ScreenOrderRepository (+ CLAUDE.md, slice.yaml)
  external_systems/
    warehouse/       WarehousePublisher, WarehouseTranslator, WarehouseClient (+ CLAUDE.md, slice.yaml)
  config/            OrdersConfiguration.kt (@Bean per Decider + AggregateTypeConfiguration)
```
