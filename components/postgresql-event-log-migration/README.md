# Essentials Components - PostgreSQL event log migration (preview)

Moves aggregate types one way from the classic event store's tables (`postgresql-event-store`) to the new event
log's (`postgresql-event-log`), in the same database. `Migration` runs a read-only `preflight()`, `prepare()`, repeated
online `copyEpoch()`s while the classic engine keeps running, and, with every classic node stopped, one `cutover()`
transaction under `SHARE` locks; `verify()`, `verifyAll()` and `verifyAfterCutover()` compare the copy by hash.
Subscriptions' resume points are imported per mapping (`ImportedSubscription`). `MigrationCli` runs the same from a YAML
file. The classic tables are only read.

> **Preview in 0.62.0.** There is no way back after the cut-over except restoring a backup: rehearse it on a copy of
> the database first.

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-event-log-migration</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

## Documentation

- [LLM-postgresql-event-log.md](../../LLM/LLM-postgresql-event-log.md#migrating-existing-data) - the procedure in short
- [docs/design/event-log.md](../../docs/design/event-log.md) §10 - how the migration works
- [docs/archive/event-store-engine-phase9.md](../../docs/archive/event-store-engine-phase9.md) - the design, its operator procedure (§5) and gate results
