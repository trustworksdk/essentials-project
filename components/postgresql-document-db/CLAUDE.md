# postgresql-document-db

JSONB document store on top of PostgreSQL with optimistic locking. Maven: `postgresql-document-db`.

Written in Kotlin. No Spring dependency — pure Jdbi + `HandleAwareUnitOfWorkFactory`.

## Package Structure

| Package | Contents |
|---|---|
| `document_db` | Core interfaces/types: `DocumentDbRepository`, `DocumentDbRepositoryFactory`, `VersionedEntity`, `Version`, `DelegatingDocumentDbRepository`, `OptimisticLockingException`, `IdSerializer` |
| `document_db.annotations` | `@DocumentEntity(tableName)`, `@Id`, `@Indexed` |
| `document_db.postgresql` | Impl: `PostgresqlDocumentDbRepository`, `EntityConfiguration`, `QueryBuilder`, `Condition`, `Property`, `SingleProperty`, `NestedProperty` |

## Key Classes

| Class | Role |
|---|---|
| `VersionedEntity<ID, SELF>` | Interface every entity must implement; mandates `var version: Version` and `var lastUpdated: OffsetDateTime` |
| `Version` | `LongValueType` wrapper; constants `ZERO`, `NOT_SAVED_YET`; `increment()` returns next |
| `DocumentDbRepositoryFactory` | Entry point; registers JDBI `VersionArgumentFactory`/`VersionColumnMapper`; factory methods: `create` (StringValueType id), `createForStringId`, `createForCompositeId(idSerializer)` |
| `PostgresqlDocumentDbRepository` | Sole impl of `DocumentDbRepository`; DDL on init (`CREATE TABLE IF NOT EXISTS`, index creation); uses `INSERT … ON CONFLICT DO NOTHING` for save, `UPDATE … WHERE version = :loadedVersion` for OCC |
| `EntityConfiguration` | Built via reflection from `@DocumentEntity`/`@Id`/`@Indexed`; holds KProperty1 refs for id/version/lastUpdated; `configureEntity()` companion factory recursively validates all property names via `PostgresqlUtil.checkIsValidTableOrColumnName` |
| `QueryBuilder` | Fluent DSL → builds `JdbiQuery` (SQL string + bindings map); supports `where`, `orderBy`, `limit`, `offset` |
| `Condition` | Kotlin infix DSL for predicates (`eq`, `lt`, `lte`, `gt`, `gte`, `like`, `and`, `or`); emits `CAST(data->>'field' AS TYPE)` SQL fragments |
| `SingleProperty` / `NestedProperty` | `Property<T,R>` implementations; nested path built as `data->'a'->'b'->>'c'` |
| `DelegatingDocumentDbRepository` | Open class for entity-specific repos; delegate pattern, all calls forwarded |

## Table Schema

Every entity → single table:

```sql
CREATE TABLE IF NOT EXISTS <tableName> (
    id text PRIMARY KEY,
    data JSONB NOT NULL,
    version BIGINT,
    last_updated TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Annotated `@Indexed` properties → `CREATE INDEX IF NOT EXISTS idx_<table>_<field> ON <table> ((data ->> 'field'))`.

## Test Structure

- All integration tests: `*IT.kt` — require live PostgreSQL via Testcontainers (`PostgreSQLContainer("postgres:latest")`)
- `TestObjectMappers.createJSONSerializer()` — the only way ITs build a serializer. Flavour-neutral: mapper config from `EssentialsObjectMappers` (that config *is* the persisted-JSON contract) plus the active flavour's Kotlin module, which is what binds the data classes' immutable constructors. Both Kotlin modules can be named directly — Jackson 3's lives in group `tools.jackson.module`, so the FQCNs differ, unlike `types-jackson`/`types-jackson3`
- Unit test: `EntityConfigurationTest.kt` — no DB, tests reflection/annotation parsing
- `Entities.kt` — shared test entities (`Order`, `Product`, `Visit`, `ShippingOrder`, `CompositeOrder`)
- `QueryIT.kt` — `QueryBuilder`/`Condition` DSL coverage including nested property paths and pagination
- `CompositeDocumentDbRepositoryIT.kt` — tests non-StringValueType composite id with custom `IdSerializer`

Run ITs: requires Docker (Testcontainers pulls `postgres:latest` automatically).

## Extension Points

| SPI | Purpose |
|---|---|
| `IdSerializer<ID>` | `typealias (ID) -> String`; supply to `createForCompositeId` for non-String/StringValueType ids |
| `DelegatingDocumentDbRepository` | Subclass for entity-specific repos with custom query methods |
| `Property<T,R>` interface | **No longer an extension point** — `sealed`. Use `JsonPathProperty` for a custom JSON path in `Condition`/`QueryBuilder` |

## Gotchas

- **OCC is enforced at DB level**: `save` uses `ON CONFLICT DO NOTHING` → returns 0 rows → `OptimisticLockingException`. `update` uses `WHERE version = :loadedVersion`; same exception on mismatch. Both `version` and `lastUpdated` are mutated in-place on the entity object before SQL execution.
- **Both `version` and `lastUpdated` must be `var`** (mutable). Read-only properties fail at `EntityConfiguration.build()` with `IllegalStateException`.
- **Auto-id assignment only for String/StringValueType**: null id on save attempts `RandomIdGenerator.generate()` only if property is mutable AND type is `String`/`StringValueType`. Other null ids throw immediately.
- **SQL injection surface**: table name (from `@DocumentEntity`) and all entity property names are string-concatenated into SQL. `PostgresqlUtil.checkIsValidTableOrColumnName` is first-line defense — not exhaustive. All property names validated recursively at construction time.
- **`Property` is `sealed`** — only `SingleProperty`, `NestedProperty`, `JsonPathProperty`. Closing it costs nothing: the sink guard restricts a `Property` to a plain JSON path, and `JsonPathProperty` already expresses every one of those. Kotlin sealing is per-compilation-module, so test code can't implement it either — write hostile cases as built-in properties over hostile Kotlin identifiers, the way `JsonPathExpressionGuardTest` does.
- **Sealing is not a substitute for validating at the SQL sink** — `SingleProperty`/`NestedProperty` wrap arbitrary `KProperty1`s, and a Kotlin backtick identifier may legally contain quotes and spaces (`` val `name'||(SELECT version())||'` ``), so even a built-in implementation carries text this module didn't author. A guard in `JsonPathProperty.init`/`Index.init` alone therefore misses it. Every concatenation goes through `checkedJSONValueArrowPath()` / `checkedBindName()` (Query.kt), which pin the expression to `data` + single-quoted segments and run each segment through `PostgresqlUtil`. Add new sinks through those accessors. (Semicolons are the one payload character the JVM rejects in an identifier — don't read that as the class of problem being closed.)
- **The guard limits what a `Property` emits, not what `Condition` composes** — the commented-out `anyLike` builds `EXISTS (SELECT 1 FROM jsonb_array_elements_text(<path>) …)` in `Condition` around a `SingleProperty` path, so it still works; it just has to read the path via `checkedJSONValueArrowPath()`. What is ruled out is a *Property* returning a non-path expression (a function call, or a jsonb array index `data->'tags'->0`, which the pattern doesn't allow). Model such needs as a closed vocabulary in `Condition`/`QueryBuilder` — the way `DbType` already does — not as a free-form string from a `Property`.
- **The bind name is a SQL sink but not an identifier** — `Condition.uniqueBindName` interpolates `:${property.name()}__N`, and JDBI rewrites `:name` to a `?` before PostgreSQL sees it. So `MAX_IDENTIFIER_LENGTH` does not apply: the name is a *JSON* property path, and `NestedProperty` joins a whole chain of them, which 63 would truncate for an ordinary 4-level chain. `checkedBindName()` borrows only PostgresqlUtil's character class and reserved-word list, via `isValidSqlIdentifier(name, 256)`, and truncates to `MAX_BIND_NAME_LENGTH` (256) *before* validating — safe, because what's validated is exactly what's emitted, and uniqueness comes from the counter. Truncation logs a WARN (logger `…document_db.postgresql.BindName`): it's the only silent step in the guard, and two properties sharing a 256-char prefix produce bind names differing only in the counter. Don't reach for `isValidQualifiedSqlIdentifier` (2*63+1): it requires exactly one dot, which no `name()` has, and a dot in a JDBI bind name means bean-property navigation. Keep 63 for anything PostgreSQL *does* parse as an identifier (index names, `Index`) — it silently truncates those, so a longer one is a collision waiting to happen.
- Java-facing entry points that take a runtime `String` (`Condition.eq/lt/lte/gt/gte/like(path,…)`, `QueryBuilder.orderBy(path,…)`, `pathProperty`, `Index.fromPaths`, `addIndexByPaths`, `removeIndex`) are pinned by `JavaPathApiSqlInjectionTest`; the sink guard by `JsonPathExpressionGuardTest`. The Kotlin API cannot reach an identifier position with a runtime string at all — it goes through `KProperty1` references.
- **`version`/`lastUpdated` property names are hardcoded** (`"version"`, `"lastUpdated"` constants in `EntityConfiguration`). Renaming those fields on an entity breaks reflection lookup.
- **`saveAll`/`updateAll`/`deleteAll` wrap in single UoW** but iterate calling individual `save`/`update`/`delete` — not batched SQL.
- **`Condition.and`/`or` consume last two conditions from internal list** via `removeLast()` — order of chained calls matters; mixing `and`/`or` without explicit parentheses in DSL can produce unexpected groupings.
- **`then` extension outside `Condition` scope** (top-level `KProperty1.then`) uses `NoJSONSerializer` placeholder — only valid for `Index` definition, not query execution.
- JDBI `VersionArgumentFactory`/`VersionColumnMapper` registered on `Jdbi` instance in `DocumentDbRepositoryFactory.init` — must use factory, not construct `PostgresqlDocumentDbRepository` directly, or register manually.
- **Main code names no Jackson type** — it takes the `JSONSerializer` SPI, so both Jackson majors work without a flavour-specific branch. Only the tests need the flavour's Kotlin module, so the pom carries both (`com.fasterxml.jackson.module` and `tools.jackson.module`), `optional`. Keep it that way: a Jackson import in main code would tie the module to one major.
