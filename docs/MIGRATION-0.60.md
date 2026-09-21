# Migration guide — 0.60

0.60 is a breaking release across several modules. This guide collects what a consumer has to change, one entry
per change. Entries are added as the work lands, so this document grows through the release.

For the 0.50 deprecations whose removal this release carries out, see
[MIGRATION-NEXT_MAJOR.md](./MIGRATION-NEXT_MAJOR.md).

---

## Durable queues

### `useOrderedUnorderedQuery` is removed

The queue had two claim-query implementations: a single unified query, and a pair of separate ordered/unordered
queries selected by `useOrderedUnorderedQuery`. Since 0.50.0 the flag defaulted to `true` in every construction
route, so effectively every deployment already ran the ordered/unordered pair. The unified query and the flag
that selected it are now gone, and the ordered/unordered pair is the only per-queue claim path.

**Removed:**

| Element | Where |
|---|---|
| `PostgresqlDurableQueuesBuilder.setUseOrderedUnorderedQuery(boolean)` | `postgresql-queue` |
| `PostgresqlDurableQueues.isUseOrderedUnorderedQuery()` | `postgresql-queue` |
| the `useOrderedUnorderedQuery` constructor parameter, from both constructors that declared it | `postgresql-queue` |
| `DurableQueuesSql.buildGetNextMessageReadyForDeliverySqlStatement(Collection<String>)` | `postgresql-queue` |
| `essentials.durable-queues.use-ordered-unordered-query` | `spring-boot-starter-postgresql` |

**What to do:** delete the builder call, the constructor argument or the property. There is no replacement,
because there is no longer a choice to express. If you were setting it to `true` — the default — behaviour is
unchanged. If you were setting it to `false`, you now get the ordered/unordered pair; it was measured 5.4×
faster on the unified query's own workload (`docs/RELEASE-NOTES-0.50.0.md` §1.1.3).

The two constructors that took the flag change arity, so a positional call site will not compile:

```java
// Before
new PostgresqlDurableQueues(unitOfWorkFactory, jsonSerializer, tableName, listener,
                            optimizerFactory, transactionalMode, messageHandlingTimeout,
                            useCentralizedMessageFetcher, pollingInterval, centralizedOptimizerFactory,
                            useOrderedUnorderedQuery);

// After
new PostgresqlDurableQueues(unitOfWorkFactory, jsonSerializer, tableName, listener,
                            optimizerFactory, transactionalMode, messageHandlingTimeout,
                            useCentralizedMessageFetcher, pollingInterval, centralizedOptimizerFactory);
```

Both constructors remain `@Deprecated(forRemoval = true)`; prefer `PostgresqlDurableQueues.builder()`.

### Two indexes are dropped on startup

`idx_<table>_next_msg` and `idx_<table>_ready` existed only to serve the removed unified query.
`PostgresqlDurableQueues` now issues `DROP INDEX IF EXISTS` for both during table initialisation, alongside the
older index drops it already performed, so an upgraded deployment stops paying write amplification for indexes
nothing reads.

⚠️ **Index drop and recreate on startup is not zero-downtime safe** across a version where index names change.
On a large queue table, plan the upgrade as you would any other index change: the drops run inside the
bootstrap transaction while the framework holds its advisory lock, so a concurrently starting instance waits.

The four remaining indexes (`idx_<table>_ordered_msg`, `idx_<table>_ordered_ready`,
`idx_<table>_unordered_ready`, `idx_<table>_ordered_head`) are unchanged.
