# Essentials Components - PostgreSQL event log processors (preview)

The application layer of the new event store engine (`postgresql-event-log`):

- `EventLogViewProcessor`, `EventLogReactor`, `EventLogInboxProcessor` and `EventLogPartitionedViewProcessor`, annotated base classes with `@MessageHandler`
  dispatch that port a classic `ViewEventProcessor` or `EventProcessor` by swapping its superclass;
- `ViewProjection` (`CONTIGUOUS`, each page in one transaction with its resume point) and `EventReactor` (`FRESH`, at
  least once), built with lambdas;
- `EventLogEventStore`, the `EventStore` that aggregate repositories, snapshots and closing books use on the new engine;
- the page transaction sources `UnitOfWorkPageTransactions` and `SpringPageTransactions`;
- `EventLogSubscriptionsApi`, the admin SPI behind the admin API's `event-log-subscriptions` operations.

> **Preview in 0.62.0.** The API may change in a minor release. The classic processors do not run on the new engine;
> port them as the LLM doc describes.

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-event-log-processor</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

JDBI, Spring transactions, the PostgreSQL driver and SLF4J are `provided`: declare them yourself, or use
`spring-boot-starter-postgresql-event-log`.

Every processor base class binds the delivered event as the cause (`CausationContext`) around each handler call, so events a handler appends record it as `caused_by_event_id`.

## Documentation

- [LLM-postgresql-event-log.md](../../LLM/LLM-postgresql-event-log.md) - processors, consistency, porting, settings, gotchas
- [docs/design/event-log.md](../../docs/design/event-log.md) - how the engine and this module work
