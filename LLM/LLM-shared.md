# Shared - LLM Reference

> Zero-dependency utilities and functional primitives for Java 17+. See [README.md](../shared/README.md) for detailed explanations.

## TOC
- [Quick Facts](#quick-facts)
- [Core Utilities](#core-utilities)
- [Functional Programming](#functional-programming)
- [Collections & Streams](#collections--streams)
- [Reflection](#reflection)
- [Time & Measurement](#time--measurement)
- [Control Flow](#control-flow)
- [Concurrency](#concurrency)
- [Messages](#messages)
- [Security](#security)
- [Additional Utilities](#additional-utilities)
- [Thread Safety](#thread-safety)
- [Gotchas](#gotchas)

---

## Quick Facts
- **Base package**: `dk.trustworks.essentials.shared`
- **Purpose**: Zero-dependency utilities and functional primitives
- **Dependencies**: None (SLF4J API as `provided` scope only)
- **Foundation**: All Essentials modules build on shared
- **Status**: WORK-IN-PROGRESS

```xml
<dependency>
    <groupId>dk.trustworks.essentials</groupId>
    <artifactId>shared</artifactId>
</dependency>
```

**Dependencies from other modules**: None - this is the foundational module that other modules depend on.

## Core Utilities

### FailFast

**Class**: `dk.trustworks.essentials.shared.FailFast`

Enhanced validation - throws `IllegalArgumentException` with better error messages.

```java
import dk.trustworks.essentials.shared.FailFast;

// API
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
var items = FailFast.requireNonEmpty(items, "List must have items");
```

### Exceptions

**Class**: `dk.trustworks.essentials.shared.Exceptions`

```java
import dk.trustworks.essentials.shared.Exceptions;

// API
<T extends Throwable, R> R sneakyThrow(Throwable t) throws T
String getStackTrace(Throwable t)
Throwable getRootCause(Throwable e)
boolean doesStackTraceContainExceptionOfType(Throwable e, Class<?> exceptionType)
boolean isCriticalError(Throwable t)
<T extends Throwable> void rethrowIfCriticalError(T t)

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
    Exceptions.rethrowIfCriticalError(t);  // Rethrows if VirtualMachineError/ThreadDeath/LinkageError
    // Handle recoverable
}
```

### Lifecycle

**Interface**: `dk.trustworks.essentials.shared.Lifecycle`

```java
public interface Lifecycle {
    void start();       // Idempotent
    void stop();        // Idempotent
    boolean isStarted();
}
```

Used by EventStore, DurableQueues, SubscriptionManager, EventProcessor components.

### MessageFormatter

**Class**: `dk.trustworks.essentials.shared.MessageFormatter`

```java
import dk.trustworks.essentials.shared.MessageFormatter;
import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;

// Positional placeholders
String msg(String message, Object... placeholderValues)

// Named parameters
String bind(String message, NamedArgumentBinding... bindings)
String bind(String message, List<NamedArgumentBinding> bindings)
String bind(String message, Map<String, Object> bindings)

class NamedArgumentBinding {
    static NamedArgumentBinding arg(String name, Object value)
}

// Usage - Positional
String msg = MessageFormatter.msg("Operation {} failed with {}", id, code);

// Usage - Named
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

**Base package**: `dk.trustworks.essentials.shared.functional.tuple`

Immutable tuples supporting 0-4 elements.

```java
import dk.trustworks.essentials.shared.functional.tuple.Tuple;
import dk.trustworks.essentials.shared.functional.tuple.Pair;

// Factory methods
static Empty empty()
static <T1> Single<T1> of(T1 t1)
static <T1, T2> Pair<T1, T2> of(T1 t1, T2 t2)
static <T1, T2> Pair<T1, T2> fromEntry(Map.Entry<T1, T2> entry)
static <T1, T2, T3> Triple<T1, T2, T3> of(T1 t1, T2 t2, T3 t3)
static <T1, T2, T3, T4> Quad<T1, T2, T3, T4> of(T1 t1, T2 t2, T3 t3, T4 t4)

// Pair API
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
var swapped = Tuple.of("first", "second").swap();

// Mapping
var mapped = Tuple.of(10, 20).map((a, b) -> Tuple.of(a * 2, b * 2));
var mapped1 = Tuple.of("hello", "world").map1(String::toUpperCase);

// Stream operations
Map<String, Integer> map = stream
    .map(item -> Tuple.of(item.getName(), item.getCount()))
    .collect(Collectors.toMap(Pair::_1, Pair::_2));
```

**Triple and Quad** have similar APIs with `_1`, `_2`, `_3`, `_4` accessors and `map` methods.

### Either

**Class**: `dk.trustworks.essentials.shared.functional.tuple.Either`

Choice type representing one of two mutually exclusive values.

```java
import dk.trustworks.essentials.shared.functional.tuple.Either;

// Construction
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
Result<T1, T2> toResult()

// Usage - Error handling without exceptions
Either<String, Integer> result = parseNumber(input);

// Pattern matching with fold
String message = result.fold(
    error -> "Parse failed: " + error,
    value -> "Parsed: " + value
);

// Chaining operations
Either<String, Data> processed = validate(data)
    .flatMap2(d -> enrich(d))
    .flatMap2(e -> persist(e));
```

### Result

**Class**: `dk.trustworks.essentials.shared.functional.tuple.Result`

Specialized Either with semantic `success`/`error` naming. Extends `Either<ERROR, SUCCESS>`.

```java
import dk.trustworks.essentials.shared.functional.tuple.Result;

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
Result<ValidationError, Data> result = validate(data);

// Pattern matching
String message = result.fold(
    error -> "Failed: " + error.getMessage(),
    data -> "Success: " + data.getId()
);

// Chaining operations
Result<Error, Data> processed = validate(data)
    .flatMapSuccess(d -> enrich(d))
    .flatMapSuccess(e -> persist(e));

// Conditional execution
result.ifSuccess(data -> process(data));
result.ifError(error -> logError(error));
```

#### Either & Result: map vs flatMap

- **`map[1|2]`**: Transform value. Function returns plain value. Container re-wraps result.
- **`flatMap[1|2]`**: Chain logic. Function returns another Either/Result. Prevents nesting.

**Slot Targeting:**
- **`1` (Left/Error)**: `map1` / `flatMap1` - error recovery or translation
- **`2` (Right/Success)**: `map2` / `flatMap2` - "Happy Path"

| Method | Target | Short-circuits if... |
|--------|--------|----------------------|
| `map1` | `_1` | never |
| `flatMap1` | `_1` | `is_2()` |
| `map2` | `_2` | never |
| `flatMap2` | `_2` | `is_1()` |

### ComparableTuple

**Interface**: `dk.trustworks.essentials.shared.functional.tuple.comparable.ComparableTuple`

```java
public interface ComparableTuple<CONCRETE_TUPLE extends ComparableTuple<CONCRETE_TUPLE>>
    extends Tuple<CONCRETE_TUPLE>, Comparable<CONCRETE_TUPLE>
```

All tuple implementations implement `Comparable` for sorted collections.

### Checked Functional Interfaces

**Package**: `dk.trustworks.essentials.shared.functional`

Bridge checked exceptions to standard Java functional interfaces. Each has a `safe()` method that wraps checked exceptions in `CheckedExceptionRethrownException` (unchecked).

| Interface | Wraps/Returns   | Static Method                                                |
|-----------|-----------------|--------------------------------------------------------------|
| `CheckedRunnable` | `Runnable`      | `safe(CheckedRunnable)`, `safe(String ctx, CheckedRunnable)` |
| `CheckedSupplier<R>` | `Supplier<R>`   | `safe(CheckedSupplier<R>)`, `safe(String ctx, CheckedSupplier<R>)` |
| `CheckedConsumer<T>` | `Consumer<T>`   | `safe(CheckedConsumer<T>)`, `safe(String ctx, CheckedConsumer<T>)` |
| `CheckedFunction<T,R>` | `Function<T,R>` | `safe(CheckedFunction)`, `safe(String ctx, CheckedFunction)` |
| `CheckedBiFunction<T,U,R>` | `BiFunction`    | `safe(CheckedBiFunction)`, `safe(String ctx, CheckedBiFunction)` |
| `CheckedTripleFunction` | `TripleFunction`  | `safe(CheckedTripleFunction)`, `safe(String ctx, CheckedTripleFunction)` |
| `CheckedQuadFunction` | `QuadFunction`    | `safe(CheckedQuadFunction)`, `safe(String ctx, CheckedQuadFunction)` |

```java
// Usage
items.stream()
     .map(CheckedFunction.safe(item -> Files.readString(Path.of(item))))
     .toList();
```

Also provides `TripleFunction<T1,T2,T3,R>` and `QuadFunction<T1,T2,T3,T4,R>` for higher-arity functions.

---

## Collections & Streams

### Lists

**Class**: `dk.trustworks.essentials.shared.collections.Lists`

```java
import dk.trustworks.essentials.shared.collections.Lists;

// API
Stream<Pair<Integer, T>> toIndexedStream(List<T> list)
Optional<T> first(List<T> list)
Optional<T> last(List<T> list)
List<T> nullSafeList(List<T> list)
List<List<T>> partition(List<T> list, int batchSize)

// Usage
Lists.toIndexedStream(List.of("A", "B", "C"))
     .forEach(pair -> System.out.println(pair._1 + ": " + pair._2));
// Output: 0: A, 1: B, 2: C

// Partition for batch processing
List<List<Data>> batches = Lists.partition(allData, 100);
batches.forEach(batch -> processBatch(batch));
```

### Streams

**Class**: `dk.trustworks.essentials.shared.collections.Streams`

```java
import dk.trustworks.essentials.shared.collections.Streams;

// API
<R, T, U> Stream<R> zipOrderedAndEqualSizedStreams(
    Stream<T> streamT,
    Stream<U> streamU,
    BiFunction<T, U, R> zipFunction
)

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

**Class**: `dk.trustworks.essentials.shared.reflection.Reflector`

High-performance caching reflection wrapper. Cache is concurrent (thread-safe).

```java
import dk.trustworks.essentials.shared.reflection.Reflector;

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

**Package**: `dk.trustworks.essentials.shared.reflection`

| Class | Key Methods                                                                                           |
|-------|-------------------------------------------------------------------------------------------------------|
| `Accessibles` | `T accessible(T)`, `isAccessible(AccessibleObject)`                                                   |
| `BoxedTypes` | `isPrimitiveType(Class)`, `isBoxedType(Class)`, `Class boxedType(Class)`                              |
| `Classes` | `Class forName(String)`, `List<Class> superClasses(Class)`, `intcompareTypeSpecificity(Class, Class)` |
| `Constructors` | `List<Constructor> constructors(Class)`                                                               |
| `Fields` | `List<Field> fields(Class)`, `findField(Set, String, Class)`                                          |
| `Interfaces` | `List<Class> interfaces(Class)`                                                                       |
| `Methods` | `List<Method> methods(Class)`, `findMatchingMethod(Set, String, Class, Class...)`                     |
| `Parameters` | `List<Class> argumentTypes(Object...)`, `boolean parameterTypesMatches(Class[], Class[], boolean)`    |

### PatternMatchingMethodInvoker

**Base package**: `dk.trustworks.essentials.shared.reflection.invocation`

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
    InvokeMostSpecificTypeMatched,
    InvokeAllMatches
}

// Invoke methods
void invoke(ARGUMENT_ROOT_TYPE argument)
void invoke(ARGUMENT_ROOT_TYPE argument, NoMatchingMethodsHandler handler)
```

#### MethodPatternMatcher Interface

```java
interface MethodPatternMatcher<ARGUMENT_COMMON_ROOT_TYPE> {
    boolean isInvokableMethod(Method candidateMethod);
    Class<?> resolveInvocationArgumentTypeFromMethodDefinition(Method method);
    Class<?> resolveInvocationArgumentTypeFromObject(Object argument);
    void invokeMethod(Method methodToInvoke, Object argument,
                      Object invokeMethodOn, Class<?> resolvedArgumentType) throws Exception;
}
```

#### SingleArgumentAnnotatedMethodPatternMatcher

```java
// Constructor - with Class
SingleArgumentAnnotatedMethodPatternMatcher(
    Class<? extends Annotation> methodAnnotation,
    Class<?> argumentRootType
)

// Constructor - with GenericType
SingleArgumentAnnotatedMethodPatternMatcher(
    Class<? extends Annotation> methodAnnotation,
    GenericType<?> argumentRootType
)

// Matching logic:
// 1. Method has @methodAnnotation
// 2. Method has exactly 1 parameter
// 3. Parameter type is assignable from argumentRootType
```

#### Example

```java
import dk.trustworks.essentials.shared.reflection.invocation.PatternMatchingMethodInvoker;
import dk.trustworks.essentials.shared.reflection.invocation.SingleArgumentAnnotatedMethodPatternMatcher;
import dk.trustworks.essentials.shared.reflection.invocation.InvocationStrategy;

public class EventHandler {
    private final PatternMatchingMethodInvoker<Event> invoker;

    public EventHandler() {
        invoker = new PatternMatchingMethodInvoker<>(
            this,
            new SingleArgumentAnnotatedMethodPatternMatcher<>(Handler.class, Event.class),
            InvocationStrategy.InvokeMostSpecificTypeMatched
        );
    }

    public void handle(Event event) {
        invoker.invoke(event);
    }

    @Handler
    private void onEvent(Event event) { /* Fallback */ }

    @Handler
    private void onSpecificEvent(SpecificEvent event) { /* Specific */ }
}
```

### FunctionalInterfaceLoggingNameResolver

**Class**: `dk.trustworks.essentials.shared.reflection.FunctionalInterfaceLoggingNameResolver`

Extract readable names from lambda expressions and method references.

```java
import dk.trustworks.essentials.shared.reflection.FunctionalInterfaceLoggingNameResolver;

// API
static String resolveLoggingName(Object handler)

// Usage
var handler = (EventHandler) event -> process(event);
String name = FunctionalInterfaceLoggingNameResolver.resolveLoggingName(handler);
// Returns: "MyClass::EventHandler" instead of "$$Lambda$123"
```

Thread-safe with internal caching.

---

## Time & Measurement

### StopWatch

**Class**: `dk.trustworks.essentials.shared.time.StopWatch`

```java
import dk.trustworks.essentials.shared.time.StopWatch;

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
TimingWithResult<List<Data>> result = StopWatch.time(() -> loadData());
logger.info("Loaded {} items in {}", result.result.size(), result.duration);
```

⚠️ StopWatch instances are NOT thread-safe. Use separate instances per thread.

### MeasurementRecorder & MeasurementTaker

**Package**: `dk.trustworks.essentials.shared.measurement`

Pluggable interface for recording measurements - integrate with logging, Micrometer, or custom metrics systems.

```java
// MeasurementRecorder - core interface
public interface MeasurementRecorder {
    void record(MeasurementContext context, Duration duration);
}

// Built-in: LoggingMeasurementRecorder, MicrometerMeasurementRecorder

// MeasurementContext - metric metadata
var context = MeasurementContext.builder("processing.time")
    .description("Time to process data")
    .tag("type", "STANDARD")
    .build();

// MeasurementTaker - facade for multiple recorders
var taker = MeasurementTaker.builder()
    .addRecorder(loggingRecorder)
    .withOptionalMicrometerMeasurementRecorder(Optional.of(meterRegistry))
    .build();

// Fluent API
return taker.context("processing.time")
    .tag("type", type)
    .record(chain::proceed);
```

**LogThresholds**: Default thresholds (debug=25ms, info=100ms, warn=500ms, error=2000ms).

---

## Control Flow

### If Expression

**Class**: `dk.trustworks.essentials.shared.logic.IfExpression`

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

// Usage - Suppliers
var result = If(() -> exceedsThreshold(amount),
                () -> cancel(id))
            .Else(() -> accept(id));
```

### InterceptorChain

**Base package**: `dk.trustworks.essentials.shared.interceptor`

Generic chain-of-responsibility pattern.

```java
import dk.trustworks.essentials.shared.interceptor.Interceptor;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import dk.trustworks.essentials.shared.interceptor.InterceptorOrder;

// Base marker interface
public interface Interceptor {}

// Ordering annotation
public @interface InterceptorOrder {
    int value() default 10;
}
// Lower number = higher priority (executes first)

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

#### Example

```java
// Define operation-specific interceptor
public interface QueryInterceptor extends Interceptor {
    QueryResult intercept(Query query,
                         InterceptorChain<Query, QueryResult, QueryInterceptor> chain);
}

// Execute with chain
var result = InterceptorChain.newInterceptorChainForOperation(
    query,
    registeredInterceptors,  // Presorted by @InterceptorOrder
    (interceptor, chain) -> interceptor.intercept(query, chain),
    () -> executeQueryDirectly(query)
).proceed();

// Ordering example
@InterceptorOrder(1)  // Runs FIRST
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
```

---

## Concurrency

### ThreadFactoryBuilder

**Class**: `dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder`

```java
import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;

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

**Package**: `dk.trustworks.essentials.shared.messages`

Type-safe structured messages with hierarchical keys. Templates: `MessageTemplate0` through `MessageTemplate4` for 0-4 typed parameters.

```java
// Define templates
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

**Package**: `dk.trustworks.essentials.shared.security`

⚠️ **Purpose**: Role-based access control for **Essentials Admin UI only** - not for business application code. Secures DurableQueuesApi, FencedLockApi, SubscriptionApi, etc.

### Core Interfaces

| Type | Purpose |
|------|---------|
| `EssentialsSecurityProvider` | Framework-independent role validation |
| `EssentialsAuthenticatedUser` | Authenticated user with role checks |

**Built-in implementations**: `AllAccessSecurityProvider`, `NoAccessSecurityProvider`, `AllAccessAuthenticatedUser`, `NoAccessAuthenticatedUser`

### EssentialsSecurityRoles

| Role | Description |
|------|-------------|
| `ESSENTIALS_ADMIN` | Full access to all APIs |
| `LOCK_READER`, `LOCK_WRITER` | FencedLock read/write |
| `QUEUE_READER`, `QUEUE_WRITER`, `QUEUE_PAYLOAD_READER` | DurableQueues operations |
| `SUBSCRIPTION_READER`, `SUBSCRIPTION_WRITER` | Subscription management |
| `SCHEDULER_READER`, `POSTGRESQL_STATS_READER` | Scheduler and stats |

### EssentialsSecurityValidator

```java
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;

// Throws EssentialsSecurityException if unauthorized
validateHasEssentialsSecurityRole(provider, principal, QUEUE_READER);
validateHasAnyEssentialsSecurityRoles(provider, principal, QUEUE_READER, ESSENTIALS_ADMIN);

// Check without throwing
boolean allowed = hasEssentialsSecurityRole(provider, principal, QUEUE_READER);
```

---

## Additional Utilities

### GenericType

**Class**: `dk.trustworks.essentials.shared.types.GenericType`

Capture generic/parameterized type information at runtime.

```java
import dk.trustworks.essentials.shared.types.GenericType;

// Constructor
GenericType<T>()

// Methods
Class<T> getType()
Type getGenericType()

// Static utility
static Class<?> resolveGenericTypeOnSuperClass(Class<?> concreteType, int genericTypeArgumentIndex)

// Usage - Capture parameterized type
var genericType = new GenericType<List<Money>>(){};
// genericType.getType() returns List.class
// genericType.getGenericType() returns ParameterizedType with Money type argument

// Resolve generic from superclass
class ConcreteClass extends GenericClass<String, Integer> {}

Class<?> firstType = GenericType.resolveGenericTypeOnSuperClass(ConcreteClass.class, 0);
// Returns: String.class
```

### Network

**Class**: `dk.trustworks.essentials.shared.network.Network`

```java
static String hostName()  // Get local hostname
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
| Result | Thread-safe (immutable) |
| Message | Thread-safe (immutable) |
| Timing | Thread-safe (immutable) |
| MeasurementContext | Thread-safe (immutable) |
| StopWatch | **NOT thread-safe** (use separate instance per thread) |
| InterceptorChain | **NOT thread-safe** (single-use instance) |

---

## Gotchas

- ⚠️ FailFast throws `IllegalArgumentException`, not `NullPointerException`
- ⚠️ Either requires exactly ONE element to be non-null
- ⚠️ StopWatch instances are NOT thread-safe
- ⚠️ Checked functional `.safe()` methods wrap in `CheckedExceptionRethrownException` (unchecked)
- ⚠️ `Exceptions.sneakyThrow()` rethrows checked exception without declaring it
- ⚠️ Reflector caches reflection data - reuse instances for best performance
- ⚠️ Security abstraction is for **Essentials Admin UI APIs only**
- ⚠️ Measurement recording is synchronous

---

## See Also

- [README.md](../shared/README.md) - Full human-friendly documentation
- [LLM-types.md](./LLM-types.md) - Semantic types built on shared utilities
- [LLM-reactive.md](./LLM-reactive.md) - LocalEventBus and LocalCommandBus
- [LLM-foundation.md](./LLM-foundation.md) - UnitOfWork, FencedLock, DurableQueues
