# Reactive - LLM Reference

> Token-efficient reference for LLMs. For detailed explanations, see [README.md](../reactive/README.md).

## Quick Facts
- **Package**: `dk.trustworks.essentials.reactive`
- **Purpose**: In-memory event bus and command bus for event-driven JVM applications
- **Dependencies**: `reactor-core` (provided), optional `spring-context` (provided)
- **Foundation**: Uses `shared` module's `InterceptorChain` and `PatternMatchingMethodInvoker`

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>reactive</artifactId>
</dependency>
```

**Dependencies from other modules**:
- `InterceptorChain`, `PatternMatchingMethodInvoker`, `Lifecycle` from [shared](./LLM-shared.md)

## TOC
- [Core Components](#core-components)
- [LocalEventBus API](#localeventbus-api)
- [LocalCommandBus API](#localcommandbus-api)
- [Command Interceptors](#command-interceptors)
- [Spring Integration](#spring-integration)
- [Common Patterns](#common-patterns)
- [Gotchas](#gotchas)

---

## Core Components

Base package: `dk.trustworks.essentials.reactive`

| Class | Package Suffix         | Purpose |
|-------|------------------------|---------|
| **Event Bus** |                        | |
| `EventBus` | .                      | Event bus interface |
| `LocalEventBus` | .                      | Pub/sub bus with sync/async subscribers |
| `EventHandler` | .                      | Event subscriber functional interface |
| `AnnotatedEventHandler` | .                      | `@Handler` annotation routing base class |
| `OnErrorHandler` | .                      | Async handler error callback |
| `Handler` | .                      | Annotation for event/command handler methods |
| **Command Bus** |                        | |
| `CommandBus` | `.command`             | Command bus interface |
| `LocalCommandBus` | `.command`             | CQRS bus (single handler per command) |
| `AbstractCommandBus` | `.command`             | Abstract base with caching |
| `CommandHandler` | `.command`             | Command processor interface |
| `AnnotatedCommandHandler` | `.command`             | `@Handler`/`@CmdHandler` routing base class |
| `SendAndDontWaitErrorHandler` | `.command`             | Fire-and-forget error callback interface |
| `CmdHandler` | `.command`             | Annotation (alias for `@Handler`) |
| **Exceptions** |                        | |
| `NoCommandHandlerFoundException` | `.command`             | No handler found for command type |
| `MultipleCommandHandlersFoundException` | `.command`             | Multiple handlers found |
| `SendCommandException` | `.command`             | Command execution failed |
| **Interceptors** |                        | |
| `CommandBusInterceptor` | `.command.interceptor` | Command interception interface |
| `CommandBusInterceptorChain` | `.command.interceptor` | Interceptor chain interface |
| **Spring Integration** |                        | |
| `ReactiveHandlersBeanPostProcessor` | `.spring`              | Auto-register Spring beans as handlers |
| `AsyncEventHandler` | `.spring`              | Annotation marking async event handlers |

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

Base class for annotation-based event routing. Uses `PatternMatchingMethodInvoker` from `dk.trustworks.essentials.shared.reflection` with `InvocationStrategy.InvokeMostSpecificTypeMatched`.

```java
public abstract class AnnotatedEventHandler implements EventHandler {
    void handle(Object event);  // Dispatches to @Handler methods matching event type
}
```

**Pattern:**
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

    // Handler inspection - for infrastructure (diagnostics, startup checks), not routing. Since 0.60.1, default methods
    default List<CommandHandler> getCommandHandlers();                                  // Snapshot, registration order; default List.of()
    default List<CommandHandler> findCommandHandlersCapableOfHandling(Class<?> commandType); // No throw, no cache
    default Map<Class<?>, List<CommandHandler>> findCommandTypesHandledByMoreThanOneCommandHandler(CommandHandler handler);

    // Command sending
    <R, C> R send(C command);                            // Sync, blocks until complete
    <R, C> Mono<R> sendAsync(C command);                 // Async with reactor.core.publisher.Mono<result>
    <C> void sendAndDontWait(C command);                 // Fire-and-forget, non-durable
    <C> void sendAndDontWait(C command, Duration delay); // Delayed fire-and-forget (java.time.Duration)
}
```

**Handler inspection (since 0.60.1):** `getCommandHandlers()` lists the registered handlers. Its `default` returns an
empty list for a third-party `CommandBus` that does not track them - read that as "unknown", not "none".
`AbstractCommandBus` (`LocalCommandBus`, `DurableLocalCommandBus`) returns them all. A `CommandHandler` can declare its
command types through `default Set<Class<?>> handledCommandTypes()` (empty by default; used only for diagnostics, never
routing). `AnnotatedCommandHandler` returns its `@CmdHandler`/`@Handler` parameter types, and
`EventStreamDeciderCommandHandlerAdapter` its deciders' `COMMAND` type arguments.
`findCommandTypesHandledByMoreThanOneCommandHandler(handler)` checks those declared types: which of them `handler` and
another registered handler can both `canHandle`. An overlap on a type nobody declares is not found.

**Duplicate handlers WARN at registration (since 0.60.1):** when `addCommandHandler` makes a command type handled by more
than one handler, `AbstractCommandBus` logs a WARN naming the type and handlers. Every send of that type fails with
`MultipleCommandHandlersFoundException`. The registration still succeeds, because an overlap that is never sent keeps
working. If the check itself fails, that is logged at ERROR and never fails the registration.

**Thread safety:** `addCommandHandler`/`removeCommandHandler` are safe from any number of threads, also while commands are
being sent. A command type resolved during a registration is never cached past it.

### LocalCommandBus Implementation

**Package:** `dk.trustworks.essentials.reactive.command`
**Extends:** `AbstractCommandBus`

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

**Note:** Interceptors automatically sorted by `@InterceptorOrder` on registration.

### Send Methods

| Method | Blocking | Returns | Error Behavior | Durability |
|--------|----------|---------|----------------|------------|
| `send(cmd)` | Yes | `<R>` Result | Exception to caller (wrapped - see [Exceptions arrive wrapped](#exceptions-arrive-wrapped-in-unitofworkexception)) | N/A |
| `sendAsync(cmd)` | No | `Mono<R>` | Exception in Mono error channel (wrapped likewise) | N/A |
| `sendAndDontWait(cmd)` | No | void | `SendAndDontWaitErrorHandler` callback | ❌ Lost on restart |
| `sendAndDontWait(cmd, delay)` | No | void | `SendAndDontWaitErrorHandler` callback | ❌ Lost on restart |

**Threads**: only `send` runs the handler on the caller's thread. `sendAsync` and `sendAndDontWait` run it on Reactor's
`boundedElastic` (`publishOn` fuses with `Mono.fromCallable`), so `ThreadLocal`/`ScopedValue` context does not reach
the handler on its own. Carry it with a `CommandContextPropagator`:

```java
public interface CommandContextPropagator {
    // Called on the SENDING thread; return an invocation that restores the captured context around the handler
    <R> Callable<R> propagate(Callable<R> handlerInvocation);
}

commandBus.addContextPropagator(myPropagator);   // AbstractCommandBus; first added wraps outermost
```

The propagator captures at *send*, so a `sendAsync` `Mono` subscribed later still carries the sender's context.
[foundation](./LLM-foundation.md#event-causation) supplies `CausationCommandContextPropagator`.

### When a handler throws (rejections)

With foundation's `UnitOfWorkControllingCommandBusInterceptor` (both Spring Boot starters add it to their
`DurableLocalCommandBus`), `send`, `sendAsync` and `sendAndDontWait` each run the handler inside
`unitOfWorkFactory.withUnitOfWork(...)`. A throw from the handler therefore:

- **rolls back the handler's own writes** when the interceptor opened the `UnitOfWork` (none was active).
- **marks an outer `UnitOfWork` rollback-only** when `send` joined one the caller opened with
  `withUnitOfWork`/`usingUnitOfWork` - even if the caller catches the exception. That `UnitOfWork` then rolls back
  instead of committing, with no error. Not so under a Spring `@Transactional`: `SpringTransactionAwareUnitOfWork`
  ignores the mark for a transaction Spring manages, so a caller that catches the exception commits whatever the
  handler wrote before it threw.
- under `sendAsync` never touches the caller's `UnitOfWork`: the handler runs on another thread in its own, and the
  `Mono` signals the error.
- under `sendAndDontWait` **rolls back the handler's writes on every bus and with every `SendAndDontWaitErrorHandler`**
  (since 0.60.1). The exception unwinds through the interceptor chain first - so the `UnitOfWork` rolls back, or an
  outer one the delivery runs in is marked rollback-only - and only then reaches the `SendAndDontWaitErrorHandler`,
  as the exception the handler threw (not the `UnitOfWorkException` wrapper), exactly once. What the error handler does
  next decides only whether the command is tried again:
  - on a `DurableLocalCommandBus` with the default `RethrowingSendAndDontWaitErrorHandler`, the rethrow reaches the
    durable queue, which **redelivers** the command per its `RedeliveryPolicy` (default `DEFAULT_REDELIVERY_POLICY`:
    linear backoff 150 ms, capped at 1 s, 20 redeliveries), then dead-letters it. The queue's built-in permanent-error
    list applies, so an `IllegalArgumentException` dead-letters at once (see
    [LLM-foundation.md § The built-in permanent-error list](./LLM-foundation.md#the-built-in-permanent-error-list)).
  - an error handler that does not rethrow (the plain `LocalCommandBus` default `FallbackSendAndDontWaitErrorHandler`,
    or one you set on a `DurableLocalCommandBus`) logs and drops the failure: nothing is retried, nothing is committed.

  Before 0.60.1 the bus called the error handler *inside* the chain, so a non-rethrowing one made the handler look
  successful and the `UnitOfWork` committed whatever it wrote before throwing - by default on a plain
  `LocalCommandBus`. An interceptor that throws on its own (not the handler) still bypasses the error handler; on a
  `DurableLocalCommandBus` the queue's `RedeliveryPolicy` handles that failure. An interceptor's
  `interceptSendAndDontWait` now sees the handler's exception come out of `chain.proceed()`, as it already did for
  `send`/`sendAsync` - `RecordExecutionTimeCommandBusInterceptor` times failed deliveries the same way.

So a throw is for a failure, not for a business rejection that must persist (an audit row, a "rejected" status): the
rollback discards it, and `sendAndDontWait` retries it. Return an outcome instead - an `EventStreamDecider` emits a
rejection event (e.g. `OrderRejected`), which is appended and which `send()` returns (see
[LLM-eventsourced-aggregates.md § Command Sending](./LLM-eventsourced-aggregates.md#command-sending)).

### Exceptions arrive wrapped in `UnitOfWorkException`

Under the UnitOfWork interceptor, the handler's exception does not reach the caller as itself:
`withUnitOfWork` wraps whatever it catches in `UnitOfWorkException` (foundation, `...foundation.transaction`), one level
per enclosing `withUnitOfWork`/`usingUnitOfWork` - the interceptor's, plus every outer one the exception passes
through. `send` called inside your own `withUnitOfWork` arrives as `UnitOfWorkException → UnitOfWorkException →
DomainException`. So `catch (DomainException e)` around `send` never matches (`AnnotatedCommandHandler` already
unwraps the reflective `InvocationTargetException`, so that is not the extra level).

`AnnotatedCommandHandler` logs the exception it rethrows at DEBUG (since 0.61; ERROR with stack trace before), because the
sender reports it: `send` throws it, a `sendAndDontWait`'s `SendAndDontWaitErrorHandler` logs it, a durable command bus
keeps it on the queued message and logs the dead letter. Override `getExceptionLogLevel(Exception)` to log here too.

```java
try {
    commandBus.send(new ConfirmOrder(orderId));
} catch (RuntimeException e) {
    if (Exceptions.doesStackTraceContainExceptionOfType(e, OrderNotConfirmable.class)) {   // dk.trustworks.essentials.shared
        return ResponseEntity.unprocessableEntity().build();
    }
    throw e;
}
```

`doesStackTraceContainExceptionOfType` walks the whole cause chain and answers `boolean`; walk `getCause()` yourself to
get the instance. For a queue consumer, classify by type with
`MessageDeliveryErrorHandler.builder().stopRedeliveryOn(OrderNotConfirmable.class).build()`, which also matches anywhere
in the cause chain (see [LLM-foundation.md § MessageDeliveryErrorHandler](./LLM-foundation.md#messagedeliveryerrorhandler)).

### CommandHandler Types

**Package:** `dk.trustworks.essentials.reactive.command`

```java
// Interface-based (single command)
public interface CommandHandler {
    boolean canHandle(Class<?> commandType);
    Object handle(Object command);
    default Set<Class<?>> handledCommandTypes();  // Diagnostics only (duplicate-handler WARN); default empty. Since 0.60.1
}

// Annotation-based (multiple commands)
public abstract class AnnotatedCommandHandler implements CommandHandler {
    boolean canHandle(Class<?> commandType);  // Checks @Handler/@CmdHandler methods
    Object handle(Object command);            // Dispatches to matching method
    Set<Class<?>> handledCommandTypes();      // The @Handler/@CmdHandler parameter types
}
```

Uses `PatternMatchingMethodInvoker` from `dk.trustworks.essentials.shared.reflection` with `InvocationStrategy.InvokeMostSpecificTypeMatched`.

**Pattern:**
```java
public class OrderCommandHandler extends AnnotatedCommandHandler {
    @Handler  // or @CmdHandler
    private OrderId handle(CreateOrder cmd) { return orderRepository.save(...).getId(); }

    @CmdHandler  // Alias for @Handler
    private void handle(CancelOrder cmd) { order.cancel(cmd.reason()); }
}
```

### Handler Annotations

**Packages:** `dk.trustworks.essentials.reactive` (`@Handler`), `dk.trustworks.essentials.reactive.command` (`@CmdHandler`)

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

```java
public interface SendAndDontWaitErrorHandler {
    void handleError(Throwable exception, Object commandMessage, CommandHandler handler);
}
```

**Implementations:**
- `FallbackSendAndDontWaitErrorHandler` - Logs error, swallows exception (no retry)
- `RethrowingSendAndDontWaitErrorHandler` - Logs error, rethrows exception (enables retry with DurableQueues)

Since 0.60.1 `handleError` runs after the handler's exception has unwound through the interceptor chain, so any
`UnitOfWork` an interceptor opened is already rolled back whichever implementation you use; it receives the handler's
own exception, never a wrapper (see [When a handler throws](#when-a-handler-throws-rejections)).

### Exceptions

**Package:** `dk.trustworks.essentials.reactive.command`

```java
// Base of the two routing failures below - abstract, never thrown itself
public abstract class SendCommandException extends RuntimeException {
    public final Class<?> commandType;
    public SendCommandException(Class<?> commandType, String message)
    public SendCommandException(Class<?> commandType, String message, Exception e)
}

// No handler found for command type
public final class NoCommandHandlerFoundException extends SendCommandException {
    public NoCommandHandlerFoundException(Class<?> commandType, String message)
}

// Multiple handlers found (violates single-handler rule)
public final class MultipleCommandHandlersFoundException extends SendCommandException {
    public MultipleCommandHandlersFoundException(Class<?> commandType, String message)
}
```

Both are thrown by `findCommandHandlerCapableOfHandling`, before any interceptor runs, so they reach the caller
unwrapped. A failing handler's own exception is **not** wrapped in a `SendCommandException` - see
[Exceptions arrive wrapped](#exceptions-arrive-wrapped-in-unitofworkexception).

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

Uses `@InterceptorOrder` from `dk.trustworks.essentials.shared.interceptor`. **Lower value = higher priority (runs first)**.

```java
@InterceptorOrder(1)   // Runs FIRST
public class SecurityInterceptor implements CommandBusInterceptor { ... }

@InterceptorOrder(10)  // Runs SECOND (default order is 10)
public class LoggingInterceptor implements CommandBusInterceptor { ... }
```

⚠️ Interceptors automatically sorted by `CommandBus` on registration.

### Common Interceptor Patterns

**UnitOfWork Interceptor** (example using foundation module):
```java
// NOTE: UnitOfWorkControllingCommandBusInterceptor is from foundation module
// Package: dk.trustworks.essentials.components.foundation.reactive.command
import dk.trustworks.essentials.components.foundation.reactive.command.UnitOfWorkControllingCommandBusInterceptor;

LocalCommandBus commandBus = new LocalCommandBus(
    new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory)
);
```

**Custom Security Interceptor:**
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

**Package:** `dk.trustworks.essentials.reactive.spring`

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

## Gotchas

- ⚠️ `LocalCommandBus.sendAndDontWait()` is **non-durable** - commands lost on JVM restart. Use `DurableLocalCommandBus` from [foundation](./LLM-foundation.md) for persistence.
- ⚠️ Each command type requires **exactly one handler** - throws `NoCommandHandlerFoundException` or `MultipleCommandHandlersFoundException`.
- ⚠️ Sync event handler exceptions **propagate to caller and roll back** the publishing `UnitOfWork`.
- ⚠️ Async event handler exceptions go to `OnErrorHandler` - **no automatic retry**.
- ⚠️ `@AsyncEventHandler` annotation only works with `ReactiveHandlersBeanPostProcessor` in Spring context. Manual registration uses `addAsyncSubscriber()`.
- ⚠️ Interceptor order: **lower `@InterceptorOrder` = higher priority** (runs first). Default order is 10.
- ⚠️ `AnnotatedEventHandler`/`AnnotatedCommandHandler` use reflection - consider performance for high-throughput scenarios.
- ⚠️ Event handlers registered with `ReactiveHandlersBeanPostProcessor` are added to **ALL** `EventBus` beans in the context.
- ⚠️ `LocalCommandBus` automatically sorts interceptors on add - manual ordering not needed.

---

## See Also

- [README.md](../reactive/README.md) - Full documentation with motivation, examples, best practices
- [LLM-shared.md](./LLM-shared.md) - `InterceptorChain`, `PatternMatchingMethodInvoker`, `Lifecycle`
- [LLM-foundation.md](./LLM-foundation.md) - `DurableLocalCommandBus`, `UnitOfWork`, `DurableQueues`

**Test References:**
- `LocalEventBusTest.java` - Event bus usage patterns
- `AnnotatedEventHandlerTest.java` - Annotation-based event handlers
- `LocalCommandBusTest.java` - Command bus usage patterns
- `AnnotatedCommandHandlerTest.java` - Annotation-based command handlers
- `DefaultCommandBusInterceptorChainTest.java` - Interceptor patterns
- `ReactiveHandlersBeanPostProcessorTest.java` - Spring integration
