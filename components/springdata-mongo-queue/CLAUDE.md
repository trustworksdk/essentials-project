# springdata-mongo-queue

MongoDB-backed `DurableQueues` implementation using Spring Data MongoDB. Maven: `springdata-mongo-queue`.

Status: **WORK-IN-PROGRESS**

## Package Structure

- `dk.trustworks.essentials.components.queue.springdata.mongodb` — all production code (2 classes)

## Key Classes

| Class | Role |
|---|---|
| `MongoDurableQueues` | Core impl of `DurableQueues`; owns collection setup, indexes, polling, Change Stream listener, ordered-message logic, stuck-message reset |
| `MongoDurableQueueConsumer` | Thin subclass of `DefaultDurableQueueConsumer`; parameterized for Mongo UoW types; no real logic of its own |
| `DurableQueuedMessage` (inner `@Document`) | MongoDB document + `QueuedMessage` impl; payload stored as `byte[]`, deserialized lazily on first `getMessage()` call via injected `QuadFunction` |

## Document Schema

Single shared collection (default: `durable_queues`). Key fields:
- `queueName`, `isBeingDelivered`, `isDeadLetterMessage`, `nextDeliveryTimestamp`
- `key`, `keyOrder` — ordered-message support
- `messagePayload` (byte[]), `messagePayloadType` (FQCN)
- `totalDeliveryAttempts`, `redeliveryAttempts`, `lastDeliveryError`

Auto-created indexes on startup: `next_msg`, `ordered_msg`, `stuck_msgs`, `find_msg`, `resurrect_msg`. Outdated indexes (field set mismatch) are dropped and recreated.

## Transaction Modes

`TransactionalMode` enum controls two operating modes:

**`SingleOperationTransaction`** (recommended) — each queue op is its own atomic Mongo operation. Requires `messageHandlingTimeout`. Stuck-message reset runs lazily per poll cycle when timeout elapsed.

**`FullyTransactional`** — requires `SpringMongoTransactionAwareUnitOfWorkFactory`; queue writes participate in the ambient UoW/transaction. Needs MongoDB replica set.

## Polling & Notification

- `startCollectionListener()` subscribes a Mongo Change Stream on insert/update/replace → wakes relevant `MongoDurableQueueConsumer` via `messageAdded()`.
- Change Streams require replica set. DocumentDB error 136 → logged as info, not error.
- `getNextMessageReadyForDelivery` holds per-queue `ReentrantLock` (fair, 1s tryLock) to reduce write conflicts on concurrent pollers.
- `QueuePollingOptimizer` default: `SimpleQueuePollingOptimizer` with min=50% polling interval, max=20× polling interval.

## Ordered Messages

`OrderedMessage` → stored with `deliveryMode=IN_ORDER`, `key`, `keyOrder`.

On fetch: if lower-`keyOrder` message with same key exists → reschedule current (push `nextDeliveryTimestamp` past predecessor). If predecessor is dead-letter → cascade current and all higher-order siblings to dead-letter. Logic lives in `resolveIfMessageShouldBeDelivered()`.

## Deserialization Error Handling

`DurableQueueDeserializationException` during fetch → message immediately marked dead-letter via `markAsDeadLetterMessageInternal()`. In `FullyTransactional` mode this opens a new session (current UoW is rolled back) to write the dead-letter status.

## Extension Points

- `DurableQueuesInterceptor` — intercept any queue operation; add via `addInterceptor()`. Sorted by `@Order` on `start()` and on add.
- `QueuePollingOptimizer` / `queuePollingOptimizerFactory` — supply custom optimizer per consumer via constructor param.
- Subclass `MongoDurableQueues` (`protected` constructor) — override collection name, inject custom serializer.

## Test Structure

All tests in `dk.trustworks.essentials.components.queue.springdata.mongodb`.

- `MongoDurableQueuesIT` — extends shared `DurableQueuesIT` (foundation module); `@Testcontainers` + `MongoDBContainer("mongo:latest")` replica-set URL, `@DataMongoTest`, `@DirtiesContext` per method.
- `SingleOperationTransactionMongo*IT` — same harness wired for `SingleOperationTransaction` mode.
- `MongoDistributedCompetingConsumersDurableQueuesIT` / `MongoLocalCompetingConsumers*IT` — competing-consumer scenarios.
- `MongoLocalOrderedMessages*IT` — ordered-message delivery and redelivery.
- `MongoDuplicateConsumptionDurableQueuesIT` — idempotency edge cases.
- `MongoDurableQueuesIndexIT` — verifies index creation/upgrade.
- `MongoDurableQueuesTest` — unit test (no container).
- `DurableLocalCommandBusIT` — command bus wired over `MongoDurableQueues`.

All ITs need Docker (Testcontainers spins up MongoDB).

## Gotchas

- Collection name is lower-cased on construction; `MongoUtil.checkIsValidCollectionName()` is first-line defense but not exhaustive — never derive name from untrusted input.
- `DurableQueuedMessage.getMessage()` requires `deserializeMessagePayloadFunction` to be injected before call; omitting `setDeserializeMessagePayloadFunction()` → NPE. Injection happens in `getNextMessageReadyForDelivery`, `getQueuedMessage`, and `queryQueuedMessages`.
- `acknowledgeMessageAsHandled` matches on `isDeadLetterMessage=false`; if handler calls `markAsDeadLetterMessage` mid-flight then ack returns `true` via secondary DLQ check — this is intentional.
- `FullyTransactional` + deserialization error opens fresh Mongo session with explicit `MAJORITY` write concern — bypasses the in-flight UoW on purpose.
- `QueuePollingOptimizer` default factory is `this::createQueuePollingOptimizerFor`; passing `null` for factory in constructors falls back to this default — not null-safe if overridden improperly in subclasses.
- `sharedQueueCollectionName` lowercased in constructor → collection names always lowercase regardless of input case.
