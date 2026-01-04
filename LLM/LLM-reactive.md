# Reactive - LLM Reference

## Quick Facts
- **Package**: `dk.trustworks.essentials.reactive`
- **Purpose**: In-memory event bus and command bus for event-driven JVM applications
- **Dependencies**: `reactor-core` (provided), optional `spring-context` (provided)
- **Foundation**: Uses `shared` module's `InterceptorChain` and `PatternMatchingMethodInvoker`
- **Status**: WORK-IN-PROGRESS

## TOC
- [Quick Facts](#quick-facts)
- [Core Components](#core-components)
- [LocalEventBus API](#localeventbus-api)
  - [EventBus Interface](#eventbus-interface)
  - [LocalEventBus Implementation](#localeventbus-implementation)
  - [Event Handler Types](#event-handler-types)
  - [AnnotatedEventHandler](#annotatedeventhandler)
  - [Handler Annotation](#handler-annotation)
  - [Sync vs Async Handlers](#sync-vs-async-handlers)
- [LocalCommandBus API](#localcommandbus-api)
  - [CommandBus Interface](#commandbus-interface)
  - [LocalCommandBus Implementation](#localcommandbus-implementation)
  - [Send Methods](#send-methods)
  - [CommandHandler Types](#commandhandler-types)
  - [Handler Annotations](#handler-annotations)
  - [Error Handling](#error-handling)
  - [Exceptions](#exceptions)
- [Command Interceptors](#command-interceptors)
  - [CommandBusInterceptor Interface](#commandbusinterceptor-interface)
  - [CommandBusInterceptorChain Interface](#commandbusinterceptorchain-interface)
  - [Interceptor Ordering](#interceptor-ordering)
  - [Common Interceptor Patterns](#common-interceptor-patterns)
- [Spring Integration](#spring-integration)
  - [ReactiveHandlersBeanPostProcessor](#reactivehandlersbeanpostprocessor)
  - [AsyncEventHandler Annotation](#asynceventhandler-annotation)
  - [Registration Rules](#registration-rules)
- [Common Patterns](#common-patterns)
- [Dependencies & Integration](#dependencies--integration)
- [Thread Safety](#thread-safety)
- [Gotchas](#gotchas)
- [See Also](#see-also)

---

## Core Components

| Class | Package | Purpose |
|-------|---------|---------|
| **Event Bus** | | |
| `EventBus` | `dk.trustworks.essentials.reactive` | Event bus interface |
| `LocalEventBus` | `dk.trustworks.essentials.reactive` | **Implements `EventBus`** - Pub/sub bus with sync/async subscribers |
| `EventHandler` | `dk.trustworks.essentials.reactive` | Event subscriber functional interface |
| `AnnotatedEventHandler` | `dk.trustworks.essentials.reactive` | **Implements `EventHandler`** - `@Handler` annotation routing |
| `OnErrorHandler` | `dk.trustworks.essentials.reactive` | Async handler error callback |
| `Handler` | `dk.trustworks.essentials.reactive` | Annotation for event/command handler methods |
| **Command Bus** | | |
| `CommandBus` | `dk.trustworks.essentials.reactive.command` | Command bus interface |
| `LocalCommandBus` | `dk.trustworks.essentials.reactive.command` | **Implements `CommandBus`** - CQRS bus (single handler per command) |
| `AbstractCommandBus` | `dk.trustworks.essentials.reactive.command` | Abstract base class implementing `CommandBus` with caching |
| `CommandHandler` | `dk.trustworks.essentials.reactive.command` | Command processor interface |
| `AnnotatedCommandHandler` | `dk.trustworks.essentials.reactive.command` | **Implements `CommandHandler`** - `@Handler`/`@CmdHandler` routing |
| `SendAndDontWaitErrorHandler` | `dk.trustworks.essentials.reactive.command` | Fire-and-forget error callback interface |
| `CmdHandler` | `dk.trustworks.essentials.reactive.command` | Annotation for command handler methods (alias for `@Handler`) |
| **Exceptions** | | |
| `NoCommandHandlerFoundException` | `dk.trustworks.essentials.reactive.command` | Thrown when no handler found for command type |
| `MultipleCommandHandlersFoundException` | `dk.trustworks.essentials.reactive.command` | Thrown when multiple handlers found for command type |
| `SendCommandException` | `dk.trustworks.essentials.reactive.command` | Thrown when command execution fails |
| **Interceptors** | | |
| `CommandBusInterceptor` | `dk.trustworks.essentials.reactive.command.interceptor` | Command interception interface |
| `CommandBusInterceptorChain` | `dk.trustworks.essentials.reactive.command.interceptor` | Interceptor chain interface |
| **Spring Integration** | | |
| `ReactiveHandlersBeanPostProcessor` | `dk.trustworks.essentials.reactive.spring` | Auto-register Spring beans as handlers |
| `AsyncEventHandler` | `dk.trustworks.essentials.reactive.spring` | Annotation marking async event handlers |

---

## LocalEventBus API

### EventBus Interface

**Package:** `dk.trustworks.essentials.reactive`
**Extends:** `Lifecycle` (`dk.trustworks.essentials.shared.functional.Lifecycle`)

```java
public interface EventBus extends Lifecycle {
    EventBus publish(Object event);
    EventBus addAsyncSubscriber(EventHandler subscriber);
    EventBus addSyncSubscriber(EventHandler subscriber);
    EventBus removeAsyncSubscriber(EventHandler subscriber);
    EventBus removeSyncSubscriber(EventHandler subscriber);
    boolean hasAsyncSubscriber(EventHandler subscriber);
    boolean hasSyncSubscriber(EventHandler subscriber);
}
```

### LocalEventBus Implementation

**Package:** `dk.trustworks.essentials.reactive`
**Implements:** `EventBus`

```java
// Constructors
LocalEventBus(String busName)
LocalEventBus(String busName, OnErrorHandler onErrorHandler)
LocalEventBus(String busName, int parallelThreads, OnErrorHandler onErrorHandler)
LocalEventBus(String busName, int parallelThreads, int backpressureBufferSize)
LocalEventBus(String busName, int parallelThreads, int backpressureBufferSize,
              OnErrorHandler onErrorHandler, int overflowMaxRetries, double queuedTaskCapFactor)

// Builder API
static Builder builder()

class Builder {
    Builder busName(String name)                      // Default: "default"
    Builder parallelThreads(int threads)              // Default: 1-4 depending on CPU
    Builder backpressureBufferSize(int size)          // Default: 1024
    Builder overflowMaxRetries(int retries)           // Default: 20
    Builder queuedTaskCapFactor(double factor)        // Default: 1.5
    Builder onErrorHandler(OnErrorHandler handler)
    LocalEventBus build()
}

// Instance methods
String getName()

// Constants
static final int DEFAULT_BACKPRESSURE_BUFFER_SIZE = 1024
static final int DEFAULT_OVERFLOW_MAX_RETRIES = 20
static final double QUEUED_TASK_CAP_FACTOR = 1.5d
```

### Event Handler Types

**Package:** `dk.trustworks.essentials.reactive`

```java
// Event handler functional interface
@FunctionalInterface
public interface EventHandler {
    void handle(Object event);
}

// Error callback for async handlers
@FunctionalInterface
public interface OnErrorHandler {
    void handle(EventHandler failingSubscriber, Object event, Exception exception);
}
```

### AnnotatedEventHandler

**Package:** `dk.trustworks.essentials.reactive`
**Implements:** `EventHandler`

Base class for annotation-based event routing. Uses `PatternMatchingMethodInvoker` from `dk.trustworks.essentials.shared.reflection` with `InvocationStrategy.InvokeMostSpecificTypeMatched`.

```java
public abstract class AnnotatedEventHandler implements EventHandler {
    void handle(Object event);  // Dispatches to @Handler methods matching event type
}
```

**Usage pattern:**
```java
public class OrderEventHandler extends AnnotatedEventHandler {
    @Handler
    void handle(OrderCreated event) { ... }

    @Handler
    void handle(OrderCancelled event) { ... }

    @Handler
    void onOrderEvent(OrderEvent event) { /* Fallback for OrderEvent hierarchy */ }
}
```

### Handler Annotation

**Package:** `dk.trustworks.essentials.reactive`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Handler {}
```

### Sync vs Async Handlers

| Subscriber Type | Thread | Transaction | Error Behavior | Use Case |
|-----------------|--------|-------------|----------------|----------|
| **Sync** (`addSyncSubscriber`) | Publisher thread | Same `UnitOfWork` | Exception propagates, rolls back | Critical logic, data consistency |
| **Async** (`addAsyncSubscriber`) | Bounded elastic pool | Separate | `OnErrorHandler` callback | Notifications, I/O, non-critical |

---

## LocalCommandBus API

### CommandBus Interface

**Package:** `dk.trustworks.essentials.reactive.command`

```java
public interface CommandBus {
    // Interceptor management
    List<CommandBusInterceptor> getInterceptors();
    CommandBus addInterceptor(CommandBusInterceptor interceptor);
    CommandBus addInterceptors(List<CommandBusInterceptor> interceptors);
    boolean hasInterceptor(CommandBusInterceptor interceptor);
    CommandBus removeInterceptor(CommandBusInterceptor interceptor);

    // Handler management
    CommandBus addCommandHandler(CommandHandler handler);
    CommandBus removeCommandHandler(CommandHandler handler);
    boolean hasCommandHandler(CommandHandler handler);
    CommandHandler findCommandHandlerCapableOfHandling(Object command);

    // Command sending
    <R, C> R send(C command);                            // Sync, blocks until complete
    <R, C> Mono<R> sendAsync(C command);                 // Async with reactor.core.publisher.Mono<result>
    <C> void sendAndDontWait(C command);                 // Fire-and-forget, non-durable
    <C> void sendAndDontWait(C command, Duration delay); // Delayed fire-and-forget (java.time.Duration)
}
```

### LocalCommandBus Implementation

**Package:** `dk.trustworks.essentials.reactive.command`
**Implements:** `CommandBus`

⚠️ **Non-durable**: `sendAndDontWait` commands lost on JVM restart. Use `DurableLocalCommandBus` from [foundation](./LLM-foundation.md) for durability.

```java
// Constructors
LocalCommandBus()
LocalCommandBus(SendAndDontWaitErrorHandler errorHandler)
LocalCommandBus(List<CommandBusInterceptor> interceptors)
LocalCommandBus(SendAndDontWaitErrorHandler errorHandler, List<CommandBusInterceptor> interceptors)
LocalCommandBus(CommandBusInterceptor... interceptors)
LocalCommandBus(SendAndDontWaitErrorHandler errorHandler, CommandBusInterceptor... interceptors)
```

**Note:** Interceptors automatically sorted by `@InterceptorOrder` annotation on registration.

### Send Methods

| Method | Blocking | Returns | Error Behavior | Durability |
|--------|----------|---------|----------------|------------|
| `send(cmd)` | Yes | `<R>` Result | Exception to caller | N/A |
| `sendAsync(cmd)` | No | `Mono<R>` (`reactor.core.publisher.Mono`) | Exception in Mono error channel | N/A |
| `sendAndDontWait(cmd)` | No | void | `SendAndDontWaitErrorHandler` callback | ❌ Lost on restart |
| `sendAndDontWait(cmd, delay)` | No | void | `SendAndDontWaitErrorHandler` callback | ❌ Lost on restart |

### CommandHandler Types

**Package:** `dk.trustworks.essentials.reactive.command`

```java
// Interface-based (single command)
public interface CommandHandler {
    boolean canHandle(Class<?> commandType);
    Object handle(Object command);
}

// Annotation-based (multiple commands)
public abstract class AnnotatedCommandHandler implements CommandHandler {
    boolean canHandle(Class<?> commandType);  // Checks @Handler/@CmdHandler methods
    Object handle(Object command);            // Dispatches to matching method
}
```

Uses `PatternMatchingMethodInvoker` from `dk.trustworks.essentials.shared.reflection` with `InvocationStrategy.InvokeMostSpecificTypeMatched`.

**Annotation-based example:**
```java
public class OrderCommandHandler extends AnnotatedCommandHandler {
    @Handler  // or @CmdHandler
    private OrderId handle(CreateOrder cmd) { return orderRepository.save(...).getId(); }

    @CmdHandler  // Alias for @Handler
    private void handle(CancelOrder cmd) { order.cancel(cmd.reason()); }
}
```

### Handler Annotations

**Package:** `dk.trustworks.essentials.reactive` (`@Handler`) and `dk.trustworks.essentials.reactive.command` (`@CmdHandler`)

```java
// Used in both event and command handlers
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Handler {}

// Alias for @Handler, semantic clarity for command handlers
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdHandler {}
```

### Error Handling

**Package:** `dk.trustworks.essentials.reactive.command`

**SendAndDontWaitErrorHandler Interface:**
```java
public interface SendAndDontWaitErrorHandler {
    void handleError(Throwable exception, Object commandMessage, CommandHandler handler);
}
```

**Implementations:**
```java
// Logs error, swallows exception (no retry)
class FallbackSendAndDontWaitErrorHandler implements SendAndDontWaitErrorHandler {
    // Package: dk.trustworks.essentials.reactive.command
}

// Logs error, rethrows exception (enables retry with DurableQueues)
class RethrowingSendAndDontWaitErrorHandler implements SendAndDontWaitErrorHandler {
    // Package: dk.trustworks.essentials.reactive.command
}
```

### Exceptions

**Package:** `dk.trustworks.essentials.reactive.command`

```java
// No handler found for command type
public class NoCommandHandlerFoundException extends RuntimeException {
    NoCommandHandlerFoundException(Class<?> commandType, String message)
    Class<?> getCommandType()
}

// Multiple handlers found (violates single-handler rule)
public class MultipleCommandHandlersFoundException extends RuntimeException {
    MultipleCommandHandlersFoundException(Class<?> commandType, String message)
    Class<?> getCommandType()
}

// Command execution failed
public class SendCommandException extends RuntimeException {
    SendCommandException(Object command, Throwable cause)
    Object getCommand()
}
```

---

## Command Interceptors

### CommandBusInterceptor Interface

**Package:** `dk.trustworks.essentials.reactive.command.interceptor`

```java
public interface CommandBusInterceptor {
    default Object interceptSend(Object command, CommandBusInterceptorChain chain) {
        return chain.proceed();
    }

    default Object interceptSendAsync(Object command, CommandBusInterceptorChain chain) {
        return chain.proceed();
    }

    default void interceptSendAndDontWait(Object commandMessage, CommandBusInterceptorChain chain) {
        chain.proceed();
    }
}
```

### CommandBusInterceptorChain Interface

**Package:** `dk.trustworks.essentials.reactive.command.interceptor`

```java
public interface CommandBusInterceptorChain<R> {
    R proceed();
    Object command();
    CommandHandler commandHandler();
}
```

### Interceptor Ordering

Uses `@InterceptorOrder` annotation from `dk.trustworks.essentials.shared.interceptor` package. **Lower value = higher priority (runs first)**.

```java
@InterceptorOrder(1)   // Runs FIRST
public class SecurityInterceptor implements CommandBusInterceptor { ... }

@InterceptorOrder(10)  // Runs SECOND (default order is 10)
public class LoggingInterceptor implements CommandBusInterceptor { ... }
```

⚠️ Interceptors automatically sorted by `CommandBus` on registration.

### Common Interceptor Patterns

**UnitOfWork Interceptor:**
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

**Security Interceptor:**
```java
@InterceptorOrder(1)
public class SecurityInterceptor implements CommandBusInterceptor {
    @Override
    public Object interceptSend(Object command, CommandBusInterceptorChain chain) {
        if (!securityContext.hasPermission(command)) {
            throw new UnauthorizedException();
        }
        return chain.proceed();
    }
}
```

---

## Spring Integration

### ReactiveHandlersBeanPostProcessor

**Location:** `dk.trustworks.essentials.reactive.spring.ReactiveHandlersBeanPostProcessor`

Auto-discovers and registers handler beans. Eliminates manual `addCommandHandler()`/`addSyncSubscriber()` calls.

**Behavior:**
1. Scans all Spring beans during initialization
2. Finds beans implementing `CommandHandler` or `EventHandler`
3. Auto-registers with appropriate bus
4. Auto-unregisters on bean destruction

**Requirements:**
- Exactly one `CommandBus` bean in context (for command handlers)
- One or more `EventBus` beans in context (event handlers register with ALL)

**Configuration:**
```java
@Configuration
public class ReactiveConfig {
    @Bean
    static ReactiveHandlersBeanPostProcessor reactiveHandlersBeanPostProcessor() {
        return new ReactiveHandlersBeanPostProcessor();
    }

    @Bean
    public LocalEventBus localEventBus() {
        return LocalEventBus.builder()
            .busName("ApplicationEvents")
            .parallelThreads(10)
            .onErrorHandler((subscriber, event, ex) -> log.error("Error", ex))
            .build();
    }

    @Bean
    public LocalCommandBus localCommandBus() {
        return new LocalCommandBus();
    }
}
```

### AsyncEventHandler Annotation

**Package:** `dk.trustworks.essentials.reactive.spring`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AsyncEventHandler {}
```

**Usage:**
```java
@Component
@AsyncEventHandler  // Marks for async registration
public class NotificationHandler implements EventHandler {
    void handle(Object event) { ... }
}
```

### Registration Rules

| Bean Type | Annotation | Registration Method |
|-----------|------------|---------------------|
| `CommandHandler` | (none) | `CommandBus.addCommandHandler()` |
| `EventHandler` | (none) | `EventBus.addSyncSubscriber()` |
| `EventHandler` | `@AsyncEventHandler` | `EventBus.addAsyncSubscriber()` |

**Example:**
```java
// Auto-registered as command handler
@Component
public class OrderCommandHandler implements CommandHandler { ... }

// Auto-registered as SYNC event handler
@Component
public class InventoryEventHandler implements EventHandler { ... }

// Auto-registered as ASYNC event handler
@Component
@AsyncEventHandler
public class NotificationHandler implements EventHandler { ... }
```

---

## Common Patterns

### Event Publishing with Sync/Async Handlers

```java
LocalEventBus eventBus = LocalEventBus.builder()
    .busName("OrderEvents")
    .parallelThreads(5)
    .build();

// Sync: Same thread, rolls back on error
eventBus.addSyncSubscriber(event -> {
    if (event instanceof OrderCreated created) {
        inventoryService.reserve(created.orderId()); // Part of transaction
    }
});

// Async: Separate thread, OnErrorHandler on error
eventBus.addAsyncSubscriber(event -> {
    if (event instanceof OrderCreated created) {
        emailService.sendConfirmation(created); // Fire-and-forget
    }
});

eventBus.publish(new OrderCreated(orderId, customerId));
```

### Command Handler with Interceptor

```java
// Interceptor wraps all commands in UnitOfWork
LocalCommandBus commandBus = new LocalCommandBus(
    new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory)
);

// Handler doesn't manage UnitOfWork
public class CreateOrderHandler implements CommandHandler {
    @Override
    public Object handle(Object command) {
        // Already in UnitOfWork from interceptor
        return orderRepository.save(Order.create(...)).getId();
    }
}
```

### Multi-Command Handler with Events

```java
@Component
public class OrderCommandHandler extends AnnotatedCommandHandler {
    private final OrderRepository orderRepository;
    private final EventBus eventBus;

    @CmdHandler
    private OrderId handle(CreateOrder cmd) {
        Order order = Order.create(cmd.customerId(), cmd.items());
        orderRepository.save(order);
        eventBus.publish(new OrderCreated(order.getId()));
        return order.getId();
    }

    @CmdHandler
    private void handle(CancelOrder cmd) {
        Order order = orderRepository.findById(cmd.orderId())
            .orElseThrow(() -> new OrderNotFoundException(cmd.orderId()));
        order.cancel(cmd.reason());
        orderRepository.save(order);
        eventBus.publish(new OrderCancelled(order.getId(), cmd.reason()));
    }
}
```

---

## Dependencies & Integration

### Module Dependencies

| Module | Classes Used | Package |
|--------|--------------|---------|
| **[shared](./LLM-shared.md)** | `InterceptorChain` | `dk.trustworks.essentials.shared.interceptor` |
| | `PatternMatchingMethodInvoker` | `dk.trustworks.essentials.shared.reflection` |
| | `Lifecycle` | `dk.trustworks.essentials.shared.functional` |
| | `FailFast` | `dk.trustworks.essentials.shared` |
| | `@InterceptorOrder` | `dk.trustworks.essentials.shared.interceptor` |
| **reactor-core** (provided) | `Mono`, `Flux` | `reactor.core.publisher` |
| | `Schedulers` | `reactor.core.scheduler` |
| **spring-context** (provided, optional) | `BeanPostProcessor` | `org.springframework.beans.factory.config` |
| | `ApplicationContextAware` | `org.springframework.context` |

### Downstream Modules

| Module | Classes | Package |
|--------|---------|---------|
| **[foundation](./LLM-foundation.md)** | `DurableLocalCommandBus` extends `AbstractCommandBus` | `dk.trustworks.essentials.components.foundation.messaging` |
| **[postgresql-event-store](./LLM-postgresql-event-store.md)** | Event processors use `LocalEventBus` | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor` |
| **[eventsourced-aggregates](./LLM-eventsourced-aggregates.md)** | Aggregate repositories publish events | `dk.trustworks.essentials.components.eventsourced.aggregates` |

---

## Thread Safety

| Class | Thread Safety |
|-------|---------------|
| `LocalEventBus` | ✅ Thread-safe (concurrent maps, Reactor scheduling) |
| `LocalCommandBus` | ✅ Thread-safe (concurrent handler cache, Reactor scheduling) |
| Handler implementations | ⚠️ User responsibility |

---

## Gotchas

- ⚠️ `LocalCommandBus.sendAndDontWait()` (`dk.trustworks.essentials.reactive.command.LocalCommandBus`) is **non-durable** - commands lost on JVM restart. Use `DurableLocalCommandBus` (`dk.trustworks.essentials.components.foundation.messaging.DurableLocalCommandBus`) from [foundation](./LLM-foundation.md) for persistence.
- ⚠️ Each command type requires **exactly one handler** - throws `NoCommandHandlerFoundException` or `MultipleCommandHandlersFoundException` (both in `dk.trustworks.essentials.reactive.command`).
- ⚠️ Sync event handler exceptions **propagate to caller and roll back** the publishing `UnitOfWork`.
- ⚠️ Async event handler exceptions go to `OnErrorHandler` (`dk.trustworks.essentials.reactive.OnErrorHandler`) - **no automatic retry**.
- ⚠️ `@AsyncEventHandler` (`dk.trustworks.essentials.reactive.spring.AsyncEventHandler`) annotation only works with `ReactiveHandlersBeanPostProcessor` in Spring context. Manual registration uses `addAsyncSubscriber()`.
- ⚠️ Interceptor order: **lower `@InterceptorOrder` (`dk.trustworks.essentials.shared.interceptor.InterceptorOrder`) = higher priority** (runs first). Default order is 10.
- ⚠️ `AnnotatedEventHandler`/`AnnotatedCommandHandler` use reflection via `PatternMatchingMethodInvoker` (`dk.trustworks.essentials.shared.reflection.PatternMatchingMethodInvoker`) - consider performance for high-throughput scenarios.
- ⚠️ Event handlers registered with `ReactiveHandlersBeanPostProcessor` (`dk.trustworks.essentials.reactive.spring.ReactiveHandlersBeanPostProcessor`) are added to **ALL** `EventBus` beans in the context.
- ⚠️ `LocalCommandBus` automatically sorts interceptors on add - manual ordering not needed.

---

## See Also

- [README.md](../reactive/README.md) - Full documentation with motivation, examples, best practices
- [LLM-shared.md](./LLM-shared.md) - `InterceptorChain`, `PatternMatchingMethodInvoker`, `Lifecycle`
- [LLM-foundation.md](./LLM-foundation.md) - `DurableLocalCommandBus`, `UnitOfWork`, `DurableQueues`
- [LLM-postgresql-event-store.md](./LLM-postgresql-event-store.md) - Event processors using `LocalEventBus`

**Test References:**
- `LocalEventBusTest.java` - Event bus usage patterns
- `AnnotatedEventHandlerTest.java` - Annotation-based event handlers
- `LocalCommandBusTest.java` - Command bus usage patterns
- `AnnotatedCommandHandlerTest.java` - Annotation-based command handlers
- `DefaultCommandBusInterceptorChainTest.java` - Interceptor patterns
- `ReactiveHandlersBeanPostProcessorTest.java` - Spring integration
