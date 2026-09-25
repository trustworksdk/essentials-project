# spring-boot-starter-postgresql-queue-shard-owned

Spring Boot auto-configuration for `postgresql-queue-shard-owned`. Published, like the engine it configures — no `maven.deploy.skip`; only the `examples/` modules carry one.

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

- **The `DurableQueues` bean is opt-in and off by default.** The engine's own contract is the `MessageQueue` SPI. `essentials.shard-owned-queue.durable-queues-enabled=true` adds a `ShardOwnedDurableQueues` bean, which moves Inbox, Outbox, `DurableLocalCommandBus` and every `EventProcessor`'s projections onto this engine; it wins because `spring-boot-starter-postgresql` declares its `PostgresqlDurableQueues` `@ConditionalOnMissingBean` and this starter orders itself `beforeName` that class. It stays off unless asked — a starter on the classpath must not relocate an application's delivery path. (This used to say there was no such bean, deliberately; the open decision it referred to was taken.)
- **The `DurableQueues` bean must keep applying the `DurableQueuesInterceptor` beans.** It displaces `EssentialsComponentsConfiguration`'s `durableQueues` bean, which is where `addInterceptors(durableQueuesInterceptors)` is called — so omitting the injected list does not fail, it silently drops every interceptor the application has, `RecordExecutionTimeDurableQueueInterceptor` included. The symptom is an application that turns on this engine and loses its queue timers, with nothing in the log. `durable_queues_interceptor_beans_reach_the_adapter` is the guard.
- **Schema init must stay non-destructive.** It runs on every boot. `ShardOwnedSchema.initialize` is the safe one; `recreate` drops everything and is for tests. The engine's only entry point used to be the destructive one.
- **The factory caches per name, and that is correctness.** Two `MessageQueue`s for one name in one process register as two competing consumers and halve each other's fair share.
- **`instanceId` defaults to `Network.hostName()`**, matching the fenced lock manager and `DefaultEssentialsScheduler`, which both use it bare. It was a per-boot UUID; that avoided collisions between two instances on one host but produced an id that means nothing in a log line or the membership table and changes every restart. Where the hostname is not unique per process, set `essentials.shard-owned-queue.instance-id` — two processes sharing an id look like one to the fair-share rebalance and each get half the shards.
- **`ShardOwnedQueueFactory` takes the initializer as a constructor arg it never stores.** That is the bean-ordering edge: without it Spring may build the factory before the schema exists.
- **Queue names are config data, not bean names.** Mapping `application.yml` keys to bean names would make that mapping part of the contract.
- **A shard-count change fails the context.** Re-declaring a registered queue with a different count is refused by the registry — accepting it re-routes every key.
- **The admin endpoints moved to `spring-boot-starter-admin-api`** — `ShardOwnedQueuesController` sits with every other admin controller, and `EssentialsAdminApiSpec` carries the `shard-owned-queues` paths. They are in the generated OpenAPI document. This used to say the opposite, and the reason it gave was real at the time: a published artifact cannot depend on one in no repository. Publishing the engine removed it. What stays here is `ShardOwnedQueuesAdminApiAutoConfiguration`, which wires the `*Api` bean when the admin API starter and an `EssentialsSecurityProvider` are both present.
- **The dependency on `spring-boot-starter-admin-api` is `provided`, and must stay so.** It drags the event-store starter in with it. An application that wants a queue and nothing else must not acquire an event store by depending on this starter — `an_application_with_no_security_provider_still_gets_a_working_queue` is the guard.
- **A `provided` dependency's `provided` transitives are omitted, not inherited.** `spring-boot-starter-webmvc` is `provided` inside the admin API starter, so it has to be declared here too. The first compile failed on exactly this.
- **`ShardOwnedQueueFactory implements MessageQueues`, and `queueNames()` reads the registry.** Not the `queues` map, which holds only what this process asked for — the difference is the queues this pod does *not* consume, which are the ones an operator is looking for.
- **`findQueue` must not start consuming.** Building a `MessageQueue` does not lease shards; `consume(...)` does. If that ever changes, an admin request against a queue this pod does not serve would silently enlist it as a consumer with no handler. `resolving_a_queue_for_inspection_does_not_start_consuming_it` pins it.
- **Tests share one static container, so `@BeforeEach` calls `recreate`.** Without it, one test's `orders=2` makes another's `orders=4` fail — the protection working against a test that forgot it shares a database.

## Schema mode

The starter does not depend on `spring-boot-starter-postgresql`, so `ShardOwnedQueueInitializer` reads `essentials.schema.mode` itself (Binder, default `create`). `create`: unchanged - `ShardOwnedSchema.initialize` plus registration. `validate`: checks the registry table exists (a `SchemaValidationException`, not an SQL error), then registers; queue sequences are created directly by the engine, which needs `CREATE` rights at runtime. `emit`: registers nothing. `external`: registers. The `ShardOwnedSchemaContributor` bean carries the fixed schema into the base starter's harness and is absent with `initialize-schema=false`. `ShardOwnedSchemaModeIT` covers each mode.
