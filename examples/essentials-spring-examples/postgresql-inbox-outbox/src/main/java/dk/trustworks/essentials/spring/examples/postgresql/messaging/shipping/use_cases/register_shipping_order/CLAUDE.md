# Slice: shipping.register_shipping_order

**Kind:** command   **Status:** live   **Owner:** shipping-team   **Tier:** service-entity
**Purpose:** Register a new `ShippingOrder` with its destination address.

## Invariants
- A shipping order is registered at most once — re-handling `RegisterShippingOrder` for an id that
  already exists is a no-op, not an error (enforced by `RegisterShippingOrderHandler`)

## This slice unpacks its own command
`RegisterShippingOrderHandler` builds both the entity and the event from the command's **fields**:

```java
new ShippingOrder(cmd.orderId().toString(), cmd.destinationAddress())
new ShippingOrderRegistered(cmd.orderId(), cmd.destinationAddress())
```

The `toString()` is because this entity's `@Id` is a plain `String` — see the BC's `CLAUDE.md`. The
entity defensive-copies the address, which is a mutable `@Embeddable` here and would otherwise be shared
between the command and the persisted row.

Never `new ShippingOrder(cmd)` and never `ShippingOrderRegistered.from(cmd)`. Both existed here once
and both are §R4 violations: `events/` is the bounded context's importable surface, so a command
reference there drags this slice's wire contract into every foreign consumer of the event, and
`entities/` is the consistency boundary, which should not know one slice's request shape.

## Boundaries
**Reacts to:** `RegisterShippingOrder` off the `CommandBus`.
**Publishes:** `ShippingOrderRegistered` on the `EventBus` — an integration fact, not a stored event.
**Forbidden:**
  - Never import another slice's internals — only `shipping/events/` and `shipping/types/`
    (plus `shipping/entities/`, this BC's §R5 service-entity style).
  - Never read through `entities/ShippingOrders` for a screen — that is `views/order_status`.

## Data
**Owns (writes):** the `ShippingOrder` document.
**Reads:** the same document, to detect the already-registered case.

## Files
- `RegisterShippingOrder.java` — the command; also the HTTP request body (§R2, no DTO)
- `RegisterShippingOrderHandler.java` — the slice's single `@Handler`
- `RegisterShippingOrderAPI.java` — `POST /shipping/register-order`, this slice's only endpoint
