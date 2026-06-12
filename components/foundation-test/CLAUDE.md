# foundation-test

Reusable abstract IT base classes for testing `foundation` module implementations. Maven: `foundation-test`.

All classes live in `src/main/java` — shipped as a test-support library consumed by DB-specific modules (postgresql, mongodb, etc.), not a standalone test module.

## Package Structure

- `...foundation.test.fencedlock` — abstract ITs for `DBFencedLockManager` implementations
- `...foundation.test.messaging.queue` — abstract ITs for `DurableQueues` implementations; includes `test_data/` domain fixtures
- `...foundation.test.messaging.queue.test_data` — shared domain value objects (`OrderId`, `CustomerId`, `ProductId`, `AccountId`) and events (`OrderEvent`, `ProductEvent`)
- `...foundation.test.reactive.command` — abstract IT for `DurableLocalCommandBus`

## Key Classes

| Class | Role |
|---|---|
| `DBFencedLockManagerIT` | Two-node lock manager suite: acquire/release/timeout/DB-disruption scenarios. Subclass supplies `createLockManagerNode1/2()`, `disruptDatabaseConnection()`, `restoreDatabaseConnection()`, `isConnectionRestored()` |
| `DBFencedLockManager_MultiNode_ReleaseLockIT` | Focused variant: multi-node release semantics only |
| `DurableQueuesIT` | Core queue suite: enqueue, consume-in-order, redelivery, dead-letter, resurrection, ordered messages, interceptors, deserialization failure isolation |
| `DistributedCompetingConsumersDurableQueuesIT` | Two `DurableQueues` instances against shared storage; 1000 msgs, 20 parallel consumers — verifies no duplicates |
| `LocalCompetingConsumersDurableQueueIT` | Single-node competing-consumer correctness |
| `LocalOrderedMessagesDurableQueueIT` | 2000 ordered msgs, 20 consumers — verifies per-key ordering guarantee |
| `LocalOrderedMessagesRedeliveryDurableQueueIT` | Redelivery semantics for ordered messages |
| `DuplicateConsumptionDurableQueuesIT` | Verifies exactly-once-like delivery under concurrent consumers |
| `DurableQueuesLoadIT` | Load/stress variant |
| `AbstractDurableLocalCommandBusIT` | Command bus backed by `DurableQueues`; sync send, send-and-don't-wait, error handler wiring |
| `ProxyJSONSerializer` | Wraps real `JSONSerializer`; can inject truncated-JSON corruption per payload type (one-shot or persistent) to simulate missing-class deserialization failures |
| `RecordingQueuedMessageHandler` (inner) | `ConcurrentLinkedQueue`-backed message recorder; optional `Consumer<Message>` for side-effects/throw |
| `TestLockCallback` (inner) | Captures `lockAcquired`/`lockReleased` events for assertions |

## Test Structure

- All ITs are `abstract` — no `@SpringBootTest`, no Testcontainers here. DB setup is delegated to subclass.
- Subclass provides: factory methods (`createDurableQueues`, `createUnitOfWorkFactory`, `resetQueueStorage`, `createLockManagerNode1/2`, etc.).
- `@BeforeEach` calls factory methods then starts the SUT; `@AfterEach` stops and nulls.
- Awaitility used for async assertions; typical timeouts 2–10 s.
- `withDurableQueue(Supplier)` / `usingDurableQueue(Runnable)` helpers in `DurableQueuesIT` branch on `TransactionalMode` — `FullyTransactional` wraps in UoW, `SingleOperationTransaction` runs bare.
- `DBFencedLockManagerIT.deleteAllLocksInDBWithRetry` retries up to 5x with backoff on `WriteConflict` — needed for multi-node setups sharing one DB schema.

## Extension Points

Implement these abstract methods in the consuming module's concrete IT:

**DurableQueues ITs**
- `createDurableQueues(UOW_FACTORY, JSONSerializer)` — wire the concrete impl
- `createUnitOfWorkFactory()` — e.g. Jdbi/Jooq factory pointing at Testcontainer
- `resetQueueStorage(UOW_FACTORY)` — truncate queue tables before each test
- `createJSONSerializer()` — wrapped by `ProxyJSONSerializer` automatically

**FencedLock ITs**
- `createLockManagerNode1/2()` — two separate manager instances (simulating two JVM nodes)
- `disruptDatabaseConnection()` / `restoreDatabaseConnection()` / `isConnectionRestored()` — inject network fault

**CommandBus IT**
- `createDurableQueues(UOW_FACTORY)` — queue backend
- `createUnitOfWorkFactory()` — tx factory

## Gotchas

- `ProxyJSONSerializer` one-shot corruption auto-disables after first corrupt call; pass `persistent=true` to keep corrupting (simulates class rename/removal across all redeliveries).
- `DBFencedLockManagerIT` guard in `@BeforeEach` throws if `lockManagerNode1/2` is non-null — JUnit state leak will be caught immediately rather than silently re-using stale managers.
- `DBFencedLockManager_MultiNode_ReleaseLockIT` reuses `deleteAllLocksInDBWithRetry` via static import from `DBFencedLockManagerIT` — not a copy.
- `verify_loosing_db_connection_no_locks_are_released` deliberately allows either node to hold the lock post-reconnect (race condition is acknowledged) — assertions branch on which node holds it.
- `DistributedCompetingConsumersDurableQueuesIT` spins up two independent `DurableQueues` instances against the same storage — subclass must support that (shared connection pool / same schema).
- `DurableQueuesIT` calls `durableQueues.addInterceptor(...)` in-test to test two-stage redelivery; interceptors added mid-test affect all subsequent operations in that test only.
