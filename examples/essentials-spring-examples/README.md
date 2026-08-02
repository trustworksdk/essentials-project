# Essentials Spring Examples

Spring Boot applications showing how the Essentials components are wired into a real service.

These examples were previously maintained in a standalone `essentials-spring-examples` repository. They now build as
part of the Essentials reactor (`examples/essentials-spring-examples`), so they always compile and test against the
Essentials sources in this working tree rather than against a released version.

- [postgresql-cqrs](postgresql-cqrs/README.md) — PostgreSQL focused CQRS and Event Sourced aggregate examples
- [postgresql-inbox-outbox](postgresql-inbox-outbox/README.md) — PostgreSQL focused Inbox/Outbox example that integrates with Kafka
- [mongodb-inbox-outbox](mongodb-inbox-outbox/README.md) — MongoDB focused Inbox/Outbox example that integrates with Kafka

For a performance/CDC focused Spring Boot application, see [essentials-performance-lab](../essentials-performance-lab/README.md).

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

`docker-compose.yml` in this folder starts PostgreSQL, MongoDB, Kafka and the observability stack
(OpenTelemetry Collector, Prometheus, Grafana, Tempo, Loki) that the examples export to:

```bash
docker compose up -d
mvn spring-boot:run -pl :postgresql-cqrs
```

The per-example README describes the scenario each application demonstrates and how to drive it with `curl`.

## Jackson flavour

These examples target the **default Jackson 3 flavour**, which is what Spring Boot 4 ships. They compile under
`-Pjackson2`, but the `postgresql-cqrs`, `postgresql-inbox-outbox` and `mongodb-inbox-outbox` Kafka integration tests
fail there, and that is expected rather than a defect in the examples:

- Spring Boot 4 auto-configures a **Jackson 3** `JsonMapper`, and Spring for Apache Kafka 4's
  `JacksonJsonSerializer`/`JacksonJsonDeserializer` bind against it. That is the mapper on the application's own JSON
  boundary (Kafka and REST), regardless of which flavour the Essentials persistence layer uses.
- Essentials value-type support for that mapper comes from `EssentialTypesJacksonModule`. `types-jackson` and
  `types-jackson3` publish that class under the same FQCN and only one is ever on the classpath, so under `-Pjackson2`
  the bean is a *Jackson 2* `Module` — which Boot's Jackson 3 auto-configuration does not collect.
- Result: under `-Pjackson2` a `CharSequenceType` such as `OrderId` no longer round-trips over Kafka
  (`MismatchedInputException: cannot deserialize from Object value`).

An application that must keep Essentials on Jackson 2 while running on Spring Boot 4 therefore has to supply its own
Jackson 3 handling for Essentials value types on its external JSON boundaries. The examples deliberately do not, so
that they stay a demonstration of the library rather than of a workaround.

## Observability

All three applications publish metrics and traces over OTLP and push logs to Loki. Spring Boot 4 split the actuator
into per-concern modules, so the tracing auto-configuration comes from `spring-boot-starter-opentelemetry` rather than
from `spring-boot-starter-actuator`; see the aggregator `pom.xml`.

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
