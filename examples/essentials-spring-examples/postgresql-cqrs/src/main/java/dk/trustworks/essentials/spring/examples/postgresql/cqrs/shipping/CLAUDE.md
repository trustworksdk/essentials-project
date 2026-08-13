# Bounded context: shipping

Shipping an order: registering a shipping order, marking it shipped, and exchanging both facts with the
external order-management system over Kafka.

**Write style: aggregate (`rules/slice-design.md` §R5).** `ShippingOrder` is an `AggregateRoot` reached
through `ShippingOrders`, a `StatefulAggregateRepository` wrapper. Sanctioned lane — do not convert it to a
`Decider`.

## Slices

| Slice | Kind | Role |
|---|---|---|
| `use_cases/register_shipping_order` | command | Registers a new `ShippingOrder` |
| `use_cases/ship_order` | command | Marks it shipped |
| `external_systems/order_management` | translation | The Kafka anti-corruption boundary, both directions |
| `views/order_status` | view | Projects a named status per order |

## Two triggers, one slice

`ShipOrder` is raised from two places: `ShipOrderAPI` (HTTP) and `OrderEventsKafkaListener` (an
`OrderAccepted` arriving from order-management). That is one slice with two triggers, not two slices — a
command slice is identified by its command type, not by its transport.

## The context has a hole where Orders should be

`OrderId` lives here, but an *order* is not this context's concept — it belongs to the order-management
system, which is not part of this module. `external_systems/order_management/` is the seam.

The wire contracts in both directions carry a plain `String` id; `OrderId` exists only inside this
context, and the two adapters convert at the boundary. Do not "simplify" the DTOs to use `OrderId` —
that is the ACL not translating, and it is what this slice previously got wrong.

## Kafka trusted packages

`config/KafkaConfiguration` names this slice's incoming and outgoing packages explicitly. Two traps are
recorded there: a trailing `".*"` trusts strict *sub*packages only, so it excludes the package it names; and
the one `ConsumerFactory` also backs the integration test's consumer on the outbound topic, so both
directions must be trusted.

## Boundaries

The importable surface is `events/` and `types/`. `aggregates/` is BC-private. Nothing outside `shipping`
imports from it today.

## Directories beside the slices

- `events/` — the sealed `ShippingEvent` hierarchy, one variant per file
- `types/` — `OrderId`, `ShippingDestinationAddress`
- `aggregates/` — `ShippingOrder` and its repository wrapper
