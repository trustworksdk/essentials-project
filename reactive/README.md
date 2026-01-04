# Reactive Module

> In-memory event bus and command bus for event-driven applications within a single JVM

This module provides reactive building blocks for event-driven architecture: `LocalEventBus` for pub/sub event processing and `LocalCommandBus` for CQRS command handling. Built on Project Reactor for backpressure-aware async processing, using utilities from the [shared](../shared) module for interceptor chains and reflection-based handler dispatch.

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-reactive.md](../LLM/LLM-reactive.md)

## Table of Contents
- [Installation](#installation)
- [Quick Reference](#quick-reference)
- [LocalEventBus](#localeventbus)
- [LocalCommandBus](#localcommandbus)
- [Command Interceptors](#command-interceptors)
- [Spring Integration](#spring-integration)
- [Best Practices](#best-practices)

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>reactive</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

**Required dependency** (provided scope - add to your project):
```xml
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-core</artifactId>
</dependency>
```

**Optional dependencies** for Spring integration:
```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-beans</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-context</artifactId>
</dependency>
```

## Quick Reference

| Component | Purpose |
|-----------|---------|
| **LocalEventBus** | Publish/subscribe event bus with sync and async handlers |
| **LocalCommandBus** | CQRS command bus with single-handler-per-command enforcement |
| **AnnotatedEventHandler** | Annotation-based event handler routing via `@Handler` |
| **AnnotatedCommandHandler** | Annotation-based command handler routing via `@Handler`/`@CmdHandler` |
| **CommandBusInterceptor** | Intercept commands before/after handling |
| **ReactiveHandlersBeanPostProcessor** | Auto-register Spring beans as handlers |

## LocalEventBus

Pub/sub event bus supporting synchronous and asynchronous subscribers. Multiple handlers can receive the same event.

**Why use LocalEventBus?** Decouples event producers from consumers.  
Synchronous handlers execute in the publishing thread (within same transaction).  
Asynchronous handlers process in parallel with backpressure support.

### Basic Usage

```java
// Create event bus
LocalEventBus eventBus = new LocalEventBus("OrderEvents", 3,
    (subscriber, event, exception) -> log.error("Handler {} failed on {}", subscriber, event, exception));

// Register handlers
eventBus.addSyncSubscriber(event -> {
    if (event instanceof OrderCreated created) {
        reserveInventory(created.orderId());
    }
});

eventBus.addAsyncSubscriber(event -> {
    if (event instanceof OrderCreated created) {
        sendConfirmationEmail(created.customerId());
    }
});

// Publish events
eventBus.publish(new OrderCreated(orderId, customerId));
```

### Builder API

```java
LocalEventBus eventBus = LocalEventBus.builder()
    .busName("OrderEvents")
    .parallelThreads(5)                          // Async handler threads (default: 1-4 depending on CPU count)
    .backpressureBufferSize(1024)                // Buffer size (default: 1024)
    .overflowMaxRetries(20)                      // Retry attempts on overflow (default: 20)
    .queuedTaskCapFactor(1.5)                    // Queued task capacity factor (default: 1.5)
    .onErrorHandler((subscriber, event, ex) ->
        log.error("Handler {} failed", subscriber, ex))
    .build();
```

### Annotation-Based Handlers

Colocate related event handlers in a single class using `@Handler`:

```java
public class OrderEventHandler extends AnnotatedEventHandler {

    @Handler
    void handle(OrderCreated event) {
        log.info("Order created: {}", event.orderId());
        inventoryService.reserve(event.orderId());
    }

    @Handler
    void handle(OrderCancelled event) {
        log.info("Order cancelled: {}", event.orderId());
        inventoryService.release(event.orderId());
    }
}

// Register with bus
eventBus.addSyncSubscriber(new OrderEventHandler(inventoryService));
```

**Learn more:** See [AnnotatedEventHandlerTest.java](src/test/java/dk/trustworks/essentials/reactive/AnnotatedEventHandlerTest.java) and [LocalEventBusTest.java](src/test/java/dk/trustworks/essentials/reactive/LocalEventBusTest.java)

### Sync vs Async Handlers

| Subscriber Type | Thread | Error Handling | Use Case |
|-----------------|--------|----------------|----------|
| **Sync** (`addSyncSubscriber`) | Publisher thread | Exception propagates, rolls back `UnitOfWork` | Critical business logic, same transaction |
| **Async** (`addAsyncSubscriber`) | Bounded elastic pool | Error handler callback, no rollback | Notifications, I/O operations |

**Critical:** 
- Sync handler exceptions will roll back the publishing `UnitOfWork`. 

## LocalCommandBus

CQRS command bus enforcing single-handler-per-command. Commands express intent to change state; handlers are the single authority for processing each command type.

**Why use LocalCommandBus?** Provides location transparency - senders don't know which handler processes commands. Enforces CQRS principle that each command has exactly one handler.

### Handler Requirements

- **Exactly one handler per command type** - throws `NoCommandHandlerFoundException` or `MultipleCommandHandlersFoundException`
- Handlers optionally return values (e.g., generated IDs)
- CQRS principle: prefer returning minimal data or void

### Sending Commands

```java
LocalCommandBus commandBus = new LocalCommandBus();
commandBus.addCommandHandler(new CreateOrderHandler(orderRepository));

// Synchronous - blocks until complete
OrderId orderId = commandBus.send(new CreateOrder(customerId, items));

// Asynchronous with result - returns Mono
Mono<OrderId> result = commandBus.sendAsync(new CreateOrder(customerId, items));
OrderId orderId = result.block(Duration.ofSeconds(5));

// Fire-and-forget - no result, no waiting
commandBus.sendAndDontWait(new SendOrderConfirmation(orderId));

// Delayed fire-and-forget
commandBus.sendAndDontWait(new SendReminder(orderId), Duration.ofHours(24));
```
Critical:
- For async handlers with retry/durability needs, use `DurableLocalCommandBus` from the [foundation](../components/foundation) module.

### Command Handler Implementation

**Interface-based handler** (single command):

```java
public class CreateOrderHandler implements CommandHandler {

    @Override
    public boolean canHandle(Class<?> commandType) {
        return CreateOrder.class.equals(commandType);
    }

    @Override
    public Object handle(Object command) {
        var createOrder = (CreateOrder) command;
        var order = Order.create(createOrder.customerId(), createOrder.items());
        return orderRepository.save(order).getId();
    }
}
```

**Annotation-based handler** (multiple commands in one class):

Supports both `@Handler` and `@CmdHandler` annotations:

```java
public class OrderCommandHandler extends AnnotatedCommandHandler {

    @Handler
    private OrderId handle(CreateOrder cmd) {
        var order = Order.create(cmd.customerId(), cmd.items());
        return orderRepository.save(order).getId();
    }

    @CmdHandler  // @Handler and @CmdHandler are interchangeable
    private void handle(CancelOrder cmd) {
        var order = orderRepository.findById(cmd.orderId())
            .orElseThrow(() -> new OrderNotFoundException(cmd.orderId()));
        order.cancel(cmd.reason());
        orderRepository.save(order);
    }
}
```

**Learn more:** See [AnnotatedCommandHandlerTest.java](src/test/java/dk/trustworks/essentials/reactive/command/AnnotatedCommandHandlerTest.java) and [LocalCommandBusTest.java](src/test/java/dk/trustworks/essentials/reactive/command/LocalCommandBusTest.java)

### Error Handling

| Method | Error Behavior |
|--------|----------------|
| `send()` | Exception propagates to caller |
| `sendAsync()` | Exception in Mono error channel |
| `sendAndDontWait()` | Exception passed to `SendAndDontWaitErrorHandler` |

For `sendAndDontWait`, configure error handling:

```java
LocalCommandBus commandBus = new LocalCommandBus(
    (exception, command, handler) -> {
        log.error("Command {} failed in {}", command.getClass().getSimpleName(), handler, exception);
        // Custom error handling (e.g., dead letter queue)
    }
);
```

**For durability and retries:** 
- Use `DurableLocalCommandBus` from the [foundation](../components/foundation) module, which persists `sendAndDontWait` commands to a `DurableQueue`.

## Command Interceptors

Intercept commands before and after handling for cross-cutting concerns (security, logging, transactions).

**Interface:** `dk.trustworks.essentials.reactive.command.interceptor.CommandBusInterceptor`

### Creating Interceptors

```java
public class UnitOfWorkInterceptor implements CommandBusInterceptor {

    @Override
    public Object interceptSend(Object command, CommandBusInterceptorChain chain) {
        return unitOfWorkFactory.withUnitOfWork(uow -> chain.proceed());
    }

    @Override
    public Object interceptSendAsync(Object command, CommandBusInterceptorChain chain) {
        return unitOfWorkFactory.withUnitOfWork(uow -> chain.proceed());
    }

    @Override
    public void interceptSendAndDontWait(Object command, CommandBusInterceptorChain chain) {
        unitOfWorkFactory.withUnitOfWork(uow -> {
            chain.proceed();
            return null;
        });
    }
}
```

### Interceptor Ordering

Use `@InterceptorOrder` annotation (lower number = higher priority):

```java
@InterceptorOrder(1)  // Runs first
public class SecurityInterceptor implements CommandBusInterceptor { ... }

@InterceptorOrder(10)  // Runs after security
public class LoggingInterceptor implements CommandBusInterceptor { ... }
```

### Registering Interceptors

```java
// At construction
LocalCommandBus commandBus = new LocalCommandBus(
    new SecurityInterceptor(),
    new UnitOfWorkInterceptor()
);

// Or later
commandBus.addInterceptor(new LoggingInterceptor());
```
Note: Interceptors are automatically sorted by the CommandBus according to their `@InterceptorOrder`.

**Learn more:** See [DefaultCommandBusInterceptorChainTest.java](src/test/java/dk/trustworks/essentials/reactive/command/interceptor/DefaultCommandBusInterceptorChainTest.java)

## Spring Integration

Without Spring integration, you must manually register each handler:

```java
// Manual registration (tedious for many handlers)
commandBus.addCommandHandler(new OrderCommandHandler());
commandBus.addCommandHandler(new PaymentCommandHandler());
eventBus.addSyncSubscriber(new InventoryEventHandler());
eventBus.addAsyncSubscriber(new NotificationHandler());
```

`ReactiveHandlersBeanPostProcessor` eliminates this boilerplate by automatically discovering and registering handler beans.

### How It Works

When Spring initializes beans, the post-processor:
1. Scans all beans in the application context
2. Finds beans implementing `CommandHandler` or `EventHandler`
3. Registers them with the appropriate bus automatically

| Bean Type | Annotation | Registration |
|-----------|------------|--------------|
| `CommandHandler` | (none needed) | `CommandBus.addCommandHandler()` |
| `EventHandler` | (none) | `EventBus.addSyncSubscriber()` |
| `EventHandler` | `@AsyncEventHandler` | `EventBus.addAsyncSubscriber()` |

**Requirements:**
- One `CommandBus` bean must exist in the context (for command handlers)
- One or more `EventBus` beans must exist (event handlers register with all of them)
- Handlers are automatically unregistered when beans are destroyed

### Configuration

```java
@Configuration
public class ReactiveConfig {

    // Enable automatic handler registration
    @Bean
    static ReactiveHandlersBeanPostProcessor reactiveHandlersBeanPostProcessor() {
        return new ReactiveHandlersBeanPostProcessor();
    }

    // Required: at least one EventBus bean
    @Bean
    public LocalEventBus localEventBus() {
        return LocalEventBus.builder()
            .busName("ApplicationEvents")
            .parallelThreads(10)
            .onErrorHandler((subscriber, event, ex) ->
                log.error("Event handler error", ex))
            .build();
    }

    // Required: exactly one CommandBus bean
    @Bean
    public LocalCommandBus localCommandBus() {
        return new LocalCommandBus();
    }
}
```

### Handler Examples

Just annotate handlers with `@Component` - registration happens automatically:

```java
// CommandHandler: automatically registered with CommandBus
@Component
public class OrderCommandHandler implements CommandHandler {
    @Override
    public boolean canHandle(Class<?> commandType) {
        return CreateOrder.class.equals(commandType);
    }

    @Override
    public Object handle(Object command) { ... }
}

// EventHandler: automatically registered as SYNC subscriber
@Component
public class InventoryEventHandler implements EventHandler {
    @Override
    public void handle(Object event) { ... }
}

// EventHandler + @AsyncEventHandler: registered as ASYNC subscriber
@Component
@AsyncEventHandler
public class NotificationHandler implements EventHandler {
    @Override
    public void handle(Object event) { ... }
}
```

**Learn more:** See [ReactiveHandlersBeanPostProcessorTest.java](src/test/java/dk/trustworks/essentials/reactive/spring/ReactiveHandlersBeanPostProcessorTest.java)

## Best Practices

### Event vs Command Design

| Aspect | Command | Event |
|--------|---------|-------|
| **Naming** | Imperative (`CreateOrder`) | Past tense (`OrderCreated`) |
| **Intent** | Request to change state | Fact that happened |
| **Handlers** | Exactly one | Multiple allowed |
| **Can fail** | Yes | No (represents past fact) |
| **Return value** | Optional (IDs, minimal data) | None |

### When to Use Sync vs Async Event Handlers

**Sync handlers** for:
- Critical business logic that must complete before transaction commits
- Operations that should roll back on failure
- Maintaining data consistency within a bounded context

**Async handlers** for:
- Notifications (email, push)
- Cross-system integrations
- Non-critical logging/analytics

### Command Durability Considerations

`LocalCommandBus.sendAndDontWait()` is **non-durable**:
- Commands lost on JVM restart
- No retry on failure
- Suitable for non-critical operations

For durability, use `DurableLocalCommandBus` from the [foundation](../components/foundation) module:
- Commands persisted to `DurableQueue`
- Automatic retry with `RedeliveryPolicy`
- Survives JVM restarts

## See Also

- [LLM-reactive.md](../LLM/LLM-reactive.md) - API reference for LLM assistance
- [foundation](../components/foundation) - `DurableLocalCommandBus`, `UnitOfWork`, `Inbox/Outbox`
- [shared](../shared) - `InterceptorChain`, `PatternMatchingMethodInvoker`
