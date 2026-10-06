# Slice: shipping.ship_order

**Kind:** command · **Lane:** service-entity · **Status:** live · **Owner:** shipping-team

## Invariants
- `ship-once` — `ShippingOrder.markOrderAsShipped()` returns `false` the second time. The guard lives
  on the entity because the entity is the consistency boundary.

## Boundaries
**Writes:** the `ShippingOrder` entity, through `persistence/ShippingOrders`.
**Publishes:** `OrderShipped` on the `EventBus`, only when the state actually changed.
