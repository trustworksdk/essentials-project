# Database schema harness for 0.60

Essentials creates its own database objects at runtime, from inside the components that use them. This plan
moves that work behind a describe-then-apply seam: components declare the schema they need, and a pluggable
applier decides what happens to it — execute it (today's behaviour, and still the default), verify it without
touching the database, emit it as SQL for a DBA, or hand it to Flyway or Liquibase.

Three problems sit under that, and they are worth naming separately because they have different sizes and
different risk:

1. **Extraction and ordering.** DDL runs from constructors and lifecycle methods, in whatever order bean
   construction happens to produce. Mechanical to fix.
2. **Versioning.** There is no record of what has been applied, so every change must be idempotent and must
   re-run on every boot forever. A one-shot change can never be retired.
3. **Delegation.** There is no way to run Essentials without granting the application's database user DDL
   privileges, and no way to let an existing Flyway or Liquibase setup own the framework's objects.

**Target: 0.60**, alongside the other breaking work in that release. Problems 1 and 2 are the substance;
problem 3 lands as the SPI seam plus the no-DDL-privileges modes, with the Flyway and Liquibase adapters as
separate optional modules that can follow at any time.

---

## 1. What the framework does today

Sixteen production classes issue DDL. Nothing tracks what they have done. (Inventory re-checked against
`release/0.60` on 2026-09-24, after the queue refactor and the shard-owned engine landed.)

| # | Class | Module | Creates | Invoked from |
|---|---|---|---|---|
| 1 | `ExecutorScheduledJobRepository` | `foundation` | scheduled-jobs table | `initializeTable()` |
| 2 | `ListenNotify` | `foundation` | `notify_<table>_change()` function + trigger, per table | helper, called by 8 and by the starters |
| 3 | `PostgresqlFencedLockStorage` | `postgresql-distributed-fenced-lock` | `fenced_locks` table + 1 index | `initializeLockStorage(…)`, at lock-manager start |
| 4 | `DurableQueuesSql` (via `PostgresqlDurableQueues`) | `postgresql-queue` | queue table + 6 indexes + legacy index drops | constructor, `PostgresqlDurableQueues:511` |
| 5 | ~~`PostgresqlDurableQueuesStatistics`~~ | `postgresql-queue` | — | **deleted** by Q2 of the queue refactor, which has shipped |
| 6 | `CdcSql` / `CdcInboxRepository` | `postgresql-event-store` | CDC inbox table + 1 index | repository construction |
| 7 | `PostgresqlDurableSubscriptionRepository` | `postgresql-event-store` | subscriptions table | constructor |
| 8 | `PostgresqlEventStreamGapHandler` | `postgresql-event-store` | transient-gaps table + index, permanent-gaps table | construction |
| 9 | `SeparateTablePerAggregateTypePersistenceStrategy` | `postgresql-event-store` | **one event-stream table per `AggregateType`**, + tenant index, + notify function and trigger | `addAggregateEventStreamConfiguration(…)`, any time |
| 10 | `PostgresqlAggregateSnapshotStore` | `eventsourced-aggregates` | snapshots table | construction |
| 11 | `PostgresqlAggregateSnapshotJobRepository` | `eventsourced-aggregates` | job table + 2 indexes | construction |
| 12 | `PostgresqlAggregateArchiveRegistry` | `eventsourced-aggregates` | archive table + index | construction |
| 13 | `PostgresqlClosingBooksGenerationRepository` | `eventsourced-aggregates` | table + `ALTER TABLE … ADD COLUMN` + unique index | construction |
| 14 | `MongoDurableQueues` | `springdata-mongo-queue` | collection + indexes | construction |
| 15 | `ShardOwnedSchema` | `postgresql-queue-shard-owned` | 7 tables, 3 views, 2 sequences, **plus one sequence per registered queue** | static `initialize(…)`, and `registerQueue(…)` at any time |
| 16 | `PostgresqlTTLManager` | `foundation` | TTL function | TTL job registration |
| 17 | `MongoFencedLockStorage` | `springdata-mongo-distributed-fenced-lock` | collection + indexes | `initializeLockStorage(…)` |

### Already solved: concurrent bootstrap

`PostgresqlUtil.acquireBootstrapLock(handle)` takes `pg_advisory_xact_lock(ESSENTIALS_BOOTSTRAP_LOCK_KEY)`, and
every PostgreSQL site above calls it inside the same transaction as its DDL. Two JVMs starting simultaneously
serialise. This plan keeps that mechanism unchanged and moves it into the harness, so a contributor cannot
forget it.

### Already solved in miniature: a pluggable installer

`SeparateTablePerAggregateTypePersistenceStrategy.enableNotifyTriggerInstallation(NotifyTriggerInstaller)` lets
Spring autoconfig inject a callback that is invoked once per already-registered table and again for every
subsequent registration. That is the shape the harness generalises — a two-phase (sweep + ongoing) installer,
documented idempotent, CAS-guarded against double wiring.

### Not solved

- **No version ledger.** Every statement is `CREATE … IF NOT EXISTS`, so the set of statements only grows. The
  legacy index drops at `PostgresqlDurableQueues:526-533` run on every boot of every deployment, including ones
  that never had those indexes, and can never be removed.
- **DDL is interleaved with object construction.** A component that fails to build leaves partial schema, and a
  component that only reads still needs DDL rights to be constructed.
- **Ordering is implicit.** Nothing declares that the scheduled-jobs table must exist before a TTL job
  registers, or that `fenced_locks` precedes anything taking a lock. It works because construction order
  happens to be right.
- **No opt-out.** There is no `essentials.*.create-tables=false`; the application's database user must hold DDL
  privileges permanently, in every environment.
- **Two schema shapes are conflated.** Rows 1–8 and 10–14 are a *fixed set of objects with configurable names*.
  Row 9 is a *variable set* — one table per `AggregateType`, registrable at runtime. Only the first shape can
  ever be expressed as static migration scripts.

---

## 2. The contributor SPI

New package `dk.trustworks.essentials.components.foundation.schema` in `foundation`.

A component stops executing DDL and starts describing it:

```java
public interface EssentialsSchemaContributor {
    String moduleId();                       // "postgresql-queue", stable, used in the ledger key
    int    order();                          // coarse ordering; see the phase constants below
    SchemaChangeSet contribute(SchemaContext context);
}
```

```java
public record SchemaChange(
        String  changeId,        // stable per module, e.g. "queue-table", "queue-indexes-v2"
        String  objectName,      // the resolved, validated identifier this change owns
        List<String> statements, // executed in order, in one transaction with the others in the set
        boolean repeatable       // true = run every boot (today's semantics); false = run once, ledger-gated
) {}
```

`SchemaContext` carries the resolved configuration a contributor needs — table names, the `JSONSerializer`
where relevant, the tenant column — so a contributor takes no constructor dependencies of its own beyond what
it already has. Keep it to one parameter; the repo's construction rules cap public constructors at five
parameters and forbid `Optional` parameters (`EssentialsConstructionRules`), and a growing context object is
how a contributor signature stays compliant.

### Ordering

`order()` uses named constants rather than free integers, so the ordering is reviewable in one place:

| Constant | Value | Contents |
|---|---|---|
| `ORDER_LEDGER` | 0 | the schema-history table itself |
| `ORDER_INFRASTRUCTURE` | 100 | scheduled jobs, fenced locks |
| `ORDER_EVENT_STORE` | 200 | subscriptions, gaps, CDC inbox |
| `ORDER_QUEUES` | 300 | durable queues |
| `ORDER_AGGREGATES` | 400 | snapshots, snapshot jobs, archive, closing books |
| `ORDER_APPLICATION` | 1000 | reserved for consumers |

### Identifier validation moves into the harness

Every contributor's `objectName` is passed through `PostgresqlUtil.checkIsValidTableOrColumnName(…)` (or
`MongoUtil.checkIsValidCollectionName(…)`) by the harness before any applier sees it. Today each site calls it
itself, and a new site can forget. Derived names — the index names in
`PostgresqlClosingBooksGenerationRepository` and `PostgresqlAggregateSnapshotJobRepository` — are validated the
same way, which also keeps the existing `MAX_IDENTIFIER_LENGTH` truncation guard.

### The dynamic contributor

Row 9 cannot produce its change set up front. It gets a second interface:

```java
public interface DynamicSchemaContributor extends EssentialsSchemaContributor {
    /** Route the changes of every object registered from now on to sink. */
    void attach(SchemaChangeSink sink);
}

@FunctionalInterface
public interface SchemaChangeSink {
    void apply(List<SchemaChange> changes);   // the changes of one newly registered object
}
```

`contribute(…)` describes every object registered so far, so the sweep covers them like any other
contribution. `EssentialsSchemaHarness.apply()` first attaches each dynamic contributor to
`applier.sinkFor(contributor)` (a default method on `SchemaApplier` that wraps the changes as one
`SchemaChangeSet` of that contributor) and only then collects. Attaching first means an object registered
while the sweep runs is not missed; it may reach the applier twice, which is why a dynamic contributor's
changes must be safe to repeat. The design originally had the harness pull `contributeFor(key, …)`; the
harness cannot know when a key is registered, so the contributor pushes instead.

`SeparateTablePerAggregateTypePersistenceStrategy` implements it, keyed by `AggregateType`. In
`SchemaOwnership.COMPONENT` mode (the default) the strategy attaches its own create applier at construction,
so `addAggregateEventStreamConfiguration(…)` behaves as before. In `HARNESS` mode nothing is attached until
the harness runs: types registered before that are covered by the sweep, types registered after go through
the harness' applier. `resetEventStorageFor(…)` always re-creates the table, with the harness' applier if
one is attached and its own create applier otherwise, because a reset is an explicit request to start over.

`enableNotifyTriggerInstallation(NotifyTriggerInstaller)` executed the trigger DDL itself, beside the
table's schema, so a `validate` or `emit` harness would never have seen the trigger. It is deprecated in
favour of `enableNotifyTriggers(Consumer<String>)`: the `pg_notify` trigger becomes a
`change-notification-trigger` change of each table's contribution, and the callback only registers the table
with the change listener (`LISTEN` does not need the trigger to exist). The two methods exclude each other.
The Spring starter's `EventStoreNotifyPollingBootstrap` uses the new one.

`ShardOwnedSchema` (row 15) is the second dynamic contributor, keyed by queue id. Decided 2026-09-24: the
engine stays dependent on `shared` only, so it does not implement the SPI itself. It exposes its DDL as plain
statements - `schemaStatements()` (exactly what `initialize` executes) and
`queueSequenceStatements(queueId, shardCount)` - and every registration method has an overload taking a
`QueueDdlExecutor`, whose default runs the statements under the engine's copy of the bootstrap lock, as
before. `ShardOwnedSchemaContributor` in `postgresql-queue-shard-owned-adapter` (which already depends on
`foundation`) carries them into the harness: the fixed schema is one `engine-schema` change, and each queue's
sequences are a `queue-sequences` change on object `shard_queue_q<id>`, applied as the queue registers.
The registry row and lease rows stay data written by the engine. Two consequences:

- A queue's sequence names embed the id the registry allocates at registration, so they can never be part of
  a script written before the queue exists (`emit`). They are created or checked as queues register.
- Registration writes to the registry table, which is part of the fixed schema, so queues register after the
  harness ran. `growShardCount` re-records the same change with the larger statement set.

The engine keeps its own copy of the bootstrap-lock key, for use without a harness.

Decided 2026-09-25: **the shard-owned engine needs the right to create sequences at runtime.** In a mode that
does not create the schema (`validate`, `emit`, `external`) `ShardOwnedSchemaContributor` contributes only the fixed
schema; each queue's sequences are created by the engine itself as the queue registers, under the bootstrap lock,
and are not recorded. It logs one warning saying so when the harness attaches it. The engine's README documents
the right under "Database rights". A later option, not in 0.60: a script-created function the engine calls to
create a queue's sequences, so only `EXECUTE` on it is needed at runtime.

---

## 3. The applier

```java
public interface SchemaApplier {
    void apply(List<SchemaChangeSet> changeSets);
}
```

One property selects the built-in:

```properties
essentials.schema.mode = create   # create (default) | validate | emit | external
```

| Mode | Behaviour | For |
|---|---|---|
| `create` | Executes the statements under the bootstrap lock, records them in the ledger. Today's behaviour. | default, dev, most deployments |
| `validate` | Executes nothing. Reads `information_schema` / `pg_indexes` and fails startup with a diff of what is missing. | deployments where the app user has no DDL rights |
| `emit` | Executes nothing. Writes the full resolved DDL to a configured file (and logs a summary), then fails startup unless `validate` also passes. | generating the script to hand to a DBA |
| `external` | Executes nothing, verifies nothing. The schema is somebody else's problem. | Flyway/Liquibase adapters, and consumers who manage it entirely themselves |

`emit` is the mode that makes `validate` usable: run `emit` once against any environment, give the output to
whoever holds DDL rights, then run `validate` from then on. Neither mode needs Flyway.

**As built (step 6).** `PostgresqlValidateSchemaApplier` compares against the **ledger**, not the catalog. A
contribution is plain SQL, so there is no structured description of the objects to diff `information_schema`
against without parsing SQL. Instead every described change must have a ledger row with the checksum of its
current statements: a missing row is "not applied", a different checksum is "applied with other statements"
(for a one-shot change, "edited after it was applied"). Every problem is listed in one
`SchemaValidationException`, and nothing is executed - not even the ledger table. The trade-off: the ledger is
trusted, so an object dropped by hand after it was recorded goes unnoticed.

`PostgresqlSchemaScript` renders what the create applier does as one script: one transaction under the
bootstrap lock, the ledger table, then a header per module and each change followed by its ledger upsert
(`applied_by = 'essentials-schema-script'`, first `applied_ts` kept). One-shot changes run inside a `DO` guard
that checks the ledger and `EXECUTE`s the statements under a dedicated dollar-quote tag, so a re-run skips them.
`PostgresqlEmitSchemaApplier` writes that script, replacing the file on the first apply and appending a
self-contained block for each later dynamic registration, and does nothing else. Decided 2026-09-25: `emit` is a
**pre-step, not a startup mode that fails**. It needs no database connection - the script is rendered from what the
contributors describe - so it runs in a build or deployment pipeline; the Spring wiring (step 7) exits the
application cleanly once it is written (under Spring the context is still built, so the database has to be
reachable, though it may be empty). `validate` is the only mode that refuses to start. This replaces the
earlier "emit, then fail unless validate passes", which made generating the script a start-and-fail step, at odds
with how easy an Essentials setup otherwise is. The default mode stays `create`, so nothing changes for anyone who
does not opt in.

`SchemaApplier.createsSchema()` (and the same on `SchemaChangeSink`) says whether an applier executes the
statements: `true` only for the create applier. A dynamic contributor that cannot describe everything up front uses
it to decide whether it has to create later objects itself. `EssentialsSchemaScriptRoundTripIT` (postgresql-event-store) runs every
PostgreSQL contributor through emit -> run script -> validate, and checks the script records exactly the ledger
the create mode does.

---

## 4. The version ledger

One table, created by the `ORDER_LEDGER` contributor before anything else, under the bootstrap lock, with no
ledger entry of its own.

```sql
CREATE TABLE IF NOT EXISTS essentials_schema_history (
    module_id     TEXT        NOT NULL,
    change_id     TEXT        NOT NULL,
    object_name   TEXT        NOT NULL,
    checksum      TEXT        NOT NULL,
    applied_ts    TIMESTAMPTZ NOT NULL,
    applied_by    TEXT        NOT NULL,   -- host/instance, for forensics only
    PRIMARY KEY (module_id, change_id, object_name)
);
```

The key includes `object_name` because one change routinely applies to more than one object in the same
database. The event store is the unavoidable case: `SeparateTablePerAggregateTypePersistenceStrategy` applies
the same "create event-stream table" change once per registered `AggregateType`. Keyed on
`(module_id, change_id)` alone, the first `AggregateType` records the change and every later one is skipped.
The same holds, less dramatically, for two services sharing a database with different configured table names.

`checksum` is over the statement list. A change whose checksum differs from the recorded one is a change that
was edited after shipping; the harness fails startup with both values rather than silently re-running or
silently skipping.

**What the ledger unlocks**, concretely:

- **One-shot changes become possible.** `repeatable = false` runs once per `(module, change, object)` and never
  again. The legacy index drops at `PostgresqlDurableQueues:526-533`, and the two more that the queue
  refactor's Q1 adds, become one-shot and can be deleted from the codebase in a later release instead of
  accumulating forever.
- **`ALTER TABLE` stops being a special case.** `PostgresqlClosingBooksGenerationRepository:154` is a
  hand-rolled migration today, kept safe only by `ADD COLUMN IF NOT EXISTS`. Under the ledger it is an ordinary
  one-shot change.
- **A non-idempotent change becomes expressible at all** — a backfill, a column type change, an index rebuild.
  None of which the framework can currently ship.

---

## 5. Flyway and Liquibase

**Not as versioned `.sql` files.** Two properties of the framework's schema rule that out: table names are
resolved from runtime configuration, and the event store's table *set* is a function of which `AggregateType`s
an application registers. A checksummed, statically ordered script collection cannot express either.

**Flyway** — a new optional module `essentials-schema-flyway` providing:

- a `JavaMigration` that runs the harness in `create` mode against Flyway's connection. The checksum Flyway
  tracks is the migration class, not the generated SQL, so per-deployment name variance stops mattering.
- a Flyway `Callback` on `afterMigrate` for applications that would rather not version the framework's schema
  at all.
- the framework side set to `essentials.schema.mode=external`, plus `@DependsOn("flyway")` on the Essentials
  bootstrap bean so ordering is declared rather than inferred.

**Liquibase** — `essentials-schema-liquibase`, a `CustomTaskChange` doing the same thing.

Both modules declare their Flyway/Liquibase dependency `provided` — the repo rule is that third-party
integrations are never transitive and consumers declare their own.

**Boundary, stated once:** Essentials never versions, inspects, or migrates the application's own schema. The
adapters exist so a consumer's existing tool owns the framework's objects too, not so the framework owns the
consumer's.

---

## 6. Mongo

`MongoDurableQueues` creates a collection and its indexes at construction; the Mongo fenced-lock module does
the same. They implement the same `EssentialsSchemaContributor`, with `SchemaChange.statements` carrying index
specifications rather than SQL, behind a `MongoSchemaApplier`.

`create` and `external` are meaningful there. `validate` is meaningful. `emit` is not — there is no script to
hand anybody — and the Mongo applier rejects it at startup with that explanation rather than accepting it and
doing nothing.

Scope for 0.60: the seam and `create`/`validate`. No Flyway equivalent.

---

## 7. Existing deployments

First boot on 0.60 against a database populated by 0.5x finds an empty ledger and every object already
present. It must not fail, and must not re-run one-shot changes that already happened.

The harness therefore **adopts on first sight** — without a special path. Every change's statements must be safe
to run against a database where 0.5x already created the object in its current shape (`CREATE … IF NOT EXISTS`,
`DROP … IF EXISTS`, `ADD COLUMN IF NOT EXISTS`), and on first boot the harness simply runs them: they are no-ops,
and the ledger rows they leave are what is new.

This deliberately differs from recording a row *without* executing when `objectName` already exists. That rule
would silently skip any later one-shot change to an existing object — an `ALTER TABLE … ADD COLUMN` on a table
0.5x created would be marked applied and never run. The price of the safer rule is a contributor obligation,
checked in review and by the `create`-mode ITs: a statement that is not safe to repeat against an existing object
does not belong in a change. One consequence worth stating in the migration guide: a
deployment upgrading from 0.5x is adopted as-is, so an object that is *present but wrong* — hand-edited, or
half-created by a failed 0.5x boot — is recorded as correct. `validate` mode is the tool for checking that; it
is not run implicitly during adoption because it would fail upgrades for pre-existing drift the upgrade did
not cause.

---

## 8. Cross-cutting consequences

**Every `postgresql-*` module is touched**, plus `eventsourced-aggregates`, `foundation`, both Mongo modules
and all three Spring Boot starters. Mostly moving statements into a contributor and deleting an
`initialize…()` call — but it is wide, and it is the reason this is a 0.60 item rather than a patch.

**An ArchUnit rule, frozen like the construction rules.** No class outside a `SchemaContributor` may execute a
statement starting `CREATE`, `ALTER`, `DROP` or `TRUNCATE`. Wrapped in `FreezingArchRule.freeze(…)` in the
usual way if the sweep does not finish in one pass, so the violation store's size is the progress metric.

**The starters gain one bean and one property block** — the harness, and `essentials.schema.*`. The harness
bean must be constructed before any component whose DDL it now owns, which for the Spring path means the
existing component beans take a dependency on it. Silent-startup-failure risk here is real: a component
constructed before the harness would find no table and fail at first query rather than at startup. One
integration test per starter that asserts the ordering.

**As built (step 7).** The ordering is solved the other way round: no component depends on the harness.
`EssentialsSchemaHarnessRunner` (`spring-boot-starter-postgresql`) is a `SmartInitializingSingleton`, so it
applies every `EssentialsSchemaContributor` bean once all singletons exist and before any lifecycle starts.
Components built with `SchemaOwnership.HARNESS` touch no schema while they are constructed, so the gap between
construction and the harness is harmless, and nothing consumes, polls or subscribes before `validate` has
passed. The details:

- `essentials.schema.*` lives on `EssentialsComponentsProperties.getSchema()`: `mode` (`create` | `validate` |
  `emit` | `external`, default `create`), `history-table-name`, `emit.script-file` (default
  `essentials-schema.sql`) and `emit.exit` (default `true`).
- `create`: every component still creates its own schema as it is constructed (`SchemaMode.schemaOwnership()`
  is `COMPONENT`), exactly as in earlier releases; the runner then re-runs those repeatable changes, which finds
  nothing to do and covers any application contributor. All other modes build the components with `HARNESS`.
- The fenced lock manager is started by the lifecycle manager outside `create`, not at construction: its
  lock-confirmation thread reads the lock table at once.
- `emit`: the lifecycle manager does not start the Essentials lifecycles, and the runner stops the application
  with exit code 0 on `ApplicationStartedEvent` - after the context refresh, where `System.exit` cannot deadlock
  with Spring's shutdown hook, and before any `ApplicationRunner`. `emit.exit=false` keeps it running, for tests.
  The context is still built, so the components are constructed and some open a connection while they are;
  the database may be empty, but it has to be reachable.
- One component can be reachable as several beans, and two components can describe the same table (the
  snapshot store bean and the snapshot repositories the factory builds). The runner de-duplicates contributors
  by identity, and the harness drops a change described identically twice and rejects only one described
  differently.
- Nested contributors pass the ownership through and contribute on behalf of what they build:
  `PostgresqlFencedLockManager` (its lock storage), `DefaultEssentialsScheduler` (its job repository),
  `PostgresqlAggregateSnapshotRepository` (its snapshot store).
- `spring-boot-starter-postgresql-queue-shard-owned` does not depend on the base starter; it reads
  `essentials.schema.mode` itself. `create` is unchanged. `validate` checks for the registry table before
  registering the configured queues, so a missing schema is a `SchemaValidationException` rather than an SQL
  error, and the queues' sequences are created directly as decided above. `emit` registers nothing. A
  `ShardOwnedSchemaContributor` bean carries the fixed schema into the base starter's harness, and is absent when
  `essentials.shard-owned-queue.initialize-schema=false`.
- Not covered: the closing-books generation repository that `ClosingBooksSetupBuilder` builds is application
  code, not a starter bean, so the runner cannot see it; it keeps creating its own table. An application in a
  non-create mode supplies its own `PostgresqlClosingBooksGenerationRepository` built with `HARNESS` and exposes
  it as a bean.
- ITs: `EssentialsSchemaModeIT` (base starter), `EventStoreSchemaModeIT` (event store starter, including an
  AggregateType registered at runtime being refused in `validate`) and `ShardOwnedSchemaModeIT`.

**Admin surface: deferred.** A "what schema does this deployment have" endpoint is an obvious follow-on and is
deliberately not in this plan. If it lands, the repo rule applies — the `*Api` SPI, the `EssentialsAdminApiSpec`
mapping table, and a controller in `spring-boot-starter-admin-api`, kept in sync, plus an OpenAPI baseline
consequence.

**Docs:** `LLM/LLM-foundation.md` (the new SPI and the `essentials.schema.*` properties),
`LLM/LLM-postgresql-event-store.md`, `LLM/LLM-postgresql-queue.md`,
`LLM/LLM-spring-boot-starter-modules.md`, the affected modules' `README.md` and `CLAUDE.md`, and one line in
the root `CLAUDE.md` Critical Gotchas because it crosses every module. Then `graphify update .`.

**Interaction with the durable-queues refactor.** Settled: the refactor shipped first, so its `DROP INDEX IF EXISTS`
statements are repeatable today and become one-shot changes in step 10.

---

## 9. Sequencing

| Step | Content | Constraint |
|---|---|---|
| 1 | The SPI, `SchemaContext`, `SchemaChange`, ordering constants — `foundation`, no callers yet | Depends on nothing |
| 2 | The ledger table and its `ORDER_LEDGER` contributor; adoption-on-first-sight | After 1 |
| 3 | `create` applier; harness bean; move `acquireBootstrapLock` into it | After 2 |
| 4 | Convert the fixed-shape contributors — rows 1, 3, 4, 6, 7, 8, 10, 11, 12, 13, 16 | After 3; one module at a time, each independently shippable |
| 5 | `DynamicSchemaContributor`; convert rows 9 and 15; fold in `enableNotifyTriggerInstallation` | After 4. The riskiest step — the event store's table-per-`AggregateType` path, and the shard-owned engine's per-queue sequence |
| 6 | `validate` and `emit` appliers | After 4; independent of 5 |
| 7 | Spring starter wiring, `essentials.schema.*` properties, ordering ITs | After 3, finalised after 6 |
| 8 | Mongo contributors (rows 14, 17) + `MongoSchemaApplier` | After 3; independent of 4–7 |
| 9 | ArchUnit rule, frozen | After 4, 5 and 8 — it can only pass once the sweep is done |
| 10 | Convert existing repeatable one-shots to `repeatable = false`: the legacy index drops, the closing-books `ALTER TABLE`, the queue refactor's index drops | After 4 |
| 11 | `essentials-schema-flyway` | After 6. Optional module, can slip past 0.60 |
| 12 | `essentials-schema-liquibase` | After 11. Optional module, can slip past 0.60 |
| 13 | Migration guide, docs, `graphify update .` | Release |

Steps 11 and 12 are the ones to drop if 0.60 gets tight; the seam they plug into is step 6, which is where the
practical value is.

---

## 10. Decisions taken

| # | Question | Decision |
|---|---|---|
| 1 | Does Essentials adopt Flyway, or expose a seam? | **A seam.** Runtime-resolved table names and per-`AggregateType` tables cannot be static scripts; Flyway and Liquibase become adapters over the seam |
| 2 | Ledger key | **`(module_id, change_id, object_name)`** — one change applies once per `AggregateType` table, so the object has to be part of the identity |
| 3 | Default mode | **`create`** — identical observable behaviour to 0.5x, so an upgrade changes nothing for anyone who ignores this feature |
| 4 | First boot against an existing 0.5x database | **Adopt on sight**, no validation. Failing an upgrade on pre-existing drift the upgrade did not cause is the wrong default |
| 5 | Checksum mismatch | **Fail startup**, printing both. Not a warning |
| 6 | Where does the SPI live? | **`foundation`**, `…foundation.schema`. The PostgreSQL applier next to `PostgresqlUtil`; the Mongo applier in the Mongo modules |
| 7 | Flyway/Liquibase dependency scope | **`provided`**, in their own optional modules, per the repo-wide rule |
| 8 | Admin endpoint for schema status | **Out of scope**, noted in §8 so its absence is not read as an oversight |
| 9 | Target release | **0.60** for steps 1–10 and 13; 11–12 additive whenever ready |
| 10 | Adoption on first sight | **No special path** — statements must be safe against an existing object, and first boot runs them (§7). Recording without executing would skip later one-shots on existing objects |
| 11 | `validate`: startup path or separate command? | **Startup, failing it.** A deployment that cannot find its schema should refuse to run, not fail at first query. A dry-run entry point can follow |
| 12 | `emit`: one file or per module? | **One combined, ordered script**, with a header per module — what a DBA runs, still diffable |
| 13 | A contributor removed from the classpath | **Nothing happens in 0.60**: its ledger rows and objects stay, documented. A reporting `--prune` is later work |
| 14 | The shard-owned engine | **In scope**, as the second dynamic contributor (step 5) |

## 11. Open questions

None left: the four listed here when the plan was written are decisions 11–14 above, taken on 2026-09-24.
