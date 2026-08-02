# Foundation Types

Strongly-typed value objects and identifiers shared across event-sourcing and multi-tenancy components. Maven: `foundation-types`.

## Package Structure

- `dk.trustworks.essentials.components.foundation.types` — general-purpose IDs, tenancy types, `RandomIdGenerator`
- `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types` — event-store-specific types: ordering, naming, revision
- `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream` — `AggregateType` (stream grouping key)
- `dk.cloudcreate.essentials.components.eventsourced.eventstore.postgresql.eventstream` — legacy cloudcreate namespace shim (mirrors trustworks package; do not add new types here)

## Key Classes

| Class | Role |
|---|---|
| `EventId` | Unique event identifier; wraps `CharSequenceType`; `random()` delegates to `RandomIdGenerator` |
| `CorrelationId` | Cross-service correlation; `CharSequenceType` + `Identifier` |
| `MessageId` | Deduplication key for messages; same pattern as `EventId` |
| `SubscriberId` | Identifies an event-store subscriber |
| `TenantId` | Concrete `Tenant` impl; single `CharSequenceType` string value |
| `Tenant` | Marker interface — custom tenants implement this; serialized via `toString()`, deserialized via single-string constructor |
| `AggregateType` | Names an aggregate stream group; becomes a table name in `SeparateTablePerAggregateType` strategy → SQL-injection risk if user-supplied |
| `EventType` | Stores FQCN of event Java class; always prefixed with `FQCN:` on serialization; `isSerializedEventType()` detects prefix |
| `EventName` | String name for schema-less/JSON-only events; no `FQCN:` prefix |
| `EventTypeOrName` | `Either<EventType, EventName>` union — exactly one side non-null; `getValue()` returns string regardless of which side is set |
| `EventOrder` | Per-aggregate sequence; starts at 0; `NO_EVENTS_PREVIOUSLY_PERSISTED = -1` |
| `GlobalEventOrder` | Cross-aggregate sequence within one `AggregateType`; starts at 1 (Postgres BIGINT IDENTITY); `FIRST_GLOBAL_EVENT_ORDER = 1` |
| `EventRevision` | Schema revision integer; starts at 1; mirrors `@Revision` annotation value |
| `Revision` | `@Target(TYPE)` annotation read by `PersistableEventMapper` at persist time |
| `RandomIdGenerator` | Static ID factory; auto-detects `com.fasterxml.uuid.Generators` on classpath → UUIDv1 (sequential); falls back to UUIDv4; overridable via `overrideRandomIdGenerator()` |

## Test Structure

No tests in this module — types are pure value objects with no infrastructure dependencies. Tests live in consuming modules (e.g., `postgresql-event-store`).

## Extension Points

- `Tenant` interface — implement to define custom multi-tenant identifiers; must have single-string constructor for deserialization; `toString()` is the serialized form
- `RandomIdGenerator.overrideRandomIdGenerator(Supplier<String>, boolean)` — swap ID generation strategy (e.g., UUIDv7, Snowflake) globally; must be thread-safe

## Gotchas

- `EventOrder` starts at **0**; `GlobalEventOrder` starts at **1** (Postgres identity column default) — mixing them up breaks resume-point logic
- `EventType` constructor auto-prepends `FQCN:` if not already present — constructing from a serialized string is safe; constructing from a raw FQCN is also safe; double-prefixing is guarded
- `EventTypeOrName` extends `Either` with fields `_1`/`_2` directly — both can be null at construction time; only `with(EventType)` / `with(EventName)` enforce non-null; raw constructor `(null, null)` is not blocked
- `AggregateType` value flows directly into SQL table names in `SeparateTablePerAggregateTypePersistenceStrategy` — never accept from untrusted input; validate with `PostgresqlUtil#checkIsValidTableOrColumnName`
- `Tenant` deserialization expects single-`String`-arg constructor — custom `Tenant` implementations missing this constructor will fail at runtime in consuming modules
- Legacy `dk.cloudcreate` package exists for backward compatibility — contains only `AggregateType`; new types go in `dk.trustworks` namespace only
- `java-uuid-generator` dependency is `optional` — UUIDv1 generation only active if explicitly on classpath; absence silently falls back to UUIDv4 (non-sequential)
