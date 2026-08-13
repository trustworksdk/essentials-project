# Slice: shipping.order_management

**Kind:** translation   **Status:** live   **Owner:** shipping-team   **Tier:** service-entity
**Purpose:** Anti-corruption boundary to the order-management system over Kafka, in both directions.

## One slice, two directions
`incoming/` and `outgoing/` are two halves of **one** anti-corruption boundary, not two slices.
Direction does not matter to the slice kind — an inbound adapter is as much a translation as an
outbound client, and both face the same neighbouring system.

- **Inbound.** `OrderEventsKafkaListener` consumes `order-events`, and on `OrderAccepted` puts a
  `ShipOrder` into an `Inbox`, which durably forwards it to the `CommandBus`. Naming another slice's
  command type purely to dispatch it is the collaboration §R4 prescribes, not a violation of it.
- **Outbound.** `ShippingEventKafkaPublisher` subscribes to the `EventBus` **synchronously, inside the
  transaction that produced the event**, converts `OrderShipped` to `ExternalOrderShipped` and puts it
  in an `Outbox`. That is what makes the state change and the outgoing message atomic.

## The Inbox is why the idempotency check exists
The inbound `Inbox` is configured for up to 10 redeliveries, so `ShipOrder` is at-least-once. The guard
lives on `ShippingOrder` in `entities/`, and `ship_order`'s handler must write the mutation back — see
that slice's `CLAUDE.md`.

## Boundaries
**Consumes:** `OrderAccepted` (Kafka), `OrderShipped` (`EventBus`).
**Produces:** `ShipOrder` (command bus), `ExternalOrderShipped` (Kafka).
**No API** — translation slices expose no endpoint.
**Forbidden:**
  - Never let `OrderEvent`/`ExternalOrderShippingEvent` leak past this slice; they are the *foreign*
    schema, and translating them is this slice's entire job.
  - Never write the `ShippingOrder` document from here — raise the command instead.

## The id is translated, not shared
The wire contracts carry a plain `String` id in both directions; `OrderId` exists only on our side of
the boundary. Two lines do the whole translation:

- `OrderEventsKafkaListener` — `new ShipOrder(OrderId.of(event.id()))`
- `ShippingEventKafkaPublisher` — `new ExternalOrderShipped(e.orderId().toString())`

Keep it that way. Typing the DTOs with `OrderId` compiles and looks tidier, and that is what this slice
used to do — but then the ACL does not actually translate: an upstream id-format change reaches
straight into the domain instead of stopping here, and our internal type becomes part of a contract we
do not own.

It was never only a coupling concern. A DTO carrying a value type also needs the Essentials
single-value-type (de)serializer on whichever `ObjectMapper` Kafka uses, and that dependency is what
broke the module under `-Pjackson2`: Spring Boot 4 hands `KafkaConfiguration` a Jackson 3 `JsonMapper`,
while that profile puts the Jackson 2 flavour of the types module on the classpath. Nothing internal
crosses the wire now, so the mapper flavour no longer matters here.

## Files
- `incoming/OrderEvent.java`, `incoming/OrderAccepted.java` — the foreign inbound schema
- `incoming/OrderEventsKafkaListener.java` — Kafka → `Inbox` → `CommandBus`
- `outgoing/ExternalOrderShippingEvent.java`, `outgoing/ExternalOrderShipped.java` — the foreign
  outbound schema
- `outgoing/ShippingEventKafkaPublisher.java` — `EventBus` → `Outbox` → Kafka
