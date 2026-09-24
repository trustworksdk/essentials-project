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

Fourteen production classes issue DDL. Nothing tracks what they have done.

| # | Class | Module | Creates | Invoked from |
|---|---|---|---|---|
| 1 | `ExecutorScheduledJobRepository` | `foundation` | scheduled-jobs table | `initializeTable()` |
| 2 | `ListenNotify` | `foundation` | `notify_<table>_change()` function + trigger, per table | helper, called by 8 and by the starters |
| 3 | `PostgresqlFencedLockStorage` | `postgresql-distributed-fenced-lock` | `fenced_locks` table + 1 index | `initializeLockStorage(…)`, at lock-manager start |
| 4 | `DurableQueuesSql` (via `PostgresqlDurableQueues`) | `postgresql-queue` | queue table + 6 indexes + legacy index drops | constructor, `PostgresqlDurableQueues:511` |
| 5 | `PostgresqlDurableQueuesStatistics` | `postgresql-queue` | statistics table + 2 indexes + function + trigger on the *queue* table | bean construction — **deleted by Q2** of the queue refactor |
| 6 | `CdcSql` / `CdcInboxRepository` | `postgresql-event-store` | CDC inbox table + 1 index | repository construction |
| 7 | `PostgresqlDurableSubscriptionRepository` | `postgresql-event-store` | subscriptions table | constructor |
| 8 | `PostgresqlEventStreamGapHandler` | `postgresql-event-store` | transient-gaps table + index, permanent-gaps table | construction |
| 9 | `SeparateTablePerAggregateTypePersistenceStrategy` | `postgresql-event-store` | **one event-stream table per `AggregateType`**, + tenant index, + notify function and trigger | `addAggregateEventStreamConfiguration(…)`, any time |
| 10 | `PostgresqlAggregateSnapshotStore` | `eventsourced-aggregates` | snapshots table | construction |
| 11 | `PostgresqlAggregateSnapshotJobRepository` | `eventsourced-aggregates` | job table + 2 indexes | construction |
| 12 | `PostgresqlAggregateArchiveRegistry` | `eventsourced-aggregates` | archive table + index | construction |
| 13 | `PostgresqlClosingBooksGenerationRepository` | `eventsourced-aggregates` | table + `ALTER TABLE … ADD COLUMN` + unique index | construction |
| 14 | `MongoDurableQueues` | `springdata-mongo-queue` | collection + indexes | construction |

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
    /** Invoked once per object registered after bootstrap. Must be idempotent. */
    SchemaChangeSet contributeFor(Object registrationKey, SchemaContext context);
}
```

`SeparateTablePerAggregateTypePersistenceStrategy` implements it, keyed by `AggregateType`. At bootstrap the
harness sweeps the already-registered configurations; afterwards, `addAggregateEventStreamConfiguration(…)`
routes through the harness instead of executing directly. This is exactly the two-phase shape
`enableNotifyTriggerInstallation` already uses, so that method folds into the harness rather than sitting
beside it.

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

The harness therefore **adopts on first sight**: for every change whose `objectName` already exists, it writes
a ledger row without executing the statements. This is `create` mode's normal path for `IF NOT EXISTS`
statements anyway; the ledger row is what is new. One consequence worth stating in the migration guide: a
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

**Admin surface: deferred.** A "what schema does this deployment have" endpoint is an obvious follow-on and is
deliberately not in this plan. If it lands, the repo rule applies — the `*Api` SPI, the `EssentialsAdminApiSpec`
mapping table, and a controller in `spring-boot-starter-admin-api`, kept in sync, plus an OpenAPI baseline
consequence.

**Docs:** `LLM/LLM-foundation.md` (the new SPI and the `essentials.schema.*` properties),
`LLM/LLM-postgresql-event-store.md`, `LLM/LLM-postgresql-queue.md`,
`LLM/LLM-spring-boot-starter-modules.md`, the affected modules' `README.md` and `CLAUDE.md`, and one line in
the root `CLAUDE.md` Critical Gotchas because it crosses every module. Then `graphify update .`.

**Interaction with the durable-queues refactor.** Both plans touch `PostgresqlDurableQueues`' DDL block.
Q1 there drops two indexes and adds two `DROP INDEX IF EXISTS`; Q2 deletes the statistics trigger, function and
table. Doing this harness first would let all of those be one-shot changes; doing it second means they ship as
repeatable statements and get converted later. Either order works — see §9.

---

## 9. Sequencing

| Step | Content | Constraint |
|---|---|---|
| 1 | The SPI, `SchemaContext`, `SchemaChange`, ordering constants — `foundation`, no callers yet | Depends on nothing |
| 2 | The ledger table and its `ORDER_LEDGER` contributor; adoption-on-first-sight | After 1 |
| 3 | `create` applier; harness bean; move `acquireBootstrapLock` into it | After 2 |
| 4 | Convert the fixed-shape contributors — rows 1, 3, 4, 6, 7, 8, 10, 11, 12, 13 | After 3; one module at a time, each independently shippable |
| 5 | `DynamicSchemaContributor`; convert row 9; fold in `enableNotifyTriggerInstallation` | After 4. The riskiest step — the event store's table-per-`AggregateType` path |
| 6 | `validate` and `emit` appliers | After 4; independent of 5 |
| 7 | Spring starter wiring, `essentials.schema.*` properties, ordering ITs | After 3, finalised after 6 |
| 8 | Mongo contributors + `MongoSchemaApplier` | After 3; independent of 4–7 |
| 9 | ArchUnit rule, frozen | After 4, 5 and 8 — it can only pass once the sweep is done |
| 10 | Convert existing repeatable one-shots to `repeatable = false`: the legacy index drops, the closing-books `ALTER TABLE`, the queue refactor's index drops | After 4, and after the queue refactor's Q1 if that ships first |
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

## 11. Open questions

- **Does `validate` belong on the startup path at all, or as a separate command?** Failing startup is the
  honest default for a deployment that cannot create what it needs, but an operator may prefer a dry-run
  entry point that reports without taking the application down. Both are cheap; the question is which is the
  default.
- **Should `emit` write one file per module or one combined script?** A DBA reviewing a single ordered file is
  the likelier workflow, but per-module output is easier to diff across releases.
- **What happens when a contributor is removed from the classpath?** Its ledger rows stay, its objects stay,
  and nothing notices. A `--prune` story exists (report orphaned rows, never drop objects) but is not designed
  here.
- **Ordering versus the durable-queues refactor.** Doing this first makes that plan's index drops one-shot from
  the start; doing it second is simpler to review. Neither blocks the other — it only changes how much of
  step 10 there is.
