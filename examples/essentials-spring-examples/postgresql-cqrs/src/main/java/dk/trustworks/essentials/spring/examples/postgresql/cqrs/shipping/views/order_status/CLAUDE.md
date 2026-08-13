# Slice: shipping.order_status

**Kind:** view   **Status:** live   **Owner:** shipping-team
**Purpose:** Answer "where is my order" from a read model carrying a named status per shipping order.

## Three queries, one slice
`listOrders()`, `getOrderStatus(orderId)` and `findOrdersByStatus(status)` all interrogate the *same*
read model, which this slice owns — exactly what §R2 groups into one slice. Splitting them would give
three slices sharing one read model (the R4 ownership violation) or three projections of the same
events.

## Boundaries
**Reacts to / reads:** `ShippingOrderRegistered` and `OrderShipped`, off the `ShippingOrders`
aggregate type, through a `ViewEventProcessor`. Reads nothing but its own `OrderStatusView`.
**Publishes:** nothing — a view slice never produces events.
**Forbidden:**
  - Never import another slice's internals — only `shipping/events/` and `shipping/types/`.
  - Never write to an aggregate, and never let another slice query this read model directly.

## Data
**Owns (writes):** `OrderStatusView`, a DocumentDB entity in table `shipping_order_status`, with a
`status` index added once in `OrderStatusRepositoryConfiguration`.
**Consistency:** eventual. The projection is asynchronous, so a read straight after
`POST /shipping/ship-order` may still report `REGISTERED`.

## Why `status` is a projection concept
`ShippingOrder` holds a `boolean shipped` — that is all its invariant needs. A caller wants a *name*
for the state. That difference is the reason this is a projection rather than a getter on the
aggregate, which the aggregate-style bar forbids.

## Idempotency
Every `@MessageHandler` takes `OrderedMessage` and compares `message.getOrder()` against the stored
version before writing, because `OrderShipped` redelivery is expected rather than exceptional.
`onSubscriptionsReset` wipes the model so a subscription reset replays cleanly.

## The endpoint takes the semantic type, not a `String`

`@PathVariable OrderId` binds directly. That needs `types-spring-web`'s `SingleValueTypeConverter`, registered
by `config/WebConfiguration` importing `EssentialsWebMvcConfigurer` - the dependency alone does nothing,
it is not auto-configuration. Dropping that import turns these endpoints into HTTP **500**s
(`ConversionNotSupportedException`), not 400s, so the symptom does not point at the cause.

## Files
- `OrderStatusView.java` — the read model; also the HTTP response body (§R2, no DTO)
- `OrderStatusProjection.java` — `ViewEventProcessor` building it
- `OrderStatusRepositoryConfiguration.java` — repository + `status` index wiring
- `OrderStatusAPI.java` — the three `GET /shipping/orders…` endpoints
