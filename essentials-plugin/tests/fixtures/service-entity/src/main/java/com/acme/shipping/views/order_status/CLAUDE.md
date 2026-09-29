# Slice: shipping.order_status

**Kind:** view · **Lane:** service-entity · **Status:** live · **Owner:** shipping-team

## Invariants
- Read-only. This slice never writes, and never touches `persistence/ShippingOrders`.
- The read is strongly consistent — same table, same transaction. That is what this lane buys.

## Boundaries
**Reads:** the `ShippingOrder` table, through its own `OrderStatusQueries` interface.
**Serves:** `GET /api/shipping/order-status`, `GET /api/shipping/order-status/{orderId}`.
**Forbidden:** injecting the write repository; returning the entity; calling `save`/`delete`.

## Note
`projections` is empty and that is correct on this lane — there is no projector and no separate read
model, so gates 10 and 13's twin machinery do not apply.
