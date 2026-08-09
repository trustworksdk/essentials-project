# Slice: shipping.register_shipping_order

**Kind:** command   **Status:** live   **Owner:** shipping-team
**Purpose:** Register a new `ShippingOrder` with the address it is to be shipped to.

## Invariants
- A shipping order is registered at most once — the handler looks the order up first and does
  nothing when it already exists (enforced by `RegisterShippingOrderHandler`)

## Boundaries
**Reacts to / reads:** `RegisterShippingOrder`, sent over the `CommandBus` from
`RegisterShippingOrderAPI`. Loads the `ShippingOrder` aggregate through `ShippingOrders` to decide
whether this is a new registration.
**Publishes:** `ShippingOrderRegistered` — applied by the `ShippingOrder` constructor.
**Forbidden:**
  - Never import another slice's internals — only `shipping/events/` and `shipping/types/`
    (plus `shipping/aggregates/`, which this BC runs on the aggregate style, §R5).
  - Never add this slice's logic to a shared decider/controller/event file.

## Data
**Owns (writes):** the `ShippingOrder` aggregate stream (`AggregateType` `ShippingOrders`).
**Reads:** the same aggregate, for the existence check only. No read model is consulted.

## Notes
The decision lives on the aggregate, not in a decider: this BC is on the **aggregate style**, so the
handler is thin — load, call one method, let the unit of work persist. The command bus is
`DurableLocalCommandBus`, so `handle` already runs inside a transaction.

## Files
- `RegisterShippingOrder.java` — the command; also the HTTP request body (§R2, no DTO)
- `RegisterShippingOrderHandler.java` — the slice's single `@CmdHandler`
- `RegisterShippingOrderAPI.java` — `POST /shipping/register-order`, this slice's only endpoint
