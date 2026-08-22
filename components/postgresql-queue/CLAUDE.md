# postgresql-queue

PostgreSQL-backed durable queue — `FOR UPDATE SKIP LOCKED` polling, ordered/unordered delivery, dead-letter, competing consumers. Maven: `postgresql-queue`.

## Package Structure

- `dk.trustworks.essentials.components.queue.postgresql` — all main sources (flat, no sub-packages for domain logic)
- `dk.trustworks.essentials.components.queue.postgresql.jdbi` — JDBI argument/column mappers for `QueueName` and `QueueEntryId`

## Key Classes

| Class | Internal role |
|-------|---------------|
| `PostgresqlDurableQueues` | Main impl of `BatchMessageFetchingCapableDurableQueues`; owns table init, consumer registries, interceptor chain, LISTEN/NOTIFY wiring |
| `PostgresqlDurableQueuesBuilder` | Builder (prefer over constructors); default: `SingleOperationTransaction`, centralized fetcher, 20ms poll interval |
| `DurableQueuesSql` | All SQL strings; parameterized via `{:tableName}` binding → avoids injection after `PostgresqlUtil.checkIsValidTableOrColumnName` guard |
| `DurableQueuesSerialization` | Wraps `JSONSerializer`; deserializes payload + metadata; throws `DurableQueueDeserializationException` on failure (not runtime crash) |
| `QueuedMessageRowMapper` | JDBI `RowMapper<QueuedMessage>`; shared by single + batch fetch paths; injected with payload/metadata deserializer lambdas |
| `MessageMappingResult` | Record holding successful + failed mappings per batch poll; failed entries don't abort successful ones |
| `PostgresqlDurableQueueConsumer` | Traditional per-queue polling consumer; extends `DefaultDurableQueueConsumer` from foundation |
| `SingleOperationTransactionDurableQueuesInterceptor` | Wraps each `DurableQueues` operation in its own UoW when mode = `SingleOperationTransaction` |
| `QueueTableNotification` | Deserialization target for PG LISTEN/NOTIFY payloads from the `durable_queues` trigger |
| `QueueNameDuplicationFilter` | Collapses N notifications for same `queue_name` in a single poll batch into 1 → reduces redundant wakeups |
| `PostgresqlDurableQueuesStatistics` | Separate bean; implements `DurableQueuesStatistics`; owns its own table + TTL job for delivery logs |

Foundation classes used but not owned here (in `foundation` module):
- `CentralizedMessageFetcher` — single poll thread across all queues, dispatches to worker threads
- `CentralizedMessageFetcherDurableQueueConsumer` — consumer registered with centralized fetcher
- `DefaultDurableQueueConsumer` — per-queue poll-thread base (traditional mode)

## Test Structure

- All ITs under `src/test/java/.../queue/postgresql/`
- Require real Postgres via **Testcontainers** (`@Testcontainers` + `PostgreSQLContainer<>`)
- Base class pattern: `PostgresqlDurableQueuesIT` extends `DurableQueuesIT` (from foundation test module); concrete subclasses flip `useCentralizedMessageFetcher()` → `true`/`false`
- Naming convention: `Centralized*IT` = centralized fetcher, `Traditional*IT` = legacy per-consumer, `SingleOperationTransaction*IT` = explicit-ack mode
- `*PerformanceIT` / `*LoadIT` / `*LatencyIT` — throughput/latency benchmarks, not part of normal CI
- `PostgresqlDurableQueuesTest` — unit tests using Mockito mocks (no DB), covers table name validation, init behaviour

## Extension Points

- `DurableQueuesInterceptor` — implement to intercept any queue operation; registered via `builder().addInterceptor(...)` or `durableQueues.addInterceptor(...)`; order controlled by `InterceptorOrder`
- `QueuePollingOptimizer` — pluggable per-queue backoff strategy for traditional consumers (`setQueuePollingOptimizerFactory`)
- `CentralizedQueuePollingOptimizer` (or custom `QueuePollingOptimizer`) — per-queue backoff for centralized fetcher (`setCentralizedQueuePollingOptimizerFactory`)
- `JSONSerializer` — swap Jackson serializer; affects payload + metadata columns
- `NotificationDuplicationFilter` — pluggable dedup for LISTEN/NOTIFY (module ships `QueueNameDuplicationFilter`)

## Gotchas

- **`FullyTransactional` mode is broken for retries/DLQ**: rollback reverts attempt count → retry logic fails. Always use `SingleOperationTransaction` in production.
- **Table name → SQL injection risk**: `sharedQueueTableName` is interpolated directly into SQL via `{:tableName}`. `PostgresqlUtil.checkIsValidTableOrColumnName` is first-line defense only; caller must sanitize.
- **Schema auto-migrates on startup**: old composite indexes (`idx_*_queue_name`, `idx_*_next_delivery_ts` etc.) are **dropped** and replaced each start. Not zero-downtime safe across versions if index names change.
- **`useOrderedUnorderedQuery` flag**: when `true` (the default everywhere as of the queue fix), enables separate ordered/unordered CTE paths + corresponding partial indexes. Both paths must stay in sync in `DurableQueuesSql`. It used to be `false` in `PostgresqlDurableQueuesBuilder` while the Spring starter defaulted it to `true`, so non-Spring callers silently got the unified query — up to **5.4× slower** on a backlog mixing ordered and unordered messages, because that query runs the ordered per-key barrier (a correlated `NOT EXISTS`) against every candidate row including unordered ones that cannot need it, and sorts by `key_order` which is a constant `-1` for those rows. Pure-ordered traffic is indifferent. (Measured at 4000 rows, medians of 5 repetitions, PostgreSQL 17.5.) `CentralizedFetcherDurableQueueIT` / `CentralizedFetcherDurableQueueIT_WithOrderedUnordered` are the `false`/`true` pair and both now set the flag explicitly, so neither can drift onto the default.
- **Centralized fetcher is default**: `transactionalMode` defaults to `SingleOperationTransaction` (not `FullyTransactional`). **Builder and constructors now agree** — until 0.40.x the multi-arg constructors hardcoded `FullyTransactional` while `builder()` defaulted to `SingleOperationTransaction`, so the same component behaved differently depending on how it was created. They were converged on the builder's defaults (`SingleOperationTransaction` + `DEFAULT_MESSAGE_HANDLING_TIMEOUT` of 30s), because `FullyTransactional` is the side that is broken for retries/DLQ. A caller that genuinely wants `FullyTransactional` must now name it explicitly. This is a behaviour change for existing constructor callers — see `docs/MIGRATION-NEXT_MAJOR.md`.
- **`QueueNameDuplicationFilter` deduplicates within one poll batch only** — not across polls. 100 queued messages for same queue → 1 notification per poll cycle, not permanently collapsed.
- **`DurableQueueDeserializationException` keeps message in queue**: deserialization failure does not auto-DLQ the message; consumer must handle/re-throw to trigger redelivery policy.
- **Bootstrap lock**: `initializeQueueTables()` acquires `PostgresqlUtil.acquireBootstrapLock` → serializes DDL on multi-node startup; do not hold external locks that could deadlock this.
