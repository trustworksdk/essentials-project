# Slice: shipping.register_shipping_order

**Kind:** command · **Lane:** service-entity · **Status:** live · **Owner:** shipping-team

## Invariants
- An order is registered once; re-registering the same id is rejected by the primary key.

## Boundaries
**Writes:** the `ShippingOrder` entity, through `persistence/ShippingOrders`.
**Publishes:** `ShippingOrderRegistered` on the `EventBus`.
**Forbidden:** reaching into another slice's internals; querying the read side.
