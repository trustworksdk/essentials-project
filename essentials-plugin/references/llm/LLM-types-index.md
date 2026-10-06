# Essentials Types & Components Index

> Quick type/component lookup organized by use case. For implementation details, see linked module docs.

**Note**: This is an **index document** - a quick lookup table mapping types to their source modules. See individual module docs for detailed APIs and dependencies.

## Quick Lookup (Alphabetical)

| Type/Interface | Category | Module |
|----------------|----------|--------|
| `AggregateEventStream<ID>` | Event Store | postgresql-event-store |
| `AggregateRoot<ID,EVENT,SELF>` | Aggregates | eventsourced-aggregates |
| `AggregateType` | Event Store | foundation-types |
| `AggregateTypeConfiguration` (Kotlin) | Kotlin Eventsourcing | kotlin-eventsourcing |
| `Amount` | Money | types |
| `AnnotatedCommandHandler` | Buses | reactive |
| `AnnotatedEventHandler` | Buses | reactive |
| `BigDecimalType<T>` | Base Types | types |
| `BigDecimalValueType<SELF>` | Kotlin Types | types |
| `BigIntegerValueType<SELF>` | Kotlin Types | types |
| `BooleanType<T>` | Base Types | types |
| `BooleanValueType<SELF>` | Kotlin Types | types |
| `ByteType<T>` | Base Types | types |
| `ByteValueType<SELF>` | Kotlin Types | types |
| `CharSequenceType<T>` | Base Types | types |
| `CommandBus` | Buses | reactive |
| `Condition<E>` | Document DB | postgresql-document-db |
| `CorrelationId` | Identifiers | foundation-types |
| `CountryCode` | Validation | types |
| `CurrencyCode` | Money | types |
| `Decider<CMD,EVENT,ERROR,STATE>` | Aggregates | eventsourced-aggregates |
| `Decider<COMMAND,EVENT>` (Kotlin) | Kotlin Eventsourcing | kotlin-eventsourcing |
| `DelegatingDocumentDbRepository<E,ID>` | Document DB | postgresql-document-db |
| `@DocumentEntity` | Document DB | postgresql-document-db |
| `DocumentDbRepository<E,ID>` | Document DB | postgresql-document-db |
| `DocumentDbRepositoryFactory` | Document DB | postgresql-document-db |
| `DoubleType<T>` | Base Types | types |
| `DoubleValueType<SELF>` | Kotlin Types | types |
| `DurableLocalCommandBus` | Buses | foundation |
| `DurableQueueConsumer` | Messaging | foundation |
| `DurableQueues` | Messaging | foundation |
| `Either<T1,T2>` | Utilities | shared |
| `EmailAddress` | Validation | types |
| `Empty` | Utilities | shared |
| `EventBus` | Buses | reactive |
| `EventId` | Identifiers | foundation-types |
| `EventName` | Event Store | foundation-types |
| `EventOrder` | Event Store | foundation-types |
| `EventProcessor` | Event Processing | postgresql-event-store |
| `EventRevision` | Event Store | foundation-types |
| `EventStore` | Event Store | postgresql-event-store |
| `EventStreamDecider<CMD,EVENT>` | Aggregates | eventsourced-aggregates |
| `EventStreamEvolver<EVENT,STATE>` | Aggregates | eventsourced-aggregates |
| `EventType` | Event Store | foundation-types |
| `Evolver<EVENT,STATE>` (Kotlin) | Kotlin Eventsourcing | kotlin-eventsourcing |
| `EventTypeOrName` | Event Store | foundation-types |
| `FencedLock` | Coordination | foundation |
| `FencedLockManager` | Coordination | foundation |
| `FlexAggregate<ID,SELF>` | Aggregates | eventsourced-aggregates |
| `FlexAggregateRepository<ID,AGGREGATE>` | Aggregates | eventsourced-aggregates |
| `FloatType<T>` | Base Types | types |
| `FloatValueType<SELF>` | Kotlin Types | types |
| `GivenWhenThenScenario` (Java) | Aggregates | eventsourced-aggregates |
| `GivenWhenThenScenario` (Kotlin) | Kotlin Eventsourcing | kotlin-eventsourcing |
| `GlobalEventOrder` | Event Store | foundation-types |
| `Identifier` | Identifiers | types |
| `@Id` | Document DB | postgresql-document-db |
| `Index<E>` | Document DB | postgresql-document-db |
| `@Indexed` | Document DB | postgresql-document-db |
| `Inbox` | Messaging | foundation |
| `InstantType<T>` | Temporal | types |
| `InstantValueType<SELF>` | Kotlin Types | types |
| `IntegerType<T>` | Base Types | types |
| `IntValueType<SELF>` | Kotlin Types | types |
| `InTransactionEventProcessor` | Event Processing | postgresql-event-store |
| `Lifecycle` | Utilities | shared |
| `LocalCommandBus` | Buses | reactive |
| `LocalDateTimeType<T>` | Temporal | types |
| `LocalDateTimeValueType<SELF>` | Kotlin Types | types |
| `LocalDateType<T>` | Temporal | types |
| `LocalDateValueType<SELF>` | Kotlin Types | types |
| `LocalEventBus` | Buses | reactive |
| `LocalTimeType<T>` | Temporal | types |
| `LocalTimeValueType<SELF>` | Kotlin Types | types |
| `LongRange` | Utilities | types |
| `LongType<T>` | Base Types | types |
| `LongValueType<SELF>` | Kotlin Types | types |
| `MessageId` | Identifiers | foundation-types |
| `Money` | Money | types |
| `MongoFencedLockManager` | Coordination | springdata-mongo-distributed-fenced-lock |
| `MongoDurableQueues` | Messaging | springdata-mongo-queue |
| `NumberType<N,T>` | Base Types | types |
| `OffsetDateTimeType<T>` | Temporal | types |
| `OptimisticLockingException` | Document DB | postgresql-document-db |
| `OffsetDateTimeValueType<SELF>` | Kotlin Types | types |
| `OrderedMessage` | Messaging | foundation |
| `Outbox` | Messaging | foundation |
| `Pair<T1,T2>` | Utilities | shared |
| `Percentage` | Money | types |
| `Quad<T1,T2,T3,T4>` | Utilities | shared |
| `QueryBuilder<ID,E>` | Document DB | postgresql-document-db |
| `PersistedEvent` | Event Store | postgresql-event-store |
| `PostgresqlDurableQueues` | Messaging | postgresql-queue |
| `PostgresqlEventStore` | Event Store | postgresql-event-store |
| `PostgresqlFencedLockManager` | Coordination | postgresql-distributed-fenced-lock |
| `QueueName` | Messaging | foundation |
| `RedeliveryPolicy` | Messaging | foundation |
| `Result<ERROR,SUCCESS>` | Utilities | shared |
| `@Revision` | Event Store | foundation-types |
| `ShortType<T>` | Base Types | types |
| `ShortValueType<SELF>` | Kotlin Types | types |
| `Single<T>` | Utilities | shared |
| `SingleValueType<V,T>` | Base Types | types |
| `StringValueType<SELF>` | Kotlin Types | types |
| `StatefulAggregateRepository` | Aggregates | eventsourced-aggregates |
| `SubscriberId` | Identifiers | foundation-types |
| `Tenant` | Identifiers | foundation-types |
| `TenantId` | Identifiers | foundation-types |
| `TimeWindow` | Utilities | types |
| `Triple<T1,T2,T3>` | Utilities | shared |
| `UnitOfWork` | Transactions | foundation |
| `UnitOfWorkFactory<UOW>` | Transactions | foundation |
| `Version` | Document DB | postgresql-document-db |
| `VersionedEntity<ID,SELF>` | Document DB | postgresql-document-db |
| `ViewEventProcessor` | Event Processing | postgresql-event-store |
| `ZonedDateTimeType<T>` | Temporal | types |
| `ZonedDateTimeValueType<SELF>` | Kotlin Types | types |

---

## 1. Semantic Types (Base Classes)

Base package: `dk.trustworks.essentials.types`

Use these to create custom strongly-typed wrappers eliminating primitive obsession.

| Type | Wraps | Purpose |
|------|-------|---------|
| `SingleValueType<V,T>` | Any | Master interface for all semantic types |
| `CharSequenceType<T>` | String | String-based IDs and values |
| `NumberType<N,T>` | Number | Abstract base for all numeric types |
| `BigDecimalType<T>` | BigDecimal | Decimal values with full arithmetic |
| `BigIntegerType<T>` | BigInteger | Arbitrary precision integers |
| `LongType<T>` | Long | 64-bit integers |
| `IntegerType<T>` | Integer | 32-bit integers |
| `ShortType<T>` | Short | 16-bit integers |
| `ByteType<T>` | Byte | 8-bit integers |
| `DoubleType<T>` | Double | 64-bit floating point |
| `FloatType<T>` | Float | 32-bit floating point |
| `BooleanType<T>` | Boolean | Boolean wrapper |

**Marker Interface:**

| Type | Purpose |
|------|---------|
| `Identifier` | Optional marker for semantic ID types |

See: [LLM-types.md](LLM-types.md)

---

## 2. Temporal Types (Base Classes)

Base package: `dk.trustworks.essentials.types`

| Type | Wraps | Purpose |
|------|-------|---------|
| `JSR310SingleValueType<T>` | Temporal | Base for Java Time wrappers |
| `InstantType<T>` | Instant | Instant wrapper |
| `LocalDateType<T>` | LocalDate | LocalDate wrapper |
| `LocalDateTimeType<T>` | LocalDateTime | LocalDateTime wrapper |
| `LocalTimeType<T>` | LocalTime | LocalTime wrapper |
| `OffsetDateTimeType<T>` | OffsetDateTime | OffsetDateTime wrapper |
| `ZonedDateTimeType<T>` | ZonedDateTime | ZonedDateTime wrapper |

See: [LLM-types.md](LLM-types.md)

---

## 3. Kotlin Value Types (Base Interfaces)

Base package: `dk.trustworks.essentials.kotlin.types`

Kotlin value class interfaces for zero-runtime-overhead semantic types.

### String & Numeric

| Interface | Wraps | Purpose |
|-----------|-------|---------|
| `StringValueType<SELF>` | String | String-based value classes |
| `BigDecimalValueType<SELF>` | BigDecimal | Decimal value classes |
| `BigIntegerValueType<SELF>` | BigInteger | Arbitrary precision integers |
| `LongValueType<SELF>` | Long | 64-bit integers |
| `IntValueType<SELF>` | Int | 32-bit integers |
| `ShortValueType<SELF>` | Short | 16-bit integers |
| `ByteValueType<SELF>` | Byte | 8-bit integers |
| `DoubleValueType<SELF>` | Double | 64-bit floating point |
| `FloatValueType<SELF>` | Float | 32-bit floating point |
| `BooleanValueType<SELF>` | Boolean | Boolean value classes |

### Temporal

| Interface | Wraps | Purpose |
|-----------|-------|---------|
| `InstantValueType<SELF>` | Instant | Instant value classes |
| `LocalDateValueType<SELF>` | LocalDate | LocalDate value classes |
| `LocalDateTimeValueType<SELF>` | LocalDateTime | LocalDateTime value classes |
| `LocalTimeValueType<SELF>` | LocalTime | LocalTime value classes |
| `OffsetDateTimeValueType<SELF>` | OffsetDateTime | OffsetDateTime value classes |
| `ZonedDateTimeValueType<SELF>` | ZonedDateTime | ZonedDateTime value classes |

### Built-in Kotlin Types

| Type | Implements | Purpose |
|------|------------|---------|
| `Amount` (Kotlin) | `BigDecimalValueType` | Currency-agnostic monetary value |
| `CountryCode` (Kotlin) | `StringValueType` | ISO-3166-2 country codes |

See: [LLM-types.md](LLM-types.md)

---

## 4. Money & Currency

Base package: `dk.trustworks.essentials.types`

| Type | Extends | Purpose |
|------|---------|---------|
| `Amount` | `BigDecimalType<Amount>` | Currency-agnostic monetary value |
| `CurrencyCode` | `CharSequenceType<CurrencyCode>` | ISO-4217 currency codes (validated) |
| `Money` | (standalone) | Amount + CurrencyCode tuple (enforces same currency) |
| `Percentage` | `BigDecimalType<Percentage>` | Percentage calculations with `of()` method |

See: [LLM-types.md](LLM-types.md)

---

## 5. Validation Types

Base package: `dk.trustworks.essentials.types`

| Type | Extends | Validation |
|------|---------|------------|
| `CountryCode` | `CharSequenceType<CountryCode>` | ISO-3166-2 country codes |
| `EmailAddress` | `CharSequenceType<EmailAddress>` | Pluggable email validation |

See: [LLM-types.md](LLM-types.md)

---

## 6. Utility Types

Base package: `dk.trustworks.essentials.types`

| Type | Purpose |
|------|---------|
| `LongRange` | Range of Long values with `stream()`, `covers()`, `between()` |
| `TimeWindow` | Time period with Instant bounds, `covers()`, `close()` |

See: [LLM-types.md](LLM-types.md)

---

## 7. Identifiers & Correlation

Base package: `dk.trustworks.essentials.components.foundation.types`

| Type | Extends | Purpose |
|------|---------|---------|
| `CorrelationId` | `CharSequenceType` + `Identifier` | Cross-service operation tracking |
| `EventId` | `CharSequenceType` + `Identifier` | Unique event identifier |
| `MessageId` | `CharSequenceType` + `Identifier` | User-facing message correlation |
| `SubscriberId` | `CharSequenceType` + `Identifier` | Event stream subscriber identity |
| `TenantId` | `CharSequenceType` + `Tenant` + `Identifier` | Multi-tenant identifier |
| `Tenant` | (interface) | Marker interface for tenant representation |

**Utilities:**

| Type | Purpose |
|------|---------|
| `RandomIdGenerator` | Static UUID/time-based ID generator |

See: [LLM-foundation-types.md](LLM-foundation-types.md)

---

## 8. Event Store Types

### Event Identification

Base package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`

| Type | Package Suffix | Purpose |
|------|----------------|---------|
| `AggregateType` | `.eventstream` | Aggregate classification (becomes table name) |
| `EventName` | `.types` | Named event identifier (string, for schema-less events) |
| `EventType` | `.types` | Typed event identifier (FQCN, for Java classes) |
| `EventTypeOrName` | `.types` | Union: EventType OR EventName |

### Event Ordering

| Type | Package Suffix | Scope | Starting |
|------|----------------|-------|----------|
| `EventOrder` | `.types` | Per aggregate instance | 0 |
| `GlobalEventOrder` | `.types` | Per aggregate type | 1 |

### Versioning

| Type | Package Suffix | Purpose |
|------|----------------|---------|
| `EventRevision` | `.types` | Event schema version (IntegerType) |
| `@Revision` | `.types` | Annotation for event schema versioning |

### Core Interfaces

| Interface | Package Suffix | Purpose |
|-----------|----------------|---------|
| `EventStore` | (root) | Event operations (append, fetch, poll) |
| `ConfigurableEventStore<C>` | (root) | EventStore + configuration methods |
| `PersistedEvent` | `.eventstream` | Event with metadata after storage |
| `AggregateEventStream<ID>` | `.eventstream` | Stream of events for aggregate instance |

### Implementation

| Class | Purpose |
|-------|---------|
| `PostgresqlEventStore` | Full EventStore implementation |

See: [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md), [LLM-foundation-types.md](LLM-foundation-types.md)

---

## 9. Event Processing

Base package: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor`

| Processor | Processing Mode | Exclusive | Best For |
|-----------|-----------------|-----------|----------|
| `EventProcessor` | Async (Inbox queue) | Yes | External integrations, long operations |
| `InTransactionEventProcessor` | Sync (in-tx) | Configurable | Consistent projections |
| `ViewEventProcessor` | Async direct + queue on failure | Yes | Low-latency views |

See: [LLM-postgresql-event-store.md](LLM-postgresql-event-store.md#eventprocessor-framework)

---

## 10. Distributed Coordination (Fenced Locks)

### Interface

Base package: `dk.trustworks.essentials.components.foundation.fencedlock`

| Type | Purpose |
|------|---------|
| `FencedLockManager` | Distributed lock manager interface (extends `Lifecycle`) |
| `FencedLock` | Acquired lock with fence token (implements `AutoCloseable`) |
| `LockName` | Lock identifier |
| `LockCallback` | Async lock acquisition callback |

### Implementations

| Implementation | Package | Module |
|----------------|---------|--------|
| `PostgresqlFencedLockManager` | `dk.trustworks.essentials.components.distributed.fencedlock.postgresql` | postgresql-distributed-fenced-lock |
| `MongoFencedLockManager` | `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo` | springdata-mongo-distributed-fenced-lock |

See: [LLM-foundation.md](LLM-foundation.md#fencedlock-distributed-locking), [LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md)

---

## 11. Messaging & Queues

### DurableQueues Interface

Base package: `dk.trustworks.essentials.components.foundation.messaging.queue`

| Type | Purpose |
|------|---------|
| `DurableQueues` | Durable queue interface (extends `Lifecycle`) |
| `QueueName` | Queue identifier |
| `QueueEntryId` | Message entry ID |
| `QueuedMessage` | Message with metadata |
| `OrderedMessage` | Message with ordering key and sequence |
| `DurableQueueConsumer` | Active consumer handle |
| `ConsumeFromQueue` | Consumer configuration builder |

### Redelivery

Base package: `dk.trustworks.essentials.components.foundation.messaging`

| Type | Purpose |
|------|---------|
| `RedeliveryPolicy` | Retry strategies (fixed, linear, exponential backoff) |
| `MessageDeliveryErrorHandler` | Error handling strategies |

### Implementations

| Implementation | Package | Module |
|----------------|---------|--------|
| `PostgresqlDurableQueues` | `dk.trustworks.essentials.components.queue.postgresql` | postgresql-queue |
| `MongoDurableQueues` | `dk.trustworks.essentials.components.queue.springdata.mongodb` | springdata-mongo-queue |

### Inbox/Outbox (Store-and-Forward)

Base package: `dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward`

| Type | Purpose |
|------|---------|
| `Inbox` | Store-and-forward for incoming messages |
| `Outbox` | Store-and-forward for outgoing messages |
| `Inboxes` | Inbox factory |
| `Outboxes` | Outbox factory |
| `InboxConfig` | Inbox configuration |
| `OutboxConfig` | Outbox configuration |
| `MessageConsumptionMode` | `SingleGlobalConsumer` or `GlobalCompetingConsumers` |

See: [LLM-foundation.md](LLM-foundation.md#durablequeues-messaging), [LLM-postgresql-queue.md](LLM-postgresql-queue.md)

---

## 12. Command & Event Buses

### Interfaces

Base package: `dk.trustworks.essentials.reactive`

| Type | Package Suffix | Purpose |
|------|----------------|---------|
| `EventBus` | (root) | Event publishing interface |
| `LocalEventBus` | (root) | In-memory pub/sub with sync/async subscribers |
| `EventHandler` | (root) | Event subscriber functional interface |
| `AnnotatedEventHandler` | (root) | `@Handler` annotation routing base class |

Base package: `dk.trustworks.essentials.reactive.command`

| Type | Purpose |
|------|---------|
| `CommandBus` | Command dispatch interface |
| `LocalCommandBus` | In-memory CQRS bus (non-durable) |
| `CommandHandler` | Command processor interface |
| `AnnotatedCommandHandler` | `@Handler`/`@CmdHandler` routing base class |

### Durable Command Bus

Base package: `dk.trustworks.essentials.components.foundation.reactive.command`

| Type | Purpose |
|------|---------|
| `DurableLocalCommandBus` | CommandBus using DurableQueues for `sendAndDontWait` |

### Annotations

| Annotation | Package | Purpose |
|------------|---------|---------|
| `@Handler` | `reactive` | Mark event/command handler methods |
| `@CmdHandler` | `reactive.command` | Alias for `@Handler` (command clarity) |
| `@MessageHandler` | `foundation.messaging` | Mark message handler methods |

See: [LLM-reactive.md](LLM-reactive.md), [LLM-foundation.md](LLM-foundation.md#durablelocalcommandbus)

---

## 13. Transaction Management

Base package: `dk.trustworks.essentials.components.foundation.transaction`

### Interfaces

| Type | Purpose |
|------|---------|
| `UnitOfWork` | Transaction abstraction |
| `UnitOfWorkFactory<UOW>` | UnitOfWork creation and management |

### Implementations

| Implementation | Package Suffix | Technology |
|----------------|----------------|------------|
| `JdbiUnitOfWorkFactory` | `.jdbi` | Direct JDBI |
| `SpringTransactionAwareJdbiUnitOfWorkFactory` | `.spring.jdbi` | JDBI + Spring TX |
| `SpringMongoTransactionAwareUnitOfWorkFactory` | `.spring.mongo` | MongoDB + Spring TX |
| `HandleAwareUnitOfWork` | `.jdbi` | UnitOfWork with JDBI Handle access |

See: [LLM-foundation.md](LLM-foundation.md#unitofwork-transactions)

---

## 14. Aggregate Patterns (Event-Sourced)

Base package: `dk.trustworks.essentials.components.eventsourced.aggregates`

### Aggregate Types

| Pattern | Package Suffix | Style | Description |
|---------|----------------|-------|-------------|
| `AggregateRoot<ID,EVENT,SELF>` | `.stateful.modern` | OOP Mutable | Modern aggregate with `apply()` |
| `FlexAggregate<ID,SELF>` | `.flex` | OOP Explicit | Explicit event/state control |
| `EventStreamDecider<CMD,EVENT>` | `.eventstream` | Functional | Slice-based command handling |
| `EventStreamEvolver<EVENT,STATE>` | `.eventstream` | Functional | State reconstruction from events |
| `Decider<CMD,EVENT,ERROR,STATE>` | `.decider` | Functional | Typed error handling |

### Repositories

| Type | Package Suffix | Purpose |
|------|----------------|---------|
| `StatefulAggregateRepository` | `.stateful` | Load/save stateful aggregates |
| `FlexAggregateRepository<ID,AGGREGATE>` | `.flex` | Load/save FlexAggregate instances |
| `AggregateSnapshotRepository` | `.snapshot` | Snapshot persistence |

### Testing

| Type | Package Suffix | Purpose |
|------|----------------|---------|
| `GivenWhenThenScenario` | `.eventstream.test` | BDD testing for EventStreamDecider |

### Annotations

| Annotation | Purpose |
|------------|---------|
| `@EventHandler` | Mark event handler methods in aggregates |

See: [LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md)

---

## 15. Kotlin EventSourcing

Base package: `dk.trustworks.essentials.components.kotlin.eventsourcing`

Kotlin-native functional event sourcing with Decider/Evolver patterns.

### Core Patterns

| Type | Purpose |
|------|---------|
| `Decider<COMMAND, EVENT>` | Command → event decision (pure function) |
| `Evolver<EVENT, STATE>` | Event → state evolution (left-fold) |
| `AggregateTypeConfiguration` | Aggregate metadata and ID extraction |

### Infrastructure

| Type | Package Suffix | Purpose |
|------|----------------|---------|
| `DeciderCommandHandlerAdapter` | `.adapters` | CommandBus integration |
| `DeciderAndAggregateTypeConfigurator` | `.adapters` | Auto-wiring for Spring |

### Testing

| Type | Package Suffix | Purpose |
|------|----------------|---------|
| `GivenWhenThenScenario` | `.test` | Pure function BDD testing |

See: [LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md)

---

## 16. Document Database (PostgreSQL)

Base package: `dk.trustworks.essentials.components.document_db`

Kotlin document database using PostgreSQL JSONB with type-safe queries.

### Core Types

| Type | Purpose |
|------|---------|
| `DocumentDbRepository<E, ID>` | CRUD + type-safe queries for JSONB documents |
| `DocumentDbRepositoryFactory` | Creates repositories, auto-creates tables/indexes |
| `VersionedEntity<ID, SELF>` | Required interface for entities (version + lastUpdated) |
| `Version` | Optimistic locking version (LongValueType) |
| `DelegatingDocumentDbRepository<E, ID>` | Base class for custom repositories |

### Query API

| Type | Purpose |
|------|---------|
| `QueryBuilder<ID, E>` | Type-safe query builder with sorting/pagination |
| `Condition<E>` | Type-safe WHERE clause conditions |
| `Index<E>` | Index definition for JSONB properties |

### Annotations

| Annotation | Purpose |
|------------|---------|
| `@DocumentEntity` | Table name for entity |
| `@Id` | Marks ID property |
| `@Indexed` | Creates GIN index on property |

### Exceptions

| Type | Purpose |
|------|---------|
| `OptimisticLockingException` | Thrown on version conflict during update |

See: [LLM-postgresql-document-db.md](LLM-postgresql-document-db.md)

---

## 17. Shared Utilities

Base package: `dk.trustworks.essentials.shared`

### Tuples

Package: `dk.trustworks.essentials.shared.functional.tuple`

| Type | Purpose |
|------|---------|
| `Empty` | Immutable 0-tuple (no elements) |
| `Single<T>` | Immutable 1-tuple |
| `Pair<T1,T2>` | Immutable 2-tuple |
| `Triple<T1,T2,T3>` | Immutable 3-tuple |
| `Quad<T1,T2,T3,T4>` | Immutable 4-tuple |
| `Either<T1,T2>` | Choice type (one of two values) |
| `Result<ERROR,SUCCESS>` | Success/error result type |

### Core Utilities

| Type | Package | Purpose |
|------|---------|---------|
| `Lifecycle` | (root) | Start/stop interface |
| `FailFast` | (root) | Enhanced null/validation checks |
| `Exceptions` | (root) | Root cause analysis, sneaky throw |
| `MessageFormatter` | (root) | SLF4J-style message formatting |
| `Reflector` | `.reflection` | High-performance caching reflection |
| `StopWatch` | `.time` | Time operations |
| `InterceptorChain` | `.interceptor` | Chain-of-responsibility pattern |

See: [LLM-shared.md](LLM-shared.md)

---

## Cross-Reference: Module to Key Types

| Module | Key Types |
|--------|-----------|
| `types` | `SingleValueType`, `CharSequenceType`, `NumberType`, `Amount`, `Money`, `Percentage`, `TimeWindow`, `LongRange`, `Identifier`, Kotlin: `StringValueType`, `BigDecimalValueType`, `LongValueType`, `InstantValueType`, etc. |
| `foundation-types` | `CorrelationId`, `EventId`, `MessageId`, `SubscriberId`, `TenantId`, `AggregateType`, `EventName`, `EventType`, `EventOrder`, `GlobalEventOrder`, `EventRevision` |
| `foundation` | `UnitOfWork`, `UnitOfWorkFactory`, `DurableQueues`, `FencedLockManager`, `FencedLock`, `Inbox`, `Outbox`, `DurableLocalCommandBus`, `OrderedMessage`, `RedeliveryPolicy` |
| `reactive` | `EventBus`, `LocalEventBus`, `CommandBus`, `LocalCommandBus`, `AnnotatedEventHandler`, `AnnotatedCommandHandler` |
| `postgresql-event-store` | `EventStore`, `PostgresqlEventStore`, `PersistedEvent`, `AggregateEventStream`, `EventProcessor`, `InTransactionEventProcessor`, `ViewEventProcessor` |
| `eventsourced-aggregates` | `AggregateRoot`, `FlexAggregate`, `FlexAggregateRepository`, `EventStreamDecider`, `EventStreamEvolver`, `Decider`, `StatefulAggregateRepository`, `GivenWhenThenScenario` |
| `kotlin-eventsourcing` | `Decider` (Kotlin), `Evolver`, `AggregateTypeConfiguration`, `GivenWhenThenScenario` |
| `postgresql-document-db` | `DocumentDbRepository`, `DocumentDbRepositoryFactory`, `VersionedEntity`, `Version`, `QueryBuilder`, `Index` |
| `shared` | `Empty`, `Single`, `Pair`, `Triple`, `Quad`, `Either`, `Result`, `Lifecycle`, `FailFast`, `Reflector`, `StopWatch` |
| `postgresql-distributed-fenced-lock` | `PostgresqlFencedLockManager` |
| `postgresql-queue` | `PostgresqlDurableQueues` |
| `springdata-mongo-distributed-fenced-lock` | `MongoFencedLockManager` |
| `springdata-mongo-queue` | `MongoDurableQueues` |

---

## See Also

- [LLM.md](LLM.md) - Main navigation index
- Individual module docs for detailed API reference
