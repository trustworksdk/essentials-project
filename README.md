```
 ╔════════════════════════════════════════════════════════════════════════════════════════╗
 ║                                                                                        ║
 ║     ███████╗███████╗███████╗███████╗███╗   ██╗████████╗██╗ █████╗ ██╗     ███████╗     ║
 ║     ██╔════╝██╔════╝██╔════╝██╔════╝████╗  ██║╚══██╔══╝██║██╔══██╗██║     ██╔════╝     ║
 ║     █████╗  ███████╗███████╗█████╗  ██╔██╗ ██║   ██║   ██║███████║██║     ███████╗     ║
 ║     ██╔══╝  ╚════██║╚════██║██╔══╝  ██║╚██╗██║   ██║   ██║██╔══██║██║     ╚════██║     ║
 ║     ███████╗███████║███████║███████╗██║ ╚████║   ██║   ██║██║  ██║███████╗███████║     ║
 ║     ╚══════╝╚══════╝╚══════╝╚══════╝╚═╝  ╚═══╝   ╚═╝   ╚═╝╚═╝  ╚═╝╚══════╝╚══════╝     ║
 ║                                                                                        ║
 ║                     Java 17+ Building Blocks for Strongly-Typed Code                   ║
 ║                                                                                        ║
 ╚════════════════════════════════════════════════════════════════════════════════════════╝
```

> High-level, strongly-typed building blocks for Java 17+ applications—framework-independent core with seamless integrations

📖 **LLM Context:** [LLM.md](LLM/LLM.md)

> **NOTE:** This library is WORK-IN-PROGRESS

---

## Table of Contents

- [What is Essentials?](#what-is-essentials)
- [License](#license)
- [Why Choose Essentials?](#why-choose-essentials)
- [When Should You Use This?](#when-should-you-use-this)
- [Quick Start](#quick-start)
- [Module Overview](#module-overview)
  - [Core Modules](#core-modules)
  - [Type Integrations](#type-integrations)
  - [Advanced Components](#advanced-components)
- [Getting Started](#getting-started)
- [Version Compatibility](#version-compatibility)
- ⚠️ [Security](#security)
- [Testing](#testing)
- [Resources](#resources)


---

## What is Essentials?

Essentials is a set of Java 17+ building blocks designed to help you write **strongly-typed, self-documenting code** without framework lock-in.

**Core Modules:** Zero-dependency utilities providing **semantic types**, immutable value objects, functional primitives, and reactive patterns.

**Integration Modules:** Seamless support for Jackson, Spring Boot, Spring Data, JPA, JDBI, Avro, and more.

**Advanced Components:** Production-ready infrastructure patterns (`Event Store`, `Event Sourcing`, `Distributed Locks`, `Durable Queues`, `Inbox`/`Outbox`) using your existing PostgreSQL or MongoDB database.

**Choose your path:**
- Use **core modules** for strongly-typed domain modeling in any Java application using **semantic types** (think `OrderId` instead of `String`)
- Add **integration modules** for framework-specific serialization and persistence of **semantic types**
- Use **components** for distributed systems requiring event sourcing, queues, and coordination

---
## License

Essentials is released under version 2.0 of the [Apache License](https://www.apache.org/licenses/LICENSE-2.0).

--- 

## Why Choose Essentials?

### ✅ Eliminate Primitive Obsession

Stop passing `String orderId, String customerId` and hoping you get the order right. Semantic types make code self-documenting and catch errors at compile time:

```java
// Before: What are these strings? Easy to mix up!
public void processOrder(String orderId, String customerId, BigDecimal amount) { ... }
processOrder(customerId, orderId, amount);  // Compiles, but wrong!

// After: Compiler catches mistakes
public void processOrder(OrderId orderId, CustomerId customerId, Amount amount) { ... }
processOrder(customerId, orderId, amount);  // Compile error!
```

Creating semantic types takes just 4 lines:

```java
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }
    public static OrderId of(CharSequence value) { return new OrderId(value); }
}
```

### ✅ Zero-Dependency Core

Core modules (`shared`, `types`, `immutable`, `reactive`) have **no runtime dependencies** (except SLF4J as `provided`). Your application controls its dependency tree.

### ✅ Framework-Independent with Easy Integrations

Write your domain logic once. Integrate with your preferred frameworks through dedicated modules:

| Framework | Module | Purpose                                                      |
|-----------|--------|--------------------------------------------------------------|
| Jackson 3 | `types-jackson3` | JSON serialization for **Semantic Types** (Jackson 3 — the default, matching Spring Boot 4) |
| Jackson 2 | `types-jackson` | JSON serialization for **Semantic Types** (Jackson 2) |
| Spring Data MongoDB | `types-springdata-mongo` | MongoDB persistence for **Semantic Types**                    |
| Spring Data JPA | `types-springdata-jpa` | JPA persistence for **Semantic Types**                        |
| JDBI v3 | `types-jdbi` | `Jdbi` SQL argument and result mapping for **Semantic Types** |
| Apache Avro | `types-avro` | Binary serialization for **Semantic Types**                                        |
| Spring WebMvc/WebFlux | `types-spring-web` | Path/request parameter conversion for **Semantic Types**         |

### ✅ Provided Scope for Third-Party Dependencies

Integration modules use Maven `provided` scope—dependencies are **NOT transitive**.  
You control which versions of Jackson, Spring, and other frameworks your application uses!

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson3</artifactId>
    <version>${essentials.version}</version>
</dependency>
<!-- You add Jackson yourself at your preferred version -->
<dependency>
    <groupId>tools.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>${jackson.version}</version>
</dependency>
```

### ✅ Choosing the Jackson Major

`types-jackson3`/`immutable-jackson3` (Jackson 3, group `tools.jackson.core`) are the default, matching Spring Boot 4.
`types-jackson`/`immutable-jackson` (Jackson 2, group `com.fasterxml.jackson.core`) remain supported.

Both publish the **same class names**, so exactly one may be on the classpath. The components modules pull the Jackson 3
flavour transitively; to stay on Jackson 2, exclude it and declare the Jackson 2 flavour yourself:

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>postgresql-event-store</artifactId>
    <version>${essentials.version}</version>
    <exclusions>
        <exclusion>
            <groupId>dk.trustworks.essentials</groupId>
            <artifactId>types-jackson3</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

Both majors write **byte-identical JSON**, so data persisted by a Jackson 2 deployment stays readable after moving to
Jackson 3 — that equivalence is enforced by golden wire-format tests that run under both flavours. Build every mapper
used for persistence through `EssentialsObjectMappers`; a hand-assembled one drifts from that contract.

#### Two things to check in your own event/payload classes when moving to Jackson 3

Both are silent — the JSON keeps being *written* correctly and only fails on the way back in.

1. **Constructor parameter names now matter.** Jackson 3 reads them from the bytecode and treats any constructor as an
   implicit properties-based creator, even when a no-arg constructor exists; Jackson 2 populated fields instead. A
   parameter whose name differs from the JSON property it ends up in receives `null`, so the object fails its own
   validation or comes back half-populated:

   ```java
   // Breaks under Jackson 3: the parameter is "priceValidity", the persisted property is "priceValidityPeriod"
   public InitialPriceSet(ProductId forProduct, PriceId priceId, Money price, TimeWindow priceValidity) {
       super(forProduct, priceId, priceValidity);   // assigns this.priceValidityPeriod
   }
   ```

   Rename the parameter to match the property, or annotate it with `@JsonProperty("priceValidityPeriod")` — that
   annotation lives in `com.fasterxml.jackson.annotation`, which both majors share. The same applies to classic
   `Event<ID>` subclasses that take an `orderId` and pass it to `aggregateId(...)`: the property is `aggregateId`.

2. **`@JsonDeserialize(keyUsing = …)` stops applying.** It lives in Jackson 2's
   `com.fasterxml.jackson.databind.annotation` package, which Jackson 3 does not read. For maps keyed by an Essentials
   value type you no longer need it at all — `types-jackson3` handles those keys itself. For other key types, switch the
   import to `tools.jackson.databind.annotation`.

### ✅ Production-Ready Infrastructure Components

For distributed applications, Essentials Components provide:
- **Event Store** with subscriptions, processors, and optional Hybrid CDC (`pgoutput` default, `wal2json` optional) fallback-to-polling (PostgreSQL)
- **Event Sourcing** with `AggregateRoot`s, `Decider`, `Evolver`, `Repositories`, `Snapshot Repositories` (PostgreSQL)
- **Distributed Fenced Locks** for leader election and singleton workers
- **Durable Queues** with retry, DLQ, and ordered delivery
- **Inbox/Outbox** patterns for reliable messaging

All using your existing database—no Redis, Kafka, Axon-Server, or EventStoreDB required for intra-service coordination.

### Database & Technology Support

#### Foundation Components

| Database | Locks | Queues | Inbox/Outbox | Transaction Support    |
|----------|-------|--------|--------------|------------------------|
| **PostgreSQL** | ✅ | ✅ | ✅ | JDBI + Spring JDBC/JPA |
| **MongoDB** | ✅ | ✅ | ✅ | Spring Data MongoDB    |

#### Event-Driven Components (PostgreSQL Only)

| Database | Event Store | Subscriptions | Event Processor | Aggregates |
|----------|-------------|---------------|-----------------|------------|
| **PostgreSQL** | ✅ Full-featured | ✅ | ✅ | ✅ |
| **MongoDB** | ❌ | ❌ | ❌ | ❌ |

---

## When Should You Use This?

### ✅ Good Fit

| Use Case | Recommended Modules                                                                                                           |
|----------|-------------------------------------------------------------------------------------------------------------------------------|
| **Strongly-typed domain models** | `types` + framework integrations (including Kotlin support)                                                                   |
| **Immutable value objects** | `immutable`, `immutable-jackson`                                                                                              |
| **Event-driven architecture** | `reactive` (`EventBus`, `CommandBus`)                                                                                         |
| **Event sourcing & CQRS** | Components: `postgresql-event-store`, `eventsourced-aggregates` and for Kotlin `kotlin-eventsourcing`, `kotlin-eventsourcing` |
| **Distributed coordination** | Components: `postgresql-distributed-fenced-lock`, `postgresql-queue`, `springdata-mongo-queue`, `springdata-mongo-distributed-fenced-lock` |
| **Reliable messaging** | Components: `foundation` (`Inbox`/`Outbox`)                                                                                       |

### ❌ Not a Good Fit

- **Cross-service messaging** at scale—use Kafka, RabbitMQ
- **Cross-service distributed locks**—use Zookeeper, etcd
- **Extreme throughput requirements**—millions of events/second need specialized stores

---

## Quick Start

### Path 1: Strongly-Typed Domain Models

**1. Add dependencies:**
```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types</artifactId>
    <version>${essentials.version}</version>
</dependency>
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>types-jackson</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**2. Create semantic types:**
```java
public class OrderId extends CharSequenceType<OrderId> implements Identifier {
    public OrderId(CharSequence value) { super(value); }
    public OrderId(String value) { super(value); }
    public static OrderId of(CharSequence value) { return new OrderId(value); }
    public static OrderId random() { return new OrderId(RandomIdGenerator.generate()); }
}

public class Quantity extends IntegerType<Quantity> {
    public Quantity(Integer value) {
        super(value);
        FailFast.requireTrue(value >= 0, "Quantity cannot be negative");
    }
    public static Quantity of(int value) { return new Quantity(value); }
}
```

**3. Use in your domain:**
```java
public class CreateOrder {
    public final OrderId                  id;
    public final Amount                   totalAmount;
    public final CurrencyCode             currency;
    public final Percentage               salesTax;
    public final Map<ProductId, Quantity> orderLines;
}
```

**4. Configure Jackson:**
```java
objectMapper.registerModule(new EssentialTypesJacksonModule());
```

### Path 2: Event-Driven Components (Spring Boot)

For distributed applications with event sourcing, queues, and locks:

**1. Add Spring Boot starter:**
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-postgresql-event-store</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**2. Configure database:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

**3. Inject auto-configured components:**
```java
@Service
public class OrderService {
    private final EventStore eventStore;
    private final DurableQueues queues;
    private final FencedLockManager locks;

    // All auto-configured by Spring Boot—just inject and use
}
```

See [Essentials Components](components/README.md) for complete documentation.

---

## Module Overview

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                   ESSENTIALS MODULE OVERVIEW                                    │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ SPRING BOOT AUTO-CONFIGURATION                                                                  │
├─────────────────────────────────────────────────────────────────────────────────────────────────┤
│  spring-boot-starter-postgresql          spring-boot-starter-postgresql-event-store             │
│  spring-boot-starter-mongodb             spring-boot-starter-admin-api                          │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                   │
                                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ COMPONENTS - PostgreSQL                                                                         │
├─────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────┐  ┌──────────────────────────┐  ┌──────────────────────────┐       │
│  │ postgresql-event-store   │  │ eventsourced-aggregates  │  │ kotlin-eventsourcing     │       │
│  │ (EventStore, Subs, Proc) │  │ (AggregateRoot, Decider) │  │ (Kotlin DSL)             │       │
│  └──────────────────────────┘  └──────────────────────────┘  └──────────────────────────┘       │
│  ┌──────────────────────────┐  ┌──────────────────────────┐  ┌──────────────────────────┐       │
│  │postgresql-distributed-   │  │ postgresql-queue         │  │ postgresql-document-db   │       │
│  │fenced-lock               │  │ (DurableQueues+DLQ)      │  │ (Kotlin DocumentDB)      │       │
│  └──────────────────────────┘  └──────────────────────────┘  └──────────────────────────┘       │
│  ┌──────────────────────────┐                                                                   │
│  │spring-postgresql-event-  │                                                                   │
│  │store (Spring Tx)         │                                                                   │
│  └──────────────────────────┘                                                                   │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ COMPONENTS - MongoDB                                                                            │
├─────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────┐  ┌────────────────────────────────────┐                 │
│  │ springdata-mongo-distributed-      │  │ springdata-mongo-queue             │                 │
│  │ fenced-lock                        │  │ (DurableQueues+DLQ)                │                 │
│  └────────────────────────────────────┘  └────────────────────────────────────┘                 │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ COMPONENTS - Foundation & Admin API                                                             │
├─────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐         │
│  │ foundation-types │  │ foundation       │  │ foundation-test  │  │ admin-api-spec   │         │
│  │ (Common Types)   │  │ (UnitOfWork,     │  │ (Test Utils)     │  │ (Admin contract) │         │
│  │                  │  │  Inbox/Outbox)   │  │                  │  │                  │         │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘  └──────────────────┘         │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                   │
                                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ TYPE INTEGRATIONS (provided scope - framework-specific)                                         │
├─────────────────────────────────────────────────────────────────────────────────────────────────┤
│  types-jackson          types-springdata-mongo    types-springdata-jpa    types-jdbi            │
│  types-avro             types-spring-web          immutable-jackson                             │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                   │
                                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ CORE MODULES (zero dependencies except SLF4J as provided)                                       │
├─────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                         │
│  │ shared       │  │ types        │  │ immutable    │  │ reactive     │                         │
│  │ (Tuples,     │  │ (Semantic    │  │ (Immutable   │  │ (EventBus,   │                         │
│  │  Reflection, │  │  Types)      │  │  Value       │  │  CommandBus) │                         │
│  │  Validation) │  │              │  │  Objects)    │  │              │                         │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘                         │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Modules

Zero-dependency building blocks:

| Module | Purpose | Documentation |
|--------|---------|---------------|
| [shared](shared/README.md) | Tuples, collections, reflection, validation, exceptions, message formatting | [LLM](LLM/LLM-shared.md) |
| [types](types/README.md) | Semantic types via `SingleValueType` (OrderId, Amount, Percentage, etc.) | [LLM](LLM/LLM-types.md) |
| [immutable](immutable/README.md) | Immutable value objects with auto-generated `equals`/`hashCode`/`toString` | [LLM](LLM/LLM-immutable.md) |
| [reactive](reactive/README.md) | LocalEventBus, LocalCommandBus for in-memory event-driven patterns | [LLM](LLM/LLM-reactive.md) |

**Core Module Dependencies:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        CORE MODULE DEPENDENCY GRAPH                         │
└─────────────────────────────────────────────────────────────────────────────┘

                           ┌──────────────────────┐
                           │                      │
                           │      reactive        │
                           │                      │
                           │  • LocalEventBus     │
                           │  • LocalCommandBus   │
                           │  • Interceptors      │
                           │                      │
                           └──────────┬───────────┘
                                      │
                                      │ depends on
                                      │
                                      ▼
     ┌────────────────────┐      ┌──────────────────────┐
     │                    │      │                      │
     │     immutable      │      │        types         │
     │                    │      │                      │
     │  • ValueObject     │      │  • SingleValueType   │
     │  • @Exclude        │      │  • Amount, %         │
     │    annotations     │      │  • CurrencyCode      │
     │                    │      │  • Identifiers       │
     │                    │      │                      │
     └────────────────────┘      └───────────┬──────────┘
              │                              │
              │                              │ depends on
              │                              │
              │                              ▼
              │                 ┌──────────────────────────────────┐
              │                 │                                  │
              └────────────────▶│           shared                 │
                                │                                  │
                                │  • Tuples (Pair, Triple)         │
                                │  • FailFast validation           │
                                │  • Reflection (Reflector)        │
                                │  • Collections utilities         │
                                │  • MessageFormatter              │
                                │  • Checked* interfaces           │
                                │  • InterceptorChain              │
                                │  • PatternMatchingInvoker        │
                                │                                  │
                                │  Zero dependencies               │
                                │  (except SLF4J as provided)      │
                                │                                  │
                                └──────────────────────────────────┘

Legend:
  ▼  = depends on
  Module dependencies are managed via Maven with zero transitive runtime dependencies
```

### Type Integrations

Type-Integration [LLM Documentation](LLM/LLM-types-integrations.md)

Framework-specific support for semantic types (all use `provided` scope):

| Module | Framework | Purpose | LLM Documentation                        |
|--------|-----------|---------|------------------------------------------|
| [types-jackson](types-jackson/README.md) | Jackson | JSON serialization/deserialization | [LLM](LLM/LLM-types-jackson.md)          |
| [types-springdata-mongo](types-springdata-mongo/README.md) | Spring Data MongoDB | MongoDB persistence + ID generation | [LLM](LLM/LLM-types-springdata-mongo.md) |
| [types-springdata-jpa](types-springdata-jpa/README.md) | Spring Data JPA | JPA persistence (experimental) | [LLM](LLM/LLM-types-springdata-jpa.md)   |
| [types-jdbi](types-jdbi/README.md) | JDBI v3 | SQL argument/column mapping | [LLM](LLM/LLM-types-jdbi.md)             |
| [types-avro](types-avro/README.md) | Apache Avro | Binary serialization with logical types | [LLM](LLM/LLM-types-avro.md)             |
| [types-spring-web](types-spring-web/README.md) | Spring WebMvc/WebFlux | `@PathVariable`/`@RequestParam` conversion | [LLM](LLM/LLM-types-spring-web.md)       |
| [immutable-jackson](immutable-jackson/README.md) | Jackson | Deserialization for immutable classes | [LLM](LLM/LLM-immutable-jackson.md)      |

### Advanced Components

Production-ready infrastructure patterns. See [Essentials Components](components/README.md) for complete documentation.  
Components [LLM Documentation](LLM/LLM-components.md)

**Spring Boot Starters:**

| Starter | What It Provides | LLM Documentation |
|---------|------------------|-------------------|
| [spring-boot-starter-postgresql](components/spring-boot-starter-postgresql/README.md) | UnitOfWork, FencedLocks, DurableQueues, Inbox/Outbox, CommandBus | [LLM](LLM/LLM-spring-boot-starter-modules.md) |
| [spring-boot-starter-postgresql-event-store](components/spring-boot-starter-postgresql-event-store/README.md) | All above + EventStore, Subscriptions, EventProcessors, Aggregates | [LLM](LLM/LLM-spring-boot-starter-modules.md) |
| [spring-boot-starter-mongodb](components/spring-boot-starter-mongodb/README.md) | MongoDB equivalents of foundation components | [LLM](LLM/LLM-spring-boot-starter-modules.md) |

**Component Modules:**

| Module | Purpose                                                       | LLM Documentation |
|--------|---------------------------------------------------------------|-------------------|
| [postgresql-event-store](components/postgresql-event-store/README.md) | Full-featured Event Store with subscriptions, gap handling, and optional Hybrid CDC (`pgoutput` default, `wal2json` optional) | [LLM](LLM/LLM-postgresql-event-store.md) |
| [eventsourced-aggregates](components/eventsourced-aggregates/README.md) | AggregateRoot, Decider, Evolver patterns for DDD              | [LLM](LLM/LLM-eventsourced-aggregates.md) |
| [postgresql-distributed-fenced-lock](components/postgresql-distributed-fenced-lock/README.md) | PostgreSQL distributed locking with fence tokens              | [LLM](LLM/LLM-postgresql-distributed-fenced-lock.md) |
| [postgresql-queue](components/postgresql-queue/README.md) | PostgreSQL durable message queues with retry and DLQ          | [LLM](LLM/LLM-postgresql-queue.md) |
| [springdata-mongo-distributed-fenced-lock](components/springdata-mongo-distributed-fenced-lock/README.md) | MongoDB distributed locking with fence tokens                 | [LLM](LLM/LLM-springdata-mongo-distributed-fenced-lock.md) |
| [springdata-mongo-queue](components/springdata-mongo-queue/README.md) | MongoDB durable message queues with retry and DLQ             | [LLM](LLM/LLM-springdata-mongo-queue.md) |
| [postgresql-document-db](components/postgresql-document-db/README.md) | Kotlin Document database using PostgreSQL JSONB               | [LLM](LLM/LLM-postgresql-document-db.md) |
| [kotlin-eventsourcing](components/kotlin-eventsourcing/README.md) | Kotlin DSL for functional event sourcing                      | [LLM](LLM/LLM-kotlin-eventsourcing.md) |

**Event Sourcing Stack Dependencies:**

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                         EVENT SOURCING COMPONENT DEPENDENCIES                        │
└──────────────────────────────────────────────────────────────────────────────────────┘

                        ┌────────────────────────────────────┐
                        │                                    │
                        │  spring-boot-starter-postgresql-   │
                        │  event-store                       │
                        │                                    │
                        │  Auto-configures entire stack:     │
                        │  • EventStore + Repositories       │
                        │  • Subscriptions + Processors      │
                        │  • UnitOfWork + Foundation         │
                        │                                    │
                        └─────────────┬──────────────────────┘
                                      │
                                      │ auto-configures
                                      │
                ┌─────────────────────┼─────────────────────┐
                │                     │                     │
                ▼                     ▼                     ▼
┌──────────────────────────┐  ┌──────────────────────┐  ┌──────────────────────────┐
│                          │  │                      │  │                          │
│  eventsourced-aggregates │  │ kotlin-eventsourcing │  │ spring-postgresql-       │
│                          │  │      (optional)      │  │ event-store              │
│  • AggregateRoot         │  │  • Kotlin DSL        │  │                          │
│  • StatefulAggregate     │  │  • Decider           │  │  • Spring Tx support     │
│  • Decider pattern       │  │  • EventEvolver      │  │  • @Transactional        │
│  • EventStreamDecider    │  │  • Functional style  │  │    integration           │
│  • Repositories          │  │                      │  │                          │
│  • Snapshot support      │  │                      │  │                          │
│                          │  │                      │  │                          │
└────────────┬─────────────┘  └──────────┬───────────┘  └────────────┬─────────────┘
             │                           │                           │
             │                           │                           │
             └───────────────────────────┼───────────────────────────┘
                                         │
                                         │ depends on
                                         │
                                         ▼
                         ┌───────────────────────────────────┐
                         │                                   │
                         │   postgresql-event-store          │
                         │                                   │
                         │   Core Event Store:               │
                         │   • ConfigurableEventStore        │
                         │   • Event persistence             │
                         │   • Event streaming               │
                         │   • Subscriptions (with gaps)     │
                         │   • GlobalEventOrder tracking     │
                         │   • EventProcessor framework      │
                         │   • Multitenancy support          │
                         │   • Interceptors                  │
                         │                                   │
                         └─────────────┬─────────────────────┘
                                       │
                                       │ depends on
                                       │
                     ┌─────────────────┼─────────────────┐
                     │                 │                 │
                     ▼                 ▼                 ▼
         ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
         │                  │  │                  │  │                  │
         │ foundation-      │  │  foundation      │  │  postgresql-     │
         │ types            │  │                  │  │  queue           │
         │                  │  │  • UnitOfWork    │  │                  │
         │ • EventId        │  │  • Inbox/Outbox  │  │  • DurableQueues │
         │ • EventOrder     │  │  • FencedLock    │  │  • Used by       │
         │ • Aggregate      │  │  • DurableQueues │  │    EventProc     │
         │   Type           │  │  • CommandBus    │  │                  │
         │ • Correlation    │  │                  │  │                  │
         │   Id             │  │                  │  │                  │
         │                  │  │                  │  │                  │
         └────────┬─────────┘  └────────┬─────────┘  └──────────────────┘
                  │                     │
                  │                     │
                  │                     └──────────────────────┐
                  │                                            │
                  │ depends on                                 │
                  │                                            │
                  ▼                                            ▼
      ┌──────────────────┐                          ┌──────────────────┐
      │                  │                          │                  │
      │  types           │                          │  reactive        │
      │                  │                          │                  │
      │  • Semantic      │                          │  • EventBus      │
      │    types         │                          │  • CommandBus    │
      │                  │                          │  • Interceptors  │
      │                  │                          │                  │
      └────────┬─────────┘                          └────────┬─────────┘
               │                                             │
               │                                             │
               └───────────────────────────────┬─────────────┘
                                               │
                                               │ depends on
                                               │
                                               ▼
                                   ┌──────────────────────┐
                                   │                      │
                                   │      shared          │
                                   │                      │
                                   │  • Tuples            │
                                   │  • Reflection        │
                                   │  • MessageFormatter  │
                                   │  • InterceptorChain  │
                                   │                      │
                                   └──────────────────────┘

Note: PostgreSQL JDBC driver, JDBI v3, Jackson and Project Reactor are required runtime dependencies
```

---

## Getting Started

### Example 1: Semantic Types with Jackson

```java
// 1. Define types
public class CustomerId extends CharSequenceType<CustomerId> implements Identifier {
    public CustomerId(CharSequence value) { super(value); }
    public CustomerId(String value) { super(value); }
    public static CustomerId of(CharSequence value) { return new CustomerId(value); }
}

// 2. Use in commands/events
public record CreateOrder(
    OrderId orderId,
    CustomerId customerId,
    Amount totalAmount,
    CurrencyCode currency
) {}

// 3. Configure Jackson
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new EssentialTypesJacksonModule());

// 4. Serialize/deserialize automatically
String json = mapper.writeValueAsString(new CreateOrder(
    OrderId.of("ORD-123"),
    CustomerId.of("CUST-456"),
    Amount.of("199.99"),
    CurrencyCode.USD
));
// {"orderId":"ORD-123","customerId":"CUST-456","totalAmount":"199.99","currency":"USD"}
```

### Example 2: LocalEventBus for Event-Driven Architecture

```java
// Create event bus
LocalEventBus eventBus = LocalEventBus.builder()
    .busName("OrderEvents")
    .parallelThreads(5)
    .onErrorHandler((subscriber, event, ex) -> log.error("Handler failed", ex))
    .build();

// Register handlers
eventBus.addSyncSubscriber(event -> {
    if (event instanceof OrderCreated created) {
        inventoryService.reserve(created.orderId());
    }
});

eventBus.addAsyncSubscriber(event -> {
    if (event instanceof OrderCreated created) {
        emailService.sendConfirmation(created.customerId());
    }
});

// Publish events
eventBus.publish(new OrderCreated(orderId, customerId));
```

### Example 3: LocalCommandBus for CQRS

```java
// Create command bus with handlers
LocalCommandBus commandBus = new LocalCommandBus();
commandBus.addCommandHandler(new OrderCommandHandler(orderRepository));

// Send commands
OrderId orderId = commandBus.send(new CreateOrder(customerId, items));

// Or async
Mono<OrderId> result = commandBus.sendAsync(new CreateOrder(customerId, items));
```

### Example 4: Immutable Value Objects

```java
public class ImmutableOrder extends ImmutableValueObject {
    public final OrderId                  orderId;
    public final CustomerId               customerId;
    @Exclude.EqualsAndHashCode  // Metadata, not part of identity
    public final Instant                  createdAt;
    @Exclude.ToString           // Don't log sensitive data
    public final Money                    totalPrice;

    public ImmutableOrder(OrderId orderId, CustomerId customerId,
                          Instant createdAt, Money totalPrice) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.createdAt = createdAt;
        this.totalPrice = totalPrice;
    }
}

// Auto-generated equals/hashCode/toString based on non-excluded fields
```

---

## Version Compatibility

| Essentials Version | Java | Spring Boot | Notes |
|--------------------|------|-------------|-------|
| [0.40.24+](https://github.com/trustworksdk/essentials-project/tree/main) | 17+ | 3.3.x | Under active development |

### Migration Note

> [Cloud Create](https://github.com/cloudcreate-dk) originally developed the [Essentials project](https://github.com/cloudcreate-dk/essentials-project).
> As of May 8th 2025, [Trustworks](https://www.trustworks.dk) has assumed responsibility for further development.
>
> **Compatibility:** Trustworks' Essentials release version **0.40.24** remains API and functionally compatible with Cloud Create's version **0.40.24** (released May 5th 2025). Migration requires only updating module names and package references from `dk.cloudcreate` to `dk.trustworks`.

---

## Security

Several components allow customization of:
- Table names, column names, function names, index names (PostgreSQL)
- Collection names (MongoDB)
- Configuration values

Essentials applies naming convention validation as an initial defense layer. **However, this is NOT exhaustive protection against SQL/NoSQL injection.**

### ⚠️ Critical: SQL/NoSQL Injection Risk

> **Your Responsibility:** Users MUST sanitize and validate all:
> - API input parameters
> - SQL table/column/index names
> - MongoDB collection names
> - Configuration values derived from external sources

**Safe Pattern:**
```java
// ❌ DANGEROUS - user input enables injection
var tableName = userInput + "_events";

// ✅ SAFE - controlled, predefined names
var tableName = "order_events";

// ⚠️ VALIDATE if from config
PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
MongoUtil.checkIsValidCollectionName(collectionName);
```

**Insufficient attention to these practices may leave your application vulnerable to attacks.**

### What Validation Does NOT Protect Against

#### PostgreSQL
- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

#### MongoDB
- NoSQL injection via **values** (use Spring Data MongoDB's type-safe query methods)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- Query operator injection (e.g., `$where`, `$regex`, `$ne`)

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

### Module-Specific Security Guidance

See individual module documentation for detailed security considerations:
- [Components README](components/README.md#security)
- [foundation-types](components/foundation-types/README.md)
- [postgresql-event-store](components/postgresql-event-store/README.md)
- [postgresql-distributed-fenced-lock](components/postgresql-distributed-fenced-lock/README.md)
- [postgresql-queue](components/postgresql-queue/README.md)
- [eventsourced-aggregates](components/eventsourced-aggregates/README.md)
- [kotlin-eventsourcing](components/kotlin-eventsourcing/README.md)
- [springdata-mongo-queue](components/springdata-mongo-queue/README.md)
- [springdata-mongo-distributed-fenced-lock](components/springdata-mongo-distributed-fenced-lock/README.md)

---

## Testing

### Run All Tests

```bash
# All unit tests
mvn test

# All unit + integration tests
mvn verify
```

### Run Specific Module Tests

```bash
# Single module
mvn test -pl <module-name> -am

# Example: types module
mvn test -pl types -am

# Integration tests for a module
mvn verify -pl <module-name> -am
```

### Build Commands

```bash
# Full build
mvn clean install

# Fast build (skip dependency check)
mvn clean install -DskipDependencyCheck=true

# Build specific module with dependencies
mvn clean install -pl components/postgresql-event-store -am

# Simulated release build (test-release profile)
mvn clean install -P test-release
```

### Test Requirements

- **PostgreSQL modules**: Requires Docker for TestContainers
- **MongoDB modules**: Requires Docker for TestContainers (replica set mode)

---

## Resources

- **GitHub:** [https://github.com/trustworksdk/essentials-project](https://github.com/trustworksdk/essentials-project)
- **Components Documentation:** [components/README.md](components/README.md)
- **LLM Reference:** [LLM/LLM.md](LLM/LLM.md)
- **Each module contains detailed README.md** with usage examples
