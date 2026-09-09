# postgresql-queue-shard-owned-adapter

Presents the shard-owned engine as a `DurableQueues`, so `Inbox`, `Outbox` and `DurableLocalCommandBus` run on it unchanged. **Experimental, NOT published** (`maven.deploy.skip=true`) — follows the engine it adapts, and has its own reason: it serves the `DurableQueues` surface only partially.

Engine docs: `docs/durable-queue-shard-owned.md`.

## Why this is a small adapter and not a rewrite

Inbox, Outbox and `DurableLocalCommandBus` touch **8** of `DurableQueues`' 30 methods and never touch a `QueueEntryId`: `queueMessage`, `queueMessages`, `consumeFromQueue`, `purgeQueue`, `getTotalMessagesQueuedFor`, `getUnitOfWorkFactory`, `getTransactionalMode`.

## Classes

| Class | Responsibility |
|---|---|
| `ShardOwnedDurableQueues` | The adapter. Builder-constructed |
| `ShardOwnedDurableQueueConsumer` | `DurableQueueConsumer` over a `Subscription` |
| `ShardOwnedQueuedMessage` | `QueuedMessage`, in a full and a partial shape |
| `MessageEnvelope` | The persisted payload format |
| `QueueEntryIdCodec` | `QueueEntryId` <-> `(QueueName, MessageId)` |

## Gotchas

- **`InboxName.asQueueName()` is `Inbox:<name>`, and the codec's separator is a colon.** So every queue this module exists to serve contains the separator. `decode` splits on the **last** colon — a `MessageId` never contains one. Splitting on the first truncates every inbox and outbox to `"Inbox"`/`"Outbox"` and reports "no such message" against a queue that exists. `an_inbox_or_outbox_queue_name_survives_although_it_contains_the_separator` derives the names from the real types, so a change to that derivation breaks the test rather than production.
- **The queue name has to be inside the `QueueEntryId`.** Most of the by-id surface takes an entry id and nothing else, which works for `PostgresqlDurableQueues` because its ids are UUIDs. A `MessageId` is unique per queue only — `u-0-1` exists in every queue — so ignoring that would let `deleteMessage` delete an unrelated queue's message and return `true`.
- **`SingleOperationTransaction` only; `FullyTransactional` is refused, not approximated.** Acks are batched and flushed on the owner's connection under a fence, and cannot enlist in a caller's transaction. Reporting the mode without honouring it converts "handler writes and dequeue commit together" into duplicates after a crash.
- **Enqueue IS transactional and that is the load-bearing test.** `HandleAwareUnitOfWork.handle().getConnection()` -> `enqueue(Connection, …)`. `an_outbox_enqueue_rolls_back_with_the_callers_transaction` was verified to fail when that path is disabled — without it the Outbox leaves messages describing work that rolled back.
- **`ShardOwnedQueuedMessage` is partial on the push path, and throws rather than stubs.** The engine's `MessageHandler` receives `(key, payload, payloadType)` only — no id, no attempt count, no timestamps. They exist on the row; passing them means widening the handler (3 call sites, 2 internal interfaces, ~81 lambda sites) **and** adding `attempts` to `ShardOwnedStorage.Row`, which is an extra column on the hot cursor read. A stub `getTotalDeliveryAttempts()` of 0 would make attempt-keyed logic silently never fire.
- **`markForRedeliveryIn(delay)` redelivers, but not after `delay`.** The adapter turns it into a throw, and the engine schedules from its own policy — there is no id to schedule against from inside a handler. The attempt also counts against the policy's budget.
- **Retry is the engine's, not `DefaultDurableQueueConsumer`'s.** That class implements redelivery itself; this consumer does not use it. Running both would put two retry clocks on one message. The `RedeliveryPolicy` is translated once into `ConsumerOptions` at subscription time — note the off-by-one: the policy counts *re*deliveries, the engine counts attempts.
- **`MessageEnvelope` is a persisted format from the first enqueue.** `payloadType` (the engine's opaque int) carries `FORMAT_VERSION`, so a future format is distinguishable per row — it is the only field outside the envelope, hence the only one readable before deciding how to parse. Payload and metadata are nested JSON *strings*, which is why they appear escaped in the readable views; a flat three-string object beats embedding a document whose shape depends on an unresolved type.
- **Metadata travels as its own envelope field**, not merged into the payload — a payload with its own `metaData` property would otherwise collide.
- **An unknown queue name fails by default.** `DurableQueues` invents a queue on first use; this engine will not invent a shard count. `setAutoRegisterShardCount(n)` opts in, and commits every new queue to a number nobody chose.
- **Pass the same `MessageQueues` the rest of the process uses.** Two registries each build their own `MessageQueue` per name, which register as two competing consumers and get half the shards each.
- **Not supported, each for a structural reason, all throwing with it:** `addInterceptor`/`removeInterceptor` (the engine has its own chain; accepting and ignoring makes a tracing interceptor produce silence), `getQueuedMessages` and `queryForMessagesSoonReadyForDelivery` (global sort order over per-shard cursor reads), `hasOrderedMessageQueuedForKey` (no per-key lookup in the SPI), `getNextMessageReadyForDelivery` (needs a row-lease session outliving the call).
- **`resurrectDeadLetterMessage` returns empty even on success.** The message re-enters at a fresh sequence, so its `QueueEntryId` changes and the old one no longer addresses it.
- **Two `queue` packages export `QueueName`, `Message` and `QueuedMessage`.** Single-type imports for the engine's other types; those three written out in full wherever an engine one is meant. Star-importing both is a compile error, and it is the first thing that happens to anyone adding a file here.
- **The test module needs both Jackson majors, the datatype modules and objenesis explicitly.** `foundation` declares them optional/provided, so none is transitive, and `EssentialsObjectMappers` picks a branch at runtime — naming one flavor makes the tests fail under the other profile instead of exercising it.

## Not done

- No Spring auto-configuration. Wiring a flag that selects this `DurableQueues` bean belongs in the starter that owns that bean.
- `getQueueNameFor` works (it reads the id), but the admin API and UI need the listing operations that do not.
