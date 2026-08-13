# Slice: shipping.order_status

**Kind:** view   **Status:** live   **Owner:** shipping-team   **Tier:** service-entity
**Purpose:** Answer where a shipping order stands — by list, by id, or filtered by shipped status.

## Three queries, one slice
`listOrders()`, `getOrderStatus(id)` and `findOrdersByShippedStatus(shipped)` all interrogate the read
model **this** slice owns, which is exactly what §R2 groups into one slice. Splitting them into three
would mean three slices sharing one read model — the ownership violation §R4 forbids.

## How the read side works on this lane
There is **one** collection, written by the command slices and read here, so §R4's ownership rule is
restated rather than dropped:

> A view slice may read the entity's collection, but **never through the write repository**.

So this slice declares its own narrow, read-only `OrderStatusQueries` and returns `OrderStatusView`, a
Spring Data **closed interface projection**. That interface is a *declaration, not a mapper*, which is
why it satisfies §R2's no-adapter rule — there is no `…Response` mirror and no `toDto()`. It is also
strictly better than returning `ShippingOrder`, which would hand callers a mutable object and put every
field of the write model on the wire. Note what it omits: `destinationAddress` is never fetched.

`findByShipped(boolean)` lives here and must never be added to `entities/ShippingOrders` — a finder on
the write repository whose callers are screens is the read side served from the write model.

## Three things that do **not** apply here
- **Eventual consistency.** There is no projector. Reads hit the same collection the command slices
  write, so `OrderStatusIT` asserts immediately after each command with no `Awaitility` anywhere.
- **Projection idempotency.** No versioned read model, no `EventOrder`, no redelivery to double-apply.
  `/essentials:slice-check` gate 10 is *skipped* on this tier, not merely tolerated.
- **The `_v2` migration twin.** § Evolving a view slice is written around replaying a stream into a
  second read model. With no stream, a shape change is an ordinary schema migration.

## Trap: do not name a projection query `findById`
Spring Data matches that signature to the CRUD base implementation instead of deriving a query, so it
returns the `ShippingOrder` **entity** and silently ignores the declared projection type. It surfaces
as a `ClassCastException` at the call site, not as a wiring error. `findOrderStatusById` derives the
same `id = ?` query and does project.

## Boundaries
**Reads:** the `ShippingOrder` collection, through `OrderStatusQueries` only.
**Forbidden:**
  - Never inject `entities/ShippingOrders` here, and never call `save`/`delete` anywhere in this slice.
  - Never return `ShippingOrder` from `OrderStatusAPI`.

## The endpoint takes the semantic type, not a `String`

`@PathVariable OrderId` binds directly. That needs `types-spring-web`'s `SingleValueTypeConverter`, registered
by `config/WebConfiguration` importing `EssentialsWebMvcConfigurer` - the dependency alone does nothing,
it is not auto-configuration. Dropping that import turns these endpoints into HTTP **500**s
(`ConversionNotSupportedException`), not 400s, so the symptom does not point at the cause.

## Files
- `OrderStatusView.java` — the read shape; also the response body (§R2, no DTO)
- `OrderStatusQueries.java` — this slice's read-only query interface
- `OrderStatusAPI.java` — `GET /shipping/orders`, its three queries
