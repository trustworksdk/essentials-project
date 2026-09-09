# spring-boot-starter-postgresql-queue-shard-owned

Spring Boot auto-configuration for `postgresql-queue-shard-owned`. **Experimental, NOT published** (`maven.deploy.skip=true`) — follows the engine it configures.

Engine docs: `docs/durable-queue-shard-owned.md`. Consumer reference: `LLM/LLM-postgresql-queue-shard-owned.md`.

## What it wires

| Bean | Notes |
|---|---|
| `ShardOwnerSettings` | From `essentials.shard-owned-queue.*` |
| `ShardRuntime` | **One per process.** `destroyMethod = "stop"` |
| `ShardOwnedQueueInitializer` | Non-destructive schema init + registers configured queues. Constructor does the work so failure fails the context |
| `ShardOwnedQueueFactory` | `queue(name)` → cached `MessageQueue` |

## Gotchas

- **No `DurableQueues` bean, deliberately.** The engine implements its own `MessageQueue` SPI; Inboxes/Outboxes/EventProcessor/admin API are not wired. Whether an adapter should exist is an open decision and the starter must not imply one.
- **Schema init must stay non-destructive.** It runs on every boot. `ShardOwnedSchema.initialize` is the safe one; `recreate` drops everything and is for tests. The engine's only entry point used to be the destructive one.
- **The factory caches per name, and that is correctness.** Two `MessageQueue`s for one name in one process register as two competing consumers and halve each other's fair share.
- **`instanceId` defaults to a random UUID per boot, not the hostname** — two instances on one host would collide, and a collision makes two processes look like one to the fair-share rebalance.
- **`ShardOwnedQueueFactory` takes the initializer as a constructor arg it never stores.** That is the bean-ordering edge: without it Spring may build the factory before the schema exists.
- **Queue names are config data, not bean names.** Mapping `application.yml` keys to bean names would make that mapping part of the contract.
- **A shard-count change fails the context.** Re-declaring a registered queue with a different count is refused by the registry — accepting it re-routes every key.
- **Tests share one static container, so `@BeforeEach` calls `recreate`.** Without it, one test's `orders=2` makes another's `orders=4` fail — the protection working against a test that forgot it shares a database.
