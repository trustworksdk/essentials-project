# Slice: shipping.ship_order

**Kind:** command   **Status:** live   **Owner:** shipping-team
**Purpose:** Mark an already-registered `ShippingOrder` as shipped.

## Invariants
- An order is shipped at most once — `markOrderAsShipped()` applies `OrderShipped` only when the
  aggregate is not already shipped (enforced by `ShippingOrder`)

## Two triggers, one slice
`ShipOrder` reaches this slice from **two** places, and that does not make it two slices — a command
slice is identified by its command type, not by its transport:
1. `POST /shipping/ship-order` via `ShipOrderAPI`.
2. The `external_systems/order_management` translation slice, which raises `ShipOrder` when an
   `OrderAccepted` arrives from order-management over Kafka.

Trigger 2 is why the idempotency check sits on the aggregate rather than in the handler: that path
delivers through an `Inbox` with an at-least-once guarantee, so the command can be handled twice.

## Boundaries
**Reacts to / reads:** `ShipOrder` off the `CommandBus`; loads the `ShippingOrder` aggregate.
**Publishes:** `OrderShipped`.
**Forbidden:**
  - Never import another slice's internals — only `shipping/events/` and `shipping/types/`
    (plus `shipping/aggregates/`, this BC's §R5 aggregate style).
  - Never add this slice's logic to a shared decider/controller/event file.

## Data
**Owns (writes):** the `ShippingOrder` aggregate stream (`AggregateType` `ShippingOrders`).
**Reads:** the same aggregate. `ShipOrderHandler` uses `getOrder`, not `findOrder`, so shipping an
order that was never registered fails rather than creating one.

## Files
- `ShipOrder.java` — the command; also the HTTP request body (§R2, no DTO)
- `ShipOrderHandler.java` — the slice's single `@CmdHandler`
- `ShipOrderAPI.java` — `POST /shipping/ship-order`, this slice's only endpoint
