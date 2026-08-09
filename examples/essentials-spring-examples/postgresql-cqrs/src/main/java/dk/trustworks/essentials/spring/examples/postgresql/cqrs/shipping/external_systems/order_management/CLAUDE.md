# Slice: shipping.order_management

**Kind:** translation   **Status:** live   **Owner:** shipping-team
**Purpose:** The anti-corruption boundary between `shipping` and the external order-management
system, carried over Kafka in both directions.

A translation slice has **no external API** — the transport is Kafka, not HTTP.

## Boundaries
**Inbound** — `OrderEventsKafkaListener` consumes the `order-events` topic. An `OrderAccepted`
becomes a `ShipOrder` on the `CommandBus` (`sendAndDontWait`); every other `OrderEvent` is logged and
dropped. That command belongs to the `use_cases/ship_order` slice — this slice raises it, it does not
decide anything itself.
**Outbound** — `ShippingEventKafkaPublisher` is an `EventProcessor` subscribed to the
`ShippingOrders` aggregate type. `OrderShipped` becomes an `ExternalOrderShipped` on the
`shipping-events` topic, keyed by order id and stamped with the event's order from the
`OrderedMessage`. Delivery is at-least-once through the processor's Inbox, which is why
`ShippingOrder.markOrderAsShipped()` is idempotent (`INV-SO-1`).
**Forbidden:**
  - Never import another slice's internals — only `shipping/events/`, `shipping/types/`, and the
    command type of the slice this one triggers.
  - Never let an external schema (`OrderEvent`, `ExternalOrderShippingEvent`) escape this directory.

## The id is translated, not shared
The wire contracts carry a plain `String` id in both directions; `OrderId` exists only on our side of
the boundary. Two lines do the whole translation:

- `OrderEventsKafkaListener` — `new ShipOrder(OrderId.of(event.id()))`
- `ShippingEventKafkaPublisher` — `new ExternalOrderShipped(e.orderId().toString(), …)`

Keep it that way. Typing the DTOs with `OrderId` compiles and looks tidier, but then the ACL does not
actually translate: an upstream id-format change reaches straight into the domain instead of stopping
here, and our internal type becomes part of a contract we do not own. It is also why no
`shipping.types` package appears in `KafkaConfiguration`'s trusted packages — nothing internal crosses
the wire.

## Files
- `incoming/OrderEvent.java` — the external inbound contract (interface)
- `incoming/OrderAccepted.java` — the one variant this slice acts on
- `incoming/OrderEventsKafkaListener.java` — `order-events` listener; maps to `ShipOrder`
- `outgoing/ExternalOrderShippingEvent.java` — the external outbound contract (interface)
- `outgoing/ExternalOrderShipped.java` — the published external event
- `outgoing/ShippingEventKafkaPublisher.java` — `EventProcessor` publishing to `shipping-events`

Trusted-package wiring for both directions lives in the application's `config/KafkaConfiguration`.
