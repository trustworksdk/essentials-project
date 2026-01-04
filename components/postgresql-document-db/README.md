# Essentials Components - PostgreSQL Document DB

> **NOTE:** **The library is WORK-IN-PROGRESS**

A Kotlin-based Document Database built on PostgreSQL JSONB, providing flexible JSON storage with type-safe queries, optimistic locking, and automatic schema management.

**LLM Context:** [LLM-postgresql-document-db.md](../../LLM/LLM-postgresql-document-db.md)

## Table of Contents

- [Maven Dependency](#maven-dependency)
- [Security](#security)
- [Overview](#overview)
- [Defining Entities](#defining-entities)
- [Repository Setup](#repository-setup)
- [CRUD Operations](#crud-operations)
- [Querying](#querying)
- [Indexing](#indexing)
- [Custom Repositories](#custom-repositories)
- [Optimistic Locking](#optimistic-locking)

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-document-db</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required `provided` dependencies** (you must add these to your project):

```xml
<!-- Kotlin -->
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-stdlib-jdk8</artifactId>
    <version>${kotlin.version}</version>
</dependency>
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-reflect</artifactId>
    <version>${kotlin.version}</version>
</dependency>

<!-- PostgreSQL & JDBI -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>${postgresql.version}</version>
</dependency>
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-core</artifactId>
    <version>${jdbi.version}</version>
</dependency>

<!-- Jackson -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>${jackson.version}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.module</groupId>
    <artifactId>jackson-module-kotlin</artifactId>
    <version>${jackson.version}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
    <version>${jackson.version}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jdk8</artifactId>
    <version>${jackson.version}</version>
</dependency>

<!-- Logging -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>${slf4j.version}</version>
</dependency>
```

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

The `@DocumentEntity.tableName`, entity property names, and `Index.name` are used directly in SQL statements via string concatenation, exposing the component to **SQL injection attacks**.

> ⚠️ **WARNING:** It is your responsibility to sanitize these values to prevent SQL injection.  
> See the Components [Security](../README.md#security) section for more details.

**Mitigations:**
- `PostgresqlDocumentDbRepository` calls `PostgresqlUtil#checkIsValidTableOrColumnName(String)` for basic validation at startup
- Entity property names are validated via `EntityConfiguration.checkPropertyNames()`
- This provides initial defense but does **NOT** guarantee complete SQL injection protection

**Developer Responsibility:**
- Only use table, index, and property names from controlled, trusted sources
- Never derive these values from external/untrusted input
- Validate all entity class definitions during development

## Overview

### Why

Traditional ORMs require rigid schemas and migrations. Document databases offer flexibility but often lack ACID guarantees.
PostgreSQL Document DB combines the best of both worlds:

| Feature | Benefit                                                                               |
|---------|---------------------------------------------------------------------------------------|
| **JSONB Storage** | Flexible, schema-less documents with PostgreSQL's ACID guarantees                     |
| **Type-Safe Queries** | Kotlin property references prevent typos and enable IDE support                       |
| **Optimistic Locking** | Built-in version checking prevents concurrent update conflicts                        |
| **Automatic Schema** | Tables and indexes created automatically on startup                                   |
| **Semantic Types** | First-class support for `SingleValueType` properties and `StringValueType` identifiers |

### Quick Start

A complete example showing entity definition, CRUD operations, and querying:

```kotlin
// 1. Define a semantic ID type
@JvmInline
value class ProductId(override val value: String) : StringValueType<ProductId> {
    companion object {
        fun random() = ProductId(RandomIdGenerator.generate())
    }
}

// 2. Define an entity
@DocumentEntity("products")
data class Product(
    @Id val id: ProductId,
    @Indexed var name: String,
    var price: Amount,
    var category: String,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<ProductId, Product>

// 3. Create a repository
val productRepo = repositoryFactory.create(Product::class)

// 4. Save a new entity
val product = Product(
    id = ProductId.random(),
    name = "Laptop",
    price = Amount("999.99"),
    category = "Electronics"
)
val saved = productRepo.save(product)  // version = 0

// 5. Load and update
val loaded = productRepo.getById(saved.id)
loaded.price = Amount("899.99")
val updated = productRepo.update(loaded)  // version = 1

// 6. Query with type-safe conditions
val electronics = productRepo.queryBuilder()
    .where(productRepo.condition().matching {
        (Product::category eq "Electronics")
            .and(Product::price lt Amount("1000.00"))
    })
    .orderBy(Product::name, QueryBuilder.Order.ASC)
    .find()
```

## Defining Entities

**Package:** `dk.trustworks.essentials.components.document_db`

Entities are Kotlin data classes representing your domain objects.  
The repository serializes the entire entity to JSON and stores it in PostgreSQL's JSONB column—no schema migrations required.
You can add, remove, or rename properties at any time; the JSON structure simply evolves with your code.

**Two usage patterns:**

| Pattern | Description | Schema Evolution |
|---------|-------------|------------------|
| **Long-lived entities** | Traditional CRUD data that persists indefinitely | Requires backwards-compatible changes (see [Schema Evolution](#schema-evolution)) |
| **Event Modeled Views** | Projections built from event streams that can be deleted and recreated (see [`ViewEventProcessor`](../postgresql-event-store/README.md#vieweventprocessor)) | Schema can change freely—just rebuild the view |

All entities must implement `VersionedEntity<ID, SELF>`, which adds two repository-managed properties:

| Property | Type | Purpose |
|----------|------|---------|
| `version` | `Version` | Incremented on each update for optimistic locking |
| `lastUpdated` | `OffsetDateTime` | Automatically set to the current timestamp on save/update |

### Entity Requirements

1. Implement `VersionedEntity<ID, SELF_TYPE>`
2. Annotate with `@DocumentEntity("table_name")`
3. Mark the ID property with `@Id`
4. ID must be `String` or `StringValueType` (or use `createForCompositeId()`)

### Basic Entity

```kotlin
@DocumentEntity("orders")
data class Order(
    @Id
    val orderId: OrderId,            // Semantic StringValueType ID
    var description: String,
    var amount: Amount,              // Semantic BigDecimal type
    var orderDate: OrderDate,        // Semantic LocalDateTime type
    @Indexed
    var personName: String,
    var invoiceAddress: Address,        // Nested object
    var contactDetails: ContactDetails, // Nested object
    
    // VersionedEntity properties with applied default values
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<OrderId, Order>

// Nested types (no annotations needed)
data class Address(val street: String, val zipCode: Int, val city: String)
data class ContactDetails(val name: String, val address: Address, val phoneNumbers: List<String>)
```

### Semantic Value Types

Use Kotlin value classes that extend appropriate `SingleValueType` interfaces from the [types](../../types/README.md) module.  
This provides compile-time type safety—you can't accidentally pass a `ProductId` where an `OrderId` is expected, or mix up different date fields.

**String-based ID type:**

```kotlin
@JvmInline
value class OrderId(override val value: String) : StringValueType<OrderId> {
    companion object {
        fun random(): OrderId = OrderId(RandomIdGenerator.generate())
    }
}
```

**Date/time semantic type:**

```kotlin
@JvmInline
value class OrderDate(override val value: LocalDateTime) : LocalDateTimeValueType<OrderDate> {
    companion object {
        fun now(): OrderDate = OrderDate(LocalDateTime.now())
    }
}
```

### JDBI Type Registration

All `SingleValueType` properties used in your entities require JDBI `ArgumentFactory` and `ColumnMapper` registration. This includes:
- **ID properties** (e.g., `OrderId`, `ProductId`)
- **Value properties** (e.g., `Amount`, `Percentage`, `OrderDate`)

For each semantic type, create the appropriate factories by extending the base classes:

```kotlin
// String ID type
class OrderIdArgumentFactory : StringValueTypeArgumentFactory<OrderId>()
class OrderIdColumnMapper : StringValueTypeColumnMapper<OrderId>()

// LocalDateTime type
class OrderDateArgumentFactory : LocalDateTimeValueTypeArgumentFactory<OrderDate>()
class OrderDateColumnMapper : LocalDateTimeValueTypeColumnMapper<OrderDate>()
```

Register all semantic types with JDBI during initialization:

```kotlin
jdbi.apply {
    // Custom ID types
    registerArgument(OrderIdArgumentFactory())
    registerColumnMapper(OrderIdColumnMapper())

    // Custom date types
    registerArgument(OrderDateArgumentFactory())
    registerColumnMapper(OrderDateColumnMapper())

    // Built-in types from types-jdbi module
    registerArgument(AmountArgumentFactory())
    registerColumnMapper(AmountColumnMapper())
}
```

> **Tip:** The [types-jdbi](../../types-jdbi/README.md) module provides pre-built factories for common types like `Amount` and `CountryCode`.    
> You only need to create custom factories for your own domain-specific types.
> See `dk.trustworks.essentials.components.boot.autoconfigure.postgresql.JdbiConfigurationCallback` for easy per component Jdbi configuration in Spring Boot.

### Version

Every entity must include a `version` property for optimistic locking.  
The `Version` class is a value class wrapping a `Long` that the repository increments automatically on each update.

When you create a new entity, initialize the `version` to `Version.NOT_SAVED_YET`.  
After calling `save()`, the `version` becomes `Version.ZERO`.   
Each subsequent `update()` increments the `version` by 1.

```kotlin
// Library provided Version type
@JvmInline
value class Version(override val value: Long) : LongValueType<Version> {
    companion object {
        val ZERO = Version(0)           // After first save
        val NOT_SAVED_YET = Version(-1) // Before save (default)
    }
}
```

> **Note:** The `DocumentDbRepositoryFactory` automatically registers `VersionArgumentFactory` and `VersionColumnMapper` with JDBI—you don't need to register these manually.

**Test reference:** `PostgresqlDocumentDbRepositoryIT.kt`

## Repository Setup

The Repository pattern separates your domain logic from persistence concerns. Instead of writing SQL or managing JDBC connections directly, you interact with a clean interface that handles all database operations.

**Two key components:**

| Component | Role                                                                                                                                                    |
|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `DocumentDbRepository<E, ID>` | The interface you use for all CRUD operations—save, update, find, delete, and query. One instance per entity type.                                      |
| `DocumentDbRepositoryFactory` | Creates configured `DocumentDbRepository` instances as well as table creation, index setup, and serialization. Create once, reuse for all repositories. |

This separation means your domain code depends only on the repository interface, making it easy to test (mock the repository) and keeping persistence details isolated.

### DocumentDbRepositoryFactory

The factory is the entry point for creating repositories. It requires three dependencies:

| Dependency | Purpose |
|------------|---------|
| `Jdbi` | Database connection and query execution |
| `UnitOfWorkFactory` | Transaction management (see [Foundation - UnitOfWork](../foundation/README.md#unitofwork-transactions)) |
| `JSONSerializer` | Entity serialization to/from JSONB |

The factory also handles automatic setup when creating repositories:
- Creates the entity table if it doesn't exist
- Creates indexes from `@Indexed` annotations
- Registers `Version` type with JDBI

Create a single factory instance and reuse it for all repositories:

```kotlin
val repositoryFactory = DocumentDbRepositoryFactory(
    jdbi,
    JdbiUnitOfWorkFactory(jdbi),
    JacksonJSONSerializer(
        EssentialsImmutableJacksonModule.createObjectMapper(
            Jdk8Module(),
            JavaTimeModule()
        ).registerKotlinModule()
    )
)
```

### Creating Repositories

The factory provides three methods for creating repositories, depending on your ID type strategy. Each method returns a fully configured `DocumentDbRepository` ready for use.

| Method | Use Case |
|--------|----------|
| `create(entityClass)` | Entities with `StringValueType` IDs (recommended) |
| `createForStringId(entityClass)` | Entities with plain `String` IDs |
| `createForCompositeId(entityClass, idSerializer)` | Entities with composite or custom ID types |

**For `StringValueType` IDs (recommended):**

```kotlin
val orderRepository: DocumentDbRepository<Order, OrderId> =
    repositoryFactory.create(Order::class)
```

**For `String` IDs:**

```kotlin
val productRepository: DocumentDbRepository<Product, String> =
    repositoryFactory.createForStringId(Product::class)
```

**For composite/custom IDs:**

```kotlin
val compositeRepository = repositoryFactory.createForCompositeId(
    CompositeOrder::class
) { id -> "${id.orderId}_${id.addressId}" }  // IdSerializer function
```

> **Tip:** For production use, wrap `DocumentDbRepository` in a [Custom Repository](#custom-repositories) to encapsulate domain-specific queries and add indexes programmatically.

### Spring Integration

When using Spring Boot, you can leverage dependency injection to wire up the factory and repositories.  
With [`spring-boot-starter-postgresql`](../spring-boot-starter-postgresql/README.md), the core dependencies (`Jdbi`, `UnitOfWorkFactory`, `JSONSerializer`) are auto-configured as beans.

**Recommended KotlinModule configuration:**

```kotlin
@Bean
fun kotlinJacksonModule(): KotlinModule {
    return KotlinModule.Builder()
        .withReflectionCacheSize(512)
        .configure(KotlinFeature.NullToEmptyCollection, false)
        .configure(KotlinFeature.NullToEmptyMap, false)
        .configure(KotlinFeature.NullIsSameAsDefault, false)
        .configure(KotlinFeature.SingletonSupport, false)
        .configure(KotlinFeature.StrictNullChecks, false)
        .build()
}
```

**Factory and repository bean configuration:**

```kotlin
@Configuration
class DocumentDbConfig {

    @Bean
    fun documentDbRepositoryFactory(
        jdbi: Jdbi,
        unitOfWorkFactory: HandleAwareUnitOfWorkFactory<*>,
        jsonSerializer: JSONSerializer
    ) = DocumentDbRepositoryFactory(jdbi, unitOfWorkFactory, jsonSerializer)

    @Bean
    fun orderRepository(factory: DocumentDbRepositoryFactory): OrderRepository {
        return OrderRepository(factory.create(Order::class))
    }
}
```

### Database Schema

Understanding the underlying table structure helps when debugging or writing custom queries.  
When you create a repository, the factory automatically creates a table for that entity type (if it doesn't exist).

The table name is determined by the `@DocumentEntity` annotation on your entity class:

```kotlin
@DocumentEntity("orders")  // Creates table named "orders"
data class Order(...) : VersionedEntity<OrderId, Order>
```

> ⚠️ **Security:** The table name is used directly in SQL statements with String concatenation.  
> See ⚠️[Security](#security) for sanitization requirements.

**Generated table structure:**

```sql
CREATE TABLE IF NOT EXISTS orders (
    id           TEXT PRIMARY KEY,
    data         JSONB NOT NULL,
    version      BIGINT,
    last_updated TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

| Column | Purpose                                                                                                |
|--------|--------------------------------------------------------------------------------------------------------|
| `id` | Entity identifier (extracted from `@Id` property, or serialized via `IdSerializer` for composite IDs)  |
| `data` | Complete entity serialized as JSON                                                                  |
| `version` | Optimistic locking version number (maps to `VersionedEntity.version`)                            |
| `last_updated` | Automatic timestamp for last modification  (maps to `VersionedEntity.lastUpdated`)          |

The entire entity (including nested objects and collections) is stored in the `data` column.
This means you can evolve your entity structure without database migrations—just add or remove properties as needed.

### Schema Evolution

Since entities are stored as JSON, schema changes don't require database migrations. However, you need to consider how existing data will deserialize after code changes.

**For long-lived entities** (data that persists indefinitely), follow these backwards-compatible practices:

| Change | Safe? | Notes |
|--------|-------|-------|
| Add property with default | ✅ Yes | Existing rows deserialize with the default value |
| Add nullable property | ✅ Yes | Existing rows deserialize as `null` |
| Remove unused property | ✅ Yes | JSON ignores unknown fields (configure Jackson accordingly) |
| Rename property | ⚠️ Careful | Use `@JsonAlias("oldName")` to read both old and new names |
| Change property type | ❌ No | Existing data won't deserialize correctly |

**For Event Modeled Views** (projections from event streams), schema evolution is simpler:

1. Change your entity structure as needed
2. Delete all rows from the table (or drop and recreate)
3. Replay events to rebuild the view with the new schema

The [`ViewEventProcessor`](../postgresql-event-store/README.md#vieweventprocessor) from the Event Store module automates this pattern—it tracks replay state and handles view recreation when you change the processor's event handlers.

## CRUD Operations

The `DocumentDbRepository` provides a complete set of operations for managing entities.  
All operations are transactional—they participate in the current `UnitOfWork` if one exists, or create a new transaction if not.

### Save (Create New Entity)

Use `save()` to persist a **new** entity to the database. The entity must not already exist (based on its ID).

```kotlin
val order = Order(
    orderId = OrderId.random(),
    description = "New Order",
    amount = Amount("100.00"),
    orderDate = OrderDate.now(),
    personName = "John Doe",
    invoiceAddress = Address("123 Main St", 12345, "Springfield"),
    contactDetails = ContactDetails("John", Address("123 Main St", 12345, "Springfield"), listOf("555-1234"))
)

val savedOrder = orderRepository.save(order)
// savedOrder.version == Version.ZERO (first save always sets version to 0)
```

**What happens:**
- The entity is serialized to JSON and inserted into the database
- The `version` is set to `Version.ZERO` (or your specified initial version)
- The `lastUpdated` timestamp is set to the current time
- Returns the saved entity with updated `version` and `lastUpdated`

**Auto-generated IDs:** If your ID property is declared as `var` (mutable) and is `null`, the repository generates a random ID automatically.   
However, we recommend explicitly generating IDs (as shown above with `OrderId.random()`) for clarity.

**Error handling:**
- Throws `OptimisticLockingException` if an entity with the same ID already exists
- Use `update()` instead if you want to modify an existing entity

**Variant:** `save(entity, initialVersion)` lets you specify a custom starting version instead of `Version.ZERO`.

### Update (Modify Existing Entity)

Use `update()` to persist changes to an **existing** entity. The entity must have been previously saved.

```kotlin
// 1. Load the entity
val order = orderRepository.findById(OrderId("order1"))!!

// 2. Modify properties
order.description = "Updated description"
order.amount = Amount("150.00")

// 3. Save changes
val updatedOrder = orderRepository.update(order)
// updatedOrder.version == previous version + 1
```

**What happens:**
- The repository checks that the entity's current `version` matches the database
- If versions match: updates the row, increments `version`, updates `lastUpdated`
- If versions don't match: throws `OptimisticLockingException` (another process modified the entity)

**Optimistic locking in action:** This pattern prevents lost updates. If two processes load the same entity, modify it, and try to save, the second one will fail with `OptimisticLockingException`. See [Optimistic Locking](#optimistic-locking) for handling strategies.

**Variant:** `update(entity, nextVersion)` lets you specify the exact next version instead of auto-incrementing.

### Read (Query Entities)

Multiple methods for retrieving entities, depending on your needs:

| Method | Returns | When to Use |
|--------|---------|-------------|
| `findById(id)` | `Entity?` | When the entity might not exist (returns `null` if not found) |
| `getById(id)` | `Entity` | When the entity must exist (throws exception if not found) |
| `existsById(id)` | `Boolean` | When you only need to check existence without loading the entity |
| `findAll()` | `List<Entity>` | Load all entities (use with caution on large tables) |
| `findAllById(ids)` | `List<Entity>` | Load multiple entities by their IDs in a single query |
| `count()` | `Long` | Get total number of entities without loading them |

```kotlin
// Safe lookup - handle missing entity gracefully
val order = orderRepository.findById(OrderId("order1"))
if (order != null) {
    // Process the order
}

// Assertive lookup - throws if not found (use when ID is known to be valid)
val order = orderRepository.getById(OrderId("order1"))

// Check before expensive operation
if (orderRepository.existsById(orderId)) {
    // Proceed with operation
}

// Batch loading - more efficient than multiple findById calls
val orders = orderRepository.findAllById(listOf(
    OrderId("order1"),
    OrderId("order2"),
    OrderId("order3")
))
```

> **Tip:** For complex queries with filtering, sorting, and pagination, use the [Query API](#querying) instead.

### Delete (Remove Entities)

Multiple methods for removing entities:

| Method | Description |
|--------|-------------|
| `deleteById(id)` | Delete by ID (no-op if entity doesn't exist) |
| `delete(entity)` | Delete by entity reference |
| `deleteAllById(ids)` | Delete multiple entities by their IDs |
| `deleteAll(entities)` | Delete multiple entity references |
| `deleteAll()` | Delete **all** entities in the table (use with caution!) |

```kotlin
// Delete single entity by ID
orderRepository.deleteById(OrderId("order1"))

// Delete entity you already have loaded
orderRepository.delete(order)

// Batch delete by IDs
orderRepository.deleteAllById(listOf(
    OrderId("order1"),
    OrderId("order2")
))

// Delete all (typically used for Event Modeled Views during rebuild)
orderRepository.deleteAll()
```

> **Note:** Delete operations don't check versions—they succeed regardless of the entity's current state.

### Batch Operations

For better performance when working with multiple entities, use batch operations instead of calling single-entity methods in a loop:

| Method | Description |
|--------|-------------|
| `saveAll(entities)` | Save multiple new entities in a single operation |
| `updateAll(entities)` | Update multiple existing entities in a single operation |

```kotlin
// Save multiple new entities
val newOrders = listOf(order1, order2, order3)
val savedOrders = orderRepository.saveAll(newOrders)
// Each entity gets Version.ZERO

// Update multiple entities
val modifiedOrders = listOf(order1, order2)
val updatedOrders = orderRepository.updateAll(modifiedOrders)
// Each entity's version is incremented individually
```

> **Note:** Batch operations still perform individual version checks for each entity. If any entity fails (e.g., version mismatch), the entire batch operation may fail depending on transaction configuration.

## Querying

The query API provides type-safe SQL-like queries using Kotlin property references.  
Instead of writing raw SQL strings with JSON path expressions, you use Kotlin's `::propertyName` syntax.  
This catches errors at compile time and enables IDE autocomplete.

Under the hood, the query builder generates PostgreSQL JSON operators like `data->>'propertyName'` and handles type casting automatically.  
For nested objects, use the `then` operator to chain property references.

### Basic Query

```kotlin
val orders = orderRepository.queryBuilder()
    .where(orderRepository.condition()
        .matching {
            Order::personName eq "John Doe"
        })
    .find()
```

### Comparison Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `eq` | Equals | `Order::amount eq Amount("100.00")` |
| `lt` | Less than | `Order::amount lt Amount("100.00")` |
| `lte` | Less than or equal | `Order::amount lte Amount("100.00")` |
| `gt` | Greater than | `Order::amount gt Amount("100.00")` |
| `gte` | Greater than or equal | `Order::amount gte Amount("100.00")` |
| `like` | SQL LIKE pattern | `Order::personName like "%John%"` |

### Logical Operators

```kotlin
val orders = orderRepository.queryBuilder()
    .where(orderRepository.condition()
        .matching {
            (Order::personName like "%John%")
                .or(Order::personName like "%Jane%")
                .and(Order::description like "%urgent%")
        })
    .find()
```

### Nested Property Queries

Use `then` to navigate into nested objects:

```kotlin
val orders = orderRepository.queryBuilder()
    .where(orderRepository.condition()
        .matching {
            Order::contactDetails then ContactDetails::address then Address::city eq "Springfield"
        })
    .find()
```

### Sorting

```kotlin
// Single property
val orders = orderRepository.queryBuilder()
    .where(condition)
    .orderBy(Order::orderDate, QueryBuilder.Order.DESC)
    .find()

// Nested property
val orders = orderRepository.queryBuilder()
    .where(condition)
    .orderBy(Order::contactDetails then ContactDetails::address then Address::city, QueryBuilder.Order.ASC)
    .find()
```

### Pagination

```kotlin
val page = orderRepository.queryBuilder()
    .where(orderRepository.condition()
        .matching { Order::amount gt Amount("0.00") })
    .orderBy(Order::orderDate, QueryBuilder.Order.DESC)
    .offset(50)   // Skip first 50
    .limit(25)    // Return 25 results
    .find()
```

### Complete Example

```kotlin
val result = orderRepository.queryBuilder()
    .where(orderRepository.condition()
        .matching {
            (Order::contactDetails then ContactDetails::address then Address::city like "Spring%")
                .and(Order::amount gte Amount("100.00"))
        })
    .orderBy(Order::orderDate, QueryBuilder.Order.DESC)
    .limit(100)
    .offset(0)
    .find()
```

**Test reference:** `QueryIT.kt`

## Indexing

Indexes dramatically improve query performance on JSONB data.  
Without indexes, PostgreSQL must scan every row and parse the JSON to evaluate query conditions.  
With proper indexes, PostgreSQL can quickly locate matching rows using B-tree lookups.

The repository supports two indexing approaches:
- **`@Indexed` annotation**: For top-level entity properties (created automatically on repository initialization)
- **`addIndex()` method**: For nested properties, composite indexes, or indexes added after initialization

### Using @Indexed Annotation

For top-level properties, use the `@Indexed` annotation:

```kotlin
@DocumentEntity("orders")
data class Order(
    @Id val orderId: OrderId,
    @Indexed var personName: String,  // Creates idx_orders_personname
    @Indexed var status: String,       // Creates idx_orders_status
    var description: String,           // Not indexed
    // ...
)
```

Index name pattern: `idx_${tableName}_${propertyName}` (lowercase)

### Programmatic Index Creation

For nested properties or composite indexes, use `addIndex()`:

```kotlin
val orderRepository = repositoryFactory.create(Order::class)

// Single nested property index
orderRepository.addIndex(Index(
    name = "city",
    properties = listOf(Order::contactDetails then ContactDetails::address then Address::city)
))
// Creates: idx_orders_city

// Composite index on multiple properties
orderRepository.addIndex(Index(
    name = "orderdate_amount",
    properties = listOf(Order::orderDate.asProperty(), Order::amount.asProperty())
))
// Creates: idx_orders_orderdate_amount
```

### Removing Indexes

```kotlin
orderRepository.removeIndex("city")
// or
orderRepository.removeIndex(index)
```

**Test reference:** `PostgresqlDocumentDbRepositoryIT.kt:88-89`

## Custom Repositories

While `DocumentDbRepository` provides all the basic operations, you'll often want to create domain-specific repositories that encapsulate common queries and business logic. This keeps your service layer clean and makes queries reusable.

The `DelegatingDocumentDbRepository` base class delegates all standard operations to the underlying repository while letting you add custom query methods and configure indexes.

### Basic Custom Repository

```kotlin
class OrderRepository(
    delegateTo: DocumentDbRepository<Order, OrderId>
) : DelegatingDocumentDbRepository<Order, OrderId>(delegateTo) {

    fun findByPersonName(name: String): List<Order> {
        return queryBuilder()
            .where(condition().matching { Order::personName eq name })
            .orderBy(Order::orderDate, QueryBuilder.Order.DESC)
            .find()
    }

    fun findByCity(city: String): List<Order> {
        return queryBuilder()
            .where(condition().matching {
                Order::contactDetails then ContactDetails::address then Address::city eq city
            })
            .find()
    }

    fun findLargeOrders(minimumAmount: Amount): List<Order> {
        return queryBuilder()
            .where(condition().matching { Order::amount gt minimumAmount })
            .find()
    }
}

// Usage
val repository = OrderRepository(repositoryFactory.create(Order::class))
val largeOrders = repository.findLargeOrders(Amount("1000.00"))
```

### Adding Indexes in Custom Repositories

For complex entities, define indexes in the repository's `init` block using `delegateTo.addIndex()`. This is especially useful for:
- **Composite indexes** spanning multiple properties
- **Nested property indexes** for frequently queried nested fields
- **Indexes on entities with composite IDs**

**Example: Document with Multiple Revisions**

This example demonstrates a common pattern where a logical document (`DocumentId`) can have multiple revisions (`DocumentRevision`).  
Each revision is stored as a separate `DocumentView` entity, identified by a composite ID combining both values.

> **Note:** `DocumentRevision` represents the business concept of document revisions, while `Version` tracks database-level updates to each entity.

```kotlin
// Semantic types for the composite ID
@JvmInline
value class DocumentId(override val value: String) : StringValueType<DocumentId>

@JvmInline
value class DocumentRevision(override val value: Long) : LongValueType<DocumentRevision>

/**
 * Composite ID combining DocumentId and DocumentRevision.
 * A single document can have many revisions, each stored as a separate DocumentView.
 */
data class DocumentIdAndRevision(
    val documentId: DocumentId,
    val revision: DocumentRevision
) {
    companion object {
        /** Serializes the composite ID to a string for database storage */
        val IdSerializer: IdSerializer<DocumentIdAndRevision> = {
            "${it.documentId.value}:${it.revision.value}"
        }
    }
}

/**
 * Represents a single revision of a document.
 * Multiple DocumentView entities can exist for the same DocumentId (one per revision).
 */
@DocumentEntity("documents_list")
data class DocumentView(
    @Id
    val id: DocumentIdAndRevision,         // Composite ID: documentId + revision
    @Indexed
    val documentId: DocumentId,            // Indexed for querying all revisions
    val revision: DocumentRevision,        // The revision number
    val attributes: DocumentAttributes,
    var registeredAt: OffsetDateTime,
    var uploadedAt: OffsetDateTime? = null,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<DocumentIdAndRevision, DocumentView>

data class DocumentAttributes(
    val category: String,
    val type: String,
    val metadata: Map<String, String>
)

@Repository
class DocumentsListRepository(
    documentDbRepositoryFactory: DocumentDbRepositoryFactory
) : DelegatingDocumentDbRepository<DocumentView, DocumentIdAndRevision>(
    documentDbRepositoryFactory.createForCompositeId(
        DocumentView::class,
        DocumentIdAndRevision.IdSerializer
    )
) {
    init {
        // Composite index for efficient lookup by documentId + revision
        delegateTo.addIndex(Index(
            "id_and_revision",
            listOf(
                DocumentView::documentId.asProperty(),
                DocumentView::revision.asProperty()
            )
        ))

        // Indexes on nested properties for filtering by category/type
        delegateTo.addIndex(Index(
            "attr_category",
            listOf(DocumentView::attributes then DocumentAttributes::category)
        ))
        delegateTo.addIndex(Index(
            "attr_type",
            listOf(DocumentView::attributes then DocumentAttributes::type)
        ))
    }

    /** Find all revisions of a document, sorted by revision number (newest first) */
    fun findAllRevisions(documentId: DocumentId): List<DocumentView> {
        return queryBuilder()
            .where(condition().matching { DocumentView::documentId eq documentId })
            .orderBy(DocumentView::revision, QueryBuilder.Order.DESC)
            .find()
    }

    /** Find a specific revision of a document */
    fun findByDocumentIdAndRevision(
        documentId: DocumentId,
        revision: DocumentRevision
    ): DocumentView? {
        return queryBuilder()
            .where(condition().matching {
                (DocumentView::documentId eq documentId)
                    .and(DocumentView::revision eq revision)
            })
            .find()
            .firstOrNull()
    }

    /** Find all documents with a category */
    fun findByCategory(category: String): List<DocumentView> {
        return queryBuilder()
            .where(condition().matching {
                DocumentView::attributes then DocumentAttributes::category eq category
            })
            .find()
    }
}
```

**Key points:**
- Use `delegateTo.addIndex()` in the `init` block to ensure indexes are created when the repository is instantiated
- Use `.asProperty()` for top-level properties in composite indexes
- Use `then` operator for nested property paths
- Combine `@Indexed` annotations for simple cases with programmatic indexes for complex cases
- Index properties you frequently query by (e.g., `documentId` for finding all revisions)

**Test reference:** `PostgresqlDocumentDbRepositoryIT.kt:83`

## Optimistic Locking

### Why

Optimistic locking prevents lost updates when multiple processes modify the same entity concurrently.

### How It Works

1. Each entity has a `version` property (starts at `Version.ZERO` on save)
2. On update, the repository checks: `WHERE id = :id AND version = :loadedVersion`
3. If the version doesn't match, `OptimisticLockingException` is thrown
4. On successful update, version is incremented

### Handling Conflicts

```kotlin
val order = orderRepository.findById(orderId)!!

try {
    order.description = "Updated by process A"
    orderRepository.update(order)
} catch (e: OptimisticLockingException) {
    // Another process updated the entity - reload and retry
    val freshOrder = orderRepository.findById(orderId)!!
    freshOrder.description = "Updated by process A (retry)"
    orderRepository.update(freshOrder)
}
```

### Version States

| Value | Meaning |
|-------|---------|
| `Version.NOT_SAVED_YET` (-1) | Entity has never been saved |
| `Version.ZERO` (0) | Entity was just saved |
| `Version(n)` where n > 0 | Entity has been updated n times |

**Test reference:** `PostgresqlDocumentDbRepositoryIT.kt:181-197`
