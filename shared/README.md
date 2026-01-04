# Shared Module

> Zero-dependency utilities and functional programming primitives for Java 17+

This module provides the foundational building blocks for all Essentials libraries. All utilities are framework-independent with no third-party dependencies (except SLF4J API as `provided`).

> **NOTE:** This library is WORK-IN-PROGRESS

**LLM Context:** [LLM-shared.md](../LLM/LLM-shared.md)

## Table of Contents
- [Installation](#installation)
- [Quick Reference](#quick-reference)
- [Validation](#validation)
- [Functional Programming](#functional-programming)
- [Collections & Streams](#collections--streams)
- [Exception Handling](#exception-handling)
- [Message Formatting](#message-formatting)
- [Reflection](#reflection)
- [Time & Measurement](#time--measurement)
- [Concurrency](#concurrency)
- [Control Flow](#control-flow)
- ⚠️ [Security](#security)
- [Additional Utilities](#additional-utilities)

## Installation

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>shared</artifactId>
    <version>${essentials.version}</version>
</dependency>
```

Optional dependency (already available in most projects):
```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
</dependency>
```

## Quick Reference

| Feature                        | Purpose                                                          |
|--------------------------------|------------------------------------------------------------------|
| **FailFast**                   | Enhanced argument validation (replaces `Objects.requireNonNull`) |
| **Tuples**                     | Immutable tuples (Pair, Triple, Quad) with map operations        |
| **Either**                     | Choice type representing one of two values                       |
| **Result**                     | `Either` specialization with success/error semantics             |
| **Checked***                   | Bridge checked exceptions to functional interfaces               |
| **Reflector**                  | High-performance caching reflection API                          |
| **StopWatch**                  | Time operations and methods                                      |
| **MeasurementRecorder**        | Pluggable measurement recording (logging, Micrometer)            |
| **Lists/Streams**              | Collection and stream utilities                                  |
| **InterceptorChain**           | Chain-of-responsibility pattern                                  |
| **MessageFormatter**           | SLF4J-style and named-parameter message formatting               |
| **MessageTemplate**            | Structured messages with typed parameters                        |
| **EssentialsSecurityProvider** | Role-based security abstraction                                  |
| **Lifecycle**                  | Common start/stop interface                                      |

## Validation

### FailFast

Replacement for `Objects.requireNonNull()` with better error messages and additional validation methods. All methods throw `IllegalArgumentException` on failure.

```java
import static dk.trustworks.essentials.shared.FailFast.*;

public Order createOrder(OrderId id, List<LineItem> items, CustomerId customerId) {
    requireNonNull(id, "Order ID is required");
    requireNonEmpty(items, "Order must have at least one line item");
    requireNonBlank(customerId, "Customer ID cannot be blank");
    requireTrue(items.size() <= 100, "Orders cannot exceed 100 items");

    return new Order(id, items, customerId);
}
```

**Available methods:**
- `requireNonNull(T, String)` - null check with custom message
- `requireNonBlank(CharSequence, String)` - non-empty string validation
- `requireTrue/requireFalse(boolean, String)` - boolean assertions
- `requireNonEmpty(Collection/Array/Map, String)` - collection validation
- `requireMustBeInstanceOf(Object, Class<T>)` - type checking

**Why use FailFast?** Unlike `Objects.requireNonNull()` which throws `NullPointerException`, FailFast throws `IllegalArgumentException` with clear messages, making debugging easier.

## Functional Programming

### Tuples

Immutable `Tuple`'s supporting 0-4 (`Empty`, `Single`, `Pair`/`Either`/`Result`, `Triple`, `Quad`) elements with type-safe operations.

```java
// Creating tuples
Pair<String, Integer> pair = Tuple.of("count", 42);
Triple<String, Long, BigDecimal> triple = Tuple.of("Product", 100L, new BigDecimal("19.99"));

// Accessing elements
String key = pair._1;     // or pair._1()
Integer value = pair._2;  // or pair._2()

// Mapping
Pair<String, String> mapped = pair.map(String::toUpperCase, Object::toString);
// Result: ("COUNT", "42")

Triple<String, String, String> allStrings = triple.map(Object::toString,
                                                       Object::toString,
                                                       Object::toString);

// Swapping pairs
Pair<Integer, String> swapped = pair.swap(); // (42, "count")

// Use in streams
Map<String, Integer> map = items.stream()
    .map(item -> Tuple.of(item.getName(), item.getQuantity()))
    .collect(Collectors.toMap(Pair::_1, Pair::_2));
```

**Learn more:** See [TupleTest.java](src/test/java/dk/trustworks/essentials/shared/functional/tuple/TupleTest.java) for comprehensive examples.

### Either
An immutable **choice** type representing one of two mutually exclusive values.  
*   Use its specialization `Result` for semantic success/error handling.
*   By convention, `_1` is "Left" (Error) and `_2` is "Right" (Success/Value).

#### `map` vs `flatMap`
Regardless of whether you are targeting the first (`1`) or second (`2`) slot:

*   **`map`**: Use for **simple transformations**. You provide a function that returns a plain value (e.g., `String` to `Integer`). The `Either` container handles the re-wrapping for you.
*   **`flatMap`**: Use for **logic chaining**. You provide a function that returns another `Either`. This "flattens" the result, preventing nested types like `Either<T1, Either<T1, R2>>`.

#### Slot Targeting (`1` vs `2`)
*   **`map1` / `flatMap1`**: Operates on the **first** slot (`T1`).
*   **`map2` / `flatMap2`**: Operates on the **second** slot (`T2`). Usually where the "Happy Path" logic lives.

#### Method Summary
| Method | Target | Logic | Short-circuits if... |
| :--- | :--- | :--- | :--- |
| **`map1`** | `_1` | Applies function to `_1`, stays in same "slot". | Never (applies to whatever is in `_1`). |
| **`flatMap1`** | `_1` | Applies function to `_1`, function determines the new state. | **`is_2()`** is true. |
| **`map2`** | `_2` | Applies function to `_2`, stays in same "slot". | Never (applies to whatever is in `_2`). |
| **`flatMap2`** | `_2` | Applies function to `_2`, function determines the new state. | **`is_1()`** is true. |

### Practical Example: `Either<Failure, User>`

1.  **`map2(User::getName)`**: 
    *   If it's a `User`, returns `Either<Failure, String>` (the user's name).
    *   If it's a `Failure`, it stays the same `Failure`.
2.  **`flatMap2(this::validateUser)`**: 
    *   If it's a `User`, runs validation. Validation returns a *new* `Either`.
    *   If validation fails, the whole object effectively "switches sides" to become a `Failure`.
3.  **`fold(onFail, onSuccess)`**:
    *   The "pattern match" exit point. Forces you to handle both cases to produce a final result (like a UI message).

> **In short:** Use **`map`** to change the **value**; use **`flatMap`** to potentially change the **outcome**.

### Practical Example
Imagine an `Either<Failure, User>`:

*   **`map2(User::getName)`**: Returns `Either<Failure, String>`. It just changes the user to their name if a user exists.
*   **`flatMap2(this::validateUser)`**: Returns `Either<Failure, User>`. If the user exists, it runs a validation that might *also* return a `Failure`. If it fails, the whole `Either` switches from a "User state" to a "Failure state".
*   **`flatMap1(...)`**: Would be used to handle or transform the error case while ignoring the success case.

In short: Use **`map`** to change the *data* inside a side; use **`flatMap`** to perform an operation that might *change the side* itself.

```java
// Creating Either values
Either<String, Integer> success = Either.of_2(42);
Either<String, Integer> error = Either.of_1("Invalid input");

// Checking which value is present
if (result.is_1()) {
    String errorMsg = result._1();
    logger.error("Operation failed: {}", errorMsg);
} else {
    Integer value = result._2();
    processValue(value);
}

// Conditional execution
result.ifIs_1(err -> handleError(err));
result.ifIs_2(value -> processValue(value));

// Pattern matching with fold
String message = result.fold(
    err -> "Error: " + err,
    value -> "Success: " + value
);

// Mapping individual elements
Either<String, Double> doubled = result.map2(v -> v * 2.0);
Either<String, Integer> mappedError = result.map1(String::toUpperCase);

// Chaining operations with flatMap
Either<String, ProcessedOrder> processed = validateOrder(data)
    .flatMap2(order -> enrichOrder(order))
    .flatMap2(enriched -> persistOrder(enriched));

// Convert to Result for semantic naming
Result<String, Integer> asResult = result.toResult();
```

**Learn more:** See [EitherTest.java](src/test/java/dk/trustworks/essentials/shared/functional/tuple/EitherTest.java) for examples.

### Result

Specialized `Either` with semantic `success`/`error` naming - ideal for operation outcomes.

#### `map` vs `flatMap`
Regardless of whether you are targeting the first (`1`) or second (`2`) slot:

*   **`map`**: Use for **simple transformations**. You provide a function that returns a plain value (e.g., `String` to `Integer`). The `Result` container handles the re-wrapping for you.
*   **`flatMap`**: Use for **logic chaining**. You provide a function that returns another `Result`. This "flattens" the result, preventing nested types like `Result<ERROR, Result<ERROR, SUCCESS>>`.

#### Slot Targeting (`1` vs `2`)
*   **`map1` / `flatMap1`**: Operates on the **first** slot (`ERROR`).
*   **`map2` / `flatMap2`**: Operates on the **second** slot (`SUCCESS`). Usually where the "Happy Path" logic lives.

#### Method Summary
| Method | Target | Logic | Short-circuits if... |
| :--- | :--- | :--- | :--- |
| **`map1`** | `_1` | Applies function to `_1`, stays in same "slot". | Never (applies to whatever is in `_1`). |
| **`flatMap1`** | `_1` | Applies function to `_1`, function determines the new state. | **`is_2()`** is true. |
| **`map2`** | `_2` | Applies function to `_2`, stays in same "slot". | Never (applies to whatever is in `_2`). |
| **`flatMap2`** | `_2` | Applies function to `_2`, function determines the new state. | **`is_1()`** is true. |

### Practical Example: `Result<Failure, User>`

1.  **`map2(User::getName)`**:
    *   If it's a `User`, returns `Result<Failure, String>` (the user's name).
    *   If it's a `Failure`, it stays the same `Failure`.
2.  **`flatMap2(this::validateUser)`**:
    *   If it's a `User`, runs validation. Validation returns a *new* `Result`.
    *   If validation fails, the whole object effectively "switches sides" to become a `Failure`.
3.  **`fold(onFail, onSuccess)`**:
    *   The "pattern match" exit point. Forces you to handle both cases to produce a final result (like a UI message).

> **In short:** Use **`map`** to change the **value**; use **`flatMap`** to potentially change the **outcome**.

### Practical Example
Imagine an `Result<Failure, User>`:

*   **`map2(User::getName)`**: Returns `Result<Failure, String>`. It just changes the user to their name if a user exists.
*   **`flatMap2(this::validateUser)`**: Returns `Result<Failure, User>`. If the user exists, it runs a validation that might *also* return a `Failure`. If it fails, the whole `Result` switches from a "User state" to a "Failure state".
*   **`flatMap1(...)`**: Would be used to handle or transform the error case while ignoring the success case.

In short: Use **`map`** to change the *data* inside a side; use **`flatMap`** to perform an operation that might *change the side* itself.


```java
// Creating Result values
Result<ValidationError, Order> success = Result.success(order);
Result<ValidationError, Order> failure = Result.error(new ValidationError("Invalid"));

// Semantic accessors
if (result.isSuccess()) {
    Order order = result.success();
    processOrder(order);
} else {
    ValidationError error = result.error();
    logError(error);
}

// Conditional execution
result.ifSuccess(order -> processOrder(order));
result.ifError(error -> logError(error));

// Pattern matching
String message = result.fold(
    error -> "Failed: " + error.getMessage(),
    order -> "Success: " + order.getId()
);

// Chaining operations that may fail
Result<Error, ProcessedOrder> processed = validateOrder(data)
    .flatMapSuccess(order -> enrichOrder(order))
    .flatMapSuccess(enriched -> persistOrder(enriched));

// Mapping
Result<Error, String> orderId = result.mapSuccess(order -> order.getId());
Result<String, Order> mappedError = result.mapError(e -> e.getMessage());
```

**Learn more:** See [ResultTest.java](src/test/java/dk/trustworks/essentials/shared/functional/tuple/ResultTest.java) for examples.

### Checked Functional Interfaces

Bridge checked exceptions to standard Java functional interfaces without verbose try-catch blocks.

**Problem:** Standard functional interfaces don't support checked exceptions.

```java
// Verbose approach
items.stream()
     .map(item -> {
         try {
             return Files.readString(Path.of(item));
         } catch (IOException e) {
             throw new RuntimeException(e);
         }
     })
     .toList();
```

**Solution:** Use `CheckedFunction.safe()` and similar helpers.

```java
items.stream()
     .map(CheckedFunction.safe(item -> Files.readString(Path.of(item))))
     .toList();
```

**Available interfaces:**
- `CheckedRunnable` - for `Runnable`
- `CheckedSupplier<R>` - for `Supplier<R>`
- `CheckedConsumer<T>` - for `Consumer<T>`
- `CheckedFunction<T, R>` - for `Function<T, R>`
- `CheckedBiFunction<T, U, R>` - for `BiFunction<T, U, R>`
- `CheckedTripleFunction<T, U, V, R>` - for three-argument functions

All support `.safe()` methods that wrap checked exceptions in `CheckedExceptionRethrownException`.  
`RuntimeException`s are rethrown without wrapping.

**Learn more:** See [CheckedFunctionTest.java](src/test/java/dk/trustworks/essentials/shared/functional/CheckedFunctionTest.java)

## Collections & Streams

### Lists

```java
// Indexed stream - pairs each element with its index
Stream<Pair<Integer, String>> indexed = Lists.toIndexedStream(List.of("A", "B", "C"));
indexed.forEach(pair -> System.out.println(pair._1 + ": " + pair._2));
// Output: 0: A, 1: B, 2: C

// First/last element access
Optional<String> first = Lists.first(myList);
Optional<String> last = Lists.last(myList);

// Partition for batch processing
List<List<Order>> batches = Lists.partition(allOrders, 100);
batches.forEach(batch -> processBatch(batch));
```

### Streams

```java
// Zip two streams of equal length
var names = Stream.of("Alice", "Bob", "Charlie");
var ages = Stream.of(30, 25, 35);

List<String> result = Streams.zipOrderedAndEqualSizedStreams(names, ages,
    (name, age) -> name + " is " + age
).toList();
// Result: ["Alice is 30", "Bob is 25", "Charlie is 35"]
```

## Exception Handling

### Exceptions

Utilities for exception analysis, root cause tracking, and rethrowing.

```java
try {
    processOrder(order);
} catch (Exception e) {
    // Get root cause (handles circular references)
    Throwable root = Exceptions.getRootCause(e);
    logger.error("Root cause: {}", root.getMessage());

    // Check if exception chain contains specific type
    if (Exceptions.doesStackTraceContainExceptionOfType(e, SQLException.class)) {
        // Handle database error
    }

    // Rethrow if critical (VirtualMachineError, ThreadDeath, LinkageError)
    Exceptions.rethrowIfCriticalError(e);
}
```

**Sneaky throw** (use with caution - rethrows checked exception without declaring it):

```java
public <T> T loadConfig(String path) {
    try {
        return parseJson(Files.readString(Path.of(path)));
    } catch (IOException e) {
        return Exceptions.sneakyThrow(e); // Throws IOException without declaring it
    }
}
```

## Message Formatting

### MessageFormatter

SLF4J-compatible message formatting for consistent code style.

**Positional placeholders:**
```java
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

throw new OrderException(msg("Order {} for customer {} failed with {}",
                             orderId, customerId, errorCode));
```

**Named parameters:**
```java
import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;

// Varargs syntax
String greeting = MessageFormatter.bind(
    "Hello {:firstName} {:lastName}",
    arg("firstName", "John"),
    arg("lastName", "Doe")
);
// Result: "Hello John Doe"

// Map syntax
String msg = MessageFormatter.bind(
    "Account {:account} balance: {:balance}",
    Map.of("account", "ACC-001", "balance", "$1,234.56")
);
```

**Why use named parameters?** Perfect for translations where argument order varies by language.

**Learn more:** [MessageFormatterTest.java](src/test/java/dk/trustworks/essentials/shared/MessageFormatterTest.java)

### MessageTemplate

Type-safe structured messages with hierarchical keys - useful for validation errors and internationalization.

```java
// Define message hierarchy
MessageTemplate0 ROOT = MessageTemplates.root("ESSENTIALS");
MessageTemplate0 VALIDATION = ROOT.subKey("VALIDATION");

MessageTemplate2<BigDecimal, BigDecimal> AMOUNT_TOO_HIGH =
    VALIDATION.key2("AMOUNT_TOO_HIGH", "Amount {0} is higher than {1}");

MessageTemplate2<BigDecimal, BigDecimal> AMOUNT_TOO_LOW =
    VALIDATION.key2("AMOUNT_TOO_LOW", "Amount {0} is lower than {1}");

// Create messages with typed parameters
Message msg = AMOUNT_TOO_HIGH.create(new BigDecimal("1000"), new BigDecimal("500"));
// Key: "ESSENTIALS.VALIDATION.AMOUNT_TOO_HIGH"
// Message: "Amount 1000 is higher than 500"

// Use in exceptions
throw new ValidationException(AMOUNT_TOO_LOW.create(amount, minAmount));
```

Available template types support 0-4 parameters: `MessageTemplate0` through `MessageTemplate4`.

**Learn more:** [MessageTemplatesTest.java](src/test/java/dk/trustworks/essentials/shared/messages/MessageTemplatesTest.java)

## Reflection

### Reflector

High-performance caching reflection API for constructors, methods, and fields.

```java
// Create cached reflector
var reflector = Reflector.reflectOn(MyClass.class);

// Create instances
if (reflector.hasMatchingConstructorBasedOnArguments("arg1", 123)) {
    var instance = reflector.newInstance("arg1", 123);
} else {
    var instance = reflector.invokeStatic("of", "arg1", 123);
}

// Invoke methods
String result = reflector.invoke("processData", instance, data);

// Get/set fields
String name = reflector.get(instance, "name");
reflector.set(instance, "name", "New Name");

// Find fields by annotation
Optional<Field> idField = reflector.findFieldByAnnotation(Id.class);

// Static operations
Integer constant = reflector.getStatic("MAX_SIZE");
reflector.invokeStatic("initialize", config);
```

**Learn more:** [ReflectorTest.java](src/test/java/dk/trustworks/essentials/shared/reflection/ReflectorTest.java)

### Reflection Utilities

Low-level utilities used by `Reflector` - also available for direct use.

#### Accessibles
Make `AccessibleObject` instances (fields, methods, constructors) accessible for reflection.

```java
Field field = Accessibles.accessible(someField);  // Marks as accessible
boolean isAccessible = Accessibles.isAccessible(someMethod);
```

#### BoxedTypes
Primitive/boxed type conversion utilities.

```java
BoxedTypes.isPrimitiveType(int.class);     // true
BoxedTypes.isBoxedType(Integer.class);     // true
BoxedTypes.boxedType(int.class);           // Integer.class
BoxedTypes.boxedType(String.class);        // String.class (unchanged)
```

#### Classes
Class loading and type hierarchy utilities.

```java
// Load class by FQCN (Fully Qualified Class Name)
Class<?> clazz = Classes.forName("com.example.MyClass");
Class<?> clazz = Classes.forName("com.example.MyClass", customClassLoader);

// Check classpath
boolean exists = Classes.doesClassExistOnClasspath("com.example.MyClass");

// Get superclasses (excluding Object)
List<Class<?>> supers = Classes.superClasses(MyClass.class);

// Compare type specificity (for sorting by inheritance)
// Returns: -1 if left less specific, 0 if equal, 1 if left more specific
int cmp = Classes.compareTypeSpecificity(String.class, CharSequence.class);  // 1 (String is more specific)
```

#### Constructors
Get constructors from a type.

```java
List<Constructor<?>> ctors = Constructors.constructors(MyClass.class);  // All constructors (each marked as accessible) 
```

#### Fields
Find and get fields from a type (including inherited fields).

```java
Set<Field> allFields = Fields.fields(MyClass.class);  // All fields (each marked as accessible) 
Optional<Field> field = Fields.findField(allFields, "fieldName", String.class);
```

#### Interfaces
Get all interfaces implemented by a type (recursively).

```java
Set<Class<?>> interfaces = Interfaces.interfaces(MyClass.class);
```

#### Methods
Find and get methods from a type (including inherited and interface methods).

```java
Set<Method> allMethods = Methods.methods(MyClass.class);  // All methods (each marked as accessible) 
Optional<Method> method = Methods.findMatchingMethod(allMethods, "methodName", String.class, int.class, String.class);
```

#### Parameters
Parameter type matching for method/constructor resolution.

```java
// Convert arguments to types (null arguments are mapped to Parameters.NULL_ARGUMENT_TYPE)
Class<?>[] types = Parameters.argumentTypes("hello", 42, null);

// Check parameter type compatibility
boolean matches = Parameters.parameterTypesMatches(actualTypes, declaredTypes, false);  // false = allow boxing/inheritance
```

### PatternMatchingMethodInvoker

Reflective pattern matching for event handlers and command processors.

```java
public class OrderEventHandler {
    private final PatternMatchingMethodInvoker<OrderEvent> invoker;

    public OrderEventHandler() {
        invoker = new PatternMatchingMethodInvoker<>(
            this,
            new SingleArgumentAnnotatedMethodPatternMatcher<>(EventHandler.class, OrderEvent.class),
            InvocationStrategy.InvokeMostSpecificTypeMatched
        );
    }

    public void handle(OrderEvent event) {
        // Invokes the most specific matching @EventHandler method
        invoker.invoke(event);
    }

    @EventHandler
    private void onOrderEvent(OrderEvent event) {
        // Fallback handler
    }

    @EventHandler
    private void onOrderCreated(OrderCreated event) {
        // Specific handler
    }
}
```

**Learn more:** [PatternMatchingMethodInvokerTest.java](src/test/java/dk/trustworks/essentials/shared/reflection/invocation/PatternMatchingMethodInvokerTest.java)

## Time & Measurement

### StopWatch

Time operations and methods with minimal overhead.

```java
// Time a runnable
Duration elapsed = StopWatch.time(() -> heavyComputation());
logger.info("Computation took {}", elapsed);

// Time an operation that returns a value
TimingWithResult<List<Order>> result = StopWatch.time(() -> loadOrders());
logger.info("Loaded {} orders in {}", result.result.size(), result.duration);

// Instance API with description
var watch = StopWatch.start("Database query");
// ... operation ...
Timing timing = watch.stop();
logger.info("{} took {}", timing.description, timing.duration);
```

### MeasurementRecorder

Pluggable interface for recording measurements - integrate with logging, Micrometer, or custom metrics systems.

```java
// Define measurement context
var context = MeasurementContext.builder("order.processing.time")
    .description("Time to process an order")
    .tag("orderType", "STANDARD")
    .tag("customerId", customerId.toString())
    .build();

// Record measurement
measurementRecorder.record(context, duration);
```

**Built-in implementations:**
- `LoggingMeasurementRecorder` - logs measurements via SLF4J
- `MicrometerMeasurementRecorder` - sends measurements to Micrometer

**Note:** See `LoggingMeasurementRecorder` and `MeasurementTaker` usage in component integration tests.

## Concurrency

### ThreadFactoryBuilder

Create named, daemon-aware thread factories for executors.

```java
ExecutorService executor = Executors.newScheduledThreadPool(10,
    ThreadFactoryBuilder.builder()
                        .daemon(true)
                        .nameFormat("Handler-Thread-%d")
                        .priority(Thread.NORM_PRIORITY)
                        .build()
);
```

## Control Flow

### If Expression

Java lacks ternary expressions with multiple conditions. `If` expression fills this gap.

**Before:**
```java
int value = getValue();
String description;
if (value < 0) {
    description = "Negative number";
} else if (value == 0) {
    description = "Zero";
} else {
    description = "Positive number";
}
```

**After:**
```java
import static dk.trustworks.essentials.shared.logic.IfExpression.If;

int value = getValue();
String description = If(value < 0, "Negative number")
                    .ElseIf(value == 0, "Zero")
                    .Else("Positive number");
```

**With lambda suppliers:**
```java
var result = If(() -> orderAmountExceedsThreshold(orderAmount),
                () -> cancelOrder(orderId))
            .Else(() -> acceptOrder(orderId));
```

### InterceptorChain

Generic chain-of-responsibility pattern implementation.
This pattern is useful for decoupling the sender of a request from its receivers, allowing multiple objects to handle the request or add cross-cutting concerns (like logging, security, or transaction management) without the sender knowing about them.

**Interceptor Interface and Ordering:**

All interceptors must implement the `Interceptor` marker interface. Control execution order using the `@InterceptorOrder` annotation:

```java
import dk.trustworks.essentials.shared.interceptor.Interceptor;
import dk.trustworks.essentials.shared.interceptor.InterceptorOrder;

// Default order is 10 when @InterceptorOrder is not present
public class DefaultOrderInterceptor implements CommandBusInterceptor {
    // ...
}

// Lower number = higher priority (executes first)
@InterceptorOrder(1)
public class HighPriorityInterceptor implements CommandBusInterceptor {
    // Executes BEFORE interceptors with higher order numbers
}

@InterceptorOrder(100)
public class LowPriorityInterceptor implements CommandBusInterceptor {
    // Executes AFTER interceptors with lower order numbers
}
```

**Example: Unit of Work Interception**  
A common use case in this library is ensuring that command handling occurs within a transactional boundary.  
An interceptor can start a `UnitOfWork` before the command is processed and commit it afterwards:

```java
public class UnitOfWorkInterceptor implements CommandBusInterceptor {
    @Override
    public Object interceptSend(Object command, CommandBusInterceptorChain chain) {
        // Start a unit of work, proceed with the chain, and ensure it closes
        return unitOfWorkFactory.withUnitOfWork(uow -> chain.proceed());
    }
}
```

**Generic Usage:**
```java
// Define custom interceptor
public interface QueryInterceptor extends Interceptor {
    QueryResult intercept(Query query,
                         InterceptorChain<Query, QueryResult, QueryInterceptor> chain);
}

// Execute with interceptor chain
var result = InterceptorChain.newInterceptorChainForOperation(
        query,                                                      // The operation to intercept
        registeredInterceptors,                                     // List of configured interceptors, must be presorted by @InterceptorOrder - see DefaultInterceptorChain.sortInterceptorsByOrder
        (interceptor, chain) -> interceptor.intercept(query, chain), // How to invoke the interceptor method
        () -> executeQueryDirectly(query)                           // The default behavior if no interceptor stops the chain
).proceed();
```

**Learn more:** [DefaultInterceptorChainTest.java](src/test/java/dk/trustworks/essentials/shared/interceptor/DefaultInterceptorChainTest.java)

## Security

### EssentialsSecurityProvider

**Purpose:** Role-based access control for **Essentials Admin UI only** - not for business application code.

This abstraction secures administrative APIs like `DurableQueuesApi`, `FencedLockApi`, and `SubscriptionApi`. Implement your own `EssentialsSecurityProvider` to integrate with Spring Security, JWT, or custom authentication.

```java
public interface EssentialsSecurityProvider {
    // Check if principal has required role
    boolean isAllowed(Object principal, String requiredRole);

    // Extract user identification from principal
    Optional<String> getPrincipalName(Object principal);
}
```

**Built-in implementations:**
- `AllAccessSecurityProvider` - allows all access (development/testing)
- `NoAccessSecurityProvider` - denies all access

**Admin API usage example** (from `DefaultDurableQueuesApi`):
```java
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;

public class DefaultDurableQueuesApi implements DurableQueuesApi {
    private final EssentialsSecurityProvider securityProvider;

    @Override
    public Set<QueueName> getQueueNames(Object principal) {
        // Validate principal has QUEUE_READER or ESSENTIALS_ADMIN role
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal,
                                              QUEUE_READER, ESSENTIALS_ADMIN);
        return durableQueues.getQueueNames();
    }

    @Override
    public boolean deleteMessage(Object principal, QueueEntryId queueEntryId) {
        // Write operations require QUEUE_WRITER or ESSENTIALS_ADMIN
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal,
                                              QUEUE_WRITER, ESSENTIALS_ADMIN);
        return durableQueues.deleteMessage(queueEntryId);
    }
}
```

**Available roles** (`EssentialsSecurityRoles`):
| Role | Purpose |
|------|---------|
| `ESSENTIALS_ADMIN` | Full admin access to all Essentials APIs |
| `LOCK_READER` / `LOCK_WRITER` | Fenced lock administration |
| `QUEUE_READER` / `QUEUE_WRITER` | Queue administration |
| `QUEUE_PAYLOAD_READER` | View message payloads (sensitive data) |
| `SUBSCRIPTION_READER` / `SUBSCRIPTION_WRITER` | Event subscription administration |
| `POSTGRESQL_STATS_READER` | PostgreSQL statistics access |
| `SCHEDULER_READER` | Scheduler administration |

**Note:** For business application security, use your framework's security model (Spring Security, etc.) directly.

## Additional Utilities

### GenericType

Capture generic/parameterized type information at runtime.

**Problem:** Cannot write `List<Money>.class` - generics are erased.

**Solution:**
```java
// Capture parameterized type
var genericType = new GenericType<List<Money>>(){};

// genericType.getType() returns List.class
// genericType.getGenericType() returns ParameterizedType with Money type argument

// Resolve generic type from superclass
Class<?> idType = GenericType.resolveGenericTypeOnSuperClass(Order.class, 0);
// Given: class Order extends AggregateRoot<OrderId, Order>
// Returns: OrderId.class
```

### Lifecycle

Common interface for components with start/stop lifecycle.

```java
public interface Lifecycle {
    void start();      // Idempotent - duplicate calls ignored
    void stop();       // Idempotent - duplicate calls ignored
    boolean isStarted();
}
```

Implementations must be idempotent - calling `start()` on already-started component should be a no-op.

### Network

```java
String hostname = Network.hostName(); // Get local hostname
```

## Thread Safety

- **Thread-safe:** `FailFast`, `Exceptions`, `MessageFormatter`, `Reflector` (uses concurrent caching), all Tuple types, `Message`, `Timing`, `MeasurementContext`
- **Not thread-safe:** `StopWatch` instances (use separate instance per thread)

## See Also

- [LLM-shared.md](../LLM/LLM-shared.md) - Detailed API documentation for LLM assistance
- [types](../types) - Semantic types built on shared utilities
- [immutable](../immutable) - Immutable value objects using reflection utilities
