# Essentials Components - EventSourced Aggregates

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM-Context:** [LLM-eventsourced-aggregates.md](../../LLM/LLM-eventsourced-aggregates.md)

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

**Interface:** `dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotRepository`

In event sourcing, loading an aggregate means replaying all its events to reconstruct current state.  
For aggregates with hundreds or thousands of events, this becomes slow.  
**Snapshots** solve this by periodically saving the aggregate's state, so subsequent loads only need to replay events since the snapshot.

Supported by `StatefulAggregateRepository` and the `Decider`'s associated `CommandHandler`.

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
- **"Closing the Books" pattern**: Periodically create a summary event and start a new stream
- **Shorter aggregate lifecycles**: Design aggregates to complete/archive naturally
- **CQRS read models**: If reads are slow, optimize the read side instead

### Configuration

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

> **Tip:** Before implementing snapshots, consider the "Closing the Books" pattern to keep event streams small.

**Test references:**
- [`PostgresqlAggregateSnapshotRepositoryTest`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/PostgresqlAggregateSnapshotRepositoryTest.java)
- [`PostgresqlAggregateSnapshotRepository_keepALimitedNumberOfHistoricSnapshotsIT`](src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/PostgresqlAggregateSnapshotRepository_keepALimitedNumberOfHistoricSnapshotsIT.java)

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
