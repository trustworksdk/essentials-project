# Essentials components: PostgreSQL Inbox-Outbox example

A Spring Boot application built on `spring-boot-starter-postgresql`, which auto-configures every
PostgreSQL-focused Essentials component. All `@Bean`s it contributes use `@ConditionalOnMissingBean`, so any of
them can be replaced by declaring your own.

**There is no event store in this module.** State lives in a single JPA `@Entity` row per order, and the
`Inbox`/`Outbox` store-and-forward patterns are what make integration with Kafka reliable. That is the point of
the example: `PostgresqlDurableQueues` gives you at-least-once messaging with retries, dead-lettering and
crash-safety **without** adopting event sourcing.

Its two siblings:

- [`mongodb-inbox-outbox`](../mongodb-inbox-outbox/README.md) — the same application on MongoDB. Same slices,
  same flow; the differences are called out below.
- [`postgresql-cqrs`](../postgresql-cqrs/README.md) — the same `shipping` domain modelled the *other* way, with
  CQRS + event sourcing. Compare the two to see what a stream buys and what it costs.

## What this example demonstrates

| Concept | Where to look |
|---|---|
| `Outbox` — publish to Kafka only if the local transaction commits | `shipping/external_systems/order_management/outgoing/ShippingEventKafkaPublisher` |
| Durable, retried command delivery from a Kafka listener | `shipping/external_systems/order_management/incoming/OrderEventsKafkaListener` |
| Service-entity write style (no aggregate, no event store) | `shipping/entities/ShippingOrder`, `ShippingOrders` |
| Idempotency without a stream | `ShippingOrder.markOrderAsShipped()` |
| A **strongly consistent** read slice over the write model | `shipping/views/order_status/` |
| Synchronous `EventBus` handler joining the writer's transaction | `ShippingEventKafkaPublisher` (`AnnotatedEventHandler`) |
| An `Inbox` under load — 15 000 messages, 20 parallel consumers | `src/test/.../shipping/load/`, `LoadOrderShippingProcessorIT` |
| Raw `DurableQueues` throughput | `src/test/.../DurableQueuesLoadIT` |

## How the code is laid out

One bounded context, `shipping`, packaged by vertical slice rather than by layer — there is no `controllers/`,
`services/`, `domain/` or `repositories/` package:

```
messaging/
  Application.java
  config/                             app-level wiring — Kafka factories, JPA converters
  shipping/
    entities/                         ShippingOrder (JPA @Entity) + ShippingOrders (write repository)
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
same table the command just wrote, in the same transaction. That is the thing this write style is better at.

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
  │    save the row                         Kafka topic        │   ↑ plain String id
  │    eventBus.publish(ShippingOrderRegistered)               │
  └───────────┬─────────────────────────      "order-events"   ▼
              │                            ┌─ OrderEventsKafkaListener  @Transactional ─
              ▼                            │    ⇦ THE TRANSLATION IN ⇨
      ┌───────────────┐                    │    commandBus.sendAndDontWait(
      │ shipping_order│                    │        new ShipOrder(OrderId.of(event.id())))
      │  table (JPA)  │                    └────────────────────┬──────────────────────
      └───────┬───────┘                                         │
              │                            ┌────────────────────▼─────────────────────┐
              │                            │ DurableLocalCommandBus                   │
              │                            │  → durable_queues table                  │
              │                            │  survives a crash; retried               │
              │                            └────────────────────┬─────────────────────┘
              │                                                 │ ShipOrder
              │                                                 ▼
              │             ┌─ ONE TRANSACTION ────────────────────────────────
              │             │ ShipOrderHandler
              │             │   order = shippingOrders.getOrder(cmd.orderId())
              │             │   if (order.markOrderAsShipped()) {    ← idempotency
              │             │       shippingOrders.save(order);
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
              │             │            a durable_queues row
              │             └─ commit ────────────┬──────────────
              │                                   │ async, retried (fixedBackoff 100ms ×10)
              │                                   ▼
              │                        ┌──────────────────────┐
              │                        │ Outbox consumer      │
              │                        │  kafkaTemplate.send  │
              │                        └──────────┬───────────┘
              ▼                                   ▼
   GET /shipping/orders                Kafka topic "shipping-events"
   (strongly consistent — same table)
```

Step by step:

1. `POST /shipping/register-order` sends `RegisterShippingOrder` with `commandBus.send`, so the caller learns
   synchronously that the order was accepted. `RegisterShippingOrderHandler` runs in that transaction: it treats
   an already-existing order as a no-op rather than an error (the command may be redelivered), writes the
   `ShippingOrder` row, and publishes `ShippingOrderRegistered` on the `LocalEventBus`.
2. The external OrderService publishes `OrderAccepted` to the `order-events` topic. Its `id` is a plain
   `String` — the external contract does not know about `OrderId`.
3. `OrderEventsKafkaListener` is the **incoming half of the anti-corruption boundary** and the one and only
   place `String` becomes `OrderId`. It forwards `ShipOrder` with `sendAndDontWait`, which writes the command to
   the `durable_queues` table before returning. If the application dies between the Kafka poll and the handler
   running, the command is still there and is retried according to the configured `RedeliveryPolicy`.
4. `ShipOrderHandler` loads the entity and calls `markOrderAsShipped()`, which returns `false` if the order was
   already shipped. That boolean **is** the idempotency guard: messaging gives at-least-once delivery, so
   `ShipOrder` can arrive more than once, and only the first arrival changes anything.
5. `ShippingEventKafkaPublisher` is an `AnnotatedEventHandler` registered **synchronously** on the
   `LocalEventBus`, so its `@Handler` runs on the same thread and in the same transaction as the handler that
   published `OrderShipped`. It is the **outgoing half of the boundary**: `OrderShipped` (which carries
   `OrderId`) becomes `ExternalOrderShipped(String orderId)`, appended to the `Outbox`.
6. Because the `Outbox` is backed by `PostgresqlDurableQueues`, that append is just another row in the same
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

### Differences from the MongoDB sibling

| | `postgresql-inbox-outbox` | `mongodb-inbox-outbox` |
|---|---|---|
| Kafka listener → command | `commandBus.sendAndDontWait` | an explicit `Inbox` (`OrderService:OrderEvents`) forwarding to the command bus |
| Entity `@Id` | plain `String` — see `shipping/CLAUDE.md` | `OrderId` |
| Persistence on mutation | `save()` is explicit, though JPA would flush the managed entity anyway | `save()` is **required** — Spring Data MongoDB does no dirty checking |
| Extra load harness | yes (`shipping/load/`, test scope) | no |

Both are `Inbox`-and-`Outbox` examples; they differ only in which of the two durable hops the incoming side is
written with. `OrderEventsKafkaListener` in the MongoDB module keeps the `sendAndDontWait` alternative in a
comment beside the `Inbox` call, so the two are directly comparable.

---

## Build, test and run

All commands are run from the `examples/essentials-spring-examples` folder.

```bash
mvn verify -pl :postgresql-inbox-outbox                 # unit + integration tests (needs Docker)
mvn -Pjackson2 verify -pl :postgresql-inbox-outbox -am  # the other Jackson flavour; -am is required
docker compose up -d && mvn spring-boot:run -pl :postgresql-inbox-outbox
```

The `-am` is not optional on the non-default Jackson flavour — see
[the aggregator README](../README.md#jackson-flavour).

### Tests

| Test | What it covers |
|---|---|
| `shipping/entities/ShippingOrderTest` | the context's only invariant — `markOrderAsShipped()` returns `true` then `false`. No Spring, no container |
| `shipping/ShippingFlowIT` | the whole flow: register → Kafka `OrderAccepted` → command queue → ship → `Outbox` → Kafka. Also asserts that shipping twice publishes exactly **one** `ExternalOrderShipped` |
| `shipping/views/order_status/OrderStatusIT` | the view slice, asserting strong consistency — no `Awaitility` anywhere |
| `shipping/LoadOrderShippingProcessorIT` | 15 000 rows through an `Inbox` with 20 parallel consumers; ~45 s, the slowest test here |
| `DurableQueuesLoadIT` | `DurableQueues` throughput, independent of the shipping context |

`AbstractIntegrationTest` holds the shared PostgreSQL + Kafka Testcontainers. The load harness the load IT
drives (`shipping/load/LoadOrderShippingProcessor` and its commands) lives in **test scope** so the demo
application does not boot a 20-consumer `load-test` inbox — `shipping/CLAUDE.md` explains why.

### Drive it with `curl`

Start the runtime stack and the application:

```bash
docker compose up -d
mvn spring-boot:run -pl :postgresql-inbox-outbox
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
`... [postgresql-inbox-outbox] [....] [e7754a2059013cfdff422bfeda5d3e09-4acc5a9396d96dfc]...`

The second value (`e7754a2059013cfdff422bfeda5d3e09`) is the `traceId`. Open Grafana at
`http://localhost:3000`, go to the `Logs, Traces, Metrics` dashboard, and paste it into the `Trace ID` box.

Stop with `Ctrl-C`, then `docker compose down -v`.

---

## Application Setup

Everything this application needs is auto-configured by
[`spring-boot-starter-postgresql`](../../../components/spring-boot-starter-postgresql/README.md).

**That README is the reference for the complete bean list and every `essentials.*` property, including the
security notices for the configurable table names.** It is kept in step with the code; the sections below only
record what *this example* configures on top of the defaults, and are not a substitute.

In short, the starter provides: `Jdbi` wrapped in a `TransactionAwareDataSourceProxy` plus
`SpringTransactionAwareJdbiUnitOfWorkFactory`, `PostgresqlDurableQueues`, `Inboxes`/`Outboxes`,
`DurableLocalCommandBus`, `LocalEventBus`, `PostgresqlFencedLockManager`, `MultiTableChangeListener`,
`ReactiveHandlersBeanPostProcessor` (which is what auto-registers every `@CmdHandler` and `@Handler` bean in
this module), `JacksonJSONSerializer`, the Micrometer interceptors, the optional `EssentialsScheduler` /
`PostgresqlTTLManager`, and the admin API beans.

> ⚠️ **Security.** `essentials.durable-queues.shared-queue-table-name` and
> `essentials.fenced-lock-manager.fenced-locks-table-name` are concatenated into SQL. Derive them from a
> trusted source only. The starter README carries the full notice.

### What this example configures — `src/main/resources/application.properties`

```properties
essentials.immutable-jackson-module-enabled=true

# Reactive buses
essentials.reactive.event-bus-backpressure-buffer-size=1024
essentials.reactive.overflow-max-retries=20
essentials.reactive.queued-task-cap-factor=1.5

# DurableQueues — backs the command bus, the Outbox, and the load harness's Inbox
essentials.durable-queues.shared-queue-table-name=durable_queues
essentials.durable-queues.transactional-mode=singleoperationtransaction
essentials.durable-queues.use-centralized-message-fetcher=true
essentials.durable-queues.centralized-message-fetcher-polling-interval=20ms
essentials.durable-queues.polling-delay-interval-increment-factor=0.5
essentials.durable-queues.max-polling-interval=2s
essentials.durable-queues.verbose-tracing=false

essentials.fenced-lock-manager.fenced-locks-table-name=fenced_locks
essentials.fenced-lock-manager.lock-confirmation-interval=5s
essentials.fenced-lock-manager.lock-time-out=12s
essentials.fenced-lock-manager.release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation=false

essentials.multi-table-change-listener.filter-duplicate-notifications=true
essentials.multi-table-change-listener.polling-interval=100ms

# Metrics — operations slower than a threshold are logged at that level; see the starter README
essentials.metrics.durable-queues.enabled=true
essentials.metrics.command-bus.enabled=true
essentials.metrics.message-handler.enabled=true
# … each with .thresholds.{debug,info,warn,error} = 25ms / 200ms / 500ms / 5000ms

# JPA
spring.jpa.generate-ddl=true
spring.jpa.hibernate.ddl-auto=create-drop
spring.datasource.hikari.maximum-pool-size=25
```

Notes on the values this example picks:

- **`use-centralized-message-fetcher=true`** is the starter default and what the `postgresql-cqrs` sibling
  uses. `polling-delay-interval-increment-factor` and `max-polling-interval` are listed above but have **no
  effect** in this mode — they configure the legacy per-consumer polling path, which is what
  `use-centralized-message-fetcher=false` selects. They are kept as a worked example of the properties.
- **`transactional-mode=singleoperationtransaction`** is the recommended mode and the starter default.
  `fullytransactional` makes queue operations join the caller's transaction, which breaks retry counting and
  dead-lettering, because a failure marks the whole transaction for rollback.
- **The fenced-lock and multi-table-change-listener values differ from the starter defaults** (`15s`/`4s` and
  `50ms` respectively). They are set explicitly here so the file doubles as a worked example of the properties;
  neither choice is a recommendation.
- **`ddl-auto=create-drop`** is a demo convenience: the `shipping_order` table is created at startup and dropped
  at shutdown. The Essentials tables (`durable_queues`, `fenced_locks`) are created by the components
  themselves, not by Hibernate.

### Extension points this example uses

Both `DurableLocalCommandBus` extension points are available and apply only to **fire-and-forget** commands sent
with `CommandBus.sendAndDontWait` — which, in this module, is the path the Kafka listener takes:

| Bean type | Effect | Default |
|---|---|---|
| `RedeliveryPolicy` | retry schedule for queued commands, and which exceptions skip straight to a dead-letter | `DurableLocalCommandBus.DEFAULT_REDELIVERY_POLICY` |
| `SendAndDontWaitErrorHandler` | if it does not rethrow, the command is neither retried nor dead-lettered | `RethrowingSendAndDontWaitErrorHandler` |

This module leaves both at their defaults;
[`postgresql-cqrs`'s `Application.java`](../postgresql-cqrs/README.md#what-this-example-overrides-in-code) has a
worked example of overriding them.

Two configuration classes carry example-specific wiring:

| Class | Role |
|---|---|
| `config/KafkaConfiguration` | Kafka producer/consumer factories and the trusted-packages prefix, derived from `Application`'s package rather than from a type inside a slice |
| `config/JpaConfig` | component-scans `dk.trustworks.essentials.types.springdata.jpa.converters` so JPA can persist Essentials semantic types |
| `config/converters/OrderIdAttributeConverter` | kept against a retry of typing the entity's `@Id` as `OrderId`; it currently applies to no field — see `shipping/CLAUDE.md` |
