# Admin API - LLM Reference

> Token-efficient reference for LLMs. For the contract and roadmap, see [docs/openapi/README.md](../docs/openapi/README.md).

## Quick Facts
- **Base Package**: `dk.trustworks.essentials.components.adminapi.rest`
- **Purpose**: HTTP admin/monitoring API over the in-process `*Api` SPIs — replaced the removed Vaadin admin UI
- **Key Deps**: Spring WebMVC (provided), Spring Boot (provided), Jackson 3
- **Contract**: `components/admin-api-spec/openapi/essentials-admin-api.yaml` — 40 operations, generated code-first from the SPIs
- **No Node**: build and validation are JVM-only. The bundled default UI is Thymeleaf + vanilla JavaScript; any other UI consumes the same contract

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-admin-api</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `DBFencedLockApi`, `DurableQueuesApi`, `SchedulerApi`, `PostgresqlQueryStatisticsApi` from [foundation](./LLM-foundation.md)
- `EventStoreApi`, `CdcApi`, `PostgresqlEventStoreStatisticsApi` from [postgresql-event-store](./LLM-postgresql-event-store.md)
- SPI beans are wired by [spring-boot-starter-modules](./LLM-spring-boot-starter-modules.md)

## TOC
- [Modules](#modules)
- [Endpoints](#endpoints)
- [Security](#security)
- [Configuration](#configuration)
- [Clients](#clients)
- [Gotchas](#gotchas)

## Modules

| Module | Purpose |
|--------|---------|
| `admin-api-spec` | The OpenAPI contract + drift/compatibility/validation gates. Publishes the YAML as `:yaml:openapi` and on the jar classpath at `/openapi/essentials-admin-api.yaml` |
| `spring-boot-starter-admin-api` | Serves the contract over Spring WebMVC; auto-configured |
| `admin-api-client-java` | Java client generated from the contract (`java`/`native` library) |
| `spring-boot-starter-admin-ui` | Optional default UI — Thymeleaf shell, vanilla JavaScript, served at `/essentials/admin`. Calls the endpoints above from the browser |

## Endpoints

Mounted under `/api/essentials/admin/v1` (configurable). Contract paths are relative to that.

| Tag | Paths | Required roles |
|-----|-------|----------------|
| `fenced-locks` | `GET /fenced-locks`, `DELETE /fenced-locks/{lockName}` | `essentials_lock_reader` / `essentials_lock_writer` |
| `scheduler` | `GET /scheduler/pg-cron-jobs[/count]`, `.../{jobId}/run-details[/count]`, `GET /scheduler/executor-jobs[/count]` | `essentials_scheduler_reader` |
| `postgresql-query-statistics` | `GET /postgresql/query-statistics/top-ten-slowest` | `essentials_postgresql_stats_reader` |
| `durable-queues` | `GET /durable-queues`, message get/delete/resurrect/mark-as-dead-letter, per-queue messages, dead-letters, counts, statistics, purge | `essentials_queue_reader` / `essentials_queue_writer` |
| `event-store` | `GET /event-store/subscriptions`, `GET /event-store/subscriptions/statistics`, `GET /event-store/subscriptions/{subscriberId}/aggregate-types/{aggregateType}/statistics`, `GET /event-store/aggregate-types/{aggregateType}/highest-global-event-order` | `essentials_subscription_reader` |
| `cdc` | `GET /event-store/cdc/status` | `essentials_subscription_reader` |
| `event-store-statistics` | `GET /event-store/statistics/table-sizes`, `table-activity`, `table-cache-hit-ratio` | `essentials_postgresql_stats_reader` |

`essentials_admin` satisfies every operation. Each operation lists its roles in the contract under `x-required-roles`.

Responses: `200` plus `401`, `403`, `500` on every operation, `400` where there is a parameter or body to reject, `404` on the `Optional`-returning ones. Error bodies use the contract's `Error` schema.

## Security

The API authenticates nobody and depends on no security framework. Implement two SPIs:

| SPI | Answers | Used by |
|-----|---------|---------|
| `dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser` | Who is calling? | The adapter, to resolve the principal (`401` if unauthenticated) |
| `dk.trustworks.essentials.shared.security.EssentialsSecurityProvider` | May this principal do this? | The SPI beans, per operation, per role (`403` if denied) |

How the request was authenticated — session, bearer token, mTLS, gateway header — is entirely the host's business.

**Secure by default**: both SPIs default to their no-access implementations, so an application that implements neither exposes nothing. The starter logs a warning at startup while those defaults are in place, because the API includes destructive operations (purge a queue, delete a message, release a lock).

## Configuration

| Property | Default | Purpose |
|----------|---------|---------|
| `essentials.admin-api.enabled` | `true` | Set `false` to expose nothing |
| `essentials.admin-api.base-path` | `/api/essentials/admin/v1` | Relocate the mount point, e.g. behind a gateway prefix |

## Clients

The contract's server URL is relative, so the Java client must be told where the API is mounted:

```java
var apiClient = new ApiClient();
apiClient.updateBaseUri("https://host/api/essentials/admin/v1");
```

The `native` library generates no auth helpers by design, matching the contract — attach credentials via `ApiClient.setRequestInterceptor(...)`.

## Gotchas

- **Adding an operation touches 3 places**: the `*Api` SPI, the `EssentialsAdminApiSpec` mapping table, and a controller. A conformance test fails until all three agree.
- **PostgreSQL-oriented**: the scheduler and statistics endpoints are backed by `pg_cron` and `pg_stat_statements`. The MongoDB starter wires no `*Api` beans, so there is no admin API surface there.
- **Subscription statistics are per-instance, subscription rows are not**: `GET /event-store/subscriptions` reports resume points from the database, so it lists the subscriptions of every instance, while the two `.../statistics` operations are counted in memory by the instance that answers. A subscription running elsewhere has no statistics, and an exclusive subscription only handles events where it holds its fenced lock — read a zero counter together with `active`/`lock`, never as a stall. Turn collection off with `essentials.eventstore.subscription-manager.statistics.enabled=false`, and the endpoints answer empty/`404`.
- **Destructive operations are exposed**: `DELETE /durable-queues/queues/{queueName}/messages` purges a queue. Role enforcement is entirely your `EssentialsSecurityProvider` implementation.
- **`5xx` bodies carry no detail** — the cause is logged instead, so internal failures cannot leak schema names, SQL, or hostnames.
- **Path-major versioning**: breaking changes ship as `/api/essentials/admin/v2` served side-by-side; `v1` consumers and their generated clients keep working.
