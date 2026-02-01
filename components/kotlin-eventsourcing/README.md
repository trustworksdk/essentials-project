# Essentials Components - Kotlin EventSourcing

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-kotlin-eventsourcing.md](../../LLM/LLM-kotlin-eventsourcing.md)

This library provides an **experimental** Kotlin-focused `Decider`/`Evolver` approach to event sourcing, designed to integrate with the [`EventStore`](../postgresql-event-store/README.md) and [`CommandBus`](../../reactive/README.md#commandbus).

## Table of Contents

- [Maven Dependency](#maven-dependency)
- ⚠️ [Security](#security)
- [Decider](#decider)
  - [Why Use Decider](#why-use-decider)
  - [Interface Definition](#interface-definition)
  - [Implementing a Decider](#implementing-a-decider)
  - [Why Single Event Return](#why-single-event-return)
- [Evolver](#evolver)
  - [Interface Definition](#interface-definition)
  - [State Class Example](#state-class-example)
  - [Evolver Implementation](#evolver-implementation)
  - [Using Evolver in Deciders](#using-evolver-in-deciders)
  - [Querying State from EventStore](#querying-state-from-eventstore)
- [Integration with CommandBus](#integration-with-commandbus)
  - [Command Handling Flow](#command-handling-flow)
  - [AggregateTypeConfiguration](#aggregatetypeconfiguration)
  - [Spring Boot Configuration](#spring-boot-configuration)
  - [Manual Wiring (without Spring)](#manual-wiring-without-spring)
  - [Sending Commands](#sending-commands)
- [GivenWhenThenScenario Testing](#givenwhenthenscenario-testing)
  - [Basic Scenarios](#basic-scenarios)
  - [Testing Failures](#testing-failures)
  - [Custom Assertions](#custom-assertions)
  - [Assertion Exceptions](#assertion-exceptions)
- [Comparison with Java Patterns](#comparison-with-java-patterns)

---

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>kotlin-eventsourcing</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependencies (`provided` scope):**
- `org.jetbrains.kotlin:kotlin-stdlib-jdk8`
- `org.jetbrains.kotlin:kotlin-reflect`

See [postgresql-event-store](../postgresql-event-store/README.md#maven-dependency) for additional EventStore dependencies.

---

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

Configuration parameters are used directly in SQL statements via string concatenation.

> ⚠️ **WARNING:** It is your responsibility to sanitize configuration values to prevent SQL injection.
> See the Components [Security](../README.md#security) section for more details.

### Parameters Requiring Sanitization

| Parameter | Description |
|-----------|-------------|
| `AggregateType` | Identifier used in SQL construction via string concatenation |

### Aggregate-Id Security

- Generate using secure methods (`RandomIdGenerator.generate()` or `UUID.randomUUID()`)
- Never contain unsanitized user input
- Use safe characters to prevent SQL injection

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

---

## `Decider`

> **NOTE:** API is experimental and subject to change!

**Interface:** `dk.trustworks.essentials.components.kotlin.eventsourcing.Decider`

A Kotlin-native functional approach to event sourcing that processes commands directly against event streams to produce new events.  
Ideal for **Event Modeling** and **slice-based implementation**.

The `Decider` pattern naturally supports **slice-based implementation** where each command handler is a separate, focused unit.  
This follows the Open/Closed Principle better: open for extension (add new commands), closed for modification (don't change existing handlers to add support for new commands).

### Why Use `Decider`

- **Kotlin-idiomatic**: Uses Kotlin features like data classes, sealed interfaces, and null safety
- **Event-centric**: Works directly with event streams, no state management
- **Functional**: Pure functions - same input always produces same output
- **Idempotent**: Safely callable multiple times
- **Slice-friendly**: Each decider handles one command type, supporting Open/Closed Principle
- **Easy testing**: Simple Given-When-Then testing

### Interface Definition

```kotlin
interface Decider<COMMAND, EVENT> {
    fun handle(cmd: COMMAND, events: List<EVENT>): EVENT?
    fun canHandle(cmd: Any): Boolean
}
```

### Implementing a Decider

The `handle` method receives:
- **`command`**: The command to process
- **`events`**: The complete event history for this aggregate (loaded from the EventStore). Use this to check current state, enforce invariants, or ensure idempotency.

Returns:
- **`EVENT`**: Command succeeded, persist this event
- **`null`**: Command handled but no event needed (idempotent/no-op)
- **Throw exception**: Command rejected due to invalid state

```kotlin
class CreateOrderDecider : Decider<CreateOrder, OrderEvent> {

    override fun handle(cmd: CreateOrder, events: List<OrderEvent>): OrderEvent? {
        // Idempotency check
        if (events.any { it is OrderCreated }) {
            return null // Already created
        }

        return OrderCreated(cmd.id)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is CreateOrder
}

class ShipOrderDecider : Decider<ShipOrder, OrderEvent> {

    override fun handle(cmd: ShipOrder, events: List<OrderEvent>): OrderEvent? {
        if (events.isEmpty()) {
            throw RuntimeException("Cannot ship an order that hasn't been created")
        }

        if (events.any { it is OrderShipped }) {
            return null // Already shipped - idempotent
        }

        if (!events.any { it is OrderAccepted }) {
            throw RuntimeException("Cannot ship an order that hasn't been accepted")
        }

        return OrderShipped(cmd.id)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is ShipOrder
}
```

### Why Single Event Return

The Kotlin `Decider` intentionally returns `EVENT?` (at most one event) rather than a list. This encourages **explicit event modeling**:

| Instead of... | Model explicitly as... |
|---------------|------------------------|
| `CreateOrderWithProducts` -> `OrderCreated` + `ProductsAdded` | `OrderWithProductsPlaced` (single event) |
| `RegisterCustomer` -> `CustomerCreated` + `AddressAdded` | `CustomerRegistered` (includes address) |

**Benefits:**
- **Clear intent**: Each event represents one complete business fact
- **No event reuse across commands**: `ProductsAdded` from `AddProductsToOrder` is different from products included at order creation
- **Simpler evolution**: Events capture what happened, not implementation details
- **Better Event Storming alignment**: One command → one event (or none)

> 💡 If you find yourself wanting to return multiple events, consider whether you're modeling implementation steps rather than business outcomes.

For Java patterns that support multiple events, see the [Decider](../eventsourced-aggregates/README.md#decider-pattern) pattern in eventsourced-aggregates.

**Test reference:** [`GivenWhenThenScenarioTest`](src/test/kotlin/dk/trustworks/essentials/components/kotlin/eventsourcing/test/GivenWhenThenScenarioTest.kt)

---

## `Evolver`

**Interface:** `dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver`

A functional interface for deriving state by applying events sequentially (left-fold pattern).

The `Evolver` reconstructs state by folding over events sequentially:

```
Evolver.applyEvents(evolver, null, events):

  null  ->  Event[0]  ->  STATE?  ->  Event[1]  ->  STATE?  -> ... -> Final STATE
                |                         |
          applyEvent()              applyEvent()
```

### Interface Definition

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

### State Class Example

Follows the immutable state pattern:
- State is a Kotlin `data class` (inherently immutable)
- `copy()` method returns **new instances** with updated values

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

enum class OrderStatus { CREATED, CONFIRMED, SHIPPED, CANCELLED }
```

### Evolver Implementation

```kotlin
class OrderStateEvolver : Evolver<OrderEvent, OrderState> {

    override fun applyEvent(event: OrderEvent, state: OrderState?): OrderState? {
        return when (event) {
            // First event creates initial state
            is OrderCreated -> OrderState.created(event.id)

            // Subsequent events transform state using copy()
            is OrderConfirmed -> state?.copy(status = OrderStatus.CONFIRMED)
            is OrderShipped -> state?.copy(status = OrderStatus.SHIPPED)
            is OrderCancelled -> state?.copy(
                status = OrderStatus.CANCELLED,
                cancelReason = event.reason
            )

            // Unknown events - state unchanged
            else -> state
        }
    }
}
```

### Helper Methods for Event Extraction

The `Evolver` companion object provides helper methods to extract and deserialize events from an `AggregateEventStream`:

```kotlin
// Extract events as a Sequence (lazy evaluation)
val eventsSequence: Sequence<OrderEvent> = Evolver.extractEvents<OrderEvent>(eventStream)

// Extract events as a List (eager evaluation)
val eventsList: List<OrderEvent> = Evolver.extractEventsAsList<OrderEvent>(eventStream)
```

These methods:
- Automatically deserialize persisted events to their runtime type
- Filter events by the specified type using Kotlin's reified type parameters
- Provide a cleaner alternative to manual `.events().map { it.event().deserialize<T>() }.toList()`

### Using `Evolver` in Deciders

For commands that need to validate against current state, use `Evolver` to reconstruct the aggregate state from its event history:

```kotlin
class ConfirmOrderDecider : Decider<ConfirmOrder, OrderEvent> {

    private val evolver = OrderStateEvolver()

    override fun handle(cmd: ConfirmOrder, events: List<OrderEvent>): OrderEvent? {
        val state = Evolver.applyEvents(evolver, 
                                        null,   // Initial state is null
                                        events)

        if (state == null) {
            throw RuntimeException("Order does not exist")
        }

        if (state.status == OrderStatus.CONFIRMED) {
            return null // Idempotent
        }

        if (!state.canBeConfirmed()) {
            throw RuntimeException("Cannot confirm order in status: ${state.status}")
        }

        return OrderConfirmed(cmd.id)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is ConfirmOrder
}
```

### Querying State from EventStore

```kotlin
fun getOrderState(orderId: OrderId): OrderState? {
    val eventStream = eventStore.fetchStream(AGGREGATE_TYPE, orderId)

    if (!eventStream.isPresent) {
        return null
    }

    // Using Evolver.extractEventsAsList helper (recommended)
    val events = Evolver.extractEventsAsList<OrderEvent>(eventStream.get())
    return Evolver.applyEvents(OrderStateEvolver(), null, events)
}
```

**Alternative - Manual Deserialization:**

```kotlin
fun getOrderState(orderId: OrderId): OrderState? {
    val eventStream = eventStore.fetchStream(AGGREGATE_TYPE, orderId)

    if (!eventStream.isPresent) {
        return null
    }

    // Manual approach - more verbose
    val events = eventStream.get()
        .events()
        .map { it.event().deserialize<OrderEvent>() }
        .toList()

    return Evolver.applyEvents(OrderStateEvolver(), null, events)
}
```

---

## Integration with CommandBus

While `Decider` implementations are pure functions, they need infrastructure for:

| Concern | What the infrastructure handles |
|---------|--------------------------------|
| **Command Routing** | Routes incoming commands to the correct decider based on `canHandle()` |
| **Aggregate ID Resolution** | Extracts aggregate IDs from commands/events for stream lookup |
| **Event Loading** | Automatically loads the aggregate's event history from the `EventStore` before calling `handle()` |
| **Event Persistence** | Persists the resulting event (if any) to the `EventStore` after `handle()` returns |
| **Transaction Management** | Ensures all operations occur within a `UnitOfWork` for consistency |

To wire `Decider` implementations with the command handling infrastructure, you need three components:

| Component | Purpose                                                                                                       |
|-----------|---------------------------------------------------------------------------------------------------------------|
| `AggregateTypeConfiguration` | Defines how to extract aggregate IDs from commands/events and which deciders support the given `AggregateType` |
| `DeciderCommandHandlerAdapter` | Bridges deciders with the `CommandBus`, handling event loading/persistence                                    |
| `DeciderAndAggregateTypeConfigurator` | Auto-wires `AggregateTypeConfiguration`s and deciders together                                                |

### Command Handling Flow

```
CommandBus.send(command)
    |
DeciderCommandHandlerAdapter.handle(command)
    |
1. Extract aggregateId from command
2. Load event stream from EventStore
3. decider.handle(command, events) -> EVENT?
4. If event returned: persist to EventStore
    |
Return event (or null if idempotent)
```

### `AggregateTypeConfiguration`

**Class:** `dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration`

The configuration tells the infrastructure how to work with your aggregate type. Think of it as answering these questions:

| Question | Parameter | Example |
|----------|-----------|---------|
| What's this aggregate called? | `aggregateType` | `AggregateType.of("Orders")` |
| What type is the aggregate ID? | `aggregateIdType` | `OrderId::class.java` |
| How do we serialize the ID? | `aggregateIdSerializer` | `AggregateIdSerializer.serializerFor(OrderId::class.java)` |
| Which deciders handle this aggregate? | `deciderSupportsAggregateTypeChecker` | Check if command inherits from `OrderCommand` |
| How do we get the ID from a command? | `commandAggregateIdResolver` | `{ cmd -> (cmd as OrderCommand).id }` |
| How do we get the ID from an event? | `eventAggregateIdResolver` | `{ e -> (e as OrderEvent).id }` |

> **Note:** The `commandAggregateIdResolver` may return `null` for "create" commands where the ID is generated. In this case, the infrastructure uses `eventAggregateIdResolver` to get the ID from the resulting event.

```kotlin
AggregateTypeConfiguration(
    aggregateType = AggregateType.of("Orders"),
    aggregateIdType = OrderId::class.java,
    aggregateIdSerializer = AggregateIdSerializer.serializerFor(OrderId::class.java),

    // Deciders that handle commands inheriting from OrderCommand
    deciderSupportsAggregateTypeChecker = DeciderSupportsAggregateTypeChecker
        .HandlesCommandsThatInheritsFromCommandType(OrderCommand::class),

    // Extract aggregate ID from command (null OK for create commands)
    commandAggregateIdResolver = { cmd -> (cmd as OrderCommand).id },

    // Extract aggregate ID from event (fallback when command ID is null)
    eventAggregateIdResolver = { e -> (e as OrderEvent).id }
)
```

### Spring Boot Configuration

The recommended approach is to define each `AggregateTypeConfiguration` and `Decider` as separate `@Bean`s. Spring automatically collects all beans of these types and injects them into the configurator.

```kotlin
@Configuration
class OrdersConfiguration {
    companion object {
        @JvmStatic
        val AGGREGATE_TYPE = AggregateType.of("Orders")
    }

    /**
     * Bridges CommandBus, EventStore, configurations, and deciders.
     */
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

    @Bean
    fun createOrderDecider() = CreateOrderDecider()

    @Bean
    fun acceptOrderDecider() = AcceptOrderDecider()

    @Bean
    fun shipOrderDecider() = ShipOrderDecider()
}
```

> **Tip:** You can also annotate deciders with `@Component` or `@Service` instead of defining `@Bean` methods - Spring will auto-discover them.

### Manual Wiring (without Spring)

```kotlin
val orderConfig = AggregateTypeConfiguration(
    aggregateType = AggregateType.of("Orders"),
    aggregateIdType = OrderId::class.java,
    aggregateIdSerializer = AggregateIdSerializer.serializerFor(OrderId::class.java),
    deciderSupportsAggregateTypeChecker = DeciderSupportsAggregateTypeChecker
        .HandlesCommandsThatInheritsFromCommandType(OrderCommand::class),
    commandAggregateIdResolver = { cmd -> (cmd as OrderCommand).id },
    eventAggregateIdResolver = { e -> (e as OrderEvent).id }
)

val adapter = DeciderCommandHandlerAdapter(
    CreateOrderDecider(),
    orderConfig,
    eventStore
)

commandBus.addCommandHandler(adapter)
```

### Sending Commands

Note: Assumes the `CommandBus` is configured with the `UnitOfWorkControllingCommandBusInterceptor`.

```kotlin
@Service
class OrderService(
    private val commandBus: CommandBus,
    private val unitOfWorkFactory: UnitOfWorkFactory
) {

    /**
     * Creates an order idempotently - safe to call multiple times with the same orderId.
     * @param orderId The order ID (caller provides for idempotency)
     * @param customerId The customer placing the order
     * @return true if order was created, false if it already existed
     */
    fun createOrder(orderId: OrderId, customerId: CustomerId): Boolean {
          val event = commandBus.send(CreateOrder(orderId, customerId)) as OrderEvent?
          // event is null if order already exists (decider returned null)
          event != null
    }

    fun confirmOrder(orderId: OrderId) {
          commandBus.send(ConfirmOrder(orderId))
    }
}
```

---

## `GivenWhenThenScenario` Testing

**Class:** `dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario`

Because `Decider` implementations are **pure functions** (input → output, no side effects), they can be tested without any infrastructure:

| Benefit | Why it matters |
|---------|----------------|
| **No database required** | Tests run in milliseconds, not seconds |
| **No Spring context** | No slow application startup |
| **No mocking** | Deciders have no dependencies to mock |
| **Deterministic** | Same input always produces same output |
| **Readable** | Given-When-Then format matches business requirements |

The pattern mirrors how you'd describe the behavior:
- **Given** these past events (the aggregate's history)...
- **When** this command is received...
- **Then** expect this event (or no event, or an exception)

### Basic Scenarios

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
fun `Create an Order twice is idempotent`() {
    val scenario = GivenWhenThenScenario(CreateOrderDecider())
    val orderId = OrderId.random()

    scenario
        .given(OrderCreated(orderId))
        .when_(CreateOrder(orderId))
        .thenExpectNoEvent()
}

@Test
fun `Ship an accepted Order`() {
    val scenario = GivenWhenThenScenario(ShipOrderDecider())
    val orderId = OrderId.random()

    scenario
        .given(
            OrderCreated(orderId),
            OrderAccepted(orderId)
        )
        .when_(ShipOrder(orderId))
        .then_(OrderShipped(orderId))
}
```

### Testing Failures

```kotlin
@Test
fun `Cannot ship an Order that hasn't been accepted`() {
    val scenario = GivenWhenThenScenario(ShipOrderDecider())
    val orderId = OrderId.random()

    scenario
        .given(OrderCreated(orderId))
        .when_(ShipOrder(orderId))
        .thenFailsWithExceptionType(RuntimeException::class)
}

@Test
fun `Cannot accept an Order that hasn't been created`() {
    val scenario = GivenWhenThenScenario(AcceptOrderDecider())
    val orderId = OrderId.random()

    scenario
        .given() // No events
        .when_(AcceptOrder(orderId))
        .thenFailsWithException(
            RuntimeException("Cannot accept an order that hasn't been created")
        )
}
```

### Custom Assertions

```kotlin
@Test
fun `Order creation includes timestamp`() {
    val scenario = GivenWhenThenScenario(CreateOrderDecider())
    val orderId = OrderId.random()
    val beforeTest = OffsetDateTime.now()

    scenario
        .given()
        .when_(CreateOrder(orderId, customerId))
        .thenAssert { actualEvent ->
            assertThat(actualEvent).isNotNull
            assertThat(actualEvent).isInstanceOf(OrderCreated::class.java)

            val created = actualEvent as OrderCreated
            assertThat(created.orderId).isEqualTo(orderId)
            assertThat(created.occurredAt)
                .isAfter(beforeTest)
                .isBefore(beforeTest.plusSeconds(1))
        }
}
```

### Assertion Exceptions

All assertion failures throw subclasses of `AssertionException`:

| Exception | Condition |
|-----------|-----------|
| `NoCommandProvidedException` | `when_()` not called before `then_()` |
| `DidNotExpectAnEventException` | Expected `null`, got an event |
| `ExpectedAnEventButDidGetAnyEventException` | Expected an event, got `null` |
| `ActualAndExpectedEventsAreNotEqualExcepted` | Events don't match |
| `ExpectToFailWithAnExceptionButNoneWasThrown` | Expected exception, none thrown |
| `ActualExceptionIsNotEqualToExpectedException` | Wrong exception thrown |

**Test reference:** [`GivenWhenThenScenarioTest`](src/test/kotlin/dk/trustworks/essentials/components/kotlin/eventsourcing/test/GivenWhenThenScenarioTest.kt)

---

## Comparison with Java Patterns

This module is conceptually similar to the Java [`EventStreamDecider`](../eventsourced-aggregates/README.md#eventstreamdecider) pattern but with Kotlin-specific features:

| Aspect | Kotlin `Decider` | Java `EventStreamDecider` |
|--------|------------------|---------------------------|
| **Language** | Kotlin-native (data classes, null safety) | Java (Optional, records) |
| **Return type** | `EVENT?` (nullable) | `Optional<EVENT>` |
| **Event stream** | `List<EVENT>` | `List<EVENT>` |
| **Test framework** | `GivenWhenThenScenario` (Kotlin) | `GivenWhenThenScenario` (Java) |
| **Configuration** | `AggregateTypeConfiguration` | `EventStreamAggregateTypeConfiguration` |
| **Adapter** | `DeciderCommandHandlerAdapter` | `EventStreamDeciderCommandHandlerAdapter` |

For OOP-style aggregates or typed error handling, see the Java [eventsourced-aggregates](../eventsourced-aggregates/README.md) module.
