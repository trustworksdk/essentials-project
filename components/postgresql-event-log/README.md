# Essentials Components - PostgreSQL event log (new event store engine, preview)

The new PostgreSQL event store engine: append, stream loads and watermark-based subscriptions in two consistency modes
(`CONTIGUOUS` and `FRESH`), with engine leases for exclusive subscriptions, page transactions, retry and parking, and
cross-JVM wake-ups. Plain Java on raw pgjdbc; payloads are JSON text, stored byte for byte.

> **Preview in 0.61.0.** The API, the settings and the table layout may change in a minor release. The classic event
> store (`postgresql-event-store`) stays the default and fully supported.

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-event-log</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

Most applications do not use this module directly. They use `spring-boot-starter-postgresql-event-log`, which
declares the `EventLog`, the `EventStore` adapter and the processors' dependencies, and write their processors with the
base classes of `postgresql-event-log-processor`.

The engine refuses to start on a database where the application's role cannot see other backends' `backend_xid` in
`pg_stat_get_activity` (grant `pg_read_all_stats`).

## Documentation

- [LLM-postgresql-event-log.md](../../LLM/LLM-postgresql-event-log.md) - the consumer reference: when to use it, processors, porting, settings, limits
- [docs/design/event-log.md](../../docs/design/event-log.md) - how the engine works: storage, append, the tail reader, subscriptions, leases, invariants, measurements
