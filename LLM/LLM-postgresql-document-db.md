# postgresql-document-db - LLM Reference

**WORK-IN-PROGRESS** - Kotlin document database using PostgreSQL JSONB with type-safe queries, optimistic locking, and automatic schema management.

## TOC
- [Quick Facts](#quick-facts)
- [Core Concepts](#core-concepts)
- [Entity Definition](#entity-definition)
- [Repository Setup](#repository-setup)
- [CRUD Operations](#crud-operations)
- [Query API](#query-api)
- [Indexing](#indexing)
- [Custom Repositories](#custom-repositories)
- [Database Schema](#database-schema)
- [Optimistic Locking](#optimistic-locking)
- [Common Patterns](#common-patterns)
- [Gotchas](#gotchas)
- ⚠️ [Security](#security)
- [Test References](#test-references)

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.document_db`
- **Language**: Kotlin (data classes, value classes, property references)
- **Storage**: PostgreSQL JSONB column with ACID guarantees
- **Key deps**: PostgreSQL, JDBI, Jackson, Kotlin stdlib/reflect (all `provided` scope)
- **Maven**: `dk.trustworks.essentials.components:postgresql-document-db`
- **Status**: WORK-IN-PROGRESS (experimental)

## Core Concepts

| Concept | Class/Interface | Role |
|---------|----------------|------|
| Repository | `DocumentDbRepository<E, ID>` | CRUD + type-safe queries for JSONB documents |
| Factory | `DocumentDbRepositoryFactory` | Create repositories, auto-create tables/indexes |
| Entity contract | `VersionedEntity<ID, SELF>` | Required interface: adds `version` + `lastUpdated` |
| Optimistic locking | `Version` | Prevent concurrent update conflicts (auto-incremented) |
| Type-safe queries | `QueryBuilder<E>` | Kotlin property refs → PostgreSQL JSON paths |
| Indexes | `@Indexed`, `Index<E>` | Performance via GIN indexes on JSONB properties |

## Entity Definition

### Requirements
1. Implement `VersionedEntity<ID, SELF_TYPE>`
2. `@DocumentEntity("table_name")` annotation
3. `@Id` annotation on ID property
4. ID must be `StringValueType`, `String` and use `DocumentDbRepositoryFactory.createForStringId(entityClass)` or for composite id's use `DocumentDbRepositoryFactory.createForCompositeId(entityClass, idSerializer)`

### Basic Entity Pattern

```kotlin
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
@JvmInline
value class OrderId(override val value: String) : StringValueType<OrderId> {
    companion object {
        fun random() = OrderId(RandomIdGenerator.generate())
    }
}
```

### JDBI Type Registration (REQUIRED)

Every `SingleValueType` property needs JDBI registration:

```kotlin
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

> **Note**: `Version` is auto-registered by `DocumentDbRepositoryFactory`.
> See `dk.trustworks.essentials.components.boot.autoconfigure.postgresql.JdbiConfigurationCallback` for easy per component Jdbi configuration in Spring Boot.

### Version Property

```kotlin
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

`DocumentDbRepositoryFactory` creates configured `DocumentDbRepository` instances as well as table creation, index setup, and serialization. Create once, reuse for all repositories.

```kotlin
val factory = DocumentDbRepositoryFactory(
    jdbi,
    JdbiUnitOfWorkFactory(jdbi),  // Or Spring-managed UnitOfWorkFactory
    JacksonJSONSerializer(
        EssentialsImmutableJacksonModule.createObjectMapper(
            Jdk8Module(),
            JavaTimeModule()
        ).registerKotlinModule()
    )
)
```

**Recommended KotlinModule config:**
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
val orderRepo: DocumentDbRepository<Order, OrderId> = factory.create(Order::class)
// Table + indexes auto-created
val productRepository: DocumentDbRepository<Product, String> =
    repositoryFactory.createForStringId(Product::class)
val compositeRepository = repositoryFactory.createForCompositeId(
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
| `update(entity)` | Modify existing | Entity w/ `version++` | Checks version, throws `OptimisticLockingException` |
| `findById(id)` | Nullable lookup | `Entity?` | Safe when might not exist |
| `getById(id)` | Required lookup | `Entity` | Throws if missing |
| `existsById(id)` | Existence check | `Boolean` | No entity loading |
| `deleteById(id)` | Delete by ID | `Unit` | No-op if missing |
| `saveAll(list)` | Batch create | `List<Entity>` | All get `version=ZERO` |
| `updateAll(list)` | Batch update | `List<Entity>` | Individual version checks |
| `findAllById(ids)` | Batch load | `List<Entity>` | Single query |
| `findAll()` | Load all | `List<Entity>` | Caution: large tables |
| `count()` | Total entities | `Long` | No loading |
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

### Query Patterns

```kotlin
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

## Custom Repositories

Extend `DelegatingDocumentDbRepository` for domain-specific queries + index management:

```kotlin
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

- ⚠️ **JDBI Registration**: Every `SingleValueType` property requires `ArgumentFactory` + `ColumnMapper`. Missing registration → runtime errors during save/load.
- ⚠️ **Nested Queries**: Must use `then` operator: `Order::address then Address::city` (NOT `Order::address.city`).
- ⚠️ **Version Management**: Framework auto-increments version. Don't manipulate manually.
- ⚠️ **Auto IDs**: Recommended to generate explicitly (`OrderId.random()`) vs relying on `var id` auto-generation.
- ⚠️ **Batch Performance**: Use `saveAll()`/`updateAll()` for multiple entities, not loops with `save()`/`update()`.
- ⚠️ **Index Creation**: Call `addIndex()` in custom repository `init` block, not per-query.
- ⚠️ **Composite Index**: Use `.asProperty()` for top-level properties: `Order::amount.asProperty()`.

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function-names that are used with **String concatenation** → SQL injection risk.  
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

> ⚠️ **SQL Injection Risk**: Table names (`@DocumentEntity`), property names, and index names are used to generate SQL statement using string concatenation.

**Mitigations:**
- `PostgresqlUtil.checkIsValidTableOrColumnName()` validates at startup
- `EntityConfiguration.checkPropertyNames()` validates property names
- Provides basic defense but NOT complete protection

**Developer Responsibility:**
- Only use table/index/property names from controlled sources
- NEVER derive from external/untrusted input
- Validate all entity definitions during development

See [README Security](../components/postgresql-document-db/README.md#security) for details.

## Test References

- `PostgresqlDocumentDbRepositoryIT.kt` - Main integration tests
  - Line 83: Composite ID repository
  - Lines 88-89: Index management
  - Lines 181-197: Optimistic locking
- `QueryIT.kt` - Query API tests

## See Also

- [README](../components/postgresql-document-db/README.md) - Full documentation with motivation and deep dives
- [LLM-foundation](./LLM-foundation.md) - UnitOfWork, PostgresqlUtil
- [LLM-types](./LLM-types.md) - SingleValueType pattern
- [LLM-types-jdbi](./LLM-types-jdbi.md) - JDBI type registration
- [LLM-postgresql-event-store](./LLM-postgresql-event-store.md) - ViewEventProcessor for projections
- [LLM-spring-boot-starter-modules](./LLM-spring-boot-starter-modules.md) - Auto-configuration
