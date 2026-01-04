# EventSourced Aggregates - LLM Reference

> See [README](../components/eventsourced-aggregates/README.md) for detailed explanations and motivation.

## Quick Facts
- **Package**: `dk.trustworks.essentials.components.eventsourced.aggregates`
- **Purpose**: Event-sourced aggregate patterns for DDD
- **Deps**: postgresql-event-store, foundation, immutable-jackson
- **Status**: WORK-IN-PROGRESS

## Maven
```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>eventsourced-aggregates</artifactId>
</dependency>
```

## TOC
- [Pattern Selection](#pattern-selection)
- [1. EventStreamDecider (Functional)](#1-eventstreamdecider-functional)
- [2. Decider Pattern (Typed Errors)](#2-decider-pattern-typed-errors)
- [3. Modern AggregateRoot (OOP)](#3-modern-aggregateroot-oop)
- [4. FlexAggregate (Explicit Control)](#4-flexaggregate-explicit-control)
- [StatefulAggregateRepository](#statefulaggregaterepository)
- [Aggregate Snapshots](#aggregate-snapshots)
- [In-Memory Projections](#in-memory-projections)
- [Common Patterns & Gotchas](#common-patterns--gotchas)
- ⚠️ [Security](#security)

## Pattern Selection

| Pattern | State                                                        | Testing | Best For |
|---------|--------------------------------------------------------------|---------|----------|
| **EventStreamDecider** | Immutable, event stream                                      | Given-When-Then | Event Modeling, functional, slicing |
| **Decider** | Immutable, external                                          | Result types | Typed errors, enterprise |
| **Modern AggregateRoot** | Mutable `StatefulAggregate`, internal                        | Unit tests | OOP, Spring Boot |
| **Modern + WithState** | Mutable `StatefulAggregate`, state separated                 | Unit tests | Complex state separation |
| **FlexAggregate** | Immutable, internal                                          | Functional | Explicit event control |
| **Classic AggregateRoot** | Mutable `StatefulAggregate`, requires Event base inheritance | Unit tests | Legacy (avoid for new) |

All `StatefulAggregate` patterns use `StatefulAggregateRepository`.

---

## 1. EventStreamDecider (Functional)

**Interface**: `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider`

Pure functional, slice-based, ideal for Event Modeling. Returns single event (or none).
The `handle` method receives:
- **`command`**: The command to process
- **`events`**: The complete event history for this aggregate (loaded from the EventStore).

### API
```java
interface EventStreamDecider<COMMAND, EVENT> {
    // Return Optional.of(event) if success
    // Return Optional.empty() if idempotent
    // Throw exception if invalid / business validation failed
    Optional<EVENT> handle(COMMAND command, List<EVENT> events);
    boolean canHandle(Class<?> command);
}
```

### Basic Decider
```java
public class CreateOrderDecider implements EventStreamDecider<CreateOrder, OrderEvent> {
    @Override
    public Optional<OrderEvent> handle(CreateOrder cmd, List<OrderEvent> events) {
        requireNonNull(cmd, "command cannot be null");

        // Check idempotency
        if (events.stream().anyMatch(e -> e instanceof OrderCreated)) {
            return Optional.empty(); // Already exists
        }

        return Optional.of(new OrderCreated(cmd.orderId(), cmd.customerId()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return CreateOrder.class == command;
    }
}
```

### State Validation with EventStreamEvolver
```java
public class ConfirmOrderDecider implements EventStreamDecider<ConfirmOrder, OrderEvent> {
    private final OrderEvolver evolver = new OrderEvolver();

    @Override
    public Optional<OrderEvent> handle(ConfirmOrder cmd, List<OrderEvent> events) {
        Optional<OrderState> state = EventStreamEvolver.applyEvents(evolver, events);

        if (state.isEmpty()) throw new IllegalStateException("Order not found");
        if (state.get().status() == CONFIRMED) return Optional.empty(); // Idempotent
        if (!state.get().canBeConfirmed()) throw new IllegalStateException("Invalid status");

        return Optional.of(new OrderConfirmed(cmd.orderId()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return ConfirmOrder.class == command;
    }
}

// EventStreamEvolver - reconstructs state from events (left-fold pattern)
public class OrderEvolver implements EventStreamEvolver<OrderEvent, OrderState> {
    @Override
    public Optional<OrderState> applyEvent(OrderEvent event, Optional<OrderState> current) {
        return switch (event) {
            case OrderCreated e -> Optional.of(OrderState.created(e.orderId(), e.customerId()));
            case OrderConfirmed e -> current.map(s -> s.withStatus(CONFIRMED));
            case OrderShipped e -> current.map(s -> s.withStatus(SHIPPED));
            default -> current; // Ignore unknown
        };
    }
}
```

### Immutable State Pattern
```java
// State as record - inherently immutable
public record OrderState(
    OrderId orderId,
    CustomerId customerId,
    OrderStatus status
) {
    public static OrderState created(OrderId id, CustomerId customerId) {
        return new OrderState(id, customerId, PENDING);
    }

    // Return NEW instances
    public OrderState withStatus(OrderStatus newStatus) {
        return new OrderState(orderId, customerId, newStatus);
    }

    public boolean canBeConfirmed() { return status == PENDING; }
}
```

### Spring Boot Wiring

Configure a `EventStreamAggregateTypeConfiguration` per `AggregateType`.
And a `EventStreamDeciderAndAggregateTypeConfigurator` which auto-wires `EventStreamAggregateTypeConfiguration`s and their deciders together.

**Parameters**:

| Parameter | Purpose | Example |
|-----------|---------|---------|
| `aggregateType` | Aggregate identifier | `AggregateType.of("Orders")` |
| `aggregateIdType` | ID type | `OrderId.java` |
| `aggregateIdSerializer` | Serializer | `AggregateIdSerializer.serializerFor(...)` |
| `deciderSupportsAggregateTypeChecker` | Decider filter | Check if cmd inherits from `OrderCommand` |
| `commandAggregateIdResolver` | Extract ID from cmd | `cmd -> ((OrderCommand) cmd).orderId()` |
| `eventAggregateIdResolver` | Extract ID from event | `event -> ((OrderEvent) event).orderId() ` |

```java
@Configuration
public class EventSourcingConfig {
    @Bean
    public EventStreamDeciderAndAggregateTypeConfigurator configurator(
            ConfigurableEventStore<?> eventStore,
            CommandBus commandBus,
            List<EventStreamAggregateTypeConfiguration> configs,
            List<EventStreamDecider<?, ?>> deciders) {
        return new EventStreamDeciderAndAggregateTypeConfigurator(
            eventStore, commandBus, configs, deciders
        );
    }

    @Bean
    public EventStreamAggregateTypeConfiguration ordersConfig() {
        return new EventStreamAggregateTypeConfiguration(
            AggregateType.of("Orders"),
            OrderId.class,
            AggregateIdSerializer.serializerFor(OrderId.class),
            new HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
            cmd -> ((OrderCommand) cmd).orderId(),      // Extract ID from command
            event -> ((OrderEvent) event).orderId()     // Extract ID from event
        );
    }

    @Bean public CreateOrderDecider createOrderDecider() { return new CreateOrderDecider(); }
    @Bean public ConfirmOrderDecider confirmOrderDecider() { return new ConfirmOrderDecider(); }
}
```
> 💡 Deciders can use `@Component`/`@Service` instead of `@Bean` methods.
> 
### Command Sending

Note: Assumes the `CommandBus` is configured with the `UnitOfWorkControllingCommandBusInterceptor`.

```java
@Service
public class OrderService {
    private final CommandBus commandBus;
    private final UnitOfWorkFactory unitOfWorkFactory;

    public boolean createOrder(OrderId orderId, CustomerId customerId) {
        OrderCreated event = (OrderCreated) commandBus.send(new CreateOrder(orderId, customerId));
        return event != null; // null if idempotent (decider returned Optional.empty())
    }
}
```

### Testing with GivenWhenThenScenario
```java
@Test
void shouldCreateOrder() {
    var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());

    scenario
        .given() // No events
        .when(new CreateOrder(orderId, customerId))
        .then(new OrderCreated(orderId, customerId));
}

@Test
void shouldBeIdempotent() {
    var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());

    scenario
        .given(new OrderCreated(orderId, customerId))
        .when(new CreateOrder(orderId, customerId))
        .thenExpectNoEvent();
}

@Test
void shouldRejectInvalidTransition() {
    var scenario = new GivenWhenThenScenario<>(new ShipOrderDecider());

    scenario
        .given(new OrderCreated(orderId, customerId)) // Not confirmed
        .when(new ShipOrder(orderId))
        .thenThrows(IllegalStateException.class);
}

@Test
void shouldCreateOrderWithCustomAssertions() {
    var scenario = new GivenWhenThenScenario<>(new CreateOrderDecider());

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
### Why Single Event?

Forces explicit event modeling instead of implementation steps:

| Anti-pattern | Correct Pattern |
|--------------|-----------------|
| `CreateOrder` → `OrderCreated` + `ProductsAdded` | `OrderWithProductsPlaced` |
| `RegisterCustomer` → `CustomerCreated` + `AddressAdded` | `CustomerRegistered` |

**Benefits**: Clear intent, no event reuse, simpler evolution, Event Storming alignment.

> 💡 Multiple events needed? Consider modeling business outcomes, not implementation steps.

**Test refs**: [`EventStreamDeciderTest`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/eventstream/EventStreamDeciderTest.java), [`EventStreamDeciderIT`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/eventstream/EventStreamDeciderIT.java)

---

## 2. Decider Pattern (Typed Errors)

**Interface**: `dk.trustworks.essentials.components.eventsourced.aggregates.decider.Decider`

Pure functional with `HandlerResult<ERROR, EVENT>` instead of exceptions. Can return multiple events.

### API
```java
interface Decider<COMMAND, EVENT, ERROR, STATE>
    extends Handler<COMMAND, EVENT, ERROR, STATE>,
            StateEvolver<EVENT, STATE>,
            InitialStateProvider<STATE>,
            IsStateFinalResolver<STATE> {}

interface Handler<COMMAND, EVENT, ERROR, STATE> {
    HandlerResult<ERROR, EVENT> handle(COMMAND command, STATE state);
}

interface StateEvolver<EVENT, STATE> {
    STATE applyEvent(EVENT event, STATE state);
}

interface InitialStateProvider<STATE> {
    default STATE initialState() {
        return null;
    }
}

interface IsStateFinalResolver<STATE> {
   boolean isFinal(STATE state);
}
```

### Implementation
```java
public class OrderDecider implements Decider<OrderCommand, OrderEvent, OrderError, OrderState> {
    @Override
    public HandlerResult<OrderError, OrderEvent> handle(OrderCommand cmd, OrderState state) {
        return switch (cmd) {
            case AddProduct c -> {
                if (state.accepted()) {
                    yield HandlerResult.error(new OrderError.AlreadyAccepted(state.orderId()));
                }
                yield HandlerResult.events(new ProductAdded(state.orderId(), c.productId(), c.quantity()));
            }
            case AcceptOrder c -> {
                if (state.accepted()) yield HandlerResult.events(); // Idempotent
                if (!state.hasProducts()) {
                    yield HandlerResult.error(new OrderError.NoProducts(state.orderId()));
                }
                yield HandlerResult.events(new OrderAccepted(state.orderId()));
            }
        };
    }

    @Override
    public OrderState applyEvent(OrderEvent event, OrderState state) {
        return switch (event) {
            case OrderAdded e -> OrderState.initial(e.orderId());
            case ProductAdded e -> state.withProduct(e.productId(), e.quantity());
            case OrderAccepted e -> state.withAccepted();
        };
    }

    @Override
    public OrderState initialState() { return OrderState.empty(); }

    @Override
    public boolean isFinal(OrderState state) {
        return state.status() == COMPLETED || state.status() == CANCELLED;
    }
}
```

### Typed Errors
```java
// Sealed for exhaustive pattern matching
public sealed interface OrderError {
    record AlreadyAccepted(OrderId orderId) implements OrderError {}
    record NoProducts(OrderId orderId) implements OrderError {}
    record InvalidQuantity(ProductId productId, int quantity) implements OrderError {}
}

// Handling
var result = decider.handle(command, state);
switch (result) {
    case HandlerResult.Success<OrderError, OrderEvent> s -> persistEvents(s.events());
    case HandlerResult.Error<OrderError, OrderEvent> e -> switch (e.error()) {
        case AlreadyAccepted err -> Response.conflict("Already accepted");
        case NoProducts err -> Response.badRequest("No products");
        case InvalidQuantity err -> Response.badRequest("Invalid: " + err.quantity());
    };
}

// Or use fold()
result.fold(error -> handleError(error), events -> persistEvents(events));
```

### Wiring with CommandHandler
```java
var commandHandler = CommandHandler.deciderBasedCommandHandler(
    eventStore,
    AggregateType.of("Orders"),
    OrderId.class,
    cmd -> Optional.of(cmd.orderId()),
    event -> Optional.of(event.orderId()),
    snapshotRepository,
    OrderState.class,
    new OrderDecider()
);

// Usage
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    var result = commandHandler.handle(new AddProduct(orderId, productId, 2));
    result.fold(error -> handleError(error), events -> handleSuccess(events));
});
```

**Test refs**: [`DeciderTest`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/decider/DeciderTest.java), [`DeciderBasedCommandHandlerIT`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/decider/DeciderBasedCommandHandlerIT.java)

---

## 3. Modern AggregateRoot (OOP)

**Class**: `dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot`

Mutable, automatic change tracking. Works with any event type (records, POJOs) without requiring any inheritance.

### Basic Pattern
```java
public class Order extends AggregateRoot<OrderId, OrderEvent, Order> {
    private Map<ProductId, Integer> productAndQuantity;
    private boolean accepted;

    public Order() {} // For snapshot deserialization

    // Rehydration constructor
    public Order(OrderId orderId) {
        super(orderId);
    }

    // Business constructor
    public Order(OrderId orderId, CustomerId customerId, int orderNumber) {
        this(orderId);
        apply(new OrderEvent.OrderAdded(orderId, customerId, orderNumber));
    }

    // Command methods
    public void addProduct(ProductId productId, int quantity) {
        if (accepted) throw new IllegalStateException("Order accepted");
        apply(new OrderEvent.ProductAddedToOrder(aggregateId(), productId, quantity));
    }

    public void accept() {
        if (accepted) return; // Idempotent
        apply(new OrderEvent.OrderAccepted(aggregateId()));
    }

    // Event handlers
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

### WithState Pattern (Separated State)
```java
public class Order extends AggregateRoot<OrderId, OrderEvent, Order>
        implements WithState<OrderId, OrderEvent, Order, OrderState> {

    public Order(OrderId orderId) { super(orderId); }

    public Order(OrderId orderId, CustomerId customerId, int orderNumber) {
        super(orderId);
        apply(new OrderEvent.OrderAdded(orderId, customerId, orderNumber));
    }

    public void addProduct(ProductId productId, int quantity) {
        if (state().accepted) throw new IllegalStateException("Order accepted");
        apply(new OrderEvent.ProductAddedToOrder(aggregateId(), productId, quantity));
    }

    @Override
    protected OrderState state() { return super.state(); }
}

// Separate state class
public class OrderState extends AggregateState<OrderId, OrderEvent, Order> {
    Map<ProductId, Integer> productAndQuantity;
    boolean accepted;

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

**Test refs**: [`OrderAggregateRootRepositoryIT`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/modern/OrderAggregateRootRepositoryIT.java), [`OrderAggregateRootWithStateRepositoryIT`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/modern/with_state/OrderAggregateRootWithStateRepositoryIT.java)

---

## 4. FlexAggregate (Explicit Control)

**Class**: `dk.trustworks.essentials.components.eventsourced.aggregates.flex.FlexAggregate`

Command methods return `EventsToPersist`. Must explicitly persist using the associated `FlexAggregateRepository`.
```java
FlexAggregateRepository.from(eventStore,
                             AggregateType.of("Orders"),   
                             unitOfWorkFactory,
                             OrderId.class,
                             Order.class);
```

```java
public class Order extends FlexAggregate<OrderId, Order> {
    private Map<ProductId, Integer> products;
    private boolean accepted;

    // Static factory - returns events
    public static EventsToPersist<OrderId, Object> createOrder(OrderId id, CustomerId customerId) {
        return newAggregateEvents(id, new OrderAdded(id, customerId));
    }

    // Command methods return EventsToPersist
    public EventsToPersist<OrderId, Object> addProduct(ProductId productId, int quantity) {
        if (accepted) throw new IllegalStateException("Order accepted");
        return events(new ProductAddedToOrder(aggregateId(), productId, quantity));
    }

    public EventsToPersist<OrderId, Object> accept() {
        if (accepted) return noEvents(); // Idempotent
        return events(new OrderAccepted(aggregateId()));
    }

    @EventHandler
    private void on(OrderAdded e) { products = new HashMap<>(); }

    @EventHandler
    private void on(ProductAddedToOrder e) { products.merge(e.productId(), e.quantity(), Integer::sum); }

    @EventHandler
    private void on(OrderAccepted e) { accepted = true; }
}

// Usage - explicit persistence
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    var createEvents = Order.createOrder(orderId, customerId);
    repository.persist(createEvents);

    var order = repository.load(orderId);
    var addEvents = order.addProduct(productId, 2);
    repository.persist(addEvents); // Must explicitly persist
});
```

**Test ref**: [`FlexAggregateRepositoryIT`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/flex/FlexAggregateRepositoryIT.java)

---

## StatefulAggregateRepository

**Interface**: `dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository`

Works with Modern/Classic `StatefulAggregateRoot` (with or without `WithState`).

### Setup
```java
var ordersRepository = StatefulAggregateRepository.from(
    eventStore,
    AggregateType.of("Orders"),
    StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
    Order.class
);

// With snapshots
var ordersRepository = StatefulAggregateRepository.from(
    eventStore,
    AggregateType.of("Orders"),
    StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
    Order.class,
    snapshotRepository
);
```

### Usage
```java
// Create
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    var order = new Order(orderId, customerId, 1234);
    ordersRepository.save(order);
});

// Load and modify - auto-persists on commit
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    var order = ordersRepository.load(orderId);
    order.addProduct(productId, 1);
    order.accept();
    // Changes persist automatically on UnitOfWork commit
});

// Optional load
var maybeOrder = unitOfWorkFactory.withUnitOfWork(
    unitOfWork -> ordersRepository.tryLoad(orderId)
);
```

**Test refs**: [`StatefulAggregateRepositoryIT`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/stateful/StatefulAggregateRepositoryIT.java), [`TransactionalBehaviorIT`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/stateful/TransactionalBehaviorIT.java)

---

## Aggregate Snapshots

**Interface**: `dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotRepository`

Optimize loading for aggregates with many events. Snapshots save state at EventOrder N, then only load events after N.

Supported by `StatefulAggregateRepository` and the `Decider`'s associated `CommandHandler`. 

### When to Use
| Scenario | Without | With |
|----------|---------|------|
| 500 events | Replay 500 | Snapshot + ~50 |
| Long-lived | Degrades | Consistent |

### Configuration
```java
var snapshotRepository = new PostgresqlAggregateSnapshotRepository(
    eventStore,
    unitOfWorkFactory,
    jsonSerializer,
    AddNewAggregateSnapshotStrategy.updateWhenBehindByNumberOfEvents(100),
    AggregateSnapshotDeletionStrategy.keepALimitedNumberOfHistoricSnapshots(3)
);

// Async processing
var asyncSnapshot = DelayedAddAndDeleteAggregateSnapshotDelegate.delegateTo(snapshotRepository);
```

### Strategies
| Strategy | Options |
|----------|---------|
| **AddNewAggregateSnapshotStrategy** | `updateWhenBehindByNumberOfEvents(n)` - Every N events<br>`updateOnEachAggregateUpdate()` - Always |
| **AggregateSnapshotDeletionStrategy** | `keepALimitedNumberOfHistoricSnapshots(n)` - Keep last N<br>`keepAllHistoricSnapshots()` - Never delete<br>`deleteAllHistoricSnapshots()` - Only latest |

**Consider first**: "Closing the Books" pattern to keep streams small.

**Test refs**: [`PostgresqlAggregateSnapshotRepositoryTest`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/PostgresqlAggregateSnapshotRepositoryTest.java), [`PostgresqlAggregateSnapshotRepository_keepALimitedNumberOfHistoricSnapshotsIT`](../components/eventsourced-aggregates/src/test/java/dk/trustworks/essentials/components/eventsourced/aggregates/snapshot/PostgresqlAggregateSnapshotRepository_keepALimitedNumberOfHistoricSnapshotsIT.java)

---

## In-Memory Projections

**Class**: `dk.trustworks.essentials.components.eventsourced.aggregates.projection.AnnotationBasedInMemoryProjector`

Reconstruct state without full aggregate load.

### Implementation
```java
public class OrderSummary {
    private OrderId orderId;
    private List<ProductId> products = new ArrayList<>();
    private boolean accepted;

    public OrderSummary() {} // Required

    @EventHandler
    private void on(OrderAdded event) { this.orderId = event.orderId(); }

    @EventHandler
    private void on(ProductAddedToOrder event) { products.add(event.productId()); }

    @EventHandler
    private void on(OrderAccepted event) { this.accepted = true; }

    // Getters
    public OrderId getOrderId() { return orderId; }
    public List<ProductId> getProducts() { return List.copyOf(products); }
    public boolean isAccepted() { return accepted; }
}
```

### Usage
```java
// Auto-registered by spring-boot-starter-postgresql-event-store
Optional<OrderSummary> summary = eventStore.inMemoryProjection(
    AggregateType.of("Orders"),
    orderId,
    OrderSummary.class
);

// Manual registration
eventStore.addGenericInMemoryProjector(new AnnotationBasedInMemoryProjector());
```

### vs EventStreamEvolver

| Aspect | AnnotationBasedInMemoryProjector | EventStreamEvolver |
|--------|-----------------------------------|---------------------|
| Purpose | Ad-hoc queries | State in deciders |
| State | Mutable class | Immutable records |
| Registration | With EventStore | Used in code |

---

## Common Patterns & Gotchas

### Event Design
```java
// ✅ Immutable with shared interface
public interface OrderEvent {
    OrderId orderId();
}
public record OrderCreated(OrderId orderId, CustomerId customerId) implements OrderEvent {}

// ❌ Mutable
public class OrderCreated {
    private OrderId orderId;
    public void setOrderId(OrderId id) {} // Don't
}
```

### Business Logic
```java
// ✅ Logic in commands
public void addProduct(ProductId productId, int quantity) {
    if (accepted) throw new IllegalStateException("Order accepted");
    apply(new ProductAddedToOrder(aggregateId(), productId, quantity));
}

// ❌ Logic in event handlers
@EventHandler
private void on(ProductAddedToOrder e) {
    if (accepted) throw new IllegalStateException(); // Don't validate here
    productAndQuantity.merge(e.productId, e.quantity, Integer::sum);
}
```

### Aggregate Creation
```java
// ✅ Emit creation event
public Order(OrderId orderId, CustomerId customerId, int orderNumber) {
    super(orderId);
    apply(new OrderAdded(orderId, customerId, orderNumber));
}

// ✅ Use UnitOfWork explicitly,  unless running in a Spring managed transaction or sending commands via a `CommandBus` using the `UnitOfWorkControllingCommandBusInterceptor` 
unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
    var order = new Order(orderId, customerId, 1234);
    ordersRepository.save(order);
});

// ❌ No UnitOfWork
var order = new Order(orderId, customerId, 1234);
ordersRepository.save(order); // Throws NoActiveUnitOfWorkException
```

### Event Handlers
```java
// ✅ Simple, focused
@EventHandler
private void on(ProductAddedToOrder e) {
    productAndQuantity.merge(e.productId, e.quantity, Integer::sum);
}

// ❌ No I/O in handlers
@EventHandler
private void on(OrderCreated e) {
    emailService.sendConfirmation(e.customerId); // Don't
}
```

---

## Security

### ⚠️ Critical: SQL Injection Risk

The components allow customization of table/column/index/function-names that are used with **String concatenation** → SQL injection risk. 
While Essentials applies naming convention validation as an initial defense layer, **this is NOT exhaustive protection** against SQL injection.

Configuration parameters used in SQL via string concatenation.

### Sanitize These
| Parameter | Where | Risk |
|-----------|-------|------|
| `AggregateType` | SQL queries | Injection |
| `snapshotTableName` | PostgresqlAggregateSnapshotRepository | Injection |

### Mitigations
- `PostgresqlUtil.checkIsValidTableOrColumnName()` provides basic validation
- NOT complete protection

### Your Responsibility
- Generate IDs with `RandomIdGenerator.generate()` or `UUID.randomUUID()`
- Never use unsanitized user input for table names, columns names, `AggregateType`, or IDs
- Validate all configuration at startup

---

## See Also
- [postgresql-event-store](./LLM-postgresql-event-store.md) - Event persistence
- [foundation](./LLM-foundation.md) - UnitOfWork, transactions
- [shared](./LLM-shared.md) - Pattern matching, reflection
- [types](./LLM-types.md) - ID types
