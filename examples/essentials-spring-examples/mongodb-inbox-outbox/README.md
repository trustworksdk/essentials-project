# Essentials components: MongoDB Inbox-Outbox example

A Spring Boot application built on `spring-boot-starter-mongodb`, which auto-configures every MongoDB-focused
Essentials component. All `@Bean`s it contributes use `@ConditionalOnMissingBean`, so any of them can be
replaced by declaring your own.

**There is no event store in this module.** State lives in a single `@Document` per order, and the
`Inbox`/`Outbox` store-and-forward patterns are what make integration with Kafka reliable. That is the point of
the example: `MongoDurableQueues` gives you at-least-once messaging with retries, dead-lettering and
crash-safety **without** adopting event sourcing.

Its two siblings:

- [`postgresql-inbox-outbox`](../postgresql-inbox-outbox/README.md) — the same application on PostgreSQL/JPA.
  Same slices, same flow; the differences are called out below.
- [`postgresql-cqrs`](../postgresql-cqrs/README.md) — the same `shipping` domain modelled the *other* way, with
  CQRS + event sourcing. Compare the two to see what a stream buys and what it costs.

## What this example demonstrates

| Concept | Where to look |
|---|---|
| `Inbox` — durably accept an incoming Kafka message before acting on it | `shipping/external_systems/order_management/incoming/OrderEventsKafkaListener` |
| `Outbox` — publish to Kafka only if the local transaction commits | `shipping/external_systems/order_management/outgoing/ShippingEventKafkaPublisher` |
| Service-entity write style (no aggregate, no event store) | `shipping/entities/ShippingOrder`, `ShippingOrders` |
| Idempotency without a stream | `ShippingOrder.markOrderAsShipped()` |
| A **strongly consistent** read slice using closed interface projections | `shipping/views/order_status/` |
| Synchronous `EventBus` handler joining the writer's transaction | `ShippingEventKafkaPublisher` (`AnnotatedEventHandler`) |
| Registering an application's own `CharSequenceType` with Spring Data MongoDB | `Application.additionalCharSequenceTypesSupported()` |
| Adding extra Spring Data converters | `Application.additionalGenericConverters()` |
| A custom `DurableQueuesInterceptor` | `config/ExampleDurableQueuesInterceptor` |

## How the code is laid out

One bounded context, `shipping`, packaged by vertical slice rather than by layer — there is no `controllers/`,
`services/`, `domain/` or `repositories/` package:

```
messaging/
  Application.java
  config/                             app-level wiring — Kafka factories, DurableQueues interceptor
  shipping/
    entities/                         ShippingOrder (@Document) + ShippingOrders (write repository)
    events/                           ShippingEvent (sealed) → ShippingOrderRegistered, OrderShipped
    types/                            OrderId, ShippingDestinationAddress
    use_cases/register_shipping_order/   command + handler + endpoint
    use_cases/ship_order/                command + handler + endpoint
    views/order_status/                  read model, its queries and its endpoint
    external_systems/order_management/   the Kafka anti-corruption boundary (incoming/ + outgoing/)
```

`shipping/CLAUDE.md` records why this context is on the **service-entity** write style: it has no need to
reconstruct state from history, so a stream would be cost without benefit.

### Endpoints

| Method | Path | Dispatch | Slice |
|---|---|---|---|
| POST | `/shipping/register-order` | `commandBus.send` (synchronous) | `shipping.register_shipping_order` |
| POST | `/shipping/ship-order` | `commandBus.send` (synchronous) | `shipping.ship_order` |
| GET | `/shipping/orders`, `/shipping/orders/{orderId}`, `/shipping/orders?shipped=true` | — | `shipping.order_status` |

Unlike the `postgresql-cqrs` sibling's `GET`s, these reads are **strongly consistent**: the view queries the
same collection the command just wrote. That is the thing this write style is better at.

---

## Shipping flow

An external **OrderService** publishes order events onto Kafka; this application ships the order and publishes
its own event back onto Kafka. Neither DTO on the wire uses this application's types.

```
   POST /shipping/register-order                  ┌─────────────────────────┐
         │ RegisterShippingOrder                  │  external OrderService  │
         ▼ commandBus.send (synchronous)          └────────────┬────────────┘
  ┌─ RegisterShippingOrderHandler ──────                       │ OrderAccepted
  │    already registered? → no-op                             │ {"id":"…"}
  │    save the document                    Kafka topic        │   ↑ plain String id
  │    eventBus.publish(ShippingOrderRegistered)               │
  └───────────┬─────────────────────────      "order-events"   ▼
              │                            ┌─ OrderEventsKafkaListener  @Transactional ─
              ▼                            │    ⇦ THE TRANSLATION IN ⇨
      ┌───────────────┐                    │    shipOrdersInbox.addMessageReceived(
      │ shippingOrder │                    │        new ShipOrder(OrderId.of(event.id())))
      │  collection   │                    └────────────────────┬──────────────────────
      └───────┬───────┘                                         │
              │                            ┌────────────────────▼─────────────────────┐
              │                            │ Inbox "OrderService:OrderEvents"         │
              │                            │  → durable_queues collection             │
              │                            │  fixedBackoff 100ms ×10                  │
              │                            │  SingleGlobalConsumer, 5 parallel        │
              │                            └────────────────────┬─────────────────────┘
              │                                                 │ forwards to the CommandBus
              │                                                 ▼
              │             ┌─ ONE TRANSACTION ────────────────────────────────
              │             │ ShipOrderHandler
              │             │   order = shippingOrders.getOrder(cmd.orderId())
              │             │   if (order.markOrderAsShipped()) {    ← idempotency
              │             │       shippingOrders.save(order);      ← REQUIRED
              │             │       eventBus.publish(new OrderShipped(orderId));
              │             │   }
              │             │                    │
              │             │                    ▼  LocalEventBus, synchronous
              │             │ ShippingEventKafkaPublisher (AnnotatedEventHandler)
              │             │   ⇦ THE TRANSLATION OUT ⇨
              │             │   kafkaOutbox.sendMessage(
              │             │       new ExternalOrderShipped(orderId.toString()))
              │             │                    │
              │             │                    ▼
              │             │            a durable_queues document
              │             └─ commit ────────────┬──────────────
              │                                   │ async, retried (fixedBackoff 100ms ×10)
              │                                   ▼
              │                        ┌──────────────────────┐
              │                        │ Outbox consumer      │
              │                        │  kafkaTemplate.send  │
              │                        └──────────┬───────────┘
              ▼                                   ▼
   GET /shipping/orders                Kafka topic "shipping-events"
   (strongly consistent — same collection)
```

Step by step:

1. `POST /shipping/register-order` sends `RegisterShippingOrder` with `commandBus.send`, so the caller learns
   synchronously that the order was accepted. `RegisterShippingOrderHandler` runs in that transaction: it treats
   an already-existing order as a no-op rather than an error (the command may be redelivered), writes the
   `ShippingOrder` document, and publishes `ShippingOrderRegistered` on the `LocalEventBus`.
2. The external OrderService publishes `OrderAccepted` to the `order-events` topic. Its `id` is a plain
   `String` — the external contract does not know about `OrderId`.
3. `OrderEventsKafkaListener` is the **incoming half of the anti-corruption boundary** and the one and only
   place `String` becomes `OrderId`. Rather than call the command bus directly, it hands `ShipOrder` to an
   `Inbox` it created in its constructor and which is configured to forward onto the `CommandBus`. Because the
   listener method is `@Transactional`, the message is committed to `durable_queues` before Kafka's offset is
   acknowledged — which is exactly what the Inbox pattern is for.

   > The listener keeps `commandBus.sendAndDontWait(...)` in a comment right beside the `Inbox` call. Both are
   > durable; the `Inbox` is shown here because it lets you name the queue, size its consumer pool and give it
   > its own `RedeliveryPolicy` independently of the command bus's. The `postgresql-inbox-outbox` sibling takes
   > the `sendAndDontWait` route, so the two can be read against each other.

4. The `Inbox`'s consumers (5 of them, `SingleGlobalConsumer` mode so only one application instance consumes)
   forward `ShipOrder` to the `CommandBus`, retrying with a fixed 100 ms backoff up to 10 times before
   dead-lettering.
5. `ShipOrderHandler` loads the document and calls `markOrderAsShipped()`, which returns `false` if the order
   was already shipped. That boolean **is** the idempotency guard: messaging gives at-least-once delivery, so
   `ShipOrder` can arrive more than once, and only the first arrival changes anything.

   > **The explicit `save()` is not optional here.** Spring Data MongoDB does no dirty checking — a loaded
   > document is not tracked, so a mutation that is not written back is silently lost, and the idempotency guard
   > would then never survive a redelivery. The JPA sibling would flush a managed entity anyway; that hidden
   > dependency is what made the identical handler body wrong when it was first ported to MongoDB.

6. `ShippingEventKafkaPublisher` is an `AnnotatedEventHandler` registered **synchronously** on the
   `LocalEventBus`, so its `@Handler` runs on the same thread and in the same transaction as the handler that
   published `OrderShipped`. It is the **outgoing half of the boundary**: `OrderShipped` (which carries
   `OrderId`) becomes `ExternalOrderShipped(String orderId)`, appended to the `Outbox`.
7. Because the `Outbox` is backed by `MongoDurableQueues`, that append is just another document in the same
   transaction. **Either the order is marked shipped and the Kafka message is queued, or neither happens** —
   which is the whole point of the pattern. A separate consumer thread then forwards it with `KafkaTemplate`,
   retrying on failure.

> **The Kafka DTOs must keep their plain `String` ids.** `OrderEvent.id()` and
> `ExternalOrderShippingEvent.orderId()` are deliberately not typed with `OrderId`. Typing them with the domain
> type means the boundary stops translating — and it broke the `-Pjackson2` build, because the Kafka mapper and
> the Essentials types module can end up on different Jackson majors. See the module's `CLAUDE.md`.

### One thing the diagram does not show

`ShippingOrderRegistered` is published, but **nothing subscribes to it today**. It is part of the context's
public surface (`events/` is importable by other contexts) and exists so that registration is observable the
same way shipping is; the example simply has no consumer for it yet.

### Differences from the PostgreSQL sibling

| | `mongodb-inbox-outbox` | `postgresql-inbox-outbox` |
|---|---|---|
| Kafka listener → command | an explicit `Inbox` (`OrderService:OrderEvents`) forwarding to the command bus | `commandBus.sendAndDontWait` |
| Entity `@Id` | `OrderId` — needs `AdditionalCharSequenceTypesSupported` in `Application` | plain `String` |
| Persistence on mutation | `save()` is **required** — no dirty checking | `save()` is explicit, though JPA would flush anyway |
| View read shape | closed interface projection read straight from the `Document`, so the entity needs no getters | closed interface projection over the JPA entity |
| Extra load harness | no | yes (`shipping/load/`, test scope) |

---

## Build, test and run

All commands are run from the `examples/essentials-spring-examples` folder.

```bash
mvn verify -pl :mongodb-inbox-outbox                 # unit + integration tests (needs Docker)
mvn -Pjackson2 verify -pl :mongodb-inbox-outbox -am  # the other Jackson flavour; -am is required
docker compose up -d && mvn spring-boot:run -pl :mongodb-inbox-outbox
```

The `-am` is not optional on the non-default Jackson flavour — see
[the aggregator README](../README.md#jackson-flavour).

### Tests

| Test | What it covers |
|---|---|
| `shipping/entities/ShippingOrderTest` | the context's only invariant — `markOrderAsShipped()` returns `true` then `false`. No Spring, no container |
| `shipping/ShippingFlowIT` | the whole flow: register → Kafka `OrderAccepted` → `Inbox` → ship → `Outbox` → Kafka. Also asserts that shipping twice publishes exactly **one** `ExternalOrderShipped` |
| `shipping/views/order_status/OrderStatusIT` | the view slice, asserting strong consistency — no `Awaitility` anywhere |

`AbstractIntegrationTest` holds the shared MongoDB + Kafka Testcontainers. Kafka starts even for tests that do
not use it, because `KafkaConfiguration` needs `spring.kafka.bootstrap-servers` to build the context.

### Drive it with `curl`

Start the runtime stack and the application:

```bash
docker compose up -d
mvn spring-boot:run -pl :mongodb-inbox-outbox
```

The last command blocks the terminal, so open a second one for the requests below.

```bash
# 1. Register the shipping order
curl -L 'http://localhost:8080/shipping/register-order' \
  -X POST \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  -d '{
    "orderId": "order1",
    "destinationAddress": {
      "recipientName": "John Doe",
      "street": "Test Street 1",
      "zipCode": "1234",
      "city": "Test City"
    }
  }'

# 2. Ship it (in the real flow this command comes from the Kafka listener instead)
curl -L 'http://localhost:8080/shipping/ship-order' \
  -X POST \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  -d '{ "orderId": "order1" }'

# 3. Read it back - immediately, because the view is strongly consistent
curl 'http://localhost:8080/shipping/orders'
curl 'http://localhost:8080/shipping/orders/order1'
curl 'http://localhost:8080/shipping/orders?shipped=true'
```

In the Spring Boot terminal you should see log entries similar to:
`... [mongodb-inbox-outbox] [....] [e7754a2059013cfdff422bfeda5d3e09-4acc5a9396d96dfc]...`

The second value (`e7754a2059013cfdff422bfeda5d3e09`) is the `traceId`. Open Grafana at
`http://localhost:3000`, go to the `Logs, Traces, Metrics` dashboard, and paste it into the `Trace ID` box.

Stop with `Ctrl-C`, then `docker compose down -v`.

---

## Application Setup

Everything this application needs is auto-configured by
[`spring-boot-starter-mongodb`](../../../components/spring-boot-starter-mongodb/README.md).

**That README is the reference for the complete bean list and every `essentials.*` property, including the
security notices for the configurable collection names.** It is kept in step with the code; the sections below
only record what *this example* configures on top of the defaults, and are not a substitute.

In short, the starter provides: `MongoTransactionManager` +
`SpringMongoTransactionAwareUnitOfWorkFactory`, `MongoDurableQueues`, `Inboxes`/`Outboxes`,
`DurableLocalCommandBus`, `LocalEventBus`, `MongoFencedLockManager`, `ReactiveHandlersBeanPostProcessor` (which
is what auto-registers every `@CmdHandler` and `@Handler` bean in this module), `JacksonJSONSerializer`,
`SingleValueTypeRandomIdGenerator`, a `MongoCustomConversions` carrying `SingleValueTypeConverter`, and the
Micrometer interceptors.

> ⚠️ **Security.** `essentials.durable-queues.shared-queue-collection-name` and
> `essentials.fenced-lock-manager.fenced-locks-collection-name` are used directly as collection names. Derive
> them from a trusted source only. The starter README carries the full notice.

### What this example configures — `src/main/resources/application.properties`

```properties
essentials.immutable-jackson-module-enabled=true

# Reactive buses
essentials.reactive.event-bus-backpressure-buffer-size=1024
essentials.reactive.overflow-max-retries=20
essentials.reactive.queued-task-cap-factor=1.5

# DurableQueues — backs the command bus, the Inbox and the Outbox
essentials.durable-queues.shared-queue-collection-name=durable_queues
essentials.durable-queues.transactional-mode=singleoperationtransaction
essentials.durable-queues.message-handling-timeout=5s
essentials.durable-queues.polling-delay-interval-increment-factor=0.5
essentials.durable-queues.max-polling-interval=2s
essentials.durable-queues.verbose-tracing=false

essentials.fenced-lock-manager.fenced-locks-collection-name=fenced_locks
essentials.fenced-lock-manager.lock-confirmation-interval=5s
essentials.fenced-lock-manager.lock-time-out=12s
essentials.fenced-lock-manager.release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation=false

# Metrics — operations slower than a threshold are logged at that level; see the starter README
essentials.metrics.durable-queues.enabled=true
essentials.metrics.command-bus.enabled=true
essentials.metrics.message-handler.enabled=true
# … each with .thresholds.{debug,info,warn,error} = 25ms / 200ms / 500ms / 5000ms

spring.data.mongodb.auto-index-creation=true
```

Notes on the values this example picks:

- **`transactional-mode=singleoperationtransaction`** is the recommended mode and the starter default.
  `fullytransactional` makes queue operations join the caller's transaction, which breaks retry counting and
  dead-lettering, because a failure marks the whole transaction for rollback.
- **`message-handling-timeout=5s`** is shorter than the starter default (`30s`). It only applies in
  `singleoperationtransaction` mode, where it is how long an unacknowledged in-flight message waits before
  being redelivered — a short value makes the retry behaviour visible in a demo, and is too aggressive for a
  real workload whose handlers can legitimately take seconds.
- **The fenced-lock values restate the starter defaults** (`12s` timeout / `5s` confirmation) rather than
  changing them, so the file doubles as a worked example of the properties. Note that the PostgreSQL starter's
  defaults are different (`15s` / `4s`) — these are per-starter, not global. `lock-confirmation-interval` must
  stay well below `lock-time-out`, and nothing validates that.

### Extension points this example uses

`Application.java` registers two Spring Data MongoDB extension points that the starter exposes:

```java
/**
 * The starter's SingleValueTypeConverter covers Essentials' own types (LockName, QueueEntryId, QueueName).
 * An application's own CharSequenceType - here OrderId, used as ShippingOrder's @Id - must be declared.
 */
@Bean
AdditionalCharSequenceTypesSupported additionalCharSequenceTypesSupported() {
    return new AdditionalCharSequenceTypesSupported(OrderId.class);
}

/**
 * Any additional Spring Data converters the application needs, merged into MongoCustomConversions.
 */
@Bean
AdditionalConverters additionalGenericConverters() {
    return new AdditionalConverters(Jsr310Converters.StringToDurationConverter.INSTANCE,
                                    Jsr310Converters.DurationToStringConverter.INSTANCE);
}
```

Both `DurableLocalCommandBus` extension points are also available, and apply only to **fire-and-forget**
commands sent with `CommandBus.sendAndDontWait`:

| Bean type | Effect | Default |
|---|---|---|
| `RedeliveryPolicy` | retry schedule for queued commands, and which exceptions skip straight to a dead-letter | `DurableLocalCommandBus.DEFAULT_REDELIVERY_POLICY` |
| `SendAndDontWaitErrorHandler` | if it does not rethrow, the command is neither retried nor dead-lettered | `RethrowingSendAndDontWaitErrorHandler` |

This module leaves both at their defaults — its incoming path goes through an `Inbox`, which carries its own
`RedeliveryPolicy`, set inline in `OrderEventsKafkaListener`'s constructor.
[`postgresql-cqrs`'s `Application.java`](../postgresql-cqrs/README.md#what-this-example-overrides-in-code) has a
worked example of overriding the command-bus pair.

Two more classes carry example-specific wiring:

| Class | Role |
|---|---|
| `config/KafkaConfiguration` | Kafka producer/consumer factories and the trusted-packages prefix, derived from `Application`'s package rather than from a type inside a slice |
| `config/ExampleDurableQueuesInterceptor` | a `DurableQueuesInterceptor` bean, collected automatically by the starter — here it only trace-logs queue operations, but the same hook is where you would add auditing or filtering |
