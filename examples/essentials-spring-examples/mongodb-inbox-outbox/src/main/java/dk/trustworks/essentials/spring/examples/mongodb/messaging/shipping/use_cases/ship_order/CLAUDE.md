# Slice: shipping.ship_order

**Kind:** command   **Status:** live   **Owner:** shipping-team   **Tier:** service-entity
**Purpose:** Mark an already-registered `ShippingOrder` as shipped.

## Invariants
- An order is shipped at most once — `markOrderAsShipped()` reports a state change only when the order
  is not already shipped (enforced by `ShippingOrder`)
- The mutation is written back explicitly (enforced by `ShipOrderHandler`) — see below

## Two triggers, one slice
`ShipOrder` reaches this slice from **two** places, and that does not make it two slices — a command
slice is identified by its command type, not by its transport:
1. `POST /shipping/ship-order` via `ShipOrderAPI`.
2. The `external_systems/order_management` translation slice, which raises `ShipOrder` when an
   `OrderAccepted` arrives from order-management over Kafka.

Trigger 2 is why the idempotency check sits on the entity rather than in the handler: that path
delivers through an `Inbox` with an at-least-once guarantee, so the command can be handled twice.

## The `save()` is not optional
```java
if (existingOrder.markOrderAsShipped()) {
    shippingOrders.save(existingOrder);   // <- this line
    eventBus.publish(new OrderShipped(cmd.orderId()));
}
```

**Spring Data MongoDB does no dirty checking.** Unlike JPA there is no persistence context tracking the
loaded document, so without the explicit `save` the mutation is dropped, every redelivery reloads a
document that still says `shipped=false`, and a duplicate `OrderShipped` goes out to Kafka. The
`postgresql-inbox-outbox` sibling ran the identical handler body and was correct only because JPA
flushed it — this module was not. `ShippingFlowIT` pins the behaviour by sending `ShipOrder` twice and
asserting exactly one `ExternalOrderShipped`.

## Boundaries
**Reacts to:** `ShipOrder` off the `CommandBus` (directly, or forwarded by the `Inbox`).
**Publishes:** `OrderShipped` on the `EventBus` — picked up synchronously by the translation slice's
outbound half, which puts it in the `Outbox` inside the same transaction.
**Forbidden:**
  - Never import another slice's internals — only `shipping/events/` and `shipping/types/`
    (plus `shipping/entities/`).
  - Never move the idempotency check into this handler; a redelivery would then race itself.

## Data
**Owns (writes):** the `ShippingOrder` document.
**Reads:** the same document. The handler uses `getOrder`, not `findOrder`, so shipping an order that
was never registered fails rather than creating one.

## Files
- `ShipOrder.java` — the command; also the HTTP request body (§R2, no DTO)
- `ShipOrderHandler.java` — the slice's single `@Handler`
- `ShipOrderAPI.java` — `POST /shipping/ship-order`, this slice's only endpoint
