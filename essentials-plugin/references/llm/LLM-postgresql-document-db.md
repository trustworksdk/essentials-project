# postgresql-document-db - LLM Reference

> WORK-IN-PROGRESS - Kotlin-first document database using PostgreSQL JSONB with type-safe queries, optimistic locking, and automatic schema management. A dedicated Java interop surface is available - see [Java Interop](#java-interop). For detailed explanations, see [README](https://github.com/trustworksdk/essentials-project/blob/0.60.0/components/postgresql-document-db/README.md).

Base package: `dk.trustworks.essentials.components.document_db`

## TOC
- [Quick Facts](#quick-facts)
- [Core Concepts](#core-concepts)
- [Entity Definition](#entity-definition)
- [Repository Setup](#repository-setup)
- [CRUD Operations](#crud-operations)
- [Query API](#query-api)
- [Indexing](#indexing)
- [Java Interop](#java-interop)
- [Custom Repositories](#custom-repositories)
- [Database Schema](#database-schema)
- [Optimistic Locking](#optimistic-locking)
- [Common Patterns](#common-patterns)
- [Gotchas](#gotchas)
- ⚠️ [Security](#security)
- [Test References](#test-references)

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.document_db`
- **Language**: Kotlin-first (data classes, value classes, property references). **Java is supported** via `JavaVersionedEntity`, `Class<T>` factory overloads, `long` version overloads, and string-path query/index helpers - see [Java Interop](#java-interop)
- **Storage**: PostgreSQL JSONB column with ACID guarantees
- **Key deps**: PostgreSQL, JDBI, Jackson, Kotlin stdlib/reflect (all `provided` scope)
- **Status**: WORK-IN-PROGRESS (experimental)

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-document-db</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `HandleAwareUnitOfWorkFactory`, `UnitOfWork` from [foundation](./LLM-foundation.md)
- `JSONSerializer` / `EssentialsObjectMappers` from [foundation](./LLM-foundation.md) (Jackson 3)
- `CharSequenceType` (for ID types) from [types](./LLM-types.md)

## Core Concepts

| Concept | Class/Interface | Role |
|---------|----------------|------|
| Repository | `DocumentDbRepository<E, ID>` | CRUD + type-safe queries for JSONB documents |
| Factory | `DocumentDbRepositoryFactory` | Creates repositories, auto-creates tables/indexes |
| Entity contract | `VersionedEntity<ID, SELF>` | Required interface: adds `version` + `lastUpdated` |
| Optimistic locking | `Version` | Prevents concurrent update conflicts (auto-incremented) |
| Type-safe queries | `QueryBuilder<ID, E>` | Kotlin property refs → PostgreSQL JSON paths |
| Query conditions | `Condition<E>` | Type-safe WHERE clauses |
| Indexes | `@Indexed`, `Index<E>` | Performance via GIN indexes on JSONB properties |
| Custom repositories | `DelegatingDocumentDbRepository<E, ID>` | Base class for domain-specific repositories |
| Exception | `OptimisticLockingException` | Thrown on version conflict during update |

## Entity Definition

### Requirements
1. Implement `VersionedEntity<ID, SELF_TYPE>`
2. `@DocumentEntity("table_name")` annotation
3. `@Id` annotation on ID property
4. ID must be `String`, `StringValueType`, or use `createForCompositeId(entityClass, idSerializer)`

### Basic Entity Pattern

```kotlin
import dk.trustworks.essentials.components.document_db.VersionedEntity
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity
import dk.trustworks.essentials.components.document_db.annotations.Id
import dk.trustworks.essentials.components.document_db.annotations.Indexed

@DocumentEntity("orders")  // Table name
data class Order(
    @Id val orderId: OrderId,           // Semantic StringValueType ID
    @Indexed var personName: String,     // Creates idx_orders_personname
    var amount: Amount,                  // Semantic BigDecimal type
    var address: Address,                // Nested object
    var lines: List<OrderLine>,          // Collections supported
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<OrderId, Order>

// Nested types (no annotations)
data class Address(val street: String, val zipCode: Int, val city: String)
```

### Semantic ID Type

```kotlin
import dk.trustworks.essentials.kotlin.types.StringValueType
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator

@JvmInline
value class OrderId(override val value: String) : StringValueType<OrderId> {
    companion object {
        fun random() = OrderId(RandomIdGenerator.generate())
    }
}
```

### JDBI Type Registration (REQUIRED)

Every Semantic type, such as `StringValueType` properties, requires JDBI registration:

```kotlin
import dk.trustworks.essentials.kotlin.types.jdbi.StringValueTypeArgumentFactory
import dk.trustworks.essentials.kotlin.types.jdbi.StringValueTypeColumnMapper

// Create factories (extend base classes from types-jdbi)
class OrderIdArgumentFactory : StringValueTypeArgumentFactory<OrderId>()
class OrderIdColumnMapper : StringValueTypeColumnMapper<OrderId>()

// Register with JDBI
jdbi.apply {
    registerArgument(OrderIdArgumentFactory())
    registerColumnMapper(OrderIdColumnMapper())
    // Repeat for all custom SingleValueType properties
}
```

> `Version` is auto-registered by `DocumentDbRepositoryFactory`.
> See `dk.trustworks.essentials.components.boot.autoconfigure.postgresql.JdbiConfigurationCallback` for Spring Boot Jdbi configuration.

### Version Property

```kotlin
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.kotlin.types.LongValueType

@JvmInline
value class Version(override val value: Long) : LongValueType<Version> {
    companion object {
        val NOT_SAVED_YET = Version(-1)  // Before first save
        val ZERO = Version(0)            // After save()
        // Version(n) after n updates
    }
}
```

## Repository Setup

### Factory Creation

`DocumentDbRepositoryFactory` creates repositories, tables, indexes, handles serialization. Create once, reuse.

```kotlin
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers
import dk.trustworks.essentials.components.foundation.json.Jackson3JSONSerializer
import tools.jackson.module.kotlin.KotlinModule

val factory = DocumentDbRepositoryFactory(
    jdbi,
    JdbiUnitOfWorkFactory(jdbi),  // Or Spring-managed UnitOfWorkFactory
    // Canonical Essentials mapper (Jackson 3) + KotlinModule for Kotlin documents
    Jackson3JSONSerializer(
        EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule.Builder().build())
    )
)
```

`KotlinModule` is required for Kotlin documents: without it a `@JvmInline value class` is written as
`{"value":"…"}` instead of the bare scalar. Upgrading from 0.50: `JacksonJSONSerializer` (Jackson 2) was removed —
use `Jackson3JSONSerializer`, and `com.fasterxml.jackson.module.kotlin` becomes `tools.jackson.module.kotlin`.

**Recommended KotlinModule config** (pass it to `createJackson3ObjectMapper(...)`; a `KotlinModule` *bean* reaches
only Spring Boot's web mapper — the Essentials starters deliberately do not add context `JacksonModule` beans to the
persistence serializer, so define your own `JSONSerializer` bean for that):
```kotlin
@Bean
fun kotlinModule() = KotlinModule.Builder()
    .withReflectionCacheSize(512)
    .configure(KotlinFeature.NullToEmptyCollection, false)
    .configure(KotlinFeature.NullIsSameAsDefault, false)
    .configure(KotlinFeature.StrictNullChecks, false)
    .build()
```

### Repository Creation

| Method | Use Case | Example |
|--------|----------|---------|
| `create(entityClass)` | `StringValueType` ID (recommended) | `factory.create(Order::class)` |
| `createForStringId(entityClass)` | Plain `String` ID | `factory.createForStringId(Product::class)` |
| `createForCompositeId(entityClass, idSerializer)` | Composite/custom ID | See [Composite ID Pattern](#composite-id-pattern) |

```kotlin
import dk.trustworks.essentials.components.document_db.DocumentDbRepository

val orderRepo: DocumentDbRepository<Order, OrderId> = factory.create(Order::class)
// Table + indexes auto-created

val productRepository: DocumentDbRepository<Product, String> =
    factory.createForStringId(Product::class)

val compositeRepository = factory.createForCompositeId(
    CompositeOrder::class
) { id -> "${id.orderId}_${id.addressId}" }  // IdSerializer function
```

### Spring Integration

```kotlin
@Configuration
class DocumentDbConfig {
    @Bean
    fun factory(
        jdbi: Jdbi,
        unitOfWorkFactory: HandleAwareUnitOfWorkFactory<*>,
        jsonSerializer: JSONSerializer
    ) = DocumentDbRepositoryFactory(jdbi, unitOfWorkFactory, jsonSerializer)

    @Bean
    fun orderRepo(factory: DocumentDbRepositoryFactory) =
        OrderRepository(factory.create(Order::class))
}
```

## CRUD Operations

| Method | Purpose | Returns | Notes |
|--------|---------|---------|-------|
| `save(entity)` | Create new | Entity w/ `version=ZERO` | Throws if ID exists |
| `save(entity, initialVersion)` | Create with custom version | Entity w/ specified version | Alternative to default ZERO |
| `update(entity)` | Modify existing | Entity w/ `version++` | Checks version, throws `OptimisticLockingException` |
| `update(entity, nextVersion)` | Update with explicit version | Entity w/ specified version | Alternative to auto-increment |
| `findById(id)` | Nullable lookup | `Entity?` | Safe when might not exist |
| `getById(id)` | Required lookup | `Entity` | Throws if missing |
| `existsById(id)` | Existence check | `Boolean` | No entity loading |
| `deleteById(id)` | Delete by ID | `Unit` | No-op if missing |
| `delete(entity)` | Delete by entity | `Unit` | Uses entity ID |
| `saveAll(list)` | Batch create | `List<Entity>` | All get `version=ZERO` |
| `updateAll(list)` | Batch update | `List<Entity>` | Individual version checks |
| `findAllById(ids)` | Batch load | `List<Entity>` | Single query |
| `findAll()` | Load all | `List<Entity>` | ⚠️ Caution: large tables |
| `count()` | Total entities | `Long` | No loading |
| `deleteAll(entities)` | Delete multiple | `Unit` | By entity references |
| `deleteAllById(ids)` | Delete multiple | `Unit` | By IDs |
| `deleteAll()` | Delete all | `Unit` | ⚠️ Caution |

### Examples

```kotlin
// Create
val order = Order(OrderId.random(), "John", Amount("99.99"), ...)
val saved = repo.save(order)  // version=ZERO

// Update
saved.amount = Amount("199.99")
val updated = repo.update(saved)  // version=1

// Read
val found = repo.findById(orderId)  // null if missing
val required = repo.getById(orderId)  // throws if missing

// Batch operations
val orders = listOf(order1, order2, order3)
repo.saveAll(orders)  // More efficient than loop
```

### Event Projection Pattern

⚠️ A `ViewEventProcessor` projection needs the event's order, so take `OrderedMessage` as the second
parameter and store `message.order` (the event's `EventOrder`) as the row version:

```kotlin
@MessageHandler
fun on(event: ProductAddedToOrder, message: OrderedMessage) {
    val view = repo.findById(event.orderId) ?: return
    if (view.version.value >= message.order) return        // already applied — redelivery
    view.itemCount++
    repo.update(view, Version(message.order))              // version = EventOrder, NOT auto-increment
}
```

- The second parameter is optional to the dispatcher — `PatternMatchingMessageHandler` invokes a
  single-argument `@MessageHandler` normally — so leaving it out compiles and runs. Without it the handler
  has no `EventOrder`, and a redelivered event is applied twice.
- `update(entity, nextVersion)` only rejects a *concurrent* writer (`WHERE version = <loaded version>`); it
  does not reject an event that was already applied. The skip check above is what makes the projection
  idempotent.
- `Version` is a Kotlin value class with a constructor and no `of()` factory: `Version(message.order)`.

## Query API

### Operators

| Kotlin | SQL | Example |
|--------|-----|---------|
| `eq` | `=` | `Order::name eq "John"` |
| `lt` | `<` | `Order::amount lt Amount("100")` |
| `lte` | `<=` | `Order::amount lte Amount("100")` |
| `gt` | `>` | `Order::amount gt Amount("100")` |
| `gte` | `>=` | `Order::amount gte Amount("50")` |
| `like` | `LIKE` | `Order::name like "%John%"` |
| `and` | `AND` | `(cond1).and(cond2)` |
| `or` | `OR` | `(cond1).or(cond2)` |

Each operator also has a **path-string overload** (`eq("address.city", value)`, optionally with a `DbType`) that needs no Kotlin property reference — see [Java Interop](#java-interop). Those overloads are callable from Kotlin too and are the right choice when the path is computed rather than statically known.

### Query Patterns

```kotlin
import dk.trustworks.essentials.components.document_db.postgresql.QueryBuilder

// Simple query
repo.queryBuilder()
    .where(repo.condition().matching {
        Order::personName eq "John Doe"
    })
    .find()

// Nested property (use `then`)
repo.queryBuilder()
    .where(repo.condition().matching {
        Order::address then Address::city eq "Springfield"
    })
    .find()

// Complex with sorting + pagination
repo.queryBuilder()
    .where(repo.condition().matching {
        (Order::personName like "%John%")
            .or(Order::personName like "%Jane%")
            .and(Order::amount gte Amount("100"))
    })
    .orderBy(Order::orderDate, QueryBuilder.Order.DESC)
    .limit(50)
    .offset(100)
    .find()

// Nested property sort
repo.queryBuilder()
    .where(condition)
    .orderBy(Order::address then Address::city, QueryBuilder.Order.ASC)
    .find()
```

## Indexing

### Annotation-Based (Top-Level Properties)

```kotlin
@DocumentEntity("orders")
data class Order(
    @Indexed var personName: String,  // idx_orders_personname
    @Indexed var status: String,      // idx_orders_status
    var description: String,          // Not indexed
    // ...
)
```

Index naming: `idx_${tableName}_${propertyName}` (lowercase)

### Programmatic (Nested/Composite)

```kotlin
import dk.trustworks.essentials.components.document_db.Index
import dk.trustworks.essentials.components.document_db.postgresql.then
import dk.trustworks.essentials.components.document_db.postgresql.asProperty

// Single nested property
repo.addIndex(Index(
    name = "city",
    properties = listOf(Order::address then Address::city)
))
// Creates: idx_orders_city

// Composite index
repo.addIndex(Index(
    name = "date_amount",
    properties = listOf(
        Order::orderDate.asProperty(),
        Order::amount.asProperty()
    )
))
// Creates: idx_orders_date_amount

// Remove index
repo.removeIndex("city")
```

### Path-Based (language-neutral)

`addIndexByPaths` builds an `Index` from dot-notation JSON paths — no Kotlin property references, so it works identically from Java and Kotlin:

```kotlin
repo.addIndexByPaths("name_city", "name", "address.city")
// equivalent to: repo.addIndex(Index.fromPaths("name_city", "name", "address.city"))
```

## Java Interop

The module is written in Kotlin but ships a first-class Java surface. Java code never needs `KClass`, `KProperty1`, or the `Version` value class.

### Entity: extend `JavaVersionedEntity<ID, SELF>`

`JavaVersionedEntity` is an abstract bridge class that implements `VersionedEntity.version` in terms of two primitive `long` accessors. Java entities extend it instead of implementing `VersionedEntity` directly.

```java
import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;
import dk.trustworks.essentials.components.document_db.annotations.Indexed;

@DocumentEntity(tableName = "java_products")
public class JavaProduct extends JavaVersionedEntity<String, JavaProduct> {
    @Id
    public String id;          // MUST be public — read by field access, see Java gotchas below
    @Indexed
    private String name;
    private long version = Version.NOT_SAVED_YET_VALUE;
    private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

    @Override public long getVersionValue()                 { return version; }
    @Override public void setVersionValue(long version)     { this.version = version; }
    @Override public OffsetDateTime getLastUpdated()        { return lastUpdated; }
    @Override public void setLastUpdated(OffsetDateTime lu) { this.lastUpdated = lu; }

    public String getId()   { return id; }
    public String getName() { return name; }
}
```

**Contract:** `JavaVersionedEntity` declares exactly two abstract members — `getVersionValue(): long` and `setVersionValue(long)`. It makes `version` a `final override`, so do **not** try to override `version` itself. `lastUpdated` is inherited from `VersionedEntity` as a Kotlin `var` of type `OffsetDateTime`, which Java must satisfy with the `getLastUpdated()`/`setLastUpdated(OffsetDateTime)` pair.

`Version` constants for Java: `Version.NOT_SAVED_YET_VALUE` (`-1L`) and `Version.ZERO_VALUE` (`0L`); also `Version.notSavedYetValue()` / `Version.zeroValue()`.

### Factory: `Class<T>` overloads

Every factory method has a Java-friendly `Class<T>` overload beside its `KClass<T>` original:

| Method | Use when the `@Id` property is |
|---|---|
| `createForStringId(Class<ENTITY>)` | a plain `String` |
| `create(Class<ENTITY>)` | a Kotlin `StringValueType` (value class) |
| `createForCompositeId(Class<ENTITY>, IdSerializer<ID>)` | anything else (incl. Java `CharSequenceType` IDs) |
| `createForCompositeId(Class<ENTITY>, java.util.function.Function<ID, String>)` | anything else — lambda form, most idiomatic from Java |

```java
DocumentDbRepository<JavaProduct, String> repo = factory.createForStringId(JavaProduct.class);
DocumentDbRepository<Patient, PatientId> patients =
    factory.createForCompositeId(Patient.class, PatientId::toString);
```

### CRUD: `long` version overloads

`save` and `update` each have a `long` overload so Java never constructs a `Version`:

```java
repo.save(product, Version.ZERO_VALUE);   // fun save(entity, initialVersionValue: Long)
repo.update(product, 42L);                // fun update(entity, nextVersionValue: Long)
```

### Queries: string paths + explicit `DbType`

`Condition` exposes non-infix, path-string overloads alongside the Kotlin property-reference infix forms. Dot notation addresses nested properties (`"address.city"`).

| Method | Overloads |
|---|---|
| `eq` / `lt` / `lte` / `gt` / `gte` | `(String path, Any? value)` and `(String path, Any? value, DbType dbType)` |
| `like` | `(String path, String value)` — always casts to `DbType.TEXT` |
| `and` / `or` | `(Condition<T> other)` |

Without a `DbType`, the JSON value is compared as text; pass a `DbType` to emit a `CAST(...)` so numeric and temporal comparisons order correctly.

**`DbType` values (complete):** `TEXT`, `INTEGER`, `BIGINT`, `REAL`, `DOUBLE_PRECISION`, `NUMERIC`, `BOOLEAN`, `SMALLINT`, `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMPTZ`.

```java
var result = repository.queryBuilder()
    .where(repository.condition()
        .eq("category", "Electronics")
        .lt("price", "1000.00", DbType.NUMERIC)
        .gte("createdAt", OffsetDateTime.now().minusDays(7), DbType.TIMESTAMPTZ))
    .orderBy("address.city", QueryBuilder.Order.ASC)
    .orderBy("price", DbType.NUMERIC, QueryBuilder.Order.ASC)
    .limit(100)
    .offset(0)
    .find();
```

Chained condition calls are combined with `AND`; use the explicit `and(Condition)` / `or(Condition)` methods only when you need to control grouping. `orderBy` has both `(String path, Order order)` and `(String path, DbType dbType, Order order)` forms — use the `DbType` form whenever the sort key is numeric or temporal, otherwise it sorts as text.

### Indexes from Java

```java
repo.addIndexByPaths("idx_name_city", "name", "address.city");
// or build one explicitly:
Index<JavaProduct> index = Index.fromPaths("idx_name_city", "name", "address.city");
```

`JsonPathProperty<T>` is the underlying path type: `new JsonPathProperty<JavaProduct>("address.city")` yields `name() == "address_city"`, `toJSONValueArrowPath() == "data->'address'->>'city'"`, and `toJSONArrowPath() == "data->'address'->'city'"`.

### Java gotchas

- ⚠️ **The `@Id` field must be `public`.** `EntityConfiguration` resolves `@Id` through Kotlin reflection over `memberProperties`. For a Java class the property is synthesised from the **field**, so the repository reads it by direct field access — *not* by calling `getId()` — and does not make it accessible. A `private` `@Id` makes the first `save`/`update`/`delete` throw Kotlin's `IllegalCallableAccessException` (wrapping `IllegalAccessException`). Nothing fails at repository creation, so inside a message handler it surfaces only as a failing/dead-lettered message and a projection that never populates.

  `version` and `lastUpdated` escape this because they are declared on `JavaVersionedEntity`/`VersionedEntity` as Kotlin properties with real getter methods, so their `KProperty1` getter is a method call, not a field read. The rule: **Kotlin-declared property → method call; Java-declared field → field read.**

- ⚠️ **A pure-Java module must declare `kotlin-stdlib-jdk8` and `kotlin-reflect` itself.** This module declares both in `provided` scope, so they are **not** transitive. Java code needs them at *compile* time regardless: `createForStringId` and the `Condition` DSL expose `KClass`/`KProperty1` overloads that javac must resolve in order to select the `Class`-based one. Without them the build fails with `cannot access kotlin.reflect.KClass`.
- ⚠️ **`@DocumentEntity` takes a named argument from Java**: `@DocumentEntity(tableName = "java_products")`, not the positional Kotlin form `@DocumentEntity("orders")`.
- ⚠️ **Initialise the version field to `Version.NOT_SAVED_YET_VALUE`**, matching the Kotlin default `Version.NOT_SAVED_YET`, so an unsaved entity reads as unsaved. `save()` overwrites the version with its `initialVersion` argument, so this is a marker, not what `save()` checks.
- ⚠️ **`@Indexed` only works on top-level properties** (see [Indexing](#indexing)). Nested paths need `addIndexByPaths` / `Index.fromPaths`.
- ⚠️ **Path strings are concatenated into SQL.** Every path segment is validated with `PostgresqlUtil.checkIsValidTableOrColumnName`, the same defence as table and property names — and with the same limits. Never build a path from user input. See [Security](#security).

## Custom Repositories

Extend `DelegatingDocumentDbRepository` for domain-specific queries + index management:

```kotlin
import dk.trustworks.essentials.components.document_db.DelegatingDocumentDbRepository

class OrderRepository(
    delegateTo: DocumentDbRepository<Order, OrderId>
) : DelegatingDocumentDbRepository<Order, OrderId>(delegateTo) {

    init {
        // Configure indexes once at startup
        delegateTo.addIndex(Index(
            "city",
            listOf(Order::address then Address::city)
        ))
    }

    fun findByCity(city: String) = queryBuilder()
        .where(condition().matching {
            Order::address then Address::city eq city
        })
        .find()

    fun findLargeOrders(minAmount: Amount) = queryBuilder()
        .where(condition().matching { Order::amount gt minAmount })
        .orderBy(Order::amount, QueryBuilder.Order.DESC)
        .find()
}

// Usage
val repo = OrderRepository(factory.create(Order::class))
val largeOrders = repo.findLargeOrders(Amount("1000"))
```

## Database Schema

Auto-created table structure:

```sql
CREATE TABLE IF NOT EXISTS orders (
    id           TEXT PRIMARY KEY,        -- From @Id property
    data         JSONB NOT NULL,          -- Full entity as JSON
    version      BIGINT,                  -- Optimistic lock version
    last_updated TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Example indexes
CREATE INDEX idx_orders_personname ON orders USING gin ((data->>'personName'));
CREATE INDEX idx_orders_city ON orders USING gin ((data->'address'->>'city'));
```

| Column | Maps To | Purpose |
|--------|---------|---------|
| `id` | `@Id` property | Primary key (String or serialized composite) |
| `data` | Entire entity | JSONB serialization |
| `version` | `VersionedEntity.version` | Optimistic locking |
| `last_updated` | `VersionedEntity.lastUpdated` | Auto-timestamp |

### Schema Evolution

| Change | Safe? | Notes |
|--------|-------|-------|
| Add property w/ default | ✅ | Existing rows deserialize with default |
| Add nullable property | ✅ | Existing rows get `null` |
| Remove unused property | ✅ | JSON ignores unknown fields |
| Rename property | ⚠️ | Use `@JsonAlias("oldName")` for compatibility |
| Change property type | ❌ | Breaks deserialization |

**Event Modeled Views**: Can change freely—delete all rows + replay events via [`ViewEventProcessor`](./LLM-postgresql-event-store.md#vieweventprocessor).

## Optimistic Locking

### How It Works
1. Entity has `version` (starts at `Version.ZERO` after `save()`)
2. `update()` checks: `WHERE id = :id AND version = :currentVersion`
3. If version mismatch → `OptimisticLockingException`
4. On success → version incremented

### Version States
- `Version.NOT_SAVED_YET` (-1) → Before first `save()`
- `Version.ZERO` (0) → After `save()`
- `Version(n)` where n > 0 → After n `update()` calls

### Conflict Handling

```kotlin
import dk.trustworks.essentials.components.document_db.OptimisticLockingException

try {
    order.description = "Updated by process A"
    repo.update(order)
} catch (e: OptimisticLockingException) {
    // Another process modified entity - reload and retry
    val fresh = repo.getById(order.orderId)
    fresh.description = order.description  // Apply changes
    repo.update(fresh)
}
```

## Common Patterns

### Composite ID Pattern

```kotlin
import dk.trustworks.essentials.components.document_db.IdSerializer

// Composite ID type
data class DocumentIdAndRevision(
    val documentId: DocumentId,
    val revision: DocumentRevision
) {
    companion object {
        val IdSerializer: IdSerializer<DocumentIdAndRevision> = {
            "${it.documentId.value}:${it.revision.value}"
        }
    }
}

// Entity
@DocumentEntity("documents")
data class DocumentView(
    @Id val id: DocumentIdAndRevision,
    @Indexed val documentId: DocumentId,  // Index for querying across all revisions
    val revision: DocumentRevision,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<DocumentIdAndRevision, DocumentView>

// Repository with composite index
class DocumentsRepository(factory: DocumentDbRepositoryFactory)
    : DelegatingDocumentDbRepository<DocumentView, DocumentIdAndRevision>(
        factory.createForCompositeId(
            DocumentView::class,
            DocumentIdAndRevision.IdSerializer
        )
    ) {
    init {
        delegateTo.addIndex(Index(
            "id_and_revision",
            listOf(
                DocumentView::documentId.asProperty(),
                DocumentView::revision.asProperty()
            )
        ))
    }

    fun findAllRevisions(docId: DocumentId) = queryBuilder()
        .where(condition().matching { DocumentView::documentId eq docId })
        .orderBy(DocumentView::revision, QueryBuilder.Order.DESC)
        .find()
}
```

## Gotchas

- ⚠️ **JDBI Registration**: Every Semantic type property requires `ArgumentFactory` + `ColumnMapper`. Missing registration → runtime errors during save/load.
- ⚠️ **Nested Queries**: Must use `then` operator: `Order::address then Address::city` (NOT `Order::address.city`).
- ⚠️ **Version Management**: Framework auto-increments version. Don't manipulate manually.
- ⚠️ **Auto IDs**: Recommended to generate explicitly (`OrderId.random()`) vs relying on `var id` auto-generation.
- ⚠️ **Batch Performance**: Use `saveAll()`/`updateAll()` for multiple entities, not loops with `save()`/`update()`.
- ⚠️ **Index Creation**: Call `addIndex()` in custom repository `init` block, not per-query.
- ⚠️ **Composite Index**: Use `.asProperty()` for top-level properties: `Order::amount.asProperty()`.
- ⚠️ **Java entities**: extend `JavaVersionedEntity<ID, SELF>` and implement `getVersionValue()` / `setVersionValue(long)` rather than implementing `VersionedEntity` directly (whose `version` is the Kotlin `Version` value class) — see [Java Interop](#java-interop) for the full Java surface.
- ⚠️ **CharSequenceType IDs**: IDs extending Java's `CharSequenceType` (not Kotlin `StringValueType`) need `createForCompositeId()` with an ID serializer. `create()` is bounded to `StringValueType` IDs and will not compile for them:
  ```kotlin
  // ✅ Kotlin StringValueType ID
  val repo = factory.create(Order::class)

  // ✅ Java CharSequenceType ID (e.g., shared types)
  val repo = factory.createForCompositeId(Patient::class) { id -> id.toString() }

  // ❌ Java CharSequenceType ID with create() — does not compile (ID must be a StringValueType)
  val repo = factory.create(Patient::class)
  ```
- ⚠️ **`Version` has no `of()`**: it is a Kotlin value class — construct it, `Version(message.order)`. From Java use the `long` overloads and `Version.NOT_SAVED_YET_VALUE` / `Version.ZERO_VALUE`.
- ⚠️ **Path-string queries compare as text**: `lt("price", "1000.00")` compares the JSON value as text, so numeric and temporal ranges (and `orderBy(path, …)`) order wrongly. Pass a `DbType` — `lt("price", "1000.00", DbType.NUMERIC)`, `orderBy("price", DbType.NUMERIC, Order.ASC)` — to emit a `CAST`.
- ⚠️ **findById vs getById**: `findById()` returns `null` when not found; `getById()` is `findById(id)!!` and throws `NullPointerException`. In a projector whose view may not exist yet (created by an event from another stream, or not yet created), use `findById()` and handle the `null`.

## Security

### ⚠️ Critical: SQL Injection Risk

Table names (`@DocumentEntity`), property names, and index names are used in SQL via **string concatenation** → SQL injection risk.

> ⚠️ **SQL Injection Risk**: `@DocumentEntity.tableName`, entity property names, and `Index.name` are used in SQL statements via string concatenation.

**Mitigations:**
- `PostgresqlUtil.checkIsValidTableOrColumnName()` validates at startup
- `EntityConfiguration.checkPropertyNames()` validates property names
- Provides basic defense but NOT complete protection

**Developer Responsibility:**
- Only use table/index/property names from controlled sources
- NEVER derive from external/untrusted input
- Validate all entity definitions during development

See [README Security](https://github.com/trustworksdk/essentials-project/blob/0.60.0/components/postgresql-document-db/README.md#security) for details.

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

## Test References

- `PostgresqlDocumentDbRepositoryIT.kt` - Main integration tests
  - Composite ID repository
  - Index management
  - Optimistic locking (lines 181-197)
- `QueryIT.kt` - Query API tests

## See Also

- [README](https://github.com/trustworksdk/essentials-project/blob/0.60.0/components/postgresql-document-db/README.md) - Full documentation with motivation and deep dives
- [LLM-foundation](./LLM-foundation.md) - UnitOfWork, PostgresqlUtil
- [LLM-types](./LLM-types.md) - SingleValueType pattern
- [LLM-types-jdbi](./LLM-types-jdbi.md) - JDBI type registration
- [LLM-postgresql-event-store](./LLM-postgresql-event-store.md) - ViewEventProcessor for projections
- [LLM-spring-boot-starter-modules](./LLM-spring-boot-starter-modules.md) - Auto-configuration
