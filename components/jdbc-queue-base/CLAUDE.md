# jdbc-queue-base

Shared JDBC/JDBI abstractions for durable queue implementations across SQL dialects. Maven: `jdbc-queue-base`.

Not a standalone queue — base layer consumed by `postgresql-queue` and `mssql-queue`.

## Package Structure

- `dk.trustworks.essentials.components.queue.jdbc` — core support classes: SQL templates, row mapping, stuck-message reset, serialization, notifications
- `dk.trustworks.essentials.components.queue.jdbc.jdbi` — JDBI argument/column mapper factories for `QueueName` and `QueueEntryId`
- `dk.trustworks.essentials.components.queue.jdbc.test` — abstract ITs that dialect implementations extend (lives in test jar)
- `dk.trustworks.essentials.components.queue.postgresql.test_data` — shared test fixtures (domain events, test message factory)

## Key Classes

| Class | Role |
|---|---|
| `JdbcDurableQueuesBuilder<DURABLE_QUEUES,SELF>` | Abstract fluent builder; dialect subclass overrides `buildDurableQueues(...)` and `self()` |
| `JdbcDurableQueuesSql` | Abstract SQL template base; subclasses provide dialect-specific pagination and boolean literals |
| `QueuedMessageRowMapper` | JDBI `RowMapper` for `QueuedMessage`; accepts pluggable payload/metadata deserializer lambdas |
| `JdbcMessageMappingSupport` | Static helpers: wraps JDBI `Query` execution, isolates per-row deserialization errors |
| `JdbcMessageMappingResult<F>` | Holds successful `QueuedMessage` list + typed `FailedMessageMapping` list from one query |
| `JdbcFailedMessageMappingHandler` | Converts deserialization failures → dead-letter messages via injected `markAsDeadLetterDirect` fn |
| `JdbcBatchFetchSupport` | Static helpers: group by queue, slot-checking, `useOrderedUnorderedQuery` fetch strategy, optimizer updates |
| `JdbcStuckMessagesResetSupport` | Resets messages stuck as `isBeingDelivered`; only fires in `SingleOperationTransaction` mode with time-gating |
| `JdbcSqlExecutionSupport` | `executeTableNameSql` — validates table name then interpolates `{:tableName}` and executes |
| `JdbcDurableQueueConsumer<DURABLE_QUEUES>` | Thin subclass of `DefaultDurableQueueConsumer` binding to `HandleAwareUnitOfWork` |
| `SingleOperationTransactionDurableQueuesInterceptor` | `DurableQueuesInterceptor` that wraps every operation in a fresh UoW for `SingleOperationTransaction` mode |
| `JdbcDurableQueuesStatistics` | Implements `DurableQueuesStatistics`; writes/queries a separate stats table |
| `QueueTableNotification` | `TableChangeNotification` subclass; deserialized from DB trigger JSON payload |
| `QueueNameDuplicationFilter` | `NotificationDuplicationFilter` that collapses N notifications for same `queue_name` within one poll |
| `DurableQueuesSerialization` | Builds default `ObjectMapper` with all required modules |
| `QueueEntryIdArgumentFactory` / `QueueNameArgumentFactory` | JDBI argument factories (bind typed CharSequence IDs to SQL params) |
| `QueueEntryIdColumnMapper` / `QueueNameColumnMapper` | JDBI column mappers (result set → typed ID) |

## Test Structure

- Abstract bases in `dk.trustworks.essentials.components.queue.jdbc.test` published in `*-tests.jar`
- `DialectDurableQueuesITBase<DURABLE_QUEUES>` — extends foundation `DurableQueuesIT`; dialect subclasses implement `createDialectDurableQueues(...)` and `useCentralizedMessageFetcher()`
- `AbstractDurableQueuesPerformanceIT`, `AbstractDurableQueuesLatencyIT`, `AbstractCentralizedToggleDurableQueuesLoadIT` — perf/load IT bases
- `AbstractCentralizedToggleDurableLocalCommandBusIT`, `AbstractSingleOperationTransactionDurableLocalCommandBusIT` — command bus ITs
- `DurableQueuesTestSupport` — static factory for `MultiTableChangeListener`, `QueuePollingOptimizer`, and drop-table helpers
- Tests in dialect modules (postgresql-queue, mssql-queue) extend these bases and provide Testcontainers DB

## Extension Points

- `JdbcDurableQueuesBuilder` — subclass per dialect; override `buildDurableQueues(...)` with all parameters
- `JdbcDurableQueuesSql` — subclass per dialect; implement `getGetQueuedMessagesPaginationSql()`, `getDeadLetterTrueSqlValue()`, `getDeadLetterFalseSqlValue()`
- `DialectDurableQueuesITBase` — subclass per dialect to run the full IT suite
- `DurableQueuesInterceptor` — register additional interceptors alongside `SingleOperationTransactionDurableQueuesInterceptor`

## Gotchas

- `JdbcStuckMessagesResetSupport` only activates in `SingleOperationTransaction` mode — `FullyTransactional` mode relies on transaction rollback instead; reset is time-gated per queue to avoid DB hammering
- `useOrderedUnorderedQuery` flag triggers separate ordered/unordered fetches (ordered first, fallback to unordered if empty) vs. single combined query — impacts latency vs. throughput tradeoff
- Table names passed through `{:tableName}` interpolation (not SQL params) → validated early via `PostgresqlUtil.checkIsValidTableOrColumnName`; never derive from untrusted input
- `QueueNameDuplicationFilter` deduplication scoped to single notification poll batch only — multiple polls can each deliver one notification per queue
- `JdbcMessageMappingSupport` swallows per-row deserialization exceptions; caller must check `failedMappings()` and invoke `JdbcFailedMessageMappingHandler` to dead-letter them — skipping this silently drops messages
- `JdbcDurableQueueConsumer` is a near-empty class; real polling logic lives in `DefaultDurableQueueConsumer` (foundation)
- `centralizedMessageFetcherPollingInterval` defaults to 20ms; centralized fetcher is on by default (`useCentralizedMessageFetcher=true`) — disable only if per-consumer polling is intentional
