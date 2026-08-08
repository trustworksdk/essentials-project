# Essentials Components - EventSourced Aggregates

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-eventsourced-aggregates.md](../../LLM/LLM-eventsourced-aggregates.md)

This library provides multiple flavors of Event-Sourced Aggregates designed to work with the [`EventStore`](../postgresql-event-store/README.md).

## Table of Contents

- [Maven Dependency](#maven-dependency)
- [Choosing an Aggregate Pattern](#choosing-an-aggregate-pattern)
- ⚠️ [Security](#security)
  - [Aggregate-Id Security](#aggregate-id-security)
- [Modern AggregateRoot](#modern-aggregateroot)
  - [With Separate State Object](#with-separate-state-object-withstate)
  - [Without Separate State Object](#without-separate-state-object)
- [StatefulAggregateRepository](#statefulaggregaterepository)
- [EventStreamDecider](#eventstreamdecider)
  - [EventStreamEvolver](#eventstreamevolver)
  - [GivenWhenThenScenario Testing](#givenwhenthenscenario-testing)
    - [Assertion Exceptions](#assertion-exceptions)
- [Decider Pattern](#decider-pattern)
- [Aggregate Snapshots](#aggregate-snapshots)
  - [Policy-Driven Snapshotting](#policy-driven-snapshotting)
  - [Snapshot Execution Modes](#snapshot-execution-modes)
  - [Durable Async Snapshots](#durable-async-snapshots)
  - [Spring Boot Configuration](#spring-boot-configuration)
- [Closing the Books](#closing-the-books)
  - [Generations](#generations)
  - [Declaring a Closing-Books Policy](#declaring-a-closing-books-policy)
  - [Decision Policies](#decision-policies)
  - [Repositories and the Coordinator](#repositories-and-the-coordinator)
  - [Scheduled Scans](#scheduled-scans)
  - [Archiving Closed Generations](#archiving-closed-generations)
  - [Closing-Books Spring Boot Configuration](#closing-books-spring-boot-configuration)
- [In-Memory Projections](#in-memory-projections)
- [Other Aggregate Patterns](#other-aggregate-patterns)
  - [FlexAggregate](#flexaggregate)
  - [Classic AggregateRoot](#classic-aggregateroot)

---

## Maven Dependency

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>eventsourced-aggregates</artifactId>
    <version>${essentials.version}</version>
</dependency>
```
Please see the [Event Store](../postgresql-event-store/README.md#maven-dependency) documentation for more details on other `provided` scope dependencies.

---

## Choosing an Aggregate Pattern

| Pattern | State | Complexity | Testing | Best For |
|---------|-------|------------|---------|----------|
| **[EventStreamDecider](#eventstreamdecider)** | Immutable, Event stream-based | Low | Given-When-Then | Event Modeling, slicing, functional teams |
| **[Decider](#decider-pattern)** | Immutable, external | High | Result types | Enterprise systems, sophisticated error handling |
| **[Modern AggregateRoot](#modern-aggregateroot)** | Mutable, internal | Low | Unit tests | OOP teams, Spring Boot integration |
| **[Modern + WithState](#with-separate-state-object-withstate)** | Mutable, separated | Low-Medium | Separated testing | Complex aggregates needing maintainability |
| **[Classic AggregateRoot](#classic-aggregateroot)** | Mutable, internal | Low | Unit tests | Legacy/existing projects |
| **[Classic + WithState](#classic-aggregateroot)** | Mutable, separated | Low-Medium | Separated testing | Legacy projects with complex state |
| **[FlexAggregate](#flexaggregate)** | Immutable, internal | Medium | Functional | Explicit state control, FP teams |

> **StatefulAggregate patterns:** 
> - Modern `AggregateRoot` and Classic `AggregateRoot` (with or without separate **state**) all implement `StatefulAggregate` and use the same [`StatefulAggregateRepository`](#statefulaggregaterepository) for persistence.

### Aggregate Type Hierarchy

```
                              ┌─────────────────────────────────┐                                ╔══════════════════════════════════════════════════════════════════════════════════╗
                              │      Aggregate<ID, TYPE>        │  ← Base interface              ║  Functional Patterns (no aggregate base class)                                   ║ 
                              │  aggregateId(), rehydrate()     │                                ╠══════════════════════════════════════════════════════════════════════════════════╣ 
                              └────────────────┬────────────────┘                                ║                                         │                                        ║ 
                                               │                                                 ║  EventStreamDecider<COMMAND, EVENT>     │  Decider<CMD, EVENT, ERROR, STATE>     ║ 
                  ┌────────────────────────────┴────────────────────────────┐                    ║  ├─ handle(cmd, events) → Optional      │  ├─ handle(cmd, state) → HandlerResult ║ 
                  │                                                         │                    ║  └─ canHandle(cmdClass)                 │  ├─ applyEvent(event, state) → STATE   ║ 
                  ▼                                                         ▼                    ║                                         │  └─ initialState() → STATE             ║ 
┌─────────────────────────────────────┐               ┌─────────────────────────────────┐        ║                                         │                                        ║ 
│ StatefulAggregate<ID, EVENT, TYPE>  │               │    FlexAggregate<ID, TYPE>      │        ║  Uses: EventStreamDeciderCommandHandler │  Uses: CommandHandler                  ║ 
│  getUncommittedChanges()            │               │    Command methods return       │        ║        Adapter + CommandBus             │        .deciderBasedCommandHandler()   ║ 
│  markChangesAsCommitted()           │               │  EventsToPersist<ID, Object>    │        ║                                         │                                        ║ 
└──────────────────┬──────────────────┘               └─────────────────────────────────┘        ╚══════════════════════════════════════════════════════════════════════════════════╝ 
                   │                                      Uses: FlexAggregateRepository          
                   │
      ┌────────────┴────────────┐
      │                         │
      ▼                         ▼
┌───────────────────┐   ┌───────────────────┐
│    <modern>       │   │    <classic>      │
│  AggregateRoot    │   │   AggregateRoot   │
│  (recommended)    │   │    (legacy)       │
├───────────────────┤   ├───────────────────┤
│   Any event type  │   │ Events extend     │
│                   │   │ Event base class  │
└─────────┬─────────┘   └─────────┬─────────┘
          │                       │
          ▼                       ▼
   ┌──────────────┐        ┌──────────────┐
   │  + WithState │        │  + WithState │    ← Optional: separate state class
   │  (optional)  │        │  (optional)  │
   └──────────────┘        └──────────────┘

          Both use: StatefulAggregateRepository
```

### Quick Decision Guide

**Start with Modern AggregateRoot if:**
- You're new to event sourcing
- You prefer Object-Oriented Programming patterns
- You want automatic change tracking

**Use EventStreamDecider if:**
- You're adopting Event Modeling
- You want slice-based implementation
- You prefer functional, pure functions

**Use Decider if:**
- You're adopting Event Modeling
- You want slice-based implementation
- You need sophisticated error handling with Result types
- You want complete separation of concerns ()

---
## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function names that are used with **String concatenation** → SQL injection risk.
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

Configuration parameters are used directly in SQL statements via string concatenation.

> ⚠️ **WARNING:** It is your responsibility to sanitize configuration values to prevent SQL injection.
> See the Components [Security](../README.md#security) section for more details.

### Parameters Requiring Sanitization

| Parameter | Description                                                                                                                                         |
|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `AggregateType` | Used as aggregate type identifier and used in SQL construction using String concatenation                                                           |
| `snapshotTableName` | Defines PostgreSQL table for snapshot storage in `PostgresqlAggregateSnapshotRepository` and is used in SQL construction using String concatenation |

### Aggregate-Id Security

Aggregate IDs are used in SQL queries and must be generated securely:
- Use `RandomIdGenerator.generate()` or `UUID.randomUUID()`
- Never contain unsanitized user input
- Use safe characters to prevent SQL injection

### Mitigations

- `PostgresqlAggregateSnapshotRepository` and `EventStore` components calls `PostgresqlUtil#checkIsValidTableOrColumnName(String)` for basic validation
- This provides initial defense but does **NOT** guarantee complete SQL injection protection

### Developer Responsibility

- Derive values only from controlled, trusted sources
- Never use external/untrusted input for table names, `AggregateType`, or `Aggregate-Id` values
- Validate all configuration values during application startup

### What Validation Does NOT Protect Against

- SQL injection via **values** (use parameterized queries)
- Malicious input that passes naming conventions but exploits application logic
- Configuration loaded from untrusted external sources without additional validation
- Names that are technically valid but semantically dangerous
- WHERE clauses and raw SQL strings

**Bottom line:** Validation is a defense layer, not a security guarantee. Always use hardcoded names or thoroughly validated configuration.

---

## Modern `AggregateRoot` 

**Package:** `dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern`

A mutable `StatefulAggregate` design with automatic change tracking. Events are applied internally and tracked for persistence when the `UnitOfWork` commits.  
Implements `AggregateRoot` and supports `WithState`.
Supported by `StatefulAggregateRepository`

### Why Use Modern AggregateRoot

- **Familiar Object-Oriented Programming pattern** with automatic change tracking
- **IDE-friendly** with good code navigation
- **Integrates with Spring Boot** and dependency injection
- **Flexible state management** - embed **state** or separate it

### Without Separate State Object

State and event handlers live directly in the aggregate class.

```java
public class Order extends AggregateRoot<OrderId, OrderEvent, Order> {
    private Map<ProductId, Integer> productAndQuantity;
    private boolean accepted;

    // For snapshot deserialization
    public Order() {} 

    // Rehydration constructor
    public Order(OrderId orderId) {
        super(orderId);
    }

    // Business constructor
    public Order(OrderId orderId, CustomerId customerId, int orderNumber) {
        this(orderId);
        apply(new OrderEvent.OrderAdded(orderId, customerId, orderNumber));
    }

    // Business/Command method
    public void addProduct(ProductId productId, int quantity) {
        if (accepted) throw new IllegalStateException("Order is already accepted");
        apply(new OrderEvent.ProductAddedToOrder(aggregateId(), productId, quantity));
    }

    // Business/Command method
    public void accept() {
        if (accepted) return; // Idempotent
        apply(new OrderEvent.OrderAccepted(aggregateId()));
    }

    @EventHandler
    private void on(OrderEvent.OrderAdded e) {
        productAndQuantity = new HashMap<>();
    }

    @EventHandler
    private void on(OrderEvent.ProductAddedToOrder e) {
        productAndQuantity.merge(e.productId, e.quantity, Integer::sum);
    }

    @EventHandler
    private void on(OrderEvent.OrderAccepted e) {
        accepted = true;
    }
}
```

**Test reference:** [`OrderAggregateRootRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/modern/OrderAggregateRootRepositoryIT.java)

---

### With Separate State Object (`WithState`)

Separates aggregate state from business logic for better maintainability in complex aggregates.

```java
public class Order extends AggregateRoot<OrderId, OrderEvent, Order>
        implements WithState<OrderId, OrderEvent, Order, OrderState> {

    // Rehydration constructor
    public Order(OrderId orderId) {
        super(orderId);
    }

    // Business constructor
    public Order(OrderId orderId, CustomerId customerId, int orderNumber) {
        super(orderId);
        apply(new OrderEvent.OrderAdded(orderId, customerId, orderNumber));
    }
    
    // Business/Command method
    public void addProduct(ProductId productId, int quantity) {
        if (state().accepted) {
            throw new IllegalStateException("Order is already accepted");
        }
        apply(new OrderEvent.ProductAddedToOrder(aggregateId(), productId, quantity));
    }

    // Business/Command method
    public void accept() {
        if (state().accepted) return; // Idempotent
        apply(new OrderEvent.OrderAccepted(aggregateId()));
    }

    // Covariant return type for cleaner access
    @Override
    protected OrderState state() {
        return super.state();
    }
}
```

**State class with event handlers:**  
The state class is a regular Java class with event handlers that extends `AggregateState`.

```java
public class OrderState extends AggregateState<OrderId, OrderEvent, Order> {
    Map<ProductId, Integer> productAndQuantity;
    boolean accepted;

    @EventHandler
    private void on(OrderEvent.OrderAdded e) {
        productAndQuantity = new HashMap<>();
    }

    @EventHandler
    private void on(OrderEvent.ProductAddedToOrder e) {
        var existing = productAndQuantity.getOrDefault(e.productId, 0);
        productAndQuantity.put(e.productId, e.quantity + existing);
    }

    @EventHandler
    private void on(OrderEvent.OrderAccepted e) {
        accepted = true;
    }
}
```

**Test reference:** [`OrderAggregateRootWithStateRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/modern/with_state/OrderAggregateRootWithStateRepositoryIT.java)

---

## StatefulAggregateRepository

**Interface:** `dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository`

Repository for loading and persisting `StatefulAggregate` instances. Works with both:
- [Modern AggregateRoot](#modern-aggregateroot) (recommended `StatefulAggregate` design)
- [Classic AggregateRoot](#classic-aggregateroot) (legacy)

Integrates with [`EventStore`](../postgresql-event-store/README.md) and `UnitOfWork` for automatic change tracking.

### Creating a Repository

```java
var ordersRepository = StatefulAggregateRepository.from(
    eventStore,
    AggregateType.of("Orders"),
    StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
    Order.class
);

// With snapshot support
var ordersRepository = StatefulAggregateRepository.from(
    eventStore,
    AggregateType.of("Orders"),
    StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
    Order.class,
    snapshotRepository
);
```

> See [`AggregateType`](../postgresql-event-store/README.md#aggregatetype) in the postgresql-event-store documentation for naming conventions.

### Usage Pattern

```java
// Creating a new aggregate
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    var order = new Order(orderId, customerId, 1234);
    ordersRepository.save(order);
});

// Loading and modifying - changes auto-persist on commit
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    var order = ordersRepository.load(orderId);
    order.addProduct(anotherProductId, 1);
    order.accept();
    // Changes persist automatically when UnitOfWork commits
});

// Optional loading
var maybeOrder = unitOfWorkFactory.withUnitOfWork(
    unitOfWork -> ordersRepository.tryLoad(orderId)
);
```

### Persistence Flow

```
new Order(...) → repository.save(order) → unitOfWork.commit()
                          ↓
      Events tracked in aggregate.getUncommittedChanges()
                          ↓
      UnitOfWork commit → EventStore.appendToStream(...)
```

**Test references:**
- [`StatefulAggregateRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/stateful/StatefulAggregateRepositoryIT.java)
- [`TransactionalBehaviorIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/stateful/TransactionalBehaviorIT.java)

---

## `EventStreamDecider`

> **NOTE:** API is experimental and subject to change!

**Interface:** `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider`

A simplified functional approach to event sourcing that processes commands directly against event streams to produce new events.  
Ideal for **Event Modeling** and **slice-based implementation**.

The `Decider` pattern naturally supports **slice-based implementation** where each command handler is a separate, focused unit.  
This follows the Open/Closed Principle better: open for extension (add new commands), closed for modification (don't change existing handlers to add support for new commands).


### Why Use EventStreamDecider

- **Event-centric**: Works directly with event streams, no state management
- **Functional**: Pure functions - same input always produces same output
- **Idempotent**: Safely callable multiple times
- **Slice-friendly**: Each decider handles one command type, supporting Open/Closed Principle
- **Easy testing**: Simple Given-When-Then testing

### Implementing a Decider

The `handle` method receives:
- **`command`**: The command to process
- **`events`**: The complete event history for this aggregate (loaded from the EventStore). Use this to check current state, enforce invariants, or ensure idempotency.

Returns `Optional<EVENT>`:
- **`Optional.of(event)`**: Command succeeded, persist this event
- **`Optional.empty()`**: Command handled but no event needed (idempotent/no-op)
- **Throw exception**: Command rejected due to invalid state or business rule violation

**Why single event return?**

`EventStreamDecider` intentionally returns `Optional<EVENT>` (at most one event) rather than a list. This encourages **explicit event modeling**:

| Instead of... | Model explicitly as... |
|---------------|------------------------|
| `CreateOrderWithProducts` → `OrderCreated` + `ProductsAdded` | `OrderWithProductsPlaced` (single event capturing the full intent) |
| `RegisterCustomer` → `CustomerCreated` + `AddressAdded` | `CustomerRegistered` (includes address in payload) |

**Benefits:**
- **Clear intent**: Each event represents one complete business fact
- **No event reuse across commands**: `ProductsAdded` from `AddProductsToOrder` is different from products included at order creation
- **Simpler evolution**: Events capture what happened, not implementation details
- **Better Event Storming alignment**: One command → one event (or none)

> 💡 If you find yourself wanting to return multiple events, consider whether you're modeling implementation steps rather than business outcomes.

```java
public class CreateOrderDecider implements EventStreamDecider<CreateOrder, OrderEvent> {

    @Override
    public Optional<OrderEvent> handle(CreateOrder command, List<OrderEvent> events) {
        requireNonNull(command, "command cannot be null");
        requireNonNull(events, "events cannot be null");

        // Check if order exists (idempotency)
        boolean orderExists = events.stream()
            .anyMatch(e -> e instanceof OrderCreated);

        if (orderExists) {
            return Optional.empty(); // Idempotent - no new event
        }

        return Optional.of(new OrderCreated(command.orderId(), command.customerId()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return CreateOrder.class == command;
    }
}
```

### Using `EventStreamEvolver` for State Validation/Projection

For commands that need to validate against current state, use `EventStreamEvolver`:

```java
public class ConfirmOrderDecider implements EventStreamDecider<ConfirmOrder, OrderEvent> {

    private final OrderEvolver evolver = new OrderEvolver();

    @Override
    public Optional<OrderEvent> handle(ConfirmOrder command, List<OrderEvent> events) {
        OrderState currentState = EventStreamEvolver.applyEvents(evolver, events);

        if (currentState.isEmpty()) {
            throw new IllegalStateException("Cannot confirm order that does not exist");
        }

        var state = currentState.get();
        if (state.status() == OrderStatus.CONFIRMED) {
            return Optional.empty(); // Idempotent
        }

        if (!state.canBeConfirmed()) {
            throw new IllegalStateException("Cannot confirm order in status: " + state.status());
        }

        return Optional.of(new OrderConfirmed(command.orderId()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return ConfirmOrder.class == command;
    }
}
```

### `EventStreamEvolver`

**Interface:** `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamEvolver`

A functional interface for deriving state by applying events sequentially (left-fold pattern).

The `EventStreamEvolver` reconstructs state by folding over events sequentially:

```
EventStreamEvolver.applyEvents(evolver, events):

  Optional.empty()  →  Event[0]  →  Optional<State>  →  Event[1]  →  Optional<State>  → ... → Final State
                          ↓                                 ↓
                    applyEvent()                      applyEvent()
```

**State record example:**

Follows the immutable state pattern:
- State is a Java `record` (inherently immutable)
- `with...()` methods return **new instances** with updated values

```java
public record OrderState(
        OrderId orderId,
        CustomerId customerId,
        OrderStatus status,
        String cancellationReason
) {
    public static OrderState created(OrderId orderId, CustomerId customerId) {
        return new OrderState(orderId, customerId, OrderStatus.PENDING, null);
    }

    public OrderState withStatus(OrderStatus newStatus) {
        return new OrderState(orderId, customerId, newStatus, cancellationReason);
    }

    public OrderState withCancelled(String reason) {
        return new OrderState(orderId, customerId, OrderStatus.CANCELLED, reason);
    }

    public boolean canBeConfirmed() {
        return status == OrderStatus.PENDING;
    }

    public boolean canBeShipped() {
        return status == OrderStatus.CONFIRMED;
    }
}
```

**Evolver implementation:**

```java
// Usage: fold over event list/history to reconstruct current state
List<OrderEvent> events = List.of(
    new OrderCreated(orderId, customerId),
    new OrderConfirmed(orderId)
);
Optional<OrderState> state = EventStreamEvolver.applyEvents(new OrderStateEvolver(), events);
```

**How the example works:**
1. Starts with `Optional.empty()` (no state yet)
2. For each event in the history the `EventStreamEvolver` calls `applyEvent(event, currentState)`
3. Each call returns a **new** `Optional<STATE>` (immutable - never mutates existing state)
4. The first event (e.g., `OrderCreated`) creates the initial state: `Optional.of(new OrderState(...))`
5. Subsequent events transform state using `with...()` methods that return new instances

**Immutable state pattern:**
- State is a Java `record` (inherently immutable)
- `with...()` methods return **new instances** with updated values
- `currentState.map(s -> s.withStatus(...))` safely handles the `Optional` - if state doesn't exist, returns `Optional.empty()`

```java
public class OrderStateEvolver implements EventStreamEvolver<OrderEvent, OrderState> {

    @Override
    public Optional<OrderState> applyEvent(OrderEvent event, Optional<OrderState> currentState) {
        return switch (event) {
            // First event creates initial state
            case OrderCreated e -> Optional.of(
                OrderState.created(e.orderId(), e.customerId())
            );
            // Subsequent events transform existing state (returns new instance)
            case OrderConfirmed e -> currentState.map(s -> s.withStatus(OrderStatus.CONFIRMED));
            case OrderShipped e -> currentState.map(s -> s.withStatus(OrderStatus.SHIPPED));
            case OrderCancelled e -> currentState.map(s -> s.withCancelled(e.reason()));
            // Unknown events are ignored (state unchanged)
            default -> currentState;
        };
    }
}
```

**`EventStreamEvolver` with EventStore:**

```java
// `Extract events from persisted stream
Optional<OrderState> state = eventStore.fetchStream(AggregateType.of("Orders"), orderId)
    .flatMap(stream -> {
        List<OrderEvent> events = EventStreamEvolver.extractEventsAsList(stream, OrderEvent.class);
        return EventStreamEvolver.applyEvents(new OrderStateEvolver(), events);
    });
```

### Integration with CommandBus

While `EventStreamDecider` implementations are pure functions, they need infrastructure for:

| Concern | What the infrastructure handles                                                                   |
|---------|---------------------------------------------------------------------------------------------------|
| **Command Routing** | Routes incoming commands to the correct decider based on `canHandle()`                            |
| **Aggregate ID Resolution** | Extracts aggregate IDs from commands/events for stream lookup                                     |
| **Event Loading** | Automatically loads the aggregate's event history from the `EventStore` before calling `handle()` |
| **Event Persistence** | Persists the resulting event (if any) to the `EventStore` after `handle()` returns                |
| **Transaction Management** | Ensures all operations occur within a `UnitOfWork` for consistency                                |

To wire `EventStreamDecider` implementations with the command handling infrastructure, you need three components:

| Component | Purpose                                                                                                       |
|-----------|---------------------------------------------------------------------------------------------------------------|
| `EventStreamAggregateTypeConfiguration` | Defines how to extract aggregate IDs from commands/events and which deciders support the given aggregate type |
| `EventStreamDeciderCommandHandlerAdapter` | Bridges deciders with the `CommandBus`, handling event loading/persistence                                    |
| `EventStreamDeciderAndAggregateTypeConfigurator` | Auto-wires `EventStreamAggregateTypeConfiguration`s and deciders together                                     |

**Command Handling Flow:**

```
CommandBus.send(command)
    ↓
EventStreamDeciderCommandHandlerAdapter.handle(command)
    ↓
1. Extract aggregateId from command
2. Load event stream from EventStore
3. Find decider that canHandle(command)
4. decider.handle(command, events) → Optional<Event>
5. If event present: persist to EventStore
    ↓
Return event (or null if idempotent)
```

**EventStreamAggregateTypeConfiguration Parameters:**

The configuration tells the infrastructure how to work with your aggregate type. Think of it as answering these questions:

| Question | Parameter | Example |
|----------|-----------|---------|
| What's this aggregate called? | `aggregateType` | `AggregateType.of("Orders")` |
| What type is the aggregate ID? | `aggregateIdType` | `OrderId.class` |
| How do we serialize the ID? | `aggregateIdSerializer` | `AggregateIdSerializer.serializerFor(OrderId.class)` |
| Which deciders handle this aggregate? | `deciderSupportsAggregateTypeChecker` | Check if command extends `OrderCommand` |
| How do we get the ID from a command? | `commandAggregateIdResolver` | `cmd -> cmd.orderId()` |
| How do we get the ID from an event? | `eventAggregateIdResolver` | `event -> event.orderId()` |

> **Note:** The `commandAggregateIdResolver` may return `null` for "create" commands where the ID is generated. In this case, the infrastructure uses `eventAggregateIdResolver` to get the ID from the resulting event.

```java
new EventStreamAggregateTypeConfiguration(
    AggregateType.of("Orders"),
    OrderId.class,
    AggregateIdSerializer.serializerFor(OrderId.class),

    // Deciders that handle commands inheriting from OrderCommand
    new EventStreamDeciderSupportsAggregateTypeChecker
        .HandlesCommandsThatInheritFromCommandType(OrderCommand.class),

    // Extract aggregate ID from command (null OK for create commands)
    command -> ((OrderCommand) command).orderId(),

    // Extract aggregate ID from event (fallback when command ID is null)
    event -> ((OrderEvent) event).orderId()
)
```

**Spring Boot Configuration:**

The recommended approach is to define each `EventStreamAggregateTypeConfiguration` and `EventStreamDecider` as separate `@Bean`s. Spring automatically collects all beans of these types and injects them into the configurator.

```java
@Configuration
public class EventSourcingConfiguration {

    /**
     * Bridges CommandBus, EventStore, AggregateTypeConfigurations, and Deciders.
     * Spring auto-collects all EventStreamAggregateTypeConfiguration and
     * EventStreamDecider beans defined in the application.
     */
    @Bean
    public EventStreamDeciderAndAggregateTypeConfigurator deciderConfigurator(
            ConfigurableEventStore<?> eventStore,
            CommandBus commandBus,
            List<EventStreamAggregateTypeConfiguration> aggregateTypeConfigurations,
            List<EventStreamDecider<?, ?>> deciders) {
        return new EventStreamDeciderAndAggregateTypeConfigurator(
            eventStore,
            commandBus,
            aggregateTypeConfigurations,
            deciders
        );
    }

    // ==================== Orders Aggregate ====================

    @Bean
    public EventStreamAggregateTypeConfiguration ordersAggregateTypeConfiguration() {
        return new EventStreamAggregateTypeConfiguration(
            AggregateType.of("Orders"),
            OrderId.class,
            AggregateIdSerializer.serializerFor(OrderId.class),
            new EventStreamDeciderSupportsAggregateTypeChecker
                .HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
            command -> ((OrderCommand) command).orderId(),
            event -> ((OrderEvent) event).orderId()
        );
    }

    @Bean
    public CreateOrderDecider createOrderDecider() {
        return new CreateOrderDecider();
    }

    @Bean
    public ConfirmOrderDecider confirmOrderDecider() {
        return new ConfirmOrderDecider();
    }

    @Bean
    public ShipOrderDecider shipOrderDecider() {
        return new ShipOrderDecider();
    }

    // ==================== Customers Aggregate ====================

    @Bean
    public EventStreamAggregateTypeConfiguration customersAggregateTypeConfiguration() {
        return new EventStreamAggregateTypeConfiguration(
            AggregateType.of("Customers"),
            CustomerId.class,
            AggregateIdSerializer.serializerFor(CustomerId.class),
            new EventStreamDeciderSupportsAggregateTypeChecker
                .HandlesCommandsThatInheritFromCommandType(CustomerCommand.class),
            command -> ((CustomerCommand) command).customerId(),
            event -> ((CustomerEvent) event).customerId()
        );
    }

    @Bean
    public RegisterCustomerDecider registerCustomerDecider() {
        return new RegisterCustomerDecider();
    }

    @Bean
    public UpdateCustomerDecider updateCustomerDecider() {
        return new UpdateCustomerDecider();
    }
}
```

> **Tip:** You can also annotate your deciders with `@Component` or `@Service` instead of defining `@Bean` methods - Spring will auto-discover them.

**Sending Commands:**

Note: Assumes the `CommandBus` is configured with the `UnitOfWorkControllingCommandBusInterceptor`.

```java
@Service
public class OrderService {
    private final CommandBus commandBus;
    private final UnitOfWorkFactory unitOfWorkFactory;

    /**
     * Creates an order idempotently - safe to call multiple times with the same orderId.
     * @param orderId The order ID (caller provides for idempotency)
     * @param customerId The customer placing the order
     * @return true if order was created, false if it already existed
     */
    public boolean createOrder(OrderId orderId, CustomerId customerId) {
        OrderCreated event = (OrderCreated) commandBus.send(
            new CreateOrder(orderId, customerId)
        );
        // event is null if order already exists (decider returned Optional.empty())
        return event != null;
    }

    public void confirmOrder(OrderId orderId) {
        commandBus.send(new ConfirmOrder(orderId));
    }
}
```

**Manual Wiring (without Spring):**

```java
var orderConfig = new EventStreamAggregateTypeConfiguration(...);

var adapter = new EventStreamDeciderCommandHandlerAdapter(
    eventStore,
    orderConfig,
    List.of(new CreateOrderDecider(), new ConfirmOrderDecider())
);

commandBus.addCommandHandler(adapter);
```

### GivenWhenThenScenario Testing

**Class:** `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario`

Because `EventStreamDecider` implementations are **pure functions** (input → output, no side effects), they can be tested without any infrastructure:

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

```java
@Test
void shouldCreateOrder() {
    var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());
    var orderId = OrderId.random();
    var customerId = CustomerId.random();

    scenario
        .given() // No existing events
        .when(new CreateOrder(orderId, customerId))
        .then(new OrderCreated(orderId, customerId));
}

@Test
void shouldBeIdempotentWhenOrderExists() {
    var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());
    var orderId = OrderId.random();
    var customerId = CustomerId.random();

    scenario
        .given(new OrderCreated(orderId, customerId))
        .when(new CreateOrder(orderId, customerId))
        .thenExpectNoEvent();
}

@Test
void shouldFailForInvalidTransition() {
    var scenario = new GivenWhenThenScenario<>(new ShipOrderDecider());
    var orderId = OrderId.random();

    scenario
        .given(new OrderCreated(orderId, customerId)) // Not confirmed
        .when(new ShipOrder(orderId))
        .thenThrows(IllegalStateException.class);
}

@Test
void shouldCreateOrderWithCustomAssertions() {
    var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());
    var orderId = OrderId.random();
    var customerId = CustomerId.random();

    scenario
        .given()
        .when(new CreateOrder(orderId, customerId))
        .thenAssert(actualEvent -> {
            if (actualEvent == null) {
                throw new AssertionException("Expected an event but got null");
            }
            if (!(actualEvent instanceof OrderCreated)) {
                throw new AssertionException("Expected OrderCreated but got " + actualEvent.getClass());
            }
            var created = (OrderCreated) actualEvent;
            if (!created.orderId().equals(orderId)) {
                throw new AssertionException("Order ID mismatch");
            }
        });
}
```

**Test references:**
- [`EventStreamDeciderTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/eventstream/EventStreamDeciderTest.java)
- [`EventStreamDeciderIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/eventstream/EventStreamDeciderIT.java)

#### Assertion Exceptions

All assertion failures throw subclasses of `AssertionException`:

| Exception | Condition |
|-----------|-----------|
| `NoCommandProvidedException` | `when()` not called before `then()` |
| `DidNotExpectAnEventException` | Expected no event, got an event |
| `ExpectedAnEventButDidNotGetAnyEventException` | Expected an event, got `null` |
| `ActualAndExpectedEventsAreNotEqualException` | Events don't match |
| `ExpectedToFailWithAnExceptionButNoneWasThrownException` | Expected specific exception, none thrown |
| `ExpectedToFailWithAnExceptionTypeButNoneWasThrownException` | Expected exception type, none thrown |
| `ActualExceptionIsNotEqualToExpectedException` | Wrong exception thrown (type or message) |
| `ActualExceptionTypeIsNotEqualToExpectedException` | Wrong exception type thrown |
| `ActualExceptionMessageIsNotEqualToExpectedMessageException` | Exception message doesn't match |
| `FailedWithUnexpectedException` | Decider threw an unexpected exception |

---

## `Decider` Pattern

**Interface:** `dk.trustworks.essentials.components.eventsourced.aggregates.decider.Decider`

The `Decider` pattern is a **pure functional** approach to event sourcing inspired by functional programming and Domain-Driven Design. Unlike traditional aggregate designs where state, command handling, and event application are intertwined, `Decider` cleanly separates these concerns into distinct functions:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Decider<CMD, EVENT, ERROR, STATE>              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  handle(command, state) → HandlerResult<ERROR, EVENT>                       │
│      "Given current state, what events should this command produce?"        │
│                                                                             │
│  applyEvent(event, state) → STATE                                           │
│      "How does this event change the state?"                                │
│                                                                             │
│  initialState() → STATE                                                     │
│      "What's the starting state before any events?"                         │
│                                                                             │
│  isFinal(state) → boolean                                                   │
│      "Has this aggregate reached a terminal state?"                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

This separation makes the `Decider` highly testable, composable, and easy to reason about.

### Why Use Decider

- **Complete separation**: Command handling, state evolution, and initial state are separate concerns
- **Result types**: Return `HandlerResult<ERROR, EVENT>` instead of throwing exceptions
- **Return multiple events**: You want or need to return multiple events, which isn't supported by [EventStreamDecider](#eventstreamdecider) (see for more details)
- **Type-safe errors**: Define domain-specific error types
- **Enterprise-ready**: Integrates with snapshots and performance optimizations

### Structure

```java
public interface Decider<COMMAND, EVENT, ERROR, STATE>
    extends Handler<COMMAND, EVENT, ERROR, STATE>,
            StateEvolver<EVENT, STATE>,
            InitialStateProvider<STATE>,
            IsStateFinalResolver<STATE> {
}
```

### Example Decider

```java
public class OrderDecider implements Decider<OrderCommand, OrderEvent, OrderError, OrderState> {

    @Override
    public HandlerResult<OrderError, OrderEvent> handle(OrderCommand command, OrderState state) {
        return switch (command) {
            case AddProduct cmd -> {
                if (state.accepted()) {
                    yield HandlerResult.error(new OrderError.OrderAlreadyAccepted(state.orderId()));
                }
                yield HandlerResult.events(new ProductAddedToOrder(state.orderId(), cmd.productId(), cmd.quantity()));
            }
            case AcceptOrder cmd -> {
                if (state.accepted()) {
                    yield HandlerResult.events(); // Idempotent
                }
                if (!state.hasProducts()) {
                    yield HandlerResult.error(new OrderError.OrderHasNoProducts(state.orderId()));
                }
                yield HandlerResult.events(new OrderAccepted(state.orderId()));
            }
        };
    }

    @Override
    public OrderState applyEvent(OrderEvent event, OrderState state) {
        return switch (event) {
            case OrderAdded e -> OrderState.initial(e.orderId());
            case ProductAddedToOrder e -> state.withProduct(e.productId(), e.quantity());
            case OrderAccepted e -> state.withAccepted();
        };
    }

    @Override
    public OrderState initialState() {
        return OrderState.empty();
    }

    @Override
    public boolean isFinal(OrderState state) {
        return state.status() == OrderStatus.COMPLETED || state.status() == OrderStatus.CANCELLED;
    }
}
```

### Slice-Based Implementation (Open/Closed Principle)

The `Decider` pattern naturally supports **slice-based implementation** where each command handler is a separate, focused unit.  
This follows the Open/Closed Principle better: open for extension (add new commands), closed for modification (don't change existing handlers to add support for new commands). 
For full Open/Close principle support, see [EventStreamDecider](#eventstreamdecider).

**Approach 1: State-based delegation**

Each state variant handles its own commands. Adding new commands means adding new state handling, not modifying existing code:

```java
// State implements Handler - each state knows how to handle commands relevant to it
public sealed interface OrderState
        extends Handler<OrderCommand, OrderEvent, OrderError, OrderState> {

    // New order - only handles CreateOrder
    record NotCreated() implements OrderState {
        @Override
        public HandlerResult<OrderError, OrderEvent> handle(OrderCommand cmd, OrderState state) {
            if (cmd instanceof CreateOrder create) {
                return HandlerResult.events(new OrderCreated(create.orderId(), create.customerId()));
            }
            return HandlerResult.error(new OrderError.OrderNotFound(cmd.orderId()));
        }
    }

    // Active order - handles AddProduct, RemoveProduct, Accept
    record Active(OrderId orderId, Map<ProductId, Integer> products) implements OrderState {
        @Override
        public HandlerResult<OrderError, OrderEvent> handle(OrderCommand cmd, OrderState state) {
            return switch (cmd) {
                case AddProduct c -> HandlerResult.events(new ProductAdded(orderId, c.productId(), c.quantity()));
                case RemoveProduct c -> products.containsKey(c.productId())
                    ? HandlerResult.events(new ProductRemoved(orderId, c.productId()))
                    : HandlerResult.error(new OrderError.ProductNotInOrder(orderId, c.productId()));
                case AcceptOrder c -> products.isEmpty()
                    ? HandlerResult.error(new OrderError.OrderHasNoProducts(orderId))
                    : HandlerResult.events(new OrderAccepted(orderId));
                default -> HandlerResult.error(new OrderError.InvalidCommand(orderId, cmd));
            };
        }
    }

    // Accepted order - no more modifications allowed
    record Accepted(OrderId orderId) implements OrderState {
        @Override
        public HandlerResult<OrderError, OrderEvent> handle(OrderCommand cmd, OrderState state) {
            return HandlerResult.error(new OrderError.OrderAlreadyAccepted(orderId));
        }
    }
}

// Decider delegates to state
public class OrderDecider implements Decider<OrderCommand, OrderEvent, OrderError, OrderState> {
    @Override
    public HandlerResult<OrderError, OrderEvent> handle(OrderCommand cmd, OrderState state) {
        return state.handle(cmd, state);  // Delegate to state
    }

    // ... applyEvent, initialState, isFinal
}
```

**Approach 2: Composable command handlers**

Define individual handlers per command, then compose them:

```java
// Each slice handles one command type
@FunctionalInterface
interface CommandSlice<CMD, EVENT, ERROR, STATE> {
    HandlerResult<ERROR, EVENT> handle(CMD command, STATE state);
}

// Individual slices
CommandSlice<AddProduct, OrderEvent, OrderError, OrderState> addProductSlice =
    (cmd, state) -> state.accepted()
        ? HandlerResult.error(new OrderError.OrderAlreadyAccepted(state.orderId()))
        : HandlerResult.events(new ProductAdded(state.orderId(), cmd.productId(), cmd.quantity()));

CommandSlice<AcceptOrder, OrderEvent, OrderError, OrderState> acceptOrderSlice =
    (cmd, state) -> {
        if (state.accepted()) return HandlerResult.events(); // Idempotent
        if (!state.hasProducts()) return HandlerResult.error(new OrderError.OrderHasNoProducts(state.orderId()));
        return HandlerResult.events(new OrderAccepted(state.orderId()));
    };

// Compose in Decider
public HandlerResult<OrderError, OrderEvent> handle(OrderCommand cmd, OrderState state) {
    return switch (cmd) {
        case AddProduct c -> addProductSlice.handle(c, state);
        case AcceptOrder c -> acceptOrderSlice.handle(c, state);
        // Adding new command = add new slice + add case here
    };
}
```

> **Benefits:** Each slice is independently testable, focused on one responsibility, and can be developed/maintained by different team members. See [`DeciderTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/decider/DeciderTest.java) for a complete example.

### Error Types

The `Decider` pattern uses **typed errors** instead of exceptions. This is a fundamental design choice with significant benefits:

**Why typed errors over exceptions?**

| Aspect | Exceptions | Typed Errors (`HandlerResult<ERROR, EVENT>`) |
|--------|------------|----------------------------------------------|
| **Visibility** | Hidden in method signature | Explicit in return type - caller *must* handle |
| **Control flow** | Disrupts normal flow, can be caught anywhere | Normal return value, handled at call site |
| **Exhaustiveness** | No compile-time check for handling | `sealed interface` + pattern matching = compiler checks all cases |
| **Performance** | Stack trace creation is expensive | Regular object allocation |
| **Composition** | Try-catch nesting becomes complex | Functional composition with `fold()`, `map()`, etc. |

**When to use which:**
- **Typed errors**: Expected business rule violations (order already accepted, insufficient funds, invalid state transitions)
- **Exceptions**: Unexpected failures (database down, null pointer, programming errors)

**Defining error types with sealed interfaces:**

Using Java's `sealed interface` ensures the compiler knows all possible error cases, enabling exhaustive pattern matching:

```java
public sealed interface OrderError {
    record OrderAlreadyAccepted(OrderId orderId) implements OrderError {}
    record OrderHasNoProducts(OrderId orderId) implements OrderError {}
    record InvalidQuantity(ProductId productId, int quantity) implements OrderError {}
    record OrderNotFound(OrderId orderId) implements OrderError {}
}
```

**Handling errors:**

```java
var result = decider.handle(command, state);

// Pattern matching (exhaustive - compiler ensures all cases handled)
return switch (result) {
    case HandlerResult.Success<OrderError, OrderEvent> success ->
        persistEvents(success.events());
    case HandlerResult.Error<OrderError, OrderEvent> error ->
        switch (error.error()) {
            case OrderAlreadyAccepted e -> Response.conflict("Order already accepted");
            case OrderHasNoProducts e -> Response.badRequest("Order must have products");
            case InvalidQuantity e -> Response.badRequest("Invalid quantity: " + e.quantity());
            case OrderNotFound e -> Response.notFound("Order not found: " + e.orderId());
        };
};

// Or use fold() for functional style
result.fold(
    error -> handleError(error),
    events -> persistEvents(events)
);
```

### Wiring with CommandHandler

While the `Decider` is a pure function, it needs infrastructure to:

| Concern | What the infrastructure handles |
|---------|--------------------------------|
| **State Reconstruction** | Loads events from `EventStore`, applies them via `applyEvent()` to build current state |
| **Snapshot Optimization** | Optionally loads snapshot first, then only events since snapshot |
| **Event Persistence** | Persists resulting events to `EventStore` after successful `handle()` |
| **Transaction Management** | Ensures all operations occur within a `UnitOfWork` |
| **Aggregate ID Resolution** | Extracts aggregate IDs from commands/events for stream lookup |

**Command Handling Flow:**

```
CommandHandler.handle(command)
    ↓
1. Extract aggregateId from command
2. Load snapshot (if snapshotRepository provided)
3. Load events from EventStore (all or since snapshot)
4. Reconstruct state: initialState() → applyEvent(e1) → applyEvent(e2) → ... → currentState
5. decider.handle(command, currentState) → HandlerResult<ERROR, EVENT>
6. If success with events: persist to EventStore
7. If snapshot strategy triggers: save new snapshot
    ↓
Return HandlerResult (success with events, or error)
```

**Creating a CommandHandler:**

```java
var commandHandler = CommandHandler.deciderBasedCommandHandler(
    eventStore,                              // Event persistence
    AggregateType.of("Orders"),              // Logical aggregate name
    OrderId.class,                           // Aggregate ID type
    cmd -> Optional.of(cmd.orderId()),       // Extract ID from command
    event -> Optional.of(event.orderId()),   // Extract ID from event (fallback for Create commands without an ID)
    snapshotRepository,                      // Optional - null if no snapshots
    OrderState.class,                        // State class (also used for snapshot serialization)
    new OrderDecider()                       // Your decider implementation
);

// Usage within UnitOfWork
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    var result = commandHandler.handle(new AddProduct(orderId, productId, 2));
    result.fold(
        error -> handleError(error),
        events -> handleSuccess(events)
    );
});
```

**Test references:**
- [`DeciderTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/decider/DeciderTest.java)
- [`DeciderBasedCommandHandlerIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/decider/DeciderBasedCommandHandlerIT.java)

---

## Aggregate Snapshots

**Package:** `dk.trustworks.essentials.components.eventsourced.aggregates.snapshot`

In event sourcing, loading an aggregate means replaying all its events to reconstruct current state.  
For aggregates with hundreds or thousands of events, this becomes slow.  
**Snapshots** solve this by periodically saving the aggregate's state, so subsequent loads only need to replay events since the snapshot.

Supported by `StatefulAggregateRepository` and the `Decider`'s associated `CommandHandler`.

The module offers two ways of wiring snapshotting up. Both implement the same `AggregateSnapshotRepository`
SPI, so the repositories and command handlers that consume snapshots are unaffected by the choice:

| Approach | Entry point | Wiring | Use when |
|----------|-------------|--------|----------|
| **Manual** | `PostgresqlAggregateSnapshotRepository` | Construct the repository yourself and pass strategies explicitly | You wire everything by hand, or you already use this and don't need policies |
| **Policy-driven** | `@AggregateSnapshotPolicy` + `AsyncAggregateSnapshotRepository` / `DurableAsyncAggregateSnapshotRepository` on top of an `AggregateSnapshotStore` | Declared per aggregate class (or per `AggregateType` in Spring Boot properties) and resolved for you | You want per-aggregate policies, asynchronous snapshotting, or crash-safe snapshot jobs |

Key types in the policy-driven model:

| Type | Role |
|------|------|
| `AggregateSnapshotStore` | Storage abstraction — `loadSnapshot`, `loadAllSnapshots`, `findMostRecentLastIncludedEventOrder`, `saveSnapshot`, `deleteSnapshotsOlderThan`, `deleteAllSnapshots`. Postgres implementation: `PostgresqlAggregateSnapshotStore` |
| `AggregateSnapshotStateAdapter` | Converts between an aggregate instance and its serialized snapshot state. `DefaultAggregateSnapshotStateAdapter` instantiates aggregates via Objenesis, with a Jackson empty-JSON fallback |
| `AggregateSnapshotPolicyRegistry` | Holds the resolved `AggregateSnapshotPolicyDescriptor` per `AggregateType`. In-memory implementation: `InMemoryAggregateSnapshotPolicyRegistry` |
| `AggregateSnapshotRepositoryProvider` | Resolves the `AggregateSnapshotRepository` to use for a given `AggregateType` |

### Why Use Snapshots

| Scenario | Without Snapshots | With Snapshots |
|----------|-------------------|----------------|
| Order with 500 events | Load & replay 500 events | Load snapshot + replay ~50 events |
| Long-lived aggregate | Performance degrades over time | Consistent load performance |
| High-frequency updates | Each load replays entire history | Only recent events replayed |

### How Snapshots Work

**On Load (Rehydration):**

```
┌─────────────────────────────────────────────────────────────────────────────────────┐    
│                              Loading an Aggregate                                   │    
├─────────────────────────────────────────────────────────────────────────────────────┤    
│                                                                                     │    
│  1. Check for snapshot                                                              │    
│     └─→ Query: "What's the latest snapshot for aggregate X?"                        │    
│                                                                                     │    
│  2. If snapshot exists:                                                             │    
│     ├─→ Deserialize snapshot → AggregateState (at EventOrder N)                     │    
│     └─→ Load events WHERE eventOrder > N (only events AFTER snapshot)               │    
│                                                                                     │    
│  3. If no snapshot:                                                                 │    
│     └─→ Load ALL events from EventStore                                             │    
│                                                                                     │    
│  4. Rehydrate: Apply events to state (starting from snapshot state or initial state)│
│     └─→ state = applyEvent(e1, applyEvent(e2, ... applyEvent(eN, state)))           │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘

Example timeline:
    Events:    [E1] [E2] [E3] [E4] [E5] [E6] [E7] [E8] [E9] [E10]
    Snapshot:                       ↑ (captured at E5)
    On load:                        Load snapshot → replay [E6] [E7] [E8] [E9] [E10]
                                    (Only needs to load 5 events instead of 10)
```

**On Save (Persistence):**

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              Saving an Aggregate                                │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  1. Persist new events to EventStore                                            │
│     └─→ appendToStream(aggregateType, aggregateId, newEvents)                   │
│                                                                                 │
│  2. Check AddNewAggregateSnapshotStrategy                                       │
│     └─→ "Should we create a snapshot now?"                                      │
│         • updateWhenBehindByNumberOfEvents(100): Yes if 100+ events since last  │
│         • updateOnEachAggregateUpdate(): Yes, always                            │
│                                                                                 │
│  3. If strategy says yes:                                                       │
│     └─→ Serialize current aggregate state → Store as snapshot                   │
│                                                                                 │
│  4. Apply AggregateSnapshotDeletionStrategy                                     │
│     └─→ "Which old snapshots should we delete?"                                 │
│         • keepALimitedNumberOfHistoricSnapshots(3): Delete all but latest 3     │
│         • deleteAllHistoricSnapshots(): Delete all but the one just created     │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

**What gets stored in a snapshot:**

| Field | Description |
|-------|-------------|
| `aggregateType` | The type of aggregate (e.g., "Orders") |
| `aggregateId` | The specific aggregate instance ID |
| `eventOrder` | The event order when snapshot was taken |
| `aggregateState` | Serialized (JSON) representation of the aggregate's state |

### When to Use Snapshots

**Good candidates:**
- Aggregates with 100+ events on average
- High-frequency update aggregates (e.g., IoT sensors, trading)
- Aggregates where load latency matters

**Consider alternatives first:**
- **["Closing the Books" pattern](#closing-the-books)**: Periodically close the current event stream and start a new generation
- **Shorter aggregate lifecycles**: Design aggregates to complete/archive naturally
- **CQRS read models**: If reads are slow, optimize the read side instead

### Manual Configuration

```java
var snapshotRepository = new PostgresqlAggregateSnapshotRepository(
    eventStore,
    unitOfWorkFactory,
    jsonSerializer,
    AddNewAggregateSnapshotStrategy.updateWhenBehindByNumberOfEvents(100),
    AggregateSnapshotDeletionStrategy.keepALimitedNumberOfHistoricSnapshots(3)
);

// Combine with DelayedAddAndDeleteAggregateSnapshotDelegate for async snapshot processing (otherwise snapshots are created and deleted synchronously - in transaction)
var asyncSnapshot = DelayedAddAndDeleteAggregateSnapshotDelegate.delegateTo(snapshotRepository);

var ordersRepository = StatefulAggregateRepository.from(
    eventStore,
    AggregateType.of("Orders"),
    StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
    Order.class,
    asyncSnapshot
);
```

### Strategies

**AddNewAggregateSnapshotStrategy:**
- `updateWhenBehindByNumberOfEvents(n)` - Create snapshot every N events
- `updateOnEachAggregateUpdate()` - Snapshot on every change

**AggregateSnapshotDeletionStrategy:**
- `keepALimitedNumberOfHistoricSnapshots(n)` - Keep last N snapshots
- `keepAllHistoricSnapshots()` - Never delete
- `deleteAllHistoricSnapshots()` - Only keep latest

> **Tip:** Before implementing snapshots, consider the ["Closing the Books"](#closing-the-books) pattern to keep event streams small.

### Policy-Driven Snapshotting

Instead of hand-wiring strategies per repository, annotate the aggregate implementation with
`@AggregateSnapshotPolicy`. The annotation is a **code-local default** that external configuration
(for example Spring Boot properties keyed by `AggregateType`) can override.

```java
@AggregateSnapshotPolicy(
        aggregateType = "Orders",
        mode = SnapshotExecutionMode.ASYNC_DURABLE,
        everyNEvents = 100,
        deletionMode = SnapshotDeletionMode.KEEP_LAST_N,
        keepLastSnapshots = 3
)
public class Order extends AggregateRoot<OrderId, OrderEvent, Order> {
    // ...
}
```

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `enabled` | `boolean` | `true` | Whether snapshotting is enabled for this aggregate |
| `mode` | `SnapshotExecutionMode` | `SYNC` | How snapshots are persisted — see [Snapshot Execution Modes](#snapshot-execution-modes) |
| `everyNEvents` | `int` | `100` | Snapshot when the aggregate is this many events behind the last snapshot |
| `deletionMode` | `SnapshotDeletionMode` | `DELETE_ALL_HISTORIC` | `DELETE_ALL_HISTORIC` or `KEEP_LAST_N` |
| `keepLastSnapshots` | `int` | `1` | Number of snapshots retained when `deletionMode = KEEP_LAST_N` |
| `aggregateType` | `String` | `""` | The `AggregateType` this policy applies to |

The annotation is turned into an `AggregateSnapshotPolicyDescriptor` and registered in an
`AggregateSnapshotPolicyRegistry`. Under Spring Boot, `AggregateSnapshotPolicyBeanPostProcessor`
discovers annotated beans automatically.

### Snapshot Execution Modes

`SnapshotExecutionMode` decides **where** snapshot creation runs, and whether it survives a crash:

| Mode | Repository | Behaviour | Trade-off |
|------|------------|-----------|-----------|
| `SYNC` | `AsyncAggregateSnapshotRepository` | Snapshot is written on the calling thread inside the same `UnitOfWork` as the events | Simplest and consistent, but adds latency to every qualifying command |
| `ASYNC_IN_MEMORY` | `AsyncAggregateSnapshotRepository` | Snapshot work is handed to a fixed-size daemon-threaded executor | No command latency, but a crash before the executor runs simply loses that snapshot (events are untouched, so correctness is preserved — only the optimization is lost) |
| `ASYNC_DURABLE` | `DurableAsyncAggregateSnapshotRepository` | A snapshot **job row** is enqueued and processed later by a background worker | No command latency and the job survives a crash; costs one extra table and a poller |

`AsyncAggregateSnapshotRepository` is a `Lifecycle` bean — `start()` provisions the executor sized by
`AsyncAggregateSnapshotSettings.workerThreads()`, `stop()` tears it down. The executor it creates uses daemon
threads, so a forgotten `stop()` cannot keep the JVM alive. In `SYNC` mode no executor is created at all and
tasks run on the calling thread.

```java
var snapshotStore = new PostgresqlAggregateSnapshotStore(eventStore,
                                                         unitOfWorkFactory,
                                                         Optional.empty(),   // default snapshot table name
                                                         jsonSerializer);

var snapshotRepository = new AsyncAggregateSnapshotRepository(
        snapshotStore,
        jsonSerializer,
        AddNewAggregateSnapshotStrategy.updateWhenBehindByNumberOfEvents(100),
        AggregateSnapshotDeletionStrategy.keepALimitedNumberOfHistoricSnapshots(3),
        AsyncAggregateSnapshotSettings.asynchronous(),   // or .synchronous()
        unitOfWorkFactory);

snapshotRepository.start();
```

### Durable Async Snapshots

`ASYNC_DURABLE` splits snapshotting into *enqueue* and *process*:

```
Command                                 Background worker
   │                                           │
   ├─ append events to EventStore              │
   ├─ (after commit) enqueue snapshot job ─────┼─→ lockNextBatch()
   │                                           ├─→ delete old snapshots
   │  user UnitOfWork commits                  ├─→ saveSnapshot()
   ▼                                           └─→ markCompleted()   (all in ONE UnitOfWork)
```

| Type | Role |
|------|------|
| `AggregateSnapshotJob` | The queued unit of work — aggregate type, id, event order, serialized payload, attempt count |
| `AggregateSnapshotJobRepository` | Enqueue / lock / complete / fail / park operations. Postgres implementation: `PostgresqlAggregateSnapshotJobRepository` |
| `PostgresqlAggregateSnapshotJobProcessor` | Processes one locked batch; delete + save + mark-completed run in a single `UnitOfWork` |
| `DurableAsyncSnapshotManager` | `Lifecycle` bean that polls the job table and drives the processor |
| `DurableAsyncSnapshotSettings` | `pollInterval`, `batchSize`, `workerThreads`, `maxRetries`, `retryDelay`, `processingTimeout` — `DurableAsyncSnapshotSettings.defaults()` |

`AggregateSnapshotJobStatus` values:

| Status | Meaning |
|--------|---------|
| `PENDING` | Waiting to be picked up |
| `PROCESSING` | Locked by a worker. Rows whose `processing_started_ts` is older than `processingTimeout` (default 5 minutes) are reclaimed — a dead worker cannot strand a job |
| `FAILED` | An attempt failed and the job will be retried after `retryDelay`, up to `maxRetries` |
| `PARKED` | Retries exhausted. The job stays visible for inspection; re-enqueueing the same aggregate/event-order **replaces** a `PARKED` row, so a corrected payload can displace a poison pill. `PENDING` / `PROCESSING` / `FAILED` rows are left untouched |

Correctness properties worth knowing:

- **Enqueue happens after commit.** The job is registered via a `UnitOfWorkLifecycleCallback`, so a rollback of the
  user's `UnitOfWork` never leaves a job referencing uncommitted events.
- **Saves are version-guarded.** `saveSnapshot` will not overwrite a newer snapshot, and deletion is bounded via
  `deleteSnapshotsOlderThan`, so an out-of-order worker cannot resurrect a stale snapshot or wipe a newer one.

### Spring Boot Configuration

`spring-boot-starter-postgresql-event-store` auto-configures the whole snapshot subsystem
(`SnapshotConfiguration`). Snapshotting is **off by default**.

```properties
essentials.eventstore.snapshots.enabled=true
essentials.eventstore.snapshots.default-mode=async-durable
essentials.eventstore.snapshots.default-every-n-events=100
essentials.eventstore.snapshots.default-deletion-mode=keep-last-n
essentials.eventstore.snapshots.default-keep-last-snapshots=3

# ASYNC_IN_MEMORY executor pool size
essentials.eventstore.snapshots.worker-threads=1

# ASYNC_DURABLE worker
essentials.eventstore.snapshots.durable.enabled=true
essentials.eventstore.snapshots.durable.poll-interval=1s
essentials.eventstore.snapshots.durable.batch-size=25
essentials.eventstore.snapshots.durable.worker-threads=2
essentials.eventstore.snapshots.durable.max-retries=3
essentials.eventstore.snapshots.durable.retry-delay=5s
essentials.eventstore.snapshots.durable.processing-timeout=5m

# Per-AggregateType override of the @AggregateSnapshotPolicy defaults
essentials.eventstore.snapshots.aggregates.Orders.enabled=true
essentials.eventstore.snapshots.aggregates.Orders.mode=async-in-memory
essentials.eventstore.snapshots.aggregates.Orders.every-n-events=50
essentials.eventstore.snapshots.aggregates.Orders.deletion-mode=keep-last-n
essentials.eventstore.snapshots.aggregates.Orders.keep-last-snapshots=3
```

> ⚠️ **Security:** `snapshot-table-name` and `durable.job-table-name` are concatenated into the SQL that creates and
> queries these tables. Only use hardcoded, trusted values — see [Security](#security).

**Test references:**
- [`PostgresqlAggregateSnapshotRepositoryTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/PostgresqlAggregateSnapshotRepositoryTest.java)
- [`PostgresqlAggregateSnapshotRepository_keepALimitedNumberOfHistoricSnapshotsIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/PostgresqlAggregateSnapshotRepository_keepALimitedNumberOfHistoricSnapshotsIT.java)
- [`AsyncAggregateSnapshotRepositoryTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/AsyncAggregateSnapshotRepositoryTest.java)
- [`DurableAsyncAggregateSnapshotRepositoryTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/DurableAsyncAggregateSnapshotRepositoryTest.java)
- [`PostgresqlAggregateSnapshotJobProcessorTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/PostgresqlAggregateSnapshotJobProcessorTest.java)
- [`PostgresqlAggregateSnapshotJobRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/PostgresqlAggregateSnapshotJobRepositoryIT.java)
- [`PostgresqlAggregateSnapshotStoreIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/PostgresqlAggregateSnapshotStoreIT.java)

---

## Closing the Books

**Package:** `dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks`

Snapshots make a long event stream *cheaper to load*. **Closing the books** stops the stream from growing at all:
at a chosen boundary the current stream is closed, and a new one — a **generation** — is opened for the same
business entity. The classic accounting analogy: at the end of the period you close the ledger, carry the balance
forward, and start a fresh book.

| | Snapshots | Closing the books |
|---|-----------|-------------------|
| Event stream | Keeps growing forever | Bounded per generation |
| Load cost | Snapshot + tail of events | Only the current generation's events |
| Old events | Stay in the hot table | Closed generations can be [archived](#archiving-closed-generations) and pruned |
| Best for | Long-lived entities with no natural period | Entities with a natural period — accounts, ledgers, monthly/yearly books |

### Generations

Application code keeps using a stable business id; the event store sees a different stream id per generation.

| Type | Role |
|------|------|
| `LogicalAggregateId<ID>` | The stable business id, e.g. `Account-123`. Unchanged across generations |
| `AggregateGeneration<ID>` | Metadata for one generation: `aggregateType`, `logicalAggregateId`, `generation`, `streamAggregateId`, `state`, `openedAt`, `closedAt` |
| `GenerationState` | `OPEN` (accepting writes) or `CLOSED` (finalized) |
| `ClosingBooksStreamIdGenerator` | Derives the concrete stream id from the logical id + generation number, e.g. `Account-123#2` |

```
Logical aggregate id:  Account-123
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
   generation 1        generation 2        generation 3
   Account-123#1       Account-123#2       Account-123#3
   [E1 … E900]         [E1 … E640]         [E1 … E12]
   CLOSED              CLOSED              OPEN   ← all reads/writes land here
```

Only **one** generation is open at a time. Loading resolves the open generation and delegates to the underlying
`StatefulAggregateRepository` using that generation's stream id.

Generation state is owned by a small interface hierarchy:

```
ClosingBooksGenerationResolver<ID>          resolve / open next / close current
        ▲
ClosingBooksGenerationRepository<ID>        marker for a persistent resolver
        ▲
ClosingBooksOpenGenerationRepository<ID>    + scan open generations across many logical aggregates
```

| Implementation | Use |
|----------------|-----|
| `InMemoryClosingBooksGenerationResolver` | Tests and single-process scenarios |
| `PostgresqlClosingBooksGenerationRepository` | Production — persists generations in `aggregate_generations`, and serializes rollovers so two callers cannot both act on the same open generation |

### Declaring a Closing-Books Policy

`@AggregateClosingBooksPolicy` declares the code-local default for an aggregate implementation; Spring Boot
properties keyed by `AggregateType` override it.

```java
@AggregateClosingBooksPolicy(
        aggregateType = "Accounts",
        triggerMode = ClosingBooksTriggerMode.SCHEDULED_SCAN,
        defaultPolicy = ClosingBooksDefaultPolicyType.EVENT_COUNT_OR_TIME_BOUNDARY,
        eventThreshold = 10_000,
        timeBoundary = ClosingBooksTimeBoundary.END_OF_MONTH,
        zoneId = "Europe/Copenhagen"
)
public class Account extends AggregateRoot<String, AccountEvent, Account> implements HasClosingBooksPeriodId {
    @Override
    public String currentClosingBooksPeriodId() {
        return currentPeriod;   // "2026-08" for END_OF_MONTH
    }
}
```

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `enabled` | `boolean` | `true` | Whether closing-books is enabled for this aggregate |
| `triggerMode` | `ClosingBooksTriggerMode` | `ON_ACCESS` | When the policy is evaluated |
| `defaultPolicy` | `ClosingBooksDefaultPolicyType` | `UNSPECIFIED` | Which built-in rule decides |
| `eventThreshold` | `long` | `-1` | Event count that triggers a close, for the `EVENT_COUNT*` policies |
| `timeBoundary` | `ClosingBooksTimeBoundary` | `NONE` | Period boundary, for the `TIME_BOUNDARY*` policies |
| `zoneId` | `String` | `"UTC"` | Time zone used to evaluate the boundary |
| `intervalDays` | `int` | `-1` | Interval, for `EVERY_N_DAYS` |
| `aggregateType` | `String` | `""` | The `AggregateType` this policy applies to |

**`ClosingBooksTriggerMode`** — when evaluation happens:

| Mode | Evaluated |
|------|-----------|
| `ON_ACCESS` | Whenever the aggregate is loaded through a closing-books repository |
| `EXPLICIT_COMMAND` | Only when the application explicitly asks for a rollover |
| `SCHEDULED_SCAN` | By the background [scheduled scan](#scheduled-scans) |

**`ClosingBooksDefaultPolicyType`** — which built-in rule decides:

| Type | Rule |
|------|------|
| `UNSPECIFIED` | No built-in rule; supply your own `ClosingBooksDecisionPolicy` |
| `MANUAL_ONLY` | Never closes automatically |
| `EVENT_COUNT` | Close once the generation exceeds `eventThreshold` events |
| `TIME_BOUNDARY` | Close when the aggregate's period id no longer matches the current period |
| `EVENT_COUNT_OR_TIME_BOUNDARY` | Close when either condition holds |
| `EXPLICIT_ONLY` | Close only on an explicit command |

**`ClosingBooksTimeBoundary`** and the period-id format the aggregate must expose:

| Boundary | Period-id format | Example |
|----------|------------------|---------|
| `NONE` | — | — |
| `END_OF_DAY` | `yyyy-MM-dd` | `2026-08-08` |
| `EVERY_N_DAYS` | `yyyy-MM-dd` | `2026-08-08` (interval from `intervalDays`) |
| `END_OF_WEEK` | `yyyy-Www` | `2026-W32` |
| `END_OF_MONTH` | `yyyy-MM` | `2026-08` |
| `END_OF_YEAR` | `yyyy` | `2026` |

> **Important:** for `TIME_BOUNDARY` and `EVENT_COUNT_OR_TIME_BOUNDARY` the aggregate **must** expose its persisted
> current period id, and the stored format must match the configured boundary. Either implement
> `HasClosingBooksPeriodId`, or pass a custom `currentPeriodIdProvider` function to
> `BuiltInClosingBooksPolicyEvaluator`. Under Spring Boot the first form is checked at startup — see
> [Closing-Books Spring Boot Configuration](#closing-books-spring-boot-configuration).

### Decision Policies

Evaluation yields a `ClosingBooksDecision`:

| Decision | Effect |
|----------|--------|
| `KEEP_OPEN` | Nothing happens |
| `CLOSE_ONLY` | Current generation is closed; no new generation is opened |
| `CLOSE_AND_OPEN_NEXT` | Current generation is closed and the next one is opened, atomically |

`BuiltInClosingBooksPolicyEvaluator` implements the `ClosingBooksDefaultPolicyType` rules above. For anything else,
implement `ClosingBooksDecisionPolicy<ID, AGGREGATE>` or compose one with `ClosingBooksDecisionPolicies`:

```java
// Close and roll over when the aggregate says the books are ready
var policy = ClosingBooksDecisionPolicies.<String, Account>closeAndOpenNextWhenAggregate(Account::isPeriodComplete);

// Only act on a scheduled scan
var scanOnly = ClosingBooksDecisionPolicies.<String, Account>closeOnlyOnScheduledScan(Account::isPeriodComplete);

// Compose
var combined = ClosingBooksDecisionPolicies.anyOf(policy, scanOnly);
```

| Factory | Result |
|---------|--------|
| `keepOpen()` / `closeOnly()` / `closeAndOpenNext()` | Constant decision |
| `when(predicate, decision)` | Decision when the `ClosingBooksEvaluationContext` predicate holds |
| `closeWhenAggregate(predicate, decision)` | Decision based on the aggregate instance |
| `closeAndOpenNextWhenAggregate(predicate)` / `closeOnlyWhenAggregate(predicate)` | Shorthands for the above |
| `whenTriggeredBy(decision, triggerModes…)` | Decision only for the given trigger modes |
| `closeAndOpenNextOnAccess(predicate)` / `closeAndOpenNextOnExplicitCommand(predicate)` / `closeOnlyOnScheduledScan(predicate)` | Trigger-scoped shorthands |
| `allOf(policies…)` / `anyOf(policies…)` | Composition |
| `fromLegacyPolicy(policy)` | Adapts an older `ClosingBooksPolicy` |

### Repositories and the Coordinator

| Type | Role |
|------|------|
| `ClosingBooksCoordinator<ID>` | Generation lifecycle for one `AggregateType` — resolve current, open first on demand, close-and-open-next atomically, evaluate a policy |
| `ClosingBooksStatefulAggregateRepository` | Thin wrapper over a `StatefulAggregateRepository` that translates a `LogicalAggregateId` to the open generation's stream id |
| `ClosingBooksLogicalAggregateRepository` | The main ergonomic seam — application code stays on logical business ids while the repository resolves and manages generation stream ids |

```java
var coordinator = new ClosingBooksCoordinator<String>(
        AggregateType.of("Accounts"),
        new PostgresqlClosingBooksGenerationRepository(unitOfWorkFactory),
        (aggregateType, logicalId, generation) -> logicalId.value() + "#" + generation,
        unitOfWorkFactory);

// <LOGICAL_ID, STREAM_ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE>
var repository = new ClosingBooksLogicalAggregateRepository<String, String, AccountEvent, Account>(
        AggregateType.of("Accounts"),
        delegateStatefulAggregateRepository,
        coordinator,
        ClosingBooksStreamIdSerializer.stringBased());

unitOfWorkFactory.usingUnitOfWork(uow -> {
    var account = repository.load(new LogicalAggregateId<>("Account-123"));
    account.deposit(Amount.of("100.00"));
});
```

`ClosingBooksLogicalAggregateRepository` methods, all taking a `LogicalAggregateId`:

| Method | Behaviour |
|--------|-----------|
| `tryLoad(id)` | Load from the open generation, or empty if there is none |
| `load(id)` | Load from the open generation; throws if no generation is open |
| `resolveCurrentGeneration(id)` / `resolveOrOpenCurrentGeneration(id)` | Inspect, or open generation `1` on demand |
| `loadOrOpen(id, …)` | Load, opening the first generation if the aggregate is new |
| `closeAndOpenNextGeneration(id, …)` | Explicit rollover; returns the aggregate in the new generation |
| `save(aggregate)` | Delegates to the underlying `StatefulAggregateRepository` |

`closeAndOpenNextGeneration(...)` runs the close + open pair inside a **single** `UnitOfWork`: a crash between the
two rolls the close back, leaving the previous generation `OPEN` for a safe retry rather than stranding the
aggregate with no open generation.

> **Carrying state forward is your job.** Closing a generation does not copy balances into the new stream. Emit an
> opening/carry-forward event on the new generation — that is what `ClosingBooksNextGenerationFactory` /
> `TypedClosingBooksNextGenerationFactory` are for.

### Scheduled Scans

`ClosingBooksManager` is a `Lifecycle` bean that periodically runs `ClosingBooksScheduledScanProcessor`s
(default implementation: `DefaultClosingBooksScheduledScanProcessor`). It takes a `FencedLockManager` lock so
**only one node** scans at a time.

```java
var manager = new ClosingBooksManager(List.of(scanProcessor),
                                      new ClosingBooksManagerSettings(Duration.ofMinutes(5),   // pollInterval
                                                                      100,                     // batchSize
                                                                      Duration.ofSeconds(5)),  // lockAcquireTimeout
                                      fencedLockManager,
                                      LockName.of("closing-books-scan"));
manager.start();
```

This is the mechanism behind `ClosingBooksTriggerMode.SCHEDULED_SCAN` — time-boundary policies need it, since
nothing else would notice that the month rolled over on an idle aggregate.

### Archiving Closed Generations

**Package:** `dk.trustworks.essentials.components.eventsourced.aggregates.archive`

Once a generation is `CLOSED` its events are immutable, which makes them safe to export out of the hot event
tables. `AggregateGenerationArchiver` archives one `(aggregateType, logicalAggregateId, generation)` triple and
returns an `AggregateArchiveEntry` with the metadata and result.

| Type | Role |
|------|------|
| `AggregateGenerationArchiver` | Entry point. Default implementation: `DefaultAggregateGenerationArchiver` |
| `AggregateArchiveExporter` | Streams persisted events into the destination's `OutputStream` without buffering the whole payload. Default: `JacksonJsonLinesAggregateArchiveExporter` |
| `AggregateArchiveDestination` | The sink. Owns the stream and wraps it with checksum/byte-counting. Built-in: `FileSystemAggregateArchiveDestination` |
| `AggregateArchiveRegistry` | Tracks archive entries. Postgres implementation: `PostgresqlAggregateArchiveRegistry` |
| `AggregateArchiveFormat` | `JSONL` (newline-delimited JSON) or `PARQUET` |
| `AggregateArchiveStatus` | `IN_PROGRESS` (reserved by a worker — prevents duplicate concurrent exports across nodes), `ARCHIVED`, `FAILED` |

### Closing-Books Spring Boot Configuration

`spring-boot-starter-postgresql-event-store` auto-configures closing books (`ClosingBooksConfiguration`) and
archiving (`AggregateArchiveApiConfiguration`). Both are **off by default**.

```properties
essentials.eventstore.closing-books.enabled=true
essentials.eventstore.closing-books.default-trigger-mode=scheduled-scan
essentials.eventstore.closing-books.default-policy=event-count-or-time-boundary
essentials.eventstore.closing-books.event-threshold=10000
essentials.eventstore.closing-books.time-boundary=end-of-month
essentials.eventstore.closing-books.zone-id=Europe/Copenhagen

# Per-AggregateType override of the @AggregateClosingBooksPolicy defaults
essentials.eventstore.closing-books.aggregates.Accounts.enabled=true
essentials.eventstore.closing-books.aggregates.Accounts.trigger-mode=on-access
essentials.eventstore.closing-books.aggregates.Accounts.time-boundary=end-of-year

essentials.eventstore.archives.enabled=true
essentials.eventstore.archives.filesystem-root-directory=/var/lib/essentials/archives
```

`AggregateClosingBooksPolicyBeanPostProcessor` discovers `@AggregateClosingBooksPolicy`-annotated beans, and
`DefaultAggregateLifecycleConfigurationValidator` validates the combined snapshot + closing-books configuration at
startup. It fails fast when:

- `triggerMode = SCHEDULED_SCAN` but no `FencedLockManager` is configured
- a policy that closes *and opens the next* generation is active but no `TypedClosingBooksNextGenerationFactory`
  is registered for that aggregate — the framework would otherwise open a generation with no carry-forward state
- a `TIME_BOUNDARY` / `EVENT_COUNT_OR_TIME_BOUNDARY` policy is active but the resolved `timeBoundary` is `NONE`, so
  the boundary can never advance and the books would never close. For `EVENT_COUNT_OR_TIME_BOUNDARY` the message also
  points at `EVENT_COUNT`, in case only the event-count condition was intended
- a `TIME_BOUNDARY` / `EVENT_COUNT_OR_TIME_BOUNDARY` policy is active but the aggregate does not implement
  `HasClosingBooksPeriodId`, so the boundary can never be evaluated. If the period id is supplied through a custom
  `currentPeriodIdProvider` instead, opt out with
  `essentials.eventstore.closing-books.period-id-provided-externally=true` (or the per-aggregate
  `…closing-books.aggregates.<AggregateType>.period-id-provided-externally=true`)
- `zoneId` is not a valid time zone

and warns when a policy relies on a value that was silently defaulted (`event-threshold`, `interval-days`).

**Test references:**
- [`ClosingBooksCoordinatorTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/closingbooks/ClosingBooksCoordinatorTest.java)
- [`ClosingBooksDecisionPoliciesTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/closingbooks/ClosingBooksDecisionPoliciesTest.java)
- [`ClosingBooksLogicalAggregateRepositoryTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/closingbooks/ClosingBooksLogicalAggregateRepositoryTest.java)
- [`BuiltInClosingBooksPolicyEvaluatorTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/closingbooks/BuiltInClosingBooksPolicyEvaluatorTest.java)
- [`ClosingBooksTimeBoundaryCalculatorTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/closingbooks/ClosingBooksTimeBoundaryCalculatorTest.java)
- [`DefaultClosingBooksScheduledScanProcessorTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/closingbooks/DefaultClosingBooksScheduledScanProcessorTest.java)
- [`PostgresqlClosingBooksGenerationRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/closingbooks/PostgresqlClosingBooksGenerationRepositoryIT.java)

---

## In-Memory Projections

**Class:** `dk.trustworks.essentials.components.eventsourced.aggregates.projection.AnnotationBasedInMemoryProjector`

When you need to reconstruct the current state of an aggregate from its events, you have two options:

| Approach | When to Use |
|----------|-------------|
| **In-Memory Projection** | One-off queries where you need current state but don't need to modify the aggregate |
| **Full Aggregate Load** | When you need to issue commands or modify the aggregate |

In-memory projections are useful for:
- **Read-only queries**: Get aggregate state without the overhead of full aggregate instantiation
- **Lightweight views**: Build simple read models on-the-fly
- **Ad-hoc analysis**: Query aggregate state without a dedicated repository

### How It Works

The `EventStore.inMemoryProjection()` method:
1. Loads all events for the specified aggregate
2. Creates a new instance of your projection class (using its no-arg constructor)
3. For each event, calls the matching `@EventHandler` method on your projection
4. Returns the populated projection (or `Optional.empty()` if no events exist)

```
Events:        [OrderAdded] → [ProductAdded] → [ProductAdded] → [OrderAccepted]
                    ↓               ↓                ↓                ↓
Projection:    @EventHandler   @EventHandler   @EventHandler   @EventHandler
                on(OrderAdded)  on(ProductAdded) on(ProductAdded) on(OrderAccepted)
                    ↓               ↓                ↓                ↓
State:         orderId=123     products=[A]    products=[A,B]   accepted=true
```

### Creating a Projection Class

A projection class is a plain Java object (POJO) with `@EventHandler` methods. No base class required.

**Requirements:**
- Public no-argument constructor
- At least one method annotated with `@EventHandler`
- Each `@EventHandler` method takes exactly one parameter (the event type it handles)

```java
public class OrderSummary {
    private OrderId orderId;
    private CustomerId customerId;
    private List<ProductId> products = new ArrayList<>();
    private boolean accepted;

    // Required: public no-argument constructor
    public OrderSummary() {}

    // Each @EventHandler method handles one event type
    @EventHandler
    private void on(OrderAdded event) {
        this.orderId = event.orderId();
        this.customerId = event.customerId();
    }

    @EventHandler
    private void on(ProductAddedToOrder event) {
        products.add(event.productId());
    }

    @EventHandler
    private void on(OrderAccepted event) {
        this.accepted = true;
    }

    // Getters for reading the projected state
    public OrderId getOrderId() { return orderId; }
    public CustomerId getCustomerId() { return customerId; }
    public List<ProductId> getProducts() { return List.copyOf(products); }
    public boolean isAccepted() { return accepted; }
}
```

### Using In-Memory Projections

```java
// The AnnotationBasedInMemoryProjector is auto-registered by
// spring-boot-starter-postgresql-event-store (enabled by default)

// Project events for a specific aggregate to your projection class
Optional<OrderSummary> summary = eventStore.inMemoryProjection(
    AggregateType.of("Orders"),
    orderId,
    OrderSummary.class
);

summary.ifPresent(s -> {
    System.out.println("Order: " + s.getOrderId());
    System.out.println("Products: " + s.getProducts().size());
    System.out.println("Accepted: " + s.isAccepted());
});
```

**Manual registration (without Spring Boot starter):**

```java
// Register the projector with the event store
eventStore.addGenericInMemoryProjector(new AnnotationBasedInMemoryProjector());
```

### Event Matching Behavior

The projector uses `InvocationStrategy.InvokeMostSpecificTypeMatched`:

| Scenario | Behavior |
|----------|----------|
| Event has matching `@EventHandler` | Method is invoked |
| Event has no matching handler | **Silently ignored** (no error thrown) |
| Multiple handlers match (inheritance) | Only the **most specific** handler is called |

This means you can define handlers for only the events you care about - other events won't cause errors.

### In-Memory Projection vs EventStreamEvolver

Both reconstruct state from events, but serve different purposes:

| Aspect | `AnnotationBasedInMemoryProjector` | `EventStreamEvolver` |
|--------|-------------------------------------|----------------------|
| **Purpose** | Ad-hoc queries via `EventStore.inMemoryProjection()` | State reconstruction inside `EventStreamDecider.handle()` |
| **State mutability** | Mutable projection class | Immutable state (records) |
| **Registration** | Registered with `EventStore` | Used directly in decider code |
| **Use case** | Read-only queries, lightweight views | Command validation, business logic |

**Use `AnnotationBasedInMemoryProjector` when:**
- You need to query aggregate state outside of command handling
- You want a simple, annotation-driven approach
- Mutable state is acceptable

**Use `EventStreamEvolver` when:**
- You're implementing `EventStreamDecider` and need state for validation
- You prefer immutable state (Java records)
- State reconstruction is part of command handling

---

## Other Aggregate Patterns

### `FlexAggregate`

**Class:** `dk.trustworks.essentials.components.eventsourced.aggregates.flex.FlexAggregate`

`FlexAggregate` is a **functional-style** aggregate design that sits between `StatefulAggregate` (automatic change tracking) and pure `Decider` (completely stateless).  
Persistence is explicit and requires an associated `FlexAggregateRepository`. 
```java
FlexAggregateRepository.from(eventStore,
                             AggregateType.of("Orders"),
                             unitOfWorkFactory,
                             OrderId.class,
                             Order.class);
```

**Key characteristics:**

| Aspect | FlexAggregate                                         | StatefulAggregate |
|--------|-------------------------------------------------------|-------------------|
| Command methods | Return `EventsToPersist` explicitly                   | Call `apply()`, events tracked internally |
| State mutations | Handled via `@EventHandler` methods                   | Handled via `@EventHandler` methods |
| Persistence | Caller must call `persist(events)` on the associated `FlexAggregateRepository` | Automatic on `UnitOfWork` commit |
| Control | Explicit - you decide when to persist                 | Implicit - framework handles it |

**When to use FlexAggregate:**
- You want explicit control over event persistence
- You prefer functional return types over side effects
- You want to inspect/validate events before persisting
- You're transitioning from a functional style but want aggregate encapsulation

```java
public class Order extends FlexAggregate<OrderId, Order> {
    private Map<ProductId, Integer> products;
    private boolean accepted;

    // Static factory for creating new aggregate - returns events, not aggregate
    public static EventsToPersist<OrderId, Object> createOrder(OrderId orderId, CustomerId customerId) {
        return newAggregateEvents(orderId, new OrderAdded(orderId, customerId));
    }

    // Command method - returns events to persist (or noEvents() for idempotent)
    public EventsToPersist<OrderId, Object> addProduct(ProductId productId, int quantity) {
        if (accepted) {
            throw new IllegalStateException("Cannot add products to accepted order");
        }
        return events(new ProductAddedToOrder(aggregateId(), productId, quantity));
    }

    // Command method - returns events to persist
    public EventsToPersist<OrderId, Object> accept() {
        if (accepted) return noEvents(); // Idempotent
        return events(new OrderAccepted(aggregateId()));
    }

    // Event handlers update internal state (called during rehydration and after persist)
    @EventHandler
    private void on(OrderAdded e) {
        products = new HashMap<>();
    }

    @EventHandler
    private void on(ProductAddedToOrder e) {
        products.merge(e.productId(), e.quantity(), Integer::sum);
    }

    @EventHandler
    private void on(OrderAccepted e) {
        accepted = true;
    }
}

// Usage - explicit control over persistence
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    // Create new order
    var createEvents = Order.createOrder(orderId, customerId);
    repository.persist(createEvents);

    // Load and modify
    var order = repository.load(orderId);
    var addProductEvents = order.addProduct(productId, 2);
    repository.persist(addProductEvents);  // Must explicitly persist

    var acceptEvents = order.accept();
    repository.persist(acceptEvents);
});
```

**Test reference:** [`FlexAggregateRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/flex/FlexAggregateRepositoryIT.java)

### Classic `AggregateRoot`

**Class:** `dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.AggregateRoot`

The Classic `AggregateRoot` is the **original** aggregate design in this library, kept for **backwards compatibility** with existing codebases.  
It has been superseded by newer patterns that offer more flexibility and better alignment with modern Java practices.

**Why Classic is considered legacy:**

| Limitation | Impact | Modern Alternative |
|------------|--------|-------------------|
| Events must extend `Event` base class | Forces inheritance hierarchy on your domain events; can't use Java records directly | [Modern AggregateRoot](#modern-aggregateroot) - any event type |
| Coupled to framework types | Domain events depend on library classes | [EventStreamDecider](#eventstreamdecider) / [Decider](#decider-pattern) - pure POJOs |
| OOP-only design | No functional programming option | [Decider](#decider-pattern) - pure functional |
| Implicit persistence | Less control over when events are persisted | [FlexAggregate](#flexaggregate) - explicit control |

**When you might still use Classic:**
- Migrating an existing codebase that already uses Classic or an approach similar to it
- You have existing events that extend `Event` and don't want to migrate

**Recommended alternatives for new projects:**

| If you want... | Use                                                                       |
|----------------|---------------------------------------------------------------------------|
| OOP with automatic change tracking | [Modern AggregateRoot](#modern-aggregateroot)                             |
| Functional, slice-based, Event Modeling | [EventStreamDecider](#eventstreamdecider)                                 |
| Pure functional with typed errors | [EventStreamDecider](#eventstreamdecider) or [Decider](#decider-pattern) |
| Explicit persistence control | [FlexAggregate](#flexaggregate)                                           |

**Difference from Modern:**

| Aspect | Classic | Modern |
|--------|---------|--------|
| Event base class | Events must extend `Event` | Any event type (records, POJOs) |
| State separation | Supports `WithState` | Supports `WithState` |
| Repository | [`StatefulAggregateRepository`](#statefulaggregaterepository) | [`StatefulAggregateRepository`](#statefulaggregaterepository) |

> **Recommendation:** Use [Modern AggregateRoot](#modern-aggregateroot) or one of the functional patterns for new projects.

**Test references:**
- [`OrderAggregateRootRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/classic/OrderAggregateRootRepositoryIT.java)
- [`OrderWithStateAggregateRootRepositoryIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/classic/state/OrderWithStateAggregateRootRepositoryIT.java)
