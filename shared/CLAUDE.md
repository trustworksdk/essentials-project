## shared

Cross-cutting primitives used by every other Essentials module. Maven: `shared`.

## Package Structure

| Package | Contents |
|---|---|
| `dk.trustworks.essentials.shared` | `FailFast`, `Exceptions`, `Lifecycle`, `MessageFormatter` — top-level guards and utilities |
| `.functional` | `Checked*` wrappers (Consumer/Function/Supplier/Runnable/BiFunction/Triple/Quad), `tuple` sub-package |
| `.functional.tuple` | `Single`, `Pair`, `Triple`, `Quad`, `Either`, `Result`, `Empty` + `comparable/` variants |
| `.interceptor` | Generic interceptor chain (`InterceptorChain`, `DefaultInterceptorChain`, `Interceptor`, `@InterceptorOrder`) |
| `.measurement` | `MeasurementTaker` facade, `MeasurementRecorder` SPI, `MicrometerMeasurementRecorder`, `LoggingMeasurementRecorder`, `LogThresholds` |
| `.messages` | `MessageTemplate0`–`MessageTemplate4`, `MessageTemplates` marker interface, `Message` |
| `.reflection` | `Reflector`, `Fields`, `Methods`, `Constructors`, `Parameters`, `Interfaces`, `Classes`, `BoxedTypes`, `Accessibles` |
| `.reflection.invocation` | `PatternMatchingMethodInvoker`, `MethodPatternMatcher` SPI, `InvocationStrategy`, `InvocationTracker`, `SingleArgumentAnnotatedMethodPatternMatcher` |
| `.security` | `EssentialsSecurityProvider` SPI, `EssentialsSecurityRoles`, `EssentialsSecurityValidator`, `AllAccessSecurityProvider`, `NoAccessSecurityProvider` |
| `.time` | `StopWatch`, `Timing`, `TimingWithResult` |
| `.concurrent` | `ThreadFactoryBuilder` |
| `.collections` | `Lists`, `Streams` |
| `.logic` | `IfExpression`, `ElseIfExpression`, `IfThenElseLogic`, `IfPredicate` — fluent if/else DSL |
| `.network` | `Network` — local IP/hostname resolution |
| `.types` | `GenericType<T>` — captures parameterized type tokens at runtime |

## Key Classes

| Class | Internal Role |
|---|---|
| `FailFast` | All precondition checks across the codebase; throws `IllegalArgumentException` (not NPE) |
| `MessageFormatter` | SLF4J-style `{}` placeholder formatter used in error messages throughout |
| `Exceptions` | `sneakyThrow` + checked-exception unwrapping |
| `Lifecycle` | Idempotent `start()`/`stop()`/`isStarted()` contract — all stateful components implement this |
| `InterceptorChain` / `DefaultInterceptorChain` | Chain-of-responsibility used by event store, command bus, etc. Each chain instance is per-operation, not reusable. Ordering driven by `@InterceptorOrder` (default 10) |
| `PatternMatchingMethodInvoker` | Reflective dispatcher — scans target object methods at construction time, caches type→method map, then dispatches by argument type at runtime. Used for `@EventHandler`, `@CommandHandler` etc. |
| `MethodPatternMatcher` | SPI consumed by `PatternMatchingMethodInvoker` to decide invokability, arg-type resolution, and actual invocation |
| `MeasurementTaker` | Fan-out timing facade; delegates to 0..N `MeasurementRecorder`s. Supports Micrometer and logging out-of-box |
| `MeasurementRecorder` | SPI for custom metrics backends |
| `GenericType<T>` | Abstract superclass token — instantiate as anonymous subclass to capture `List<Money>` style type info |
| `MessageTemplate0`–`MessageTemplate4` | Typed, key-hierarchical message templates; supports `{0}`–`{3}` positional args |
| `EssentialsSecurityProvider` | SPI for role-based access checks; `AllAccessSecurityProvider` logs a warning when used |
| `InvocationTracker` | Optional hook in `PatternMatchingMethodInvoker` for observing every method dispatch |

## Test Structure

- Plain JUnit 5, no Docker required — all tests are pure unit tests
- Tests mirror package layout under `src/test/java`
- `MyMessageTemplates` in test package is a reference implementation of `MessageTemplates`
- `ReflectorTest`, `FieldsTest`, `MethodsTest` etc. exercise reflection utilities against inner test classes in the same file

## Extension Points

| SPI | Purpose |
|---|---|
| `MethodPatternMatcher<ROOT>` | Custom method selection/invocation logic for `PatternMatchingMethodInvoker` |
| `MeasurementRecorder` | Plug in any metrics backend (already: Micrometer, logging) |
| `EssentialsSecurityProvider` | Map framework roles to application auth system |
| `Interceptor` | Per-operation interceptors; annotate with `@InterceptorOrder` for position |
| `InvocationTracker` | Observe/measure every `PatternMatchingMethodInvoker` dispatch |
| `Lifecycle` | Implement on any stateful component; callers expect idempotent start/stop |

## Gotchas

- `FailFast.requireNonNull` throws `IllegalArgumentException`, NOT `NullPointerException` — don't assume NPE in catch blocks
- `Exceptions.sneakyThrow` bypasses checked exception compile-time enforcement — callers see unchecked but `catch (Throwable)` still needed in some reflective paths
- `PatternMatchingMethodInvoker` scans and caches methods at construction time; adding methods to handler class after construction has no effect
- `InvokeMostSpecificTypeMatched` vs `InvokeAllMatches`: specifying `Object` as root arg type is valid but `InvokeAllMatches` will match every declared handler — be intentional
- `Void.class` used as sentinel in `invokeMostSpecificTypeMatchCache` to cache "no match found" — `null` cannot be stored in `ConcurrentHashMap`
- `DefaultInterceptorChain` instances are NOT reusable; create one per operation call via `InterceptorChain.newInterceptorChainForOperation`
- `GenericType<T>` must be instantiated as anonymous subclass (`new GenericType<List<Money>>() {}`); direct instantiation loses type info and throws `IllegalStateException`
- `AllAccessSecurityProvider` logs `### Initializing AllAccessSecurityProvider ###` at INFO on startup — intentional prod warning
- `MessageFormatter` uses `{}` placeholders; `MessageTemplate` uses `{0}`, `{1}` positional — they are different formats, don't mix
