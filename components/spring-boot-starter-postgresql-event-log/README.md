# Essentials Components - Spring Boot starter for the PostgreSQL event log (preview)

Auto-configuration for the new PostgreSQL event store engine (`postgresql-event-log`) and its `EventStore` adapter
(`postgresql-event-log-processor`).

> **Preview in 0.62.0.** The starter, its properties and the engine's API may change in a minor release. The old event store
> (`spring-boot-starter-postgresql-event-store`) stays the default and fully supported.

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql-event-log</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

The starter is **off by default**. Turn it on with:

```yaml
essentials:
  event-log:
    enabled: true
```

Enabled, it provides the `EventLog`, an `EventStore` bean (`EventLogEventStore`) that aggregate repositories use as
before, a `PageTransactionSource` for `ViewProjection`s, and a lifecycle bean that starts every `ViewProjection` and
`EventReactor` bean in the context once it is refreshed and stops them on shutdown. With the admin API starter present it
also provides the `EventLogSubscriptionsApi` behind the admin API's event-log subscription endpoints.

Every engine setting is a property under `essentials.event-log.*` (for example `backstop-interval`, `page-size`,
`lease-duration`, `node-name`); your IDE completes them from the configuration metadata. `essentials.schema.mode` decides
who creates the schema, as for every other Essentials component.

## Beside the old event-store starter

If `spring-boot-starter-postgresql-event-store` is on the class path too, this starter removes that starter's
`EventStoreConfiguration` while `essentials.event-log.enabled=true`. Snapshots, closing books and the aggregate admin
operations keep working on the new engine. The old `EventProcessor`, `ViewEventProcessor` and `InTransactionEventProcessor`
do not run on it: start-up fails with a message naming each such bean, its class and the base class to port it to
(`EventLogViewProcessor`, `EventLogReactor` or `EventLogInboxProcessor`); port it by swapping the superclass.

Requires the same provided dependencies as `spring-boot-starter-postgresql` (PostgreSQL driver, JDBI, Jackson 3, Reactor).

Observability: with a `MeterRegistry` bean the engine's Micrometer meters are installed (`essentials.event-log.metrics.enabled`); the old starter's append-timing and (with `management.tracing.enabled=true`) tracing interceptors are declared under the old properties, so appended events carry the trace context. See `LLM/LLM-postgresql-event-log.md` § Observability.

## Documentation

- [LLM-postgresql-event-log.md](../../LLM/LLM-postgresql-event-log.md) - the consumer reference: processors, porting, settings, limits
- [docs/design/event-log.md](../../docs/design/event-log.md) - how the engine works
