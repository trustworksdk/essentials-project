# Shared - LLM Reference

> Zero-dependency utilities and functional primitives for Java 17+

## TOC
- [Quick Facts](#quick-facts)
- [Feature Overview](#feature-overview)
- [Core Utilities](#core-utilities)
  - [FailFast](#failfast)
  - [Exceptions](#exceptions)
  - [Lifecycle](#lifecycle)
  - [MessageFormatter](#messageformatter)
- [Functional Programming](#functional-programming)
  - [Tuples](#tuples)
  - [Either](#either)
  - [Result](#result)
  - [Either & Result: Advanced Chaining](#either--result-advanced-chaining)
  - [ComparableTuple](#comparabletuple)
  - [Checked Functional Interfaces](#checked-functional-interfaces)
  - [Higher-Arity Functions](#higher-arity-functions)
- [Collections & Streams](#collections--streams)
  - [Lists](#lists)
  - [Streams](#streams)
- [Reflection](#reflection)
  - [Reflector](#reflector)
  - [Reflection Utilities](#reflection-utilities)
  - [PatternMatchingMethodInvoker](#patternmatchingmethodinvoker)
  - [FunctionalInterfaceLoggingNameResolver](#functionalinterfaceloggingnameresolver)
- [Time & Measurement](#time--measurement)
  - [StopWatch](#stopwatch)
  - [MeasurementRecorder](#measurementrecorder)
  - [MeasurementTaker](#measurementtaker)
- [Control Flow](#control-flow)
  - [If Expression](#if-expression)
  - [InterceptorChain](#interceptorchain)
- [Concurrency](#concurrency)
  - [ThreadFactoryBuilder](#threadfactorybuilder)
- [Messages](#messages)
  - [MessageTemplate](#messagetemplate)
- [Security](#security)
  - [EssentialsSecurityProvider](#essentialssecurityprovider)
  - [EssentialsSecurityRoles](#essentialssecurityroles)
  - [EssentialsSecurityValidator](#essentialssecurityvalidator)
  - [EssentialsSecurityException](#essentialssecurityexception)
  - [EssentialsAuthenticatedUser](#essentialsauthenticateduser)
- [Additional Utilities](#additional-utilities)
  - [GenericType](#generictype)
  - [Network](#network)
- [Common Patterns](#common-patterns)
- [Thread Safety](#thread-safety)
- [Gotchas](#gotchas)
- [See Also](#see-also)

---

## Quick Facts
- Package: `dk.trustworks.essentials.shared`
- Purpose: Zero-dependency utilities and functional primitives for Java 17+
- Dependencies: None (SLF4J API as `provided` scope only)
- Foundation: All Essentials modules build on shared
- **Status**: WORK-IN-PROGRESS

## Feature Overview

| Feature                                    | Package | Purpose |
|--------------------------------------------|-------|---------|
| **FailFast**                               |  | Enhanced null/validation checks (replaces `Objects.requireNonNull`) |
| **Exceptions**                             |  | Root cause analysis, sneaky throw, critical error handling |
| **Lifecycle**                              |  | Common start/stop interface for components |
| **MessageFormatter**                       |  | SLF4J-style and named-parameter message formatting |
| **Tuples**                                 | `functional.tuple` | Immutable tuples (`Empty`, `Single`, `Pair`, `Triple`, `Quad`) |
| **Either**                                 | `functional.tuple` | Choice type - one of two values |
| **Result**                                 | `functional.tuple` | `Either` specialization with success/error semantics |
| **ComparableTuple**                        | `functional.tuple` | Comparable tuples for sorted collections |
| **Checked***                               | `functional` | Bridge checked exceptions to functional interfaces |
| **TripleFunction**                         | `functional` | Three-argument function interface |
| **QuadFunction**                           | `functional` | Four-argument function interface |
| **Lists**                                  | `collections` | Indexed stream, first/last, partition utilities |
| **Streams**                                | `collections` | Zip streams utility |
| **Reflector**                              | `reflection` | High-performance caching reflection API |
| **Accessibles**                            | `reflection` | Make AccessibleObject instances accessible |
| **BoxedTypes**                             | `reflection` | Primitive/boxed type conversion |
| **Classes**                                | `reflection` | Class loading, type hierarchy utilities |
| **Constructors**                           | `reflection` | Get constructors from a type |
| **Fields**                                 | `reflection` | Find and get fields (including inherited) |
| **Interfaces**                             | `reflection` | Get all interfaces implemented by type |
| **Methods**                                | `reflection` | Find and get methods (including inherited) |
| **Parameters**                             | `reflection` | Parameter type matching utilities |
| **PatternMatchingMethodInvoker**           | `reflection.invocation` | Reflective method dispatch by type |
| **FunctionalInterfaceLoggingNameResolver** | `reflection` | Extract readable names from lambdas |
| **StopWatch**                              | `time` | Time operations and methods |
| **MeasurementRecorder**                    | `measurement` | Pluggable measurement recording interface |
| **MeasurementContext**                     | `measurement` | Immutable measurement metadata |
| **MeasurementTaker**                       | `measurement` | Facade for recording with multiple recorders |
| **LoggingMeasurementRecorder**             | `measurement` | Log measurements via SLF4J with thresholds |
| **MicrometerMeasurementRecorder**          | `measurement` | Send measurements to Micrometer |
| **LogThresholds**                          | `measurement` | Configurable log level thresholds (ms) |
| **If**                                     | `logic` | Expression-based if/else (replaces statement) |
| **Interceptor**/**InterceptorChain**       | `interceptor` | Chain-of-responsibility pattern |
| **MessageTemplate**                        | `messages` | Structured messages with typed parameters |
| **EssentialsSecurityProvider**             | `security` | Role-based security for Admin UI APIs |
| **EssentialsSecurityValidator**            | `security` | Security validation utilities |
| **EssentialsSecurityRoles**                | `security` | Predefined security roles enum |
| **EssentialsAuthenticatedUser**            | `security` | Authenticated user interface (for admin UI) |
| **EssentialsSecurityException**            | `security` | Security violation exception |
| **ThreadFactoryBuilder**                   | `concurrent` | Named, daemon-aware thread factories |
| **GenericType**                            | `types` | Capture generic type information at runtime |
| **Network**                                | `network` | Network utilities (hostname) |

---

## Core Utilities

### FailFast

**Location:** `dk.trustworks.essentials.shared.FailFast`

Enhanced argument validation - throws `IllegalArgumentException` (not NPE) with better error messages.

```java
// API Reference
requireNonNull(T object, String msg)
requireNonNull(T object, String msg, Object... msgArgs)
requireNonBlank(CharSequence str, String msg)
requireTrue(boolean condition, String msg)
requireFalse(boolean condition, String msg)
requireNonEmpty(Object[] items, String msg)
requireNonEmpty(List<T> items, String msg)
requireNonEmpty(Set<T> items, String msg)
requireNonEmpty(Map<K,V> items, String msg)
requireMustBeInstanceOf(Object obj, Class<T> type)
requireMustBeInstanceOf(Object obj, Class<?> type, String msg)

// Usage
var config = FailFast.requireNonNull(cfg, "Config cannot be null");
var username = FailFast.requireNonBlank(input, "Username cannot be empty");
FailFast.requireTrue(amount > 0, "Amount must be positive");
var items = FailFast.requireNonEmpty(orderItems, "Order must have items");
```

### Exceptions

**Location:** `dk.trustworks.essentials.shared.Exceptions`

Exception handling utilities.

```java
// API Reference
<T extends Throwable, R> R sneakyThrow(Throwable t) throws T  // Rethrow checked exception without declaring
String getStackTrace(Throwable t)
Throwable getRootCause(Throwable e)  // Handles circular references
boolean doesStackTraceContainExceptionOfType(Throwable e, Class<?> exceptionType)
boolean isCriticalError(Throwable t)  // VirtualMachineError, ThreadDeath, LinkageError
<T extends Throwable> void rethrowIfCriticalError(T t) // // Rethrow if critical (VirtualMachineError, ThreadDeath, LinkageError)

// Usage - Root cause
Throwable root = Exceptions.getRootCause(e);

// Check for specific exception in chain
if (Exceptions.doesStackTraceContainExceptionOfType(e, SQLException.class)) {
    // Handle database error
}

// Critical error handling
try {
    operation();
} catch (Throwable t) {
    Exceptions.rethrowIfCriticalError(t);  // Rethrows if critical
    // Handle recoverable
}
```

### Lifecycle

**Location:** `dk.trustworks.essentials.shared.Lifecycle`

Common interface for components with start/stop lifecycle.

```java
public interface Lifecycle {
    void start();       // Idempotent - duplicate calls ignored
    void stop();        // Idempotent - duplicate calls ignored
    boolean isStarted();
}
```

Used by: `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore`, `dk.trustworks.essentials.components.foundation.queue.DurableQueues`, `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionManager`, `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor`.

### MessageFormatter

**Location:** `dk.trustworks.essentials.shared.MessageFormatter`

SLF4J-compatible message formatting.

```java
// Positional placeholders
String msg(String message, Object... placeholderValues)

// Named parameters
String bind(String message, NamedArgumentBinding... bindings)
String bind(String message, List<NamedArgumentBinding> bindings)
String bind(String message, Map<String, Object> bindings)

// Helper
class NamedArgumentBinding {
    static NamedArgumentBinding arg(String name, Object value)
}

// Usage - Positional
String msg = MessageFormatter.msg("Order {} for customer {} failed", orderId, customerId);

// Usage - Named
import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
String msg = MessageFormatter.bind(
    "Hello {:firstName} {:lastName}",
    arg("firstName", "John"),
    arg("lastName", "Doe")
);

// Usage - Map
String msg = MessageFormatter.bind(
    "Account {:account} balance: {:balance}",
    Map.of("account", "ACC-001", "balance", "$1,234.56")
);
```

---

## Functional Programming

### Tuples

**Location:** `dk.trustworks.essentials.shared.functional.tuple.*`

Immutable tuples supporting 0-4 elements.

```java
// Factory methods
static Empty empty()
static <T1> Single<T1> of(T1 t1)
static <T1, T2> Pair<T1, T2> of(T1 t1, T2 t2)
static <T1, T2> Pair<T1, T2> fromEntry(Map.Entry<T1, T2> entry)
static <T1, T2, T3> Triple<T1, T2, T3> of(T1 t1, T2 t2, T3 t3)
static <T1, T2, T3, T4> Quad<T1, T2, T3, T4> of(T1 t1, T2 t2, T3 t3, T4 t4)

// Pair API (most common)
public final T1 _1;  // Field access
public final T2 _2;
T1 _1()              // Method access
T2 _2()
Pair<T2, T1> swap()
Map.Entry<T1, T2> toEntry()
<R1, R2> Pair<R1, R2> map(BiFunction<T1, T2, Pair<R1, R2>> fn)
<R1, R2> Pair<R1, R2> map(Function<T1, R1> fn1, Function<T2, R2> fn2)
<R1> Pair<R1, T2> map1(Function<T1, R1> fn)
<R2> Pair<T1, R2> map2(Function<T2, R2> fn)

// Usage
var pair = Tuple.of("key", 123);
String key = pair._1;    // or pair._1()
Integer value = pair._2;

// Swapping
var swapped = Tuple.of("first", "second").swap();  // ("second", "first")

// Mapping
var mapped = Tuple.of(10, 20).map((a, b) -> Tuple.of(a * 2, b * 2));  // (20, 40)
var mapped1 = Tuple.of("hello", "world").map1(String::toUpperCase);   // ("HELLO", "world")

// Stream operations
Map<String, Integer> map = stream
    .map(item -> Tuple.of(item.getName(), item.getCount()))
    .collect(Collectors.toMap(Pair::_1, Pair::_2));
```

**Triple and Quad** have similar APIs with `_1`, `_2`, `_3`, `_4` accessors and `map` methods.

### Either

**Location:** `dk.trustworks.essentials.shared.functional.tuple.Either`

Choice type representing one of two mutually exclusive values.

```java
// Construction - only ONE element can be non-null
static <T1, T2> Either<T1, T2> of_1(T1 t1)  // Left value
static <T1, T2> Either<T1, T2> of_2(T2 t2)  // Right value
Either(T1 t1, T2 t2)  // Throws if both non-null or both null

// Check which value is present
boolean is_1()
boolean is_2()

// Access values
T1 _1()              // Can be null
T2 _2()              // Can be null
Optional<T1> get_1()
Optional<T2> get_2()

// Conditional execution
void ifIs_1(Consumer<T1> consumer)
void ifIs_2(Consumer<T2> consumer)

// Transform
Either<T2, T1> swap()
<R1, R2> Either<R1, R2> map(BiFunction<T1, T2, Either<R1, R2>> fn)
<R1, R2> Either<R1, R2> map(Function<T1, R1> fn1, Function<T2, R2> fn2)
<R1> Either<R1, T2> map1(Function<T1, R1> fn)
<R2> Either<T1, R2> map2(Function<T2, R2> fn)

// Pattern matching
<R> R fold(Function<T1, R> ifIs1, Function<T2, R> ifIs2)

// Monadic chaining
<R1> Either<R1, T2> flatMap1(Function<T1, Either<R1, T2>> fn)
<R2> Either<T1, R2> flatMap2(Function<T2, Either<T1, R2>> fn)

// Conversion
Result<T1, T2> toResult()  // Convert to Result with semantic naming

// Usage - Error handling without exceptions
Either<String, Integer> result = parseNumber(input);

// Pattern matching with fold
String message = result.fold(
    error -> "Parse failed: " + error,
    value -> "Parsed: " + value
);

// Chaining operations
Either<String, ProcessedOrder> processed = validateOrder(data)
    .flatMap2(order -> enrichOrder(order))
    .flatMap2(enriched -> persistOrder(enriched));
```

### Result

**Location:** `dk.trustworks.essentials.shared.functional.tuple.Result`

Specialized Either with semantic `success`/`error` naming. Extends `Either<ERROR, SUCCESS>`.

```java
// Construction
static <E, S> Result<E, S> success(S value)
static <E, S> Result<E, S> error(E error)
static <E, S> Result<E, S> fromEither(Either<E, S> either)

// Check state
boolean isSuccess()  // Same as is_2()
boolean isError()    // Same as is_1()

// Access values
SUCCESS success()           // Can be null
ERROR error()               // Can be null
Optional<SUCCESS> getSuccess()
Optional<ERROR> getError()

// Conditional execution
void ifSuccess(Consumer<SUCCESS> consumer)
void ifError(Consumer<ERROR> consumer)

// Mapping
<S2> Result<ERROR, S2> mapSuccess(Function<SUCCESS, S2> fn)
<E2> Result<E2, SUCCESS> mapError(Function<ERROR, E2> fn)

// Monadic chaining
<S2> Result<ERROR, S2> flatMapSuccess(Function<SUCCESS, Result<ERROR, S2>> fn)
<E2> Result<E2, SUCCESS> flatMapError(Function<ERROR, Result<E2, SUCCESS>> fn)

// Pattern matching
<R> R fold(Function<ERROR, R> errorMapper, Function<SUCCESS, R> successMapper)

// Transform
Result<SUCCESS, ERROR> swap()
Either<ERROR, SUCCESS> toEither()

// Usage
Result<ValidationError, Order> result = validateOrder(data);

// Pattern matching
String message = result.fold(
    error -> "Failed: " + error.getMessage(),
    order -> "Success: " + order.getId()
);

// Chaining operations
Result<Error, ProcessedOrder> processed = validateOrder(data)
    .flatMapSuccess(order -> enrichOrder(order))
    .flatMapSuccess(enriched -> persistOrder(enriched));

// Conditional execution
result.ifSuccess(order -> processOrder(order));
result.ifError(error -> logError(error));
```

### Either & Result: Advanced Chaining
**Location:** `dk.trustworks.essentials.shared.functional.tuple.Either` (and `dk.trustworks.essentials.shared.functional.tuple.Result`)

#### map vs flatMap
*   **`map[1|2]`**: **Transform Value.** Use for simple conversions (e.g., `String` to `Integer`). The container automatically re-wraps the result.
*   **`flatMap[1|2]`**: **Chain Logic.** Use when the transformation itself can fail or change the outcome (returns another `Either`/`Result`). Prevents nesting like `Result<E, Result<E, S>>`.

#### Slot Targeting
*   **`1` (Left/Error)**: Use `map1` / `flatMap1`. Typically for error recovery or translation.
*   **`2` (Right/Success)**: Use `map2` / `flatMap2`. This is the **"Happy Path"**.

| Method | Target | Short-circuits if... | Effect |
| :--- | :--- |:---------------------| :--- |
| **`map1`** | `_1` | never                | Transforms Error value. |
| **`flatMap1`** | `_1` | `is_2()`             | Logic can switch Error → Success. |
| **`map2`** | `_2` | never                | Transforms Success value. |
| **`flatMap2`** | `_2` | `is_1()`             | Logic can switch Success → Error. |

#### Practical Example: `Result<Failure, User>`
```java
Result<Failure, User> result = findUser(id)
    // 1. Map: Change value (User -> String) if Success
    .map2(User::getName)

    // 2. FlatMap: Chain logic that might fail (String -> Result<Failure, Account>)
    .flatMap2(this::getAccount)

    // 3. Fold: Terminal pattern match (forces handling both paths)
    .fold(
        fail -> "Error: " + fail.getMessage(),
        acc  -> "Account balance: " + acc.getBalance()
    );
```


> **Summary:** Use **`map`** to change the **data**; use **`flatMap`** to potentially change the **state** (e.g., from Success to Error).

### ComparableTuple

**Location:** `dk.trustworks.essentials.shared.functional.tuple.ComparableTuple`

Base interface for comparable tuples - enables sorting and ordered collections.
The API is similar to `Tuple`, but requires the concrete type to implement `Comparable`.

```java
public interface ComparableTuple<CONCRETE_TUPLE extends ComparableTuple<CONCRETE_TUPLE>>
    extends Tuple<CONCRETE_TUPLE>, Comparable<CONCRETE_TUPLE>
```

All tuple implementations implement `Comparable` for natural ordering in sorted collections.

### Checked Functional Interfaces

**Location:** `dk.trustworks.essentials.shared.functional.*`

Bridge checked exceptions to standard Java functional interfaces.

```java
// Interfaces
@FunctionalInterface CheckedRunnable {
    void run() throws Exception;
    static Runnable safe(CheckedRunnable r)
}

@FunctionalInterface CheckedSupplier<R> {
    R get() throws Exception;
    static <R> Supplier<R> safe(CheckedSupplier<R> s)
}

@FunctionalInterface CheckedConsumer<T> {
    void accept(T t) throws Exception;
    static <T> Consumer<T> safe(CheckedConsumer<T> c)
}

@FunctionalInterface CheckedFunction<T, R> {
    R apply(T t) throws Exception;
    static <T, R> Function<T, R> safe(CheckedFunction<T, R> f)
    static <T, R> Function<T, R> safe(String contextMsg, CheckedFunction<T, R> f)
}

@FunctionalInterface CheckedBiFunction<T, U, R> {
    R apply(T t, U u) throws Exception;
    static <T, U, R> BiFunction<T, U, R> safe(CheckedBiFunction<T, U, R> f)
}

// Also: CheckedTripleFunction, CheckedQuadFunction

// Usage
items.stream()
     .map(CheckedFunction.safe(item -> Files.readString(Path.of(item))))
     .collect(Collectors.toList());

// With context
items.stream()
     .map(CheckedFunction.safe("Reading file", item -> Files.readString(Path.of(item))))
     .collect(Collectors.toList());
```

All `safe()` methods wrap checked exceptions in `dk.trustworks.essentials.shared.functional.CheckedExceptionRethrownException` (unchecked). `RuntimeException`'s are propagated unchanged.

### Higher-Arity Functions

**Location:** `dk.trustworks.essentials.shared.functional.*`
`Function` like interfaces supporting 3 (`TripleFunction`) or 4 (`QuadFunction`) parameters.

```java
@FunctionalInterface
public interface TripleFunction<T, U, V, R> {
    R apply(T t, U u, V v);
    default <V> TripleFunction<T1, T2, T3, V> andThen(Function<? super R, ? extends V> after);
}

@FunctionalInterface
public interface QuadFunction<T, U, V, W, R> {
    R apply(T t, U u, V v, W w);
    default <V> QuadFunction<T1, T2, T3, T4, V> andThen(Function<? super R, ? extends V> after);
}
```

---

## Collections & Streams

### Lists

**Location:** `dk.trustworks.essentials.shared.collections.Lists`

```java
// API
Stream<Pair<Integer, T>> toIndexedStream(List<T> list)  // Pairs with 0-based index
Optional<T> first(List<T> list)
Optional<T> last(List<T> list)
List<T> nullSafeList(List<T> list)  // Returns empty list if null
List<List<T>> partition(List<T> list, int batchSize) // Splits a given list into smaller sublists, each of a specified maximum size

// Usage - Indexed stream
Lists.toIndexedStream(List.of("A", "B", "C"))
     .forEach(pair -> System.out.println(pair._1 + ": " + pair._2));
// Output: 0: A, 1: B, 2: C

// Partition for batch processing
List<List<Order>> batches = Lists.partition(allOrders, 100);
batches.forEach(batch -> processBatch(batch));
```

### Streams

**Location:** `dk.trustworks.essentials.shared.collections.Streams`

```java
// API
<R, T, U> Stream<R> zipOrderedAndEqualSizedStreams(
    Stream<T> streamT,
    Stream<U> streamU,
    BiFunction<T, U, R> zipFunction
) // Combines two streams into a single stream by applying a zip function to pairs of elements from the two streams. The streams must have equal sizes. The resulting stream will have the same order as the input streams.

// Usage
var names = Stream.of("Alice", "Bob", "Charlie");
var ages = Stream.of(30, 25, 35);

List<String> result = Streams.zipOrderedAndEqualSizedStreams(names, ages,
    (name, age) -> name + " is " + age
).collect(Collectors.toList());
// Result: ["Alice is 30", "Bob is 25", "Charlie is 35"]
```

---

## Reflection

### Reflector

**Location:** `dk.trustworks.essentials.shared.reflection.Reflector`

High-performance caching reflection wrapper. Cache is concurrent (thread-safe).

```java
// Factory
static Reflector reflectOn(Class<?> type)
static Reflector reflectOn(String fullyQualifiedClassName)

// Fields
public final Class<?> type;
public final List<Constructor<?>> constructors;
public final Set<Method> methods;
public final Set<Field> fields;

// Constructor operations
boolean hasDefaultConstructor()
Optional<Constructor<?>> getDefaultConstructor()
boolean hasMatchingConstructorBasedOnArguments(Object... args)
boolean hasMatchingConstructorBasedOnParameterTypes(Class<?>... paramTypes)
<T> T newInstance(Object... constructorArguments)

// Method operations
boolean hasMethod(String name, boolean staticMethod, Class<?>... argTypes)
Optional<Method> findMatchingMethod(String name, boolean staticMethod, Class<?>... argTypes)
<R> R invoke(String methodName, Object target, Object... args)
<R> R invoke(Method method, Object target, Object... args)
<R> R invokeStatic(String methodName, Object... args)
<R> R invokeStatic(Method method, Object... args)

// Field operations
Optional<Field> findFieldByName(String fieldName)
Optional<Field> findStaticFieldByName(String fieldName)
Optional<Field> findFieldByAnnotation(Class<? extends Annotation> annotation)
<R> R get(Object object, String fieldName)
<R> R get(Object object, Field field)
<R> R getStatic(String fieldName)
<R> R getStatic(Field field)
void set(Object object, String fieldName, Object newValue)
void set(Object object, Field field, Object newValue)
void setStatic(String fieldName, Object newValue)
void setStatic(Field field, Object newValue)
Stream<Field> staticFields()
Stream<Field> instanceFields()

// Usage
var reflector = Reflector.reflectOn(MyClass.class);
var instance = reflector.newInstance("arg1", 123);
String result = reflector.invoke("processData", instance, data);
String name = reflector.get(instance, "name");
reflector.set(instance, "name", "New Name");
Optional<Field> idField = reflector.findFieldByAnnotation(Id.class);
```

### Reflection Utilities

Low-level utilities used by `Reflector`. All located in `dk.trustworks.essentials.shared.reflection`.

#### Accessibles

```java
// Make AccessibleObject (Field, Method, Constructor) accessible
static <T extends AccessibleObject> T accessible(T object)
static boolean isAccessible(AccessibleObject object)
```

#### BoxedTypes

```java
// Primitive/boxed type utilities
static boolean isPrimitiveType(Class<?> type)   // int.class → true
static boolean isBoxedType(Class<?> type)       // Integer.class → true
static Class<?> boxedType(Class<?> type)        // int.class → Integer.class
```

#### Classes

```java
// Class loading
static Class<?> forName(String fqcn)
static Class<?> forName(String fqcn, ClassLoader loader)
static boolean doesClassExistOnClasspath(String fqcn)
static boolean doesClassExistOnClasspath(String fqcn, ClassLoader loader)

// Type hierarchy
static List<Class<?>> superClasses(Class<?> type)  // Excludes Object
static int compareTypeSpecificity(Class<?> left, Class<?> right)
// Returns: -1 if left less specific, 0 if equal, 1 if left more specific
// Example: compareTypeSpecificity(String.class, CharSequence.class) → 1
```

#### Constructors

```java
// Get all constructors (marked accessible)
static List<Constructor<?>> constructors(Class<?> type)
```

#### Fields

```java
// Get all fields including inherited (marked accessible)
static Set<Field> fields(Class<?> type)

// Find field by name and type
static Optional<Field> findField(Set<Field> fields, String name, Class<?> type)
```

#### Interfaces

```java
// Get all interfaces implemented by type (recursively)
static Set<Class<?>> interfaces(Class<?> type)
```

#### Methods

```java
// Get all methods including inherited/interface (marked accessible)
static Set<Method> methods(Class<?> type)

// Find exact matching method
static Optional<Method> findMatchingMethod(Set<Method> methods, String name, Class<?> returnType, Class<?>... paramTypes)
```

#### Parameters

```java
// Convert arguments to types (null → NULL_ARGUMENT_TYPE)
static Class<?>[] argumentTypes(Object... arguments)

// Check if parameter types match (exactMatch=false allows boxing/inheritance)
static boolean parameterTypesMatches(Class<?>[] actual, Class<?>[] declared, boolean exactMatch)

// Sentinel for null arguments
static final class NULL_ARGUMENT_TYPE {}
```

### PatternMatchingMethodInvoker

**Location:** `dk.trustworks.essentials.shared.reflection.invocation.*`

Reflective method dispatch based on argument types.

```java
// Constructor
PatternMatchingMethodInvoker(
    Object invokeMethodsOn,
    MethodPatternMatcher<?> methodPatternMatcher,
    InvocationStrategy invocationStrategy
)

// Invocation strategies
enum InvocationStrategy {
    InvokeMostSpecificTypeMatched,  // Most specific matching method
    InvokeAllMatches                // All matching methods
}

// Invoke methods
void invoke(ARGUMENT_ROOT_TYPE argument)
void invoke(ARGUMENT_ROOT_TYPE argument, NoMatchingMethodsHandler handler)
```

#### MethodPatternMatcher Interface

Strategy interface that determines which methods are candidates for matching and how they are invoked.

```java
interface MethodPatternMatcher<ARGUMENT_COMMON_ROOT_TYPE> {
    // Is this method a candidate for invocation?
    boolean isInvokableMethod(Method candidateMethod);

    // Extract argument type from method signature (for matching)
    Class<?> resolveInvocationArgumentTypeFromMethodDefinition(Method method);

    // Extract argument type from actual object (for lookup)
    // Useful for wrapped/envelope types (e.g., Message<Payload> → Payload.class)
    Class<?> resolveInvocationArgumentTypeFromObject(Object argument);

    // Perform the actual method invocation
    void invokeMethod(Method methodToInvoke, Object argument,
                      Object invokeMethodOn, Class<?> resolvedArgumentType) throws Exception;
}
```

**When to implement custom `MethodPatternMatcher`:**
- Methods with multiple parameters (not just single argument)
- Envelope/wrapper types where you match on payload, not container
- Custom method selection logic beyond annotation + type matching
- Different invocation patterns (e.g., inject additional context)

#### SingleArgumentAnnotatedMethodPatternMatcher (Built-in)

Implements `MethodPatternMatcher` and matches methods annotated with a specific annotation that have a single parameter assignable from the root type.

```java
// Constructor - with Class
SingleArgumentAnnotatedMethodPatternMatcher(
    Class<? extends Annotation> methodAnnotation,  // e.g., @EventHandler
    Class<?> argumentRootType                       // e.g., OrderEvent.class
)

// Constructor - with GenericType (for parameterized types)
SingleArgumentAnnotatedMethodPatternMatcher(
    Class<? extends Annotation> methodAnnotation,
    GenericType<?> argumentRootType                 // e.g., new GenericType<Event<OrderId>>(){}
)

// Matching logic:
// 1. Method has @methodAnnotation
// 2. Method has exactly 1 parameter
// 3. Parameter type is assignable from argumentRootType
```

#### Complete Example

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
        invoker.invoke(event);  // Dispatches to most specific @EventHandler method
    }

    @EventHandler
    private void onOrderEvent(OrderEvent event) { /* Fallback */ }

    @EventHandler
    private void onOrderCreated(OrderCreated event) { /* Specific */ }
}
```

### FunctionalInterfaceLoggingNameResolver

**Location:** `dk.trustworks.essentials.shared.reflection.FunctionalInterfaceLoggingNameResolver`

Extract readable names from lambda expressions and method references for logging.

```java
// API
static String resolveLoggingName(Object handler)

// Usage
var handler = (EventHandler) event -> process(event);
String name = FunctionalInterfaceLoggingNameResolver.resolveLoggingName(handler);
// Returns: "MyClass::EventHandler" or similar readable name instead of "$$Lambda$123"
```

Thread-safe with internal caching. Used internally by `PatternMatchingMethodInvoker` and measurement recording.

---

## Time & Measurement

### StopWatch

**Location:** `dk.trustworks.essentials.shared.time.StopWatch`

```java
// Instance API
static StopWatch start()
static StopWatch start(String description)
Timing stop()
Duration elapsed()

// Static convenience
static Duration time(Runnable operation)
static <R> TimingWithResult<R> time(Supplier<R> operation)
static Timing time(String description, Runnable action)

// Result types
class Timing {
    public final String description;
    public final Duration duration;
}

class TimingWithResult<T> {
    public final T result;
    public final Duration duration;
}

// Usage - Instance
var watch = StopWatch.start("Database query");
// ... operation ...
Timing timing = watch.stop();
logger.info("{} took {}", timing.description, timing.duration);

// Usage - Static runnable
Duration elapsed = StopWatch.time(() -> heavyComputation());

// Usage - Static supplier
TimingWithResult<List<Order>> result = StopWatch.time(() -> loadOrders());
logger.info("Loaded {} orders in {}", result.result.size(), result.duration);
```

**Note:** StopWatch instances are NOT thread-safe. Use separate instances per thread.

### MeasurementRecorder

**Location:** `dk.trustworks.essentials.shared.measurement.*`

Pluggable interface for recording measurements.

```java
// Core interface
public interface MeasurementRecorder {
    void record(MeasurementContext context, Duration duration);
}

// MeasurementContext - immutable
class MeasurementContext {
    String getMetricName()
    String getDescription()
    Map<String, String> getTags()

    static Builder builder(String metricName)
}

class MeasurementContext.Builder {
    Builder description(String description)
    Builder tag(String key, String value)
    Builder tag(String key, CharSequence value)
    Builder tag(String key, int value)
    Builder optionalTag(String key, String value)  // Skip if null
    MeasurementContext build()
}

// Built-in implementations
class LoggingMeasurementRecorder implements MeasurementRecorder {
    LoggingMeasurementRecorder(Logger logger, LogThresholds thresholds)
}

class MicrometerMeasurementRecorder implements MeasurementRecorder {
    MicrometerMeasurementRecorder(MeterRegistry meterRegistry)
}

// LogThresholds - millisecond thresholds for log levels
class LogThresholds {
    LogThresholds(long debug, long info, long warn, long error)
    static LogThresholds defaultThresholds()  // (25, 100, 500, 2000)
}

// Usage
var context = MeasurementContext.builder("order.processing.time")
    .description("Time to process an order")
    .tag("orderType", "STANDARD")
    .tag("customerId", customerId.toString())
    .build();

measurementRecorder.record(context, duration);
```

### MeasurementTaker

**Location:** `dk.trustworks.essentials.shared.measurement.MeasurementTaker`

Facade for recording with multiple `MeasurementRecorder`s.

```java
// Builder
static Builder builder()

class Builder {
    Builder addRecorder(MeasurementRecorder recorder)
    Builder withOptionalMicrometerMeasurementRecorder(Optional<MeterRegistry> registry)
    MeasurementTaker build()
}

// Recording
<T> T record(MeasurementContext context, Supplier<T> block)
void recordTime(MeasurementContext context, Duration elapsed)

// Fluent API
FluentMeasurementContext context(String metricName)

class FluentMeasurementContext {
    FluentMeasurementContext description(String description)
    FluentMeasurementContext tag(String key, String/CharSequence/int value)
    FluentMeasurementContext optionalTag(String key, String value)
    <T> T record(Supplier<T> block)
    FluentMeasurementContext record(Duration recordedDuration)
}

// Usage - Fluent
return measurementTaker.context("essentials.eventstore.append_to_stream")
                        .description("Time taken to append events")
                        .tag("aggregateType", operation.getAggregateType())
                        .record(chain::proceed);

// Usage - Direct
measurementTaker.recordTime(
    MeasurementContext.builder("essentials.invocation")
                      .description("Time to invoke method")
                      .tag("class", className)
                      .tag("method", methodName)
                      .build(),
    duration
);
```

---

## Control Flow

### If Expression

**Location:** `dk.trustworks.essentials.shared.logic.IfExpression`

Expression-based if/else (replaces statement).

```java
// API
static <R> IfElseExpression<R> If(boolean condition, R thenValue)
static <R> IfElseExpression<R> If(BooleanSupplier condition, Supplier<R> thenValue)

class IfElseExpression<R> {
    IfElseExpression<R> ElseIf(boolean condition, R thenValue)
    IfElseExpression<R> ElseIf(BooleanSupplier condition, Supplier<R> thenValue)
    R Else(R elseValue)
    R Else(Supplier<R> elseValue)
}

// Usage - Values
import static dk.trustworks.essentials.shared.logic.IfExpression.If;

int value = getValue();
String description = If(value < 0, "Negative")
                    .ElseIf(value == 0, "Zero")
                    .Else("Positive");

// Usage - Suppliers (lazy evaluation)
var result = If(() -> orderAmountExceedsThreshold(orderAmount),
                () -> cancelOrder(orderId))
            .Else(() -> acceptOrder(orderId));
```

### InterceptorChain

**Location:** `dk.trustworks.essentials.shared.interceptor.*`

Generic chain-of-responsibility pattern.

```java
// Base marker interface (no methods)
public interface Interceptor {}

// Ordering annotation - controls execution order
public @interface InterceptorOrder {
    int value() default 10;
}
// Lower number = higher priority (executes first)
// Order 1 executes BEFORE order 10

// Chain factory
static <OPERATION, RESULT, INTERCEPTOR_TYPE extends Interceptor>
InterceptorChain<OPERATION, RESULT, INTERCEPTOR_TYPE> newInterceptorChainForOperation(
    OPERATION operation,
    List<INTERCEPTOR_TYPE> interceptors,
    BiFunction<INTERCEPTOR_TYPE, InterceptorChain<...>, RESULT> interceptorMethodInvoker,
    Supplier<RESULT> defaultBehaviour
)

// Chain API
RESULT proceed()
OPERATION operation()
```

#### Ordering Example

```java
@InterceptorOrder(1)  // Runs FIRST (lowest number)
public class SecurityInterceptor implements QueryInterceptor {
    public QueryResult intercept(Query query, InterceptorChain<...> chain) {
        validateAccess(query);
        return chain.proceed();
    }
}

@InterceptorOrder(10)  // Runs SECOND
public class LoggingInterceptor implements QueryInterceptor {
    public QueryResult intercept(Query query, InterceptorChain<...> chain) {
        log.info("Query: {}", query);
        return chain.proceed();
    }
}

// No annotation = default order 10
public class CachingInterceptor implements QueryInterceptor { ... }
```

#### Custom Interceptor Pattern

```java
// Define operation-specific interceptor interface
public interface QueryInterceptor extends Interceptor {
    QueryResult intercept(Query query, InterceptorChain<Query, QueryResult, QueryInterceptor> chain);
}

// Execute with chain
var result = InterceptorChain.newInterceptorChainForOperation(
    query,
    registeredInterceptors,  // Presorted by @InterceptorOrder - see DefaultInterceptorChain.sortInterceptorsByOrder
    (interceptor, chain) -> interceptor.intercept(query, chain),
    () -> executeQueryDirectly(query)
).proceed();
```

---

## Concurrency

### ThreadFactoryBuilder

**Location:** `dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder`

Create named, daemon-aware thread factories.

```java
// API
static Builder builder()

class Builder {
    Builder nameFormat(String nameFormat)  // Use %d for thread number
    Builder daemon(boolean daemon)
    Builder priority(int priority)
    Builder threadGroup(ThreadGroup threadGroup)
    ThreadFactory build()
}

// Usage
ExecutorService executor = Executors.newScheduledThreadPool(10,
    ThreadFactoryBuilder.builder()
                        .daemon(true)
                        .nameFormat("Handler-Thread-%d")
                        .priority(Thread.NORM_PRIORITY)
                        .build()
);
```

---

## Messages

### MessageTemplate

**Location:** `dk.trustworks.essentials.shared.messages.*`

Type-safe structured messages with hierarchical keys - useful for validation errors and internationalization.

```java
// Base interface
public interface MessageTemplate {
    String getKey()
    String getDefaultMessage()
}

// Typed templates (0-4 parameters)
MessageTemplate0, MessageTemplate1<P1>, MessageTemplate2<P1,P2>,
MessageTemplate3<P1,P2,P3>, MessageTemplate4<P1,P2,P3,P4>

// Factory
class MessageTemplates {
    static MessageTemplate0 root(String key)
}

// MessageTemplate0 methods
MessageTemplate0 subKey(String key)
<P1> MessageTemplate1<P1> key1(String key, String defaultMessage)
<P1,P2> MessageTemplate2<P1,P2> key2(String key, String defaultMessage)
<P1,P2,P3> MessageTemplate3<P1,P2,P3> key3(String key, String defaultMessage)
<P1,P2,P3,P4> MessageTemplate4<P1,P2,P3,P4> key4(String key, String defaultMessage)

// Message
class Message {
    String getKey()
    String getMessage()
    Object[] getParameters()
}

// Usage
MessageTemplate0 ROOT = MessageTemplates.root("ESSENTIALS");
MessageTemplate0 VALIDATION = ROOT.subKey("VALIDATION");

MessageTemplate2<BigDecimal, BigDecimal> AMOUNT_TOO_HIGH =
    VALIDATION.key2("AMOUNT_TOO_HIGH", "Amount {0} is higher than {1}");

// Create messages
Message msg = AMOUNT_TOO_HIGH.create(new BigDecimal("1000"), new BigDecimal("500"));
// Key: "ESSENTIALS.VALIDATION.AMOUNT_TOO_HIGH"
// Message: "Amount 1000 is higher than 500"

// Use in exception
throw new ValidationException(AMOUNT_TOO_HIGH.create(amount, maxAmount));
```

---

## Security

**Location:** `dk.trustworks.essentials.shared.security.*`

**Purpose:** Role-based access control for **Essentials Admin UI only** - not for business application code.

Secures administrative APIs: `dk.trustworks.essentials.components.foundation.queue.DurableQueuesApi`, `dk.trustworks.essentials.components.foundation.fencedlock.FencedLockApi`, `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionApi`, etc.

### EssentialsSecurityProvider

Framework-independent role-based security abstraction - integrate with Spring Security, JWT, or custom authentication.

```java
public interface EssentialsSecurityProvider {
    boolean isAllowed(Object principal, String requiredRole)
    Optional<String> getPrincipalName(Object principal)
}

// Built-in implementations
class AllAccessSecurityProvider implements EssentialsSecurityProvider {
    // Returns true for all isAllowed() calls - (development/testing)
}

class NoAccessSecurityProvider implements EssentialsSecurityProvider {
    // Returns false for all isAllowed() calls
}
```

### EssentialsSecurityRoles

```java
public enum EssentialsSecurityRoles {
    ESSENTIALS_ADMIN("essentials_admin"),           // All-in-one admin role
    LOCK_READER("essentials_lock_reader"),
    LOCK_WRITER("essentials_lock_writer"),
    QUEUE_READER("essentials_queue_reader"),
    QUEUE_PAYLOAD_READER("essentials_queue_payload_reader"),
    QUEUE_WRITER("essentials_queue_writer"),
    SUBSCRIPTION_READER("essentials_subscription_reader"),
    SUBSCRIPTION_WRITER("essentials_subscription_writer"),
    POSTGRESQL_STATS_READER("essentials_postgresql_stats_reader"),
    SCHEDULER_READER("essentials_scheduler_reader");

    String getRoleName()
}
```

### EssentialsSecurityValidator

```java
// Validation - throws EssentialsSecurityException if check fails
static void validateHasEssentialsSecurityRole(
    EssentialsSecurityProvider provider,
    Object principal,
    EssentialsSecurityRoles role
)

static void validateHasAnyEssentialsSecurityRoles(
    EssentialsSecurityProvider provider,
    Object principal,
    EssentialsSecurityRoles... roles  // At least ONE required
)

static void validateHasEssentialsSecurityRoles(
    EssentialsSecurityProvider provider,
    Object principal,
    EssentialsSecurityRoles... roles  // ALL required
)

// Check without throwing
static boolean hasEssentialsSecurityRole(
    EssentialsSecurityProvider provider,
    Object principal,
    EssentialsSecurityRoles role
)

static boolean hasAnyEssentialsSecurityRoles(
    EssentialsSecurityProvider provider,
    Object principal,
    EssentialsSecurityRoles... roles
)

// Admin API usage (from DefaultDurableQueuesApi)
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

### EssentialsSecurityException

```java
public class EssentialsSecurityException extends RuntimeException {
    EssentialsSecurityException(String message)
}
```

Thrown by `validateHas*` methods when security check fails.

### EssentialsAuthenticatedUser

Interface for authenticated users - primarily for admin UI integration.

```java
public interface EssentialsAuthenticatedUser {
    Object getPrincipal()
    boolean isAuthenticated()       // Default: false
    boolean hasRole(String role)    // Default: false
    Optional<String> getPrincipalName()

    // Convenience role checks
    boolean hasAdminRole()
    boolean hasLockReaderRole()
    boolean hasLockWriterRole()
    boolean hasQueueReaderRole()
    boolean hasQueuePayloadReaderRole()
    boolean hasQueueWriterRole()
    boolean hasSubscriptionReaderRole()
    boolean hasSubscriptionWriterRole()
    boolean hasSchedulerReaderRole()
    boolean hasPostgresqlStatsReaderRole()

    void logout()
}

// Built-in implementations
class AllAccessAuthenticatedUser implements EssentialsAuthenticatedUser
class NoAccessAuthenticatedUser implements EssentialsAuthenticatedUser
```

**Note:** For business application security, use your framework's security model (Spring Security, etc.) directly. This abstraction is specifically for Essentials Admin UI APIs.

---

## Additional Utilities

### GenericType

**Location:** `dk.trustworks.essentials.shared.types.GenericType`

Capture generic/parameterized type information at runtime.

```java
// Constructor - use anonymous class to capture type
GenericType<T>()

// Methods
Class<T> getType()              // Raw class
Type getGenericType()           // Full generic type info

// Static utility
static Class<?> resolveGenericTypeOnSuperClass(Class<?> concreteType, int genericTypeArgumentIndex)

// Usage - Capture parameterized type
var genericType = new GenericType<List<Money>>(){};
// genericType.getType() returns List.class
// genericType.getGenericType() returns ParameterizedType with Money type argument

// Resolve generic from superclass
class Order extends AggregateRoot<OrderId, Order> {}

Class<?> idType = GenericType.resolveGenericTypeOnSuperClass(Order.class, 0);
// Returns: OrderId.class
```

### Network

**Location:** `dk.trustworks.essentials.shared.network.Network`

```java
static String hostName()  // Get local hostname
```

---

## Common Patterns

### Validation with FailFast

```java
public Order createOrder(OrderId id, List<LineItem> items, CustomerId customerId) {
    FailFast.requireNonNull(id, "Order ID is required");
    FailFast.requireNonEmpty(items, "Order must have at least one line item");
    FailFast.requireNonNull(customerId, "Customer ID is required");
    return new Order(id, items, customerId);
}
```

### Exception Analysis

```java
try {
    processOrder(order);
} catch (Exception e) {
    Exceptions.rethrowIfCriticalError(e);

    if (Exceptions.doesStackTraceContainExceptionOfType(e, OptimisticLockException.class)) {
        // Retry logic
    } else {
        logger.error("Processing failed: {}", Exceptions.getRootCause(e).getMessage());
    }
}
```

### Stream Processing with Tuples

```java
Map<String, List<Order>> ordersByCustomer = orders.stream()
    .map(order -> Tuple.of(order.getCustomerId(), order))
    .collect(Collectors.groupingBy(
        Pair::_1,
        Collectors.mapping(Pair::_2, Collectors.toList())
    ));
```

### Measurement Recording

```java
// Option 1: Fluent
return measurementTaker.context("order.processing")
                        .description("Process order")
                        .tag("orderType", orderType)
                        .record(() -> processor.process(order));

// Option 2: Explicit context
var context = MeasurementContext.builder("order.processing")
    .description("Process order")
    .tag("orderType", orderType)
    .build();
return measurementTaker.record(context, () -> processor.process(order));
```

---

## Thread Safety

| Class/Interface | Thread Safety |
|----------------|---------------|
| FailFast | Thread-safe (stateless) |
| Exceptions | Thread-safe (stateless) |
| MessageFormatter | Thread-safe (stateless) |
| Reflector | Thread-safe (concurrent caching) |
| Tuple types | Thread-safe (immutable) |
| Either | Thread-safe (immutable) |
| Message | Thread-safe (immutable) |
| Timing | Thread-safe (immutable) |
| MeasurementContext | Thread-safe (immutable) |
| StopWatch | **NOT thread-safe** (use separate instance per thread) |
| InterceptorChain | **NOT thread-safe** (single-use instance) |

---

## Gotchas

- ⚠️ FailFast throws `IllegalArgumentException`, not `NullPointerException`
- ⚠️ Either requires exactly ONE element to be non-null (enforced at construction)
- ⚠️ StopWatch instances are NOT thread-safe - create separate instance per thread
- ⚠️ Checked functional `.safe()` methods wrap in `dk.trustworks.essentials.shared.functional.CheckedExceptionRethrownException` (unchecked)
- ⚠️ `Exceptions.sneakyThrow()` rethrows checked exception without declaring it - use with caution
- ⚠️ Reflector caches reflection data - reuse instances for best performance
- ⚠️ Security abstraction is for **Essentials Admin UI APIs only**, not a complete security framework
- ⚠️ Measurement recording is synchronous - all recorders execute in same thread

---

## See Also

- [README.md](../shared/README.md) - Full human-friendly documentation with examples
- [LLM-types.md](./LLM-types.md) - Semantic types built on shared utilities
- [LLM-reactive.md](./LLM-reactive.md) - LocalEventBus and LocalCommandBus
- [LLM-foundation.md](./LLM-foundation.md) - UnitOfWork, FencedLock, DurableQueues
