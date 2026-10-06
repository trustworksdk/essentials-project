# Kotlin EventSourcing - LLM Reference

> LLM-optimized reference. For detailed explanations, see [README](../components/kotlin-eventsourcing/README.md).

## Quick Facts

- **Base package**: `dk.trustworks.essentials.components.kotlin.eventsourcing`
- **Purpose**: Kotlin-native functional event sourcing (Decider/Evolver patterns)
- **Status**: WORK-IN-PROGRESS (experimental API)
- **Key deps**: `kotlin-stdlib`, `kotlin-reflect` (all `provided` scope), EventStore, CommandBus
- **Java equiv**: [eventsourced-aggregates](./LLM-eventsourced-aggregates.md) `EventStreamDecider`

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>kotlin-eventsourcing</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `AggregateType`, `AggregateIdSerializer`, `ConfigurableEventStore`, `AggregateEventStream` from [postgresql-event-store](./LLM-postgresql-event-store.md)
- `CommandBus` from [reactive](./LLM-reactive.md)
- `UnitOfWork`, `UnitOfWorkFactory` from [foundation](./LLM-foundation.md)

## TOC

- [Core Patterns](#core-patterns)
- [Decider Pattern](#decider-pattern)
- [Evolver Pattern](#evolver-pattern)
- [CommandBus Integration](#commandbus-integration)
- [GivenWhenThenScenario Testing](#givenwhenthenscenario-testing)
- [Query State from EventStore](#query-state-from-eventstore)
- [Common Patterns](#common-patterns)
- ⚠️ [Gotchas](#gotchas)
- ⚠️ [Security](#security)
- [Key Classes](#key-classes)
- [Maven Dependency](#maven-dependency)

## Core Patterns

| Pattern | Type | Returns | State | Use When |
|---------|------|---------|-------|----------|
| `Decider` | `(cmd, events) → event?` | `EVENT?` | Event stream | Command handling, slice-based impl |
| `Evolver` | `(event, state) → state?` | `STATE?` | Immutable state | State reconstruction, validation |

## Decider Pattern

**Interface**: `dk.trustworks.essentials.components.kotlin.eventsourcing.Decider<COMMAND, EVENT>`

Pure functional, slice-based, ideal for Event Modeling. Returns single event (or none).

```kotlin
interface Decider<COMMAND, EVENT> {
    fun handle(cmd: COMMAND, events: List<EVENT>): EVENT?
    fun canHandle(cmd: Any): Boolean
}
```

**Parameters**:
- `cmd` - Command to process
- `events` - Complete event history for aggregate (from EventStore)

**Returns**:

| Return | Meaning | Use Case |
|--------|---------|----------|
| `EVENT` | Success, persist event | Command produced new event |
| `null` | No-op | Idempotent handling |
| Throw | Rejection | Invalid state/business rule |

**Implementation**:

```kotlin
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider

class CreateOrderDecider : Decider<CreateOrder, OrderEvent> {
    override fun handle(cmd: CreateOrder, events: List<OrderEvent>): OrderEvent? {
        // Idempotency check
        if (events.any { it is OrderCreated }) return null
        return OrderCreated(cmd.id)
    }
    override fun canHandle(cmd: Any): Boolean = cmd is CreateOrder
}

class ShipOrderDecider : Decider<ShipOrder, OrderEvent> {
    override fun handle(cmd: ShipOrder, events: List<OrderEvent>): OrderEvent? {
        if (events.isEmpty())
            throw RuntimeException("Order not created")
        if (events.any { it is OrderShipped })
            return null // Already shipped
        if (!events.any { it is OrderAccepted })
            throw RuntimeException("Order not accepted")
        return OrderShipped(cmd.id)
    }
    override fun canHandle(cmd: Any): Boolean = cmd is ShipOrder
}
```

**Why Single Event?**

Forces explicit event modeling instead of implementation steps:

| Anti-pattern | Correct Pattern |
|--------------|-----------------|
| `CreateOrder` → `OrderCreated` + `ProductsAdded` | `OrderWithProductsPlaced` |
| `RegisterCustomer` → `CustomerCreated` + `AddressAdded` | `CustomerRegistered` |

Benefits: Clear intent, no event reuse, simpler evolution, Event Storming alignment.

> For Java patterns supporting multiple events, see [eventsourced-aggregates](./LLM-eventsourced-aggregates.md).

## Evolver Pattern

**Interface**: `dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver<EVENT, STATE>`

```kotlin
fun interface Evolver<EVENT, STATE> {
    fun applyEvent(event: EVENT, state: STATE?): STATE?

    companion object {
        fun <STATE, EVENT> applyEvents(
            stateEvolver: Evolver<EVENT, STATE>,
            initialState: STATE?,
            eventStream: List<EVENT>
        ): STATE

        // Helper methods
        inline fun <reified E> extractEvents(stream: AggregateEventStream<*>): Sequence<E>
        inline fun <reified E> extractEventsAsList(stream: AggregateEventStream<*>): List<E>
    }
}
```

**Left-fold pattern**: `null → Event[0] → STATE? → Event[1] → STATE? → ... → Final STATE`

**State Class (Immutable)**:

```kotlin
data class OrderState(
    val orderId: OrderId,
    val status: OrderStatus,
    val products: Map<ProductId, Int> = emptyMap(),
    val cancelReason: String? = null
) {
    companion object {
        fun created(orderId: OrderId) = OrderState(orderId, OrderStatus.CREATED)
    }
    fun canBeConfirmed() = status == OrderStatus.CREATED
    fun canBeShipped() = status == OrderStatus.CONFIRMED
}
```

**Evolver Implementation**:

```kotlin
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver

class OrderStateEvolver : Evolver<OrderEvent, OrderState> {
    override fun applyEvent(event: OrderEvent, state: OrderState?): OrderState? {
        return when (event) {
            is OrderCreated -> OrderState.created(event.id)
            is OrderConfirmed -> state?.copy(status = OrderStatus.CONFIRMED)
            is OrderShipped -> state?.copy(status = OrderStatus.SHIPPED)
            is OrderCancelled -> state?.copy(
                status = OrderStatus.CANCELLED,
                cancelReason = event.reason
            )
            else -> state
        }
    }
}
```

**Using Evolver in Decider**:

```kotlin
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver

class ConfirmOrderDecider : Decider<ConfirmOrder, OrderEvent> {
    private val evolver = OrderStateEvolver()

    override fun handle(cmd: ConfirmOrder, events: List<OrderEvent>): OrderEvent? {
        // applyEvents throws NPE on an empty list - guard first (see Gotchas)
        if (events.isEmpty()) throw RuntimeException("Order does not exist")
        val state = Evolver.applyEvents(evolver, null, events)

        if (state.status == OrderStatus.CONFIRMED) return null // Idempotent
        if (!state.canBeConfirmed())
            throw RuntimeException("Cannot confirm order in ${state.status}")

        return OrderConfirmed(cmd.id)
    }
    override fun canHandle(cmd: Any): Boolean = cmd is ConfirmOrder
}
```

**Helper Methods**:

```kotlin
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream

// Lazy evaluation (Sequence)
val events: Sequence<OrderEvent> = Evolver.extractEvents<OrderEvent>(stream)

// Eager evaluation (List) - recommended
val events: List<OrderEvent> = Evolver.extractEventsAsList<OrderEvent>(stream)
```

Benefits: Auto-deserialization, type filtering, cleaner than manual `stream.events().map { it.event().deserialize<T>() }`.

## CommandBus Integration

**Infrastructure Responsibilities**:

| Concern | Handled By |
|---------|-----------|
| Command routing | `DeciderCommandHandlerAdapter` |
| Aggregate ID extraction | `AggregateTypeConfiguration` |
| Event loading | EventStore fetch |
| Event persistence | EventStore append |
| Transactions | UnitOfWork |

**Flow**: `CommandBus.send(cmd)` → `DeciderCommandHandlerAdapter` → extract aggregateId → load events → `decider.handle(cmd, events)` → persist event (if any)

### AggregateTypeConfiguration

**Class**: `dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration`

```kotlin
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.StringValueTypeAggregateIdSerializer

AggregateTypeConfiguration(
    aggregateType = AggregateType.of("Orders"),
    aggregateIdType = OrderId::class.java,
    aggregateIdSerializer = StringValueTypeAggregateIdSerializer(OrderId::class),

    // Which deciders handle this aggregate
    deciderSupportsAggregateTypeChecker = DeciderSupportsAggregateTypeChecker
        .HandlesCommandsThatInheritsFromCommandType(OrderCommand::class),

    // Extract ID from command (null OK for create commands)
    commandAggregateIdResolver = { cmd -> (cmd as OrderCommand).id },

    // Extract ID from event (fallback)
    eventAggregateIdResolver = { e -> (e as OrderEvent).id }
)
```

**Parameters**:

| Parameter | Purpose | Example |
|-----------|---------|---------|
| `aggregateType` | Aggregate identifier | `AggregateType.of("Orders")` |
| `aggregateIdType` | ID type | `OrderId::class.java` |
| `aggregateIdSerializer` | Serializer | `StringValueTypeAggregateIdSerializer(OrderId::class)` |
| `deciderSupportsAggregateTypeChecker` | Decider filter | Check if cmd inherits from `OrderCommand` |
| `commandAggregateIdResolver` | Extract ID from cmd | `{ (it as OrderCommand).id }` |
| `eventAggregateIdResolver` | Extract ID from event | `{ (it as OrderEvent).id }` |

**Id serializer**: for a Kotlin `StringValueType` id (`@JvmInline value class OrderId(override val value: String) : StringValueType<OrderId>`)
use `StringValueTypeAggregateIdSerializer(OrderId::class)` (package `…eventstore.postgresql.serializer`); it needs `kotlin-reflect` at runtime.
`AggregateIdSerializer.serializerFor(OrderId::class.java)` returns the same serializer for such an id and covers `String`, `UUID` and
Java `CharSequenceType` ids. Any other id type makes `serializerFor` throw `EventStoreException` when the configuration bean is created,
so implement `AggregateIdSerializer` for it.

### Spring Boot Wiring

**Class**: `dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator`

**Dependencies from other modules**:
- `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType` from [postgresql-event-store](./LLM-postgresql-event-store.md)
- `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.StringValueTypeAggregateIdSerializer` from [postgresql-event-store](./LLM-postgresql-event-store.md)
- `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore` from [postgresql-event-store](./LLM-postgresql-event-store.md)
- `dk.trustworks.essentials.reactive.command.CommandBus` from [reactive](./LLM-reactive.md)

```kotlin
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.StringValueTypeAggregateIdSerializer
import dk.trustworks.essentials.reactive.command.CommandBus

@Configuration
class OrdersConfiguration {
    companion object {
        @JvmStatic
        val AGGREGATE_TYPE = AggregateType.of("Orders")
    }

    @Bean
    fun deciderAndAggregateTypeConfigurator(
        eventStore: ConfigurableEventStore<*>,
        commandBus: CommandBus,
        aggregateTypeConfigurations: List<AggregateTypeConfiguration>,
        deciders: List<Decider<*, *>>
    ): DeciderAndAggregateTypeConfigurator {
        return DeciderAndAggregateTypeConfigurator(
            eventStore, commandBus, aggregateTypeConfigurations, deciders
        )
    }

    @Bean
    fun orderAggregateTypeConfiguration(): AggregateTypeConfiguration {
        return AggregateTypeConfiguration(
            aggregateType = AGGREGATE_TYPE,
            aggregateIdType = OrderId::class.java,
            aggregateIdSerializer = StringValueTypeAggregateIdSerializer(OrderId::class),
            deciderSupportsAggregateTypeChecker = DeciderSupportsAggregateTypeChecker
                .HandlesCommandsThatInheritsFromCommandType(OrderCommand::class),
            commandAggregateIdResolver = { cmd -> (cmd as OrderCommand).id },
            eventAggregateIdResolver = { e -> (e as OrderEvent).id }
        )
    }

    @Bean fun createOrderDecider() = CreateOrderDecider()
    @Bean fun acceptOrderDecider() = AcceptOrderDecider()
    @Bean fun shipOrderDecider() = ShipOrderDecider()
}
```

> Deciders can use `@Component`/`@Service` instead of `@Bean` methods.

### Sending Commands

Assumes `dk.trustworks.essentials.reactive.command.CommandBus` configured with `dk.trustworks.essentials.components.foundation.reactive.command.UnitOfWorkControllingCommandBusInterceptor`.

```kotlin
import dk.trustworks.essentials.reactive.command.CommandBus
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory

@Service
class OrderService(
    private val commandBus: CommandBus,
    private val unitOfWorkFactory: UnitOfWorkFactory
) {
    fun createOrder(orderId: OrderId, customerId: CustomerId): Boolean {
        val event: OrderEvent? = commandBus.send(CreateOrder(orderId, customerId))
        return event != null // false if already exists
    }

    fun confirmOrder(orderId: OrderId) {
        commandBus.send<Any?, ConfirmOrder>(ConfirmOrder(orderId))
    }
}
```

## GivenWhenThenScenario Testing

**Class**: `dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario`

**Benefits**:

| Benefit | Why |
|---------|-----|
| No database | Pure function tests |
| No Spring | Millisecond execution |
| No mocking | No dependencies |
| Deterministic | Same input = same output |
| Readable | Business language |

**Test Patterns**:

```kotlin
import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario

@Test
fun `Create an Order`() {
    val scenario = GivenWhenThenScenario(CreateOrderDecider())
    val orderId = OrderId.random()

    scenario
        .given() // No existing events
        .when_(CreateOrder(orderId))
        .then_(OrderCreated(orderId))
}

@Test
fun `Create Order twice is idempotent`() {
    val scenario = GivenWhenThenScenario(CreateOrderDecider())
    val orderId = OrderId.random()

    scenario
        .given(OrderCreated(orderId))
        .when_(CreateOrder(orderId))
        .thenExpectNoEvent()
}

@Test
fun `Cannot ship unaccepted Order`() {
    val scenario = GivenWhenThenScenario(ShipOrderDecider())
    val orderId = OrderId.random()

    scenario
        .given(OrderCreated(orderId))
        .when_(ShipOrder(orderId))
        .thenFailsWithExceptionType(RuntimeException::class)
}

@Test
fun `Custom assertions`() {
    val scenario = GivenWhenThenScenario(CreateOrderDecider())
    val beforeTest = OffsetDateTime.now()

    scenario
        .given()
        .when_(CreateOrder(orderId, customerId))
        .thenAssert { actualEvent ->
            assertThat(actualEvent).isInstanceOf(OrderCreated::class.java)
            val created = actualEvent as OrderCreated
            assertThat(created.occurredAt).isAfter(beforeTest)
        }
}
```

**Assertion Exceptions**:

| Exception | Trigger |
|-----------|---------|
| `NoCommandProvidedException` | `when_()` not called |
| `DidNotExpectAnEventException` | Expected null, got event |
| `ExpectedAnEventButDidGetAnyEventException` | Expected event, got null |
| `ActualAndExpectedEventsAreNotEqualExcepted` | Events don't match |
| `ExpectToFailWithAnExceptionButNoneWasThrown` | Expected exception, none thrown |
| `ActualExceptionIsNotEqualToExpectedException` | Wrong exception |

**Test reference**: [`GivenWhenThenScenarioTest.kt`](../components/kotlin-eventsourcing/src/test/kotlin/dk/trustworks/essentials/components/kotlin/eventsourcing/test/GivenWhenThenScenarioTest.kt)

## Query State from EventStore

```kotlin
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType

// Recommended: Using Evolver.extractEventsAsList helper
fun getOrderState(orderId: OrderId, eventStore: EventStore<*>): OrderState? {
    val eventStream = eventStore.fetchStream(AGGREGATE_TYPE, orderId)
    if (!eventStream.isPresent) return null

    val events = Evolver.extractEventsAsList<OrderEvent>(eventStream.get())
    return Evolver.applyEvents(OrderStateEvolver(), null, events)
}

// Alternative: Manual deserialization (more verbose)
fun getOrderStateManual(orderId: OrderId, eventStore: EventStore<*>): OrderState? {
    val eventStream = eventStore.fetchStream(AGGREGATE_TYPE, orderId)
    if (!eventStream.isPresent) return null

    val events = eventStream.get()
        .events()
        .map { it.event().deserialize<OrderEvent>() }
        .toList()

    return Evolver.applyEvents(OrderStateEvolver(), null, events)
}
```

## Common Patterns

### Command/Event Design

```kotlin
// Commands - plain interface; each Decider handles one concrete command class
interface OrderCommand {
    val id: OrderId
}

data class CreateOrder(
    override val id: OrderId,
    val customerId: CustomerId
) : OrderCommand

data class AcceptOrder(override val id: OrderId) : OrderCommand
data class ShipOrder(override val id: OrderId) : OrderCommand

// Events (sealed for an exhaustive `when` in the Evolver - all subtypes in this package)
sealed interface OrderEvent {
    val id: OrderId
    val occurredAt: OffsetDateTime
}

data class OrderCreated(
    override val id: OrderId,
    val customerId: CustomerId,
    override val occurredAt: OffsetDateTime = OffsetDateTime.now()
) : OrderEvent

data class OrderAccepted(
    override val id: OrderId,
    override val occurredAt: OffsetDateTime = OffsetDateTime.now()
) : OrderEvent
```

### Idempotency

```kotlin
// ✅ Return null for already-processed commands
if (events.any { it is OrderAccepted }) return null

// ❌ Don't throw for idempotency
if (events.any { it is OrderAccepted })
    throw RuntimeException("Already accepted") // WRONG
```

### State Validation

```kotlin
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver

// ✅ Use Evolver for complex validation - guard the empty stream first
if (events.isEmpty()) throw RuntimeException("Order not created")
val state = Evolver.applyEvents(evolver, null, events)
if (!state.canBeShipped()) throw RuntimeException("Invalid state")

// ✅ Simple checks directly on events
if (!events.any { it is OrderCreated })
    throw RuntimeException("Order not created")
```

### Immutable State

```kotlin
// ✅ Use data class copy()
is OrderConfirmed -> state?.copy(status = OrderStatus.CONFIRMED)

// ❌ Don't mutate
is OrderConfirmed -> {
    state?.status = OrderStatus.CONFIRMED // Won't compile
}
```

### Kotlin vs Java Patterns

| Aspect | Kotlin `Decider` | Java `EventStreamDecider` |
|--------|------------------|---------------------------|
| Language | Kotlin data classes, null safety | Java records, Optional |
| Return | `EVENT?` | `Optional<EVENT>` |
| Event stream | `List<EVENT>` | `List<EVENT>` |
| Testing | `GivenWhenThenScenario` (Kotlin) | `GivenWhenThenScenario` (Java) |
| Config | `AggregateTypeConfiguration` | `EventStreamAggregateTypeConfiguration` |
| Adapter | `DeciderCommandHandlerAdapter` | `EventStreamDeciderCommandHandlerAdapter` |

For Java patterns or OOP aggregates, see [eventsourced-aggregates](./LLM-eventsourced-aggregates.md).

## Gotchas

### `Evolver.applyEvents()` throws instead of returning `null`

`applyEvents` ends with `!!` on the fold result, so it throws `NullPointerException` whenever the fold ends at `null`:
an empty event list with a `null` initial state (every new aggregate — `DeciderCommandHandlerAdapter` passes an empty
list when no stream exists), or an evolver whose last applied event yields `null` (e.g. `else -> state` before any
creation event). A `state == null` check *after* the call never runs. Guard before calling:

```kotlin
// ✅ Check before folding
if (events.isEmpty()) throw RuntimeException("Order does not exist")
val state = Evolver.applyEvents(evolver, null, events)

// ❌ NPE inside applyEvents on an empty list - the null check is dead code
val state = Evolver.applyEvents(evolver, null, events)
if (state == null) throw RuntimeException("Order does not exist")
```

The evolver must also return a non-null state after the first event it can see, or the same NPE fires on a non-empty list.

### `CommandBus.send()` needs an expected type in Kotlin — a cast is not enough

`CommandBus.send` is declared `<R, C> R send(C command)`. `R` appears only in the return type, so Kotlin must take it from
an expected type or explicit type arguments; `as` does not supply it. The result is the event the `Decider` returned, or
`null` when it returned `null` (idempotent no-op).

```kotlin
// ✅ Expected type on the declaration
val event: OrderEvent? = commandBus.send(CreateOrder(orderId, customerId))

// ✅ Explicit type arguments - also for a call whose result you ignore
commandBus.send<Any?, ConfirmOrder>(ConfirmOrder(orderId))

// ❌ Does not compile: "cannot infer type for type parameter 'R'"
val event = commandBus.send(cmd) as OrderEvent?
commandBus.send(cmd)
```

### Sealed command/event interfaces must live in one package

Kotlin requires every direct subtype of a `sealed` interface to be declared in the **same package and module** as the
interface ("a class can only extend a sealed class or interface declared in the same package"). The framework itself does
not care: `DeciderCommandHandlerAdapter` routes by the exact command class, and
`HandlesCommandsThatInheritsFromCommandType` matches with `isAssignableFrom`, so sealed and plain interfaces behave the same.

- Commands: a `Decider` handles exactly one concrete command class, so a sealed command hierarchy buys no exhaustiveness.
  Use a plain `interface OrderCommand` when commands live next to their deciders in separate (slice) packages.
- Events: `sealed` pays off in the `Evolver`'s exhaustive `when`, as long as all events of the aggregate share one package.

### A sealed type used as a *field* inside an event needs type info

The event store records each event's class name, so the event itself deserializes without any type
metadata. A field *inside* the event whose declared type is a sealed interface (or any abstract type) has
no such help: the Essentials mapper enables no default typing, so Jackson cannot pick the subtype and
deserialization fails — the subscriber or processor reading that event cannot get past it. Annotate the
nested sealed type:

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes(
    JsonSubTypes.Type(CardPayment::class, name = "card"),
    JsonSubTypes.Type(InvoicePayment::class, name = "invoice"))
sealed interface PaymentMethod
```

Adding it to a type that is already persisted changes the JSON it writes; rows written before carry no
`@type`, so give the annotation a `defaultImpl` or migrate them.

## Security

### ⚠️ Critical: SQL Injection Risk

Components allow customization of table/column/index/function names used with **String concatenation** → SQL injection risk.

⚠️ **Sanitize input**: `AggregateType` used in SQL string concatenation.

See [README Security](../components/kotlin-eventsourcing/README.md#security) for full details.

**Required actions**:
- Generate aggregate IDs with `RandomIdGenerator.generate()` or `UUID.randomUUID()`
- Never use unsanitized user input for aggregate IDs or types

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

## Key Classes

**Base package**: `dk.trustworks.essentials.components.kotlin.eventsourcing`

| Class | Package Suffix | Purpose |
|-------|----------------|---------|
| `Decider<COMMAND, EVENT>` | — | Command → event decision |
| `Evolver<EVENT, STATE>` | — | Event → state evolution |
| `AggregateTypeConfiguration` | — | Aggregate metadata config |
| `DeciderSupportsAggregateTypeChecker` | — | Decider-to-aggregate mapping |
| `DeciderCommandHandlerAdapter` | `.adapters` | CommandBus bridge |
| `DeciderAndAggregateTypeConfigurator` | `.adapters` | Auto-wiring |
| `GivenWhenThenScenario` | `.test` | Pure function testing |

**Exception classes** (all in `.test` suffix):

| Exception | Purpose |
|-----------|---------|
| `AssertionException` | Base class for all assertion failures |
| `NoCommandProvidedException` | No command in `when_()` |
| `DidNotExpectAnEventException` | Got event when expecting null |
| `ExpectedAnEventButDidGetAnyEventException` | Got null when expecting event |
| `ActualAndExpectedEventsAreNotEqualExcepted` | Event mismatch |
| `ExpectToFailWithAnExceptionButNoneWasThrown` | No exception thrown |
| `ActualExceptionIsNotEqualToExpectedException` | Wrong exception thrown |

## Dependencies

| Module | Key Types Used |
|--------|----------------|
| [postgresql-event-store](./LLM-postgresql-event-store.md) | `EventStore`, `ConfigurableEventStore`, `AggregateType`, `AggregateIdSerializer`, `AggregateEventStream` |
| [reactive](./LLM-reactive.md) | `CommandBus` |
| [foundation](./LLM-foundation.md) | `UnitOfWork`, `UnitOfWorkFactory` |

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>kotlin-eventsourcing</artifactId>
    <version>${essentials.version}</version>
</dependency>

<!-- Provided scope -->
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-stdlib-jdk8</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-reflect</artifactId>
    <scope>provided</scope>
</dependency>
```
