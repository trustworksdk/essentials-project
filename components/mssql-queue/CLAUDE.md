## mssql-queue

Microsoft SQL Server `DurableQueues` implementation — T-SQL port of `postgresql-queue`. Maven: `mssql-queue`.

## Package Structure

- `dk.trustworks.essentials.components.queue.mssql` — all production classes
- `dk.trustworks.essentials.components.queue.mssql.jdbi` — JDBI argument/column mappers for `QueueEntryId` and `QueueName`

## Key Classes

| Class | Role |
|---|---|
| `MsSqlDurableQueues` | Main impl of `BatchMessageFetchingCapableDurableQueues`; owns consumer maps and lifecycle |
| `MsSqlDurableQueuesBuilder` | Fluent builder; extends `JdbcDurableQueuesBuilder` — only overrides `buildDurableQueues()` |
| `MsSqlDurableQueueConsumer` | Traditional per-queue polling consumer; delegates to `DefaultDurableQueueConsumer` |
| `DurableQueuesSql` | T-SQL statement builder; extends `JdbcDurableQueuesSql`; overrides pagination, dead-letter literals, unordered/ordered fetch CTEs |
| `DurableQueuesSerialization` | Thin subclass of `jdbc.DurableQueuesSerialization`; delegates `createDefaultObjectMapper()` upward |
| `SingleOperationTransactionDurableQueuesInterceptor` | Thin subclass of `jdbc.SingleOperationTransactionDurableQueuesInterceptor` |
| `QueuedMessageRowMapper` | Thin subclass of `jdbc.QueuedMessageRowMapper` (JDBI `RowMapper<QueuedMessage>`) |
| `QueueTableNotification` | Thin subclass of `jdbc.QueueTableNotification`; payload for `MultiTableChangeListener` |
| `QueueNameDuplicationFilter` | Thin subclass; guards against duplicate consumer registration |
| `MsSqlDurableQueuesStatistics` | Queue size/DLQ stats queries; thin subclass |
| `MessageMappingResult` | Thin subclass of `jdbc.JdbcMessageMappingResult` |
| `jdbi/QueueEntry/QueueNameArgumentFactory` | JDBI bind adapters for Essentials value types |
| `jdbi/QueueEntry/QueueNameColumnMapper` | JDBI read adapters for Essentials value types |

## Test Structure

All ITs are in `src/test` under package `dk.trustworks.essentials.components.queue.mssql`
(earlier iterations used `...queue.postgresql` — watch for stale package refs in test reports).

Test matrix dimensions:
- **Fetcher mode**: `Centralized*` (default, batch polling via `CentralizedMessageFetcher`) vs `Traditional*` (per-queue polling)
- **Tx mode**: plain `FullyTransactional` vs `SingleOperationTransaction*`
- **Consumer scope**: `*LocalCompetingConsumers*` vs `*DistributedCompetingConsumers*`
- **Message ordering**: `*LocalOrderedMessages*`, `*LocalOrderedMessagesRedelivery*`
- **Load/latency/perf**: `*LoadIT`, `*LatencyIT`, `*PerformanceIT`

Base class `MsSqlDurableQueuesIT` extends `DialectDurableQueuesITBase` (from `jdbc-queue-base` test-jar).
Concrete subclasses are trivial — just flip `useCentralizedMessageFetcher()` or tx mode.

Infrastructure: Testcontainers `MsSqlGenericContainer` (wraps `MSSQLServerContainer`) — Docker required.
`JavaTimeSupport.install(jdbi)` must be called before creating `JdbiUnitOfWorkFactory` for MSSQL.

## Extension Points

- `JdbcDurableQueuesBuilder` — extend for new JDBC dialects; override `buildDurableQueues()`
- `JdbcDurableQueuesSql` — override T-SQL-specific methods: `buildUnorderedSqlStatement()`,
  `buildOrderedSqlStatement()`, `getGetQueuedMessagesPaginationSql()`,
  `getDeadLetterTrueSqlValue()` / `getDeadLetterFalseSqlValue()`
- `DurableQueuesInterceptor` — intercept any queue operation (interceptor chain pattern)
- `QueuePollingOptimizer` — adaptive polling backoff per consumer

## Gotchas

**T-SQL vs Postgres SQL divergence** — key differences in `DurableQueuesSql`:
- Uses `TOP (:limit)` not `LIMIT :limit`
- Uses `WITH (READPAST, UPDLOCK, ROWLOCK)` table hints instead of `FOR UPDATE SKIP LOCKED`
- Uses `OUTPUT inserted.*` instead of `RETURNING *`
- CTE prefix is `;WITH` (statement terminator required before CTE)
- Boolean literals are `0`/`1` (BIT), not `TRUE`/`FALSE`
- `key` column must be bracketed as `[key]` (reserved word in T-SQL)
- Pagination uses `OFFSET ... ROWS FETCH NEXT ... ROWS ONLY`

**Schema — no DDL auto-creation**: `MsSqlDurableQueues` does NOT auto-create the queue table.
Schema must be provided externally. Recommended column types and filtered indexes are documented
in the `DurableQueuesSql` class Javadoc.

**Centralized fetcher is default**: `useCentralizedMessageFetcher=true` by default (20ms poll interval).
Traditional per-queue consumers still available but not the primary path.

**`SingleOperationTransaction` mode**: consumer must explicitly call `acknowledgeMessageAsHandled()`
in a new `UnitOfWork`. Stuck-message reset runs periodically per queue; timeout tracked in
`lastResetStuckMessagesCheckTimestamps`.

**`sharedQueueTableName` injection risk**: table name concatenated into SQL strings.
`PostgresqlUtil.checkIsValidTableOrColumnName()` is called as first-line defense only — caller
must sanitize from trusted sources.

**JDBI type registration**: `QueueEntryIdArgumentFactory`, `QueueEntryIdColumnMapper`,
`QueueNameArgumentFactory`, `QueueNameColumnMapper` must be registered on the `Jdbi` instance
(handled inside `MsSqlDurableQueues` constructor).

**Source files absent from `src/`**: module source lives only in git history and compiled classes.
Use `git show <sha>:components/mssql-queue/src/...` to browse source.
