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
| `Property<T,R>` interface | Implement for custom JSON path expressions in `Condition`/`QueryBuilder` |

## Gotchas

- **OCC is enforced at DB level**: `save` uses `ON CONFLICT DO NOTHING` → returns 0 rows → `OptimisticLockingException`. `update` uses `WHERE version = :loadedVersion`; same exception on mismatch. Both `version` and `lastUpdated` are mutated in-place on the entity object before SQL execution.
- **Both `version` and `lastUpdated` must be `var`** (mutable). Read-only properties fail at `EntityConfiguration.build()` with `IllegalStateException`.
- **Auto-id assignment only for String/StringValueType**: null id on save attempts `RandomIdGenerator.generate()` only if property is mutable AND type is `String`/`StringValueType`. Other null ids throw immediately.
- **SQL injection surface**: table name (from `@DocumentEntity`) and all entity property names are string-concatenated into SQL. `PostgresqlUtil.checkIsValidTableOrColumnName` is first-line defense — not exhaustive. All property names validated recursively at construction time.
- **`version`/`lastUpdated` property names are hardcoded** (`"version"`, `"lastUpdated"` constants in `EntityConfiguration`). Renaming those fields on an entity breaks reflection lookup.
- **`saveAll`/`updateAll`/`deleteAll` wrap in single UoW** but iterate calling individual `save`/`update`/`delete` — not batched SQL.
- **`Condition.and`/`or` consume last two conditions from internal list** via `removeLast()` — order of chained calls matters; mixing `and`/`or` without explicit parentheses in DSL can produce unexpected groupings.
- **`then` extension outside `Condition` scope** (top-level `KProperty1.then`) uses `NoJSONSerializer` placeholder — only valid for `Index` definition, not query execution.
- JDBI `VersionArgumentFactory`/`VersionColumnMapper` registered on `Jdbi` instance in `DocumentDbRepositoryFactory.init` — must use factory, not construct `PostgresqlDocumentDbRepository` directly, or register manually.
- **Main code names no Jackson type** — it takes the `JSONSerializer` SPI, so both Jackson majors work without a flavour-specific branch. Only the tests need the flavour's Kotlin module, so the pom carries both (`com.fasterxml.jackson.module` and `tools.jackson.module`), `optional`. Keep it that way: a Jackson import in main code would tie the module to one major.
