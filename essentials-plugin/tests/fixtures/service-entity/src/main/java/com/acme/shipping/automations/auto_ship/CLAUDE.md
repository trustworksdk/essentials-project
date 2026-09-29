# Slice: shipping.auto_ship

**Kind:** automation · **Lane:** service-entity · **Status:** live · **Owner:** shipping-team

## Boundaries
**Consumes:** `ShippingOrderRegistered`. **Dispatches:** `ShipOrder` on the command bus.
Naming `ShipOrder` is the sanctioned cross-slice reference (R4) — the type's only use is
constructing a command handed to the bus.
