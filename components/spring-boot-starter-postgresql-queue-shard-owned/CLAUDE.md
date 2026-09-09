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
| `ShardOwnedQueuesApi` | Only when the admin API starter and an `EssentialsSecurityProvider` are present |
| `ShardOwnedQueuesController` | Same condition. Serves under the admin API base path |

## Gotchas

- **No `DurableQueues` bean, deliberately.** The engine implements its own `MessageQueue` SPI; Inboxes/Outboxes/EventProcessor/admin API are not wired. Whether an adapter should exist is an open decision and the starter must not imply one.
- **Schema init must stay non-destructive.** It runs on every boot. `ShardOwnedSchema.initialize` is the safe one; `recreate` drops everything and is for tests. The engine's only entry point used to be the destructive one.
- **The factory caches per name, and that is correctness.** Two `MessageQueue`s for one name in one process register as two competing consumers and halve each other's fair share.
- **`instanceId` defaults to `Network.hostName()`**, matching the fenced lock manager and `DefaultEssentialsScheduler`, which both use it bare. It was a per-boot UUID; that avoided collisions between two instances on one host but produced an id that means nothing in a log line or the membership table and changes every restart. Where the hostname is not unique per process, set `essentials.shard-owned-queue.instance-id` — two processes sharing an id look like one to the fair-share rebalance and each get half the shards.
- **`ShardOwnedQueueFactory` takes the initializer as a constructor arg it never stores.** That is the bean-ordering edge: without it Spring may build the factory before the schema exists.
- **Queue names are config data, not bean names.** Mapping `application.yml` keys to bean names would make that mapping part of the contract.
- **A shard-count change fails the context.** Re-declaring a registered queue with a different count is refused by the registry — accepting it re-routes every key.
- **The admin endpoints live here, not in `spring-boot-starter-admin-api`.** That module is published and this engine is not, so a controller there would give a published artifact a dependency on an artifact in no repository — and an `EssentialsAdminApiSpec` entry would put a moving surface inside a contract compatibility-checked at `1.0.0`. The controller borrows the admin API's base path, principal resolver and exception handler without extending its contract. Consequence to keep stating: **these endpoints are not in the generated OpenAPI document and not in the admin API's start-up summary.**
- **The dependency on `spring-boot-starter-admin-api` is `provided`, and must stay so.** It drags the event-store starter in with it. An application that wants a queue and nothing else must not acquire an event store by depending on this starter — `an_application_with_no_security_provider_still_gets_a_working_queue` is the guard.
- **A `provided` dependency's `provided` transitives are omitted, not inherited.** `spring-boot-starter-webmvc` is `provided` inside the admin API starter, so it has to be declared here too. The first compile failed on exactly this.
- **`ShardOwnedQueueFactory implements MessageQueues`, and `queueNames()` reads the registry.** Not the `queues` map, which holds only what this process asked for — the difference is the queues this pod does *not* consume, which are the ones an operator is looking for.
- **`findQueue` must not start consuming.** Building a `MessageQueue` does not lease shards; `consume(...)` does. If that ever changes, an admin request against a queue this pod does not serve would silently enlist it as a consumer with no handler. `resolving_a_queue_for_inspection_does_not_start_consuming_it` pins it.
- **Tests share one static container, so `@BeforeEach` calls `recreate`.** Without it, one test's `orders=2` makes another's `orders=4` fail — the protection working against a test that forgot it shares a database.
