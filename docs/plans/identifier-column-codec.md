# Pluggable identifier column types for the event store

**Status (2026-11-10):** proposal. Nothing is built yet. **Target: 0.61.0** (minor), so every change must be
additive: no source or binary breaks for existing users.

**Origin:** a user issue asks to open up `IdentifierColumnType` so that `aggregate_id`, `event_id`,
`caused_by_event_id` and `correlation_id` can be stored as `BIGINT`, with ids from a Snowflake-style generator.
Today they can only use `TEXT` or `UUID`, so their numeric ids end up in `TEXT` columns. That makes the rows and
the btree indexes larger, and comparisons slower, than a native `bigint` would be.

The issue suggests three changes:

1. Turn `IdentifierColumnType` from an enum into an interface. Each implementation supplies the column's SQL/JDBC
   type and converts values to and from a native Java type. `TEXT` and `UUID` stay available as constants.
2. Provide a way to plug in an id generator that matches the chosen column type.
3. Make `EventId` and `CorrelationId` hold generic `Object`s, with pluggable serializers, instead of strings.

This plan delivers 1 and 2 in a compatible form and recommends **not** doing 3 (see §6).

---

## 1. What the code does today

All references are under
`components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/`.

| Concern | Where | How |
|---|---|---|
| The type | `eventstream/IdentifierColumnType.java` | `public enum IdentifierColumnType { TEXT, UUID }`. It has no behaviour of its own. |
| Configuration | `persistence/AggregateEventStreamConfiguration.java:96-104` | Three `public final IdentifierColumnType` fields: `aggregateIdColumnType`, `eventIdColumnType`, `correlationIdColumnType`. `caused_by_event_id` uses `eventIdColumnType`. |
| Builders and factories | `AggregateEventStreamConfigurationBuilder`, `SeparateTablePerAggregateEventStreamConfiguration(Builder)`, `SeparateTablePerAggregateTypeEventStreamConfigurationFactory(Builder)` | Setters and static factory methods take `IdentifierColumnType`, including the `identifierColumnTypeUsedForAllIdentifiers` convenience overloads. |
| DDL | `SeparateTablePerAggregateTypePersistenceStrategy.java:710-733` | The template argument is the enum itself, so its `toString()` (`TEXT`/`UUID`) *is* the SQL type. |
| Writing (binding) | Same class, lines 1032, 1073-1108, 1166, 1280, 1311, 1343 | About ten inline `type == IdentifierColumnType.UUID ? UUID.fromString(s) : s` checks: aggregate id, event id, caused-by, correlation id, and the event-id lookups and `bindList`. A non-UUID cause in a UUID column is dropped with a warning instead of failing the append (`MIGRATION-0.60.md:1131`). |
| Reading | `persistence/table_per_aggregate_type/PersistedEventRowMapper.java:47-59` | `rs.getString(...)` for every id column. That already works for `bigint`: the driver returns `"123"`. |
| CDC | `cdc/converter/PgOutputToPersistedEventConverter.java:140,170`, `cdc/converter/JacksonWal2JsonToPersistedEventConverter.java:192,226,295,313` | pgoutput delivers text, and wal2json uses `Object.toString()`, so a `bigint` value arriving as a JSON number becomes `"123"`. Neither converter looks at the column type. |
| Spring Boot | `spring-boot-starter-postgresql-event-store/.../EssentialsEventStoreProperties.java:62`, `EventStoreConfiguration.java:409` | The `essentials.event-store.identifier-column-type` property (`text`/`uuid`) binds to the enum by name and is used for all three columns. |
| Id generation | `foundation-types/.../RandomIdGenerator.java`, `EventId.random()`, `PersistableEvent.java:112,274` | `PersistableEvent` falls back to `EventId.random()`, which delegates to the process-wide `RandomIdGenerator`. `RandomIdGenerator.overrideRandomIdGenerator(Supplier<String>, boolean)` already exists, and a `PersistableEventMapper` can set an explicit `eventId`. |

Two conclusions follow:

- The value types never need to change. Every id already travels as a string, and the only place a column type
  matters is the JDBC bind (and the DDL). A Snowflake `long` is just the string `"1234567890"` until it reaches
  the driver.
- The open-closed problem is real but small. It sits in one class (the persistence strategy) plus the
  configuration types. Each new type would otherwise add another branch at every bind site.

## 2. Why not convert the enum into an interface

Changing `IdentifierColumnType` in place, from an `enum` to an `interface` with `TEXT`/`UUID` as constants, looks
source-compatible for code that only reads `IdentifierColumnType.TEXT`. It is not compatible in general:

- **Binary break.** Code compiled against the enum calls `equals`, `hashCode`, `toString`, `name()` and
  `ordinal()` with `invokevirtual` on `IdentifierColumnType`. Once the type is an interface, those call sites throw
  `IncompatibleClassChangeError` at link time. Every consumer jar, including third-party starters, must be
  recompiled.
- **Source break.** `switch` over the enum (classic and pattern), `EnumSet`/`EnumMap`, `values()`, `valueOf(...)`
  and `ordinal()` stop compiling.
- **Spring binding.** Spring Boot binds a property to an enum by itself. An interface needs a custom `Converter`.

CLAUDE.md allows breaking changes to central APIs only in a new major version. The design below gets the same
extensibility without the break.

## 3. Design

### 3.1 A new SPI: `IdentifierColumnCodec`

A new interface in `eventstream/`:

```java
/**
 * Describes how one identifier column of an event-stream table is typed in PostgreSQL and how the framework's
 * string representation of an identifier is converted to and from the value bound through JDBC.
 * Implementations must be stateless and thread-safe.
 */
public interface IdentifierColumnCodec {
    /** The PostgreSQL column type used in the CREATE TABLE statement, e.g. "TEXT", "UUID", "BIGINT". */
    String sqlType();

    /** Convert an identifier's string form to the value bound to the JDBC statement. Throws on a value that does not fit. */
    Object toJdbcValue(String identifier);

    /** Like toJdbcValue, but returns empty instead of throwing. Used where a bad value must not fail an append (caused-by). */
    default Optional<Object> tryToJdbcValue(String identifier) { ... }

    /** Read the column back as the identifier's string form. The default uses ResultSet#getString. */
    default String fromResultSet(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    IdentifierColumnCodec TEXT   = IdentifierColumnType.TEXT;
    IdentifierColumnCodec UUID   = IdentifierColumnType.UUID;
    IdentifierColumnCodec BIGINT = BigIntIdentifierColumnCodec.INSTANCE;
}
```

`sqlType()` is validated once at configuration time with a strict pattern (letters, digits, spaces, parentheses),
because it is concatenated into DDL. This follows the same rule as table names (see "SQL/NoSQL injection" in
CLAUDE.md).

`fromResultSet` keeps the read side open for a codec whose type does not render naturally through `getString`.
None of the three built-ins needs to override it.

### 3.2 The enum implements the SPI

```java
public enum IdentifierColumnType implements IdentifierColumnCodec {
    TEXT { ... sqlType() = "TEXT", toJdbcValue(s) = s },
    UUID { ... sqlType() = "UUID", toJdbcValue(s) = java.util.UUID.fromString(s) };
}
```

Adding a superinterface to an enum is source- and binary-compatible. No constant is added, so exhaustive
`switch`es in user code still compile. Every existing `IdentifierColumnType` value now *is* a codec.

### 3.3 The `BIGINT` codec

`BigIntIdentifierColumnCodec` (public, final, singleton):

- `sqlType()` returns `"BIGINT"`.
- `toJdbcValue(s)` returns `Long.parseLong(s)` and throws `IllegalArgumentException` with the column and value in
  the message.
- `tryToJdbcValue(s)` returns empty when the value is not a valid long.

Shipping it built in answers the issue directly, and the SPI covers anything else (for example `NUMERIC`, or a
domain type).

### 3.4 Configuration: new fields next to the old ones

`AggregateEventStreamConfiguration` gains three codec fields:

```java
public final IdentifierColumnCodec aggregateIdColumnCodec;
public final IdentifierColumnCodec eventIdColumnCodec;
public final IdentifierColumnCodec correlationIdColumnCodec;
```

The existing `public final IdentifierColumnType ...ColumnType` fields stay, marked `@Deprecated(since = "0.61.0")`.
Their value is defined as follows:

- When the codec is an `IdentifierColumnType`, the old field holds that same constant. Existing readers see no
  change.
- When the codec is custom (including `BIGINT`), the old field is `null`. This is the only observable change, and it
  only happens to users who opt into a custom codec. The Javadoc says so.

  The alternative, reporting the nearest enum constant (`TEXT`), is rejected. Code that branches on the old field
  would then silently bind a string to a `bigint` column. A `NullPointerException` at the first read is the
  better failure.

Constructors: the existing public constructors keep their signatures and wrap the enum arguments. A new
constructor (or, preferably, only the builder) accepts codecs. Every builder and factory gets overloads:

- `setAggregateIdColumnType(IdentifierColumnCodec)` next to `setAggregateIdColumnType(IdentifierColumnType)`, and
  the same for the event-id and correlation-id setters. Adding an overload with a supertype parameter is source
  compatible: existing calls with an enum constant still resolve to the more specific method.
- The `identifierColumnTypeUsedForAllIdentifiers` static factories on
  `SeparateTablePerAggregateEventStreamConfiguration` and `SeparateTablePerAggregateTypeEventStreamConfigurationFactory`
  get codec overloads.

`toString()` of the configurations prints the codec.

### 3.5 Persistence strategy

In `SeparateTablePerAggregateTypePersistenceStrategy`:

- **DDL:** the template arguments become `codec.sqlType()` instead of the enum.
- **Binds:** every `== IdentifierColumnType.UUID ? UUID.fromString(..) : ..` becomes
  `configuration.<column>Codec.toJdbcValue(..)`. The caused-by bind uses `tryToJdbcValue` and keeps today's
  warn-and-drop behaviour, now for any codec, not just `UUID`.
- **`bindList("eventIds", ...)`** maps through the codec.

`PersistedEventRowMapper` reads through `codec.fromResultSet(...)`. For the built-ins this is the same
`getString` call as today.

Performance: an interface call through a final field replaces an enum comparison. The JIT inlines it, because
each call site sees a single type. No measurable change is expected on the append or poll paths. Confirm with
`PostgresqlEventStoreBenchmark` before and after (see the memory note on not regressing hot paths).

### 3.6 Id generation per event stream

`RandomIdGenerator.overrideRandomIdGenerator` already works but is process-wide. It also feeds `QueueEntryId`,
`MessageId` and `CorrelationId`, so switching it to Snowflake numbers changes ids far outside the event store. A
per-stream option avoids that:

- New interface `EventIdGenerator` (`EventId generate(AggregateType)`), stateless and thread-safe.
- New optional configuration field `eventIdGenerator`, with builder setter `setEventIdGenerator(...)`. The default
  is `null`, meaning today's behaviour.
- When it is set, the persistence strategy uses it for every `PersistableEvent` whose event id was *not* supplied
  explicitly.

  Prerequisite: `PersistableEvent` fills in `EventId.random()` eagerly at construction
  (`PersistableEvent.java:112,274`), so the strategy cannot tell a defaulted id from an explicit one. Step 4 in §8
  adds a package-private flag that records whether the id was defaulted. The public constructors and accessors
  are unchanged.

- Validation at configuration time: none. The codec validates the generated id at bind time, and a generator that
  produces non-numeric ids for a `BIGINT` column fails on the first append with a clear message.

A Snowflake implementation is not shipped. It needs a worker/node id, which is deployment-specific. The plan
documents a recipe in the LLM docs instead.

Correlation ids are left on the global generator. They often come from outside (HTTP headers, upstream systems)
and are not owned by the event store. See §5.

### 3.7 Spring Boot

- The enum property `essentials.event-store.identifier-column-type` stays as it is.
- New optional per-column properties `aggregate-id-column-type`, `event-id-column-type` and
  `correlation-id-column-type`, each accepting `text`, `uuid` or `bigint`. They fall back to
  `identifier-column-type` when unset. These bind to a small new enum `BuiltInIdentifierColumnType` (`TEXT`, `UUID`,
  `BIGINT`) that maps to codecs. Adding `BIGINT` to the existing enum is avoided so user `switch`es do not break.
- A user bean of type `IdentifierColumnCodec`, qualified per column, overrides the properties for custom types.
- A user bean of type `EventIdGenerator` is picked up when present.
- Spring configuration metadata is generated by the configuration processor (`-proc:full`). Check that the new
  properties appear in `META-INF/spring-configuration-metadata.json`.

## 4. Compatibility summary

| Change | Source | Binary | Behaviour for existing users |
|---|---|---|---|
| New `IdentifierColumnCodec` interface, `BigIntIdentifierColumnCodec` | additive | additive | none |
| Enum implements `IdentifierColumnCodec` | compatible | compatible | none |
| New codec fields on configurations; old fields deprecated | compatible | compatible | none (old fields `null` only with a custom codec) |
| Overloaded setters and factories | compatible | compatible | none |
| `EventIdGenerator` option, defaulted-id flag on `PersistableEvent` | additive | additive | none (option off by default) |
| New Spring properties and beans | additive | n/a | none |
| Persisted data and wire format | unchanged | unchanged | `TEXT`/`UUID` tables are byte-identical; no golden doc changes |

## 5. Caveats and limits

- **Existing tables are not migrated.** The DDL is `CREATE TABLE IF NOT EXISTS`, so switching an existing stream
  from `TEXT` to `BIGINT` changes nothing in the database. The bind then sends a `Long` to a `text` column and fails.
  Document that the codec must match the table, and that moving an existing stream requires a user-owned
  `ALTER TABLE ... TYPE bigint USING ...::bigint` migration. Also check whether the schema harness's `validate`
  mode compares column types, and if it does not, whether it should (see `docs/design/database-schema-harness.md`).
- **Correlation ids.** A `BIGINT` `correlation_id` requires every correlation id to be numeric, including ones
  propagated from outside the service. Recommend keeping it `TEXT` or `UUID` and narrowing only the aggregate-id and
  event-id columns.
- **Caused-by across streams.** `caused_by_event_id` uses the event-id codec of the stream being appended to. A
  cause from a stream with a different id type is dropped with a warning, as `UUID` streams already do.
- **Aggregate ids** still go through `AggregateIdSerializer` (object → string) and then the codec
  (string → JDBC). A `Long`-typed aggregate id therefore takes two cheap conversions. A direct object→JDBC path can
  come later if profiling shows it matters.
- **Other modules.** Durable queues, inbox/outbox, fenced locks and the subscription resume-point tables have their
  own id columns and are out of scope.

## 6. Why `EventId` and `CorrelationId` stay string types

The issue's third idea, generic `Object`-holding ids, is not proposed:

- `EventId` and `CorrelationId` extend `CharSequenceType` in `foundation-types`. They appear in public APIs across
  the event store, aggregates, durable queues, inbox/outbox, message metadata, CDC and the admin API.
- They are serialized into persisted JSON (event metadata, queued messages). Under the frozen wire-format rule
  (golden docs in `EssentialsObjectMappersWireFormatTest` and `WireFormatCompatibilityTest`), their JSON form cannot
  change.
- Changing their superclass or value type breaks source and binary compatibility in every module. That is
  major-version work at best.
- It buys nothing for the issue. The storage and index benefit comes from the column type, which the codec
  provides. Users who want a typed `long` in their own code can wrap it in their own `LongType` and convert at the
  edge.

## 7. Alternatives considered

| Alternative | Verdict |
|---|---|
| Add `BIGINT` to the enum | Smallest change, but it can break exhaustive `switch`es in user code (source), still offers no extension point, and the next request (`NUMERIC`, `ULID` as `uuid`, …) repeats this. Rejected. |
| Convert the enum to an interface (the issue's proposal) | Binary and source break (§2). Allowed only in a major version. Revisit at 1.0 if we want to drop the enum. |
| Make the column type a free-form `String` plus a `Function<String,Object>` | Same capability, weaker typing, and the DDL and bind halves can be configured inconsistently. Rejected in favour of one codec object that owns both. |
| Generic `EventId`/`CorrelationId` | §6. Rejected. |

## 8. Implementation steps

Each step is a separate commit with its own tests. Each step that is visible to users adds an entry to
`docs/releases/NEXT_VERSION_RELEASE.md`.

1. **SPI and built-ins.** Add `IdentifierColumnCodec` and `BigIntIdentifierColumnCodec`, and make the enum implement
   the SPI. Unit tests: each codec's `sqlType`, `toJdbcValue`, `tryToJdbcValue`, and `sqlType` validation.
2. **Configuration plumbing.** Add the codec fields, deprecate the enum fields with the `null` rule from §3.4, and
   add the overloads on the builders and factories. Update `SeparateTablePerAggregateEventStreamConfigurationTest`.
3. **Persistence strategy and row mapper.** Replace the inline checks and generate the DDL from `sqlType()`.
   Unit-test the bind values with `SeparateTablePerAggregateTypePersistenceStrategyTest`. Run
   `PostgresqlEventStoreBenchmark` before and after.
4. **`EventIdGenerator`.** Add the defaulted-id flag on `PersistableEvent`, the configuration field, and its use in
   the strategy.
5. **Integration tests.** Run the existing `SingleTenantPostgresqlEventStoreIT` / `MultiTenantPostgresqlEventStoreIT`
   append/load/poll scenarios with an all-`BIGINT` configuration (aggregate id, event id) and a `TEXT` correlation
   id. Add a causation test (`CausationLookupIT`) with numeric causes and a non-numeric cause (warn and drop), plus
   gap handling. Prefer parameterising existing ITs over new classes so test time stays flat.
6. **CDC.** Add a `BIGINT` case to `WalReplicationWithEssentialsAggregatePgOutputIT` and
   `WalReplicationWithEssentialsAggregateWal2JsonIT`, to confirm that numbers arrive as their decimal string.
7. **Spring Boot.** Add the per-column properties, `BuiltInIdentifierColumnType`, codec and generator beans, and a
   starter test. Check the configuration metadata.
8. **Docs.** Update `LLM/LLM-components.md` (§ identifier column types), `LLM/LLM-spring-boot-starter-modules.md`
   (property table), a Snowflake recipe, and the migration note for existing tables. Bump the `essentials-plugin`
   `plugin.json` version as required. Add a `docs/releases/NEXT_VERSION_RELEASE.md` entry.

## 9. Open questions

1. **Old-field value for custom codecs:** `null` (proposed) or nearest enum constant? See §3.4.
2. **Does the schema harness's `validate` mode check id column types?** If not, should it, so that a codec/table
   mismatch fails at startup instead of on the first append?
3. **Should the Spring per-column properties ship in 0.61.0,** or should 0.61.0 ship the Java API only and add the
   properties when someone asks?
