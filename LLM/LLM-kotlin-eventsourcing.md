# Kotlin EventSourcing - LLM Reference

> See [README](../components/kotlin-eventsourcing/README.md) for detailed developer documentation.

## Quick Facts

- **Package**: `dk.trustworks.essentials.components.kotlin.eventsourcing`
- **Purpose**: Kotlin-native functional event sourcing (Decider/Evolver patterns)
- **Status**: WORK-IN-PROGRESS (experimental API)
- **Key deps**: `kotlin-stdlib`, `kotlin-reflect` (all `provided`scope), `EventStore`, `CommandBus`
- **Java equiv**: [eventsourced-aggregates](./LLM-eventsourced-aggregates.md) `EventStreamDecider`

## TOC

- [Core Patterns](#core-patterns)
- [Decider Pattern](#decider-pattern)
  - [Return Values](#return-values)
  - [Implementation](#implementation)
  - [Why Single Event?](#why-single-event)
- [Evolver Pattern](#evolver-pattern)
  - [State Class (Immutable)](#state-class-immutable)
  - [Evolver Implementation](#evolver-implementation)
  - [Using Evolver in Decider](#using-evolver-in-decider)
- [CommandBus Integration](#commandbus-integration)
  - [Infrastructure Responsibilities](#infrastructure-responsibilities)
  - [AggregateTypeConfiguration](#aggregatetypeconfiguration)
  - [Spring Boot Wiring](#spring-boot-wiring)
  - [Sending Commands](#sending-commands)
- [GivenWhenThenScenario Testing](#givenwhenthenscenario-testing)
  - [Benefits](#benefits)
  - [Test Patterns](#test-patterns)
  - [Assertion Exceptions](#assertion-exceptions)
- [Query State from EventStore](#query-state-from-eventstore)
- [Command/Event Design](#commandevent-design)
- [Kotlin vs Java Patterns](#kotlin-vs-java-patterns)
- [Common Patterns](#common-patterns)
  - [Idempotency](#idempotency)
  - [State Validation](#state-validation)
  - [Immutable State](#immutable-state)
- ⚠️ [Security](#security)
- [Key Classes](#key-classes)
- [Dependencies](#dependencies)
- [Maven Dependency](#maven-dependency)

## Core Patterns

| Pattern | Type | Returns | State | Use When |
|---------|------|---------|-------|----------|
| `Decider` | `(cmd, events) → event?` | `EVENT?` | Event stream | Command handling, slice-based impl |
| `Evolver` | `(event, state) → state?` | `STATE?` | Immutable state | State reconstruction, validation |

## Decider Pattern

**Interface**: `dk.trustworks.essentials.components.kotlin.eventsourcing.Decider<COMMAND, EVENT>`

Pure functional, slice-based, ideal for Event Modeling. Returns single event (or none).
The `handle` method receives:
- **`command`**: The command to process
- **`events`**: The complete event history for this aggregate (loaded from the EventStore).

```kotlin
interface Decider<COMMAND, EVENT> {
    fun handle(cmd: COMMAND, events: List<EVENT>): EVENT?
    fun canHandle(cmd: Any): Boolean
}
```

### Return Values

| Return | Meaning | Use Case |
|--------|---------|----------|
| `EVENT` | Success, persist event | Command produced new event |
| `null` | No-op | Idempotent handling |
| Throw | Rejection | Invalid state/business rule |

### Implementation

```kotlin
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

### Why Single Event?

Forces explicit event modeling instead of implementation steps:

| Anti-pattern | Correct Pattern |
|--------------|-----------------|
| `CreateOrder` → `OrderCreated` + `ProductsAdded` | `OrderWithProductsPlaced` |
| `RegisterCustomer` → `CustomerCreated` + `AddressAdded` | `CustomerRegistered` |

**Benefits**: Clear intent, no event reuse, simpler evolution, Event Storming alignment.

> 💡 Multiple events needed? Consider modeling business outcomes, not implementation steps.

For Java patterns supporting multiple events, see [eventsourced-aggregates](./LLM-eventsourced-aggregates.md).

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
    }
}
```

**Left-fold pattern**: `null → Event[0] → STATE? → Event[1] → STATE? → ... → Final STATE`

### Helper Methods

Extract and deserialize events from `AggregateEventStream`:

```kotlin
// Lazy evaluation (Sequence)
val events: Sequence<OrderEvent> = Evolver.extractEvents<OrderEvent>(stream)

// Eager evaluation (List)
val events: List<OrderEvent> = Evolver.extractEventsAsList<OrderEvent>(stream)
```

Benefits: Auto-deserialization, type filtering, cleaner than manual `aggregateEventStream.events().map { it.event().deserialize<T>() }`.

### State Class (Immutable)

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

### Evolver Implementation

```kotlin
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

### Using Evolver in Decider

```kotlin
class ConfirmOrderDecider : Decider<ConfirmOrder, OrderEvent> {
    private val evolver = OrderStateEvolver()

    override fun handle(cmd: ConfirmOrder, events: List<OrderEvent>): OrderEvent? {
        val state = Evolver.applyEvents(evolver, null, events)

        if (state == null) throw RuntimeException("Order does not exist")
        if (state.status == OrderStatus.CONFIRMED) return null // Idempotent
        if (!state.canBeConfirmed())
            throw RuntimeException("Cannot confirm order in ${state.status}")

        return OrderConfirmed(cmd.id)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is ConfirmOrder
}
```

## CommandBus Integration

### Infrastructure Responsibilities

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
AggregateTypeConfiguration(
    aggregateType = AggregateType.of("Orders"),
    aggregateIdType = OrderId::class.java,
    aggregateIdSerializer = AggregateIdSerializer.serializerFor(OrderId::class.java),

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
| `aggregateIdSerializer` | Serializer | `AggregateIdSerializer.serializerFor(...)` |
| `deciderSupportsAggregateTypeChecker` | Decider filter | Check if cmd inherits from `OrderCommand` |
| `commandAggregateIdResolver` | Extract ID from cmd | `{ (it as OrderCommand).id }` |
| `eventAggregateIdResolver` | Extract ID from event | `{ (it as OrderEvent).id }` |

### Spring Boot Wiring

Register a `DeciderAndAggregateTypeConfigurator` which auto-wires `AggregateTypeConfiguration`s and their deciders together.

```kotlin
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
            aggregateIdSerializer = AggregateIdSerializer.serializerFor(OrderId::class.java),
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

> 💡 Deciders can use `@Component`/`@Service` instead of `@Bean` methods.

### Sending Commands

Note: Assumes the `CommandBus` is configured with the `UnitOfWorkControllingCommandBusInterceptor`.

```kotlin
@Service
class OrderService(
    private val commandBus: CommandBus,
    private val unitOfWorkFactory: UnitOfWorkFactory
) {
    fun createOrder(orderId: OrderId, customerId: CustomerId): Boolean {
        val event = commandBus.send(CreateOrder(orderId, customerId)) as OrderEvent?
        event != null // false if already exists
    }

    fun confirmOrder(orderId: OrderId) {
        unitOfWorkFactory.usingUnitOfWork {
            commandBus.send(ConfirmOrder(orderId))
        }
    }
}
```

## GivenWhenThenScenario Testing

**Class**: `dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario`

### Benefits

| Benefit | Why |
|---------|-----|
| No database | Pure function tests |
| No Spring | Millisecond execution |
| No mocking | No dependencies |
| Deterministic | Same input = same output |
| Readable | Business language |

### Test Patterns

```kotlin
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

### Assertion Exceptions

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
// Recommended: Using Evolver.extractEventsAsList helper
fun getOrderState(orderId: OrderId): OrderState? {
    val eventStream = eventStore.fetchStream(AGGREGATE_TYPE, orderId)
    if (!eventStream.isPresent) return null

    val events = Evolver.extractEventsAsList<OrderEvent>(eventStream.get())
    return Evolver.applyEvents(OrderStateEvolver(), null, events)
}

// Alternative: Manual deserialization (more verbose)
fun getOrderStateManual(orderId: OrderId): OrderState? {
    val eventStream = eventStore.fetchStream(AGGREGATE_TYPE, orderId)
    if (!eventStream.isPresent) return null

    val events = eventStream.get()
        .events()
        .map { it.event().deserialize<OrderEvent>() }
        .toList()

    return Evolver.applyEvents(OrderStateEvolver(), null, events)
}
```

## Command/Event Design

```kotlin
// Commands (sealed for exhaustiveness)
sealed interface OrderCommand {
    val id: OrderId
}

data class CreateOrder(
    override val id: OrderId,
    val customerId: CustomerId
) : OrderCommand

data class AcceptOrder(override val id: OrderId) : OrderCommand
data class ShipOrder(override val id: OrderId) : OrderCommand

// Events (sealed for exhaustiveness)
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

## Kotlin vs Java Patterns

| Aspect | Kotlin `Decider` | Java `EventStreamDecider` |
|--------|------------------|---------------------------|
| Language | Kotlin data classes, null safety | Java records, Optional |
| Return | `EVENT?` | `Optional<EVENT>` |
| Event stream | `List<EVENT>` | `List<EVENT>` |
| Testing | `GivenWhenThenScenario` (Kotlin) | `GivenWhenThenScenario` (Java) |
| Config | `AggregateTypeConfiguration` | `EventStreamAggregateTypeConfiguration` |
| Adapter | `DeciderCommandHandlerAdapter` | `EventStreamDeciderCommandHandlerAdapter` |

For Java patterns or OOP aggregates, see [eventsourced-aggregates](./LLM-eventsourced-aggregates.md).

## Common Patterns

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
// ✅ Use Evolver for complex validation
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

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function-names that are used with **String concatenation** → SQL injection risk. 
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

⚠️ **Sanitize input**: `AggregateType` used in SQL string concatenation.

See [README Security](../components/kotlin-eventsourcing/README.md#security) for full details.

**Required actions**:
- Generate aggregate IDs with `RandomIdGenerator.generate()` or `UUID.randomUUID()`
- Never use unsanitized user input for aggregate IDs or types

## Key Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `Decider<COMMAND, EVENT>` | `kotlin.eventsourcing` | Command → event decision |
| `Evolver<EVENT, STATE>` | `kotlin.eventsourcing` | Event → state evolution |
| `AggregateTypeConfiguration` | `kotlin.eventsourcing` | Aggregate metadata config |
| `DeciderCommandHandlerAdapter` | `kotlin.eventsourcing` | CommandBus bridge |
| `DeciderAndAggregateTypeConfigurator` | `kotlin.eventsourcing` | Auto-wiring |
| `GivenWhenThenScenario` | `kotlin.eventsourcing.test` | Pure function testing |

## Dependencies

| Module | Why |
|--------|-----|
| [postgresql-event-store](./LLM-postgresql-event-store.md) | Event persistence |
| [reactive](./LLM-reactive.md) | CommandBus |
| [foundation](./LLM-foundation.md) | UnitOfWork |

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
