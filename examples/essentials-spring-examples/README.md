# Essentials Spring Examples

Spring Boot applications showing how the Essentials components are wired into a real service.

These examples were previously maintained in a standalone `essentials-spring-examples` repository. They now build as
part of the Essentials reactor (`examples/essentials-spring-examples`), so they always compile and test against the
Essentials sources in this working tree rather than against a released version.

## The three examples

| Example | Starter | Persistence | Write style | Read style |
|---|---|---|---|---|
| [**postgresql-cqrs**](postgresql-cqrs/README.md) | `spring-boot-starter-postgresql-event-store` | PostgreSQL `EventStore` | event-sourced `AggregateRoot` | projections — *eventually* consistent |
| [**postgresql-inbox-outbox**](postgresql-inbox-outbox/README.md) | `spring-boot-starter-postgresql` | PostgreSQL / JPA | state-stored entity | queries over the write model — *strongly* consistent |
| [**mongodb-inbox-outbox**](mongodb-inbox-outbox/README.md) | `spring-boot-starter-mongodb` | MongoDB / Spring Data | state-stored entity | queries over the write model — *strongly* consistent |

All three model the **same `shipping` domain** — register an order, ship it when an external OrderService says
the order was accepted, tell the world it shipped — deliberately, so the two designs can be read against each
other. `postgresql-cqrs` adds two more bounded contexts (`banking`, `task`) that the others do not have.

The two `inbox-outbox` examples are near-identical; pick whichever database you use. The MongoDB one shows the
incoming Kafka hop written with an explicit `Inbox`, the PostgreSQL one with `commandBus.sendAndDontWait` —
both durable, and each README points at the other.

For a performance/CDC-focused Spring Boot application, see
[essentials-performance-lab](../essentials-performance-lab/README.md).

## Where to look for a specific construction

| If you want to see… | Go to |
|---|---|
| **`RedeliveryPolicy` customization** for the command bus | `postgresql-cqrs` → `Application.durableLocalCommandBusRedeliveryPolicy()` |
| **`SendAndDontWaitErrorHandler`** — swallowing a failure so it is neither retried nor dead-lettered | `postgresql-cqrs` → `Application.sendAndDontWaitErrorHandler()` |
| **A per-processor `RedeliveryPolicy`** | `postgresql-cqrs` → `ShippingEventKafkaPublisher.getInboxRedeliveryPolicy()` |
| **Dead-letter behaviour**, for both `sendAndDontWait` and an `Inbox` | `postgresql-cqrs` → `src/test/.../EventProcessorIT` |
| **`Inbox`** — durably accept an incoming Kafka message before acting on it | `mongodb-inbox-outbox` → `OrderEventsKafkaListener` |
| **`Outbox`** — publish to Kafka only if the local transaction commits | both `inbox-outbox` examples → `ShippingEventKafkaPublisher` |
| **`EventProcessor`** — a saga/process manager across two aggregates | `postgresql-cqrs` → `banking/automations/transfer_money/TransferMoneyProcessor` |
| The same process **written before `EventProcessor` existed** (hand-rolled subscriptions + `UnitOfWork`) | `postgresql-cqrs` → `TransferMoneyProcessorOld` |
| **`ViewEventProcessor`** — a read-model projection, with replay-safety and reset | `postgresql-cqrs` → `banking/views/account_balance/`, `shipping/views/order_status/` |
| **`InTransactionEventProcessor`** — react to an event *inside the transaction that emitted it* | `postgresql-cqrs` → `task/automations/comment_on_task_created/` |
| **`AggregateRoot` + `StatefulAggregateRepository`** | `postgresql-cqrs` → any `*/aggregates/` package |
| **Service-entity write style** — a decision on a plain entity, no event store | both `inbox-outbox` examples → `shipping/entities/` |
| **`DocumentDbRepository`** as projection storage, with indexes and optimistic versioning | `postgresql-cqrs` → `config/DocumentDbConfiguration`, both `views/` slices |
| **An anti-corruption boundary over Kafka**, and why the DTOs carry plain `String` ids | all three → `shipping/external_systems/order_management/` |
| **Idempotent message handling** under at-least-once delivery | all three → `markOrderAsShipped()` |
| **Closed interface projections** as a read model over the write entity | both `inbox-outbox` examples → `shipping/views/order_status/` |
| **A semantic type as a typed `@PathVariable`** rather than a `String` the endpoint wraps | all three → `config/WebConfiguration` + any `views/*/…API` |
| **Registering your own `CharSequenceType`** with Spring Data MongoDB | `mongodb-inbox-outbox` → `Application.additionalCharSequenceTypesSupported()` |
| **A `DurableQueuesInterceptor`** | `mongodb-inbox-outbox` → `config/ExampleDurableQueuesInterceptor` |
| **`DurableQueues` / `Inbox` under load** (15 000 messages, 20 consumers) | `postgresql-inbox-outbox` → `LoadOrderShippingProcessorIT`, `DurableQueuesLoadIT` |
| **Testcontainers wiring** for PostgreSQL + Kafka or MongoDB + Kafka | any module's `AbstractIntegrationTest` / `*IT` |
| **OpenTelemetry tracing end to end**, through the command bus and the queues | all three — see [Observability](#observability) |

Every module is also packaged by **vertical slice** rather than by layer, so `<bc>/use_cases/<slice>/` holds a
command, its handler and its endpoint together, and there is no `controllers/`, `services/`, `domain/` or
`repositories/` package anywhere. Each slice directory carries its own `slice.yaml` and `CLAUDE.md`.

## Requirements

| | |
|---|---|
| Java | 21 (the reactor compiles with `--release 21` and builds on JDK 25) |
| Spring Boot | 4.0.x |
| Jackson | 3 — see [Jackson flavour](#jackson-flavour) below |
| Docker | required — the integration tests use Testcontainers, and `docker-compose.yml` provides the local runtime stack |

## Build and test

From the repository root:

```bash
mvn test    -pl examples/essentials-spring-examples -amd    # unit tests, no Docker
mvn verify  -pl examples/essentials-spring-examples -amd    # + integration tests, needs Docker
```

Or from this folder:

```bash
mvn verify                          # all three examples
mvn verify -pl :postgresql-cqrs     # a single example
```

## Running an example locally

`docker-compose.yml` in this folder starts PostgreSQL (`5432`), MongoDB (`27017`), Kafka (`9092`) and the
observability stack — OpenTelemetry Collector, Prometheus (`9090`), Grafana (`3000`), Tempo (`3200`) and Loki
(`3100`) — that the examples export to:

```bash
docker compose up -d
mvn spring-boot:run -pl :postgresql-cqrs
```

Each application serves on `http://localhost:8080`, so run one at a time. The per-example README lists its
endpoints and walks the scenario through with `curl`.

## Jackson flavour

These examples target the **default Jackson 3 flavour**, which is what Spring Boot 4 ships. They also build and
test under `-Pjackson2`, but that build has one hard requirement:

```bash
mvn -Pjackson2 verify -pl :postgresql-cqrs -am     # -am is NOT optional
```

The profile only overrides `essentials.types-jackson.artifactId` for modules in the **current reactor**.
Without `-am`, a sibling resolves from the local repository with the property unresolved, it then resolves from
the Essentials parent's default (Jackson 3), and **both** flavours land on the classpath under the same fully
qualified class names. `EssentialsJacksonModules` fails loudly on that mismatch — believe it rather than the
profile.

### Why the Kafka DTOs carry plain `String` ids

Spring Boot 4 auto-configures a **Jackson 3** `JsonMapper`, and Spring for Apache Kafka 4's
`JacksonJsonSerializer`/`JacksonJsonDeserializer` bind against it. That is the mapper on the application's own
JSON boundary, regardless of which flavour the Essentials persistence layer uses. Under `-Pjackson2` the
Essentials value-type support (`EssentialTypesJacksonModule`) is a *Jackson 2* `Module`, which Boot's Jackson 3
auto-configuration does not collect — so an Essentials `CharSequenceType` such as `OrderId` would not round-trip
over Kafka.

The examples do not paper over that with a compatibility shim. Instead, **no Essentials type crosses the wire at
all**: every Kafka DTO in `shipping/external_systems/order_management/` is typed with a plain `String`, and the
two adapters convert at the boundary. That is the anti-corruption boundary doing its job — an upstream
id-format change cannot reach the domain — and the Jackson flavour stops being load-bearing as a side effect.
Re-typing those DTOs with `OrderId` reintroduces both problems at once; each module's `CLAUDE.md` records this.

## Observability

All three applications publish metrics and traces over OTLP and push logs to Loki. Spring Boot 4 split the actuator
into per-concern modules, so the tracing auto-configuration comes from `spring-boot-starter-opentelemetry` rather
than from `spring-boot-starter-actuator`; see the aggregator `pom.xml`.

Each application also enables the Essentials **metrics thresholds**, which log an operation at `DEBUG` / `INFO` /
`WARN` / `ERROR` depending on how long it took. See the starter READMEs
([PostgreSQL](../../components/spring-boot-starter-postgresql/README.md#metrics-configuration),
[MongoDB](../../components/spring-boot-starter-mongodb/README.md#metrics-configuration)) for the full property
set and the logger names to tune.

To follow a request end to end: find the `traceId` in the application log (the second value in
`[app] [...] [<traceId>-<spanId>]`), open Grafana at `http://localhost:3000`, go to the
`Logs, Traces, Metrics` dashboard and paste it into the `Trace ID` box.

The collector, Prometheus, Grafana, Tempo and Loki configuration lives in [`docker/`](docker/README.md).

## Configuration reference

Each example README documents only what *that example* configures. The authoritative reference for the full set
of auto-configured beans, every `essentials.*` property with its default, and the security notices, is the
starter README the example uses:

- [`spring-boot-starter-postgresql`](../../components/spring-boot-starter-postgresql/README.md)
- [`spring-boot-starter-postgresql-event-store`](../../components/spring-boot-starter-postgresql-event-store/README.md)
- [`spring-boot-starter-mongodb`](../../components/spring-boot-starter-mongodb/README.md)

## License

Essentials is released under version 2.0 of the [Apache License](https://www.apache.org/licenses/LICENSE-2.0).

## Security

Several of the components, as well as their subcomponents and/or supporting classes, allow the user of the components
to provide customized:

- table names
- column names
- collection names
- etc.

By using naming conventions for PostgreSQL table/column/index names and MongoDB Collection names, Essentials attempts
to provide an initial layer of defense intended to reduce the risk of malicious input.
**However, Essentials does not offer exhaustive protection, nor does it assure the complete security of the resulting
SQL and Mongo Queries/Updates against injection threats.**

> The responsibility for implementing protective measures against malicious API input and configuration values lies
> exclusively with the users/developers using the Essentials components and its supporting classes.
> Users must ensure thorough sanitization and validation of API input parameters, SQL table/column/index names as well
> as MongoDB collection names.

**Insufficient attention to these practices may leave the application vulnerable to attacks, endangering the security
and integrity of the database.**

> Please see the **Security** notices for the individual components you use, e.g.
> `components/postgresql-event-store/README.md`, `components/postgresql-queue/README.md`,
> `components/springdata-mongo-queue/README.md` and the Spring Boot starter READMEs, to familiarize yourself with the
> security risks related to using the Essentials Components.
